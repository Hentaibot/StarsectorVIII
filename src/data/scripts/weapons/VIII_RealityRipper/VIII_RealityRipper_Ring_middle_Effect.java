package data.scripts.weapons.VIII_RealityRipper;

/**
 * 中环装饰武器的帧动画：24 帧、每轮 0.45 秒。
 * 具体节奏由 {@link VIII_RealityRipper_RingEffectBase} 统一控制（跟随主武器前摇与冷却状态）。
 */
public class VIII_RealityRipper_Ring_middle_Effect extends VIII_RealityRipper_RingEffectBase {

    /** 中环总帧数。 */
    private static final int TOTAL_FRAMES = 24;

    /** 中环完成一轮的时间（秒）。 */
    private static final float CYCLE_TIME = 0.45f;

    @Override
    protected int getTotalFrames() {
        return TOTAL_FRAMES;
    }

    @Override
    protected float getCycleTime() {
        return CYCLE_TIME;
    }
}
