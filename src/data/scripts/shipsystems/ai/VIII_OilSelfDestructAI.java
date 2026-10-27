package data.scripts.shipsystems.ai;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAIScript;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import java.util.List;
import org.lwjgl.util.vector.Vector2f;

/**
 * VIII_oil 自爆系统的 AI：只有当油罐已经脱离母舰、并且最近的敌舰进入触发距离时
 * 才会按下系统，其余时候一律不启用。
 * <p>
 * 判定顺序（每 0.4~0.6 秒检查一次，避免每帧遍历战场）：
 * 1. 舰船存活且已经脱离（{@code getStationSlot() == null}），还挂在母舰上的油罐不放；
 * 2. 系统不在激活 / 冷却 / 无弹药状态；
 * 3. 找到最近的敌对舰船，距离 ≤ {@link #TRIGGER_RANGE} 时调用 {@code ship.useSystem()}。
 */
public class VIII_OilSelfDestructAI implements ShipSystemAIScript {

    /** 触发距离：最近的敌舰进入这个范围才自爆 */
    private static final float TRIGGER_RANGE = 500.0F;

    /** 决策间隔（最小、最大秒数），随机化以错开多艘油罐的判定 */
    private final IntervalUtil tracker = new IntervalUtil(0.4F, 0.6F);

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
        if (!ship.isAlive()) {
            return;
        }
        // 还挂在母舰上的油罐不引爆
        if (ship.getStationSlot() != null) {
            return;
        }
        tracker.advance(amount);
        if (!tracker.intervalElapsed()) {
            return;
        }
        // 正在充能 / 已激活 / 冷却中 / 没弹药都不重复按
        if (system.isActive() || system.isCoolingDown() || system.isOutOfAmmo()) {
            return;
        }
        ShipAPI nearest = findNearestEnemy();
        if (nearest == null) {
            return;
        }
        float dist = Misc.getDistance(ship.getLocation(), nearest.getLocation());
        if (dist <= TRIGGER_RANGE) {
            ship.useSystem();
        }
    }

    /** 找最近的、还活着的敌对舰船（跳过残骸与同阵营） */
    private ShipAPI findNearestEnemy() {
        List<ShipAPI> ships = engine.getShips();
        ShipAPI nearest = null;
        float nearestDist = Float.MAX_VALUE;
        for (int i = 0; i < ships.size(); i++) {
            ShipAPI other = ships.get(i);
            if (other == null || other == ship || !other.isAlive() || other.isHulk()) {
                continue;
            }
            if (other.getOwner() == ship.getOwner()) {
                continue;
            }
            float dist = Misc.getDistance(ship.getLocation(), other.getLocation());
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = other;
            }
        }
        return nearest;
    }
}
