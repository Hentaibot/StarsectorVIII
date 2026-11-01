package data.scripts.shipsystems.ai;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAIScript;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.util.IntervalUtil;

/**
 * 「闪流驱动器」的自定义系统 AI。
 * <p>
 * 这个系统在开启期间会强制收盾（DISABLE_SHIELD_WHILE_ACTIVE），换取 50 倍时间流速
 * 与大量机动/射速增益，属于"高风险高收益"的爆发手段。所以 AI 要满足两个场景之一才开：
 * <p>
 * 1. 常规输出：幅能 &lt; 55%、1400 距离内有敌方单位（含战机）；
 * 2. 保命：船体低于 35% 且 1000 距离内有敌人（此时时间加速就是最好的护盾）。
 * <p>
 * 除此之外还要求系统空闲、本舰未过载/未排散（过载时开系统只会更糟）。
 */
public class VIII_FlashstreamDriveAI implements ShipSystemAIScript {

    private static final float TRIGGER_RANGE = 1400.0F;
    private static final float MAX_FLUX_LEVEL = 0.55F;
    private static final float PANIC_HULL_LEVEL = 0.35F;
    private static final float PANIC_RANGE = 1000.0F;

    private final IntervalUtil tracker = new IntervalUtil(0.3F, 0.5F);

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
        if (engine.isPaused() || !VIII_SystemAIUtils.isOperational(ship)) {
            return;
        }

        tracker.advance(amount);
        if (!tracker.intervalElapsed()) {
            return;
        }

        if (!VIII_SystemAIUtils.isSystemReady(system)) {
            return;
        }

        float distance = VIII_SystemAIUtils.nearestEnemyDistance(engine, ship, false);
        if (isPanic(distance)) {
            ship.useSystem();
            return;
        }

        if (ship.getFluxTracker().getFluxLevel() > MAX_FLUX_LEVEL) {
            return;
        }
        if (distance > TRIGGER_RANGE) {
            return;
        }

        ship.useSystem();
    }

    /** 船体告急且有敌人贴脸时不计幅能，直接开 */
    private boolean isPanic(float distance) {
        if (distance > PANIC_RANGE) {
            return false;
        }
        float maxHitpoints = ship.getMaxHitpoints();
        if (maxHitpoints <= 0f) {
            return true;
        }
        return ship.getHitpoints() / maxHitpoints < PANIC_HULL_LEVEL;
    }
}
