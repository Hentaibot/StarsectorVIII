package data.scripts.shipsystems.ai;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAIScript;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import data.scripts.shipsystems.VIII_BoomSelfDestruct;
import java.util.List;
import org.lwjgl.util.vector.Vector2f;

/**
 * VIII_BOOM 自爆系统的 AI（基于 VIII_oil 的自爆 AI 改写，只做两处小改动）：
 * - 触发距离从油罐的 500 改成 {@link #TRIGGER_RANGE}（1200）；
 * - 只把"非战斗机"的敌对舰船当作触发目标（战斗机不参与最近目标判定）。
 * <p>
 * 其余判定顺序与油罐版一致：每 0.4~0.6 秒检查一次；系统正在激活 / 冷却 / 没弹药时不重复按；
 * 找到最近的敌方非战斗机，距离 ≤ {@link #TRIGGER_RANGE} 时才调用 {@code ship.useSystem()}。
 * <p>
 * 注意：VIII_BOOM 用的是自定义舰船 AI（{@code data.scripts.ai.VIII_BoomShipAI}），
 * 引擎不会像 BasicShipAI 那样自动推进系统 AI，所以由那个类持有本实例并每帧转发 advance。
 */
public class VIII_BoomSelfDestructAI implements ShipSystemAIScript {

    /** 触发距离：最近的敌方非战斗机进入这个范围才自爆 */
    private static final float TRIGGER_RANGE = 1200.0F;

    /** 决策间隔（最小、最大秒数），随机化以错开判定 */
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
        // 主菜单背景战斗里不要按系统（会反复引爆造成卡顿）
        if (!VIII_BoomSelfDestruct.isRealBattle(engine)) {
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

    /** 找最近的、还活着的敌对"非战斗机"舰船（跳过残骸、同阵营、战斗机） */
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
            // 只认非战斗机
            if (other.isFighter()) {
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
