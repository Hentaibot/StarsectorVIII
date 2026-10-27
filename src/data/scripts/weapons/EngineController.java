package data.scripts.weapons;

import com.fs.starfarer.api.combat.ShipAPI;

/**
 *
 * @author 49747
 */
public interface EngineController {
    float getTarget();
    float getDefault();
    float getTurnRate();
    Acc getAcc(ShipAPI ship);
    enum Acc{
        Acc,
        DeAcc,
        None
    }
}