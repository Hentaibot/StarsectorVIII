package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

import java.awt.*;

public class VIII_Coordinate extends BaseHullMod {

    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
        stats.getFluxCapacity().modifyMult(id, 1.15F);//增加幅能容量
        stats.getShieldDamageTakenMult().modifyMult(id, 0.85F);//降低护盾受到的伤害
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
        tooltip.addPara("移除内置纳米熔炉，并替换成额外的幅能电容器，将原先纳米熔炉的能量配额分配协调给护盾。", opad);
        tooltip.addSectionHeading("特殊效果", Alignment.MID, pad);
        // 整备时间和重新装填时间惩罚
        tooltip.addPara("额外提高 %s 幅能容量.\n额外降低 %s 护盾受到的伤害.", pad,Misc.getTextColor(),h,"15%","15%");
    }
        public boolean isApplicableToShip(ShipAPI ship) {

        if (ship.getVariant().hasHullMod("VIII_WateringMachine")) {
            return false;
        }
        // 其他情况：只有当舰船有护盾时才允许安装
        return ship.getShield() != null && ship.getVariant().hasHullMod("VIII_ArmorRestoration");
    }

    /**
     * 获取无法安装该插件的原因字符串。
     * @param ship 舰船对象
     * @return 原因字符串
     */
    public String getUnapplicableReason(ShipAPI ship) {

        if (ship.getVariant().hasHullMod("VIII_WateringMachine")) {
            return "与纳米浇灌机不兼容";
        }
        if (!ship.getVariant().hasHullMod("VIII_ArmorRestoration")) {
            return "需要活性纳米镀层";
        }

        return "该舰没有护盾";
    }
}
