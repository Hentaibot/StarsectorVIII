package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;


public class VIII_Recover extends BaseHullMod {
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
        stats.getHullCombatRepairRatePercentPerSecond().modifyFlat(id, 100F);
        stats.getMaxCombatHullRepairFraction().modifyFlat(id, 100F);

    }
}
