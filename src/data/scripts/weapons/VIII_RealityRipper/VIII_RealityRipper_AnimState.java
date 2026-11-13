package data.scripts.weapons.VIII_RealityRipper;

import com.fs.starfarer.api.combat.ShipAPI;

/**
 * 主武器 VIII_RealityRipper 与三圈装饰环（Ring_up / Ring_middle / Ring_down）共享的动画阶段状态。
 *
 * <p>由主武器的 everyFrameEffect 每帧写入，环武器只读。这样做的好处是环不需要再自己推断
 * {@code getChargeLevel()} / {@code getCooldownRemaining()} 在充能型爆发光束上的具体语义，
 * 直接复用主武器算好的阶段，两者永远同步。</p>
 */
public final class VIII_RealityRipper_AnimState {

    /** 舰船自定义数据里的键。 */
    public static final String KEY = "VIII_RealityRipper_AnimState";

    /** 阶段：常态（未充能、未冷却）。 */
    public static final int PHASE_IDLE = 0;
    /** 阶段：前摇中，进度不到 50%。 */
    public static final int PHASE_CHARGE_ROTATE = 1;
    /** 阶段：前摇进度达到 50%，主武器定格在第 21 帧。 */
    public static final int PHASE_CHARGE_LOCKED = 2;
    /** 阶段：冷却前 5%，环保持静止。 */
    public static final int PHASE_COOLDOWN_START = 3;
    /** 阶段：冷却 5%~40%，环持续旋转。 */
    public static final int PHASE_COOLDOWN_ROTATE = 4;
    /** 阶段：冷却超过 40%（主武器已回到第 0 帧），环收尾。 */
    public static final int PHASE_COOLDOWN_FINISH = 5;

    /** 前摇进度达到该值时主武器停在第 21 帧、环开始收尾（50%）。 */
    public static final float CHARGE_LOCK = 0.50f;

    /** 冷却前这段时间比例内环保持静止（15%）。 */
    public static final float COOLDOWN_RING_STATIC = 0.15f;

    /** 冷却到该比例时主武器已经回到第 0 帧，环开始收尾（40%）。 */
    public static final float COOLDOWN_MAIN_FRAME_ZERO = 0.40f;

    /** 当前阶段，取值见 PHASE_* 常量。 */
    public int phase = PHASE_IDLE;

    /** 前摇进度 0~1。 */
    public float chargeLevel = 0f;

    /** 冷却进度 0~1（不在冷却时为 0）。 */
    public float cooldownProgress = 0f;

    /**
     * 取出该舰共享的动画状态；没有则创建并挂到舰船自定义数据上。
     *
     * @param ship 主武器或环武器所在舰船
     * @return 状态对象；ship 为 null 时返回 null
     */
    public static VIII_RealityRipper_AnimState get(ShipAPI ship) {
        if (ship == null) return null;
        Object existing = ship.getCustomData().get(KEY);
        if (existing instanceof VIII_RealityRipper_AnimState) {
            return (VIII_RealityRipper_AnimState) existing;
        }
        VIII_RealityRipper_AnimState state = new VIII_RealityRipper_AnimState();
        ship.getCustomData().put(KEY, state);
        return state;
    }
}
