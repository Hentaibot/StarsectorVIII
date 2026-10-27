package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

import java.awt.*;
import java.util.HashMap;
import java.util.Map;

public class VIII_production extends BaseHullMod {

    // 配方常量：每天每艘船消耗与产出
    private static final int CONSUME_METALS_PER_SHIP = 100;
    private static final int PRODUCE_SUPPLIES_PER_SHIP = 65;
    private static final int PRODUCE_HEAVY_MACHINERY_PER_SHIP = 65;

    private static final int CONSUME_ORGANICS_PER_SHIP = 40;
    private static final int PRODUCE_LUXURY_GOODS_PER_SHIP = 15;
    private static final int PRODUCE_ORGANS_PER_SHIP = 5;

    private static final int CONSUME_VOLATILES_PER_SHIP = 40;
    private static final int PRODUCE_FUEL_PER_SHIP = 30;

    // 上次处理的天数标识（cycle*10000 + month*100 + day）
    private static int lastProcessedDayKey = -1;

    // 记录已处理的舰船（避免重复计算）
    private final Map<FleetMemberAPI, Integer> lastTriggerKey = new HashMap<>();

    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        // 战斗效果暂不实现
    }

    @Override
    public void advanceInCampaign(FleetMemberAPI member, float amount) {
        // 舰船被封存或玩家舰队未初始化则跳过
        if (member.isMothballed()) return;
        if (Global.getSector().getPlayerFleet() == null) return;

        CampaignClockAPI clock = Global.getSector().getClock();
        int cycle = clock.getCycle();
        int month = clock.getMonth();
        int day = clock.getDay();
        int currentDayKey = cycle * 10000 + month * 100 + day;

        // 当天已经处理过生产，跳过
        if (lastProcessedDayKey == currentDayKey) return;

        // 当天首次调用，执行生产逻辑
        lastProcessedDayKey = currentDayKey;

        // 统计所有符合条件的舰船（安装本插件、未封存、没有停止插件）
        int activeShips = 0;
        for (FleetMemberAPI fm : Global.getSector().getPlayerFleet().getFleetData().getMembersListCopy()) {
            if (fm.isMothballed()) continue;
            if (!fm.getVariant().hasHullMod("VIII_production")) continue;
            if (fm.getVariant().hasHullMod("VIII_productionStop")) continue; // 有停止插件则跳过
            activeShips++;
        }
        if (activeShips == 0) return;

        // 计算总消耗和总产出
        int totalConsumeMetals = CONSUME_METALS_PER_SHIP * activeShips;
        int totalConsumeOrganics = CONSUME_ORGANICS_PER_SHIP * activeShips;
        int totalConsumeVolatiles = CONSUME_VOLATILES_PER_SHIP * activeShips;

        int totalProduceSupplies = PRODUCE_SUPPLIES_PER_SHIP * activeShips;
        int totalProduceHeavyMachinery = PRODUCE_HEAVY_MACHINERY_PER_SHIP * activeShips;
        int totalProduceLuxuryGoods = PRODUCE_LUXURY_GOODS_PER_SHIP * activeShips;
        int totalProduceOrgans = PRODUCE_ORGANS_PER_SHIP * activeShips;
        int totalProduceFuel = PRODUCE_FUEL_PER_SHIP * activeShips;

        var cargo = Global.getSector().getPlayerFleet().getCargo();

        // 检查资源是否足够
        boolean enoughMetals = cargo.getCommodityQuantity(Commodities.METALS) >= totalConsumeMetals;
        boolean enoughOrganics = cargo.getCommodityQuantity(Commodities.ORGANICS) >= totalConsumeOrganics;
        boolean enoughVolatiles = cargo.getCommodityQuantity(Commodities.VOLATILES) >= totalConsumeVolatiles;

        // 分别处理三个生产项，不足则跳过该项
        StringBuilder productionMsg = new StringBuilder();

        if (enoughMetals) {
            cargo.removeCommodity(Commodities.METALS, totalConsumeMetals);
            cargo.addCommodity(Commodities.SUPPLIES, totalProduceSupplies);
            cargo.addCommodity(Commodities.HEAVY_MACHINERY, totalProduceHeavyMachinery);
            productionMsg.append("金属加工: +").append(totalProduceSupplies).append("补给 +")
                    .append(totalProduceHeavyMachinery).append("重型机械\n");
        } else {
            productionMsg.append("金属加工: 资源不足(需要").append(totalConsumeMetals).append("金属)\n");
        }

        if (enoughOrganics) {
            cargo.removeCommodity(Commodities.ORGANICS, totalConsumeOrganics);
            cargo.addCommodity(Commodities.LUXURY_GOODS, totalProduceLuxuryGoods);
            cargo.addCommodity(Commodities.ORGANS, totalProduceOrgans);
            productionMsg.append("有机物精炼: +").append(totalProduceLuxuryGoods).append("奢侈品 +")
                    .append(totalProduceOrgans).append("器官\n");
        } else {
            productionMsg.append("有机物精炼: 资源不足(需要").append(totalConsumeOrganics).append("有机物)\n");
        }

        if (enoughVolatiles) {
            cargo.removeCommodity(Commodities.VOLATILES, totalConsumeVolatiles);
            cargo.addCommodity(Commodities.FUEL, totalProduceFuel);
            productionMsg.append("挥发物裂解: +").append(totalProduceFuel).append("燃料\n");
        } else {
            productionMsg.append("挥发物裂解: 资源不足(需要").append(totalConsumeVolatiles).append("挥发物)\n");
        }

        // 显示生产报告
        if (!productionMsg.isEmpty()) {
            String msg =cycle + "年" + month + "月" + day + "日 深空铸造厂产出:\n" + productionMsg;
            Global.getSector().getPlayerFleet().addFloatingText(msg, Misc.getTextColor(), 1f);
        }
    }

    @Override
    public boolean shouldAddDescriptionToTooltip(ShipAPI.HullSize hullSize, ShipAPI ship, boolean isForModSpec) {
        return false;
    }

    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, ShipAPI.HullSize hullSize, ShipAPI ship, float width, boolean isForModSpec) {
        tooltip.addPara("""
                        每日自动生产（多舰船效果叠加）：
                        - 消耗 %s 金属 → 产出 %s 补给 + %s 重型机械
                        - 消耗 %s 有机物 → 产出 %s 奢侈品 + %s 器官
                        - 消耗 %s 挥发物 → 产出 %s 燃料
                        若某项资源不足则暂停该项生产。
                        可通过安装 %s 插件暂停该舰船的生产功能。""",
                0f, Color.white, Color.ORANGE,
                CONSUME_METALS_PER_SHIP + "", PRODUCE_SUPPLIES_PER_SHIP + "", PRODUCE_HEAVY_MACHINERY_PER_SHIP + "",
                CONSUME_ORGANICS_PER_SHIP + "", PRODUCE_LUXURY_GOODS_PER_SHIP + "", PRODUCE_ORGANS_PER_SHIP + "",
                CONSUME_VOLATILES_PER_SHIP + "", PRODUCE_FUEL_PER_SHIP + "",
                "停用深空铸造厂");
    }
}