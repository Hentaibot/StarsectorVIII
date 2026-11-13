package data.scripts.weapons.VIII_RealityRipper;

/**
 * 下环装饰武器的帧动画：9 帧、每轮 0.2 秒。
 * 具体节奏由 {@link VIII_RealityRipper_RingEffectBase} 统一控制（跟随主武器前摇与冷却状态）。
 */
public class VIII_RealityRipper_Ring_down_Effect extends VIII_RealityRipper_RingEffectBase {

    /** 下环总帧数。 */
    private static final int TOTAL_FRAMES = 9;

    /** 下环完成一轮的时间（秒）。 */
    private static final float CYCLE_TIME = 0.2f;

    @Override
    protected int getTotalFrames() {
        return TOTAL_FRAMES;
    }

    @Override
    protected float getCycleTime() {
        return CYCLE_TIME;
    }
}
