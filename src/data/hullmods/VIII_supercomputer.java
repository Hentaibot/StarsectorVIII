package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.impl.campaign.ids.HullMods;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

import java.awt.*;

public class VIII_supercomputer extends BaseHullMod {
    public static final float RECOIL_BONUS = 50f;

    public static float RANGE_BONUS = 200f;
    public static float PD_MINUS = 100f;
    public static float VISION_BONUS = 2500f;



    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {


        stats.getBallisticWeaponRangeBonus().modifyPercent(id, RANGE_BONUS);
        stats.getEnergyWeaponRangeBonus().modifyPercent(id, RANGE_BONUS);

        stats.getNonBeamPDWeaponRangeBonus().modifyPercent(id, -PD_MINUS);
        stats.getBeamPDWeaponRangeBonus().modifyPercent(id, -PD_MINUS);


        stats.getSightRadiusMod().modifyFlat(id, VISION_BONUS);




        stats.getMaxRecoilMult().modifyMult(id, 1f - (0.01f * RECOIL_BONUS));
        stats.getRecoilPerShotMult().modifyMult(id, 1f - (0.01f * RECOIL_BONUS));
    }
    /**
     * 在舰船创建后应用效果，移除不兼容的插件并显示警告。
     * @param ship 舰船对象
     * @param id   插件标识符
     */
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        // 移除不兼容的插件，并显示警告消息
        if (ship.getVariant().hasHullMod("dedicated_targeting_core")) {
            ship.getVariant().removeMod("dedicated_targeting_core");
            ship.getVariant().addMod("VIII_BlockedBlankHullmod");
            Global.getSoundPlayer().playUISound("cr_allied_warning", 1.0F, 1.0F);
            Global.getSector().getCampaignUI().getMessageDisplay().addMessage("不兼容专注型目标锁定核心");
        }
        if (ship.getVariant().hasHullMod("targetingunit")) {
            ship.getVariant().removeMod("targetingunit");
            ship.getVariant().addMod("VIII_BlockedBlankHullmod");
            Global.getSoundPlayer().playUISound("cr_allied_warning", 1.0F, 1.0F);
            Global.getSector().getCampaignUI().getMessageDisplay().addMessage("不兼容目标定位系统");
        }
    }
    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, ShipAPI.HullSize hullSize, ShipAPI ship, float width, boolean isForModSpec) {
        float pad = 10.0f;
        float pads = 3.0f;

        Color y = Misc.getHighlightColor();
        tooltip.addSectionHeading("效果", Alignment.MID, pad);
        tooltip.addPara("提高 %s 实弹武器和能量武器的射程.\n提高 %s PD武器的射程.\n大幅度提高战场视野范围.", pads,Misc.getTextColor(),y,"200%","100%");

    }
    public boolean isApplicableToShip(ShipAPI ship) {
        // 检查是否安装了不兼容的船体插件
        if (ship.getVariant().getHullMods().contains("dedicated_targeting_core")) return false;
        if (ship.getVariant().getHullMods().contains("targetingunit")) return false;
        return true;
    }

    public String getUnapplicableReason(ShipAPI ship) {
        // 检查不兼容的船体插件
        if (ship.getVariant().getHullMods().contains("dedicated_targeting_core")) {
            return "不兼容专注型目标锁定核心";
        }
        if (ship.getVariant().getHullMods().contains("targetingunit")) {
            return "不兼容目标定位系统";
        }
        return null;
    }



}
