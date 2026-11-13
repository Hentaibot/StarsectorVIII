package data.scripts.weapons.VIII_RealityRipper;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * 三圈环形装饰武器（VIII_RealityRipper_Ring_up / _middle / _down）的帧动画基类。
 *
 * <p>动画节奏完全由主武器 VIII_RealityRipper 每帧广播在舰船自定义数据里的阶段决定
 * （见 {@link VIII_RealityRipper_AnimState}），因此三圈环不需要各自维护状态机，
 * 只需要提供自己的总帧数与循环周期。</p>
 *
 * <p>规则：</p>
 * <ul>
 *   <li>常态（未开火、未前摇）：环静止在第 0 帧，不旋转；</li>
 *   <li>前摇开始：环立刻开始循环旋转，一直转到前摇进度 50%；</li>
 *   <li>前摇进度到 50%：主武器定格在第 21 帧，环把当前这一轮转完后停在首帧（第 0 帧）；</li>
 *   <li>冷却阶段前 5%：环保持不动；</li>
 *   <li>冷却阶段 5%~40%：环持续循环旋转（覆盖主武器第 20 帧停留与 21~41 帧动画两段）；</li>
 *   <li>冷却阶段 40% 之后（主武器已回到第 0 帧）：环最后完整转一轮，然后停在首帧直到冷却结束。</li>
 * </ul>
 *
 * <p>游戏暂停、舰船残骸或排散时动画保持在当前帧（时间不累积）。</p>
 */
public abstract class VIII_RealityRipper_RingEffectBase implements EveryFrameWeaponEffectPlugin {

    /** 播放模式：正常循环。 */
    private static final int MODE_LOOP = 0;
    /** 播放模式：把当前这一轮播完，然后停在首帧。 */
    private static final int MODE_FINISH = 1;
    /** 播放模式：直接停在首帧。 */
    private static final int MODE_HOLD = 2;

    /** 当前循环已累计的时间（秒）。 */
    private float elapsed = 0f;
    /** 是否已经停在首帧。 */
    private boolean holding = false;

    /** 环动画的总帧数。 */
    protected abstract int getTotalFrames();

    /** 环动画完成一轮所需的秒数。 */
    protected abstract float getCycleTime();

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused()) return;
        if (weapon.getAnimation() == null) return;

        ShipAPI ship = weapon.getShip();
        if (ship == null) return;
        // 舰船残骸或排散时，动画保持在当前帧（时间不累积）
        if (ship.isHulk() || ship.getFluxTracker().isVenting()) return;

        int mode = resolveMode(ship);
        if (mode == MODE_HOLD) {
            holdAtFirstFrame(weapon);
        } else if (mode == MODE_FINISH) {
            advanceUntilFinished(amount, weapon);
        } else {
            advanceLooping(amount, weapon);
        }
    }

    /**
     * 根据主武器广播的动画阶段决定环当前该处于哪种播放模式。
     *
     * <p>阶段由 {@link VIII_RealityRipper_AnimState} 提供：主武器每帧写入一次，环只读，
     * 两者永远同步，不会出现「冷却阶段环自己推断出错」的情况。</p>
     *
     * @param ship 环武器所在舰船
     * @return MODE_LOOP / MODE_FINISH / MODE_HOLD
     */
    private int resolveMode(ShipAPI ship) {
        VIII_RealityRipper_AnimState state = VIII_RealityRipper_AnimState.get(ship);
        // 没有状态（主武器不在本舰或尚未推进过）：保持静止，避免装饰环空转
        if (state == null) return MODE_HOLD;

        switch (state.phase) {
            case VIII_RealityRipper_AnimState.PHASE_CHARGE_ROTATE:
            case VIII_RealityRipper_AnimState.PHASE_COOLDOWN_ROTATE:
                // 前摇中、以及冷却 5%~40%（主武器 20 帧 → 21~41 帧）：持续旋转
                return MODE_LOOP;
            case VIII_RealityRipper_AnimState.PHASE_CHARGE_LOCKED:
            case VIII_RealityRipper_AnimState.PHASE_COOLDOWN_FINISH:
                // 前摇过半、以及主武器回到第 0 帧之后：最后转一轮再停
                return MODE_FINISH;
            default:
                // PHASE_IDLE、PHASE_COOLDOWN_START（冷却前 5%）：静止在第 0 帧
                return MODE_HOLD;
        }
    }

    /** 正常循环播放。 */
    private void advanceLooping(float amount, WeaponAPI weapon) {
        elapsed += amount;
        int totalFrames = getTotalFrames();
        float cycleTime = getCycleTime();
        while (elapsed >= cycleTime) {
            elapsed -= cycleTime;
        }
        int frame = (int) (elapsed / cycleTime * totalFrames);
        if (frame >= totalFrames) frame = totalFrames - 1;
        if (frame < 0) frame = 0;
        weapon.getAnimation().setFrame(frame);
        holding = false;
    }

    /** 把当前这一轮播完，然后停在首帧。 */
    private void advanceUntilFinished(float amount, WeaponAPI weapon) {
        if (holding) {
            weapon.getAnimation().setFrame(0);
            return;
        }
        elapsed += amount;
        if (elapsed >= getCycleTime()) {
            elapsed = 0f;
            holding = true;
            weapon.getAnimation().setFrame(0);
            return;
        }
        int totalFrames = getTotalFrames();
        int frame = (int) (elapsed / getCycleTime() * totalFrames);
        if (frame >= totalFrames) frame = totalFrames - 1;
        if (frame < 0) frame = 0;
        weapon.getAnimation().setFrame(frame);
    }

    /** 直接停在首帧。 */
    private void holdAtFirstFrame(WeaponAPI weapon) {
        elapsed = 0f;
        holding = true;
        weapon.getAnimation().setFrame(0);
    }
}
