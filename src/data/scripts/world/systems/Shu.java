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

public class Shu {
    public void generate(SectorAPI sector) {
        //生成星系
        StarSystemAPI system = sector.createStarSystem("Shu");
        //星系位置，50000/-50000贴边
        system.getLocation().set(-16000.0F, -30000.0F);
        //背景图，不过一般来说应该没人看（
        system.setBackgroundTextureFilename("graphics/backgrounds/background4.jpg");
        //打光颜色，不重要，一般没人看，抄一个就是
        system.setLightColor(new Color(225, 245, 255));
        //坐下一个新恒星，star唯一，多星系统的其他恒星用星球添加用法添加
        //我这里就延续传统用星系名命名恒星
        PlanetAPI star = system.initStar("Shu", "black_hole", 900f, 500f, 3f, 0.75f, 0f);
        //恒星的描述
        star.setCustomDescriptionId("VIII_Shu_star");
        //小行星带
        system.addAsteroidBelt(star, 100, 3600, 100, 30, 40, Terrain.ASTEROID_BELT, null);
        //
        //星门
        //
        SectorEntityToken Shu_gate = system.addCustomEntity("Shu_gate", "中转星门", "inactive_gate", "neutral");
        Shu_gate.setCircularOrbitPointingDown(star, 55, 6500, 360);
        Shu_gate.setCustomDescriptionId("Shu_gate");
        //两个应该出现的东西
        SectorEntityToken Shu_relay1 = system.addCustomEntity("Shu_relay1", // unique id
                "Shu通讯中继器", // name - if null, defaultName from custom_entities.json will be used
                "comm_relay", // type of object, defined in custom_entities.json
                "UDM_C_T_Main"); // faction

        SectorEntityToken Shu_relay2 = system.addCustomEntity("Shu_relay2", // unique id
                "Shu导航浮标", // name - if null, defaultName from custom_entities.json will be used
                "nav_buoy", // type of object, defined in custom_entities.json
                "independent"); // faction
        //导航浮标位置
        Shu_relay2.setCircularOrbitPointingDown(star, 0 + 30, 6000, 160);
        //通讯中继器位置
        Shu_relay1.setCircularOrbitPointingDown(star, 45 + 60, 3500, 700);
        //空间桥占位符
        SectorEntityToken Shu_relay3 = system.addCustomEntity("Shu_relay3", "伪装成空间桥的分流器", "coronal_tap", "neutral");
        Shu_relay3.setCircularOrbitPointingDown( star, 0.0F, 1300, 360);


        //自动跳跃点，不生成新跳跃点也能把星系内跳跃点的超空间部分补全
        system.autogenerateHyperspaceJumpPoints(true, true);

        //刷新地图用
        SectorEntityToken Shu_Station_2 = system.addCustomEntity("Shu_Station_2", "刷新地图免得亲自跑一趟用", "station_hightech2", "UDM_C_T_Main");
        Shu_Station_2.setCustomDescriptionId("Shu_Station_2");
        Shu_Station_2.setInteractionImage("illustrations", "cargo_loading");
        Shu_Station_2.setCircularOrbitWithSpin(star,  0, 3800, 160, 2, 5);
        Shu_Station_2.setFaction("UDM_C_T_Main");
        VIIIBattlegroupGenerate.addMarketplace("UDM_C_T_Main", Shu_Station_2, (ArrayList)null, "刷新地图免得亲自跑一趟用", 6,
                new ArrayList(Arrays.asList("population_6")),
                new ArrayList(Arrays.asList("open_market", "black_market","generic_military","storage")),
                new ArrayList(Arrays.asList("population", "megaport", "highcommand",
                        "orbitalworks","refining","fuelprod","starfortress_high")), 0.15F, false, true);

    }
}
