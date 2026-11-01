package data.scripts.shipsystems.ai;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAIScript;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.util.IntervalUtil;

/**
 * 「熵谐振器」的自定义系统 AI。
 * <p>
 * 效果是"母舰 + 自家舰载机 + 自家攻击群的时间流速 ×3，同时全武器射速 ×2"，
 * 前摇长达 4 秒、持续 10 秒、冷却 10 秒，所以应该在交火前提前按下，
 * 而不是等到被贴脸才开。
 * <p>
 * 开启条件（全部满足）：
 * - 系统空闲；
 * - 幅能等级 &lt; 60%（系统本身按每秒 1 点吃幅能，开早了会顶到过载）；
 * - 2500 距离内有敌方舰船（不看战机，时间加速主要用来压制主力舰）；
 * - 并且"要么场上有自己的舰载机、要么敌人已经进入 1200"，否则加速无处兑现。
 */
public class VIII_EntropyResonatorAI implements ShipSystemAIScript {

    private static final float TRIGGER_RANGE = 2500.0F;
    private static final float CLOSE_RANGE = 1200.0F;
    private static final float MAX_FLUX_LEVEL = 0.60F;

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
        if (ship.getFluxTracker().getFluxLevel() > MAX_FLUX_LEVEL) {
            return;
        }

        float distance = VIII_SystemAIUtils.nearestEnemyDistance(engine, ship, true);
        if (distance > TRIGGER_RANGE) {
            return;
        }
        if (distance > CLOSE_RANGE && !VIII_SystemAIUtils.hasOwnFighters(engine, ship)) {
            return;
        }

        ship.useSystem();
    }
}
