package data.hullmods;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.FleetDataAPI;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

import java.awt.*;


/**
 * VIII 基础设计框架船体插件。
 * 提供以下效果：
 * 1. 受到的 EMP 伤害降低 50%。
 * 2. 燃料消耗降低 40%。
 * 3. 免疫日冕（对超空间内的风暴同样生效）对战备值的影响。
 * 4. 位于超空间时最大航速 +2。
 */
public class VIII_BaseDesignFrame extends BaseHullMod {

    /** 超空间最大航速加成的来源标记。 */
    private static final String HYPER_BURN_SOURCE = "VIII_BaseDesignFrame_hyper_burn";
    /** 位于超空间时的最大航速加成值。 */
    private static final float HYPER_BURN_BONUS = 2.0F;

    /**
     * 在舰船创建前应用效果，修改基础属性。
     */
    @Override
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id){
        // 降低受到的EMP伤害至50%
        stats.getEmpDamageTakenMult().modifyMult(id, 0.5f);
        stats.getFuelUseMod().modifyMult(id, 0.6f);
        // 移除由“automated”内插可能设置的任何最大船员数修正（unmodify）
        stats.getMaxCrewMod().unmodify("automated");
        stats.getDynamic().getStat(Stats.CORONA_EFFECT_MULT).modifyMult(id, 0f);

        // 舰队成员的统计数据在每次 sync / 换装之后都会重建，这里补写一次超空间航速，
        // 避免“刚重建完、还没轮到 advanceInCampaign”的空窗期里加成丢失。
        if (stats.getFleetMember() != null && isInHyperspace(stats.getFleetMember())) {
            stats.getMaxBurnLevel().modifyFlat(HYPER_BURN_SOURCE, HYPER_BURN_BONUS);
        }
    }

    /**
     * 战役中每帧推进：按舰队是否位于超空间开/关最大航速加成。
     * <p>
     * 舰队航速取的是所有成员 getMaxBurnLevel() 的最小值，所以只有当本舰恰好是舰队里
     * 最慢的一艘时，这 2 点加成才会体现在舰队整体航速上（与游戏内其它航速加成一致）。
     */
    @Override
    public void advanceInCampaign(FleetMemberAPI member, float amount) {
        if (member == null || member.getStats() == null) {
            return;
        }
        if (isInHyperspace(member)) {
            member.getStats().getMaxBurnLevel().modifyFlat(HYPER_BURN_SOURCE, HYPER_BURN_BONUS);
        } else {
            member.getStats().getMaxBurnLevel().unmodifyFlat(HYPER_BURN_SOURCE);
        }
    }

    private static boolean isInHyperspace(FleetMemberAPI member) {
        FleetDataAPI data = member.getFleetData();
        if (data == null) {
            return false;
        }
        CampaignFleetAPI fleet = data.getFleet();
        return fleet != null && fleet.isInHyperspace();
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
        // 超空间航速、燃料、EMP、日冕
        tooltip.addPara("该舰位于超空间中航速提高 %s\n降低 %s 燃料消耗\n减少EMP对舰船 %s 的影响\n免疫日冕对战备值 (CR) 的影响，对超空间内的风暴也有同样的作用。", pad, Misc.getTextColor(), h, "2", "40%", "50%");
    }
}
