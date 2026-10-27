package data.scripts.ai;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipwideAIFlags;

import data.scripts.shipsystems.ai.VIII_OilSelfDestructAI;

/**
 * 脱离后的 VIII_oil 油罐模块专用 AI：只会向前加速，不能转向、横移、减速或倒车。
 * <p>
 * 实现方式是"主动发命令 + 封锁反向命令"：
 * - 每帧发一条 {@link ShipCommand#ACCELERATE}，引擎的运动模块会照常把推力积分成速度
 *   （模块没有引擎槽也有效，反编译 null.computeEffectiveStats 在引擎列表为空时不会
 *   进入熄火分支；运动积分由引擎的 ShipDriver 完成，与本舰 AI 无关）；
 * - 每帧用 {@link ShipAPI#blockCommandForOneFrame(ShipCommand)} 封锁 TURN_LEFT /
 *   TURN_RIGHT / STRAFE_LEFT / STRAFE_RIGHT / DECELERATE / ACCELERATE_BACKWARDS，
 *   这些指令在引擎处理命令队列时会被直接剔除，所以玩家或残余的默认 AI 都没法改变航向、
 *   也没法刹车。
 * <p>
 * 注意不能封锁 USE_SYSTEM，否则油罐自己的自爆战术系统就放不出来了。
 * <p>
 * 另外这里还托管了战术系统 AI（{@link VIII_OilSelfDestructAI}）：引擎只有在
 * BasicShipAI / FighterAI / DroneAI 里才会 createSystemAI 并逐帧推进系统的 AI 脚本
 * （反编译 com.fs.starfarer.combat.ai.BasicShipAI:208、FighterAI:173、DroneAI:134），
 * 而本类一旦通过 setShipAI 接管舰船 AI，原来的 BasicShipAI 就被换掉了，
 * 系统 AI 也就没人推进。所以由本类 init 一次系统 AI，并在 advance 里代它推进，
 * 保证"靠近敌人才启用自爆"的逻辑照常生效。
 */
public class VIII_OilModuleAI implements ShipAIPlugin {

    /** 被本 AI 接管的模块（脱离后的 VIII_oil） */
    private final ShipAPI ship;

    /** 托管的自爆系统 AI：由本类负责 init 与逐帧 advance */
    private final VIII_OilSelfDestructAI systemAI;

    /** 返回给引擎的 AI 标志集合，模块逻辑简单，实际不用 */
    private final ShipwideAIFlags aiFlags = new ShipwideAIFlags();

    /** 返回给引擎的 AI 配置，保持默认即可 */
    private final ShipAIConfig config = new ShipAIConfig();

    public VIII_OilModuleAI(ShipAPI ship) {
        this.ship = ship;
        this.systemAI = new VIII_OilSelfDestructAI();
        if (ship != null && ship.getSystem() != null) {
            systemAI.init(ship, ship.getSystem(), aiFlags, Global.getCombatEngine());
        }
    }

    /**
     * 由引擎每帧调用（在 Ship.advance 之前），这里补加速指令并封锁所有会改变
     * 航向 / 速度的其它指令。
     */
    @Override
    public void advance(float amount) {
        if (ship == null || !ship.isAlive()) {
            return;
        }
        ship.giveCommand(ShipCommand.ACCELERATE, null, 0);

        ship.blockCommandForOneFrame(ShipCommand.TURN_LEFT);
        ship.blockCommandForOneFrame(ShipCommand.TURN_RIGHT);
        ship.blockCommandForOneFrame(ShipCommand.STRAFE_LEFT);
        ship.blockCommandForOneFrame(ShipCommand.STRAFE_RIGHT);
        ship.blockCommandForOneFrame(ShipCommand.DECELERATE);
        ship.blockCommandForOneFrame(ShipCommand.ACCELERATE_BACKWARDS);

        // 代引擎推进自爆系统 AI：靠近敌人且系统就绪时会自己 ship.useSystem()
        systemAI.advance(amount, null, null, ship.getShipTarget());
    }

    @Override
    public void setDoNotFireDelay(float amount) {
    }

    @Override
    public void forceCircumstanceEvaluation() {
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
    }

    @Override
    public ShipAIConfig getConfig() {
        return config;
    }
}
