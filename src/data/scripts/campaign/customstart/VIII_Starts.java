package data.scripts.campaign.customstart;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI.CargoItemType;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.characters.CharacterCreationData;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.rulecmd.AddRemoveCommodity;
import exerelin.campaign.ExerelinSetupData;
import exerelin.campaign.PlayerFactionStore;
import exerelin.campaign.customstart.CustomStart;
import exerelin.utilities.StringHelper;

import java.util.Map;

// 自定义开局类，继承自Exerelin的CustomStart
public class VIII_Starts extends CustomStart {

    // 核心执行方法，设置玩家初始状态
    public void execute(InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap) {
        // 获取角色创建数据
        CharacterCreationData data = (CharacterCreationData) memoryMap.get("local").get("$characterData");
        data.addScriptBeforeTimePass(() -> {
            //Global.getSector().getMemoryWithoutUpdate().set("$spacerStart", true);
            Global.getSector().getMemoryWithoutUpdate().set("$nex_startLocation", "culann");
        });
        // 设置起始舰船
        String vid = "VIII_KunLun_variant"; // 舰船变体ID
        data.addStartingFleetMember(vid, FleetMemberType.SHIP); // 添加到起始舰队

        // 创建临时舰队成员用于获取信息
        FleetMemberAPI temp = Global.getFactory().createFleetMember(FleetMemberType.SHIP, vid);

        // 计算起始燃料量（等于舰船的燃料容量）
        int fuel = (int)temp.getFuelCapacity();
        int supplies = (int)temp.getCargoCapacity();
        int crew = (int)temp.getMaxCrew();

        // 添加起始货物
        data.getStartingCargo().addItems(CargoItemType.RESOURCES, "crew", (float)crew); // 1名船员
        data.getStartingCargo().addItems(CargoItemType.RESOURCES, "supplies", (float)supplies); // 补给
        data.getStartingCargo().addItems(CargoItemType.RESOURCES, "fuel", (float)fuel); // 燃料（等于舰船容量）
        data.getStartingCargo().addSpecial(new SpecialItemData("viii_package", null), 1F);
        data.getStartingCargo().addSpecial(new SpecialItemData("modspec", "VIII_Coordinate"), 1F);
        data.getStartingCargo().addSpecial(new SpecialItemData("modspec", "VIII_WateringMachine"), 1F);

        // 添加起始资金
        data.getStartingCargo().getCredits().add(10000000F); // 2000星币
        AddRemoveCommodity.addCreditsGainText(10000000, dialog.getTextPanel()); // 显示资金信息
        // 在对话框显示获得物品信息
        AddRemoveCommodity.addCommodityGainText("crew", crew, dialog.getTextPanel()); // 船员信息
        AddRemoveCommodity.addCommodityGainText("supplies", supplies, dialog.getTextPanel()); // 补给信息
        AddRemoveCommodity.addCommodityGainText("fuel", fuel, dialog.getTextPanel()); // 燃料信息
        AddRemoveCommodity.addItemGainText(new SpecialItemData("viii_package", null), 1, dialog.getTextPanel()); // 特殊物品信息
        AddRemoveCommodity.addItemGainText(new SpecialItemData("modspec", "VIII_Coordinate"), 1, dialog.getTextPanel()); // 特殊物品信息
        AddRemoveCommodity.addItemGainText(new SpecialItemData("modspec", "VIII_WateringMachine"), 1, dialog.getTextPanel()); // 特殊物品信息
        // 添加额外技能点
        MutableCharacterStatsAPI stats = data.getPerson().getStats(); // 获取角色状态
        stats.addPoints(40); // 增加3个技能点
        stats.addStoryPoints(120);
        stats.setSkillLevel("automated_ships", 1);
        stats.setSkillLevel("neural_link", 1);
        stats.setSkillLevel("hypercognition", 1);

        // 创建临时舰队用于显示
        CampaignFleetAPI tempFleet = FleetFactoryV3.createEmptyFleet(
                PlayerFactionStore.getPlayerFactionIdNGC(), // 玩家派系ID
                "patrolSmall", // 舰队类型
                null // 无关联市场
        );

        // 配置舰队
        tempFleet.getFleetData().addFleetMember(temp); // 添加舰船
        tempFleet.getFleetData().setFlagship(temp); // 设为旗舰
        temp.setCaptain(data.getPerson()); // 设置舰长为玩家角色
        temp.getRepairTracker().setCR(0.7F); // 设置初始CR为70%

        // 同步舰队数据
        tempFleet.getFleetData().setSyncNeeded();
        tempFleet.getFleetData().syncIfNeeded();
        tempFleet.forceSync();

        // 设置游戏难度
        data.setDifficulty("normal"); // 普通难度

        // 配置Exerelin特定设置
        ExerelinSetupData.getInstance().easyMode = false; // 禁用简单模式
        PlayerFactionStore.setPlayerFactionIdNGC("player"); // 设置玩家派系ID
        ExerelinSetupData.getInstance().freeStart = true; // 启用自由开局


        // 在对话框右侧显示舰队信息
        dialog.getVisualPanel().showFleetInfo(
                StringHelper.getString("exerelin_ngc", "playerFleet", true), // 标题
                tempFleet, // 要显示的舰队
                null, // 无描述
                null // 无对比舰队
        );

        // 添加对话框选项
        dialog.getOptionPanel().addOption(
                StringHelper.getString("done", true), // "完成"按钮
                "nex_NGCDone" // 选择后的操作
        );
        dialog.getOptionPanel().addOption(
                StringHelper.getString("back", true), // "返回"按钮
                "nex_NGCStartBack" // 选择后的操作
        );
    }
}