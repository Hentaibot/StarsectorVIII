package data.scripts.weapons.VIII_RealityRipper;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import data.scripts.util.VIII_BoxBasedUtil;
import org.boxutil.manager.CombatRenderingManager;
import org.boxutil.units.standard.entity.FlareEntity;
import org.dark.shaders.distortion.DistortionShader;
import org.dark.shaders.distortion.RippleDistortion;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * VIII_RealityRipper 武器特效插件。
 * 负责管理武器动画、开火时的光晕效果以及冷却期间的粒子火花。
 * 光晕在武器充能超过80%且不在冷却时持续生成（内圈白、外圈蓝），并逐帧跟随枪口位置与朝向。
 * 冷却期间的粒子分为两阶段：
 *   - 第一阶段（冷却时间的 20%~100%）：生成粒子 7~10
 *   - 第二阶段（冷却时间的 30%~100%）：生成粒子 1~6
 * 粒子位置基于武器本地偏移并随武器朝向旋转，实现跟随效果。
 */
public class VIII_RealityRipper_Effect implements EveryFrameWeaponEffectPlugin {

    // ========================= 冷却奇点 =========================
    /** 舰船自定义数据键：最后一次光束终点的位置。 */
    public static final String KEY_LAST_BEAM_END = "VIII_RealityRipper_last_beam_end";
    /** 舰船自定义数据键：本次冷却是否已经生成过奇点。 */
    private static final String KEY_RIFT_SPAWNED = "VIII_RealityRipper_rift_spawned";

    // ========================= 动画阶段比例 =========================
    // 动画阶段占冷却时间的比例（相对于总冷却时间）
    private static final float HOLD_RATIO = 0.2f;   // 开火结束后前 20% 冷却时间保持第20帧
    private static final float ANIM_RATIO = 0.2f;   // 接下来的 20% 冷却时间播放从21到41帧的动画
    // 剩余 60% 冷却时间保持第0帧（暂停）

    // ========================= 前摇阶段动画 =========================
    /** 前摇进度达到该值时开始播放动画（10%）。 */
    private static final float CHARGE_ANIM_START = 0.10f;
    /** 前摇进度达到该值时动画定格在第 20 帧（50%）。 */
    private static final float CHARGE_LOCK = 0.50f;
    /** 前摇定格帧：开火前停在能量汇聚完成的那一帧。 */
    private static final int CHARGE_LOCK_FRAME = 20;

    // ========================= 前摇放电特效 =========================
    /** 前摇进度达到该值后开始持续生成扭曲与电弧（45%）。 */
    private static final float CHARGE_FX_START = 0.45f;
    /** 装饰武器槽位 id 前缀：这些槽位作为电弧的起点。 */
    private static final String CHARGE_ARC_SLOT_PREFIX = "EMP";
    /** 扭曲效果的生成间隔（秒，取区间随机值）；按参考实现逐帧生成，这里稍微降频以控制开销。 */
    private static final float CHARGE_RIPPLE_INTERVAL_MIN = 0.06f;
    private static final float CHARGE_RIPPLE_INTERVAL_MAX = 0.10f;
    /** 扭曲尺寸倍率：实际尺寸 = 该值 × 前摇进度（参考实现为 100 × chargeLevel）。 */
    private static final float CHARGE_RIPPLE_SIZE_MULT = 175f;
    /** 扭曲强度倍率：实际强度 = 该值 × 前摇进度（参考实现为 25 × chargeLevel）。 */
    private static final float CHARGE_RIPPLE_INTENSITY_MULT = 25f;
    /** 扭曲生命周期（秒）。 */
    private static final float CHARGE_RIPPLE_LIFETIME = 0.6f;
    /** 扭曲动画帧率（参考实现约 100 帧/秒，涟漪推进更快更顺滑）。 */
    private static final float CHARGE_RIPPLE_FRAME_RATE = 100f;
    /** 尺寸淡入时长（秒）：从 0 涨到目标尺寸。 */
    private static final float CHARGE_RIPPLE_FADE_IN_SIZE = 0.1f;
    /** 强度淡出时长（秒）：生命周期末尾强度回落。 */
    private static final float CHARGE_RIPPLE_FADE_OUT_INTENSITY = 0.6f;

    /** 电弧粗细：基础值 + 随前摇进度增长。 */
    private static final float CHARGE_ARC_THICKNESS_BASE = 3f;
    private static final float CHARGE_ARC_THICKNESS_GAIN = 9f;
    /** 电弧分段长度倍率：越大分段越长、弯折越少（参考 VIII_LidarArrayStats）。 */
    private static final float CHARGE_ARC_SEGMENT_LENGTH_MULT = 8f;
    /** 电弧锯齿减弱因子：越大锯齿越平缓。 */
    private static final float CHARGE_ARC_ZIGZAG_REDUCTION = 0.15f;
    /** 电弧末端淡出距离（像素）。 */
    private static final float CHARGE_ARC_FADE_OUT_DIST = 50f;
    /** 电弧最小淡出倍率：保证末端淡出足够明显。 */
    private static final float CHARGE_ARC_MIN_FADE_OUT_MULT = 10f;
    /** 电弧闪烁频率倍率：越小闪得越慢。 */
    private static final float CHARGE_ARC_FLICKER_RATE_MULT = 0.3f;
    /** 电弧移动时长下限（秒），避免过快闪烁。 */
    private static final float CHARGE_ARC_MOVEMENT_DUR = 0.3f;
    /** 电弧扭曲强度（warping），让弧线更不规则。 */
    private static final float CHARGE_ARC_WARPING = 1f;
    /** 电弧颜色（外弧 / 内芯），每根随机取一组，整体偏紫蓝。 */
    private static final Color[][] CHARGE_ARC_COLORS = {
            {new Color(75, 0, 130, 200), new Color(100, 20, 150, 240)},
            {new Color(138, 43, 226, 180), new Color(180, 100, 255, 220)},
            {new Color(106, 90, 205, 190), new Color(147, 112, 219, 230)},
            {new Color(65, 105, 225, 185), new Color(100, 149, 237, 225)},
            {new Color(25, 25, 112, 195), new Color(72, 61, 139, 235)}
    };

    // 粒子生成延迟比例：开火结束后经过冷却时间的 30% 才开始生成第一批粒子
    private static final float PARTICLE_START_RATIO = 0.3f;

    // ========================= 光晕参数 =========================
    // 光晕朝向相对于舰船的固定偏移（度）
    static final float Facing = 90f;

    // ========================= 粒子效果参数 =========================
    // 所有粒子参数均基于武器本地坐标系，distX/distY 为本地偏移，velAngleOffset 为速度方向相对于武器角度的偏移

    // 粒子1（原fire2）参数
    private static final float PARTICLE1_DIST_X = -58f;                // 本地X方向偏移（像素）
    private static final float PARTICLE1_DIST_Y = 62f;                 // 本地Y方向偏移（像素）
    private static final float PARTICLE1_VEL_ANGLE_OFFSET = 50f;       // 速度方向相对于武器角度的偏移（度）

    // 粒子2（原fire1）参数
    private static final float PARTICLE2_DIST_X = -58f;
    private static final float PARTICLE2_DIST_Y = -62f;
    private static final float PARTICLE2_VEL_ANGLE_OFFSET = -50f;

    // 粒子3
    private static final float PARTICLE3_DIST_X = -62f;
    private static final float PARTICLE3_DIST_Y = 64f;
    private static final float PARTICLE3_VEL_ANGLE_OFFSET = 65f;

    // 粒子4
    private static final float PARTICLE4_DIST_X = -62f;
    private static final float PARTICLE4_DIST_Y = -64f;
    private static final float PARTICLE4_VEL_ANGLE_OFFSET = -65f;

    // 粒子5
    private static final float PARTICLE5_DIST_X = -65f;
    private static final float PARTICLE5_DIST_Y = 65f;
    private static final float PARTICLE5_VEL_ANGLE_OFFSET = 80f;

    // 粒子6
    private static final float PARTICLE6_DIST_X = -65f;
    private static final float PARTICLE6_DIST_Y = -65f;
    private static final float PARTICLE6_VEL_ANGLE_OFFSET = -80f;

    // 粒子7（第一组远距离粒子）
    private static final float PARTICLE7_DIST_X = -162f;
    private static final float PARTICLE7_DIST_Y = 64f;
    private static final float PARTICLE7_VEL_ANGLE_OFFSET = 65f;

    // 粒子8
    private static final float PARTICLE8_DIST_X = -162f;
    private static final float PARTICLE8_DIST_Y = -64f;
    private static final float PARTICLE8_VEL_ANGLE_OFFSET = -65f;

    // 粒子9
    private static final float PARTICLE9_DIST_X = -162f;
    private static final float PARTICLE9_DIST_Y = 64f;
    private static final float PARTICLE9_VEL_ANGLE_OFFSET = 120f;

    // 粒子10
    private static final float PARTICLE10_DIST_X = -162f;
    private static final float PARTICLE10_DIST_Y = -64f;
    private static final float PARTICLE10_VEL_ANGLE_OFFSET = -120f;

    // 速度随机范围参数
    private static final float VELOCITY_RANDOM_BASE_MIN = 10f;          // 基础速度随机最小值
    private static final float VELOCITY_RANDOM_BASE_MAX = 80f;          // 基础速度随机最大值
    private static final float VELOCITY_RANDOM_ADD_MIN = -20f;          // 额外随机增量最小值
    private static final float VELOCITY_RANDOM_ADD_MAX = 50f;           // 额外随机增量最大值

    // 粒子通用属性
    private static final float PARTICLE_SIZE = 5f;                      // 粒子大小（像素）
    private static final float PARTICLE_BRIGHTNESS = 1f;                // 粒子亮度
    private static final float PARTICLE_DURATION = 1f;                  // 粒子持续时间（秒）

    // ========================= 持续光晕参数 =========================
    /** 光晕淡入时间（秒） */
    private static final float FLARE_FADE_IN = 0.15f;
    /** 光晕保持时间（秒） */
    private static final float FLARE_FULL = 0.3f;
    /** 光晕淡出时间（秒） */
    private static final float FLARE_FADE_OUT = 0.1f;
    /** 单个光晕总生命周期（秒），到点后主动移除实体 */
    private static final float FLARE_TOTAL_LIFE = FLARE_FADE_IN + FLARE_FULL + FLARE_FADE_OUT;
    /** 持续生成的间隔（秒）：每隔这么久生成一组光晕 */
    private static final float FLARE_SPAWN_INTERVAL = 0.08f;
    /** 开始生成光晕所需的最低充能等级 */
    private static final float FLARE_CHARGE_THRESHOLD = 0.8f;
    /** 场上同时存在的光晕数量上限（兜底，正常不会触发） */
    private static final int MAX_ACTIVE_FLARES = 18;

    /** 蓝色光晕基础尺寸（世界单位） */
    private static final float BLUE_FLARE_WIDTH = 3000f;
    private static final float BLUE_FLARE_HEIGHT = 100f;
    /**
     * 正圆小型光晕的直径（世界单位）。
     * 这个值是留给手动调整的：改大就更明显，改小就更含蓄。
     */
    private static final float ROUND_FLARE_SIZE = 150f;
    private static final float ROUND_FLARE_SIZE2 = 300f;

    /** 光晕辉光强度（越大边缘越亮、衰减越远） */
    private static final float FLARE_GLOW_POWER = 0.3f;
    /** 光晕辉光强度（越大边缘越亮、衰减越远） */
    private static final float FLARE_GLOW_POWER2 = 0f;
    /** 光晕噪点强度（0 表示完全光滑） */
    private static final float FLARE_NOISE_POWER = 0.1f;

    /** 光晕内圈颜色：纯白 */
    private static final Color FLARE_CORE_COLOR = new Color(255, 255, 255, 255);
    /** 光晕外圈颜色：蓝色 */
    private static final Color FLARE_FRINGE_COLOR = new Color(0, 0, 255, 205);

    /** 光晕内圈颜色：纯白 */
    private static final Color FLARE_CORE_COLOR2 = new Color(255, 255, 255, 0);
    /** 光晕外圈颜色：蓝色 */
    private static final Color FLARE_FRINGE_COLOR2 = new Color(153, 0, 255, 255);

    // ========================= 光晕实体引用 =========================
    // 持续生成的光晕实体列表，每帧更新位置与朝向
    private final List<ActiveFlare> activeFlares = new ArrayList<ActiveFlare>();

    /** 前摇放电特效的生成节拍（扭曲与电弧共用）。 */
    private final IntervalUtil chargeFxTimer = new IntervalUtil(CHARGE_RIPPLE_INTERVAL_MIN, CHARGE_RIPPLE_INTERVAL_MAX);
    /** 电弧颜色随机源。 */
    private final Random chargeFxRandom = new Random();
    // 距离下一次生成的时间累计
    private float flareSpawnTimer = 0f;

    // ========================= 公共方法 =========================

    /**
     * 每帧调用，更新武器特效。
     *
     * @param amount 时间增量（秒）
     * @param engine 战斗引擎
     * @param weapon 当前武器
     */
    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 游戏暂停或武器无动画时跳过
        if (engine.isPaused() || weapon.getAnimation() == null) return;

        float chargeLevel = weapon.getChargeLevel();                // 当前充能等级 (0~1)
        float cooldownRemaining = weapon.getCooldownRemaining();   // 剩余冷却时间
        float totalCooldown = weapon.getCooldown();                 // 总冷却时间

        // ==================== 前摇放电特效 ====================
        // 前摇进入 45% 后，持续在炮口生成扭曲，并从 EMP 装饰槽位向炮口拉电弧
        updateChargeDischargeFx(amount, weapon, chargeLevel, cooldownRemaining);

        // ==================== 动画阶段广播 ====================
        // 把本帧所处的动画阶段写进舰船自定义数据，三圈环只读这个状态，
        // 不再各自去推断充能等级 / 冷却计时器在充能型爆发光束上的语义。
        publishAnimState(weapon, chargeLevel, cooldownRemaining, totalCooldown);

        // ==================== 冷却奇点 ====================
        // 光束结束、武器进入冷却时，在最后一次光束终点生成奇点
        updateSingularityRift(engine, weapon, cooldownRemaining);

        // ==================== 开火光晕处理 ====================
        // 持续生成光晕并持续更新位置与朝向；武器进入冷却后停止生成
        updateFlares(amount, weapon, chargeLevel, cooldownRemaining);

        // ==================== 武器动画帧管理 ====================
        if (cooldownRemaining > 0 && totalCooldown > 0) {
            float elapsedCooldown = totalCooldown - cooldownRemaining; // 已经过冷却时间
            float phase1End = totalCooldown * HOLD_RATIO;               // 第一阶段结束时间（保持第20帧）
            float phase2End = totalCooldown * (HOLD_RATIO + ANIM_RATIO); // 第二阶段结束时间（动画结束）

            if (elapsedCooldown <= phase1End) {
                // 冷却前 HOLD_RATIO 比例的时间：保持第20帧（炮口准备状态）
                weapon.getAnimation().setFrame(20);
            } else if (elapsedCooldown <= phase2End) {
                // 接下来的 ANIM_RATIO 比例时间：播放21~41帧动画（能量释放动画）
                float progress = (elapsedCooldown - phase1End) / (totalCooldown * ANIM_RATIO);
                progress = Math.min(1.0f, Math.max(0.0f, progress));
                int frame = 21 + (int) (progress * 21); // 21到41帧
                weapon.getAnimation().setFrame(frame);
            } else {
                // 剩余时间保持第0帧（静止状态）
                weapon.getAnimation().setFrame(0);
            }
        } else {
            // ==================== 前摇阶段的动画 ====================
            // 前摇进度 0%~10%：保持第 0 帧
            // 前摇进度 10%~50%：从第 1 帧推进到第 21 帧（到 50% 时正好是第 21 帧）
            // 前摇进度 50% 之后：定格在第 21 帧，等待开火
            weapon.getAnimation().setFrame(resolveChargeFrame(weapon, chargeLevel));
        }

        // ==================== 冷却期间的粒子效果 ====================
        if (cooldownRemaining > 0 && totalCooldown > 0) {
            float elapsedCooldown = totalCooldown - cooldownRemaining; // 已经过冷却时间
            float weaponAngle = weapon.getCurrAngle();                 // 武器当前角度
            Vector2f ww = weapon.getLocation();                        // 武器世界坐标

            // 第一组粒子（1~6）：在冷却时间的后70%生成（即经过前30%后开始）
            if (elapsedCooldown > totalCooldown * PARTICLE_START_RATIO) {
                spawnParticle(engine, weaponAngle, ww,
                        PARTICLE1_DIST_X, PARTICLE1_DIST_Y,
                        PARTICLE1_VEL_ANGLE_OFFSET);
                spawnParticle(engine, weaponAngle, ww,
                        PARTICLE2_DIST_X, PARTICLE2_DIST_Y,
                        PARTICLE2_VEL_ANGLE_OFFSET);
                spawnParticle(engine, weaponAngle, ww,
                        PARTICLE3_DIST_X, PARTICLE3_DIST_Y,
                        PARTICLE3_VEL_ANGLE_OFFSET);
                spawnParticle(engine, weaponAngle, ww,
                        PARTICLE4_DIST_X, PARTICLE4_DIST_Y,
                        PARTICLE4_VEL_ANGLE_OFFSET);
                spawnParticle(engine, weaponAngle, ww,
                        PARTICLE5_DIST_X, PARTICLE5_DIST_Y,
                        PARTICLE5_VEL_ANGLE_OFFSET);
                spawnParticle(engine, weaponAngle, ww,
                        PARTICLE6_DIST_X, PARTICLE6_DIST_Y,
                        PARTICLE6_VEL_ANGLE_OFFSET);
            }

            // 第二组粒子（7~10）：在冷却时间的后80%生成（即经过前20%后开始），持续到冷却结束
            // 注意：该条件独立于上一组，因此两组粒子在时间上会有重叠（0.3~1.0 同时存在）
            if (elapsedCooldown > totalCooldown * ANIM_RATIO) {
                spawnParticle(engine, weaponAngle, ww,
                        PARTICLE7_DIST_X, PARTICLE7_DIST_Y,
                        PARTICLE7_VEL_ANGLE_OFFSET);
                spawnParticle(engine, weaponAngle, ww,
                        PARTICLE8_DIST_X, PARTICLE8_DIST_Y,
                        PARTICLE8_VEL_ANGLE_OFFSET);
                spawnParticle(engine, weaponAngle, ww,
                        PARTICLE9_DIST_X, PARTICLE9_DIST_Y,
                        PARTICLE9_VEL_ANGLE_OFFSET);
                spawnParticle(engine, weaponAngle, ww,
                        PARTICLE10_DIST_X, PARTICLE10_DIST_Y,
                        PARTICLE10_VEL_ANGLE_OFFSET);
            }
        }
    }

    /**
     * 前摇进入 {@link #CHARGE_FX_START} 之后持续生成扭曲与电弧特效。
     *
     * <p>扭曲：在炮口叠加一层 {@link RippleDistortion}，尺寸与强度随前摇进度增长；
     * 电弧：从舰体上所有 id 以 {@code EMP} 开头的装饰武器槽位拉向炮口，
     * 不生成炮口到光束终点的电弧（那一段在光束自身特效里处理）。</p>
     *
     * @param amount            时间增量
     * @param weapon            主武器
     * @param chargeLevel       前摇进度（0~1）
     * @param cooldownRemaining 剩余冷却时间（>0 表示已开火，不再生成）
     */
    private void updateChargeDischargeFx(float amount, WeaponAPI weapon, float chargeLevel, float cooldownRemaining) {
        if (cooldownRemaining > 0f) return;
        if (chargeLevel < CHARGE_FX_START) return;

        ShipAPI ship = weapon.getShip();
        if (ship == null || ship.isHulk()) return;

        chargeFxTimer.advance(amount);
        if (!chargeFxTimer.intervalElapsed()) return;

        Vector2f muzzle = weapon.getFirePoint(0);
        if (muzzle == null) {
            muzzle = weapon.getLocation();
        }
        if (muzzle == null) return;

        createChargeRipple(muzzle, chargeLevel);
        spawnChargeArcs(weapon, ship, muzzle, chargeLevel);
    }

    /**
     * 在炮口生成一层重力扭曲，参数与 Moci_GravityWaveCannon 的充能扭曲保持一致。
     *
     * <p>与参考实现的差异仅在于生成频率（这里用固定间隔，参考实现逐帧生成），
     * 其余全部对齐：尺寸/强度按前摇进度线性缩放、方向固定不随机翻转（避免扭曲方向忽正忽负造成的
     * 不规则观感）、100 帧/秒推进、0.1 秒尺寸淡入、0.6 秒生命周期末尾强度淡出。</p>
     *
     * @param location    扭曲中心（炮口）
     * @param chargeLevel 前摇进度（0~1）
     */
    private void createChargeRipple(Vector2f location, float chargeLevel) {
        RippleDistortion ripple = new RippleDistortion();
        ripple.setLocation(location);
        ripple.setSize(CHARGE_RIPPLE_SIZE_MULT * chargeLevel);
        ripple.setIntensity(CHARGE_RIPPLE_INTENSITY_MULT * chargeLevel);
        // 方向固定为 true：叠层时涟漪始终朝同一侧扩散，不会互相抵消出「乱扭曲」
        ripple.flip(true);
        ripple.setLifetime(CHARGE_RIPPLE_LIFETIME);
        ripple.setFrameRate(CHARGE_RIPPLE_FRAME_RATE);
        ripple.setCurrentFrame(0f);
        ripple.fadeInSize(CHARGE_RIPPLE_FADE_IN_SIZE);
        ripple.fadeOutIntensity(CHARGE_RIPPLE_FADE_OUT_INTENSITY);
        DistortionShader.addDistortion(ripple);
    }

    /**
     * 从所有 EMP 装饰武器槽位向炮口拉出电弧。
     *
     * <p>与 {@code VIII_LidarArrayStats} 的做法一致：先构造
     * {@link EmpArcEntityAPI.EmpArcParams} 配置分段/锯齿/淡出/闪烁，再调用
     * {@link CombatEngineAPI#spawnEmpArcVisual} 得到 {@link EmpArcEntityAPI}，
     * 最后逐项微调（关闭起点光晕、单段闪烁、扭曲强度）。</p>
     *
     * @param weapon      主武器
     * @param ship        武器所在舰船
     * @param muzzle      炮口世界坐标（电弧终点）
     * @param chargeLevel 前摇进度（0~1）
     */
    private void spawnChargeArcs(WeaponAPI weapon, ShipAPI ship, Vector2f muzzle, float chargeLevel) {
        if (ship.getHullSpec() == null) return;

        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) return;

        float thickness = CHARGE_ARC_THICKNESS_BASE + CHARGE_ARC_THICKNESS_GAIN * chargeLevel;

        for (WeaponSlotAPI slot : ship.getHullSpec().getAllWeaponSlotsCopy()) {
            if (slot == null) continue;
            String slotId = slot.getId();
            if (slotId == null || !slotId.startsWith(CHARGE_ARC_SLOT_PREFIX)) continue;

            Vector2f from = slotLocationToWorld(ship, slot.getLocation());
            if (from == null) continue;

            Color[] colors = CHARGE_ARC_COLORS[chargeFxRandom.nextInt(CHARGE_ARC_COLORS.length)];

            // 电弧参数：参考 VIII_LidarArrayStats 的「重型裁决者」配置
            EmpArcEntityAPI.EmpArcParams params = new EmpArcEntityAPI.EmpArcParams();
            params.segmentLengthMult = CHARGE_ARC_SEGMENT_LENGTH_MULT;
            params.zigZagReductionFactor = CHARGE_ARC_ZIGZAG_REDUCTION;
            params.fadeOutDist = CHARGE_ARC_FADE_OUT_DIST;
            params.minFadeOutMult = CHARGE_ARC_MIN_FADE_OUT_MULT;
            params.flickerRateMult = CHARGE_ARC_FLICKER_RATE_MULT;
            params.movementDurOverride = Math.max(0.2f, CHARGE_ARC_MOVEMENT_DUR);

            EmpArcEntityAPI arc = engine.spawnEmpArcVisual(from, ship, muzzle, null, thickness, colors[0], colors[1], params);
            if (arc == null) continue;

            arc.setRenderGlowAtStart(false); // 起点不画光晕，避免十个槽位堆成一团
            arc.setSingleFlickerMode(true);  // 整条电弧同步闪烁
            arc.setWarping(CHARGE_ARC_WARPING);
        }
    }

    /**
     * 把 hull 坐标系里的槽位偏移换算成世界坐标。
     *
     * @param ship   舰船
     * @param offset 槽位在 hull 坐标系中的偏移
     * @return 世界坐标；offset 为 null 时返回 null
     */
    private Vector2f slotLocationToWorld(ShipAPI ship, Vector2f offset) {
        if (offset == null) return null;
        float rad = (float) Math.toRadians(ship.getFacing());
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        Vector2f world = new Vector2f(offset.x * cos - offset.y * sin, offset.x * sin + offset.y * cos);
        world.x += ship.getLocation().x;
        world.y += ship.getLocation().y;
        return world;
    }

    /**
     * 把当前动画阶段写入舰船自定义数据，供三圈装饰环读取。
     *
     * <p>阶段划分必须与 {@link #resolveChargeFrame(WeaponAPI, float)} 和冷却三段动画保持一致：
     * 前摇 50% 定格第 21 帧、冷却 5%~40% 播放 21~41 帧、40% 之后回到第 0 帧。</p>
     *
     * @param weapon            主武器
     * @param chargeLevel       当前前摇进度（0~1）
     * @param cooldownRemaining 剩余冷却时间
     * @param totalCooldown     总冷却时间
     */
    /**
     * 冷却开始时在最后一次光束终点生成一个奇点。
     *
     * <p>只在「刚进入冷却」的那一帧生成一次，冷却结束后清除标记，
     * 下一次开火循环可以再次生成。光束终点由
     * {@link VIII_RealityRipper_BeamEffect} 每帧写入舰船自定义数据。</p>
     *
     * @param engine            战斗引擎
     * @param weapon            主武器
     * @param cooldownRemaining 剩余冷却时间（>0 表示处于冷却中）
     */
    private void updateSingularityRift(CombatEngineAPI engine, WeaponAPI weapon, float cooldownRemaining) {
        ShipAPI ship = weapon.getShip();
        if (ship == null) return;

        if (cooldownRemaining > 0f) {
            if (ship.getCustomData().get(KEY_RIFT_SPAWNED) != null) return;
            ship.setCustomData(KEY_RIFT_SPAWNED, Boolean.TRUE);

            Vector2f point = null;
            Object stored = ship.getCustomData().get(KEY_LAST_BEAM_END);
            if (stored instanceof Vector2f) point = new Vector2f((Vector2f) stored);
            if (point == null) point = new Vector2f(weapon.getLocation());

            VIII_RealityRipper_SingularityRift.spawn(engine, ship, point);
        } else if (ship.getCustomData().get(KEY_RIFT_SPAWNED) != null) {
            ship.setCustomData(KEY_RIFT_SPAWNED, null);
        }
    }

    private void publishAnimState(WeaponAPI weapon, float chargeLevel, float cooldownRemaining, float totalCooldown) {
        ShipAPI ship = weapon.getShip();
        if (ship == null) return;

        VIII_RealityRipper_AnimState state = VIII_RealityRipper_AnimState.get(ship);
        if (state == null) return;

        state.chargeLevel = chargeLevel;

        if (cooldownRemaining > 0f && totalCooldown > 0f) {
            float progress = (totalCooldown - cooldownRemaining) / totalCooldown;
            progress = Math.min(1.0f, Math.max(0.0f, progress));
            state.cooldownProgress = progress;

            if (progress < VIII_RealityRipper_AnimState.COOLDOWN_RING_STATIC) {
                // 冷却最初 5%：主武器停在第 20 帧，环保持静止
                state.phase = VIII_RealityRipper_AnimState.PHASE_COOLDOWN_START;
            } else if (progress <= VIII_RealityRipper_AnimState.COOLDOWN_MAIN_FRAME_ZERO) {
                // 5%~40%：主武器从第 20 帧过渡到 21~41 帧，环持续旋转
                state.phase = VIII_RealityRipper_AnimState.PHASE_COOLDOWN_ROTATE;
            } else {
                // 40% 之后：主武器已回到第 0 帧，环收尾
                state.phase = VIII_RealityRipper_AnimState.PHASE_COOLDOWN_FINISH;
            }
            return;
        }

        state.cooldownProgress = 0f;
        if (chargeLevel >= VIII_RealityRipper_AnimState.CHARGE_LOCK) {
            // 前摇过半：主武器定格第 21 帧，环收尾
            state.phase = VIII_RealityRipper_AnimState.PHASE_CHARGE_LOCKED;
        } else if (chargeLevel >= CHARGE_ANIM_START) {
            // 前摇 10%~50%：主武器 1~21 帧、环旋转
            state.phase = VIII_RealityRipper_AnimState.PHASE_CHARGE_ROTATE;
        } else {
            state.phase = VIII_RealityRipper_AnimState.PHASE_IDLE;
        }
    }

    /**
     * 计算前摇阶段应显示的动画帧。
     *
     * <p>前摇进度低于 10% 时保持第 0 帧；10%~50% 之间把进度线性映射到第 1~21 帧；
     * 达到 50% 之后定格在第 21 帧，等待开火。</p>
     *
     * @param weapon      当前武器
     * @param chargeLevel 当前前摇进度（0~1）
     * @return 应设置的动画帧
     */
    private int resolveChargeFrame(WeaponAPI weapon, float chargeLevel) {
        if (weapon.getAnimation() == null) return 0;
        if (chargeLevel < CHARGE_ANIM_START) return 0;
        if (chargeLevel >= CHARGE_LOCK) return CHARGE_LOCK_FRAME;

        float progress = (chargeLevel - CHARGE_ANIM_START) / (CHARGE_LOCK - CHARGE_ANIM_START);
        int frame = 1 + (int) (progress * (CHARGE_LOCK_FRAME - 1));
        if (frame < 1) frame = 1;
        if (frame > CHARGE_LOCK_FRAME) frame = CHARGE_LOCK_FRAME;
        return frame;
    }

    /**
     * 持续生成并更新开火光晕。
     * 每帧把所有仍存活的光晕移动到当前枪口位置，并同步朝向（舰船朝向 + Facing），
     * 所以光晕始终贴着枪口、并随舰船转向一起转。
     * 武器进入冷却或充能不足时不再生成新光晕，已有光晕按 0.15 / 0.3 / 0.1 的生命周期自然淡出。
     *
     * @param amount            时间增量（秒）
     * @param weapon            当前武器
     * @param chargeLevel       当前充能等级
     * @param cooldownRemaining 剩余冷却时间
     */
    private void updateFlares(float amount, WeaponAPI weapon, float chargeLevel, float cooldownRemaining) {
        ShipAPI ship = weapon.getShip();
        float facing = (ship == null) ? 0f : ship.getFacing() + Facing;
        Vector2f location = weapon.getLocation();

        // 先更新所有存活光晕：位置与朝向持续跟随枪口 / 舰船
        Iterator<ActiveFlare> iter = activeFlares.iterator();
        while (iter.hasNext()) {
            ActiveFlare flare = iter.next();
            flare.age += amount;
            if (flare.entity == null || flare.entity.hasDelete() || flare.age >= FLARE_TOTAL_LIFE) {
                // 生命周期结束：主动移除，避免实体在空中累积
                if (flare.entity != null && !flare.entity.hasDelete()) {
                    CombatRenderingManager.removeEntity(flare.entity);
                }
                iter.remove();
                continue;
            }
            flare.entity.setLocation(location);
            flare.entity.setFacingScale(facing, 1f, 1f);
        }

        // 冷却中（或充能不足）不再生成新光晕
        if (cooldownRemaining > 0f || chargeLevel <= FLARE_CHARGE_THRESHOLD) {
            flareSpawnTimer = 0f;
            return;
        }

        // 持续生成：按固定间隔成组生成（蓝色光晕 + 正圆小光晕）
        flareSpawnTimer += amount;
        while (flareSpawnTimer >= FLARE_SPAWN_INTERVAL) {
            flareSpawnTimer -= FLARE_SPAWN_INTERVAL;
            spawnFlare(location, facing, randomScale(BLUE_FLARE_WIDTH, BLUE_FLARE_HEIGHT), false);
            float round = ROUND_FLARE_SIZE * randomMultiplier();
            float round2 = ROUND_FLARE_SIZE2 * randomMultiplier();
            spawnFlare3(location, facing, new Vector2f(round, round), true);
            spawnFlare2(location, facing, new Vector2f(round2, round2), true);
        }

        // 兜底：万一底层没有回收，强制丢弃最旧的实体，防止越积越多
        while (activeFlares.size() > MAX_ACTIVE_FLARES) {
            ActiveFlare oldest = activeFlares.remove(0);
            if (oldest.entity != null && !oldest.entity.hasDelete()) {
                CombatRenderingManager.removeEntity(oldest.entity);
            }
        }
    }

    /**
     * 生成一个跟着枪口走的光晕实体，并记录到 activeFlares 里参与逐帧更新。
     * 实体由 {@link VIII_BoxBasedUtil#createSharpFlare} 创建（不自动入队），
     * 这里手动 addEntity，方便后续逐帧更新位置与朝向、到点后再移除。
     *
     * @param location 生成位置（世界坐标）
     * @param facing   朝向（度）
     * @param scale    尺寸（世界单位）
     * @param round    是否强制为正圆
     */
    private void spawnFlare(Vector2f location, float facing, Vector2f scale, boolean round) {
        FlareEntity flare = VIII_BoxBasedUtil.createSharpFlare(location, facing, scale.x, scale.y,
                FLARE_CORE_COLOR, FLARE_FRINGE_COLOR,
                FLARE_FADE_IN, FLARE_FULL, FLARE_FADE_OUT,
                FLARE_GLOW_POWER, FLARE_NOISE_POWER);
        if (round) {
            // aspect=1 关掉 autoAspect 的纵向压缩；discRatio=2 把内圈还原成正圆
            flare.setAspect(1.0f);
            flare.setDiscRatio(2.0f);
        }
        CombatRenderingManager.addEntity(flare);
        activeFlares.add(new ActiveFlare(flare));
    }

    private void spawnFlare2(Vector2f location, float facing, Vector2f scale, boolean round) {
        FlareEntity flare = VIII_BoxBasedUtil.createSharpFlare(location, facing, scale.x, scale.y,
                FLARE_CORE_COLOR2, FLARE_FRINGE_COLOR2,
                FLARE_FADE_IN, FLARE_FULL, FLARE_FADE_OUT,
                FLARE_GLOW_POWER2, FLARE_NOISE_POWER);
        if (round) {
            // aspect=1 关掉 autoAspect 的纵向压缩；discRatio=2 把内圈还原成正圆
            flare.setAspect(1.0f);
            flare.setDiscRatio(2.0f);
        }
        CombatRenderingManager.addEntity(flare);
        activeFlares.add(new ActiveFlare(flare));
    }

    private void spawnFlare3(Vector2f location, float facing, Vector2f scale, boolean round) {
        FlareEntity flare = VIII_BoxBasedUtil.createSharpFlare(location, facing, scale.x, scale.y,
                FLARE_CORE_COLOR2, FLARE_FRINGE_COLOR2,
                FLARE_FADE_IN, FLARE_FULL, FLARE_FADE_OUT,
                FLARE_GLOW_POWER2, FLARE_NOISE_POWER);
        if (round) {
            // aspect=1 关掉 autoAspect 的纵向压缩；discRatio=2 把内圈还原成正圆
            flare.setAspect(1.0f);
            flare.setDiscRatio(2.0f);
        }
        CombatRenderingManager.addEntity(flare);
        activeFlares.add(new ActiveFlare(flare));
    }

    /** 随机尺寸倍率，避免所有光晕完全重合、看起来死板。 */
    private static float randomMultiplier() {
        return 0.85f + (float) Math.random() * 0.3f;
    }

    /** 按基础宽高生成带随机抖动的尺寸。 */
    private static Vector2f randomScale(float baseWidth, float baseHeight) {
        float mult = randomMultiplier();
        return new Vector2f(baseWidth * mult, baseHeight * mult);
    }

    /** 一个仍在生命周期内的光晕实体及其存活时间。 */
    private static final class ActiveFlare {
        private final FlareEntity entity;
        private float age;

        private ActiveFlare(FlareEntity entity) {
            this.entity = entity;
        }
    }

    /**
     * 生成单个粒子（火花）。
     * 位置基于武器本地偏移 (distX, distY) 经武器朝向旋转得到，实现粒子跟随武器移动和旋转。
     * 速度方向基于武器角度叠加 velAngleOffset，并带有随机大小。
     *
     * @param engine         战斗引擎
     * @param weaponAngle    武器当前角度（度）
     * @param weaponLoc      武器世界坐标
     * @param distX          本地 X 方向偏移（像素）
     * @param distY          本地 Y 方向偏移（像素）
     * @param velAngleOffset 速度方向相对于武器角度的偏移（度）
     */
    private void spawnParticle(CombatEngineAPI engine, float weaponAngle, Vector2f weaponLoc,
                               float distX, float distY,
                               float velAngleOffset) {
        // 将武器角度转换为弧度，计算旋转矩阵
        float rad = (float) Math.toRadians(weaponAngle);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);

        // 将本地偏移 (distX, distY) 旋转到世界坐标系
        float worldOffsetX = distX * cos - distY * sin;
        float worldOffsetY = distX * sin + distY * cos;

        // 粒子世界位置 = 武器位置 + 旋转后的偏移
        Vector2f pos = new Vector2f(weaponLoc.x + worldOffsetX, weaponLoc.y + worldOffsetY);

        // 计算速度方向（世界角度）
        float velRad = (float) Math.toRadians(weaponAngle + velAngleOffset);
        float velDirX = (float) Math.cos(velRad);
        float velDirY = (float) Math.sin(velRad);

        // 随机速度大小（基于基础范围 + 额外随机增量）
        float o = (float) Math.random() * (VELOCITY_RANDOM_BASE_MAX - VELOCITY_RANDOM_BASE_MIN) + VELOCITY_RANDOM_BASE_MIN;
        float a = o + (float) Math.random() * (VELOCITY_RANDOM_ADD_MAX - VELOCITY_RANDOM_ADD_MIN) + VELOCITY_RANDOM_ADD_MIN;
        float b = o + (float) Math.random() * (VELOCITY_RANDOM_ADD_MAX - VELOCITY_RANDOM_ADD_MIN) + VELOCITY_RANDOM_ADD_MIN;

        // 合成速度向量
        Vector2f velocity = new Vector2f(velDirX * a, velDirY * b);

        // 添加粒子到引擎（hit粒子，不产生伤害，仅视觉效果）
        engine.addHitParticle(pos, velocity, PARTICLE_SIZE, PARTICLE_BRIGHTNESS, PARTICLE_DURATION,
                new Color(25, 100, 155, 255)); // 蓝色调
    }
}