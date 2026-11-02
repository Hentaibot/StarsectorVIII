package data.scripts;
//就这样

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.campaign.SectorGeneratorPlugin;
import com.fs.starfarer.api.campaign.econ.EconomyAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import data.scripts.world.systems.Atan;
import data.scripts.world.systems.Shu;
import data.scripts.world.systems.Tau1;

import java.util.ArrayList;

//搓完星系生成就在这里import导入一下星系

// 此处名称同文件
public class VIIIBattlegroupGenerate implements SectorGeneratorPlugin {
    public VIIIBattlegroupGenerate() {
    }
    @Override
    public void generate(SectorAPI sector) {
        // 在此准备生成星系
        (new Tau1()).generate(sector);
        (new Atan()).generate(sector);
        (new Shu()).generate(sector);
        this.relationAdj(sector);
    }
    protected void relationAdj(SectorAPI sector) {
    }
    //是的孩子们，从XX搓来的神秘的市场生成
    public static MarketAPI addMarketplace(String factionID, SectorEntityToken primaryEntity, ArrayList<SectorEntityToken> connectedEntities, String name, int size, ArrayList<String> marketConditions, ArrayList<String> submarkets, ArrayList<String> industries, float tarrif, boolean freePort, boolean withJunkAndChatter) {
        EconomyAPI globalEconomy = Global.getSector().getEconomy();
        String planetID = primaryEntity.getId();
        String marketID = planetID + "_market";
        MarketAPI newMarket = Global.getFactory().createMarket(marketID, name, size);
        newMarket.setFactionId(factionID);
        newMarket.setPrimaryEntity(primaryEntity);
        newMarket.getTariff().modifyFlat("generator", tarrif);
        if (null != submarkets) {
            for(String market : submarkets) {
                newMarket.addSubmarket(market);
            }
        }

        for(String condition : marketConditions) {
            newMarket.addCondition(condition);
        }

        for(String industry : industries) {
            newMarket.addIndustry(industry);
        }

        newMarket.setFreePort(freePort);
        if (null != connectedEntities) {
            for(SectorEntityToken entity : connectedEntities) {
                newMarket.getConnectedEntities().add(entity);
            }
        }

        globalEconomy.addMarket(newMarket, withJunkAndChatter);
        primaryEntity.setMarket(newMarket);
        primaryEntity.setFaction(factionID);
        if (null != connectedEntities) {
            for(SectorEntityToken entity : connectedEntities) {
                entity.setMarket(newMarket);
                entity.setFaction(factionID);
            }
        }

        return newMarket;
    }
}