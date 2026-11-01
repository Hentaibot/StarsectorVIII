package data.scripts.shipsystems.ai;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAIScript;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.util.IntervalUtil;

/**
 * 「突击闪现」的自定义系统 AI。
 * <p>
 * 这个系统在激活期间本身不产生任何效果，真正的收益是"结束后开始的 3 秒
 * +50% 全武器射速"，所以判定重点是：现在按下，增益能不能刚好覆盖到交火窗口。
 * <p>
 * 开启条件（全部满足）：
 * - 系统空闲（2 次充能、充能很慢，没有目标时不要浪费）；
 * - 幅能等级 &lt; 90%（系统本身不吃幅能，但需要留给开火与散耗）；
 * - 1800 距离内有任何敌方单位（含战机，清理战机编队时这个增益同样有效）。
 */
public class VIII_DisplacerAI implements ShipSystemAIScript {

    private static final float TRIGGER_RANGE = 1800.0F;
    private static final float MAX_FLUX_LEVEL = 0.90F;

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
        if (ship.getFluxTracker().getFluxLevel() > MAX_FLUX_LEVEL) {
            return;
        }
        if (VIII_SystemAIUtils.nearestEnemyDistance(engine, ship, false) > TRIGGER_RANGE) {
            return;
        }

        ship.useSystem();
    }
}
