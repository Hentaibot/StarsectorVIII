package data.scripts.shipsystems.ai;

import java.util.List;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAIScript;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.util.IntervalUtil;

/**
 * 「阵地协议」（激光雷达阵列）的自定义系统 AI。
 * <p>
 * 原版 LIDAR_ARRAY 的 AI 是按"雷达扫描/索敌"设计的，与这个系统真正做的事情并不匹配。
 * 本系统激活期间的实际效果是：
 * <ul>
 *   <li>只有硬点（hardpoint）上的实弹/能量武器能开火，导弹与其它槽位全部强制停火；</li>
 *   <li>这些硬点武器射程 +40%、射速 ×3、后坐力下降、弹速 +50%；</li>
 *   <li>开火产生的幅能实时转移给敌人（已有硬幅能也按每秒约 100% 的比例转移）；</li>
 *   <li>系统结束瞬间重置所有非导弹武器的冷却，等于一次免费齐射。</li>
 * </ul>
 * 所以它是一个"硬点齐射窗口"，正确的按法是：本舰有硬点主炮、目标已经进入交战距离、
 * 并且自身没有濒临过载时再开，而不是一看见敌人就按。
 * <p>
 * 开启条件（全部满足）：
 * - 系统空闲；
 * - 幅能等级 &lt; 95%（虽然激活期能把幅能甩给敌人，但 1.5 秒前摇里仍会被集火）；
 * - 本舰至少有一门非装饰、非系统槽位的硬点实弹/能量武器（否则开了只会让其它武器停火）；
 * - 最近的敌方舰船（不看战机）距离 &lt; 2000。
 */
public class VIII_LidarArrayAI implements ShipSystemAIScript {

    private static final float TRIGGER_RANGE = 2000.0F;
    private static final float FLUX_LIMIT = 0.95F;

    private final IntervalUtil tracker = new IntervalUtil(0.25F, 0.45F);

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
        if (ship.getFluxTracker().getFluxLevel() > FLUX_LIMIT) {
            return;
        }
        if (!hasHardpointBattery(ship)) {
            return;
        }
        if (VIII_SystemAIUtils.nearestEnemyDistance(engine, ship, true) > TRIGGER_RANGE) {
            return;
        }

        ship.useSystem();
    }

    /**
     * 是否至少有一门能在激活期开火的武器：
     * 非装饰、非系统槽位、硬点、实弹或能量。没有这样一门武器时，
     * 系统只会让其它武器停火，纯粹是负收益。
     */
    private boolean hasHardpointBattery(ShipAPI owner) {
        List<WeaponAPI> weapons = owner.getAllWeapons();
        if (weapons == null) {
            return false;
        }
        for (WeaponAPI weapon : weapons) {
            if (weapon == null || weapon.isDecorative()) {
                continue;
            }
            if (weapon.getSlot() == null || weapon.getSlot().isSystemSlot()) {
                continue;
            }
            if (!weapon.getSlot().isHardpoint()) {
                continue;
            }
            WeaponType type = weapon.getType();
            if (type == WeaponType.BALLISTIC || type == WeaponType.ENERGY) {
                return true;
            }
        }
        return false;
    }
}
