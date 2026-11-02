package data.scripts.world.systems;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.StarSystemAPI;
import com.fs.starfarer.api.impl.campaign.ids.Terrain;
import com.fs.starfarer.api.util.Misc;
import data.scripts.VIIIBattlegroupGenerate;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;

public class Atan {
    //引用生成
    public void generate(SectorAPI sector) {
        //生成星系
        StarSystemAPI system = sector.createStarSystem("Atan");
        //星系位置，50000/-50000贴边
        system.getLocation().set(-22000.0F, -32000.0F);
        //背景图，不过一般来说应该没人看（
        system.setBackgroundTextureFilename("graphics/backgrounds/background4.jpg");
        //打光颜色，不重要，一般没人看，抄一个就是
        system.setLightColor(new Color(225, 245, 255));
        //坐下一个新恒星，star唯一，多星系统的其他恒星用星球添加用法添加
        //我这里就延续传统用星系名命名恒星
        PlanetAPI star = system.initStar("Atan", "star_yellow", 900f, 500f, 3f, 0.75f, 0f);
        //恒星的描述
        star.setCustomDescriptionId("VIII_Atan_star");
        //小行星带
        system.addAsteroidBelt(star, 100, 3600, 100, 30, 40, Terrain.ASTEROID_BELT, null);
        //
        //俩余辉枢纽改，我这边直接按空间站处理
        //
        SectorEntityToken Atan_Station_1 = system.addCustomEntity("Atan_Station_1", "余辉枢纽阿尔法", "station_hightech2", "UDM_C_T_Main");
        Atan_Station_1.setCustomDescriptionId("Atan_Station_1");
        Atan_Station_1.setInteractionImage("illustrations", "cargo_loading");
        Atan_Station_1.setCircularOrbitWithSpin(star,  0, 5000, 360, 2, 5);
        Atan_Station_1.setFaction("UDM_C_T_Main");
        VIIIBattlegroupGenerate.addMarketplace("UDM_C_T_Main", Atan_Station_1, (ArrayList)null, "余辉枢纽阿尔法", 6,
                //人口
                new ArrayList(Arrays.asList("population_6")),
                //市场
                new ArrayList(Arrays.asList("open_market", "black_market","generic_military","storage")),
                //建筑，如特大型港口，最高指挥部...
                new ArrayList(Arrays.asList("population", "megaport", "highcommand",
                        "orbitalworks","refining","fuelprod","starfortress_high")), 0.15F, false, true);
        //
        //第二个空间站
        //
        SectorEntityToken Atan_Station_2 = system.addCustomEntity("Atan_Station_2", "余辉枢纽贝塔", "station_hightech2", "UDM_C_T_Main");
        Atan_Station_2.setCustomDescriptionId("Atan_Station_2");
        Atan_Station_2.setInteractionImage("illustrations", "cargo_loading");
        Atan_Station_2.setCircularOrbitWithSpin(star,  0, 3800, 160, 2, 5);
        Atan_Station_2.setFaction("UDM_C_T_Main");
        VIIIBattlegroupGenerate.addMarketplace("UDM_C_T_Main", Atan_Station_2, (ArrayList)null, "余辉枢纽贝塔", 6,
                new ArrayList(Arrays.asList("population_6")),
                new ArrayList(Arrays.asList("open_market", "black_market","generic_military","storage")),
                new ArrayList(Arrays.asList("population", "megaport", "highcommand",
                        "orbitalworks","refining","fuelprod","starfortress_high")), 0.15F, false, true);
        //
        //还有一颗海洋行星说是
        //
        PlanetAPI Auvcat = system.addPlanet("VIII_Auvcat", star, "阿努凯特", "water", 180, 115, 6000, 580);
        Auvcat.setCustomDescriptionId("VIII_Auvcat");
        Auvcat.setFaction("UDM_C_T_Main");
        Misc.initConditionMarket(Auvcat);
        VIIIBattlegroupGenerate.addMarketplace("UDM_C_T_Main", Auvcat, (ArrayList)null, "阿努凯特", 6,
                new ArrayList(Arrays.asList("population_6", "terran", "farmland_bountiful", "solar_array", "mild_climate", "habitable")),
                new ArrayList(Arrays.asList("open_market", "black_market")),
                new ArrayList(Arrays.asList("population", "megaport", "orbitalworks","farming", "commerce", "militarybase")), 0.15F, false, true);
        //
        //星门
        //
        SectorEntityToken Atan_gate = system.addCustomEntity("Atan_gate", "Atan星门", "inactive_gate", "neutral");
        Atan_gate.setCircularOrbitPointingDown( star, 55, 6500, 360);
        Atan_gate.setCustomDescriptionId("Atan_gate");
        //两个应该出现的东西
        SectorEntityToken Atan_relay1 = system.addCustomEntity("Atan_relay1", // unique id
                "Atan通讯中继器", // name - if null, defaultName from custom_entities.json will be used
                "comm_relay", // type of object, defined in custom_entities.json
                "UDM_C_T_Main"); // faction

        SectorEntityToken Atan_relay2 = system.addCustomEntity("Atan_relay2", // unique id
                "Atan导航浮标", // name - if null, defaultName from custom_entities.json will be used
                "nav_buoy", // type of object, defined in custom_entities.json
                "independent"); // faction
        //导航浮标位置
        Atan_relay2.setCircularOrbitPointingDown( star, 0 + 30, 6000, 160);
        //通讯中继器位置
        Atan_relay1.setCircularOrbitPointingDown( star, 45+60, 3500, 700);


        //手动跳跃点，具体用法如下，多个跳跃点反正我是堆量的。
        this.addJumpPointAroundPlanet4(system, star, 2500.0F);
        //自动跳跃点，不生成新跳跃点也能把星系内跳跃点的超空间部分补全
        system.autogenerateHyperspaceJumpPoints(false, true);












    }

    private void addJumpPointAroundPlanet4(StarSystemAPI system, PlanetAPI star, float v) {
        SectorEntityToken VIII_Tau_1_jumpPoint1 = Global.getFactory().createJumpPoint(star.getId() + "_jumppoint", "跳跃点 ");
        VIII_Tau_1_jumpPoint1.setCircularOrbit(star, 0.0F, 2000, 50.0F);
        VIII_Tau_1_jumpPoint1.setCustomDescriptionId("VIII_Tau_1_jumpPoint1");
        system.addEntity(VIII_Tau_1_jumpPoint1);
    }
}
