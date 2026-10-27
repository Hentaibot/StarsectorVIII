// 包路径：远行星号游戏API实现中的战斗威胁包
package data.hullmods;

// 导入全局游戏实例类
import com.fs.starfarer.api.Global;
// 导入基础船体插件抽象类
import com.fs.starfarer.api.combat.BaseHullMod;
// 导入战斗引擎接口
import com.fs.starfarer.api.combat.CombatEngineAPI;
// 导入可变舰船属性统计接口
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
// 导入舰船接口
import com.fs.starfarer.api.combat.ShipAPI;
// 导入舰船尺寸枚举
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
// 导入属性统计常量（用于动态属性修改）
import com.fs.starfarer.api.impl.campaign.ids.Stats;
// 导入UI工具类接口
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
// 导入杂项工具类
import com.fs.starfarer.api.util.Misc;
import data.scripts.VIII_threat.VIII_ThreatCombatStrategyForBothSidesPlugin;
import data.scripts.VIII_threat.VIII_ThreatShipReclamationScript;
import data.scripts.VIII_threat.VIII_ThreatCombatStrategyAI;
import data.scripts.shipsystems.VIII_ConstructionSwarmSystemScript;

// 导入AWT颜色类
import java.awt.*;

/**
 * 管理威胁派系舰船特殊属性的隐藏船体插件。
 * @author Alex
 */
public class VIII_NanoFragments extends BaseHullMod {

    // 自定义标签：舰船正在被回收（用于残骸回收逻辑）
    public static String SHIP_BEING_RECLAIMED = "VIII_ship_being_reclaimed";

    // 特定船体ID：巢穴单位
    public static String HIVE_UNIT = "VIII_array";
    // 特定船体ID：制造者单位
    public static String FABRICATOR_UNIT = "VIII_indomitable";

    // 自动瞄准精度加成（固定值1，表示最高精度）
    public static float AIM_BONUS = 1f;
    // 导弹制导加成（固定值1，表示最高制导）
    public static float MISSILE_GUIDANCE_BONUS = 1f;
    // CR（战备）上限加成百分比（30%）
    public static float CR_BONUS = 30f;
    // 传感器轮廓倍率（0，表示完全隐形）
    public static float SENSOR_PROFILE_MULT = 0f;
    // 电子战惩罚倍率（0.5，表示惩罚减半）
    public static float EW_PENALTY_MULT = 0.5f;

    // 被注释的旧值：HIVE_UNIT_REGEN_RATE_MULT = 8f; HIVE_UNIT_SWARM_SIZE_MULT = 2f;
    // 巢穴单位的碎片重生率倍率（2倍）
    public static float HIVE_UNIT_REGEN_RATE_MULT = 3f;
    // 巢穴单位的碎片群规模倍率（4倍）
    public static float HIVE_UNIT_SWARM_SIZE_MULT = 5f;
    // 制造者单位的碎片重生率倍率（1倍，不变）
    public static float FABRICATOR_UNIT_REGEN_RATE_MULT = 1f;
    // 制造者单位的碎片群规模倍率（2倍）
    public static float FABRICATOR_UNIT_SWARM_SIZE_MULT = 2f;

    // 模块（武器/引擎）伤害承受倍率（0.5，减伤50%）
    public static float MODULE_DAMAGE_TAKEN_MULT = 0.5f;
    // EMP伤害承受倍率（0.5，减伤50%）
    public static float EMP_DAMAGE_TAKEN_MULT = 0.5f;

    /**
     * 在舰船创建之前应用效果（修改舰船基础属性）
     * @param hullSize 舰船尺寸
     * @param stats    可变舰船属性统计
     * @param id       效果ID（用于标识此插件）
     */
    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        // 确保建造群系统脚本的静态数据已初始化（用于碎片消耗等）
        VIII_ConstructionSwarmSystemScript.inits();

        // 自动瞄准精度加成（平坦加成）
        stats.getAutofireAimAccuracy().modifyFlat(id, AIM_BONUS);
        // 导弹制导加成（平坦加成）
        stats.getMissileGuidance().modifyFlat(id, MISSILE_GUIDANCE_BONUS);
        // 最大战斗就绪度（CR）加成：30%（CR_BONUS是百分数，除以100转换为小数）
        stats.getMaxCombatReadiness().modifyFlat(id, CR_BONUS * 0.01f);

        // 传感器轮廓倍率修改（设为0，几乎不可探测）
        stats.getSensorProfile().modifyMult(id, SENSOR_PROFILE_MULT);

        // 电子战惩罚倍率修改（设为0.5，惩罚减半）
        stats.getDynamic().getMod(Stats.ELECTRONIC_WARFARE_PENALTY_MOD).modifyMult(id, EW_PENALTY_MULT);

        // EMP伤害承受倍率（减伤50%）
        stats.getEmpDamageTakenMult().modifyMult(id, EMP_DAMAGE_TAKEN_MULT);
        // 引擎伤害承受倍率（减伤50%）
        stats.getEngineDamageTakenMult().modifyMult(id, MODULE_DAMAGE_TAKEN_MULT);
        // 武器伤害承受倍率（减伤50%）
        stats.getWeaponDamageTakenMult().modifyMult(id, MODULE_DAMAGE_TAKEN_MULT);
        // 允许在火力下修复模块（武器/引擎）
        stats.getDynamic().getMod(Stats.CAN_REPAIR_MODULES_UNDER_FIRE).modifyFlat(id, 1f);

        // 如果舰船变种不为空且船体ID是巢穴单位
        if (stats.getVariant() != null && stats.getVariant().getHullSpec().getHullId().equals(HIVE_UNIT)) {
            // 巢穴单位：碎片重生率倍率乘以 HIVE_UNIT_REGEN_RATE_MULT（2倍）
            stats.getDynamic().getStat(Stats.FRAGMENT_SWARM_RESPAWN_RATE_MULT).modifyMult(id, HIVE_UNIT_REGEN_RATE_MULT);
            // 巢穴单位：碎片群规模倍率乘以 HIVE_UNIT_SWARM_SIZE_MULT（4倍）
            stats.getDynamic().getMod(Stats.FRAGMENT_SWARM_SIZE_MOD).modifyMult(id, HIVE_UNIT_SWARM_SIZE_MULT);
        }
        if (stats.getVariant() != null && stats.getVariant().getHullSpec().getHullId().equals("VIII_blizzard")) {
            // 巢穴单位：碎片重生率倍率乘以 HIVE_UNIT_REGEN_RATE_MULT（2倍）
            stats.getDynamic().getStat(Stats.FRAGMENT_SWARM_RESPAWN_RATE_MULT).modifyMult(id, 3f);
            // 巢穴单位：碎片群规模倍率乘以 HIVE_UNIT_SWARM_SIZE_MULT（4倍）
            stats.getDynamic().getMod(Stats.FRAGMENT_SWARM_SIZE_MOD).modifyMult(id, 3f);
        }
        // 否则如果船体ID是制造者单位
        else if (stats.getVariant() != null && stats.getVariant().getHullSpec().getHullId().equals(FABRICATOR_UNIT)) {
            // 制造者单位：碎片重生率倍率乘以 FABRICATOR_UNIT_REGEN_RATE_MULT（1倍）
            stats.getDynamic().getStat(Stats.FRAGMENT_SWARM_RESPAWN_RATE_MULT).modifyMult(id, FABRICATOR_UNIT_REGEN_RATE_MULT);
            // 制造者单位：碎片群规模倍率乘以 FABRICATOR_UNIT_SWARM_SIZE_MULT（2倍）
            stats.getDynamic().getMod(Stats.FRAGMENT_SWARM_SIZE_MOD).modifyMult(id, FABRICATOR_UNIT_SWARM_SIZE_MULT);
        }

    }

    /**
     * 在舰船创建之后应用效果（目前为空，但被注释的代码原本用于添加战斗策略插件和重置系统）
     * @param ship 舰船对象
     * @param id   效果ID
     */
    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
    }

    /**
     * 在舰船被添加到战斗引擎后调用（确保威胁策略AI插件被添加）
     * @param ship 舰船对象
     * @param id   效果ID
     */
    @Override
    public void applyEffectsAfterShipAddedToCombatEngine(ShipAPI ship, String id) {
        CombatEngineAPI engine = Global.getCombatEngine();
        // 如果战斗引擎中尚未添加威胁双方策略插件，则添加一个
        if (!engine.hasPluginOfClass(VIII_ThreatCombatStrategyForBothSidesPlugin.class)) {
            engine.addPlugin(new VIII_ThreatCombatStrategyForBothSidesPlugin());
        }
    }

    /**
     * 战斗中每帧更新（用于处理残骸回收逻辑）
     * @param ship   舰船对象
     * @param amount 时间增量（秒）
     */
    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        super.advanceInCombat(ship, amount);  // 调用父类方法

        // 如果舰船不是残骸，或者已经有“正在被回收”标记，则返回
        if (!ship.isHulk() || ship.hasTag(SHIP_BEING_RECLAIMED)) return;
        // 如果是制造者单位，也不进行回收（制造者本身不会回收自己）
        if (VIII_ThreatCombatStrategyAI.isFabricator(ship)) return;

        // 获取舰船作为残骸的经过时间（秒）
        float elapsedAsHulk = 0f;
        String key = "VIII_elapsedAsHulkKey";
        if (ship.getCustomData().containsKey(key)) {
            elapsedAsHulk = (float) ship.getCustomData().get(key);
        }
        elapsedAsHulk += amount;
        ship.setCustomData(key, elapsedAsHulk);

        // 如果残骸存在时间超过1秒，则尝试寻找友方制造者单位
        if (elapsedAsHulk > 1f) {
            CombatEngineAPI engine = Global.getCombatEngine();
            int owner = ship.getOriginalOwner();  // 残骸的原始阵营
            boolean found = false;
            // 遍历所有舰船，寻找同阵营且未残骸、非中立的制造者单位，且CR未满（表示可工作）
            for (ShipAPI curr : engine.getShips()) {
                if (curr == ship || curr.getOwner() != owner) continue;
                if (curr.isHulk() || curr.getOwner() == 100) continue;
                if (!VIII_ThreatCombatStrategyAI.isFabricator(curr)) continue;
                if (curr.getCurrentCR() >= 1f) continue;
                found = true;
                break;
            }
            if (found) {
                // 如果找到符合条件的制造者，则添加舰船回收脚本（回收该残骸，持续3秒）
                Global.getCombatEngine().addPlugin(new VIII_ThreatShipReclamationScript(ship, 3f));
            } else {
                // 否则重置计时器，等待下一次检查
                ship.setCustomData(key, 0f);
            }
        }
    }

    /**
     * 当舰船生成的战机被生成时调用（此处为空实现）
     * @param fighter 生成的战机
     * @param ship    母舰
     * @param id      效果ID
     */
    @Override
    public void applyEffectsToFighterSpawnedByShip(ShipAPI fighter, ShipAPI ship, String id) {
    }

    /**
     * 是否应该在工具提示中添加描述（返回false，表示隐藏描述，因为该插件是隐藏的）
     * @param hullSize      舰船尺寸
     * @param ship          舰船对象
     * @param isForModSpec  是否用于模组规格
     * @return false
     */
    @Override
    public boolean shouldAddDescriptionToTooltip(HullSize hullSize, ShipAPI ship, boolean isForModSpec) {
        return false;
    }

    /**
     * 在工具提示中添加后置描述部分（用于显示威胁舰船的特殊属性说明）
     * @param tooltip       工具提示API
     * @param hullSize      舰船尺寸
     * @param ship          舰船对象
     * @param width         宽度
     * @param isForModSpec  是否用于模组规格
     */
    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, HullSize hullSize, ShipAPI ship, float width, boolean isForModSpec) {
        float pad = 3f;      // 内边距
        float opad = 10f;    // 外边距
        Color h = Misc.getHighlightColor();       // 高亮颜色
        Color bad = Misc.getNegativeHighlightColor(); // 负面高亮颜色（未使用）
        Color t = Misc.getTextColor();            // 文字颜色（未使用）
        Color g = Misc.getGrayColor();            // 灰色（未使用）

        tooltip.addPara("人之领 第八战斗群的大部分舰船具有一系列共同特性。", opad);

        // 战役属性部分
        tooltip.addSectionHeading("大地图特征", Alignment.MID, opad);
        tooltip.addPara("传感器特征降低至 %s 。", opad, h, new String[]{"0"});

        // 战斗属性部分
        tooltip.addSectionHeading("战斗内特征", Alignment.MID, opad);
        tooltip.addPara("所有武器(包括导弹)的制导精度提升至最高，敌方电子对抗系统(ECM)效能降低{%s}。", opad, h, new String[]{Math.round(EW_PENALTY_MULT * 100.0F) + "%"});
        tooltip.addPara("所受武器与引擎伤害降低{%s}。所受电磁脉冲伤害降低{%s}。此外，受损但仍可运作的武器与引擎在遭受攻击时仍能持续修复。", opad, h, new String[]{Math.round((1.0F - MODULE_DAMAGE_TAKEN_MULT) * 100.0F) + "%", Math.round((1.0F - EMP_DAMAGE_TAKEN_MULT) * 100.0F) + "%"});
    }

    /**
     * 获取工具提示宽度（直接调用父类方法）
     * @return 宽度
     */
    public float getTooltipWidth() {
        return super.getTooltipWidth();
    }
}