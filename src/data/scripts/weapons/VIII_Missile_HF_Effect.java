package data.scripts.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * 导弹武器（HF）帧动画脚本。
 * 动画表现：
 *  - 开火前摇：根据前摇（充能）进度从第 0 帧顺序播放到第 16 帧；
 *    进度达到 90% 时即播放到最后一帧并定格保持，不再继续推进。
 *  - 冷却阶段：在 1 秒内从第 16 帧倒放回第 0 帧。
 *  - 空闲状态：固定显示第 0 帧。
 * 游戏暂停、舰船残骸或排辐能时动画暂停（当前帧保持不变）。
 */
public class VIII_Missile_HF_Effect implements EveryFrameWeaponEffectPlugin {

    /** 当前状态：0 = 空闲，1 = 前摇，2 = 冷却 */
    private int state = 0;

    /** 冷却阶段已经过的时间（秒） */
    private float cooldownTime = 0f;

    /** 后备方案使用的前摇累计时间（秒） */
    private float windupTime = 0f;

    /** 冷却动画总时长（秒），固定 1 秒 */
    private static final float COOLDOWN_DURATION = 1.0f;

    /** 后备方案的前摇总时长（秒），仅当武器没有充能进度时使用 */
    private static final float WINDUP_FALLBACK_DURATION = 0.5f;

    /** 前摇进度达到该比例时，动画即定格在最后一帧 */
    private static final float WINDUP_PROGRESS_CAP = 0.9f;

    /** 总帧数（第 0 帧 ~ 第 16 帧，共 17 帧） */
    private static final int TOTAL_FRAMES = 17;

    /** 最后一帧的帧号 */
    private static final int LAST_FRAME = TOTAL_FRAMES - 1;

    /**
     * 每帧调用一次，用于更新武器动画。
     *
     * @param amount 时间增量（秒）
     * @param engine 战斗引擎
     * @param weapon 所作用的武器
     */
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 游戏暂停时保持当前帧，不更新任何计时器
        if (engine.isPaused()) return;

        ShipAPI ship = weapon.getShip();
        // 舰船不存在、已成为残骸或正在排辐能时，动画暂停
        if (ship == null || ship.isHulk() || ship.getFluxTracker().isVenting()) {
            return;
        }

        // 该武器没有动画则直接返回
        if (weapon.getAnimation() == null) return;

        boolean inCooldown = weapon.getCooldownRemaining() > 0f;
        boolean firing = weapon.isFiring();

        // ==================== 状态切换 ====================
        if (inCooldown) {
            // 进入冷却阶段：重置冷却计时器
            if (state != 2) {
                state = 2;
                cooldownTime = 0f;
            }
        } else if (firing) {
            // 进入前摇阶段：重置后备计时器
            if (state != 1) {
                state = 1;
                windupTime = 0f;
            }
        } else {
            // 空闲状态：显示第 0 帧
            if (state != 0) {
                state = 0;
                windupTime = 0f;
                weapon.getAnimation().setFrame(0);
            }
        }

        // ==================== 按状态更新动画帧 ====================
        if (state == 1) {
            // ---------- 前摇：按前摇进度顺序播放 ----------
            // 优先使用武器自身的充能进度（0 ~ 1）
            float charge = weapon.getChargeLevel();

            if (charge <= 0f) {
                // 后备方案：武器没有充能进度时，使用固定时长的线性进度
                windupTime += amount;
                charge = windupTime / WINDUP_FALLBACK_DURATION;
            }

            // 进度除以 0.9：达到 90% 时进度即为 1，直接映射到最后一帧
            float progress = charge / WINDUP_PROGRESS_CAP;
            if (progress < 0f) progress = 0f;
            if (progress > 1f) progress = 1f;   // 超过 90% 后保持定格

            int frame = (int) (progress * LAST_FRAME);   // 0 ~ 16
            if (frame > LAST_FRAME) frame = LAST_FRAME;
            if (frame < 0) frame = 0;

            weapon.getAnimation().setFrame(frame);

        } else if (state == 2) {
            // ---------- 冷却：从第 16 帧倒放回第 0 帧，耗时 1 秒 ----------
            cooldownTime += amount;
            float progress = cooldownTime / COOLDOWN_DURATION;
            if (progress > 1f) progress = 1f;

            int frame = LAST_FRAME - (int) (progress * LAST_FRAME);   // 16 ~ 0
            if (frame < 0) frame = 0;

            weapon.getAnimation().setFrame(frame);
        }
        // 空闲状态不额外操作，帧保持为第 0 帧
    }
}