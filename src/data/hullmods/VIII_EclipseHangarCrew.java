package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;

/**
 * 日蚀舰载机编制（隐藏内置船插，仅 VIII_eclipse 自带）。
 * <p>
 * 效果：本舰的"需求人数"（必要船员，min crew）最终乘以 0，也就是舰载机不占用船员编制。
 * <p>
 * 【为什么改 getMinCrewMod 而不是别的东西】
 * 原版里"舰载机占用的船员"并没有独立的 stat，而是由各个船插以 flat 的形式加到舰船的
 * min crew 上，例如：
 * - ExpandedDeckCrew（扩展甲板人员）：每个机库 +20 人
 *   （{@code stats.getMinCrewMod().modifyFlat(id, numFighterBays * 20f)}）；
 * - ConvertedHangar（改装机库）：每个改装机库 +20 人（CREW_REQ）。
 * 舰船最终的需求人数由 {@code FleetMember.getMinCrew()} 计算：
 * {@code ceil(stats.getMinCrewMod().computeEffective(hullSpec.getMinCrew()))}，
 * 而 StatBonus.computeEffective 的算法是
 * {@code (base + base * percent/100 + flat) * mult}。
 * 因此对 getMinCrewMod() 做一次 modifyMult(id, 0f)，就能把舰体基础值、百分比加成、
 * 以及所有"每机库 / 每甲板"的 flat 船员加成一起归零——这正是"舰载机的需求人数乘以 0"。
 * <p>
 * 内置在 VIII_eclipse 的 builtInMods 里，并在 hull_mods.csv 中标记 hidden / hiddenEverywhere。
 */
public class VIII_EclipseHangarCrew extends BaseHullMod {

    /** 唯一生效的舰体 id，避免该船插被其它途径装到别的船上时误生效 */
    public static final String HULL_ID = "VIII_eclipse";

    /**
     * 每帧重建 stats 时都会被调用：把需求人数整体乘 0。
     * 这里额外校验舰体 id，因为这个船插是内置的，若以后有人把它当普通船插装到别的船上，
     * 也不应该改变那艘船的船员需求。
     */
    @Override
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
        if (stats == null) {
            return;
        }
        if (stats.getVariant() == null || stats.getVariant().getHullSpec() == null) {
            return;
        }
        if (!HULL_ID.equals(stats.getVariant().getHullSpec().getHullId())) {
            return;
        }
        stats.getMinCrewMod().modifyMult(id, 0.0F);
    }

    /**
     * 隐藏内置船插，不在装配界面手动显示描述（codex 中也不显示）。
     */
    @Override
    public boolean shouldAddDescriptionToTooltip(ShipAPI.HullSize hullSize, ShipAPI ship, boolean isForModSpec) {
        return false;
    }
}
