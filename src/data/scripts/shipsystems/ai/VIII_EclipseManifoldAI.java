package data.scripts.shipsystems.ai;

import java.util.List;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.FighterWingAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAIScript;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.util.IntervalUtil;

import com.fs.starfarer.api.util.Misc;

/**
 * 「分流发生器」的自定义系统 AI。
 * <p>
 * 开启条件（全部满足才按）：
 * - 系统不在生效/冷却/耗尽状态；
 * - 自身幅能等级 &lt; 55%（要留出 30% 的开启代价，别一开就过载）；
 * - 本舰当前有存活的舰载机在场上（否则翻倍与加速都没有意义）；
 * - 最近的非友方、非战机目标距离 &lt; 2500（太远先不开）。
 * <p>
 * 用 IntervalUtil 节流，避免每帧刷 useSystem。
 */
public class VIII_EclipseManifoldAI implements ShipSystemAIScript {

    private static final float TRIGGER_RANGE = 2500.0F;
    private static final float MAX_FLUX_LEVEL = 0.55F;

    private final IntervalUtil tracker = new IntervalUtil(0.35F, 0.55F);

    private ShipAPI ship;
    private ShipSystemAPI system;
    private CombatEngineAPI engine;

    @Override
    public void init(ShipAPI ship, ShipSystemAPI system, ShipwideAIFlags flags, CombatEngineAPI engine) {
        this.ship = ship;
        this.system = system;
        this.engine = engine;
    }

    @Override
    public void advance(float amount, Vector2f missileDangerDir, Vector2f collisionDangerDir, ShipAPI target) {
        if (ship == null || system == null || engine == null) {
            return;
        }
        if (engine.isPaused() || !ship.isAlive() || ship.isHulk() || ship.controlsLocked()) {
            return;
        }

        tracker.advance(amount);
        if (!tracker.intervalElapsed()) {
            return;
        }

        if (system.isActive() || system.isCoolingDown() || system.isOutOfAmmo()) {
            return;
        }
        if (ship.getFluxTracker().getFluxLevel() > MAX_FLUX_LEVEL) {
            return;
        }
        if (!hasOwnFightersDeployed()) {
            return;
        }
        if (getNearestEnemyDistance() > TRIGGER_RANGE) {
            return;
        }

        ship.useSystem();
    }

    /** 场上是否还有本舰自己的舰载机 */
    private boolean hasOwnFightersDeployed() {
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

    /** 最近的敌方单位距离（跳过战机/残骸/无主），没有敌人时返回 Float.MAX_VALUE */
    private float getNearestEnemyDistance() {
        List<ShipAPI> ships = engine.getShips();
        if (ships == null) {
            return Float.MAX_VALUE;
        }
        float best = Float.MAX_VALUE;
        for (ShipAPI other : ships) {
            if (other == null || other == ship || !other.isAlive() || other.isHulk()) {
                continue;
            }
            if (other.getOwner() == ship.getOwner()) {
                continue;
            }
            if (other.isFighter()) {
                continue;
            }
            float dist = Misc.getDistance(ship.getLocation(), other.getLocation());
            if (dist < best) {
                best = dist;
            }
        }
        return best;
    }
}
