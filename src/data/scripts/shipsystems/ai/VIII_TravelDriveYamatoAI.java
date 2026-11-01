package data.scripts.shipsystems.ai;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAIScript;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.util.IntervalUtil;

/**
 * 「波动推进」的自定义系统 AI。
 * <p>
 * 这是一个 toggle 型烈焰推进：开启时最大航速 +200、加减速 +200，但转向角速度只剩 10%，
 * 并伴随机动性代价与 2 倍质量。所以它只适合"远距离直线冲锋"，贴脸时开只会让自己
 * 变成靶子，而关掉它需要再按一次系统键。
 * <p>
 * 逻辑：
 * - 关闭态：系统空闲、幅能 &lt; 60%、最近的敌方舰船在 1200~4000 之间 → 开启（远距离突进）。
 * - 开启态：距离 &lt; 700（已经冲到目标身边，该刹车转炮了）、幅能 &gt; 80%、或目标丢失 → 关闭。
 * - 开启后至少保持 1.5 秒，避免刚点着就因为距离抖动立刻关掉。
 */
public class VIII_TravelDriveYamatoAI implements ShipSystemAIScript {

    private static final float TURN_ON_MIN_RANGE = 1200.0F;
    private static final float TURN_ON_MAX_RANGE = 4000.0F;
    private static final float TURN_OFF_RANGE = 700.0F;
    private static final float TURN_ON_MAX_FLUX = 0.60F;
    private static final float TURN_OFF_MAX_FLUX = 0.80F;
    private static final float MIN_ON_TIME = 1.5F;

    private final IntervalUtil tracker = new IntervalUtil(0.25F, 0.4F);

    private ShipAPI ship;
    private ShipSystemAPI system;
    private CombatEngineAPI engine;
    private float onTime = 0.0F;

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

        if (system.isOn()) {
            onTime += amount;
        } else {
            onTime = 0.0F;
        }

        tracker.advance(amount);
        if (!tracker.intervalElapsed()) {
            return;
        }

        float distance = VIII_SystemAIUtils.nearestEnemyDistance(engine, ship, true);

        if (system.isOn()) {
            if (onTime < MIN_ON_TIME) {
                return;
            }
            boolean lostTarget = distance == Float.MAX_VALUE || distance > TURN_ON_MAX_RANGE;
            boolean tooClose = distance < TURN_OFF_RANGE;
            boolean tooHot = ship.getFluxTracker().getFluxLevel() > TURN_OFF_MAX_FLUX;
            if (lostTarget || tooClose || tooHot) {
                ship.useSystem();
            }
            return;
        }

        if (!VIII_SystemAIUtils.isSystemReady(system)) {
            return;
        }
        if (ship.getFluxTracker().getFluxLevel() > TURN_ON_MAX_FLUX) {
            return;
        }
        if (distance < TURN_ON_MIN_RANGE || distance > TURN_ON_MAX_RANGE) {
            return;
        }

        ship.useSystem();
    }
}
