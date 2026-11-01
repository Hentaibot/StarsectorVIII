package data.scripts.shipsystems.ai;

import java.util.List;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.FighterWingAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * 本 mod 战术系统 AI 共用的判定工具。
 * <p>
 * 只放纯查询逻辑，不持有状态；所有方法对 null 入参都做了保护，
 * 方便各个 AI 在战斗早期（实体还没完全就绪）安全调用。
 */
public final class VIII_SystemAIUtils {

    private VIII_SystemAIUtils() {
    }

    /**
     * 距离本舰最近的敌对目标距离。
     *
     * @param skipFighters true 时忽略敌方战机，只看主力舰/驱逐舰等
     * @return 没有可用目标时返回 {@link Float#MAX_VALUE}
     */
    public static float nearestEnemyDistance(CombatEngineAPI engine, ShipAPI ship, boolean skipFighters) {
        if (engine == null || ship == null) {
            return Float.MAX_VALUE;
        }
        List<ShipAPI> ships = engine.getShips();
        if (ships == null) {
            return Float.MAX_VALUE;
        }
        float best = Float.MAX_VALUE;
        for (ShipAPI other : ships) {
            if (!isHostile(ship, other)) {
                continue;
            }
            if (skipFighters && other.isFighter()) {
                continue;
            }
            float dist = Misc.getDistance(ship.getLocation(), other.getLocation());
            if (dist < best) {
                best = dist;
            }
        }
        return best;
    }

    /** 场上是否还有本舰放出的、存活的舰载机。 */
    public static boolean hasOwnFighters(CombatEngineAPI engine, ShipAPI ship) {
        if (engine == null || ship == null) {
            return false;
        }
        List<ShipAPI> ships = engine.getShips();
        if (ships == null) {
            return false;
        }
        for (ShipAPI other : ships) {
            if (other == null || !other.isAlive() || other.isHulk() || !other.isFighter()) {
                continue;
            }
            FighterWingAPI wing = other.getWing();
            if (wing != null && wing.getSourceShip() == ship) {
                return true;
            }
        }
        return false;
    }

    /** 本舰是否处于"可以正常开系统"的状态：存活、非残骸、未过载/排散、控制未被锁。 */
    public static boolean isOperational(ShipAPI ship) {
        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            return false;
        }
        if (ship.controlsLocked()) {
            return false;
        }
        if (ship.getFluxTracker() == null) {
            return true;
        }
        return !ship.getFluxTracker().isOverloadedOrVenting();
    }

    /** 系统是否处于"可以再按一次"的空闲状态。 */
    public static boolean isSystemReady(ShipSystemAPI system) {
        if (system == null) {
            return false;
        }
        return !system.isActive() && !system.isOn() && !system.isCoolingDown() && !system.isOutOfAmmo();
    }

    private static boolean isHostile(ShipAPI self, ShipAPI other) {
        if (other == null || other == self || !other.isAlive() || other.isHulk()) {
            return false;
        }
        return other.getOwner() != self.getOwner();
    }
}
