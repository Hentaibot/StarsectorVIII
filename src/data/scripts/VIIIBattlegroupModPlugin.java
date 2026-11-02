package data.scripts;
import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.PersonImportance;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.characters.FullName;
import com.fs.starfarer.api.characters.ImportantPeopleAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.impl.campaign.ids.Ranks;
import com.fs.starfarer.api.impl.campaign.intel.contacts.ContactIntel;
import com.fs.starfarer.api.impl.campaign.missions.hub.BaseMissionHub;

public class VIIIBattlegroupModPlugin extends BaseModPlugin{

    public VIIIBattlegroupModPlugin(){
    }
    //在这下面的就是完全复制了，完全没能看懂
    //搞了这么多子类，但现在用不到，这个框架很框架
    @Override
    public void onGameLoad(boolean newGame) {

    }

    @Override
    public void beforeGameSave() {

    }

    @Override
    public void onApplicationLoad() {

    }






    @Override
    public void onNewGame() {
        //名称同另一个文件
        if (NEX()) {
            (new VIIIBattlegroupGenerate()).generate(Global.getSector());
        } else {
            (new VIIIBattlegroupGenerate()).generate(Global.getSector());
        }

//        SharedData.getData().getPersonBountyEventData().addParticipatingFaction("UDM_C_T_Main");
    }

    public static boolean NEX() {
        return Global.getSettings().getModManager().isModEnabled("nexerelin");
    }
}