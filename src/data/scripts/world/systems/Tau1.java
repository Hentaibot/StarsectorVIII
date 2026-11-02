package data.scripts.world.systems;
//

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

// 修改文件名和下方为 你的星系名
    public class Tau1 {

    public void generate(SectorAPI sector) {

        StarSystemAPI system = sector.createStarSystem("Tau1");
        //在此输入星系大地图坐标的X轴, 在此输入星系大地图坐标的Y轴，Y轴40000已经接近到顶了
        system.getLocation().set(-20000.0F, -30000.0F);
        system.setBackgroundTextureFilename("graphics/backgrounds/background4.jpg");
        system.setLightColor(new Color(225, 245, 255));
        PlanetAPI star = system.initStar("Tau_1", "star_blue_giant", 900f, 500f, 3f, 0.75f, 0f);
        //下面这个是新增一个恒星的ID，用以在增加descriptions文件的描述
        star.setCustomDescriptionId("VIII_Tau_1_star");
        //星环，要多复制然后调一调
        //2700-4000,5+7
        system.addAsteroidBelt(star, 50, 3600, 100, 30, 40, Terrain.ASTEROID_BELT, null);
        system.addRingBand(star, "misc", "rings_dust0", 256f, 3, Color.white, 256f, 2200, 345f, null, null);
        system.addAsteroidBelt(star, 100, 2900, 256, 150, 250, Terrain.ASTEROID_BELT, null);
//        system.addAsteroidBelt(star, 100, 3000, 256, 150, 250, Terrain.ASTEROID_BELT, null);

        system.addAsteroidBelt(star, 100, 3100, 128, 200, 300, Terrain.ASTEROID_BELT, null);
//        system.addAsteroidBelt(star, 100, 3200, 188, 200, 300, Terrain.ASTEROID_BELT, null);
        system.addAsteroidBelt(star, 100, 3300, 256, 200, 300, Terrain.ASTEROID_BELT, null);
//        system.addAsteroidBelt(star, 100, 3400, 256, 200, 300, Terrain.ASTEROID_BELT, null);
        system.addAsteroidBelt(star, 100, 3500, 256, 200, 300, Terrain.ASTEROID_BELT, null);


        //星球生成代码搞半天还要自己拼，不过无所谓了，反正拼的是原版的，我觉得牢A没意见
        PlanetAPI Bast = system.addPlanet("VIII_Bast", star, "芭丝特", "cryovolcanic", 180, 115, 9500, 580);
        Bast.setCustomDescriptionId("VIII_Bast");
        Bast.setFaction("UDM_C_T_Main");
        Misc.initConditionMarket(Bast);
        VIIIBattlegroupGenerate.addMarketplace("UDM_C_T_Main", Bast, (ArrayList)null, "芭丝特", 6, new ArrayList(Arrays.asList("population_6", "terran", "farmland_bountiful", "solar_array", "mild_climate", "habitable")), new ArrayList(Arrays.asList("open_market", "black_market")), new ArrayList(Arrays.asList("population", "megaport", "orbitalworks","farming", "commerce", "militarybase")), 0.15F, false, true);

        PlanetAPI Sate = system.addPlanet("VIII_Sate", star, "赛特", "terran", 160, 120, 8000, 300);
        Sate.setCustomDescriptionId("VIII_Sate");
        Sate.setFaction("UDM_C_T_Main");
        Misc.initConditionMarket(Sate);
        VIIIBattlegroupGenerate.addMarketplace("UDM_C_T_Main", Sate, (ArrayList)null, "赛特", 6, new ArrayList(Arrays.asList("population_6", "terran", "farmland_bountiful", "solar_array", "mild_climate", "habitable")), new ArrayList(Arrays.asList("open_market", "black_market")), new ArrayList(Arrays.asList("population", "megaport", "orbitalworks","farming", "commerce", "militarybase")), 0.15F, false, true);

        //两个远离中心的小行星环
        system.addAsteroidBelt(star, 100, 8500, 128, 200, 300, Terrain.ASTEROID_BELT, null);
        system.addAsteroidBelt(star, 100, 9000, 128, 200, 300, Terrain.ASTEROID_BELT, null);

        PlanetAPI Yss = system.addPlanet("VIII_Yss", star, "伊西斯", "barren", 150, 110, 4000, 80);
        Yss.setCustomDescriptionId("VIII_Yss");
        Yss.setFaction("UDM_C_T_Main");
        Misc.initConditionMarket(Yss);
        VIIIBattlegroupGenerate.addMarketplace("UDM_C_T_Main", Yss, (ArrayList)null, "伊西斯", 6, new ArrayList(Arrays.asList("population_6", "terran", "farmland_bountiful", "solar_array", "mild_climate", "habitable")), new ArrayList(Arrays.asList("open_market", "black_market")), new ArrayList(Arrays.asList("population", "megaport", "orbitalworks","farming", "commerce", "militarybase")), 0.15F, false, true);

        PlanetAPI Nt = system.addPlanet("VIII_Nt", star, "努特", "barren", 230, 100, 2700, 100);
        Nt.setCustomDescriptionId("VIII_Nt");
        Nt.setFaction("UDM_C_T_Main");
        Misc.initConditionMarket(Nt);
        VIIIBattlegroupGenerate.addMarketplace("UDM_C_T_Main", Nt, (ArrayList)null, "努特", 6, new ArrayList(Arrays.asList("population_6", "terran", "farmland_bountiful", "solar_array", "mild_climate", "habitable")), new ArrayList(Arrays.asList("open_market", "black_market")), new ArrayList(Arrays.asList("population", "megaport", "orbitalworks","farming", "commerce", "militarybase")), 0.15F, false, true);

        //星门
        SectorEntityToken Tau_1_gate = system.addCustomEntity("Tau_1_gate", "Tau星门", "inactive_gate", "neutral");
        Tau_1_gate.setCircularOrbitPointingDown( star, 55, 6500, 360);
        Tau_1_gate.setCustomDescriptionId("Tau_1_gate");

        SectorEntityToken Tau_1_relay1 = system.addCustomEntity("Tau_1_relay1", // unique id
                "Tau1通讯中继器", // name - if null, defaultName from custom_entities.json will be used
                "comm_relay", // type of object, defined in custom_entities.json
                "UDM_C_T_Main"); // faction

        SectorEntityToken Tau_1_relay2 = system.addCustomEntity("guayota_relay", // unique id
                "Tau1导航浮标", // name - if null, defaultName from custom_entities.json will be used
                "nav_buoy", // type of object, defined in custom_entities.json
                "independent"); // faction
        //导航浮标位置
        Tau_1_relay2.setCircularOrbitPointingDown( star, 0 + 30, 6000, 160);
        //通讯中继器位置
        Tau_1_relay1.setCircularOrbitPointingDown( star, 45+60, 3500, 700);
        //分流器
        SectorEntityToken Tau_1_relay3 = system.addCustomEntity("Tau_1_relay3", "分流器", "coronal_tap", "neutral");
        Tau_1_relay3.setCircularOrbitPointingDown( star, 0.0F, 1300, 360);

        //我还抄一kanta之巢呢！
//        SectorEntityToken Tau_1_Station = system.addCustomEntity("Tau_1_Station", "没写", "station_side07", "UDM_C_T_Main");
//        Tau_1_Station.setCustomDescriptionId("Tau_1_Station");
//        Tau_1_Station.setInteractionImage("illustrations", "cargo_loading");
//        Tau_1_Station.setCircularOrbitWithSpin(star,  0, 3300, 160, 2, 5);
//        Tau_1_Station.setFaction("UDM_C_T_Main");
//        VIIIBattlegroupGenerate.addMarketplace("UDM_C_T_Main", Tau_1_Station, (ArrayList)null, "没写", 6,
//                new ArrayList(Arrays.asList("population_6")),
//                new ArrayList(Arrays.asList("open_market", "black_market","generic_military","storage")),
//                new ArrayList(Arrays.asList("population", "megaport", "highcommand",
//                        "orbitalworks","refining","fuelprod","starfortress_high")), 0.15F, false, true);

        //采矿站
        SectorEntityToken Tau_1_relay4 = system.addCustomEntity("Tau_1_relay3", "采矿站", "station_mining_remnant", "neutral");
        Tau_1_relay4.setCircularOrbitPointingDown( star, 0 + 30, 3300, 360);

        //TAU2的代码放在这下面
        //上面的行星最远的9500，所以这里我搞点烂活,顺带，第二颗及以上恒星应该都得走行星的生成
        PlanetAPI star2 = system.addPlanet("Tau_2", star,"Tau2","star_yellow", 775f, 500f, 10f, 1f);
        star2.setCustomDescriptionId("Tau_2_star");
        star2.setCircularOrbitPointingDown( star, 0 + 30, 18000, 360);

        PlanetAPI Geb = system.addPlanet("VIII_Geb", star2, "盖布", "terran", 180, 115, 3500, 580);
        Geb.setCustomDescriptionId("VIII_Bast");
        Geb.setFaction("UDM_C_T_Main");
        Misc.initConditionMarket(Geb);
        VIIIBattlegroupGenerate.addMarketplace("UDM_C_T_Main", Geb, (ArrayList)null, "盖布", 6, new ArrayList(Arrays.asList("population_6", "terran", "farmland_bountiful", "solar_array", "mild_climate", "habitable")), new ArrayList(Arrays.asList("open_market", "black_market")), new ArrayList(Arrays.asList("population", "megaport", "orbitalworks","farming", "commerce", "militarybase")), 0.15F, false, true);

        PlanetAPI Tefnut = system.addPlanet("VIII_Tefnut", star2, "泰夫努特", "gas_giant", 160, 120, 6000, 300);
        Tefnut.setCustomDescriptionId("VIII_Tefnut");
        Tefnut.setFaction("UDM_C_T_Main");
        Misc.initConditionMarket(Tefnut);
        VIIIBattlegroupGenerate.addMarketplace("UDM_C_T_Main", Tefnut, (ArrayList)null, "泰夫努特", 6, new ArrayList(Arrays.asList("population_6", "terran", "farmland_bountiful", "solar_array", "mild_climate", "habitable")), new ArrayList(Arrays.asList("open_market", "black_market")), new ArrayList(Arrays.asList("population", "megaport", "orbitalworks","farming", "commerce", "militarybase")), 0.15F, false, true);

        system.addAsteroidBelt(star2, 200, 2000, 100, 30, 40, Terrain.ASTEROID_BELT, null);
        system.addAsteroidBelt(star2, 200, 7000, 256, 150, 250, Terrain.ASTEROID_BELT, null);



        //手动跳跃点
        this.addJumpPointAroundPlanet1(system, star, 2500.0F);
        this.addJumpPointAroundPlanet2(system, Yss, 500.0F);
        this.addJumpPointAroundPlanet3(system, star2, 4000.0F);
        this.addJumpPointAroundPlanet4(system, Tefnut, 500.0F);
        //自动跳跃点，不生成新跳跃点也能把星系内跳跃点的超空间部分补全
        system.autogenerateHyperspaceJumpPoints(false, false);



    }



    //跳跃点的用法，虽然不知道对不对，但是代码跑起来了
    private void addJumpPointAroundPlanet1(StarSystemAPI system, PlanetAPI star, float distance){
        SectorEntityToken VIII_Tau_1_jumpPoint1 = Global.getFactory().createJumpPoint(star.getId() + "_jumppoint", "跳跃点 ");
        VIII_Tau_1_jumpPoint1.setCircularOrbit(star, 0.0F, 2000, 50.0F);
        VIII_Tau_1_jumpPoint1.setCustomDescriptionId("VIII_Tau_1_jumpPoint1");
        system.addEntity(VIII_Tau_1_jumpPoint1);
    }
    private void addJumpPointAroundPlanet2(StarSystemAPI system, PlanetAPI Yss, float distance){
        SectorEntityToken VIII_Tau_1_jumpPoint2 = Global.getFactory().createJumpPoint(Yss.getId() + "_jumppoint", "跳跃点");
        VIII_Tau_1_jumpPoint2.setCircularOrbit(Yss, 0.0F, 800, 50.0F);
        VIII_Tau_1_jumpPoint2.setCustomDescriptionId("VIII_Tau_1_jumpPoint1");
        system.addEntity(VIII_Tau_1_jumpPoint2);
    }


    private void addJumpPointAroundPlanet3(StarSystemAPI system, PlanetAPI star2, float v) {
        SectorEntityToken VIII_Tau_1_jumpPoint2 = Global.getFactory().createJumpPoint(star2.getId() + "_jumppoint", "跳跃点");
        VIII_Tau_1_jumpPoint2.setCircularOrbit(star2, 0.0F, 2000, 50.0F);
        VIII_Tau_1_jumpPoint2.setCustomDescriptionId("VIII_Tau_2_jumpPoint1");
        system.addEntity(VIII_Tau_1_jumpPoint2);
    }

    private void addJumpPointAroundPlanet4(StarSystemAPI system, PlanetAPI tefnut, float v) {
        SectorEntityToken VIII_Tau_1_jumpPoint2 = Global.getFactory().createJumpPoint(tefnut.getId() + "_jumppoint", "跳跃点");
        VIII_Tau_1_jumpPoint2.setCircularOrbit(tefnut, 0.0F, 800, 50.0F);
        VIII_Tau_1_jumpPoint2.setCustomDescriptionId("VIII_Tau_2_jumpPoint2");
        system.addEntity(VIII_Tau_1_jumpPoint2);
    }
}