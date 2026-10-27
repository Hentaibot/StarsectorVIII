package data.hullmods;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

import java.awt.*;


/**
 * VIII 基础设计框架船体插件。
 * 提供以下效果：
 * 1. 降低受到的 EMP、护盾、船体伤害。
 * 2. 添加装甲缓慢再生机制：战斗中有装甲损伤后，若5秒内未再受伤害，开始每秒恢复最大装甲值的1.5%，
 *    若舰船带有 "kk" 内插，则恢复速度加倍。
 * 3. 添加被击中时的视觉特效：在受击点渲染一个圆形护盾光晕，使用自定义着色器混合舰船纹理和特效纹理。
 */
public class VIII_BaseDesignFrame extends BaseHullMod {


    /**
     * 在舰船创建前应用效果，修改基础属性。
     */
    @Override
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id){
        // 降低受到的EMP伤害至75%
        stats.getEmpDamageTakenMult().modifyMult(id, 0.5f);
        //stats.getMaxBurnLevel().modifyFlat(id, 2f);
        stats.getFuelUseMod().modifyMult(id, 0.6f);
        // 移除由“automated”内插可能设置的任何最大船员数修正（unmodify）
        stats.getMaxCrewMod().unmodify("automated");
        stats.getDynamic().getStat(Stats.CORONA_EFFECT_MULT).modifyMult(id, 0f);
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
        tooltip.addPara("人之领 第八战斗群所配备的舰船采用了与人之领勘探者无人机高度重合的技术路径，被设计的便于自动母舰批量制造；优化后的动力系统和舰体结构，使其在远距离超空间航行有着得天独厚的优势。", opad);
        tooltip.addSectionHeading("基础效果", Alignment.MID, pad);
        // 整备时间和重新装填时间惩罚
        tooltip.addPara("该舰位于超空间中航速提高 %s (功能未实现)\n降低 %s 燃料消耗\n减少EMP对舰船 %s 的影响\n免疫日冕对战备值 (CR) 的影响，对超空间内的风暴也有同样的作用。", pad,Misc.getTextColor(),h,"2","40%","50%");
    }



}