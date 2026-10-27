package data.scripts.shipsystems;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.impl.combat.NegativeExplosionVisual.NEParams;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.util.vector.Vector2f;
import org.magiclib.util.MagicRender;

// 本次新增：布雷子系统的注册入口依赖这两个类
import data.scripts.shipsystems.subsystems.VIII_MineStrikeSubsystem;
import org.magiclib.subsystems.MagicSubsystemsManager;

import java.awt.*;
import java.util.List;

import static data.scripts.util.VIII_BoxBasedUtil.addSharpFlare_noFlick;

/**
 * 类似相位隐身的战术系统，改变时间流速、速度、加速度，并产生相位线圈压力。
 * 功能：
 * - 系统开启时在每个 SYSTEM 槽位生成 ID 为 "VIII_microarray" 的导弹。
 * - 系统开启时在舰船中心生成裂隙特效（仅渲染在 ABOVE_PARTICLES 层）与持续 1.1 秒的光晕。
 * - 系统开启时额外生成电弧（起点舰船中心，终点碰撞半径*5内随机）。
 * - 系统开启期间根据进度增加透明度，并强制碰撞为 NONE。
 * - 系统开启时生成舰船残影（MagicRender.battlespace），间隔 0.008 秒。
 * - 系统未开启且舰船处于相位状态时，获得最大航速与机动性 +20% 的被动加成。
 * - 系统激活时获得大额属性加成，退出后移除。
 * - 特殊判断：
 *     · 舰船 ID 为 "VIII_firebird_b" 时，使用不同的光晕颜色和残影颜色；
 *     · 舰船 ID 为 "VIII_micrastur_b" 时，使用专属光晕颜色；
 *     · 舰船 ID 为 "VIII_micrastur_a" 时，使用专属残影颜色；
 *     · 舰船 ID 为 "VIII_micrastur_a" 或 "VIII_micrastur_b" 时，
 *       光晕的长度与宽度放大 1.5 倍，电弧数量放大 3 倍。
 * - 本次新增：舰船 ID 为 "VIII_micrastur_a" 或 "VIII_micrastur_b" 时，
 *   自动（被动）挂载 MagicLib 布雷子系统 VIII_MineStrikeSubsystem（左侧 Alt 触发）。
 */
public class VIII_DivingActuator extends BaseShipSystemScript {

    // ==================== 舰船 ID 常量 ====================
    // 把这些字符串集中成常量，避免在多处方法里重复写错（原先是散落的字面量）

    /** 火鸟 B 型的船体 ID */
    private static final String HULL_ID_FIREBIRD_B = "VIII_firebird_b";
    /** 林隼 A 型的船体 ID */
    private static final String HULL_ID_MICRASTUR_A = "VIII_micrastur_a";
    /** 林隼 B 型的船体 ID */
    private static final String HULL_ID_MICRASTUR_B = "VIII_micrastur_b";

    /**
     * 用于标记"本舰已经挂载布雷子系统"的舰船自定义数据键。
     * 舰船对象随战斗创建与销毁，所以不会跨战斗残留。
     */
    private static final String MINESTRIKE_ADDED_KEY = "VIII_DivingActuator_mineStrikeAdded";

    // ==================== 颜色常量 ====================

    // 默认光晕颜色（不属于任何特判舰船时使用）
    private static final Color DEFAULT_HIT_COLOR = new Color(253, 7, 109, 255);
    // 默认残影颜色
    private static final Color DEFAULT_AFTERIMAGE_COLOR = new Color(76, 7, 253, 255);

    // 火鸟 B 型的光晕颜色（紫）
    private static final Color FIREBIRD_B_HIT_COLOR = new Color(107, 0, 161, 255);
    // 火鸟 B 型的残影颜色（品红）
    private static final Color FIREBIRD_B_AFTERIMAGE_COLOR = new Color(253, 7, 146, 255);


    // ==================== 表现强度常量 ====================

    /** 林隼系列舰船的光晕尺寸倍率（长度与宽度同时生效） */
    private static final float MICRASTUR_FLARE_SIZE_MULT = 1.5F;
    /** 林隼系列舰船的电弧数量倍率 */
    private static final float MICRASTUR_ARC_COUNT_MULT = 3F;
    /** 普通舰船每次生成的电弧数量 */
    private static final int BASE_ARC_COUNT = 40;

    private static final String ACTIVE_ID = "VIII_DivingActuator_active";
    private static final String PASSIVE_ID = "VIII_DivingActuator_passive_phase";

    public static float SHIP_ALPHA_MULT = 0.25F;
    public static float VULNERABLE_FRACTION = 0.0F;
    public static float MAX_TIME_MULT = 10F;
    public static boolean FLUX_LEVEL_AFFECTS_SPEED = true;
    public static float MIN_SPEED_MULT = 1F;
    public static float BASE_FLUX_LEVEL_FOR_MIN_SPEED = 1F;

    protected Object STATUSKEY1 = new Object();
    protected Object STATUSKEY2 = new Object();
    protected Object STATUSKEY3 = new Object();

    float effectLevelLast = 0.0F;
    private boolean missilesGenerated = false;
    private boolean arcsGenerated = false;   // 电弧生成标志

    private BaseEveryFrameCombatPlugin alphaCollisionPlugin = null;
    private float currentEffectLevel = 0f;

    // 用于光晕生成的每帧插件
    private BaseEveryFrameCombatPlugin flarePlugin = null;

    // 残影生成计时器
    private float renderTimer = 0f;

    public VIII_DivingActuator() {
    }

    // ==================== 核心工具方法 ====================

    public static float getMaxTimeMult(MutableShipStatsAPI stats) {
        return 1.0F + (MAX_TIME_MULT - 1.0F) * stats.getDynamic().getValue("phase_time_mult");
    }

    /**
     * 安全地读取舰船的船体 ID。
     * getHullSpec() 在极少数情况下可能为 null（例如舰船尚未完成初始化），此处统一做保护，
     * 所有 ID 判断都走这个方法，避免在每个分支里重复判空。
     *
     * @param ship 目标舰船，可为 null
     * @return 船体 ID；无法取得时返回空字符串（永不返回 null）
     */
    private static String getHullIdSafe(ShipAPI ship) {
        if (ship == null || ship.getHullSpec() == null) {
            return "";
        }
        String hullId = ship.getHullSpec().getHullId();
        return (hullId == null) ? "" : hullId;
    }

    /**
     * 判断该舰船是否属于「林隼」系列（VIII_micrastur_a / VIII_micrastur_b）。
     * 这两型舰船共用同一套强化表现：光晕放大 1.5 倍、电弧数量 ×3，
     * 并且都会被动挂载布雷子系统。
     *
     * @param ship 目标舰船
     * @return true 表示需要应用放大效果
     */
    private static boolean isMicrasturShip(ShipAPI ship) {
        String hullId = getHullIdSafe(ship);
        return HULL_ID_MICRASTUR_A.equals(hullId) || HULL_ID_MICRASTUR_B.equals(hullId);
    }

    /**
     * 本次新增：为「林隼」系列被动挂载 MagicLib 布雷子系统。
     * <p>
     * 特点：
     * - 只在舰船 ID 为 VIII_micrastur_a / VIII_micrastur_b 时挂载；
     * - 幂等：用舰船自定义数据做标记，同一艘船只会挂载一次（本方法每帧被调用是安全的）；
     * - 捕获异常并写日志：即使挂载失败也不会打断系统脚本的其它逻辑。
     *
     * @param ship 本舰
     */
    private static void ensureMineStrikeSubsystem(ShipAPI ship) {
        if (ship == null) {
            return;
        }
        // 只有这两型舰船会获得该子系统
        if (!isMicrasturShip(ship)) {
            return;
        }
        // 已经挂载过 → 直接跳过
        // 注意：CombatEntityAPI 只提供无参的 getCustomData()（返回 Map）用于读取，
        // 以及 setCustomData(String, Object) 用于写入；且官方文档明确不要对返回的 Map 调用 put()。
        if (ship.getCustomData().containsKey(MINESTRIKE_ADDED_KEY)) {
            return;
        }

        // 先打标记再挂载：万一流中途抛异常，也不会每帧反复重试造成刷屏
        ship.setCustomData(MINESTRIKE_ADDED_KEY, Boolean.TRUE);

        try {
            // MagicSubsystemsManager 是 Kotlin 的 object，用 INSTANCE 调用在任何版本都能编译
            MagicSubsystemsManager.addSubsystemToShip(ship, new VIII_MineStrikeSubsystem(ship));
        } catch (Throwable t) {
            Global.getLogger(VIII_DivingActuator.class).error("挂载布雷子系统失败", t);
        }
    }

    /** 根据舰船 ID 返回对应的光晕颜色 */
    private Color getHitColorForShip(ShipAPI ship) {
        String hullId = getHullIdSafe(ship);

        // 火鸟 B 型：紫罗兰色光晕
        if (HULL_ID_FIREBIRD_B.equals(hullId)) {
            return FIREBIRD_B_HIT_COLOR;
        }
        // 林隼 B 型：青蓝色光晕
        if (HULL_ID_MICRASTUR_B.equals(hullId)) {
            return FIREBIRD_B_HIT_COLOR;
        }
        // 其余舰船：默认光晕颜色
        return DEFAULT_HIT_COLOR;
    }

    /** 根据舰船 ID 返回对应的残影颜色 */
    private Color getAfterimageColorForShip(ShipAPI ship) {
        String hullId = getHullIdSafe(ship);

        // 火鸟 B 型：品红色残影
        if (HULL_ID_FIREBIRD_B.equals(hullId)) {
            return FIREBIRD_B_AFTERIMAGE_COLOR;
        }
        // 其余舰船：默认残影颜色
        return DEFAULT_AFTERIMAGE_COLOR;
    }

    protected float getDisruptionLevel(ShipAPI ship) {
        if (FLUX_LEVEL_AFFECTS_SPEED) {
            float threshold = ship.getMutableStats().getDynamic()
                    .getMod("phase_cloak_flux_level_for_min_speed_mod")
                    .computeEffective(BASE_FLUX_LEVEL_FOR_MIN_SPEED);
            if (threshold <= 0.0F) {
                return 1.0F;
            } else {
                float level = ship.getHardFluxLevel() / threshold;
                if (level > 1.0F) {
                    level = 1.0F;
                }
                return level;
            }
        } else {
            return 0.0F;
        }
    }

    protected void maintainStatus(ShipAPI playerShip, State state, float effectLevel) {
        float f = VULNERABLE_FRACTION;
        ShipSystemAPI cloak = playerShip.getPhaseCloak();
        if (cloak == null) {
            cloak = playerShip.getSystem();
        }
        if (cloak != null) {
            if (effectLevel > f) {
                Global.getCombatEngine().maintainStatusForPlayerShip(
                        this.STATUSKEY1,
                        cloak.getSpecAPI().getIconSpriteName(),
                        cloak.getDisplayName(),
                        "时间流速改变",
                        false
                );
            }
            if (effectLevel > f) {
                Global.getCombatEngine().maintainStatusForPlayerShip(
                        this.STATUSKEY2,
                        cloak.getSpecAPI().getIconSpriteName(),
                        cloak.getDisplayName(),
                        "最大航速与机动性大幅度增加",
                        false
                );
            }
            if (effectLevel > f) {
                Global.getCombatEngine().maintainStatusForPlayerShip(
                        this.STATUSKEY3,
                        cloak.getSpecAPI().getIconSpriteName(),
                        cloak.getDisplayName(),
                        "武器冷却与备弹恢复大幅度增加",
                        false
                );
            }
        }
    }

    public float getSpeedMult(ShipAPI ship, float effectLevel) {
        return this.getDisruptionLevel(ship) <= 0.0F
                ? 1.0F
                : MIN_SPEED_MULT + (1.0F - MIN_SPEED_MULT) * (1.0F - this.getDisruptionLevel(ship) * effectLevel);
    }

    // ==================== 主应用逻辑 ====================

    @Override
    public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
        ShipAPI ship = (ShipAPI) stats.getEntity();
        boolean player;
        if (stats.getEntity() instanceof ShipAPI) {
            ship = (ShipAPI) stats.getEntity();
            player = ship == Global.getCombatEngine().getPlayerShip();
            id = "VIII_DivingActuator";

            // ========== 本次新增：被动挂载布雷子系统（仅林隼 A / B 型） ==========
            // 幂等调用，每帧执行都安全；挂载后由 MagicLib 接管其按键、冷却与 HUD
            ensureMineStrikeSubsystem(ship);

            if (player) {
                this.maintainStatus(ship, state, effectLevel);
            }

            // ========== 系统开启时生成导弹 ==========
            if (!Global.getCombatEngine().isPaused()) {
                if (effectLevel > 0 && !missilesGenerated) {
                    generateMissiles(ship);
                    missilesGenerated = true;
                } else if (effectLevel <= 0 && missilesGenerated) {
                    missilesGenerated = false;
                }
            }


            // ========== 电弧生成（系统开启时生成一次，数量按舰船 ID 决定） ==========
            if (!Global.getCombatEngine().isPaused()) {
                if (effectLevel > 0 && !arcsGenerated) {
                    generateArcs(ship);
                    arcsGenerated = true;
                } else if (effectLevel <= 0 && arcsGenerated) {
                    arcsGenerated = false;
                }
            }

            // ========== 光晕生成 ==========
            if (!Global.getCombatEngine().isPaused()) {
                boolean active = (state != State.COOLDOWN && state != State.IDLE);
                if (active) {
                    if (flarePlugin == null) {
                        createFlarePlugin(ship);
                    }
                } else {
                    cleanupFlarePlugin();
                }
            }

            if (!Global.getCombatEngine().isPaused()) {
                boolean active = (state != State.COOLDOWN && state != State.IDLE);

                if (active) {
                    // ========== 激活状态：应用大额加成 ==========
                    stats.getAcceleration().modifyMult(ACTIVE_ID, 5F);
                    stats.getDeceleration().modifyMult(ACTIVE_ID, 5F);
                    stats.getTurnAcceleration().modifyMult(ACTIVE_ID, 5F);
                    stats.getMissileRoFMult().modifyMult(ACTIVE_ID, 3F);
                    stats.getBallisticRoFMult().modifyMult(ACTIVE_ID, 3F);
                    stats.getEnergyRoFMult().modifyMult(ACTIVE_ID, 3F);
                    stats.getMissileAmmoRegenMult().modifyMult(ACTIVE_ID, 3F);
                    stats.getEnergyAmmoRegenMult().modifyMult(ACTIVE_ID, 3F);
                    stats.getBallisticAmmoRegenMult().modifyMult(ACTIVE_ID, 3F);

                    removePassivePhaseBonus(stats);

                    ShipSystemAPI cloak = ship.getPhaseCloak();
                    if (cloak == null) {
                        cloak = ship.getSystem();
                    }
                    if (cloak != null) {
                        if (state == State.OUT) {
                            stats.getMaxSpeed().unmodify(id);
                        } else {
                            stats.getMaxSpeed().modifyMult(id, 2.5F);
                            stats.getAcceleration().modifyFlat(id, 1200.0F);
                        }
                        if (FLUX_LEVEL_AFFECTS_SPEED
                                && (state == State.ACTIVE || state == State.OUT || state == State.IN)) {
                            float mult = this.getSpeedMult(ship, effectLevel);
                            if (mult < 1.0F) {
                                stats.getMaxSpeed().modifyMult(id + "_2", mult);
                            } else {
                                stats.getMaxSpeed().unmodifyMult(id + "_2");
                            }
                            ((PhaseCloakSystemAPI) cloak).setMinCoilJitterLevel(this.getDisruptionLevel(ship));
                        }
                        currentEffectLevel = effectLevel;
                        if (alphaCollisionPlugin == null) {
                            createAlphaCollisionPlugin(ship);
                        }
                        float extra = 0.0F;
                        float shipTimeMult = 1.0F + (getMaxTimeMult(stats) - 1.0F) * effectLevel * (1.0F - extra);
                        stats.getTimeMult().modifyMult(id, shipTimeMult);
                        if (player) {
                            Global.getCombatEngine().getTimeMult().modifyMult(id, 1.0F / shipTimeMult);
                        } else {
                            Global.getCombatEngine().getTimeMult().unmodify(id);
                        }
                        this.effectLevelLast = effectLevel;
                    }
                } else {
                    // ========== 非激活状态：移除激活加成，处理被动相位加成 ==========
                    removeActiveBonus(stats);
                    cleanupAlphaCollisionPlugin(ship);

                    if (ship.isPhased()) {
                        applyPassivePhaseBonus(stats);
                    } else {
                        removePassivePhaseBonus(stats);
                    }

                    stats.getMaxSpeed().unmodify(id);
                    stats.getMaxSpeed().unmodifyMult(id + "_2");
                    stats.getAcceleration().unmodify(id);
                    stats.getDeceleration().unmodify(id);
                    stats.getTimeMult().unmodify(id);
                    Global.getCombatEngine().getTimeMult().unmodify(id);

                    this.effectLevelLast = 0.0F;
                }
            }
        }

        // 使用 MagicRender 绘制舰船精灵的"残影"
        if (ship != null && !Global.getCombatEngine().isPaused()
                && state != State.COOLDOWN && state != State.IDLE) {
            renderTimer += Global.getCombatEngine().getElapsedInLastFrame();
            if (renderTimer >= 0.008f) {
                renderTimer -= 0.008f;

                Color afterimageColor = getAfterimageColorForShip(ship);

                MagicRender.battlespace(
                        Global.getSettings().getSprite(ship.getHullSpec().getSpriteName()),
                        new Vector2f(ship.getLocation().getX(), ship.getLocation().getY()),
                        new Vector2f(0, 0),
                        new Vector2f(ship.getSpriteAPI().getWidth(), ship.getSpriteAPI().getHeight()),
                        new Vector2f(0, 0),
                        ship.getFacing() - 90f,
                        0f,
                        afterimageColor,
                        false,
                        0f,
                        0f,
                        0f,
                        0f,
                        0.1f,
                        0.15f,
                        0.1f,
                        0.15f,
                        CombatEngineLayers.BELOW_SHIPS_LAYER
                );
            }
        } else {
            renderTimer = 0f;
        }
    }

    /**
     * 生成电弧：起点为舰船中心，终点为碰撞半径 *5 范围内的随机点。
     * <p>
     * 数量规则：
     * - 普通舰船：BASE_ARC_COUNT（40）道；
     * - VIII_micrastur_a / VIII_micrastur_b：数量 ×3，即 120 道。
     */
    private void generateArcs(ShipAPI ship) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) return;
        Vector2f center = ship.getLocation();
        float radius = ship.getCollisionRadius() * 5f;
        Color fringe = getHitColorForShip(ship);
        Color core = Color.WHITE;
        float thickness = 15f;

        // 按舰船 ID 决定电弧数量
        // 林隼 A / B 型的电弧数量为基准值的 3 倍，其余舰船保持基准值
        int arcCount = BASE_ARC_COUNT;
        if (isMicrasturShip(ship)) {
            arcCount = (int) (BASE_ARC_COUNT * MICRASTUR_ARC_COUNT_MULT);
        }

        for (int i = 0; i < arcCount; i++) {
            Vector2f end = Misc.getPointWithinRadius(center, radius);
            EmpArcEntityAPI.EmpArcParams params = new EmpArcEntityAPI.EmpArcParams();
            params.segmentLengthMult = 8f;
            params.zigZagReductionFactor = 0.15f;
            params.fadeOutDist = 500f;
            params.minFadeOutMult = 2f;
            params.flickerRateMult = 0.5f;
            params.movementDurOverride = 0.33f;



            EmpArcEntityAPI mainArc = engine.spawnEmpArcVisual(
                    end,
                    null,
                    center,
                    null,
                    thickness,
                    fringe,
                    core,
                    params
            );
            mainArc.setSingleFlickerMode(true);
            mainArc.setRenderGlowAtStart(false);
            mainArc.setRenderGlowAtEnd(true);
            mainArc.setUpdateFromOffsetEveryFrame(false);
        }
    }

    /**
     * 创建光晕生成插件。
     */
    private void createFlarePlugin(final ShipAPI ship) {
        if (flarePlugin != null) return;
        flarePlugin = new BaseEveryFrameCombatPlugin() {
            private float elapsed = 0f;
            private float nextFlareTime = 0f;
            private final float LIFECYCLE = 1.1f;
            private final float DURATION = 2f;

            @Override
            public void advance(float amount, List<InputEventAPI> events) {
                if (Global.getCombatEngine().isPaused()) return;
                if (!ship.isAlive()) {
                    cleanupFlarePlugin();
                    return;
                }

                elapsed += amount;
                if (elapsed > DURATION) {
                    cleanupFlarePlugin();
                    return;
                }

                if (elapsed >= nextFlareTime) {
                    nextFlareTime = elapsed + LIFECYCLE;
                    spawnFlares(ship);
                }
            }
        };
        Global.getCombatEngine().addPlugin(flarePlugin);
    }

    private void cleanupFlarePlugin() {
        if (flarePlugin != null) {
            Global.getCombatEngine().removePlugin(flarePlugin);
            flarePlugin = null;
        }
    }

    /**
     * 生成四个固定大小的光晕。
     * <p>
     * 尺寸规则：
     * - 普通舰船：使用基准尺寸；
     * - VIII_micrastur_a / VIII_micrastur_b：长度与宽度统一 ×1.5。
     */
    private void spawnFlares(ShipAPI ship) {
        Vector2f loc = new Vector2f(ship.getLocation());
        Color core = Color.WHITE;
        Color fringe = getHitColorForShip(ship);

        float width = 2000f;
        float width2 = 1000f;
        float width3 = 500f;
        float height = 100f;
        float height2 = 150f;
        float height3 = 80f;
        float in = 0.1f;
        float full = 0f;
        float out = 1.0f;
        float glowPower = 0.4f;
        float noisePower = 0.1f;

        // 按舰船 ID 放大光晕的长与宽
        // 林隼 A / B 型的光晕长度与宽度统一乘以 1.5，其余舰船倍率为 1.0（保持原样）
        float sizeMult = 1.0f;
        if (isMicrasturShip(ship)) {
            sizeMult = MICRASTUR_FLARE_SIZE_MULT;
        }

        width = width * sizeMult;
        width2 = width2 * sizeMult;
        width3 = width3 * sizeMult;
        height = height * sizeMult;
        height2 = height2 * sizeMult;
        height3 = height3 * sizeMult;

        addSharpFlare_noFlick(loc, 0f, width, height, core, fringe, in, full, out, glowPower, noisePower);
        addSharpFlare_noFlick(loc, 90f, width2, height2, core, fringe, in, full, out, glowPower, noisePower);
        addSharpFlare_noFlick(loc, 45f, width3, height3, core, fringe, in, full, out, glowPower, noisePower);
        addSharpFlare_noFlick(loc, -45f, width3, height3, core, fringe, in, full, out, glowPower, noisePower);
    }


    private void applyPassivePhaseBonus(MutableShipStatsAPI stats) {
        stats.getMaxSpeed().modifyMult(PASSIVE_ID, 1.2F);
        stats.getAcceleration().modifyMult(PASSIVE_ID, 1.2F);
        stats.getDeceleration().modifyMult(PASSIVE_ID, 1.2F);
        stats.getTurnAcceleration().modifyMult(PASSIVE_ID, 1.2F);
        stats.getMaxTurnRate().modifyMult(PASSIVE_ID, 1.2F);
    }

    private void removePassivePhaseBonus(MutableShipStatsAPI stats) {
        stats.getMaxSpeed().unmodifyMult(PASSIVE_ID);
        stats.getAcceleration().unmodifyMult(PASSIVE_ID);
        stats.getDeceleration().unmodifyMult(PASSIVE_ID);
        stats.getTurnAcceleration().unmodifyMult(PASSIVE_ID);
        stats.getMaxTurnRate().unmodifyMult(PASSIVE_ID);
    }

    private void removeActiveBonus(MutableShipStatsAPI stats) {
        stats.getAcceleration().unmodifyMult(ACTIVE_ID);
        stats.getDeceleration().unmodifyMult(ACTIVE_ID);
        stats.getTurnAcceleration().unmodifyMult(ACTIVE_ID);
        stats.getMissileRoFMult().unmodifyMult(ACTIVE_ID);
        stats.getBallisticRoFMult().unmodifyMult(ACTIVE_ID);
        stats.getEnergyRoFMult().unmodifyMult(ACTIVE_ID);
        stats.getMissileAmmoRegenMult().unmodifyMult(ACTIVE_ID);
        stats.getEnergyAmmoRegenMult().unmodifyMult(ACTIVE_ID);
        stats.getBallisticAmmoRegenMult().unmodifyMult(ACTIVE_ID);
    }

    private void createAlphaCollisionPlugin(final ShipAPI ship) {
        if (alphaCollisionPlugin != null) return;
        alphaCollisionPlugin = new BaseEveryFrameCombatPlugin() {
            @Override
            public void advance(float amount, List<InputEventAPI> events) {
                if (ship != null && ship.isAlive()) {
                    float alpha = 1.0f - (1.0f - SHIP_ALPHA_MULT) * currentEffectLevel;
                    ship.setExtraAlphaMult(alpha);
                    ship.setApplyExtraAlphaToEngines(true);
                    ship.setCollisionClass(CollisionClass.NONE);
                }
            }
        };
        Global.getCombatEngine().addPlugin(alphaCollisionPlugin);
    }

    private void cleanupAlphaCollisionPlugin(ShipAPI ship) {
        if (alphaCollisionPlugin != null) {
            Global.getCombatEngine().removePlugin(alphaCollisionPlugin);
            alphaCollisionPlugin = null;
        }
        if (ship != null) {
            ship.setExtraAlphaMult(1.0f);
            ship.setApplyExtraAlphaToEngines(false);
            if (!ship.isPhased()) {
                if (ship.isFighter()) {
                    ship.setCollisionClass(CollisionClass.FIGHTER);
                } else {
                    ship.setCollisionClass(CollisionClass.SHIP);
                }
            }
        }
    }

    private void generateMissiles(ShipAPI ship) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) return;

        WeaponAPI fakeWeapon = engine.createFakeWeapon(ship, "VIII_microarray");
        for (WeaponSlotAPI slot : ship.getHullSpec().getAllWeaponSlotsCopy()) {
            if (!slot.isSystemSlot()) continue;
            Vector2f loc = slot.computePosition(ship);
            float angle = slot.getAngle() + ship.getFacing();
            engine.spawnProjectile(ship, fakeWeapon, "VIII_microarray", loc, angle, ship.getVelocity());
        }
    }

    @Override
    public void unapply(MutableShipStatsAPI stats, String id) {
        ShipAPI ship;
        if (stats.getEntity() instanceof ShipAPI) {
            ship = (ShipAPI) stats.getEntity();
            Global.getCombatEngine().getTimeMult().unmodify(id);
            stats.getTimeMult().unmodify(id);
            stats.getMaxSpeed().unmodify(id);
            stats.getMaxSpeed().unmodifyMult(id + "_2");
            stats.getAcceleration().unmodify(id);
            stats.getDeceleration().unmodify(id);
            removeActiveBonus(stats);
            removePassivePhaseBonus(stats);

            cleanupAlphaCollisionPlugin(ship);
            cleanupFlarePlugin();

            ShipSystemAPI cloak = ship.getPhaseCloak();
            if (cloak == null) {
                cloak = ship.getSystem();
            }
            if (cloak != null) {
                ((PhaseCloakSystemAPI) cloak).setMinCoilJitterLevel(0.0F);
            }
        }
        missilesGenerated = false;
        arcsGenerated = false;
        currentEffectLevel = 0f;
        renderTimer = 0f;
    }
}