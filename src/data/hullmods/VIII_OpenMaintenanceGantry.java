package data.hullmods;

import java.awt.Color;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

public class VIII_OpenMaintenanceGantry extends BaseHullMod {

    /** 维修速度提升百分比 (20%) */
    public static final float REPAIR_BONUS = 0.20f;
    
    /** CR恢复速率提升百分比 (20%) */
    public static final float CR_RECOVERY_BONUS = 0.20f;
    
    /** 补给消耗降低百分比 (10%) */
    public static final float SUPPLY_REDUCTION = 0.10f;



    @Override
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {

    }

    @Override
    public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
        if (index == 0) return "" + (int)(REPAIR_BONUS * 100) + "%"; // 维修速度提升
        if (index == 1) return "" + (int)(CR_RECOVERY_BONUS * 100) + "%"; // CR恢复提升
        if (index == 2) return "" + (int)(SUPPLY_REDUCTION * 100) + "%"; // 补给消耗降低
        return null;
    }

    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, ShipAPI.HullSize hullSize, ShipAPI ship, float width, boolean isForModSpec) {
        float pad = 3f;
        float opad = 10f;
        Color h = Misc.getHighlightColor();
        Color good = Misc.getPositiveHighlightColor();
        Color bad = Misc.getNegativeHighlightColor();

        tooltip.addSectionHeading("该效果有最高上限", com.fs.starfarer.api.ui.Alignment.MID, opad);

        tooltip.addPara("舰队中每拥有一艘拥有该船插的舰船，提高舰队 %s 的维修速度 %s 的CR恢复速率，降低舰队 %s 的补给消耗。",
                pad, new Color[]{good, good, good, bad}, "" + (int)(REPAIR_BONUS * 100) + "%" ,"" + (int)(CR_RECOVERY_BONUS * 100) + "%",(int)(SUPPLY_REDUCTION * 100) + "%");
        tooltip.addPara("该效果最高能够叠加 %s 次。",
                pad,h, "3");

    }
}