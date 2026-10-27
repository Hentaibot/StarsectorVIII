package data.scripts.ai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import data.scripts.shipsystems.ai.VIII_BoomSelfDestructAI;
import java.util.List;

/**
 * VIII_BOOM「爆破单元」专用舰船 AI。
 * <p>
 * 行为只有三件事：
 * 1. 每 0.4~0.6 秒重新锁定一次"距离最近的敌方非战斗机舰船"（战机不算目标）；
 * 2. 朝目标持续加速，只允许前进与转向：每帧发 ACCELERATE，并封锁横移、倒车与减速；
 * 3. 到达系统开启距离（1200）后由自爆系统 AI 按下自爆。
 * <p>
 * 不操作武器：这艘船的任务就是撞上去自爆，所以本 AI 不发出任何开火 / 护盾 / 系统指令
 * （系统开启交给 {@link VIII_BoomSelfDestructAI}）。
 * <p>
 * 为什么要自己持有系统 AI：引擎只在 BasicShipAI / FighterAI / DroneAI 里创建并推进
 * {@code createSystemAI(...)}，一旦用 {@code ship.setShipAI(...)} 换成自定义 AI，
 * 系统 AI 就没人推进了，所以这里 init 一次、每帧转发 advance。
 */
public class VIII_BoomShipAI implements ShipAIPlugin {

    /**
     * 心跳标记：本 AI 每帧把当前战斗时间写进舰船 customData。
     * <p>
     * 之所以需要它：引擎在"舰船出场 / 建造完成 / 部署"等时机会调用
     * {@code ship.setDefaultAI(...)}，而 {@code ModManager.pickShipAIPlugin(...)}
     * 在没有插件接管时会**回退成 BasicShipAI**，把本 AI 整个替换掉。
     * 船插靠这个心跳判断"我的 AI 还在不在跑"，一旦超过阈值没更新就重新装一次。
     */
    public static final String HEARTBEAT_KEY = "VIII_boom_ai_heartbeat";

    /** 重新选目标的间隔（秒） */
    private static final float RETARGET_INTERVAL_MIN = 0.4F;
    private static final float RETARGET_INTERVAL_MAX = 0.6F;

    /** 转向死区（度）：目标大致在正前方时不再左右摆 */
    private static final float TURN_DEADZONE = 3.0F;

    private final ShipAPI ship;
    private final ShipwideAIFlags aiFlags = new ShipwideAIFlags();
    private final ShipAIConfig config = new ShipAIConfig();
    private final IntervalUtil tracker = new IntervalUtil(RETARGET_INTERVAL_MIN, RETARGET_INTERVAL_MAX);
    private final VIII_BoomSelfDestructAI systemAI = new VIII_BoomSelfDestructAI();

    private ShipAPI target = null;

    public VIII_BoomShipAI(ShipAPI ship) {
        this.ship = ship;
    }

    /**
     * 由挂载该 AI 的船插调用一次：把自爆系统 AI 绑定到这艘船上。
     *
     * @param engine 当前战斗引擎
     */
    public void initSystemAI(CombatEngineAPI engine) {
        if (ship == null || ship.getSystem() == null || engine == null) {
            return;
        }
        systemAI.init(ship, ship.getSystem(), aiFlags, engine);
    }

    @Override
    public void advance(float amount) {
        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            return;
        }
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null || engine.isPaused()) {
            return;
        }
        // 心跳：证明本 AI 这一帧确实在跑，船插据此判断是否需要重新安装
        ship.setCustomData(HEARTBEAT_KEY, Float.valueOf(engine.getTotalElapsedTime(false)));
        tracker.advance(amount);
        if (tracker.intervalElapsed() || !isValidTarget(target)) {
            target = findNearestTarget(engine);
        }
        // 没有目标就原地待命：既不转向也不加速
        if (target == null) {
            return;
        }
        steerTowards(target);
        chargeForward();
        // 到达开启距离后由系统 AI 决定是否按下自爆
        systemAI.advance(amount, null, null, target);
    }

    /** 是否是合法的敌方非战斗机目标 */
    private boolean isValidTarget(ShipAPI other) {
        return other != null && other != ship && other.isAlive() && !other.isHulk()
                && other.getOwner() != ship.getOwner() && !other.isFighter();
    }

    /** 找距离最近的敌方非战斗机舰船 */
    private ShipAPI findNearestTarget(CombatEngineAPI engine) {
        List<ShipAPI> ships = engine.getShips();
        ShipAPI nearest = null;
        float nearestDist = Float.MAX_VALUE;
        for (int i = 0; i < ships.size(); i++) {
            ShipAPI other = ships.get(i);
            if (!isValidTarget(other)) {
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

    /** 朝目标转向：目标在左侧就 TURN_LEFT，在右侧就 TURN_RIGHT（带 3 度死区） */
    private void steerTowards(ShipAPI target) {
        float desired = Misc.getAngleInDegrees(ship.getLocation(), target.getLocation());
        float diff = getShortestRotation(ship.getFacing(), desired);
        if (diff > TURN_DEADZONE) {
            ship.giveCommand(ShipCommand.TURN_LEFT, null, 0);
        } else if (diff < -TURN_DEADZONE) {
            ship.giveCommand(ShipCommand.TURN_RIGHT, null, 0);
        }
    }

    /**
     * 从 from 转到 to 的最短有符号角度差，范围 (-180, 180]。
     * <p>
     * 一定要自己算，不能用 {@link Misc#normalizeAngle(float)}：那个方法返回的是
     * [0, 360)（反编译实现为 {@code (angle % 360 + 360) % 360}），永远不会是负数，
     * 于是"目标在另一侧"时差值是 ~350° 而不是 -10°，AI 会朝同一边一直转下去
     * （实测表现就是"前进时持续右转"、绕圈却对不准目标）。
     * 这里与主流 mod 的写法一致：正值 = 需要左转（逆时针），负值 = 需要右转。
     */
    private static float getShortestRotation(float from, float to) {
        float diff = (to - from) % 360.0F;
        if (diff > 180.0F) {
            diff -= 360.0F;
        } else if (diff < -180.0F) {
            diff += 360.0F;
        }
        return diff;
    }

    /** 持续加速，同时封掉横移、倒车与主动减速，保证只会往前冲 */
    private void chargeForward() {
        ship.giveCommand(ShipCommand.ACCELERATE, null, 0);
        ship.blockCommandForOneFrame(ShipCommand.STRAFE_LEFT);
        ship.blockCommandForOneFrame(ShipCommand.STRAFE_RIGHT);
        ship.blockCommandForOneFrame(ShipCommand.ACCELERATE_BACKWARDS);
        ship.blockCommandForOneFrame(ShipCommand.DECELERATE);
    }

    @Override
    public void setDoNotFireDelay(float amount) {
        // 不需要控制开火间隔
    }

    @Override
    public void forceCircumstanceEvaluation() {
        // 本 AI 不做情境评估
    }

    @Override
    public boolean needsRefit() {
        return false;
    }

    @Override
    public ShipwideAIFlags getAIFlags() {
        return aiFlags;
    }

    @Override
    public void cancelCurrentManeuver() {
        // 本 AI 不使用机动指令，无需取消
    }

    @Override
    public ShipAIConfig getConfig() {
        return config;
    }
}
