// 包路径：数据/舰船系统/脚本包
package data.scripts.shipsystems;

// 导入远行星号游戏API中的核心类
import com.fs.starfarer.api.Global;                              // 全局游戏实例
import com.fs.starfarer.api.combat.*;                            // 战斗相关接口
import com.fs.starfarer.api.combat.ShipEngineControllerAPI.ShipEngineAPI; // 舰船引擎接口
import com.fs.starfarer.api.graphics.SpriteAPI;                  // 精灵图API
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;     // 基础舰船系统脚本
import com.fs.starfarer.api.util.IntervalUtil;                   // 间隔计时工具
// 导入自定义工具类和视觉特效类
import data.scripts.util.VIII_BoxBasedUtil;                      // 基于BoxUtil的粒子工具
import data.scripts.util.VIII_TipVortexVisual;                 // 尾迹涡流视觉特效
// 导入Dark Shaders库的光照类
import org.dark.shaders.light.LightShader;                       // 光照着色器
import org.dark.shaders.light.StandardLight;                     // 标准动态光源
// 导入LazyLib工具类
import org.lazywizard.lazylib.MathUtils;                         // 数学工具
import org.lazywizard.lazylib.combat.CombatUtils;                // 战斗工具
// 导入LWJGL数学库
import org.lwjgl.util.vector.Vector2f;                           // 二维向量

import java.awt.Color;                                           // AWT颜色类

/**
 * 大和型波动引擎系统脚本（Travel Drive Yamato）
 * 实现舰船推进系统的视觉效果、速度加成、伤害减免、燃烧伤害等。
 */
public class VIII_TravelDriveYamatoStats extends BaseShipSystemScript {

    // ==================== 贴图配置 ====================
    // 轮廓特效贴图所在的目录
    private static final String SPRITE_OUTLINE = "fx";
    // 轮廓特效贴图的ID（文件名）
    private static final String SPRITE_OUTLINE_ID = "VIII_slipstream2";
    // 烟雾特效贴图所在的目录
    private static final String SPRITE_SMOKE = "fx";
    // 烟雾特效贴图的ID
    private static final String SPRITE_SMOKE_ID = "VIII_slipstream3";

    private static Boolean boxUtilAvailable = null;

    // ==================== 颜色配置 ====================
    // 舰船抖动效果的颜色（淡蓝色）
    private static final Color JITTER_COLOR = new Color(100, 165, 255, 255);
    // 动态光源的颜色（淡蓝色）
    private static final Color LIGHT_COLOR = new Color(100, 165, 255, 255);

    // 涡旋颜色配置（轮廓层）
    private static final Color OUTLINE_CENTER_COLOR = new Color(150, 200, 255, 150); // 轮廓中心颜色
    private static final Color OUTLINE_UNDER_COLOR = new Color(80, 140, 220, 150);   // 轮廓底层颜色
    private static final Color OUTLINE_TIP_COLOR = new Color(180, 220, 255, 150);    // 轮廓尖端颜色
    private static final Color OUTLINE_TOP_COLOR = new Color(200, 230, 255, 150);    // 轮廓顶层颜色

    // 涡旋颜色配置（烟雾层）
    private static final Color SMOKE_CENTER_COLOR = new Color(100, 165, 255, 200);   // 烟雾中心颜色
    private static final Color SMOKE_UNDER_COLOR = new Color(50, 100, 200, 180);     // 烟雾底层颜色
    private static final Color SMOKE_TIP_COLOR = new Color(120, 180, 255, 200);      // 烟雾尖端颜色
    private static final Color SMOKE_TOP_COLOR = new Color(150, 200, 255, 180);      // 烟雾顶层颜色

    // ==================== 光源参数 ====================
    private static final boolean ENABLE_LIGHT = true;                // 是否启用动态光源
    private static final float LIGHT_INTENSITY_MIN = 0.0f;           // 最小光源强度（effectLevel=0时）
    private static final float LIGHT_INTENSITY_MAX = 2.0f;           // 最大光源强度（effectLevel=1时）
    private static final float LIGHT_SIZE_MIN = 0f;                 // 最小光源大小（effectLevel=0时）
    private static final float LIGHT_SIZE_MAX = 750f;               // 最大光源大小（effectLevel=1时）

    // ==================== 性能参数 ====================
    private static final float SPEED_BONUS = 200f;                   // 速度加成（像素/秒）
    private static final float TURN_MULT = 0.1f;                     // 转向倍率（0.1 = 降低90%转向能力）
    private static final float DAMAGE_REDUCTION = 0.25f;             // 伤害减免（0.25 = 25%减免）
    private static final float SPEED_FACTOR = 1f;                    // 推力系数（未使用）
    private static final boolean ENABLE_THRUST = false;              // 是否启用推力（false则只有速度加成）

    // ==================== 灼烧伤害参数 ====================
    private static final float BURN_DPS = 400f;                      // 每秒灼烧伤害
    private static final float DPS_DURATION = 0.1f;                  // 伤害间隔（秒），每次造成40点伤害
    private static final boolean ENABLE_BURN_DAMAGE = true;          // 是否启用灼烧伤害

    // ==================== 视觉效果参数 - 宽度速度联动控制 ====================
    private static final float WIDTH_STANDARD_SPEED = 100f;          // 宽度标准速度（速度达到此值时宽度倍率=1.0）
    private static final float WIDTH_MIN_MULT = 0.5f;                // 宽度最小倍率（速度很低时的下限）
    private static final float WIDTH_MAX_MULT = 3.0f;                // 宽度最大倍率（速度很高时的上限）

    // ==================== 视觉效果参数 - 长度速度联动控制 ====================
    private static final float LENGTH_STANDARD_SPEED = 55;           // 长度标准速度（速度达到此值时长度倍率=1.0）
    private static final float LENGTH_MIN_MULT = 1.30f;              // 长度最小倍率（速度很低时的下限）
    private static final float LENGTH_MAX_MULT = 1.30f;              // 长度最大倍率（速度很高时的上限）

    // ==================== 特效位置参数（负数=向后，正数=向前，相对于飞船朝向） ====================
    private static final float OUTLINE_START_OFFSET = -30f;          // 轮廓起点偏移（effectLevel=0时）
    private static final float OUTLINE_END_OFFSET = -45f;            // 轮廓终点偏移（effectLevel=1时）
    private static final float SMOKE_START_OFFSET = -30f;            // 烟雾起点偏移（effectLevel=0时）
    private static final float SMOKE_END_OFFSET = -60f;              // 烟雾终点偏移（effectLevel=1时）

    // ==================== 特效尺寸参数（倍率相对于基础尺寸，会叠加速度倍率） ====================
    private static final float SMOKE_WIDTH_START_MULT = 0.5f;        // 烟雾起始宽度倍率（effectLevel=0时）
    private static final float SMOKE_WIDTH_END_MULT = 1.2f;          // 烟雾终点宽度倍率（effectLevel=1时）
    private static final float SMOKE_LENGTH_START_MULT = 0.9f;       // 烟雾起始长度倍率（effectLevel=0时）
    private static final float SMOKE_LENGTH_END_MULT = 1.0f;         // 烟雾终点长度倍率（effectLevel=1时）

    private static final float OUTLINE_WIDTH_START_MULT = 0.5f;      // 轮廓起始宽度倍率（effectLevel=0时）
    private static final float OUTLINE_WIDTH_END_MULT = 1.2f;        // 轮廓终点宽度倍率（effectLevel=1时）
    private static final float OUTLINE_LENGTH_START_MULT = 0.9f;     // 轮廓起始长度倍率（effectLevel=0时）
    private static final float OUTLINE_LENGTH_END_MULT = 1.0f;       // 轮廓终点长度倍率（effectLevel=1时）

    // ==================== 粒子效果参数 ====================
    private static final float PARTICLE_INTERVAL = 10f;              // 粒子生成间隔倍率（实际间隔 = PARTICLE_INTERVAL * 每帧时间）
    private static final float PARTICLE_SIZE = 100f;                 // 粒子大小（像素）
    private static final float PARTICLE_DURATION = 3f;               // 粒子持续时间（秒）
    private static final float PARTICLE_ALPHA = 0.2f;                // 粒子透明度（0~1）

    // ==================== 抖动效果参数 ====================
    private static final float JITTER_RANGE = 50f;                   // 抖动范围（像素）
    private static final int JITTER_COPIES = 6;                      // 抖动副本数

    // ==================== 质量与结束增益参数 ====================
    private static final float MASS_MULT_WHEN_ON = 2.0f;             // 开启期间质量倍率（2 = +100%）
    /** 结束后全武器伤害加成（+25%），由 {@link VIII_YamatoPostBuffListener} 读取 */
    public static final float POST_DAMAGE_BONUS = 0.25f;
    /** 结束后全武器射速加成（+25%），由 {@link VIII_YamatoPostBuffListener} 读取 */
    public static final float POST_ROF_BONUS = 0.25f;
    /** 结束后增益持续时间（秒） */
    public static final float POST_BUFF_DURATION = 5.0f;
    private static final String MASS_BASE_KEY = "VIII_yamato_mass_base"; // 记录原始质量的自定义数据 key

    // ==================== 内部变量 ====================
    public static final Vector2f ZERO = new Vector2f();              // 零向量常量（用于音效位置等）

    private VIII_TipVortexVisual visualOutline = null;               // 轮廓涡旋视觉特效实例
    private VIII_TipVortexVisual visualSmoke = null;                 // 烟雾涡旋视觉特效实例
    private SpriteAPI sprite = null;                                 // 舰船精灵引用（用于重置透明度）
    private StandardLight light = null;                              // 动态光源实例

    private float jitterClock = 0f;                                  // 抖动计时器（累积系统激活时间）
    private float burnClock = 0f;                                    // 燃烧伤害计时器（累积伤害间隔）

    private final IntervalUtil interval = new IntervalUtil(1f, 1f);        // 粒子生成计时器（固定间隔1秒，实际倍率为PARTICLE_INTERVAL）

    /**
     * 应用系统效果（由游戏引擎在系统状态变化时调用）
     * @param stats       可变舰船属性统计
     * @param id          效果ID
     * @param state       系统状态（IN, ACTIVE, OUT, COOLDOWN, IDLE）
     * @param effectLevel 效果强度（0~1）
     */
    @Override
    public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
        // 获取系统所属的舰船
        ShipAPI ship = (ShipAPI) stats.getEntity();
        if (ship == null) {
            return; // 无舰船，直接返回
        }

        // 如果尚未获取舰船精灵，则保存引用（用于重置透明度）
        if (sprite == null) {
            sprite = ship.getSpriteAPI();
        }

        // 强制设置舰船精灵透明度为1（避免其他效果干扰，例如相位斗篷的透明度）
        sprite.setAlphaMult(1f);

        CombatEngineAPI engine = Global.getCombatEngine();
        // 战斗暂停时不应用任何效果变化
        if (engine.isPaused())
            return;

        // ==================== 质量增加（开启期间最高 +100%） ====================
        // 质量只能改实体字段，改完满强度即原始质量的 2 倍
        applyMassBonus(ship, effectLevel);

        // 重新开启系统时，先清掉上一轮结束后残留的武器增益，避免两段效果叠加
        if (state == State.IN) {
            clearPostBuff(ship);
        }

        // ==================== 应用性能修改 ====================
        // 平坦速度加成：增加200像素/秒
        stats.getMaxSpeed().modifyFlat(id, SPEED_BONUS);
        stats.getAcceleration().modifyFlat(id, SPEED_BONUS);
        stats.getDeceleration().modifyFlat(id, SPEED_BONUS);
        // 转向速率倍率：降低至原来的10%
        stats.getMaxTurnRate().modifyMult(id, TURN_MULT);
        // 装甲伤害承受倍率：减免效果强度 * 25% 的伤害
        stats.getArmorDamageTakenMult().modifyMult(id, 1f - DAMAGE_REDUCTION * effectLevel);
        // 船体伤害承受倍率：同上
        stats.getHullDamageTakenMult().modifyMult(id, 1f - DAMAGE_REDUCTION * effectLevel);

        // ==================== 施加推力（已禁用） ====================
        if (ENABLE_THRUST) {
            float force = ship.getMassWithModules() * SPEED_FACTOR * effectLevel * effectLevel;
            if (effectLevel < 0.5f)
                force = 0f;
            CombatUtils.applyForce(ship, ship.getFacing(), force);
        }

        // ==================== 初始化视觉效果（仅在BoxUtil可用时） ====================
        if (visualOutline == null && isBoxUtilAvailable()) {
            // 加载轮廓和烟雾的精灵
            SpriteAPI faw = Global.getSettings().getSprite(SPRITE_OUTLINE, SPRITE_OUTLINE_ID);
            SpriteAPI faw3 = Global.getSettings().getSprite(SPRITE_SMOKE, SPRITE_SMOKE_ID);

            // 创建轮廓涡旋并设置颜色
            visualOutline = new VIII_TipVortexVisual(ship, faw, faw, faw);
            visualOutline.setCenterColor(OUTLINE_CENTER_COLOR);
            visualOutline.setUnderColor(OUTLINE_UNDER_COLOR);
            visualOutline.setTipColor(OUTLINE_TIP_COLOR);
            visualOutline.setTopColor(OUTLINE_TOP_COLOR);
            engine.addLayeredRenderingPlugin(visualOutline);  // 添加到战斗渲染层

            // 创建烟雾涡旋并设置颜色
            visualSmoke = new VIII_TipVortexVisual(ship, faw3, faw, faw3);
            visualSmoke.setCenterColor(SMOKE_CENTER_COLOR);
            visualSmoke.setUnderColor(SMOKE_UNDER_COLOR);
            visualSmoke.setTipColor(SMOKE_TIP_COLOR);
            visualSmoke.setTopColor(SMOKE_TOP_COLOR);
            engine.addLayeredRenderingPlugin(visualSmoke);

            // 创建动态光源
            if (ENABLE_LIGHT) {
                light = new StandardLight(ship.getLocation(), new Vector2f(0, 0), new Vector2f(0, 0), ship);
                light.setColor(LIGHT_COLOR);
                light.setIntensity(LIGHT_INTENSITY_MIN); // 初始强度为最小值
                light.setSize(LIGHT_SIZE_MIN);           // 初始大小为最小值
                light.makePermanent();                   // 设置为永久存在（不自动消失）
                LightShader.addLight(light);             // 添加到光照管理器
            }
        }

        // ==================== 更新视觉效果（仅在BoxUtil可用且已初始化时） ====================
        if (isBoxUtilAvailable() && visualOutline != null && visualSmoke != null) {
            // 设置效果强度（影响特效的淡入淡出）
            visualOutline.setEffectLevel(effectLevel);
            visualSmoke.setEffectLevel(effectLevel);

            // 根据当前速度计算独立的宽度和长度速度倍率
            float currentSpeed = ship.getVelocity().length();
            float widthSizeMult = currentSpeed / WIDTH_STANDARD_SPEED;
            if (widthSizeMult < WIDTH_MIN_MULT)
                widthSizeMult = WIDTH_MIN_MULT;
            if (widthSizeMult > WIDTH_MAX_MULT)
                widthSizeMult = WIDTH_MAX_MULT;

            float lengthSizeMult = currentSpeed / LENGTH_STANDARD_SPEED;
            if (lengthSizeMult < LENGTH_MIN_MULT)
                lengthSizeMult = LENGTH_MIN_MULT;
            if (lengthSizeMult > LENGTH_MAX_MULT)
                lengthSizeMult = LENGTH_MAX_MULT;

            // 使用较大的倍率作为基础缩放（用于渲染半径和基础尺寸计算）
            float baseSizeMult = Math.max(widthSizeMult, lengthSizeMult);

            // --- 轮廓涡旋参数更新 ---
            visualOutline.setSizeMult(baseSizeMult);  // 设置基础缩放
            // 偏移量从起始值线性插值到结束值
            float outlineOffset = OUTLINE_START_OFFSET + (OUTLINE_END_OFFSET - OUTLINE_START_OFFSET) * effectLevel;
            visualOutline.setOffset(outlineOffset);
            // 宽度倍率和长度倍率根据effectLevel线性插值
            float outlineWidthMult = OUTLINE_WIDTH_START_MULT
                    + (OUTLINE_WIDTH_END_MULT - OUTLINE_WIDTH_START_MULT) * effectLevel;
            float outlineLengthMult = OUTLINE_LENGTH_START_MULT
                    + (OUTLINE_LENGTH_END_MULT - OUTLINE_LENGTH_START_MULT) * effectLevel;
            // 最终宽度倍率 = (速度宽度倍率 / 基础缩放) * 轮廓宽度倍率，实现独立控制
            visualOutline.setWidthMult((widthSizeMult / baseSizeMult) * outlineWidthMult);
            visualOutline.setLengthMult((lengthSizeMult / baseSizeMult) * outlineLengthMult);

            // --- 烟雾涡旋参数更新（类似轮廓） ---
            visualSmoke.setSizeMult(baseSizeMult);
            float smokeOffset = SMOKE_START_OFFSET + (SMOKE_END_OFFSET - SMOKE_START_OFFSET) * effectLevel;
            visualSmoke.setOffset(smokeOffset);
            float smokeWidthMult = SMOKE_WIDTH_START_MULT + (SMOKE_WIDTH_END_MULT - SMOKE_WIDTH_START_MULT) * effectLevel;
            float smokeLengthMult = SMOKE_LENGTH_START_MULT
                    + (SMOKE_LENGTH_END_MULT - SMOKE_LENGTH_START_MULT) * effectLevel;
            visualSmoke.setWidthMult((widthSizeMult / baseSizeMult) * smokeWidthMult);
            visualSmoke.setLengthMult((lengthSizeMult / baseSizeMult) * smokeLengthMult);
        }

        // ==================== 生成引擎粒子效果 ====================
        float amount = engine.getElapsedInLastFrame();          // 获取上一帧经过的时间（秒）
        interval.advance(PARTICLE_INTERVAL * amount);           // 推进计时器（倍率为PARTICLE_INTERVAL）
        if (interval.intervalElapsed()) {                       // 如果达到间隔时间
            // 遍历所有舰船引擎
            for (ShipEngineAPI eng : ship.getEngineController().getShipEngines()) {
                if (eng.isDisabled())
                    continue;                                   // 跳过损坏或禁用的引擎
                // 计算粒子速度方向：沿引擎朝向，速度大小为舰船最大速度的25%
                Vector2f vel = MathUtils.getPoint(new Vector2f(), ship.getMaxSpeed() * 0.25f,
                        eng.getEngineSlot().computeMidArcAngle(ship.getFacing()));

                // 使用BoxUtil粒子效果（仅在BoxUtil可用时）
                if (isBoxUtilAvailable()) {
                    try {
                        // 添加平滑星云粒子，使用引擎自身的尾迹颜色以保持一致性
                        VIII_BoxBasedUtil.addNebulaSmoothParticle(eng.getLocation(), vel,
                                eng.getContribution() * PARTICLE_SIZE,   // 粒子大小（受引擎推力贡献影响）
                                3f, 0.5f,                                 // 粒子扩展参数
                                effectLevel * PARTICLE_ALPHA,             // 粒子透明度（随效果强度变化）
                                PARTICLE_DURATION,                        // 粒子持续时间
                                eng.getContrailColor());                  // 引擎尾迹颜色
                    } catch (NoClassDefFoundError e) {
                        // BoxUtil不可用，忽略异常
                    }
                }
            }
        }

        // ==================== 灼烧伤害（仅在BoxUtil可用且visualSmoke已初始化时） ====================
        if (ENABLE_BURN_DAMAGE && isBoxUtilAvailable() && visualSmoke != null) {
            burnClock += engine.getElapsedInLastFrame() * effectLevel;   // 累积时间，受效果强度影响
            if (burnClock >= DPS_DURATION) {                             // 达到伤害间隔（0.1秒）
                burnClock -= DPS_DURATION;
                float burnDamage = BURN_DPS * DPS_DURATION;              // 每次伤害 = 400 * 0.1 = 40点
                float burnRange = visualSmoke.getBasicWidth() * visualSmoke.getWidthMult(); // 伤害范围等于烟雾宽度

                // 对范围内的敌方舰船造成高爆伤害
                for (ShipAPI target : CombatUtils.getShipsWithinRange(ship.getLocation(), burnRange)) {
                    if (target.getOwner() == ship.getOwner())
                        continue;
                    if (target.isPhased())
                        continue;
                    engine.applyDamage(target, target.getLocation(), burnDamage,
                            DamageType.HIGH_EXPLOSIVE, 0f, false, false, ship, false);
                }
                // 对范围内的敌方导弹造成伤害
                for (MissileAPI target : CombatUtils.getMissilesWithinRange(ship.getLocation(), burnRange)) {
                    if (target.getOwner() == ship.getOwner())
                        continue;
                    engine.applyDamage(target, target.getLocation(), burnDamage,
                            DamageType.HIGH_EXPLOSIVE, 0f, false, false, ship, false);
                }
            }
        }

        // ==================== 抖动效果 ====================
        // 仅在系统激活阶段累加抖动计时器
        if (state == State.ACTIVE)
            jitterClock += engine.getElapsedInLastFrame();
        // 抖动强度：效果强度贡献50%，激活进度贡献50%，再平方（使抖动更强）
        float rate = effectLevel * 0.5f + jitterClock / ship.getSystem().getChargeActiveDur() * 0.5f;
        ship.setJitter(this, JITTER_COLOR, rate * rate, JITTER_COPIES, rate * JITTER_RANGE);

        // ==================== 更新动态光源 ====================
        if (ENABLE_LIGHT && light != null && isBoxUtilAvailable()) {
            // 光源强度从最小值平滑过渡到最大值
            float currentIntensity = LIGHT_INTENSITY_MIN + (LIGHT_INTENSITY_MAX - LIGHT_INTENSITY_MIN) * effectLevel;
            // 光源大小从最小值平滑过渡到最大值
            float currentSize = LIGHT_SIZE_MIN + (LIGHT_SIZE_MAX - LIGHT_SIZE_MIN) * effectLevel;

            // 每帧更新所有光源属性（GraphicsLib需要每帧设置）
            light.setColor(LIGHT_COLOR);
            light.setIntensity(currentIntensity);
            light.setSize(currentSize);
            light.setLocation(ship.getLocation());   // 光源跟随舰船移动
        }

        // 系统结束时播放音效（已禁用，代码被注释）
        // if (state == State.OUT && !SOUND_EXPLODE.isEmpty()) {
        //     Global.getSoundPlayer().playSound(SOUND_EXPLODE, 1f, 1f, ship.getLocation(), ZERO);
        // }
    }

    /**
     * 撤销系统效果（系统关闭或冷却时调用）
     * @param stats 可变舰船属性统计
     * @param id    效果ID
     */
    @Override
    public void unapply(MutableShipStatsAPI stats, String id) {
        // 移除速度、转向、伤害减免的修正
        stats.getMaxSpeed().unmodifyFlat(id);
        stats.getAcceleration().unmodifyFlat(id);
        stats.getDeceleration().unmodifyFlat(id);
        stats.getMaxTurnRate().unmodifyMult(id);
        stats.getArmorDamageTakenMult().unmodifyMult(id);
        stats.getHullDamageTakenMult().unmodifyMult(id);

        ShipAPI ship = (ShipAPI) stats.getEntity();
        if (ship == null) {
            return;
        }

        // 恢复原始质量
        restoreMass(ship);

        // 系统结束：给全武器伤害 / 射速 +25% 的余波增益（持续 POST_BUFF_DURATION 秒）
        startPostBuff(ship);

        // 清除舰船精灵引用
        sprite = null;

        // 清理视觉效果（仅在BoxUtil可用时）
        if (isBoxUtilAvailable()) {
            if (visualOutline != null) {
                visualOutline.setEffectLevel(0f);   // 将效果强度设为0，触发淡出动画
                visualSmoke.setEffectLevel(0f);
            }

            // 移除动态光源
            if (light != null) {
                LightShader.removeLight(light);
                light = null;
            }
        }
    }

    /**
     * 开启期间按 effectLevel 提升舰船质量，满强度时为原始质量的 {@link #MASS_MULT_WHEN_ON} 倍（+100%）。
     * <p>
     * 质量不在 MutableShipStatsAPI 里（那里没有 Mass 统计），只能直接改
     * {@link com.fs.starfarer.api.combat.CombatEntityAPI#setMass(float)}，
     * 所以必须自己把原始值记下来，否则系统结束时还原不回去。
     * 基线写进舰船自定义数据，避免多艘舰共用同一个脚本实例时互相污染。
     *
     * @param ship        舰船
     * @param effectLevel 效果强度（0~1）
     */
    private void applyMassBonus(ShipAPI ship, float effectLevel) {
        if (ship == null) {
            return;
        }
        float base;
        Object stored = ship.getCustomData().get(MASS_BASE_KEY);
        if (stored instanceof Float) {
            base = (Float) stored;
        } else {
            base = ship.getMass();
            ship.setCustomData(MASS_BASE_KEY, base);
        }
        ship.setMass(base * (1f + (MASS_MULT_WHEN_ON - 1f) * effectLevel));
    }

    /**
     * 还原系统开启前的质量，并清掉基线记录。
     *
     * @param ship 舰船
     */
    private void restoreMass(ShipAPI ship) {
        if (ship == null) {
            return;
        }
        Object stored = ship.getCustomData().get(MASS_BASE_KEY);
        if (stored instanceof Float) {
            ship.setMass((Float) stored);
            ship.getCustomData().remove(MASS_BASE_KEY);
        }
    }

    /**
     * 重新开启系统时清掉上一轮的余波增益（监听器下一帧看到没有到期时间就会撤销）。
     *
     * @param ship 舰船
     */
    private void clearPostBuff(ShipAPI ship) {
        if (ship == null) {
            return;
        }
        ship.getCustomData().remove(VIII_YamatoPostBuffListener.UNTIL_KEY);
    }

    /**
     * 系统结束时启动余波增益：全武器伤害 +25%、射速 +25%，持续 {@link #POST_BUFF_DURATION} 秒。
     * 到期时间交给 {@link VIII_YamatoPostBuffListener} 每帧检查并撤销。
     *
     * @param ship 舰船
     */
    private void startPostBuff(ShipAPI ship) {
        if (ship == null || Global.getCombatEngine() == null) {
            return;
        }
        float until = Global.getCombatEngine().getTotalElapsedTime(false) + POST_BUFF_DURATION;
        ship.setCustomData(VIII_YamatoPostBuffListener.UNTIL_KEY, until);
        if (!ship.hasListenerOfClass(VIII_YamatoPostBuffListener.class)) {
            ship.addListener(new VIII_YamatoPostBuffListener(ship));
        }
    }

    /**
     * 检查BoxUtil是否可用
     * 使用缓存避免重复检查
     * @return true如果BoxUtil可用，false如果不可用
     */
    public static boolean isBoxUtilAvailable() {
        if (boxUtilAvailable == null) {
            boxUtilAvailable = Global.getSettings().getModManager().isModEnabled("BoxUtil");
        }
        return boxUtilAvailable;
    }

    /**
     * 获取状态栏数据（用于UI显示）
     * @param index       行索引
     * @param state       系统状态
     * @param effectLevel 效果强度
     * @return StatusData 对象，或 null
     */
    @Override
    public StatusData getStatusData(int index, State state, float effectLevel) {
        if (index == 0)
            return new StatusData("波动引擎启动", false); // 第一行显示“波动引擎启动”
        if (index == 1)
            return new StatusData("舰船质量 +100%", false); // 开启期间质量翻倍
        return null;
    }
}