package data.hullmods;

import java.awt.Color;
import java.util.List;

import org.magiclib.subsystems.MagicSubsystemsManager;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.HullModFleetEffect;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

import data.scripts.shipsystems.subsystems.VIII_CelestineBurnDriveSubsystem;

/**
 * 第八战斗群 舰队后勤统筹协议。
 *
 * 只要玩家舰队中存在一艘未封存、装备了本船插的舰船，整支舰队（包括没有装备本船插的舰船）
 * 都会获得以下增益：
 *   1. 每月维护所需补给 -10%
 *   2. 燃料消耗（每光年）-10%
 *   3. 每日战备值（CR）恢复速率 +20%
 *
 * 为什么不用 EveryFrameScript 挂常驻脚本改属性（旧做法，面板不显示数字）：
 * 舰船属性面板（com.fs.starfarer.campaign.ui.trade.new）显示前会强制舰队同步，
 * 而 FleetMember.updateStats() 每次都会用 o0OO.create(variant) 新建一份 MutableShipStatsAPI，
 * 再按 variant 上的船插逐个调用 applyEffectsBeforeShipCreation 重新叠加。
 * 也就是说：任何“在外部直接改 member.getStats()”写下的修正，都会在下一次属性重建时被彻底丢弃，
 * 面板读到的永远是基准值（修正还在旧对象上，于是只有实际结算生效、面板不显示）。
 *
 * 本实现因此改用原版自带的舰队级钩子 HullModFleetEffect（vanilla 的 PhaseField / HighResSensors 同款）：
 *   - applyEffectsBeforeShipCreation：装备本船插的舰船自身在每次属性重建时立刻叠加修正；
 *   - onFleetSync：每次舰队同步（打开舰船数据面板时面板自己会触发一次全量同步）之后，
 *     把修正重新压到舰队内每个成员“刚刚重建好的”属性对象上，面板随即读到的就是修正后的数值；
 *   - advanceInCampaign(fleet)：玩家舰队每帧调用，保证属性重建之后、任何结算读取之前修正已经在位。
 *
 * 旧做法还有一个问题：MutableFleetStatsAPI 里没有“舰队级维护/补给”统计，所以只能逐个成员改；
 * 这里同样逐个成员改，只是改的时机变成“每次属性重建之后”，因此面板与实际结算都一致。
 */
public class VIII_FleetLogistics extends BaseHullMod implements HullModFleetEffect {

    /** 船插 id，必须与 hull_mods.csv 的 id 列一致 */
    public static final String HULLMOD_ID = "VIII_FleetLogistics";

    /** 修正来源标识，用于 MutableStat.unmodify(String) 精确撤销 */
    public static final String MOD_SOURCE = "VIII_FleetLogistics";

    /** 唯一会获得"烈焰驱动器"子系统的舰体 id */
    public static final String HULL_ID_CELESTINE = "VIII_Celestine";

    /** 不享受"每月维护补给 -10%"的舰体 id（其余增益照旧） */
    public static final String HULL_ID_ADVENTURER = "VIII_adventurer";

    /** 全舰队每月维护补给降低比例（10%） */
    public static final float SUPPLY_REDUCTION = 0.10F;

    /** 全舰队燃料消耗降低比例（10%） */
    public static final float FUEL_REDUCTION = 0.10F;

    /** 全舰队每日 CR 恢复速率提升比例（20%） */
    public static final float CR_RECOVERY_BONUS = 0.20F;

    /**
     * 属性重建时调用：装备本船插的舰船自己也要吃增益（它也是舰队的一员）。
     * 把修正写进“本次新建的属性对象”，面板重建后直接就能显示。
     */
    @Override
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
        if (stats == null) {
            return;
        }
        FleetMemberAPI member = stats.getFleetMember();
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
        if (!hasActiveCarrier(fleet)) {
            return;
        }
        applyToStats(stats);
    }

    /**
     * 舰船创建完成时调用：为 VIII_Celestine 安装 MagicLib 子系统"烈焰驱动器"，
     * 该子系统直接复用原版舰船系统 burndrive 的脚本（BurnDriveStats），
     * 因此推进效果、名称、图标、状态文字都沿用原版；节奏则是定制的：
     * 2 秒前摇、5 秒持续、1 秒后摇，开启期间自动加速并锁死减速。
     * <p>
     * 除 VIII_Celestine 以外的舰船一律不安装。
     * MagicLib 以"子系统类"为 key 去重，重复调用不会装出两个。
     */
    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        if (ship == null || ship.getHullSpec() == null) {
            return;
        }
        if (!HULL_ID_CELESTINE.equals(ship.getHullSpec().getHullId())) {
            return;
        }
        try {
            MagicSubsystemsManager.addSubsystemToShip(ship,
                    new VIII_CelestineBurnDriveSubsystem(ship));
        } catch (Exception e) {
            Global.getLogger(VIII_FleetLogistics.class).error(
                    "为 VIII_Celestine 添加烈焰驱动器子系统失败: " + e.getMessage(), e);
        }
    }

    /**
     * 舰队同步结束时调用（舰队编成、船插、属性变化都会触发；打开舰船数据面板时也会触发）。
     * 此时所有成员的属性对象刚被重建，重新压上修正，面板读到的就是修正后的数值。
     */
    @Override
    public void onFleetSync(CampaignFleetAPI fleet) {
        applyToFleet(fleet);
    }

    /**
     * 玩家舰队每帧调用一次。属性对象随时可能被游戏重建，这里做一次“发现缺失就补上”的巡检，
     * 保证维护补给、CR 恢复、燃料这些随时会被读取的数值一直是修正后的。
     */
    @Override
    public void advanceInCampaign(CampaignFleetAPI fleet) {
        applyToFleet(fleet);
    }

    /**
     * 是否需要“每帧”调用 advanceInCampaign(CampaignFleetAPI)。
     */
    @Override
    public boolean withAdvanceInCampaign() {
        return true;
    }

    /**
     * 是否需要舰队同步回调 onFleetSync。
     */
    @Override
    public boolean withOnFleetSync() {
        return true;
    }

    /**
     * 船插详细说明（数字写在代码里，避免 CSV 与代码两处维护）。
     */
    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, ShipAPI.HullSize hullSize, ShipAPI ship, float width, boolean isForModSpec) {
        float pad = 3.0F;
        float opad = 10.0F;
        Color h = Misc.getHighlightColor();
        Color good = Misc.getPositiveHighlightColor();
        Color gray = Misc.getGrayColor();

        // 按具体舰体决定显示哪些条目：VIII_adventurer 不显示维护补给那一条
        boolean adventurer = ship != null && ship.getHullSpec() != null
                && HULL_ID_ADVENTURER.equals(ship.getHullSpec().getHullId());
        boolean celestine = ship != null && ship.getHullSpec() != null
                && HULL_ID_CELESTINE.equals(ship.getHullSpec().getHullId());

        tooltip.addSectionHeading("舰队后勤", Alignment.MID, opad);
        tooltip.addPara("舰队中只要有 %s 艘未封存、且装备了本船插的舰船，整支舰队就会获得下列增益。",
                pad, h, "1");
        if (!adventurer) {
            tooltip.addPara("全舰队每月维护所需补给 %s 。", pad, good, "-" + formatPercent(SUPPLY_REDUCTION));
        }
        tooltip.addPara("全舰队燃料消耗 %s 。", pad, good, "-" + formatPercent(FUEL_REDUCTION));
        tooltip.addPara("全舰队每日战备值（CR）恢复速率 %s 。", pad, good, "+" + formatPercent(CR_RECOVERY_BONUS));
        if (celestine) {
            tooltip.addPara("本舰体额外获得 MagicLib 子系统：%s（按 %s 启动，%s 秒前摇、%s 秒持续、%s 秒后摇；期间自动加速、无法减速）。",
                    pad, h, "烈焰驱动器",
                    VIII_CelestineBurnDriveSubsystem.ACTIVATION_KEY,
                    "" + (int) VIII_CelestineBurnDriveSubsystem.IN_DURATION,
                    "" + (int) VIII_CelestineBurnDriveSubsystem.ACTIVE_DURATION,
                    "" + (int) VIII_CelestineBurnDriveSubsystem.OUT_DURATION);
        }
        tooltip.addPara("增益作用于舰队内的所有舰船，包括没有装备本船插的舰船；同时装备多艘不会叠加。",
                pad, gray);
    }

    /**
     * 把 0.10 这样的比例格式化成 "10%"。
     */
    private static String formatPercent(float value) {
        return "" + Math.round(value * 100.0F) + "%";
    }

    /**
     * 对玩家舰队的每个成员压上/撤销修正。非玩家舰队、数据不全时直接返回。
     */
    private static void applyToFleet(CampaignFleetAPI fleet) {
        if (fleet == null || !fleet.isPlayerFleet()) {
            return;
        }
        FleetDataAPI data = fleet.getFleetData();
        if (data == null) {
            return;
        }
        boolean active = hasActiveCarrier(fleet);
        List<FleetMemberAPI> members = data.getMembersListCopy();
        for (FleetMemberAPI member : members) {
            if (member == null) {
                continue;
            }
            MutableShipStatsAPI stats = member.getStats();
            if (stats == null) {
                continue;
            }
            if (active) {
                // 已经压在“当前这份属性对象”上就跳过（属性重建后这里会变成 false，于是补上）。
                // 用 CR 恢复这条做哨兵：它对所有成员都会挂上（维护补给那条对 VIII_adventurer 不挂）。
                if (!stats.getBaseCRRecoveryRatePercentPerDay().getMultMods().containsKey(MOD_SOURCE)) {
                    applyToStats(stats);
                }
            } else if (stats.getBaseCRRecoveryRatePercentPerDay().getMultMods().containsKey(MOD_SOURCE)) {
                clearFromStats(stats);
            }
        }
    }

    /**
     * 把三项修正压到一份舰船属性上。同一 source 重复 modifyMult 是覆盖而不是叠加，
     * 所以即使被多次调用（属性重建钩子 + 舰队同步 + 每帧巡检）也不会算重。
     */
    private static void applyToStats(MutableShipStatsAPI stats) {
        // VIII_adventurer 不享受维护补给减免：这条加成直接不挂（其余两条照旧）
        if (isAdventurer(stats)) {
            stats.getSuppliesPerMonth().unmodify(MOD_SOURCE);
        } else {
            stats.getSuppliesPerMonth().modifyMult(MOD_SOURCE, 1.0F - SUPPLY_REDUCTION);
        }
        stats.getBaseCRRecoveryRatePercentPerDay().modifyMult(MOD_SOURCE, 1.0F + CR_RECOVERY_BONUS);
        stats.getFuelUseMod().modifyMult(MOD_SOURCE, 1.0F - FUEL_REDUCTION);
    }

    /**
     * 这份属性所属的舰船是否是 VIII_adventurer（该舰体不享受维护补给减免）。
     *
     * @param stats 舰船属性
     * @return true 表示不挂维护补给修正
     */
    private static boolean isAdventurer(MutableShipStatsAPI stats) {
        if (stats == null) {
            return false;
        }
        FleetMemberAPI member = stats.getFleetMember();
        if (member == null) {
            return false;
        }
        return HULL_ID_ADVENTURER.equals(member.getHullId());
    }

    /**
     * 撤销三项修正。
     */
    private static void clearFromStats(MutableShipStatsAPI stats) {
        stats.getSuppliesPerMonth().unmodify(MOD_SOURCE);
        stats.getBaseCRRecoveryRatePercentPerDay().unmodify(MOD_SOURCE);
        stats.getFuelUseMod().unmodify(MOD_SOURCE);
    }

    /**
     * 舰队中是否存在“未封存且装备了本船插”的舰船。
     */
    private static boolean hasActiveCarrier(CampaignFleetAPI fleet) {
        if (fleet == null || fleet.getFleetData() == null) {
            return false;
        }
        List<FleetMemberAPI> members = fleet.getFleetData().getMembersListCopy();
        for (FleetMemberAPI member : members) {
            if (member == null || member.isMothballed()) {
                continue;
            }
            if (member.getVariant() != null && member.getVariant().hasHullMod(HULLMOD_ID)) {
                return true;
            }
        }
        return false;
    }
}
