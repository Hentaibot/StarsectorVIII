package data.hullmods;

import java.awt.Color;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.HullModFleetEffect;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.loading.VariantSource;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * 隐秘行动模组。
 * <p>
 * 【功能】
 * <ul>
 * <li>只能装在 {@code VIII_firebird_a}、{@code VIII_micrastur_a}、{@code VIII_banshee_a} 上。</li>
 * <li>装上后把该舰就地改成对应的 b 型（黑隐型）；把船插卸下则自动变回 a 型。</li>
 * <li>b 型舰装甲降低 40%。</li>
 * <li>按 b 型给整个玩家舰队降低「被侦测范围」：报丧女妖 b -40%、林隼 b -30%、火鸟 b -20%。
 * 不同型号之间可以叠加（最多 -90%），但同一型号只算一艘，多出来的不叠加。</li>
 * </ul>
 * 【实现要点】
 * <ul>
 * <li>改型号必须在 {@code applyEffectsBeforeShipCreation} 里做：那里能拿到
 * {@code stats.getFleetMember()}，并且 {@code FleetMember.setVariant(v, false, false)}
 * 的第三个参数为 false 时不会触发 {@code updateStats()}，因此不会递归。</li>
 * <li>为了不误伤本来就存在的 b 型舰，本模组只回收「由自己改造过」的舰船：
 * 改造时在玩家舰队的 memory 里给该成员记一个标记，卸下船插后凭标记换回 a 型。</li>
 * <li>舰队被侦测范围由 {@code CampaignFleet.updateCounts()} 汇总成员
 * {@code getStats().getSensorProfile()} 后再乘 {@code fleet.getStats().getSensorProfileMod()}，
 * 所以这里只改后者并调一次 {@link CampaignFleetAPI#updateCounts()} 立即刷新。</li>
 * </ul>
 */
public class VIII_StealthOps extends BaseHullMod implements HullModFleetEffect {

    /** 与 hull_mods.csv 的 id 保持一致。 */
    public static final String ID = "VIII_StealthOps";

    /** 装甲倍率：降低 40%。 */
    public static final float ARMOR_MULT = 0.6f;

    private static final String ARMOR_SOURCE = "VIII_StealthOps_armor";
    private static final String SENSOR_SOURCE = "VIII_StealthOps_sensor";
    private static final String CONVERTED_MEM_PREFIX = "$VIII_StealthOps_converted_";
    /** 改造过的变体 id 里带这个片段，跨存档也能认出「是我们改的」。 */
    private static final String VARIANT_ID_MARK = "_stealthops";

    /**
     * 隐藏标记船插的 id。转换型号时一并挂到舰上：主船插被卸下后它仍在变体上，
     * 引擎重建统计时会调用它的钩子，由它把舰体换回 a 型并自行移除。
     */
    private static final String MARKER_MOD_ID = "VIII_StealthMarker";

    /** 主船插 id（与 hull_mods.csv 的 id 一致）。 */
    private static final String MAIN_MOD_ID = "VIII_StealthOps";

    /** a 型 → b 型。 */
    private static final Map<String, String> CONVERSIONS = new HashMap<String, String>();

    /** b 型 → a 型（卸下船插时回退）。 */
    private static final Map<String, String> REVERSE = new HashMap<String, String>();

    /** b 型提供的「被侦测范围」降低比例。 */
    private static final Map<String, Float> PROFILE_BONUS = new HashMap<String, Float>();

    static {
        CONVERSIONS.put("VIII_firebird_a", "VIII_firebird_b");
        CONVERSIONS.put("VIII_micrastur_a", "VIII_micrastur_b");
        CONVERSIONS.put("VIII_banshee_a", "VIII_banshee_b");

        REVERSE.put("VIII_firebird_b", "VIII_firebird_a");
        REVERSE.put("VIII_micrastur_b", "VIII_micrastur_a");
        REVERSE.put("VIII_banshee_b", "VIII_banshee_a");

        PROFILE_BONUS.put("VIII_banshee_b", 0.40f);
        PROFILE_BONUS.put("VIII_micrastur_b", 0.30f);
        PROFILE_BONUS.put("VIII_firebird_b", 0.20f);
    }

    /** 诊断日志（转换路径一旦出问题，看 starsector.log 里的 [StealthOps] 行）。 */
    private static void log(String msg) {
        Global.getLogger(VIII_StealthOps.class).info("[StealthOps] " + msg);
    }

    /** 是否正在统计重建里（applyEffectsBeforeShipCreation）；此时不能反过来调 updateStats。 */
    private static boolean buildingStats = false;

    /** 舰队级同步节流（毫秒，按系统时钟，暂停时也能刷新）。 */
    private static long lastSyncMs = 0L;
    private static final long SYNC_INTERVAL_MS = 200L;

    // ------------------------------------------------------------------
    // 舰船统计钩子
    // ------------------------------------------------------------------

    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        if (stats == null) {
            return;
        }
        ShipVariantAPI variant = stats.getVariant();
        if (variant == null || variant.getHullSpec() == null) {
            return;
        }

        String hullId = variant.getHullSpec().getBaseHullId();

        // 1) a 型 + 装了本插 ⇒ 改成 b 型。
        String targetId = CONVERSIONS.get(hullId);
        if (targetId != null) {
            // 转换后这一轮统计仍旧是 a 型的，下一次 updateStats 才会按 b 型重算。
            attemptConversion(stats, hullId, targetId);
            return;
        }

        // 2) b 型 + 没装本插（且是本模组改造过的）⇒ 变回 a 型。
        String sourceId = REVERSE.get(hullId);
        if (sourceId == null) {
            return;
        }
        if (!variant.hasHullMod(ID)) {
            FleetMemberAPI owner = stats.getFleetMember();
            if (isMarkedConvertedVariant(variant) && owner != null) {
                // 战役模式/无舰队上下文里也能靠变体 id 标记认出来。
                revertMember(null, owner, variant, sourceId);
            } else {
                attemptRevert(stats, hullId, sourceId);
            }
            return;
        }

        // 3) b 型 + 装了本插 ⇒ 装甲降低 40%。
        if (!variant.hasHullMod(MARKER_MOD_ID)) {
            // 自愈：旧版本转换出来的舰没有标记船插，这里补上，
            // 否则以后卸下主船插时没人能触发回退。
            variant.addMod(MARKER_MOD_ID);
        }
        stats.getArmorBonus().modifyMult(ARMOR_SOURCE, ARMOR_MULT);
    }

    // ------------------------------------------------------------------
    // 舰队级钩子
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
        syncFleet(fleet);
    }

    /**
     * 每成员级钩子：舰队同步/每帧推进时调用，比舰队级更早、更可靠地完成型号纠正。
     * 装配界面里刚装上船插、成员变体已经带本插时，这里第一帧就会把舰体换掉。
     */
    @Override
    public void advanceInCampaign(FleetMemberAPI member, float amount) {
        if (member == null || member.isMothballed() || member.getFleetData() == null) {
            return;
        }
        CampaignFleetAPI fleet = member.getFleetData().getFleet();
        if (fleet == null || !fleet.isPlayerFleet()) {
            return;
        }
        ShipVariantAPI variant = member.getVariant();
        if (variant == null || variant.getHullSpec() == null) {
            return;
        }
        String hullId = variant.getHullSpec().getBaseHullId();

        String targetId = CONVERSIONS.get(hullId);
        if (targetId != null) {
            if (variant.hasHullMod(ID)) {
                convertMember(fleet, member, variant, targetId);
            }
            return;
        }
        String sourceId = REVERSE.get(hullId);
        if (sourceId != null && !variant.hasHullMod(ID)
                && (isMarkedConverted(fleet, member) || isMarkedConvertedVariant(variant))) {
            revertMember(fleet, member, variant, sourceId);
        }
    }

    @Override
    public void onFleetSync(CampaignFleetAPI fleet) {
        syncFleet(fleet);
    }

    private void syncFleet(CampaignFleetAPI fleet) {
        if (fleet == null || !fleet.isPlayerFleet()) {
            return;
        }

        FleetDataAPI data = fleet.getFleetData();
        if (data == null) {
            return;
        }

        // 1) 型号纠正：a+本插 ⇒ b；b 无本插但由本模组改造过 ⇒ a。
        List<FleetMemberAPI> members = data.getMembersListCopy();
        for (FleetMemberAPI member : members) {
            if (member == null || member.isMothballed()) {
                continue;
            }
            ShipVariantAPI variant = member.getVariant();
            if (variant == null || variant.getHullSpec() == null) {
                continue;
            }
            String hullId = variant.getHullSpec().getBaseHullId();

            String targetId = CONVERSIONS.get(hullId);
            if (targetId != null) {
                if (variant.hasHullMod(ID)) {
                    convertMember(fleet, member, variant, targetId);
                }
                continue;
            }

            String sourceId = REVERSE.get(hullId);
            if (sourceId != null && !variant.hasHullMod(ID)
                    && (isMarkedConverted(fleet, member) || isMarkedConvertedVariant(variant))) {
                revertMember(fleet, member, variant, sourceId);
            }
        }

        // 2) 舰队被侦测范围：不同型号各算一次，同一型号只算一艘。
        long now = System.currentTimeMillis();
        if (now - lastSyncMs < SYNC_INTERVAL_MS) {
            return;
        }
        lastSyncMs = now;

        Set<String> types = new HashSet<String>();
        for (FleetMemberAPI member : data.getMembersListCopy()) {
            if (member == null || member.getVariant() == null || member.getVariant().getHullSpec() == null) {
                continue;
            }
            ShipVariantAPI variant = member.getVariant();
            if (!variant.hasHullMod(ID)) {
                continue;
            }
            String hullId = variant.getHullSpec().getBaseHullId();
            if (PROFILE_BONUS.containsKey(hullId)) {
                types.add(hullId);
            }
        }

        float total = 0f;
        for (String type : types) {
            total += PROFILE_BONUS.get(type);
        }
        if (total > 0.90f) {
            total = 0.90f;
        }

        if (fleet.getStats() == null || fleet.getStats().getSensorProfileMod() == null) {
            return;
        }
        fleet.getStats().getSensorProfileMod().unmodify(SENSOR_SOURCE);
        if (total > 0f) {
            fleet.getStats().getSensorProfileMod().modifyMult(SENSOR_SOURCE, 1f - total);
        }
        fleet.updateCounts();
    }

    // ------------------------------------------------------------------
    // 型号转换 / 回退
    // ------------------------------------------------------------------

    private void attemptConversion(MutableShipStatsAPI stats, String sourceId, String targetId) {
        FleetMemberAPI member = stats.getFleetMember();
        if (member == null) {
            return;
        }
        ShipVariantAPI variant = member.getVariant();
        if (variant == null) {
            return;
        }
        CampaignFleetAPI fleet = null;
        FleetDataAPI data = member.getFleetData();
        if (data != null && data.getFleet() != null && data.getFleet().isPlayerFleet()) {
            fleet = data.getFleet();
        }
        // 没有玩家舰队（战役模式、任务舰队、独立装配）也要能切换型号，只跳过货舱退还与封存判断。
        if (fleet != null && member.isMothballed()) {
            return;
        }
        buildingStats = true;
        try {
            convertMember(fleet, member, variant, targetId);
        } finally {
            buildingStats = false;
        }
    }

    private void attemptRevert(MutableShipStatsAPI stats, String sourceId, String targetId) {
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
        if (!isMarkedConverted(fleet, member) && !isMarkedConvertedVariant(member.getVariant())) {
            // 不是本模组改造出来的 b 型，别动它。
            return;
        }
        if (member.isMothballed()) {
            return;
        }
        ShipVariantAPI variant = member.getVariant();
        if (variant == null) {
            return;
        }
        buildingStats = true;
        try {
            revertMember(fleet, member, variant, targetId);
        } finally {
            buildingStats = false;
        }
    }

    private static void convertMember(CampaignFleetAPI fleet, FleetMemberAPI member, ShipVariantAPI variant, String targetId) {
        ShipHullSpecAPI targetHull = Global.getSettings().getHullSpec(targetId);
        if (targetHull == null) {
            log("转换失败：找不到舰体 " + targetId);
            return;
        }
        ShipHullSpecAPI sourceHull = variant.getHullSpec();
        if (sourceHull == null || !CONVERSIONS.containsKey(sourceHull.getBaseHullId())) {
            // 已经不是 a 型了，别重复处理。
            return;
        }

        variant = detachFromStock(member, variant);

        if (fleet != null) {
            clearRemovedSlots(fleet, variant, sourceHull, targetHull);
        }

        // 就地改舰体：装配界面（ShipDisplay）与成员共用同一个变体对象，
        // 换成新对象会让界面继续持有旧对象，卸下船插时改动传不到成员身上。
        variant.setHullSpecAPI(targetHull);
        variant.setHullVariantId(targetId + VARIANT_ID_MARK + "_" + FleetMemberIds.of(member));
        if (!variant.hasHullMod(MARKER_MOD_ID)) {
            // 挂上隐藏标记：主船插被卸下后，靠它的钩子把舰体换回去。
            variant.addMod(MARKER_MOD_ID);
        }
        // 用同一个对象回写：只让引擎/装配界面知道变体 id 变了（界面据此刷新显示），
        // 但不换对象，所以界面上的改动仍然作用在同一个变体上。
        member.setVariant(variant, false, false);

        if (fleet != null) {
            markConverted(fleet, member);
        }

        refreshStats(member);
        log("已转换：" + FleetMemberIds.of(member) + " " + sourceHull.getBaseHullId() + " -> " + targetId);
    }

    private static void revertMember(CampaignFleetAPI fleet, FleetMemberAPI member, ShipVariantAPI variant, String targetId) {
        ShipHullSpecAPI targetHull = Global.getSettings().getHullSpec(targetId);
        if (targetHull == null) {
            log("回退失败：找不到舰体 " + targetId);
            return;
        }
        ShipHullSpecAPI sourceHull = variant.getHullSpec();
        if (sourceHull == null || !REVERSE.containsKey(sourceHull.getBaseHullId())) {
            // 已经不是 b 型了。
            return;
        }

        variant = detachFromStock(member, variant);

        // 回退时 b 型缺少的槽位在 a 型上本来就有，不需要清槽；就地换回 a 型即可。
        variant.setHullSpecAPI(targetHull);
        variant.setHullVariantId(targetId + VARIANT_ID_MARK + "_" + FleetMemberIds.of(member));
        if (variant.hasHullMod(MARKER_MOD_ID)) {
            variant.removeMod(MARKER_MOD_ID);
        }
        if (member != null) {
            // 战役模式/独立装配里可能根本没有 FleetMember，只改变体就够了。
            member.setVariant(variant, false, false);
        }

        if (fleet != null) {
            clearConvertedMark(fleet, member);
        }

        refreshStats(member);
        log("已回退：" + FleetMemberIds.of(member) + " " + sourceHull.getBaseHullId() + " -> " + targetId);
    }

    /**
     * 让成员按新舰体重算统计（装甲、船员、部署点等）。统计重建里不能递归调用，
     * 这时只打标记，交给下一次舰队同步/装配界面刷新去重算。
     */
    private static void refreshStats(FleetMemberAPI member) {
        if (member == null || buildingStats) {
            return;
        }
        member.updateStats();
    }

    /**
     * 变体如果还是 SpecStore 里的原始（stock）对象，就先克隆一份挂到成员上再改，
     * 免得把全局共享的 stock 变体改坏。已经是个体变体时直接返回，保持对象同一性。
     */
    private static ShipVariantAPI detachFromStock(FleetMemberAPI member, ShipVariantAPI variant) {
        if (variant == null || member == null) {
            return variant;
        }
        if (variant.getSource() != VariantSource.STOCK && !variant.isStockVariant()) {
            return variant;
        }
        ShipVariantAPI copy = variant.clone();
        copy.setSource(VariantSource.REFIT);
        copy.setHullVariantId(variant.getHullVariantId() + VARIANT_ID_MARK + "_" + member.getId());
        member.setVariant(copy, false, false);
        return copy;
    }

    /**
     * 把目标舰体上不存在的槽位清空，武器退回舰队货舱；战机会一并退回。
     */
    private static void clearRemovedSlots(CampaignFleetAPI fleet, ShipVariantAPI variant, ShipHullSpecAPI sourceHull, ShipHullSpecAPI targetHull) {
        if (variant.getWings() != null && !variant.getWings().isEmpty()) {
            List<String> wings = variant.getWings();
            for (String wingId : wings) {
                if (wingId == null) {
                    continue;
                }
                if (fleet.getCargo() != null) {
                    fleet.getCargo().addFighters(wingId, 1);
                }
            }
            wings.clear();
        }

        if (sourceHull.getAllWeaponSlotsCopy() == null) {
            return;
        }
        for (WeaponSlotAPI slot : sourceHull.getAllWeaponSlotsCopy()) {
            if (slot == null) {
                continue;
            }
            if (targetHull.getWeaponSlot(slot.getId()) != null) {
                continue;
            }
            String weaponId = variant.getWeaponId(slot.getId());
            if (weaponId == null) {
                continue;
            }
            if (fleet.getCargo() != null) {
                fleet.getCargo().addWeapons(weaponId, 1);
            }
            variant.clearSlot(slot.getId());
        }
    }

    // ------------------------------------------------------------------
    // 改造标记
    // ------------------------------------------------------------------

    /**
     * 由隐藏标记船插的钩子调用：主船插已经不在变体上时，把舰体从 b 型换回 a 型。
     * 这条路径不依赖舰队上下文，所以生涯、战役模式、独立装配里都成立。
     */
    static void markerTick(FleetMemberAPI member, ShipVariantAPI statsVariant) {
        ShipVariantAPI variant = statsVariant;
        FleetMemberAPI owner = member;
        if (owner != null && owner.getVariant() != null) {
            variant = owner.getVariant();
        }
        if (variant == null) {
            return;
        }
        if (variant.hasHullMod(MAIN_MOD_ID)) {
            // 主船插还在，正常状态，交给它自己处理。
            return;
        }
        if (!variant.hasHullMod(MARKER_MOD_ID)) {
            return;
        }
        ShipHullSpecAPI hull = variant.getHullSpec();
        if (hull == null) {
            return;
        }
        String aHullId = REVERSE.get(hull.getBaseHullId());
        if (aHullId == null) {
            // 已经不是 b 型了，标记没用了。
            variant.removeMod(MARKER_MOD_ID);
            return;
        }
        boolean previous = buildingStats;
        buildingStats = true;
        try {
            revertMember(null, owner, variant, aHullId);
        } finally {
            buildingStats = previous;
        }
        if (variant.hasHullMod(MARKER_MOD_ID)) {
            variant.removeMod(MARKER_MOD_ID);
        }
        if (owner != null) {
            FleetDataAPI data = owner.getFleetData();
            if (data != null) {
                // 若这次回退发生在统计重建中，让舰队再同步一次，按 a 型重算装甲/部署点。
                data.setSyncNeeded();
            }
        }
    }

    /** 记录与日志用：没有成员时退回变体 id，避免空指针。 */
    private static final class FleetMemberIds {
        private static String of(FleetMemberAPI member) {
            if (member != null && member.getId() != null) {
                return member.getId();
            }
            return "unknown";
        }
    }

    private static String markKey(FleetMemberAPI member) {
        return CONVERTED_MEM_PREFIX + member.getId();
    }

    private static void markConverted(CampaignFleetAPI fleet, FleetMemberAPI member) {
        if (fleet.getMemoryWithoutUpdate() == null) {
            return;
        }
        fleet.getMemoryWithoutUpdate().set(markKey(member), Boolean.TRUE);
    }

    private static boolean isMarkedConvertedVariant(ShipVariantAPI variant) {
        if (variant == null) {
            return false;
        }
        String variantId = variant.getHullVariantId();
        return variantId != null && variantId.contains(VARIANT_ID_MARK);
    }

    private static boolean isMarkedConverted(CampaignFleetAPI fleet, FleetMemberAPI member) {
        if (fleet.getMemoryWithoutUpdate() == null) {
            return false;
        }
        return Boolean.TRUE.equals(fleet.getMemoryWithoutUpdate().get(markKey(member)));
    }

    private static void clearConvertedMark(CampaignFleetAPI fleet, FleetMemberAPI member) {
        if (fleet.getMemoryWithoutUpdate() == null) {
            return;
        }
        fleet.getMemoryWithoutUpdate().unset(markKey(member));
    }

    // ------------------------------------------------------------------
    // 装配界面
    // ------------------------------------------------------------------

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        if (ship == null || ship.getVariant() == null || ship.getVariant().getHullSpec() == null) {
            return false;
        }
        String hullId = ship.getVariant().getHullSpec().getBaseHullId();
        if (CONVERSIONS.containsKey(hullId)) {
            return true;
        }
        // 已经改造成 b 型的舰船要允许保留船插，否则改造后会立刻被判为非法而自动卸下。
        return PROFILE_BONUS.containsKey(hullId) && ship.getVariant().hasHullMod(ID);
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        if (ship == null) {
            return "无法识别该舰船。";
        }
        return "只能安装在黑隐特勤舰、黑隐护卫舰、黑隐巡洋舰的原型上。";
    }

    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, HullSize hullSize, ShipAPI ship, float width, boolean isForModSpec) {
        float pad = 8f;
        Color hl = Misc.getHighlightColor();
        Color gray = Misc.getGrayColor();

        tooltip.addPara("安装后将该舰切换为对应的黑隐型号，卸下则自动变回原型：", pad, gray);
        tooltip.addPara("  火鸟常规型切到黑隐型，林隼常规型切到黑隐型，报丧女妖常规型切到黑隐型。", 2f, gray);

        tooltip.addPara("黑隐型号的装甲降低 %s。", pad, gray, hl, "40%");
        tooltip.addPara("舰队被侦测范围（不同型号可以叠加，同一型号只算一艘）：", pad, gray);
        tooltip.addPara("  报丧女妖减少 %s", 2f, gray, hl, "40%");
        tooltip.addPara("  林隼减少 %s", 0f, gray, hl, "30%");
        tooltip.addPara("  火鸟减少 %s", 0f, gray, hl, "20%");
        tooltip.addPara("  三种型号齐全时共减少 %s（上限）。", 2f, gray, hl, "90%");

        tooltip.addSectionHeading("当前舰船", Alignment.MID, pad);
        if (ship != null && ship.getVariant() != null && ship.getVariant().getHullSpec() != null) {
            String hullId = ship.getVariant().getHullSpec().getBaseHullId();
            String target = CONVERSIONS.get(hullId);
            if (target != null) {
                tooltip.addPara("  将切换为：%s", 2f, gray, hl, target);
            } else {
                tooltip.addPara("  已是黑隐型号：%s", 2f, gray, hl, hullId);
            }
        }
    }
}
