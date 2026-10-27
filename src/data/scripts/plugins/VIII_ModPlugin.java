package data.scripts.plugins;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.PluginPick;
import com.fs.starfarer.api.VersionInfoAPI;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.SubmarketAPI;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Conditions;
import com.fs.starfarer.api.impl.campaign.ids.Personalities;
import com.fs.starfarer.api.impl.campaign.ids.Submarkets;
import com.fs.starfarer.api.impl.campaign.intel.FactionHostilityIntel;
import com.fs.starfarer.api.impl.campaign.intel.FactionHostilityManager;
import com.fs.starfarer.api.impl.campaign.intel.bar.events.BarEventManager;
import com.fs.starfarer.api.impl.campaign.intel.events.HostileActivityEventIntel;
import com.fs.starfarer.api.impl.campaign.intel.events.HostileActivityManager;
import com.fs.starfarer.api.impl.campaign.intel.punitive.PunitiveExpeditionManager;
import com.fs.starfarer.api.impl.campaign.submarkets.StoragePlugin;
import com.fs.starfarer.api.util.Misc;
import data.scripts.VIII_threat.VIII_ThreatSwarmAI;
import exerelin.ExerelinConstants;
import exerelin.campaign.*;
import exerelin.campaign.ai.MilitaryInfoHelper;
import exerelin.campaign.ai.StrategicAI;
import exerelin.campaign.battle.EncounterLootHandler;
import exerelin.campaign.econ.EconomyInfoHelper;
import exerelin.campaign.econ.FactionConditionPlugin;
import exerelin.campaign.econ.FleetPoolManager;
import exerelin.campaign.econ.GroundPoolManager;
import exerelin.campaign.fleets.InvasionFleetManager;
import exerelin.campaign.fleets.MiningFleetManagerV2;
import exerelin.campaign.fleets.PlayerInSystemTracker;
import exerelin.campaign.fleets.VultureFleetManager;
import exerelin.campaign.graphics.MiningCooldownDrawerV2;
import exerelin.campaign.intel.*;
import exerelin.campaign.intel.agents.AgentBarEventCreator;
import exerelin.campaign.intel.bases.Nex_LuddicPathBaseManager;
import exerelin.campaign.intel.bases.Nex_PirateBaseManager;
import exerelin.campaign.intel.hostileactivity.NexHostileActivityManager;
import exerelin.campaign.intel.merc.MercSectorManager;
import exerelin.campaign.intel.missions.ConquestMissionManager;
import exerelin.campaign.intel.missions.remnant.RemnantLostScientist;
import exerelin.campaign.intel.rebellion.RebellionCreator;
import exerelin.campaign.intel.specialforces.SpecialForcesManager;
import exerelin.campaign.submarkets.PrismMarket;
import exerelin.campaign.ui.PlayerFactionSetupNag;
import exerelin.campaign.ui.ReinitScreenScript;
import exerelin.plugins.ExerelinCampaignPlugin;
import exerelin.plugins.ScriptReplacer;
import exerelin.utilities.*;
import exerelin.utilities.versionchecker.VCModPluginCustom;
import exerelin.world.SSP_AsteroidTracker;
import exerelin.world.scenarios.ScenarioManager;

import static exerelin.plugins.ExerelinModPlugin.MEM_KEY_VERSION;


public class VIII_ModPlugin extends BaseModPlugin {
    public static boolean particleEngineEnabled = false;
    protected static boolean isNewGame = false;
    public static final boolean HAVE_LUNALIB = Global.getSettings().getModManager().isModEnabled("lunalib");
    public static final boolean HAVE_VERSION_CHECKER = Global.getSettings().getModManager().isModEnabled("lw_version_checker");


    public VIII_ModPlugin() {

    }

    public static boolean hasGraphicsLib;

    public void syncRSMODScripts() {
        this.addScriptsIfNeeded();
    }


    @Override
    public void onApplicationLoad() {

    }

    protected void addScriptsIfNeeded() {
    }

    public void onNewGame() {
        //Nex compatibility setting, if there is no nex or corvus mode(Nex), just generate the system
        boolean haveNexerelin = Global.getSettings().getModManager().isModEnabled("nexerelin");
        if (haveNexerelin) {
            SectorManager.getCorvusMode();
        }
    }

    protected void addTransientScriptsAndListeners(boolean newGame) {
        SectorAPI sector = Global.getSector();
        //sector.addTransientScript(new FieldOptionsScreenScript());
        sector.addTransientScript(new SSP_AsteroidTracker());
        sector.addTransientListener(new EncounterLootHandler());
        PlayerInSystemTracker.create();
        MiscEventsManager.create();
        TransponderCheckBlockScript.create();

        if (!Misc.isPlayerFactionSetUp())
            sector.addTransientScript(new PlayerFactionSetupNag());

        if (NexConfig.updateMarketDescOnCapture && MarketDescChanger.getInstance() == null) {
            sector.getListenerManager().addListener(new MarketDescChanger().registerInstance(), true);
        }

        if (!newGame) {
            EconomyInfoHelper.createInstance(true);
            MilitaryInfoHelper.createInstance(true);
        }


        Global.getSector().getListenerManager().addListener(new MiningCooldownDrawerV2(), true);

        Global.getSector().addTransientScript(new BattleForceJoinHelper());
    }


    @Override
    public void onEnabled(boolean wasEnabledBefore) {
        if (!isNewGame && !wasEnabledBefore)
        {
            applyToExistingSave();
        }
    }

    @Override
    public void onGameLoad(boolean newGame) {
        if (HAVE_LUNALIB) {
            LunaConfigHelper.tryLoadLunaConfigOnGameLoad();
        }

        isNewGame = newGame;

        this.syncRSMODScripts();

        ScenarioManager.clearScenario();

        addScriptsAndEventsIfNeeded();

        if (!newGame) reverseCompatibility();
        refreshMarketSettings();
        refreshFactionSettings();

        SectorAPI sector = Global.getSector();
        sector.registerPlugin(new ExerelinCampaignPlugin());
        addTransientScriptsAndListeners(newGame);
        //sector.removeScriptsOfClass(FactionHostilityManager.class);

        PrismMarket.clearSubmarketCache();

        ColonyManager.getManager().updatePlayerBonusAdmins();
        ColonyManager.updateIncome();

        if (!HAVE_VERSION_CHECKER && Global.getSettings().getBoolean("nex_enableVersionChecker"))
            VCModPluginCustom.onGameLoad(newGame);

        if (NexConfig.enableStrategicAI){
            StrategicAI.addAIsIfNeeded();
        } else {
            StrategicAI.removeAIs();
        }

        if (PersonalConfigIntel.get() == null) {
            PersonalConfigIntel.create();
        }
        Global.getSector().getListenerManager().addListener(PersonalConfigIntel.get(), true);

        for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
            ColonyManager.getManager().checkFactionMarketCondition(market);
        }

        // call listeners; should be done last
        for (ModPluginEventListener x : Global.getSector().getListenerManager().getListeners(ModPluginEventListener.class))
        {
            x.onGameLoad(newGame);
        }

        checkSelfVersion();
    }

    protected void refreshMarketSettings()
    {
        for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy())
        {
            NexUtilsMarket.setTariffs(market);
            if (ColonyManager.getManager() != null)    // will be null if loading non-Nex save
                ColonyManager.getManager().setGrowthRate(market);
        }
    }

    protected void refreshFactionSettings() {
        for (NexFactionConfig conf : NexConfig.getAllFactionConfigsCopy()) {
            conf.updateBaseAlignmentsInMemory();
        }
    }

    protected void checkSelfVersion() {
        VersionInfoAPI curr = Global.getSettings().getModManager().getModSpec(ExerelinConstants.MOD_ID).getVersionInfo();

        // TODO: compare versions and apply stuff if needed? this probably ought to be in its own class
        Global.getSector().getMemoryWithoutUpdate().set(MEM_KEY_VERSION, curr);
    }

    public static void addScripts() {
        SectorAPI sector = Global.getSector();
        new FleetPoolManager().init();
        new GroundPoolManager().init();
        sector.addScript(SectorManager.create());
        sector.addScript(DiplomacyManager.create());
        sector.addScript(InvasionFleetManager.create());
        //sector.addScript(ResponseFleetManager.create());
        sector.addScript(MiningFleetManagerV2.create());
        sector.addScript(VultureFleetManager.create());
        sector.addScript(CovertOpsManager.create());
        sector.addScript(AllianceManager.create());
        new ColonyManager().init();
        new RevengeanceManager().init();
        new SpecialForcesManager().init();
        RebellionCreator.generate();

        sector.addScript(new ConquestMissionManager());
        //sector.addScript(new DisruptMissionManager());
        sector.addScript(new FactionBountyManager());

        new MilestoneTracker().init();

        new MercSectorManager().init();

        addBarEvents();

        //DebugIntel.createIntel();
    }

    // Stuff here should be moved to new game once it is expected that no existing saves lack them
    protected void addScriptsAndEventsIfNeeded() {
        if (GroundPoolManager.getManager() == null) {
            GroundPoolManager man = new GroundPoolManager();
            man.init();
            man.initPointsFromIFM();
        }
    }

    protected static void addBarEvents() {
        BarEventManager bar = BarEventManager.getInstance();
        if (bar != null && !bar.hasEventCreator(AgentBarEventCreator.class)) {
            bar.addEventCreator(new AgentBarEventCreator());
        }
    }

    protected void applyToExistingSave()
    {

        SectorAPI sector = Global.getSector();
        addScripts();

        // debugging
        //im.advance(sector.getClock().getSecondsPerDay() * ExerelinConfig.invasionGracePeriod);
        //am.advance(sector.getClock().getSecondsPerDay() * ExerelinConfig.allianceGracePeriod);

        // replace or remove relevant intel items
        for (IntelInfoPlugin iip : Global.getSector().getIntelManager().getIntel(FactionHostilityIntel.class)) {
            FactionHostilityIntel host = (FactionHostilityIntel)iip;
            host.endHostilties();
        }
        ScriptReplacer.replaceScript(sector, FactionHostilityManager.class, null);
        //ScriptReplacer.replaceScript(sector, PirateBaseManager.class, new Nex_PirateBaseManager());
        //ScriptReplacer.replaceScript(sector, LuddicPathBaseManager.class, new Nex_LuddicPathBaseManager());
        Nex_PirateBaseManager.replaceManager();
        Nex_LuddicPathBaseManager.replaceManager();
        ScriptReplacer.replaceScript(sector, HostileActivityManager.class, new NexHostileActivityManager());
        HostileActivityEventIntel ha = HostileActivityEventIntel.get();
        if (ha != null) {
            int prog = ha.getProgress();
            ha.endImmediately();
            new HostileActivityEventIntel();
            NexHostileActivityManager.purgeOldListeners();
            NexHostileActivityManager.replaceVanillaOverrideActivities(ha);
            ha.setProgress(prog);
        }

        //ScriptReplacer.replaceScript(sector, HegemonyInspectionManager.class, new Nex_HegemonyInspectionManager());   // inspection manager no longer used
        ScriptReplacer.replaceScript(sector, PunitiveExpeditionManager.class, new Nex_PunitiveExpeditionManager());
        //ScriptReplacer.replaceMissionCreator(ProcurementMissionCreator.class, new Nex_ProcurementMissionCreator());
        for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy())
        {
            replaceSubmarket(market, Submarkets.LOCAL_RESOURCES);
            replaceSubmarket(market, Submarkets.SUBMARKET_OPEN);
            replaceSubmarket(market, Submarkets.GENERIC_MILITARY);
            replaceSubmarket(market, Submarkets.SUBMARKET_BLACK);
            replaceSubmarket(market, Submarkets.SUBMARKET_STORAGE);
        }

        for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy())
        {
            if (!market.getMemoryWithoutUpdate().contains(ExerelinConstants.MEMKEY_MARKET_STARTING_FACTION))
                market.getMemoryWithoutUpdate().set(ExerelinConstants.MEMKEY_MARKET_STARTING_FACTION, market.getFactionId());

            market.getMemoryWithoutUpdate().set(ExerelinConstants.MEMKEY_MARKET_EXISTED_AT_START, true);
            market.getMemoryWithoutUpdate().set("$startingFreeMarket", market.hasCondition(Conditions.FREE_PORT) || market.isFreePort());
            ColonyManager.updateFreePortSetting(market);
        }

        StatsTracker.getOrCreateTracker();

        SectorManager.reinitLiveFactions();
        NexUtilsReputation.syncFactionRelationshipsToPlayer();

        // add Follow Me ability
        Global.getSector().getCharacterData().addAbility("exerelin_follow_me");
        PersistentUIDataAPI.AbilitySlotsAPI slots = Global.getSector().getUIData().getAbilitySlotsAPI();
        for (PersistentUIDataAPI.AbilitySlotAPI slot: slots.getCurrSlotsCopy())
        {
            if (slot.getAbilityId() == null || slot.getAbilityId().isEmpty())
            {
                slot.setAbilityId("exerelin_follow_me");
                break;
            }
        }

        new exerelin.world.ExerelinNewGameSetup().addPrismMarket(Global.getSector(), false);

        sector.addTransientScript(new ReinitScreenScript());
    }
    public static void replaceSubmarket(MarketAPI market, String submarketId) {
        if (!market.hasSubmarket(submarketId)) return;

        CargoAPI current = market.getSubmarket(submarketId).getCargo();
        FleetDataAPI ships = current.getMothballedShips();
        boolean haveAccess = Misc.playerHasStorageAccess(market);

        market.removeSubmarket(submarketId);
        market.addSubmarket(submarketId);
        SubmarketAPI submarket = market.getSubmarket(submarketId);

        // migrate cargo
        CargoAPI newCargo = market.getSubmarket(submarketId).getCargo();
        newCargo.clear();
        newCargo.addAll(current);
        newCargo.sort();

        // move ships to new cargo
        newCargo.initMothballedShips(submarket.getFaction().getId());
        for (FleetMemberAPI ship : ships.getMembersListCopy()) {
            newCargo.getMothballedShips().addFleetMember(ship);
        }

        if (submarketId.equals(Submarkets.SUBMARKET_STORAGE)) {
            ((StoragePlugin)submarket.getPlugin()).setPlayerPaidToUnlock(haveAccess);
        }
    }

    protected void reverseCompatibility()
    {
        ScriptReplacer.replaceScript(Global.getSector(), HostileActivityManager.class, new NexHostileActivityManager());

        SectorManager.getManager().reverseCompatibility();

        for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
            if (!market.hasCondition(FactionConditionPlugin.CONDITION_ID)) {
                market.addCondition(FactionConditionPlugin.CONDITION_ID);
            }
        }

        if (MiningCooldownDrawer.getEntity() != null)
            MiningCooldownDrawer.remove();

        if (!Global.getSector().getListenerManager().hasListener(DiplomacyManager.getManager())) {
            Global.getSector().getListenerManager().addListener(DiplomacyManager.getManager());
        }

        if (InsuranceIntelV2.getInstance() != null) InsuranceIntelV2.getInstance().reverseCompatibility();

        RemnantLostScientist.reverseCompatibilityStatic();
    }

    // ==================== 舰船AI选择 ====================
    // 重写父类方法：根据舰船类型和标签，选择合适的舰船AI插件
    @Override
    public PluginPick<ShipAIPlugin> pickShipAI(FleetMemberAPI member, ShipAPI ship) {
        // 如果当前舰船是战机（Fighter）
        if (ship.isFighter()) {
            // 如果战机船体规格包含“THREAT_SWARM_AI”标签（威胁派系群AI）
            if (ship.getHullSpec().hasTag("VIII_threat_swarm_ai")) {
                // 返回一个威胁群AI实例，优先级为“核心特定”（CORE_SPECIFIC）
                return new PluginPick<>(new VIII_ThreatSwarmAI(ship), CampaignPlugin.PickPriority.CORE_SPECIFIC);
            }
            // 其他战机不指定特殊AI，返回null（使用默认AI）
            return null;
        }

        // 如果舰船船体规格包含“THREAT”标签（威胁派系舰船）
        if (ship.getHullSpec().hasTag("VIII_threat")) {
            // 创建舰船AI配置对象
            ShipAIConfig config = new ShipAIConfig();

            // 根据性格标签设置AI的个性覆盖
            if (ship.getHullSpec().hasTag("VIII_threat_timid")) {
                // 胆小性格
                config.personalityOverride = Personalities.TIMID;
            } else if (ship.getHullSpec().hasTag("VIII_threat_cautious")) {
                // 谨慎性格
                config.personalityOverride = Personalities.CAUTIOUS;
            } else if (ship.getHullSpec().hasTag("VIII_threat_aggressive")) {
                // 激进性格（注释中原本可能设置进攻性行为，但被注释掉了）
                config.personalityOverride = Personalities.AGGRESSIVE;
            } else if (ship.getHullSpec().hasTag("VIII_threat_reckless")) {
                // 鲁莽性格，并设置一系列进攻性行为标志
                config.personalityOverride = Personalities.RECKLESS;
                config.alwaysStrafeOffensively = true;                 // 始终进攻性平移
                config.backingOffWhileNotVentingAllowed = false;       // 不允许非排辐能时后退
                config.turnToFaceWithUndamagedArmor = false;           // 不为了未受损装甲而转向
                config.burnDriveIgnoreEnemies = true;                  // 燃烧驱动时忽略敌人阻挡
            } else {
                // 默认稳重性格
                config.personalityOverride = Personalities.STEADY;
            }

            // 优先级为“核心设置”（CORE_SET）
            CampaignPlugin.PickPriority priority = CampaignPlugin.PickPriority.CORE_SET;
            // 使用游戏设置创建默认的舰船AI，并传入配置，包装为PluginPick返回
            return new PluginPick<>(Global.getSettings().createDefaultShipAI(ship, config), priority);
        }
        // 对于其他普通舰船，返回null（使用默认AI逻辑）
        return null;
    }


}