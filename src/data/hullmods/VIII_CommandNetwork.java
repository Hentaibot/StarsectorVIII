package data.hullmods;

import java.awt.Color;
import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.HullModFleetEffect;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.StatBonus;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * 机动作战指挥网络。
 * <p>
 * 【实现机制】效果分两层，因为战役与战斗读取的是两套完全独立的统计对象：
 * <ul>
 * <li><b>部署点</b>：由 {@code FleetMember.getDeploymentPointsCost()} 读取
 * （反编译 FleetMember：先读 {@code stats.getDynamic().getMod("deployment_points_mod")}
 * 再乘战机编队数量）。战役里由 {@link HullModFleetEffect} 的 onFleetSync /
 * advanceInCampaign 写成员统计；进入战斗后战役脚本不再推进，而战场上的部署/增援
 * 判定仍然读同一套 FleetMember 统计，所以战斗插件每帧再对「已部署 + 预备队 + 瘫痪」
 * 的成员重写一次，保证战斗内部署点同样保持降低。</li>
 * <li><b>峰值性能与机动性</b>：战斗中每艘舰是用 {@code o0OO.create(variant)} 新建的
 * 一套统计（反编译 specs/_.java 的造船工厂），和 FleetMember 的统计不是同一个对象
 * ⇒ 战斗侧必须直接改 {@code ship.getMutableStats()}，所以这里挂一个每帧运行的
 * 战斗插件，对玩家阵营里吨位符合的舰船做幂等重写（同一个 source id 重复写不会叠加），
 * 并在没有指挥舰时统一撤销。</li>
 * </ul>
 * 峰值性能用 {@code getPeakCRDuration()}（面板「峰值时间」），机动性沿用原版
 * AuxiliaryThrusters 的口径：加速度 / 减速度 / 转向加速度 / 最大转向角速度。
 * 指挥舰本身不享受这些加成（吨位是主力舰，且显式排除）。
 */
public class VIII_CommandNetwork extends BaseHullMod implements HullModFleetEffect {

    public static final String HULLMOD_ID = "VIII_CommandNetwork";

    /** 峰值性能与机动性的修正来源 id（战斗侧逐帧重写，靠它保持幂等） */
    private static final String SOURCE_ID = "VIII_CommandNetwork_effect";
    /** 部署点修正的来源 id（战役侧与战斗侧共用，重复写不会叠加） */
    private static final String DP_SOURCE = "VIII_CommandNetwork_dp";
    /** 战斗插件只装一次的标记（引擎 customData） */
    private static final String PLUGIN_KEY = "VIII_CommandNetwork_plugin";

    private static final String DP_STAT = "deployment_points_mod";

    /** 战役侧整表刷新节流（毫秒），避免每帧遍历所有舰队成员 */
    private static long lastCampaignUpdate = 0L;
    private static final long CAMPAIGN_UPDATE_INTERVAL = 1000L;

    // ------------------------------------------------------------------
    // 数值表：与描述文本里的表格保持一致
    // ------------------------------------------------------------------

    /** 部署点降低量（正数，实际写入 -值） */
    public static float getDeploymentPointReduction(HullSize size) {
        if (size == HullSize.CRUISER) {
            return 12.0F;
        }
        if (size == HullSize.DESTROYER) {
            return 8.0F;
        }
        if (size == HullSize.FRIGATE) {
            return 4.0F;
        }
        return 0.0F;
    }

    /** 峰值性能（峰值时间）提高的百分比 */
    public static float getPeakPerformanceBonus(HullSize size) {
        if (size == HullSize.CRUISER) {
            return 25.0F;
        }
        if (size == HullSize.DESTROYER) {
            return 30.0F;
        }
        if (size == HullSize.FRIGATE) {
            return 40.0F;
        }
        return 0.0F;
    }

    /** 机动性（四项）提高的百分比 */
    public static float getManeuverabilityBonus(HullSize size) {
        if (size == HullSize.CRUISER) {
            return 20.0F;
        }
        if (size == HullSize.DESTROYER) {
            return 25.0F;
        }
        if (size == HullSize.FRIGATE) {
            return 30.0F;
        }
        return 0.0F;
    }

    /** 是否属于"巡洋舰及以下"这一档 */
    public static boolean isAffectedSize(HullSize size) {
        return size == HullSize.CRUISER || size == HullSize.DESTROYER || size == HullSize.FRIGATE;
    }

    // ------------------------------------------------------------------
    // 战斗侧
    // ------------------------------------------------------------------

    /**
     * 成员的统计对象被重建时（战斗开始前的舰队 sync、换装、编队变化等）
     * 会重新走一遍本钩子：在这里把部署点/峰值/机动性重新写给全舰队成员，
     * 避免重建后丢加成（战斗内的部署点读取的正是这套 FleetMember 统计）。
     */
    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        FleetMemberAPI member = stats != null ? stats.getFleetMember() : null;
        if (member == null) {
            return;
        }
        FleetDataAPI data = member.getFleetData();
        if (data == null) {
            return;
        }
        CampaignFleetAPI fleet = data.getFleet();
        if (fleet == null || !fleet.isPlayerFleet()) {
            return;
        }
        applyCampaignEffects(fleet);
    }

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) {
            return;
        }
        if (engine.getCustomData().get(PLUGIN_KEY) == null) {
            engine.getCustomData().put(PLUGIN_KEY, Boolean.TRUE);
            engine.addPlugin(new CommandNetworkCombatPlugin());
        }
        applyCombatEffects(engine);
    }

    private static void applyCombatEffects(CombatEngineAPI engine) {
        if (engine == null) {
            return;
        }
        ShipAPI player = engine.getPlayerShip();
        int owner = player != null ? player.getOwner() : 0;

        CombatFleetManagerAPI manager = engine.getFleetManager(owner);
        boolean active = hasNetworkCarrier(manager) || hasNetworkCarrierInShips(engine, owner);

        List<ShipAPI> ships = engine.getShips();
        for (int i = 0; i < ships.size(); i++) {
            ShipAPI other = ships.get(i);
            if (other == null || other.getHullSpec() == null || other.getOwner() != owner) {
                continue;
            }
            HullSize size = other.getHullSpec().getHullSize();
            if (isNetworkCarrier(other, owner)) {
                applyCombatShipEffects(other, false, size);
                continue;
            }
            boolean apply = active && isAffectedSize(size) && other.isAlive() && !other.isHulk();
            applyCombatShipEffects(other, apply, size);
        }

        applyCombatFleetEffects(manager, active);
    }

    private static void applyCombatFleetEffects(CombatFleetManagerAPI manager, boolean active) {
        if (manager == null) {
            return;
        }
        applyMemberList(manager.getDeployedCopy(), active);
        applyMemberList(manager.getReservesCopy(), active);
        applyMemberList(manager.getDisabledCopy(), active);
    }

    private static void applyMemberList(List<FleetMemberAPI> members, boolean active) {
        if (members == null) {
            return;
        }
        for (int i = 0; i < members.size(); i++) {
            FleetMemberAPI member = members.get(i);
            if (member == null || member.getHullSpec() == null) {
                continue;
            }
            HullSize size = member.getHullSpec().getHullSize();
            if (isNetworkCarrierMember(member)) {
                applyMemberEffects(member, false, size);
                continue;
            }
            boolean apply = active && isAffectedSize(size) && !member.isMothballed();
            applyMemberEffects(member, apply, size);
        }
    }

    private static void applyCombatShipEffects(ShipAPI ship, boolean apply, HullSize size) {
        MutableShipStatsAPI stats = ship.getMutableStats();
        if (stats == null) {
            return;
        }
        if (!apply) {
            stats.getPeakCRDuration().unmodify(SOURCE_ID);
            stats.getAcceleration().unmodify(SOURCE_ID);
            stats.getDeceleration().unmodify(SOURCE_ID);
            stats.getTurnAcceleration().unmodify(SOURCE_ID);
            stats.getMaxTurnRate().unmodify(SOURCE_ID);
            return;
        }
        float peak = getPeakPerformanceBonus(size);
        float maneuver = getManeuverabilityBonus(size);
        stats.getPeakCRDuration().modifyPercent(SOURCE_ID, peak);
        stats.getAcceleration().modifyPercent(SOURCE_ID, maneuver);
        stats.getDeceleration().modifyPercent(SOURCE_ID, maneuver);
        stats.getTurnAcceleration().modifyPercent(SOURCE_ID, maneuver);
        stats.getMaxTurnRate().modifyPercent(SOURCE_ID, maneuver);
    }

    private static boolean hasNetworkCarrier(CombatFleetManagerAPI manager) {
        if (manager == null) {
            return false;
        }
        return containsCarrier(manager.getDeployedCopy())
                || containsCarrier(manager.getReservesCopy())
                || containsCarrier(manager.getDisabledCopy());
    }

    private static boolean containsCarrier(List<FleetMemberAPI> members) {
        if (members == null) {
            return false;
        }
        for (int i = 0; i < members.size(); i++) {
            FleetMemberAPI member = members.get(i);
            if (member != null && !member.isMothballed() && isNetworkCarrierMember(member)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNetworkCarrierInShips(CombatEngineAPI engine, int owner) {
        List<ShipAPI> ships = engine.getShips();
        for (int i = 0; i < ships.size(); i++) {
            if (isNetworkCarrier(ships.get(i), owner)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNetworkCarrier(ShipAPI ship, int owner) {
        if (ship == null || ship.getVariant() == null || ship.getOwner() != owner) {
            return false;
        }
        if (!ship.isAlive() || ship.isHulk()) {
            return false;
        }
        return ship.getVariant().hasHullMod(HULLMOD_ID);
    }

    private static boolean isNetworkCarrierMember(FleetMemberAPI member) {
        return member != null && member.getVariant() != null && member.getVariant().hasHullMod(HULLMOD_ID);
    }

    /** 每帧跑一次：维持战斗船的峰值时间/机动性，以及成员的部署点加成 */
    public static class CommandNetworkCombatPlugin extends BaseEveryFrameCombatPlugin {
        @Override
        public void advance(float amount, List<InputEventAPI> events) {
            CombatEngineAPI engine = Global.getCombatEngine();
            if (engine == null || engine.isPaused()) {
                return;
            }
            applyCombatEffects(engine);
        }
    }

    // ------------------------------------------------------------------
    // 战役侧：部署点、峰值时间、机动性写进 FleetMember 统计
    // ------------------------------------------------------------------

    @Override
    public boolean withAdvanceInCampaign() {
        return true;
    }

    @Override
    public boolean withOnFleetSync() {
        return true;
    }

    @Override
    public void advanceInCampaign(CampaignFleetAPI fleet) {
        if (fleet == null || !fleet.isPlayerFleet()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastCampaignUpdate < CAMPAIGN_UPDATE_INTERVAL) {
            return;
        }
        lastCampaignUpdate = now;
        applyCampaignEffects(fleet);
    }

    @Override
    public void onFleetSync(CampaignFleetAPI fleet) {
        applyCampaignEffects(fleet);
    }

    private static void applyCampaignEffects(CampaignFleetAPI fleet) {
        if (fleet == null || !fleet.isPlayerFleet() || fleet.getFleetData() == null) {
            return;
        }
        List<FleetMemberAPI> members = fleet.getFleetData().getMembersListCopy();
        boolean active = false;
        for (int i = 0; i < members.size(); i++) {
            FleetMemberAPI member = members.get(i);
            if (member == null || member.isMothballed()) {
                continue;
            }
            if (isNetworkCarrierMember(member)) {
                active = true;
                break;
            }
        }
        for (int i = 0; i < members.size(); i++) {
            FleetMemberAPI member = members.get(i);
            if (member == null || member.getHullSpec() == null) {
                continue;
            }
            HullSize size = member.getHullSpec().getHullSize();
            if (isNetworkCarrierMember(member)) {
                applyMemberEffects(member, false, size);
                continue;
            }
            applyMemberEffects(member, active && isAffectedSize(size), size);
        }
    }

    private static void applyMemberEffects(FleetMemberAPI member, boolean apply, HullSize size) {
        MutableShipStatsAPI stats = member.getStats();
        if (stats == null) {
            return;
        }
        StatBonus dpMod = stats.getDynamic().getMod(DP_STAT);
        if (!apply) {
            dpMod.unmodify(DP_SOURCE);
            stats.getPeakCRDuration().unmodify(SOURCE_ID);
            stats.getAcceleration().unmodify(SOURCE_ID);
            stats.getDeceleration().unmodify(SOURCE_ID);
            stats.getTurnAcceleration().unmodify(SOURCE_ID);
            stats.getMaxTurnRate().unmodify(SOURCE_ID);
            return;
        }
        float dp = getDeploymentPointReduction(size);
        float baseDp = stats.getSuppliesToRecover().getBaseValue();
        if (baseDp - dp < 1.0F) {
            dp = Math.max(0.0F, baseDp - 1.0F);
        }
        float peak = getPeakPerformanceBonus(size);
        float maneuver = getManeuverabilityBonus(size);
        dpMod.modifyFlat(DP_SOURCE, -dp);
        stats.getPeakCRDuration().modifyPercent(SOURCE_ID, peak);
        stats.getAcceleration().modifyPercent(SOURCE_ID, maneuver);
        stats.getDeceleration().modifyPercent(SOURCE_ID, maneuver);
        stats.getTurnAcceleration().modifyPercent(SOURCE_ID, maneuver);
        stats.getMaxTurnRate().modifyPercent(SOURCE_ID, maneuver);
    }

    // ------------------------------------------------------------------
    // 描述
    // ------------------------------------------------------------------

    @Override
    public boolean shouldAddDescriptionToTooltip(HullSize hullSize, ShipAPI ship, boolean isForModSpec) {
        return false;
    }

    /**
     * 添加自定义tooltip后置描述
     * 该方法用于在船体插件的UI描述中添加额外的说明文本和表格，向玩家展示该插件的效果机制和数值。
     */
    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, HullSize hullSize, final ShipAPI ship, float width, boolean isForModSpec) {
        float pad = 3f;
        float opad = 10f;
        Color h = Misc.getHighlightColor();

        tooltip.addPara("一组针对巡洋舰以及以下吨位舰船的作战数据处理接口，被设计用于管理战场的指挥网络并确保一切功能有条不紊，并根据战场的态势变化，重新优化指挥网络根据当前战况进行运算处理。", opad);
        tooltip.addSectionHeading("舰队内受影响的舰船", Alignment.MID, pad);
        if (isForModSpec || (ship == null && !Global.CODEX_TOOLTIP_MODE)) return;

        tooltip.setBgAlpha(0.9f);

        float damW = 87f;
        float empW = 87f;
        float fluxW = 87f;
        float sizeW = width - damW - empW - fluxW - 10f;

        tooltip.beginTable(Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(), Misc.getBrightPlayerColor(),
                20.0F, true, true, new Object[]{"舰船大小", sizeW, "部署点", damW, "峰值性能", empW, "机动性", fluxW});

        Color c = Misc.getHighlightColor();

        tooltip.addRow(Alignment.MID, c, Misc.getHullSizeStr(HullSize.CRUISER),
                Alignment.MID, c, "-12",
                Alignment.MID, c, "+25%",
                Alignment.MID, c, "+20%");
        tooltip.addRow(Alignment.MID, c, Misc.getHullSizeStr(HullSize.DESTROYER),
                Alignment.MID, c, "-8",
                Alignment.MID, c, "+30%",
                Alignment.MID, c, "+25%");
        tooltip.addRow(Alignment.MID, c, Misc.getHullSizeStr(HullSize.FRIGATE),
                Alignment.MID, c, "-4",
                Alignment.MID, c, "+40%",
                Alignment.MID, c, "+30%");

        tooltip.addTable("", 0, opad);

        tooltip.addSpacer(5f);
        tooltip.addPara("效果仅在舰队中存在装备本插件的舰船时生效；部署点在舰队编制界面与战场部署/增援时生效，峰值性能与机动性在战斗中生效。", opad);
        tooltip.addPara("峰值性能指舰船的峰值时间（秒），机动性指加速度、减速度、转向加速度与最大转向角速度。", pad, h);
    }
}
