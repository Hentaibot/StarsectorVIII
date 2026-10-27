package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.listeners.DamageListener;
import java.awt.Color;
import org.lwjgl.util.vector.Vector2f;

/**
 * VIII_battle 船插 – 战斗增幅系统（重构版 v3）
 * 常驻效果：
 *   • 护盾维持幅能消耗降低75%
 *   • 船体装甲值提升5%
 * 状态机（三个状态）：
 *   【无状态】   → 受到攻击或开启战术系统 → 立刻进入【会战模式】（永续）
 *   【会战模式】 → 10秒内未使用战术系统且无任何武器开火 → 进入【休整模式】
 *   【休整模式】 → 受到攻击或开启战术系统 → 立刻退回【会战模式】
 * 会战模式（BuffA）：武器幅能消耗-15%，射弹/导弹速度+50%  红色抖动
 * 休整模式（BuffB）：航速+25%，机动性+50%，弹药再生+200%，战备值4秒恢复1%  白色残影
 */
public class VIII_battle extends BaseHullMod {

    // ========== 修饰符ID ==========
    private static final String BUFF_A_FLUX = "VIII_battle_buffA_flux";
    private static final String BUFF_A_PROJ_SPEED = "VIII_battle_buffA_projSpeed";
    private static final String BUFF_A_MISSILE_SPEED = "VIII_battle_buffA_missileSpeed";
    private static final String BUFF_B_MAX_SPEED = "VIII_battle_buffB_maxSpeed";
    private static final String BUFF_B_ACCEL = "VIII_battle_buffB_accel";
    private static final String BUFF_B_DECEL = "VIII_battle_buffB_decel";
    private static final String BUFF_B_TURN_RATE = "VIII_battle_buffB_turnRate";
    private static final String BUFF_B_TURN_ACCEL = "VIII_battle_buffB_turnAccel";
    private static final String BUFF_B_AMMO_MISSILE = "VIII_battle_buffB_ammoMissile";
    private static final String BUFF_B_AMMO_BALLISTIC = "VIII_battle_buffB_ammoBallistic";
    private static final String BUFF_B_AMMO_ENERGY = "VIII_battle_buffB_ammoEnergy";

    // ========== 视觉效果 ==========
    private static final Color JITTER_COLOR = new Color(128, 10, 41, 255);
    private static final Color AFTERIMAGE_COLOR = new Color(255, 255, 255, 255);

    // ========== 常驻属性 ==========
    @Override
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
        stats.getShieldUpkeepMult().modifyMult(id, 0.25f);
        stats.getArmorBonus().modifyPercent(id, 5f);
    }

    // ========== 添加伤害监听器（用于判断“受到攻击”） ==========
    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        BuffState state = getState(ship);
        if (state.listenerAdded) return;

        ship.addListener((DamageListener) (source, target, result) -> {
            if (target == ship) {
                CombatEngineAPI engine = Global.getCombatEngine();
                if (engine != null) {
                    getState(ship).lastDamageTime = engine.getTotalElapsedTime(false);
                }
            }
        });
        state.listenerAdded = true;
    }

    // ========== 每帧逻辑 ==========
    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null || engine.isPaused()) return;

        BuffState st = getState(ship);
        float now = engine.getTotalElapsedTime(false);

        boolean isHit = (now - st.lastDamageTime) < 0.2f;
        boolean systemActive = ship.getSystem() != null && ship.getSystem().isActive();
        boolean attackOrSystem = isHit || systemActive;  // 用于进入会战或退出修整

        // 检查是否有武器正在开火
        boolean weaponFiring = isAnyWeaponFiring(ship);

        switch (st.status) {
            case NONE:
                if (attackOrSystem) {
                    switchToBattle(ship, st);
                }
                break;

            case BATTLE:
                // 会战模式：若正在使用战术系统或有武器开火，则重置计时，否则累加至10秒后进入修整
                if (systemActive || weaponFiring) {
                    st.idleTimer = 0f;
                } else {
                    st.idleTimer += amount;
                    if (st.idleTimer >= 10f) {
                        switchToSprint(ship, st);
                    }
                }
                // 红色抖动
                if (st.status == Status.BATTLE) {
                    ship.setJitterUnder(this, JITTER_COLOR, 3f, 5, 3, 5f);
                    ship.setJitterShields(false);
                    ship.setCircularJitter(true);
                }
                break;

            case SPRINT:
                // 修整模式：一旦受击或使用战术系统，立即退回会战模式
                if (attackOrSystem) {
                    switchToBattle(ship, st);
                }
                // 修整中的持续效果
                if (st.status == Status.SPRINT) {
                    float crRecovery = 0.0025f * amount;   // 每4秒1% = 0.0025/秒
                    float newCR = ship.getCurrentCR() + crRecovery;
                    if (newCR > 1f) newCR = 1f;
                    ship.setCurrentCR(newCR);

                    st.afterimageTimer += amount;
                    if (st.afterimageTimer >= 0.15f) {
                        st.afterimageTimer -= 0.15f;
                        if (ship.isAlive()) {
                            Vector2f vel = new Vector2f(ship.getVelocity());
                            vel.scale(-0.4f);
                            ship.addAfterimage(AFTERIMAGE_COLOR, 0, 0,
                                    vel.x, vel.y, 0, 0, 0.25f, 0.5f,
                                    false, true, false);
                        }
                    }
                }
                break;
        }

        displayBuffs(ship, engine, st);
    }

    // ========== 检测是否有武器正在开火 ==========
    private boolean isAnyWeaponFiring(ShipAPI ship) {
        for (WeaponAPI wpn : ship.getAllWeapons()) {
            if (wpn.isFiring()) {
                return true;
            }
        }
        return false;
    }

    // ========== 状态切换 ==========
    private void switchToBattle(ShipAPI ship, BuffState st) {
        if (st.status == Status.SPRINT) {
            removeBuffB(ship);
        }
        if (st.status != Status.BATTLE) {
            addBuffA(ship);
        }
        st.status = Status.BATTLE;
        st.idleTimer = 0f;
        st.afterimageTimer = 0f;
    }

    private void switchToSprint(ShipAPI ship, BuffState st) {
        removeBuffA(ship);
        addBuffB(ship);
        st.status = Status.SPRINT;
        st.idleTimer = 0f;
        st.afterimageTimer = 0f;
    }

    // ========== 数据存储 ==========
    private BuffState getState(ShipAPI ship) {
        String key = "VIII_battle_state";
        Object obj = ship.getCustomData().get(key);
        if (obj instanceof BuffState) {
            return (BuffState) obj;
        }
        BuffState s = new BuffState();
        ship.setCustomData(key, s);
        return s;
    }

    private static class BuffState {
        Status status = Status.NONE;
        float idleTimer = 0f;           // 会战模式下“未使用系统且未开火”的累计时间
        float afterimageTimer = 0f;
        float lastDamageTime = -9999f;  // 最后一次受伤时间，用于判断“受到攻击”
        boolean listenerAdded = false;
    }

    private enum Status {
        NONE, BATTLE, SPRINT
    }

    // ========== 增益管理 ==========
    private void addBuffA(ShipAPI ship) {
        MutableShipStatsAPI s = ship.getMutableStats();
        s.getEnergyWeaponFluxCostMod().modifyMult(BUFF_A_FLUX, 0.85f);
        s.getBallisticWeaponFluxCostMod().modifyMult(BUFF_A_FLUX, 0.85f);
        s.getMissileWeaponFluxCostMod().modifyMult(BUFF_A_FLUX, 0.85f);
        s.getBeamWeaponFluxCostMult().modifyMult(BUFF_A_FLUX, 0.85f);
        s.getProjectileSpeedMult().modifyMult(BUFF_A_PROJ_SPEED, 1.5f);
        s.getMissileMaxSpeedBonus().modifyMult(BUFF_A_MISSILE_SPEED, 1.5f);
    }

    private void removeBuffA(ShipAPI ship) {
        MutableShipStatsAPI s = ship.getMutableStats();
        s.getEnergyWeaponFluxCostMod().unmodify(BUFF_A_FLUX);
        s.getBallisticWeaponFluxCostMod().unmodify(BUFF_A_FLUX);
        s.getMissileWeaponFluxCostMod().unmodify(BUFF_A_FLUX);
        s.getBeamWeaponFluxCostMult().unmodify(BUFF_A_FLUX);
        s.getProjectileSpeedMult().unmodify(BUFF_A_PROJ_SPEED);
        s.getMissileMaxSpeedBonus().unmodify(BUFF_A_MISSILE_SPEED);
    }

    private void addBuffB(ShipAPI ship) {
        MutableShipStatsAPI s = ship.getMutableStats();
        s.getMaxSpeed().modifyMult(BUFF_B_MAX_SPEED, 1.25f);
        s.getAcceleration().modifyMult(BUFF_B_ACCEL, 1.5f);
        s.getDeceleration().modifyMult(BUFF_B_DECEL, 1.5f);
        s.getMaxTurnRate().modifyMult(BUFF_B_TURN_RATE, 1.5f);
        s.getTurnAcceleration().modifyMult(BUFF_B_TURN_ACCEL, 1.5f);
        s.getMissileAmmoRegenMult().modifyMult(BUFF_B_AMMO_MISSILE, 2f);
        s.getBallisticAmmoRegenMult().modifyMult(BUFF_B_AMMO_BALLISTIC, 2f);
        s.getEnergyAmmoRegenMult().modifyMult(BUFF_B_AMMO_ENERGY, 2f);
    }

    private void removeBuffB(ShipAPI ship) {
        MutableShipStatsAPI s = ship.getMutableStats();
        s.getMaxSpeed().unmodify(BUFF_B_MAX_SPEED);
        s.getAcceleration().unmodify(BUFF_B_ACCEL);
        s.getDeceleration().unmodify(BUFF_B_DECEL);
        s.getMaxTurnRate().unmodify(BUFF_B_TURN_RATE);
        s.getTurnAcceleration().unmodify(BUFF_B_TURN_ACCEL);
        s.getMissileAmmoRegenMult().unmodify(BUFF_B_AMMO_MISSILE);
        s.getBallisticAmmoRegenMult().unmodify(BUFF_B_AMMO_BALLISTIC);
        s.getEnergyAmmoRegenMult().unmodify(BUFF_B_AMMO_ENERGY);
    }

    // ========== UI ==========
    private void displayBuffs(ShipAPI ship, CombatEngineAPI engine, BuffState st) {
        if (ship != engine.getPlayerShip()) return;

        if (st.status == Status.BATTLE) {
            engine.maintainStatusForPlayerShip("VIII_battle_buffA",
                    "graphics/icons/hullsys/incursion_mode.png",
                    "会战",
                    "武器幅能-15%  射弹/导弹速度+50%",
                    false);
        }
        if (st.status == Status.SPRINT) {
            engine.maintainStatusForPlayerShip("VIII_battle_buffB",
                    "graphics/icons/hullsys/extraction_protocol.png",
                    "休整",
                    "航速+25%  机动性+50%  弹药恢复+200%  战备恢复",
                    false);
        }
    }
}