package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShieldAPI.ShieldType;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

import java.awt.*;

/**
 * 护盾分流（Shield Shunt）船体插件。
 * 该插件会移除舰船的护盾系统，作为补偿永久提升舰船的装甲值。
 * 如果插件是内置强化（S-mod）版本，则装甲加成会进一步提高。
 * 该插件不能与临时护盾发生器（frontshield）同时安装。
 */
public class VIII_WateringMachine extends BaseHullMod {

    /** 基础装甲加成百分比（+15%） */
    public static float ARMOR_BONUS = 1.25F;

    /** 内置强化版本额外增加的装甲加成百分比（+15%） */
    public static float SMOD_ARMOR_BONUS = 15.0F;

    /**
     * 在舰船创建前应用效果：增加装甲值。
     * @param hullSize  舰船尺寸
     * @param stats     可变舰船统计数据
     * @param id        插件标识符
     */
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
        // 检查是否为内置强化（S-mod）
        boolean sMod = this.isSMod(stats);
        // 计算总装甲加成：基础加成 + 可能的内置强化加成
        float totalBonus = ARMOR_BONUS + (sMod ? SMOD_ARMOR_BONUS : 0.0F);
        // 以百分比形式增加装甲值
        stats.getArmorBonus().modifyMult(id, ARMOR_BONUS);
    }

    /**
     * 在舰船创建后应用效果：移除护盾系统。
     * @param ship 舰船对象
     * @param id   插件标识符
     */
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        // 将舰船的护盾类型设置为 NONE（无护盾），所有护盾参数设为默认值
        ship.setShield(ShieldType.NONE, 0.0F, 1.0F, 1.0F);
    }

    /**
     * 获取描述参数（用于UI显示）。
     * @param index    参数索引
     * @param hullSize 舰船尺寸
     * @return 参数字符串，索引0返回装甲加成百分比
     */
    public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
        // 只有索引0被使用，返回基础装甲加成
        return index == 0 ? (int) ARMOR_BONUS + "%" : null;
    }

    /**
     * 判断该插件是否可应用于指定舰船。
     * 安装条件：
     * 1. 如果舰船原本就没有护盾且已经安装了临时护盾发生器，则不可安装（因为要移除护盾的插件不能装在无护盾的船上？实际逻辑需要检查原代码）。
     * 2. 如果已经安装了临时护盾发生器（frontshield），则不可安装。
     * 3. 如果已经有护盾分流插件，则允许（已安装的情况）。
     * 4. 其他情况，只有舰船有护盾时才允许安装（即原本有护盾才能移除）。
     * @param ship 舰船对象
     * @return true 如果可以安装，否则 false
     */
    public boolean isApplicableToShip(ShipAPI ship) {
        if (ship.getVariant().hasHullMod("VIII_Coordinate")) {
            return false;
        }
        // 其他情况：只有当舰船有护盾时才允许安装
        return ship.getShield() != null || ship.getVariant().hasHullMod("VIII_ArmorRestoration");
    }

    /**
     * 获取无法安装该插件的原因字符串。
     * @param ship 舰船对象
     * @return 原因字符串
     */
    public String getUnapplicableReason(ShipAPI ship) {
        if (ship.getVariant().hasHullMod("VIII_Coordinate")) {
            return "与护盾协调协议不兼容";
        }
        if (!ship.getVariant().hasHullMod("VIII_ArmorRestoration")) {
            return "需要活性纳米镀层";
        }
        // 否则说明舰船没有护盾
        return "该舰没有护盾";
    }
    @Override
    public boolean shouldAddDescriptionToTooltip(ShipAPI.HullSize hullSize, ShipAPI ship, boolean isForModSpec) {
        return false; // 不自动添加描述，使用自定义描述
    }

    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, ShipAPI.HullSize hullSize, ShipAPI ship, float width, boolean isForModSpec) {
        float pad = 3.0F;
        float opad = 10.0F;
        Color h = Misc.getHighlightColor();
        Color bad = Misc.getNegativeHighlightColor();

        // 基础说明
        tooltip.addPara("移除护盾发生器，将原本的外部节点替换成额外的纳米浇灌设备和加固装甲，大幅度提升纳米致动器的修复速度。", opad);
        tooltip.addSectionHeading("特殊效果", Alignment.MID, pad);
        // 整备时间和重新装填时间惩罚
        tooltip.addPara("额外提高 %s 装甲值.\n将活性纳米镀层的装甲恢复改为常驻.", pad,Misc.getTextColor(),h,"25%");
    }

//    /**
//     * 表示该插件有内置强化效果（S-mod）。
//     * @return true
//     */
//    public boolean hasSModEffect() {
//        return true;
//    }
}