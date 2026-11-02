package data.scripts.weapons.VIII_XLretreating;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BoundsAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.util.Misc;
import data.scripts.util.VIII_BoxBasedUtil;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.boxutil.manager.CombatRenderingManager;
import org.boxutil.units.standard.entity.FlareEntity;
import org.boxutil.units.standard.entity.TrailEntity;
import org.dark.shaders.distortion.DistortionShader;
import org.dark.shaders.distortion.RippleDistortion;
import org.lwjgl.util.vector.Vector2f;

/**
 * 缩退炮（VIII_XLretreating）弹体的飞行期表现与命中判定。
 * <p>
 * 本插件取代了原先「弹体本身就是黑洞」的做法：
 * <ul>
 *   <li>飞行期<b>不再</b>产生引力、伤害或黑洞，只用若干跟随弹体的光晕
 *       （{@link VIII_BoxBasedUtil#createSharpFlare}，尺寸参考 VIII_DivingActuator 里 500×80 的较小光晕）
 *       加一个全黑小光晕模拟奇点；</li>
 *   <li>因为弹体的 collisionClass 是 NONE，引擎不会替我们判定命中，所以每帧自行检测弹体与
 *       敌舰精确碰撞边界（{@link BoundsAPI} 多边形）或已展开护盾（{@link ShieldAPI}）的交叉；</li>
 *   <li>判定命中后立刻让弹体消失，并在命中点生成一个持续 {@link #HOLE_DURATION} 秒的黑洞：
 *       透镜扭曲由 {@link VIII_SingularityRenderer} 负责，引力/伤害/吸积粒子由本插件负责。</li>
 * </ul>
 */
public class VIII_XLretreatingFlightEffect extends BaseEveryFrameCombatPlugin {

    // ===== 飞行期光晕（参考 VIII_DivingActuator 中 500×80 的小光晕） =====
    /** 光晕内核颜色（近白冷光） */
    private static final Color FLARE_CORE_COLOR = new Color(235, 240, 255, 255);
    /** 光晕外缘颜色（蓝紫，半透明） */
    private static final Color FLARE_FRINGE_COLOR = new Color(70, 105, 255, 170);
    /** 交叉光晕的宽（像素）—— 远大于弹体本身，营造"能量束"观感 */
    private static final float FLARE_WIDTH = 500.0F;
    /** 交叉光晕的高（像素） */
    private static final float FLARE_HEIGHT = 80.0F;
    /** 沿飞行方向那道长条的宽（像素） */
    private static final float STREAK_WIDTH = 780.0F;
    /** 沿飞行方向那道长条的高（像素） */
    private static final float STREAK_HEIGHT = 52.0F;
    /** 交叉光晕相对弹道偏转的角度（度），形成 ±45° 的三向交叉 */
    private static final float FLARE_ANGLE = 45.0F;
    /** 光晕淡入时长（秒） */
    private static final float FLARE_IN = 0.12F;
    /** 光晕满强度持续时长（秒） */
    private static final float FLARE_FULL = 120.0F;
    /** 光晕淡出时长（秒） */
    private static final float FLARE_OUT = 0.35F;
    /** 光晕辉光强度 0~1 */
    private static final float FLARE_GLOW = 0.45F;
    /** 光晕噪点强度（使边缘抖动、不呆板） */
    private static final float FLARE_NOISE = 0.12F;

    /** 全黑小光晕：普通混合（非加法），用来表现事件视界 */
    private static final Color BLACK_CORE_COLOR = new Color(0, 0, 0, 255);
    /** 全黑光晕的外缘色（略带冷蓝，避免纯黑边缘过硬的割裂感） */
    private static final Color BLACK_FRINGE_COLOR = new Color(6, 8, 18, 235);
    /** 全黑光晕的边长（像素） */
    private static final float BLACK_CORE_SIZE = 70.0F;

    // ===== 命中后的黑洞 =====
    /** 黑洞总存在时长（秒） */
    private static final float HOLE_DURATION = 40.0F;
    /** 事件视界的初始半径（像素，渲染器按镜头缩放换算成世界单位） */
    private static final float HOLE_BASE_RADIUS_PX = 55.0F;
    /** 事件视界每秒扩张（像素/秒） */
    private static final float HOLE_GROWTH_PX_PER_SEC = 0.8F;
    /** 吸积粒子生成环的内半径（像素） */
    private static final float HOLE_PARTICLE_MIN_RADIUS = 200.0F;
    /** 吸积粒子生成环的外半径（像素） */
    private static final float HOLE_PARTICLE_MAX_RADIUS = 1000.0F;
    /** 每帧生成的吸积粒子数量（60FPS 下约 120 个/秒） */
    private static final int HOLE_PARTICLES_PER_FRAME = 2;

    // ===== 向内汇聚的凉光线（旧版 ACCRETION_TRAIL 参数） =====
    /** 光线自带的发光内核色（近白） */
    private static final Color TRAIL_CORE_COLOR = new Color(255, 255, 255, 255);
    /** 光线靠近外端（生成点）的颜色，较淡 */
    private static final Color TRAIL_START_COLOR = new Color(150, 150, 255, 110);
    /** 光线靠近内端（中心）的颜色，较亮 */
    private static final Color TRAIL_END_COLOR = new Color(160, 160, 255, 200);
    /** 外端线宽（像素） */
    private static final float TRAIL_WIDTH_START = 2.0F;
    /** 内端线宽（像素） */
    private static final float TRAIL_WIDTH_END = 6.0F;
    /** 单根光线存活时长（秒），也是内端推进到中心的耗时 */
    private static final float TRAIL_DURATION = 0.6F;
    /** 光线淡出时长（秒） */
    private static final float TRAIL_OUT_TIME = 0.3F;
    /** 光线生成间隔（秒） */
    private static final float TRAIL_SPAWN_INTERVAL = 0.06F;
    /** 每次生成的最少根数 */
    private static final int TRAIL_MIN_PER_SPAWN = 1;
    /** 每次生成的最多根数 */
    private static final int TRAIL_MAX_PER_SPAWN = 3;
    /** 生成距离 = 事件视界半径 × 该倍数（下限） */
    private static final float TRAIL_DISTANCE_MIN_MULT = 2.0F;
    /** 生成距离 = 事件视界半径 × 该倍数（上限） */
    private static final float TRAIL_DISTANCE_MAX_MULT = 12F;
    /** 生成距离的绝对上限（像素），防止视界异常放大时把光线撒到全屏 */
    private static final float TRAIL_MAX_DISTANCE = 10000.0F;

    // ===== 命中闪光 =====
    /** 命中与消散瞬间的白色闪光颜色 */
    private static final Color FLASH_COLOR = new Color(255, 240, 245, 255);

    // ===== 黑洞光环：外圈深红、内圈白色的不闪烁椭圆光晕，随黑洞扩张 =====
    /** 光环内核色（近白微粉） */
    private static final Color HALO_CORE_COLOR = new Color(255, 246, 248, 255);
    /** 光环外缘色（深红），配合加法混合形成"红边白心" */
    private static final Color HALO_FRINGE_COLOR = new Color(216, 0, 11, 235);
    /** 光晕宽度 = 黑洞直径 × 该倍率 */
    private static final float HALO_SIZE_MULT = 2.5F;
    /** 椭圆的高 / 宽，0.1 表示极扁的横向椭圆 */
    private static final float HALO_ASPECT = 0.1F;
    /** 光环辉光强度 0~1 */
    private static final float HALO_GLOW_POWER = 0.55F;
    /** 光环整体淡出时长（秒） */
    private static final float HALO_FADE_OUT = 0.05F;
    /** 光环"满强度"时长（秒）：设极大表示永不自然淡出，由代码手动控制 alpha */
    private static final float HALO_FULL_TIME = 600.0F;
    /** 光晕尺寸每秒向目标靠拢的速率（避免尺寸突变） */
    private static final float HALO_GROWTH_SMOOTH = 6.0F;
    /** 事件视界世界半径的合理上限：渲染器首帧还没抓到屏幕尺寸时会换算出天文数字 */
    private static final float MAX_EVENT_HORIZON_RADIUS_WORLD = 1500.0F;
    /** 光晕尺寸（宽）上限，防止异常值造成巨大的红色闪光 */
    private static final float MAX_HALO_DIAMETER = 3000.0F;
    /** 光晕尺寸下限：渲染器刚建立时像素半径还很小，避免光晕先缩成一点再长大 */
    private static final float MIN_HALO_DIAMETER = 120.0F;

    // ===== 旧版（VIII_GravityCannonChargeGlow）的引力/伤害参数，数值与公式保持一致 =====
    /** 旧版 DAMAGE_INTERVAL：引力与伤害都是每 0.1 秒结算一次 */
    private static final float TICK = 0.1F;
    /** 引力作用范围的基础值（像素） */
    private static final float GRAVITY_RANGE_BASE = 1350.0F;
    /** 作用范围随 sqrt(质量) 增长的系数 */
    private static final float GRAVITY_RANGE_MASS_MULT = 0.8F;
    /** 对舰船施加的引力基础强度 */
    private static final float SHIP_GRAVITY_BASE_STRENGTH = 700.0F;
    /** 引力强度随 sqrt(质量) 增长的系数 */
    private static final float SHIP_GRAVITY_MASS_MULT = 1.1F;
    /** 残骸被吸的额外倍率 */
    private static final float SHIP_HULK_GRAVITY_MULT = 1.5F;
    /** 质量衰减起点：低于此质量不受衰减 */
    private static final float SHIP_MASS_DECAY_MIN = 2000.0F;
    /** 质量衰减终点：达到此质量则完全拉不动（用于豁免空间站等巨型目标） */
    private static final float SHIP_MASS_DECAY_MAX = 15000.0F;
    /** 事件视界内的穿盾 EMP 基础 DPS */
    private static final float EVENT_HORIZON_BASE_EMP_DPS = 1000.0F;
    /** 事件视界 EMP 随 sqrt(质量) 放大的系数 */
    private static final float EVENT_HORIZON_MASS_SCALE = 2.5F;
    /** 侵入深度换算成伤害倍率的下限 */
    private static final float EVENT_HORIZON_PENETRATION_MIN = 0.4F;
    /** 侵入深度换算成伤害倍率的上限 */
    private static final float EVENT_HORIZON_PENETRATION_MAX = 0.6F;
    /** 战机/残骸被"吞掉"所需的侵入深度阈值 */
    private static final float FIGHTER_HULK_REMOVE_PENETRATION = 0.8F;
    /** 被吞掉的目标按最大结构值的该比例转化为黑洞质量 */
    private static final float ABSORBED_MASS_RATIO = 0.25F;
    /** 视界外观测到的潮汐 EMP 基础 DPS */
    private static final float TIDAL_BASE_EMP_DPS = 400.0F;
    /** 潮汐 EMP 在最近处的峰值 DPS */
    private static final float TIDAL_MAX_EMP_DPS = 750.0F;
    /** 潮汐伤害的质量倍率基数 */
    private static final float TIDAL_MASS_MULT = 1.0F;
    /** 潮汐伤害随 sqrt(质量) 放大的系数 */
    private static final float TIDAL_MASS_SCALE = 1.0F;
    /** 潮汐伤害的整体缩放（旧版残留系数） */
    private static final float TIDAL_SIZE_MULT = 0.3F;

    // ===== 投射物引力（旧版 PROJECTILE_GRAVITY_* 参数） =====
    /** 对导弹类弹体的引力强度（拽得很猛） */
    private static final float PROJECTILE_GRAVITY_MISSILE = 3000.0F;
    /** 对普通子弹的引力强度（只有很弱的偏转） */
    private static final float PROJECTILE_GRAVITY_NORMAL = 10.0F;
    /** 投射物引力随 sqrt(质量) 放大的系数 */
    private static final float PROJECTILE_GRAVITY_MASS_MULT = 1.7F;
    /** 切向阻尼开始生效的距离比例阈值 */
    private static final float PROJECTILE_TANGENT_DAMPING_THRESHOLD = 0.2F;
    /** 切向阻尼最大削减比例 */
    private static final float PROJECTILE_TANGENT_DAMPING_MAX = 0.6F;

    /** 本插件的目标弹体 */
    private final MissileAPI missile;
    /** 战场引擎 */
    private final CombatEngineAPI engine;
    /** 视觉随机源 */
    private final Random random = new Random();

    /** 飞行期的四道光晕 */
    private final List<FlareEntity> flightFlares = new ArrayList<FlareEntity>();
    /** 飞行期的黑色核心（事件视界本体） */
    private FlareEntity blackCore;

    /** 是否已进入黑洞期 */
    private boolean holeActive;
    /** 黑洞在渲染器里的唯一标识（普通对象，仅作 key 用） */
    private Object holeToken;
    /** 黑洞中心的世界坐标 */
    private Vector2f holeCenter;
    /** 黑洞透镜扭曲渲染器（全局单例） */
    private VIII_SingularityRenderer holeRenderer;
    /** 黑洞的来源舰船（用于伤害归属） */
    private ShipAPI holeSource;
    /** 黑洞归属阵营（用于区分敌我） */
    private int holeOwner;
    /** 旧版用质量累积放大引力与伤害：初始为 0，吞噬与伤害会累加 */
    private float holeMass;
    /** 黑洞已存在时长（秒） */
    private float holeTime;
    /** 下一次 0.1 秒结算的倒计时 */
    private float tickTimer;
    /** 下一根吸积光线的生成倒计时 */
    private float trailTimer;
    /** 当前存活的吸积光线 */
    private final List<AccretionTrail> accretionTrails = new ArrayList<AccretionTrail>();
    /** 黑洞光环（红白扁椭圆） */
    private FlareEntity holeHalo;
    /** 消散计时器：-1 表示尚未进入消散阶段 */
    private float endingTimer = -1.0F;
    /** 光环当前直径（像素），用于平滑逼近 */
    private float haloDiameter = -1.0F;
    /** 是否已从引擎卸载 */
    private boolean removed;

    /**
     * @param missile 要挂钩的弹体
     * @param engine  战场引擎
     */
    public VIII_XLretreatingFlightEffect(MissileAPI missile, CombatEngineAPI engine) {
        this.missile = missile;
        this.engine = engine;
    }

    /**
     * 每帧入口。判定顺序为：消散阶段 → 黑洞阶段 → 飞行阶段。
     */
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine == null || removed || amount <= 0.0F) {
            return;
        }
        if (engine.isPaused()) {
            return;
        }
        // ① 消散阶段：只做光环整体淡出，不再结算引力与伤害
        if (endingTimer >= 0.0F) {
            endingTimer += amount;
            if (holeHalo != null) {
                float alpha = 1.0F - endingTimer / HALO_FADE_OUT;
                if (alpha < 0.0F) {
                    alpha = 0.0F;
                }
                holeHalo.setGlobalAlpha(alpha);
            }
            if (endingTimer >= HALO_FADE_OUT) {
                discardHoleHalo();
                removeSelf();
            }
            return;
        }
        // ② 黑洞阶段 / ③ 飞行阶段
        if (holeActive) {
            advanceSingularity(amount);
        } else {
            advanceFlight(amount);
        }
    }

    // =====================================================================
    // 飞行期
    // =====================================================================

    /**
     * 飞行期：弹体是否还在场、光晕跟随、每帧命中判定。
     */
    private void advanceFlight(float amount) {
        if (missile == null || missile.isExpired() || !engine.isEntityInPlay(missile)) {
            // 弹体消散（到达射程上限、被打掉等）：只清理光晕，不生成黑洞
            discardFlightFlares();
            removeSelf();
            return;
        }
        updateFlightFlares();
        ShipAPI target = findHitShip();
        if (target != null) {
            spawnSingularity(target);
        }
    }

    /**
     * 让所有飞行期光晕跟随弹体位置。
     */
    private void updateFlightFlares() {
        Vector2f location = missile.getLocation();
        if (flightFlares.isEmpty()) {
            createFlightFlares(location);
        }
        // 光晕朝向在创建时就固定（0° 与 ±45°），这里只跟着弹体移动，不随飞行方向转动
        for (int i = 0; i < flightFlares.size(); i++) {
            flightFlares.get(i).setLocation(location);
        }
        if (blackCore != null) {
            blackCore.setLocation(location);
        }
    }

    /**
     * 创建飞行期光晕：三向交叉 + 一道长条 + 黑色核心。
     */
    private void createFlightFlares(Vector2f location) {
        // 三道交叉光晕：沿飞行方向的一道长条 + 左右 ±45° 的两道（照 DivingActuator 的小光晕）
        addFlightFlare(location, 0.0F, STREAK_WIDTH, STREAK_HEIGHT);
        addFlightFlare(location, FLARE_ANGLE, FLARE_WIDTH, FLARE_HEIGHT);
        addFlightFlare(location, 90F, FLARE_WIDTH, FLARE_HEIGHT);
        addFlightFlare(location, -FLARE_ANGLE, FLARE_WIDTH, FLARE_HEIGHT);
        // 全黑小光晕，模拟奇点本体（普通混合才能"遮挡"后面的光）
        blackCore = createBlackCore(location);
        CombatRenderingManager.addEntity(blackCore);
    }

    /**
     * 创建单道光晕并加入渲染管理。
     */
    private void addFlightFlare(Vector2f location, float angle, float width, float height) {
        FlareEntity flare = VIII_BoxBasedUtil.createSharpFlare(location, angle, width, height,
                FLARE_CORE_COLOR, FLARE_FRINGE_COLOR, FLARE_IN, FLARE_FULL, FLARE_OUT,
                FLARE_GLOW, FLARE_NOISE);
        flightFlares.add(flare);
        CombatRenderingManager.addEntity(flare);
    }

    /**
     * 创建"事件视界"黑色圆盘：普通混合 + 正圆 + 不闪烁。
     */
    private FlareEntity createBlackCore(Vector2f location) {
        FlareEntity core = new FlareEntity();
        core.setLocation(location);
        core.setSize(BLACK_CORE_SIZE, BLACK_CORE_SIZE);
        core.setFacingScale(0.0F, 1.0F, 1.0F);
        core.setCoreColor(BLACK_CORE_COLOR);
        core.setFringeColor(BLACK_FRINGE_COLOR);
        core.setNormalBlend();
        core.setSmoothDisc();
        // aspect 与 discRatio 都要置 1 才是正圆：autoAspect() 会把 aspect 缩成 0.25，变成椭圆
        core.setAspect(1.0F);
        core.setDiscRatio(2.0F);
        core.setNoisePower(0.0F);
        core.setGlowPower(0.0F);
        core.setFlick(false);
        core.setGlobalTimer(FLARE_IN, FLARE_FULL, FLARE_OUT);
        core.setLayer(CombatEngineLayers.ABOVE_PARTICLES_LOWER);
        return core;
    }

    /**
     * 清空飞行期光晕。
     */
    private void discardFlightFlares() {
        for (int i = 0; i < flightFlares.size(); i++) {
            CombatRenderingManager.removeEntity(flightFlares.get(i));
        }
        flightFlares.clear();
        if (blackCore != null) {
            CombatRenderingManager.removeEntity(blackCore);
            blackCore = null;
        }
    }

    // =====================================================================
    // 命中判定：精确碰撞边界 / 已展开护盾
    // =====================================================================

    /**
     * 找出与弹体相交的第一艘敌方舰船。
     * 因为弹体 collisionClass 为 NONE，引擎不会做这件事，只能自己遍历。
     */
    private ShipAPI findHitShip() {
        Vector2f position = missile.getLocation();
        float radius = Math.max(4.0F, missile.getCollisionRadius());
        ShipAPI source = missile.getSource();
        List<ShipAPI> ships = engine.getShips();
        for (int i = 0; i < ships.size(); i++) {
            ShipAPI ship = ships.get(i);
            if (ship == null || ship == source) {
                continue;
            }
            if (ship.getOwner() == missile.getOwner()) {
                continue;
            }
            if (!ship.isAlive() || ship.isHulk() || ship.getCollisionRadius() <= 0.0F) {
                continue;
            }
            // 粗筛：远处直接跳过，避免每帧遍历多边形
            float rough = Misc.getDistance(position, ship.getLocation());
            if (rough > ship.getCollisionRadius() + radius + 200.0F) {
                continue;
            }
            if (intersectsShield(ship, position, radius)) {
                return ship;
            }
            if (intersectsHull(ship, position, radius)) {
                return ship;
            }
        }
        return null;
    }

    /** 弹体是否与已展开的护盾球/护盾弧相交 */
    private boolean intersectsShield(ShipAPI ship, Vector2f position, float radius) {
        ShieldAPI shield = ship.getShield();
        if (shield == null || !shield.isOn()) {
            return false;
        }
        Vector2f center = shield.getLocation();
        if (center == null) {
            return false;
        }
        float distance = Misc.getDistance(position, center);
        if (distance > shield.getRadius() + radius) {
            return false;
        }
        // 已经进入护盾球内部，或者落在护盾弧的正面，都算命中护盾
        return distance <= shield.getRadius() || shield.isWithinArc(position);
    }

    /** 弹体是否与舰船的精确碰撞多边形（或退化时的碰撞圆）相交 */
    private boolean intersectsHull(ShipAPI ship, Vector2f position, float radius) {
        BoundsAPI bounds = ship.getExactBounds();
        if (bounds != null) {
            List<BoundsAPI.SegmentAPI> segments = bounds.getSegments();
            if (segments != null && !segments.isEmpty()) {
                for (int i = 0; i < segments.size(); i++) {
                    BoundsAPI.SegmentAPI segment = segments.get(i);
                    if (segment == null) {
                        continue;
                    }
                    float distance = distanceToSegment(position, segment.getP1(), segment.getP2());
                    if (distance <= radius) {
                        return true;
                    }
                }
                // 弹体已经穿进多边形内部（比如从破损处进入）也算命中
                if (ship.isPointInBounds(position)) {
                    return true;
                }
                return false;
            }
        }
        // 退化情况：没有可用多边形时按碰撞圆判定
        return Misc.getDistance(position, ship.getLocation()) <= ship.getCollisionRadius() + radius;
    }

    /**
     * 点到线段的最短距离（含端点钳制与零长线段保护）。
     */
    private static float distanceToSegment(Vector2f point, Vector2f start, Vector2f end) {
        if (start == null || end == null) {
            return Float.MAX_VALUE;
        }
        float dx = end.x - start.x;
        float dy = end.y - start.y;
        float lengthSquared = dx * dx + dy * dy;
        if (lengthSquared <= 0.0001F) {
            return Misc.getDistance(point, start);
        }
        float t = ((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared;
        if (t < 0.0F) {
            t = 0.0F;
        } else if (t > 1.0F) {
            t = 1.0F;
        }
        float nearestX = start.x + dx * t;
        float nearestY = start.y + dy * t;
        float offsetX = point.x - nearestX;
        float offsetY = point.y - nearestY;
        return (float) Math.sqrt((double) (offsetX * offsetX + offsetY * offsetY));
    }

    // =====================================================================
    // 命中：结算伤害、播放命中特效、生成黑洞
    // =====================================================================

    /**
     * 命中结算：先对命中目标打一次弹体本体伤害，再移除弹体，最后在原地开黑洞。
     */
    private void spawnSingularity(ShipAPI target) {
        Vector2f point = new Vector2f(missile.getLocation());
        ShipAPI source = missile.getSource();
        // ① 弹体本体伤害（因为引擎不会替我们结算，这里手动补一次）
        if (target != null && target.isAlive() && !target.isHulk()) {
            float damage = Math.max(1.0F, missile.getDamageAmount());
            float emp = Math.max(0.0F, missile.getEmpAmount());
            DamageType type = missile.getDamageType();
            if (type == null) {
                type = DamageType.ENERGY;
            }
            // 参数顺序：目标、坐标、实弹伤害、伤害类型、EMP、软辐能、产生软辐能、来源、是否穿盾
            engine.applyDamage(target, point, damage, type, emp, false, false,
                    source == null ? missile : source, false);
        }

        // ② 命中特效（闪光 + 透镜波纹 + 红色粒子）
        spawnImpactFx(point);

        // ③ 移除弹体与飞行期光晕
        if (engine.isEntityInPlay(missile)) {
            engine.removeEntity(missile);
        }
        discardFlightFlares();

        // ④ 初始化黑洞状态
        holeActive = true;
        holeToken = new Object();
        holeCenter = point;
        holeSource = source;
        holeOwner = missile.getOwner();
        holeMass = 0.0F;
        holeTime = 0.0F;
        tickTimer = TICK;

        // ⑤ 在透镜渲染器里登记并配置扩张速率
        holeRenderer = VIII_SingularityRenderer.getOrCreate();
        if (holeRenderer != null) {
            holeRenderer.trackAt(holeToken, holeCenter);
            holeRenderer.configurePixelRadius(holeToken, HOLE_BASE_RADIUS_PX, HOLE_GROWTH_PX_PER_SEC);
        }

        // ⑥ 建立光环
        endingTimer = -1.0F;
        haloDiameter = -1.0F;
        discardHoleHalo();
        holeHalo = createHoleHalo(holeCenter);
        updateHoleHalo(1.0F);
    }

    /** 黑洞光环：内圈白色、外圈深红色的椭圆光晕，不闪烁（不设置 flick 与 noise） */
    private FlareEntity createHoleHalo(Vector2f location) {
        FlareEntity halo = new FlareEntity();
        halo.setLocation(new Vector2f(location));
        halo.setSize(1.0F, 1.0F);
        halo.setFacingScale(0.0F, 1.0F, 1.0F);
        halo.setCoreColor(HALO_CORE_COLOR);
        halo.setFringeColor(HALO_FRINGE_COLOR);
        halo.setAdditiveBlend();
        halo.setSmoothDisc();
        halo.setAspect(1.0F);
        halo.setDiscRatio(2.0F);
        halo.setGlowPower(HALO_GLOW_POWER);
        halo.setNoisePower(0.0F);
        halo.setFlick(false);
        // fadeIn 0、full 600 秒、fadeOut 0：永不自然淡出，alpha 由代码手动控制
        halo.setGlobalTimer(0.0F, HALO_FULL_TIME, 0.0F);
        halo.setLayer(CombatEngineLayers.ABOVE_PARTICLES_LOWER);
        CombatRenderingManager.addEntity(halo);
        return halo;
    }

    /**
     * 光晕尺寸随黑洞直径同步：宽 = 直径 × HALO_SIZE_MULT，高 = 宽 × HALO_ASPECT。
     * 直径先做上下限截断再平滑，避免渲染器首帧的异常值让光晕一度拉到很大。
     */
    private void updateHoleHalo(float amount) {
        if (holeHalo == null || holeCenter == null) {
            return;
        }
        float target = eventHorizonRadiusWorld() * 2.0F * HALO_SIZE_MULT;
        if (Float.isNaN(target) || Float.isInfinite(target) || target <= 0.0F) {
            return;
        }
        if (target > MAX_HALO_DIAMETER) {
            target = MAX_HALO_DIAMETER;
        }
        if (target < MIN_HALO_DIAMETER) {
            target = MIN_HALO_DIAMETER;
        }
        // 首帧直接赋目标值，之后指数平滑逼近，避免尺寸跳变
        if (haloDiameter <= 0.0F) {
            haloDiameter = target;
        } else {
            haloDiameter += (target - haloDiameter) * Math.min(1.0F, HALO_GROWTH_SMOOTH * amount);
        }
        holeHalo.setLocation(holeCenter);
        holeHalo.setSize(haloDiameter, haloDiameter * HALO_ASPECT);
        holeHalo.setFacingScale(0.0F, 1.0F, 1.0F);
    }

    /**
     * 移除黑洞光环。
     */
    private void discardHoleHalo() {
        if (holeHalo == null) {
            return;
        }
        CombatRenderingManager.removeEntity(holeHalo);
        holeHalo = null;
    }

    /** 命中瞬间：白色闪光 + 透镜波纹 + 红色粒子（环形光晕由 createHoleHalo 提供） */
    private void spawnImpactFx(Vector2f point) {
        engine.spawnExplosion(point, new Vector2f(0.0F, 0.0F), FLASH_COLOR, 150.0F, 0.55F);

        RippleDistortion ripple = new RippleDistortion(new Vector2f(point), new Vector2f(0.0F, 0.0F));
        ripple.setSize(150.0F);
        ripple.setMaxSize(620.0F);
        ripple.setIntensity(0.35F);
        ripple.setMaxIntensity(0.35F);
        ripple.setLifetime(1.1F);
        ripple.fadeOutIntensity(1.1F);
        ripple.fadeOutSize(1.1F);
        DistortionShader.addDistortion(ripple);

        // 64 个向外飞散的红色碎屑（视觉冲击）
        for (int i = 0; i < 64; i++) {
            float angle = (float) (random.nextDouble() * 360.0);
            float speed = 220.0F + (float) random.nextDouble() * 620.0F;
            Vector2f velocity = new Vector2f(
                    (float) Math.cos(Math.toRadians((double) angle)) * speed,
                    (float) Math.sin(Math.toRadians((double) angle)) * speed);
            float size = 5.0F + (float) random.nextDouble() * 13.0F;
            float duration = 0.35F + (float) random.nextDouble() * 0.75F;
            Color color = new Color(255, 60 + random.nextInt(80), 90 + random.nextInt(60), 235);
            engine.addHitParticle(point, velocity, size, 1.0F, duration, color);
        }
    }

    // =====================================================================
    // 黑洞存在期：引力 + 周期伤害 + 吸积粒子
    // =====================================================================

    /**
     * 黑洞期主循环：定长步进结算引力与伤害，再处理粒子与光线。
     */
    private void advanceSingularity(float amount) {
        holeTime += amount;
        updateHoleHalo(amount);
        tickTimer -= amount;
        // 旧版是 0.1 秒结算一次：引力与伤害都按这个节拍走，帧率波动不会改变总强度
        // guard 上限 8 防止一次卡顿后疯狂补帧
        int guard = 0;
        while (tickTimer <= 0.0F && guard < 8) {
            tickTimer += TICK;
            applyGravity();
            applyDamageTick();
            guard++;
        }
        spawnAccretionParticles();
        spawnAccretionTrails(amount);
        updateAccretionTrails(amount);
        if (holeTime >= HOLE_DURATION) {
            endSingularity();
        }
    }

    /** 事件视界当前的世界半径（渲染器按像素半径与镜头缩放换算） */
    private float eventHorizonRadiusWorld() {
        if (holeRenderer == null || holeToken == null) {
            return HOLE_BASE_RADIUS_PX;
        }
        float radius = holeRenderer.getEventHorizonRadiusWorld(holeToken);
        if (Float.isNaN(radius) || Float.isInfinite(radius)) {
            return HOLE_BASE_RADIUS_PX;
        }
        if (radius > 1.0F && radius <= MAX_EVENT_HORIZON_RADIUS_WORLD) {
            return radius;
        }
        // 首帧 screenTexW 还是 0：像素→世界换算会得到几千个单位，归一到基础半径
        return HOLE_BASE_RADIUS_PX;
    }

    /** 质量因子：sqrt(质量 / 10000)，作为引力与伤害的放大基准 */
    private float massFactor() {
        return (float) Math.sqrt((double) (Math.max(0.0F, holeMass) / 10000.0F));
    }

    /** 当前引力作用半径 = 基础值 × (1 + 系数 × sqrt质量) */
    private float gravityRange(float sqrtMass) {
        return GRAVITY_RANGE_BASE * (1.0F + GRAVITY_RANGE_MASS_MULT * sqrtMass);
    }

    /**
     * 对所有舰船与投射物施加一次引力（旧版 0.1 秒节拍）。
     */
    private void applyGravity() {
        float sqrtMass = massFactor();
        float range = gravityRange(sqrtMass);
        float rSoft = eventHorizonRadiusWorld() * 0.6F;
        List<ShipAPI> ships = engine.getShips();
        for (int i = 0; i < ships.size(); i++) {
            ShipAPI ship = ships.get(i);
            if (ship == null || ship == holeSource || ship.isShuttlePod()) {
                continue;
            }
            // 完全免疫伤害的目标（如无敌状态）视为不受引力影响
            if (ship.getMutableStats().getHullDamageTakenMult().getModifiedValue() <= 0.0F) {
                continue;
            }
            float distance = Misc.getDistance(ship.getLocation(), holeCenter);
            if (distance > range || distance <= 1.0F) {
                continue;
            }
            boolean hulk = ship.isHulk();
            // 只吸残骸与敌对舰船；友军不受影响
            boolean pulled = hulk || (ship.isAlive() && ship.getOwner() != holeOwner);
            if (!pulled) {
                continue;
            }
            // 距离越近越强，rSoft 避免中心奇点处数值爆炸
            float ratio = range / (distance + rSoft);
            float change = SHIP_GRAVITY_BASE_STRENGTH * (1.0F + SHIP_GRAVITY_MASS_MULT * sqrtMass)
                    * ratio * ratio * TICK;
            if (hulk) {
                change *= SHIP_HULK_GRAVITY_MULT;
            }
            applyGravitationalForce(ship, change);
        }
        applyProjectileGravity(sqrtMass);
    }

    /** 引力同样作用到弹药上（敌我不分）：导弹被拽得很猛，普通子弹只有很弱的偏转 */
    private void applyProjectileGravity(float sqrtMass) {
        float range = gravityRange(sqrtMass);
        float horizon = eventHorizonRadiusWorld();
        float rSoft = horizon * 0.6F;
        List<DamagingProjectileAPI> projectiles = engine.getProjectiles();
        for (int i = 0; i < projectiles.size(); i++) {
            DamagingProjectileAPI projectile = projectiles.get(i);
            if (projectile == null || projectile.isExpired() || projectile == missile) {
                continue;
            }
            Vector2f velocity = projectile.getVelocity();
            if (velocity == null) {
                continue;
            }
            Vector2f location = projectile.getLocation();
            float distance = Misc.getDistance(location, holeCenter);
            if (distance > range || distance <= 1.0F) {
                continue;
            }
            // 单位化的指向中心向量
            float dx = holeCenter.x - location.x;
            float dy = holeCenter.y - location.y;
            float length = (float) Math.sqrt((double) (dx * dx + dy * dy));
            if (length <= 0.0F) {
                continue;
            }
            dx /= length;
            dy /= length;
            boolean isMissile = projectile instanceof MissileAPI;
            float strength = isMissile ? PROJECTILE_GRAVITY_MISSILE : PROJECTILE_GRAVITY_NORMAL;
            float ratio = range / (distance + rSoft);
            float delta = strength * (1.0F + PROJECTILE_GRAVITY_MASS_MULT * sqrtMass) * ratio * ratio * TICK;
            float vx = velocity.x + dx * delta;
            float vy = velocity.y + dy * delta;

            // 切向阻尼：抑制弹药绕着黑洞转圈，让它们往里掉
            float ramp = (range - distance) / Math.max(0.001F, range - horizon * 0.5F);
            if (ramp < 0.0F) {
                ramp = 0.0F;
            } else if (ramp > 1.0F) {
                ramp = 1.0F;
            }
            ramp *= ramp;
            if (ramp > PROJECTILE_TANGENT_DAMPING_THRESHOLD) {
                // 分解为径向 + 切向，只衰减切向分量
                float radial = vx * dx + vy * dy;
                float tangentX = vx - dx * radial;
                float tangentY = vy - dy * radial;
                float damping = 1.0F - PROJECTILE_TANGENT_DAMPING_MAX * ramp;
                vx = dx * radial + tangentX * damping;
                vy = dy * radial + tangentY * damping;
            }
            velocity.x = vx;
            velocity.y = vy;
        }
    }

    /** 旧版 applyGravitationalForce：按质量衰减与 massScale 缩放，质量越大越难拉动 */
    private void applyGravitationalForce(ShipAPI target, float force) {
        float mass = target.getMass();
        if (mass <= 0.0F) {
            return;
        }
        float massDecay = 1.0F;
        if (mass > SHIP_MASS_DECAY_MIN) {
            if (mass >= SHIP_MASS_DECAY_MAX) {
                // 超重目标（空间站等）完全不动
                return;
            }
            massDecay = 1.0F - (mass - SHIP_MASS_DECAY_MIN) / (SHIP_MASS_DECAY_MAX - SHIP_MASS_DECAY_MIN);
        }
        Vector2f location = target.getLocation();
        float dx = holeCenter.x - location.x;
        float dy = holeCenter.y - location.y;
        float length = (float) Math.sqrt((double) (dx * dx + dy * dy));
        if (length <= 0.0F) {
            return;
        }
        // 轻目标（战机、残骸）更容易被甩动
        float massScale = Math.min(1.0F, 80.0F / mass);
        float pull = force * (1.0F + massScale * 2.0F) * massDecay / (float) Math.sqrt((double) mass);
        Vector2f velocity = target.getVelocity();
        if (velocity == null) {
            return;
        }
        velocity.x += dx / length * pull;
        velocity.y += dy / length * pull;
        // 附加随机角速度，让被吸的目标翻滚起来
        float angularImpulse = ((float) random.nextDouble() * 16.0F - 8.0F)
                * (1.0F + massScale * 3.0F) * massDecay;
        target.setAngularVelocity(target.getAngularVelocity() + angularImpulse);
    }

    /**
     * 每帧生成若干螺旋吸入的吸积粒子。
     */
    private void spawnAccretionParticles() {
        for (int i = 0; i < HOLE_PARTICLES_PER_FRAME; i++) {
            float angle = (float) (random.nextDouble() * 360.0);
            float radius = HOLE_PARTICLE_MIN_RADIUS
                    + (float) random.nextDouble() * (HOLE_PARTICLE_MAX_RADIUS - HOLE_PARTICLE_MIN_RADIUS);
            double radians = Math.toRadians((double) angle);
            float cos = (float) Math.cos(radians);
            float sin = (float) Math.sin(radians);
            Vector2f location = new Vector2f(holeCenter.x + cos * radius, holeCenter.y + sin * radius);
            // 向心速度 + 切向速度 = 螺旋吸入
            float inwardSpeed = radius * 1.35F;
            float tangentSpeed = radius * 0.85F;
            Vector2f velocity = new Vector2f(
                    -cos * inwardSpeed - sin * tangentSpeed,
                    -sin * inwardSpeed + cos * tangentSpeed);
            float size = 3.0F + (float) random.nextDouble() * 5.0F;
            float duration = 0.45F + (float) random.nextDouble() * 0.5F;
            Color color = new Color(255, 70 + random.nextInt(90), 110 + random.nextInt(70), 220);
            engine.addHitParticle(location, velocity, size, 1.0F, duration, color);
        }
    }

    /** 旧版视界/潮汐伤害：视界内穿盾 EMP，战机与残骸被吞掉，界外按潮汐曲线衰减 */
    private void applyDamageTick() {
        float sqrtMass = massFactor();
        float range = gravityRange(sqrtMass);
        float horizon = eventHorizonRadiusWorld();
        // 若来源舰船已不存在，则用 token 作为伤害来源对象
        Object source = holeSource == null ? holeToken : holeSource;
        List<ShipAPI> ships = engine.getShips();
        for (int i = 0; i < ships.size(); i++) {
            ShipAPI ship = ships.get(i);
            if (ship == null || ship == holeSource || ship.isShuttlePod()) {
                continue;
            }
            if (ship.getMutableStats().getHullDamageTakenMult().getModifiedValue() <= 0.0F) {
                continue;
            }
            float distance = Misc.getDistance(ship.getLocation(), holeCenter);
            if (distance > range) {
                continue;
            }
            boolean hulk = ship.isHulk();
            // edge = 目标外缘到黑洞中心的距离，小于视界即视为"沉入视界"
            float edge = distance - ship.getCollisionRadius();
            if (edge <= horizon) {
                float denominator = Math.max(1.0F, ship.getCollisionRadius() * 2.0F);
                float penetration = (horizon - edge) / denominator;
                if (penetration < 0.0F) {
                    penetration = 0.0F;
                } else if (penetration > 1.0F) {
                    penetration = 1.0F;
                }
                // 战机与残骸沉得够深就被"吞掉"，并按比例转化为黑洞质量
                if ((ship.isFighter() || hulk) && penetration >= FIGHTER_HULK_REMOVE_PENETRATION) {
                    holeMass += Math.max(0.0F, ship.getMaxHitpoints()) * ABSORBED_MASS_RATIO;
                    engine.removeEntity(ship);
                    continue;
                }
                // 只有敌方舰船承受视界内伤害；友军免疫
                if (ship.isAlive() && ship.getOwner() != holeOwner) {
                    float factor = EVENT_HORIZON_PENETRATION_MIN
                            + EVENT_HORIZON_PENETRATION_MAX * penetration;
                    float emp = EVENT_HORIZON_BASE_EMP_DPS
                            * (1.0F + EVENT_HORIZON_MASS_SCALE * sqrtMass) * factor * TICK;
                    // 穿盾 EMP（软辐能 + bypassShields）
                    engine.applyDamage(ship, ship.getLocation(), 1.0F, DamageType.ENERGY, emp,
                            true, false, source, true);
                    holeMass += emp * 0.5F;
                }
                continue;
            }
            // 视界之外的潮汐区：伤害按 (1 - 归一化距离)² 曲线衰减
            float span = Math.max(0.001F, range - horizon);
            float outerEdge = distance - ship.getCollisionRadius();
            float radial = 1.0F - (outerEdge - horizon) / span;
            if (radial <= 0.0F) {
                continue;
            }
            if (!ship.isAlive() || ship.getOwner() == holeOwner) {
                continue;
            }
            float curve = radial * radial;
            float massMult = TIDAL_MASS_MULT + 0.4F * sqrtMass;
            float emp = (TIDAL_BASE_EMP_DPS + (TIDAL_MAX_EMP_DPS - TIDAL_BASE_EMP_DPS) * curve)
                    * (massMult * (1.0F + TIDAL_MASS_SCALE * sqrtMass)) * TIDAL_SIZE_MULT * TICK;
            engine.applyDamage(ship, ship.getLocation(), 1.0F, DamageType.ENERGY, emp,
                    true, false, source, true);
            holeMass += emp * 0.1F;
        }
    }

    /** 按固定间隔生成几根从外圈向中心延伸的光线 */
    private void spawnAccretionTrails(float amount) {
        trailTimer -= amount;
        if (trailTimer > 0.0F) {
            return;
        }
        trailTimer += TRAIL_SPAWN_INTERVAL;
        float horizon = eventHorizonRadiusWorld();
        float minDistance = horizon * TRAIL_DISTANCE_MIN_MULT;
        float maxDistance = Math.min(horizon * TRAIL_DISTANCE_MAX_MULT, TRAIL_MAX_DISTANCE);
        if (maxDistance <= minDistance) {
            maxDistance = minDistance + 150.0F;
        }
        int count = TRAIL_MIN_PER_SPAWN
                + random.nextInt(TRAIL_MAX_PER_SPAWN - TRAIL_MIN_PER_SPAWN + 1);
        for (int i = 0; i < count; i++) {
            spawnAccretionTrail(minDistance, maxDistance);
        }
    }

    /**
     * 生成单根内吸光线：外端固定、内端随时间向中心推进。
     */
    private void spawnAccretionTrail(float minDistance, float maxDistance) {
        float distance = minDistance + (float) random.nextDouble() * (maxDistance - minDistance);
        float angle = (float) (random.nextDouble() * 360.0);
        double radians = Math.toRadians((double) angle);
        Vector2f start = new Vector2f(holeCenter.x + (float) Math.cos(radians) * distance,
                holeCenter.y + (float) Math.sin(radians) * distance);
        // 从生成点指向中心的向量，长度等于 distance
        Vector2f direction = new Vector2f(holeCenter.x - start.x, holeCenter.y - start.y);

        TrailEntity trail = new TrailEntity();
        // 下面两个是贴图资源路径，属于引擎查找键，不能翻译成中文
        trail.getMaterialData().setDiffuse(Global.getSettings().getSprite("graphics/fx/beamfringeb.png"));
        trail.getMaterialData().setEmissive(Global.getSettings().getSprite("graphics/fx/beamcoreb.png"));
        trail.getMaterialData().setEmissiveColor(TRAIL_CORE_COLOR);
        trail.getMaterialData().setEmissiveColorAlpha(0.4F);
        trail.setStartColor(TRAIL_START_COLOR);
        trail.setEndColor(TRAIL_END_COLOR);
        trail.setStartWidth(TRAIL_WIDTH_START);
        trail.setEndWidth(TRAIL_WIDTH_END);
        trail.setJitterPower(0.1F);
        trail.setFlick(true);
        trail.setFlickMixValue(0.5F);
        trail.setTexturePixels(256.0F);
        trail.setTextureSpeed(100.0F);
        trail.setFillStartAlpha(0.0F);
        trail.setFillEndAlpha(0.0F);
        trail.setFillStartFactor(0.9F);
        trail.setFillEndFactor(0.9F);
        trail.setLayer(CombatEngineLayers.ABOVE_PARTICLES_LOWER);
        trail.setAdditiveBlend();
        // 两个节点：固定在生成点，另一个随时间向中心延伸（看起来就是一道被吸进去的光）
        trail.addNode(new Vector2f(0.0F, 0.0F));
        trail.addNode(new Vector2f(direction.x * 0.01F, direction.y * 0.01F));
        trail.setLocation(start);
        trail.setStripLineMode(true);
        trail.setNodeRefreshAllFromCurrentIndex();
        trail.submitNodes();
        trail.setGlobalTimer(0.0F, TRAIL_DURATION, TRAIL_OUT_TIME);
        CombatRenderingManager.addEntity(trail);

        accretionTrails.add(new AccretionTrail(trail, TRAIL_DURATION, direction));
    }

    /**
     * 每帧推进每根光线的内端位置，并在末段手动压低颜色 alpha 做淡出。
     */
    private void updateAccretionTrails(float amount) {
        for (int i = accretionTrails.size() - 1; i >= 0; i--) {
            AccretionTrail wrapper = accretionTrails.get(i);
            wrapper.age += amount;
            float progress = wrapper.age / wrapper.maxAge;
            if (progress >= 1.0F || wrapper.trail.hasDelete()) {
                if (!wrapper.trail.hasDelete()) {
                    wrapper.trail.delete();
                }
                accretionTrails.remove(i);
                continue;
            }
            // 后 30% 的寿命里线性淡出
            float alpha = 1.0F;
            if (progress > 0.7F) {
                alpha = (1.0F - progress) / 0.3F;
            }
            List<Vector2f> nodes = wrapper.trail.getNodes();
            if (nodes != null && nodes.size() >= 2) {
                nodes.get(0).set(0.0F, 0.0F);
                nodes.get(1).set(wrapper.fullDirection.x * progress, wrapper.fullDirection.y * progress);
                wrapper.trail.setNodeRefreshIndex(0);
                wrapper.trail.setNodeRefreshSize(2);
                wrapper.trail.submitNodes();
            }
            wrapper.trail.setEndColor(new Color(TRAIL_END_COLOR.getRed(), TRAIL_END_COLOR.getGreen(),
                    TRAIL_END_COLOR.getBlue(), (int) (TRAIL_END_COLOR.getAlpha() * alpha)));
        }
    }

    /**
     * 清空所有吸积光线。
     */
    private void discardAccretionTrails() {
        for (int i = 0; i < accretionTrails.size(); i++) {
            AccretionTrail wrapper = accretionTrails.get(i);
            if (!wrapper.trail.hasDelete()) {
                wrapper.trail.delete();
            }
        }
        accretionTrails.clear();
    }

    /**
     * 黑洞到期：停掉透镜跟踪、播放消散特效、进入淡出阶段。
     */
    private void endSingularity() {
        Vector2f point = new Vector2f(holeCenter);
        VIII_SingularityRenderer renderer = VIII_SingularityRenderer.getOrCreate();
        if (renderer != null && holeToken != null) {
            renderer.stopTracking(holeToken);
        }

        // 光晕先定格在消散前的最终尺寸，再随窗口淡出
        updateHoleHalo(1.0F);
        engine.spawnExplosion(point, new Vector2f(0.0F, 0.0F), FLASH_COLOR, 220.0F, 0.8F);
        RippleDistortion ripple = new RippleDistortion(new Vector2f(point), new Vector2f(0.0F, 0.0F));
        ripple.setSize(220.0F);
        ripple.setMaxSize(900.0F);
        ripple.setIntensity(0.45F);
        ripple.setMaxIntensity(0.45F);
        ripple.setLifetime(1.4F);
        ripple.fadeOutIntensity(1.4F);
        ripple.fadeOutSize(1.4F);
        DistortionShader.addDistortion(ripple);

        discardAccretionTrails();
        holeActive = false;
        holeToken = null;
        holeCenter = null;
        endingTimer = 0.0F;
    }

    /** 工具方法：圆上一点（当前未被调用，属死代码，保留作为备用） */
    private static Vector2f pointOnCircle(Vector2f center, float radius, float angleDegrees) {
        double radians = Math.toRadians((double) angleDegrees);
        return new Vector2f(center.x + (float) Math.cos(radians) * radius,
                center.y + (float) Math.sin(radians) * radius);
    }

    /**
     * 从引擎卸载自身（幂等）。
     */
    private void removeSelf() {
        if (removed) {
            return;
        }
        removed = true;
        discardHoleHalo();
        if (engine != null) {
            engine.removePlugin(this);
        }
    }

    /** 一根向内汇聚的光线 */
    private static class AccretionTrail {
        /** 底层的 BoxUtil 光线实体 */
        private final TrailEntity trail;
        /** 存活时长（秒） */
        private final float maxAge;
        /** 从生成点指向中心的完整向量（长度 = 生成距离） */
        private final Vector2f fullDirection;
        /** 已存活时长（秒） */
        private float age;

        private AccretionTrail(TrailEntity trail, float maxAge, Vector2f fullDirection) {
            this.trail = trail;
            this.maxAge = maxAge;
            this.fullDirection = new Vector2f(fullDirection);
            this.age = 0.0F;
        }
    }
}