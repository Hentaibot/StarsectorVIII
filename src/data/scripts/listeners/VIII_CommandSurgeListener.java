package data.scripts.listeners;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;

/**
 * 督军（VIII_command）受到攻击时的应激反应：
 * 触发后 5 秒内最大航速与机动性各 +50%，冷却 10 秒（从触发那一刻开始计时）。
 * <p>
 * 【为什么用监听器而不是船插钩子】
 * 船插的 applyEffectsBeforeShipCreation 只负责改 stats，而"受到攻击"是一个事件；
 * 舰船级监听器正好提供这个入口：
 * - DamageTakenModifier.modifyDamageTaken 在 Ship.applyDamage 里被调用
 *   （反编译 com.fs.starfarer.combat.entities.Ship 第 3951 行，盾/甲/结构伤害都会走到），
 *   这里不修改伤害、只拿来当"被打中"的信号；
 * - AdvanceableListener.advance 由舰船每帧推进（Ship 第 1830 行附近），用来倒计时。
 * <p>
 * 【为什么每帧重写 stats】
 * 舰船的 stats 对象会在舰队同步等时机整体重建，只在触发瞬间写一次会丢；每帧
 * modifyMult 同一个 source 是幂等的，重建后下一帧就补回来。
 */
public class VIII_CommandSurgeListener implements DamageTakenModifier, AdvanceableListener {

    /** stats 修正的来源标识，unmodify 时用它精确撤销 */
    public static final String BUFF_SOURCE = "VIII_command_surge";

    /** 最大航速加成（50% => 乘 1.5） */
    public static final float SPEED_BONUS = 0.50F;

    /** 机动性加成：加速度 / 减速度 / 转向加速度 / 转向角速度（同原版辅助推进器口径） */
    public static final float MANEUVER_BONUS = 0.50F;

    /** 持续时间（秒） */
    public static final float BUFF_DURATION = 5.0F;

    /** 冷却时间（秒，从触发瞬间开始计时） */
    public static final float COOLDOWN_DURATION = 10.0F;

    private static final String STATUS_KEY = "VIII_command_surge_status";
    private static final String STATUS_ICON = "graphics/hullmods/auxilliary_thrusters.png";
    private static final String STATUS_TITLE = "督军应激协议";

    private final ShipAPI ship;

    /** 剩余增益时间（秒），> 0 表示增益生效中 */
    private float buffRemaining = 0.0F;

    /** 剩余冷却时间（秒），> 0 表示不能再次触发 */
    private float cooldownRemaining = 0.0F;

    /** 当前是否已经往 stats 上写过加成，避免无谓的 unmodify */
    private boolean buffApplied = false;

    public VIII_CommandSurgeListener(ShipAPI ship) {
        this.ship = ship;
    }

    /**
     * 被打中时调用。这里只做触发判定，绝不修改伤害
     * （返回 null 表示"没有修改任何伤害修正"）。
     */
    @Override
    public String modifyDamageTaken(Object param, CombatEntityAPI target, DamageAPI damage,
                                    Vector2f point, boolean shieldHit) {
        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            return null;
        }
        if (damage == null) {
            return null;
        }
        if (damage.getBaseDamage() * damage.getMultiplier() <= 0.0F) {
            return null;
        }
        if (isFriendlyFire(param)) {
            return null;
        }
        tryTrigger();
        return null;
    }

    /** 每帧倒计时：推进冷却、维持增益、到点撤销 */
    @Override
    public void advance(float amount) {
        if (ship == null) {
            return;
        }
        if (!ship.isAlive() || ship.isHulk()) {
            clearBuff();
            return;
        }

        if (cooldownRemaining > 0.0F) {
            cooldownRemaining = Math.max(0.0F, cooldownRemaining - amount);
        }

        if (buffRemaining <= 0.0F) {
            return;
        }

        buffRemaining -= amount;
        if (buffRemaining > 0.0F) {
            applyBuff();
            showStatus();
        } else {
            buffRemaining = 0.0F;
            clearBuff();
        }
    }

    /** 冷却已好就点火：重置增益时长与冷却，并立刻写入 stats */
    private void tryTrigger() {
        if (cooldownRemaining > 0.0F) {
            return;
        }
        buffRemaining = BUFF_DURATION;
        cooldownRemaining = COOLDOWN_DURATION;
        applyBuff();
        showStatus();
    }

    /** 友军（同 owner 的弹丸/导弹）打到自己不触发，避免被己方火力蹭出增益 */
    private boolean isFriendlyFire(Object param) {
        if (!(param instanceof DamagingProjectileAPI)) {
            return false;
        }
        DamagingProjectileAPI projectile = (DamagingProjectileAPI) param;
        return projectile.getOwner() == ship.getOwner();
    }

    /** 往 stats 写修正；同一 source 重复写是覆盖，不会叠加 */
    private void applyBuff() {
        MutableShipStatsAPI stats = ship.getMutableStats();
        if (stats == null) {
            return;
        }
        float speedMult = 1.0F + SPEED_BONUS;
        float maneuverMult = 1.0F + MANEUVER_BONUS;

        stats.getMaxSpeed().modifyMult(BUFF_SOURCE, speedMult);
        stats.getAcceleration().modifyMult(BUFF_SOURCE, maneuverMult);
        stats.getDeceleration().modifyMult(BUFF_SOURCE, maneuverMult);
        stats.getTurnAcceleration().modifyMult(BUFF_SOURCE, maneuverMult);
        stats.getMaxTurnRate().modifyMult(BUFF_SOURCE, maneuverMult);

        buffApplied = true;
    }

    /** 撤销修正；舰船已消失时直接丢弃标记即可 */
    private void clearBuff() {
        if (!buffApplied) {
            return;
        }
        MutableShipStatsAPI stats = ship == null ? null : ship.getMutableStats();
        if (stats != null) {
            stats.getMaxSpeed().unmodify(BUFF_SOURCE);
            stats.getAcceleration().unmodify(BUFF_SOURCE);
            stats.getDeceleration().unmodify(BUFF_SOURCE);
            stats.getTurnAcceleration().unmodify(BUFF_SOURCE);
            stats.getMaxTurnRate().unmodify(BUFF_SOURCE);
        }
        buffApplied = false;
    }

    /** 玩家亲自驾驶时在左下角舰船信息旁显示一条状态 */
    private void showStatus() {
        if (Global.getCombatEngine() == null) {
            return;
        }
        if (ship != Global.getCombatEngine().getPlayerShip()) {
            return;
        }
        String remaining = "" + (Math.round(buffRemaining * 10.0F) / 10.0F);
        Global.getCombatEngine().maintainStatusForPlayerShip(STATUS_KEY, STATUS_ICON, STATUS_TITLE,
                "最大航速 +" + Math.round(SPEED_BONUS * 100.0F) + "%，机动性 +"
                        + Math.round(MANEUVER_BONUS * 100.0F) + "%，剩余 " + remaining + " 秒",
                false);
    }
}
