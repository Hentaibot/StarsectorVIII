package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.characters.LevelBasedEffect.ScopeDescription;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.MutableFleetStatsAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

import java.awt.*;


public class VIII_CommandNetwork extends BaseHullMod {

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        // 仅对玩家舰队且该舰船拥有此插件（通常是旗舰）时生效
        if (ship.getFleetMember() != null && ship.getFleetMember().getFleetData() != null &&
                ship.getFleetMember().getFleetData().getFleet() == Global.getSector().getPlayerFleet()) {

            // 遍历玩家舰队所有成员
            for (FleetMemberAPI member : Global.getSector().getPlayerFleet().getMembersWithFightersCopy()) {
                HullSize hullSize = member.getHullSpec().getHullSize();
                // 如果是驱逐舰或护卫舰，降低部署点
                if (hullSize == HullSize.DESTROYER || hullSize == HullSize.FRIGATE || hullSize == HullSize.CRUISER) {
                    // 应用40%降低，即乘数0.6
                    member.getStats().getDynamic().getMod("deployment_points_mod").modifyMult(id, 0.6f);
                }
            }
        }
    }
    @Override
    public boolean shouldAddDescriptionToTooltip(HullSize hullSize, ShipAPI ship, boolean isForModSpec) {
        return false;
    }

    /**
     * 添加自定义tooltip后置描述
     * 该方法用于在船体插件的UI描述中添加额外的说明文本和表格，向玩家展示该插件的效果机制和数值。
     */
    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, HullSize hullSize, final ShipAPI ship, float width, boolean isForModSpec) {
        // 定义间距常量（垂直间距）
        float pad = 3f;      // 较小间距
        float opad = 10f;    // 较大间距

        // 获取高亮颜色（用于突出显示重要数值）
        Color h = Misc.getHighlightColor();


        // ---------- 描述文本：说明触发机制和效果 ----------
        // 说明该插件与被动型反应透镜不同，需要主动攻击命中才能触发
        tooltip.addPara("一组针对巡洋舰以及以下吨位舰船的作战数据处理接口，被设计用于管理战场的指挥网络并确保一切功能有条不紊，并根据战场的态势变化，重新优化指挥网络根据当前战况进行运算处理。", opad);
        tooltip.addSectionHeading("舰队内受影响的舰船", Alignment.MID, pad);
        // 如果是仅用于模组规格显示，或者没有具体舰船且不在图鉴模式下，则不再显示后面的表格
        if (isForModSpec || (ship == null && !Global.CODEX_TOOLTIP_MODE)) return;

        // 设置背景透明度（使表格背景半透明）
        tooltip.setBgAlpha(0.9f);


        // 定义表格各列的宽度（伤害、EMP、幅能消耗）
        float damW = 87f;
        float empW = 87f;
        float fluxW = 87f;
        // 计算“舰船大小”列的宽度：总宽度减去其他三列宽度和额外间距
        float sizeW = width - damW - empW - fluxW - 10f;

        // 开始绘制表格，设置表格颜色（基础色、暗色、亮色）、行高、是否显示表头、是否显示分隔线、表头内容
        tooltip.beginTable(Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(), Misc.getBrightPlayerColor(),
                20.0F, true, true, new Object[]{"舰船大小", sizeW, "部署点", damW, "峰值性能", empW, "机动性", fluxW});



            // 默认使用灰色文本
            Color c = Misc.getHighlightColor();

            // 添加一行数据：舰船大小（文本居中）、伤害（数值居中）、EMP（数值居中）、幅能消耗（数值居中）
        tooltip.addRow(Alignment.MID, c, Misc.getHullSizeStr(HullSize.CRUISER),
                Alignment.MID, c, "-12",
                Alignment.MID, c, "+25%",
                Alignment.MID, c, "+20%");
        tooltip.addRow(Alignment.MID, c, Misc.getHullSizeStr(HullSize.DESTROYER),
                Alignment.MID, c, "-8",
                Alignment.MID, c, "+30%",
                Alignment.MID, c, "+25%");
        tooltip.addRow(Alignment.MID, c, Misc.getHullSizeStr(HullSize.FRIGATE),
                Alignment.MID, c, "-4",
                Alignment.MID, c, "+40%",
                Alignment.MID, c, "+30%");


        // 结束表格绘制（参数：空字符串表示无额外标题，0表示无额外间距，opad表示表格后间距）
        tooltip.addTable("", 0, opad);

        // 在表格后添加一个空行，用于视觉分隔
        tooltip.addSpacer(5f);
        tooltip.addPara("但是上面的效果并没有实现，因为代码写不出来)", opad);
    }

}