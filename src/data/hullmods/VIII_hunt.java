package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import java.awt.Color;

/**
 * VIII_hunt 船插 – 猎杀与充能系统
 * 常驻效果：
 *   • 零幅能加速阈值提高10%（即幅能低于10%时即可触发零幅能加速）
 * 动态效果（状态机）：
 *   【空闲】→ 持续5秒未开火且未使用系统 → 进入【寻猎】（航速+15）
 *   【寻猎】→ 开火或使用系统 → 扣除航速加成，进入【打击充能】（持续3秒，可刷新）
 *   【打击充能】→ 停火后3秒衰减至结束 → 回到【空闲】
 * 寻猎（BuffSeek）：
 *   • 最大航速 +15  蓝色UI图标
 * 打击充能（BuffStrike）：
 *   • 武器伤害 +30% （能量/实弹/导弹/光束）
 *   • 射速加倍（冷却时间减半）
 *   • 射程 +100%
 *   • 武器幅能消耗 -30%
 *   • 蓝色抖动视觉效果
 *   • 效果在3秒内线性衰减，开火或使用系统刷新持续时间
 */
public class VIII_hunt extends BaseHullMod {

    private static final float SEEK_IDLE_TIME = 5f;        // 进入寻猎所需空闲时间
    private static final float STRIKE_MAX_DURATION = 3f;  // 打击充能持续时间
    private static final float SEEK_SPEED_BONUS = 15f;    // 寻猎航速加成
    private static final Color STRIKE_JITTER_COLOR = new Color(0, 102, 255, 255);

    // 修正键
    private static final String KEY_ZERO_FLUX = "VIII_hunt_zeroFlux";
    private static final String KEY_SEEK_SPEED = "VIII_hunt_seekSpeed";
    private static final String KEY_STRIKE_DAMAGE = "VIII_hunt_strikeDamage";
    private static final String KEY_STRIKE_ROF = "VIII_hunt_strikeRoF";
    private static final String KEY_STRIKE_RANGE = "VIII_hunt_strikeRange";
    private static final String KEY_STRIKE_FLUX = "VIII_hunt_strikeFlux";

    @Override
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
        // 零幅能加速阈值提高10%（实际 +0.1，即幅能低于10%即可触发）
        stats.getZeroFluxMinimumFluxLevel().modifyFlat(id, 0.1f);
    }

    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null || engine.isPaused()) return;

        HuntState state = getState(ship);
        boolean weaponFiring = isAnyWeaponFiring(ship);
        boolean systemActive = ship.getSystem() != null && ship.getSystem().isActive();
        boolean combatAction = weaponFiring || systemActive;

        switch (state.phase) {
            case IDLE:
                if (combatAction) {
                    state.idleTimer = 0f;
                } else {
                    state.idleTimer += amount;
                    if (state.idleTimer >= SEEK_IDLE_TIME) {
                        enterSeek(ship, state);
                    }
                }
                break;

            case SEEK:
                if (combatAction) {
                    exitSeek(ship, state);
                    enterStrike(ship, state);
                } else {
                    // 保持寻猎，无需操作（idleTimer 不再需要）
                    state.idleTimer = 0f;
                }
                break;

            case STRIKE:
                if (combatAction) {
                    // 刷新打击充能持续时间
                    state.strikeTimer = STRIKE_MAX_DURATION;
                } else {
                    state.strikeTimer -= amount;
                }
                if (state.strikeTimer <= 0f) {
                    // 打击充能结束，回到空闲
                    exitStrike(ship, state);
                    state.phase = HuntPhase.IDLE;
                    state.idleTimer = 0f;
                } else {
                    // 动态更新打击充能效果（根据剩余时间比例衰减）
                    float factor = state.strikeTimer / STRIKE_MAX_DURATION;
                    applyStrikeEffects(ship, factor);
                    // 蓝色抖动
                    ship.setJitterUnder(this, STRIKE_JITTER_COLOR, 3f * factor, 5, 3, 5f);
                    ship.setJitterShields(false);
                    ship.setCircularJitter(true);
                }
                break;
        }

        // 更新UI（仅玩家舰船）
        displayBuffs(ship, engine, state);
    }

    // ---------- 状态切换方法 ----------
    private void enterSeek(ShipAPI ship, HuntState st) {
        st.phase = HuntPhase.SEEK;
        ship.getMutableStats().getMaxSpeed().modifyFlat(KEY_SEEK_SPEED, SEEK_SPEED_BONUS);
    }

    private void exitSeek(ShipAPI ship, HuntState st) {
        ship.getMutableStats().getMaxSpeed().unmodifyFlat(KEY_SEEK_SPEED);
    }

    private void enterStrike(ShipAPI ship, HuntState st) {
        st.phase = HuntPhase.STRIKE;
        st.strikeTimer = STRIKE_MAX_DURATION;
        // 首次应用时效果由后续每帧的 applyStrikeEffects 处理
    }

    private void exitStrike(ShipAPI ship, HuntState st) {
        // 移除所有打击充能修正
        MutableShipStatsAPI stats = ship.getMutableStats();
        stats.getEnergyWeaponDamageMult().unmodify(KEY_STRIKE_DAMAGE);
        stats.getBallisticWeaponDamageMult().unmodify(KEY_STRIKE_DAMAGE);
        stats.getMissileWeaponDamageMult().unmodify(KEY_STRIKE_DAMAGE);
        stats.getBeamWeaponDamageMult().unmodify(KEY_STRIKE_DAMAGE);
        stats.getEnergyRoFMult().unmodify(KEY_STRIKE_ROF);
        stats.getBallisticRoFMult().unmodify(KEY_STRIKE_ROF);
        stats.getMissileRoFMult().unmodify(KEY_STRIKE_ROF);
        stats.getEnergyWeaponRangeBonus().unmodify(KEY_STRIKE_RANGE);
        stats.getBallisticWeaponRangeBonus().unmodify(KEY_STRIKE_RANGE);
        stats.getMissileWeaponRangeBonus().unmodify(KEY_STRIKE_RANGE);
        stats.getBeamWeaponRangeBonus().unmodify(KEY_STRIKE_RANGE);
        stats.getEnergyWeaponFluxCostMod().unmodify(KEY_STRIKE_FLUX);
        stats.getBallisticWeaponFluxCostMod().unmodify(KEY_STRIKE_FLUX);
        stats.getMissileWeaponFluxCostMod().unmodify(KEY_STRIKE_FLUX);
        stats.getBeamWeaponFluxCostMult().unmodify(KEY_STRIKE_FLUX);
    }

    /**
     * 根据衰减系数 factor (0~1) 动态设置打击充能效果
     * factor = 1 表示满效果，factor = 0 表示效果完全消失
     */
    private void applyStrikeEffects(ShipAPI ship, float factor) {
        MutableShipStatsAPI stats = ship.getMutableStats();
        // 伤害 +30%，按因子插值：从 1.0 到 1.3
        float damageMult = 1f + 0.3f * factor;
        stats.getEnergyWeaponDamageMult().modifyMult(KEY_STRIKE_DAMAGE, damageMult);
        stats.getBallisticWeaponDamageMult().modifyMult(KEY_STRIKE_DAMAGE, damageMult);
        stats.getMissileWeaponDamageMult().modifyMult(KEY_STRIKE_DAMAGE, damageMult);
        stats.getBeamWeaponDamageMult().modifyMult(KEY_STRIKE_DAMAGE, damageMult);

        // 射速加倍（冷却时间减半），因子：从 1.0 到 2.0
        float rofMult = 1f + factor;
        stats.getEnergyRoFMult().modifyMult(KEY_STRIKE_ROF, rofMult);
        stats.getBallisticRoFMult().modifyMult(KEY_STRIKE_ROF, rofMult);
        stats.getMissileRoFMult().modifyMult(KEY_STRIKE_ROF, rofMult);
        // 光束没有 RoF，只通过伤害体现，此处忽略

        // 射程 +100%：从 0% 加成到 100% 加成
        float rangeBonusPercent = 100f * factor;
        stats.getEnergyWeaponRangeBonus().modifyPercent(KEY_STRIKE_RANGE, rangeBonusPercent);
        stats.getBallisticWeaponRangeBonus().modifyPercent(KEY_STRIKE_RANGE, rangeBonusPercent);
        stats.getMissileWeaponRangeBonus().modifyPercent(KEY_STRIKE_RANGE, rangeBonusPercent);
        stats.getBeamWeaponRangeBonus().modifyPercent(KEY_STRIKE_RANGE, rangeBonusPercent);

        // 武器幅能消耗 -30%：从 1.0 到 0.7
        float fluxMult = 1f - 0.3f * factor;
        stats.getEnergyWeaponFluxCostMod().modifyMult(KEY_STRIKE_FLUX, fluxMult);
        stats.getBallisticWeaponFluxCostMod().modifyMult(KEY_STRIKE_FLUX, fluxMult);
        stats.getMissileWeaponFluxCostMod().modifyMult(KEY_STRIKE_FLUX, fluxMult);
        stats.getBeamWeaponFluxCostMult().modifyMult(KEY_STRIKE_FLUX, fluxMult);
    }

    private boolean isAnyWeaponFiring(ShipAPI ship) {
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon.isFiring()) return true;
        }
        return false;
    }

    // ---------- UI ----------
    private void displayBuffs(ShipAPI ship, CombatEngineAPI engine, HuntState state) {
        if (ship != engine.getPlayerShip()) return;

        if (state.phase == HuntPhase.SEEK) {
            engine.maintainStatusForPlayerShip("VIII_hunt_seek",
                    "graphics/icons/hullsys/burn_drive.png",    // 示例图标，可按需替换
                    "寻猎",
                    "最大航速 +15",
                    false);
        }
        if (state.phase == HuntPhase.STRIKE) {
            engine.maintainStatusForPlayerShip("VIII_hunt_strike",
                    "graphics/icons/hullsys/entropy_amplifier.png",       // 示例图标
                    "打击充能",
                    "伤害+30% 射速x2 射程+100% 幅能-30%",
                    false);
        }
    }

    // ---------- 独立状态存储 ----------
    private HuntState getState(ShipAPI ship) {
        String key = "VIII_hunt_state";
        Object obj = ship.getCustomData().get(key);
        if (obj instanceof HuntState) {
            return (HuntState) obj;
        }
        HuntState s = new HuntState();
        ship.setCustomData(key, s);
        return s;
    }

    private static class HuntState {
        HuntPhase phase = HuntPhase.IDLE;
        float idleTimer = 0f;
        float strikeTimer = 0f;
    }

    private enum HuntPhase {
        IDLE,   // 空闲（等待进入寻猎）
        SEEK,   // 寻猎
        STRIKE  // 打击充能
    }
}