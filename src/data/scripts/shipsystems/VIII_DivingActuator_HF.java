package data.scripts.shipsystems;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.util.vector.Vector2f;
import org.magiclib.util.MagicRender;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

import static data.scripts.util.VIII_BoxBasedUtil.addSharpFlare_noFlick;

/**
 * 类似相位隐身的战术系统，改变时间流速、速度、加速度，并产生相位线圈压力。
 * 功能：
 * - 系统开启时，每 0.5 秒在每个 SYSTEM 槽位生成一枚 ID 为 "VIII_Fragmentation_MIRV" 的导弹，
 *   每次激活最多生成 3 轮（即首轮立即生成，0.5 秒后第二轮，1.0 秒后第三轮）。
 * - 系统开启时在舰船中心生成裂隙特效（仅渲染在 ABOVE_PARTICLES 层）与持续 1.1 秒的光晕。
 * - 系统开启时额外生成 75 道电弧（起点舰船中心，终点碰撞半径*5内随机）。
 * - 系统开启期间根据进度增加透明度，并强制碰撞为 NONE。
 * - 系统开启时生成舰船残影（MagicRender.battlespace），间隔 0.008 秒。
 * - 系统开启期间，除 ID 为 "VIII_Missile_HFA" 和 "VIII_Missile_HFB" 的武器外，其余武器一律无法开火，
 *   并将这两把武器的弹药设置为 2。
 * - 【新增】系统激活期间（以及结束后的一小段滞留时间内），持续扫描战场，凡是 ID 为
 *   "VIII_Fragmentation_warhead2" 的射弹，一律移除，并在原位置、原朝向生成一架 ID 为
 *   "VIII_attack_swarm_wing" 的战机联队（替换逻辑参照 VIII_Fragmentation_MIRVonFireEffect）。
 * - 系统未开启且舰船处于相位状态时，获得最大航速与机动性 +50% 的被动加成。
 * - 系统激活时获得大额属性加成，退出后移除。
 * - 特殊判断：若舰船 ID 为 "VIII_banshee_b"，则使用不同的光晕颜色和残影颜色。
 */
public class VIII_DivingActuator_HF extends BaseShipSystemScript {

    // 默认光晕颜色（非 VIII_banshee_b）
    private static final Color DEFAULT_HIT_COLOR = new Color(253, 7, 109, 255);
    // 默认残影颜色
    private static final Color DEFAULT_AFTERIMAGE_COLOR = new Color(76, 7, 253, 255);

    // 激活状态属性修正的 ID
    private static final String ACTIVE_ID = "VIII_DivingActuator_HF";
    // 被动相位状态属性修正的 ID
    private static final String PASSIVE_ID = "VIII_DivingActuator_passive_HF";

    // ===== 系统开启期间允许开火的武器 ID 白名单 =====
    private static final String[] ALLOWED_WEAPON_IDS = {"VIII_Missile_HFA", "VIII_Missile_HFB"};
    // 系统开启时给白名单武器设置的弹药数量
    private static final int ALLOWED_WEAPON_AMMO = 2;

    // 锁定非白名单武器时，强制保持的"剩余冷却"时长（秒）
    // 每帧刷新，使武器永远处于未就绪状态，从而无法开火
    private static final float WEAPON_LOCK_COOLDOWN = 0.2f;

    // ===== 系统开启期间的导弹生成参数 =====
    // 每一轮导弹之间的间隔（秒）
    private static final float MISSILE_INTERVAL = 0.5f;
    // 每次激活最多生成的轮数
    private static final int MAX_MISSILE_BURSTS = 3;

    // ===== 射弹替换（弹头 → 战机联队）参数 =====
    // 需要被替换掉的射弹 ID（对应 projectiles.csv 中的弹丸规格 ID）
    private static final String TARGET_PROJECTILE_ID = "VIII_Fragmentation_warhead2";
    // 替换后生成的战机联队 ID（对应 fighter_wings.csv 中的联队 ID）
    private static final String SWAP_WING_ID = "VIII_attack_swarm_wing";
    // 是否只替换本方阵营的射弹（避免把敌方舰船发射的同类弹头也一起替换掉）
    private static final boolean SWAP_ONLY_FRIENDLY_PROJECTILES = true;
    // 替换出的战机是否叠加母舰速度
    // （参照 VIII_Fragmentation_MIRVonFireEffect 的做法：射弹速度 + 母舰速度）
    private static final boolean SWAP_ADD_SHIP_VELOCITY = true;
    // 系统关闭后，替换插件继续工作的滞留时长（秒）
    // 因为导弹从发射到分裂成弹头存在飞行时间，系统关闭后往往仍有弹头在空中
    // 设为 0 表示系统一关闭就立刻停止扫描
    private static final float WARHEAD_SWAP_LINGER = 15f;

    // ===== 可配置的常量 =====
    public static float SHIP_ALPHA_MULT = 0.25F;   // 舰船最小透明度倍率
    public static float VULNERABLE_FRACTION = 0.0F; // 脆弱比例阈值（未使用）
    public static float MAX_TIME_MULT = 10F;       // 最大时间流速倍率
    public static boolean FLUX_LEVEL_AFFECTS_SPEED = true; // 幅能是否影响速度
    public static float MIN_SPEED_MULT = 1F;        // 最低速度倍率（此处为1，即无惩罚）
    public static float BASE_FLUX_LEVEL_FOR_MIN_SPEED = 1F; // 触发最低速度的硬幅能阈值

    // 状态栏显示的键
    protected Object STATUSKEY1 = new Object();
    protected Object STATUSKEY2 = new Object();
    protected Object STATUSKEY3 = new Object();

    // 上一帧的效果等级（用于追踪状态变化）
    float effectLevelLast = 0.0F;

    // 导弹生成计时器（累计本次激活中已流逝的时间）
    // 初始值等于一个间隔，使激活后的首帧就能立即生成第一轮导弹
    private float missileTimer = MISSILE_INTERVAL;
    // 本次激活中已生成导弹的轮数
    private int missilesFired = 0;
    // 电弧是否已生成（每激活周期只生成一次）
    private boolean arcsGenerated = false;
    // 白名单武器弹药是否已在本次激活中设置过
    private boolean ammoSet = false;

    // 处理透明度和碰撞的每帧插件
    private BaseEveryFrameCombatPlugin alphaCollisionPlugin = null;
    // 当前效果等级（供插件使用）
    private float currentEffectLevel = 0f;

    // 光晕生成的每帧插件
    private BaseEveryFrameCombatPlugin flarePlugin = null;

    // 武器开火限制的每帧插件
    private BaseEveryFrameCombatPlugin weaponLockPlugin = null;

    // ===== 新增：弹头替换相关状态 =====
    // 弹头替换的每帧插件
    private BaseEveryFrameCombatPlugin warheadSwapPlugin = null;
    // 系统当前是否处于激活状态（供替换插件判断何时开始倒计滞留时间）
    private boolean systemActive = false;
    // 滞留倒计时剩余时间（秒），系统关闭后开始递减
    private float warheadSwapLinger = 0f;

    // 残影生成计时器
    private float renderTimer = 0f;

    public VIII_DivingActuator_HF() {
    }

    // ==================== 核心工具方法 ====================

    /**
     * 获取最大时间流速倍率。
     * 基于动态值 "phase_time_mult" 在 1.0 和 MAX_TIME_MULT 之间插值。
     */
    public static float getMaxTimeMult(MutableShipStatsAPI stats) {
        return 1.0F + (MAX_TIME_MULT - 1.0F) * stats.getDynamic().getValue("phase_time_mult");
    }

    /** 根据舰船 ID 返回对应的光晕颜色。若为 VIII_banshee_b 则返回紫色。 */
    private Color getHitColorForShip(ShipAPI ship) {
        if (ship != null && "VIII_banshee_b".equals(ship.getHullSpec().getHullId())) {
            return new Color(107, 0, 161, 255);
        }
        return DEFAULT_HIT_COLOR;
    }

    /** 根据舰船 ID 返回对应的残影颜色。若为 VIII_banshee_b 则返回品红色。 */
    private Color getAfterimageColorForShip(ShipAPI ship) {
        if (ship != null && "VIII_banshee_b".equals(ship.getHullSpec().getHullId())) {
            return new Color(253, 7, 146, 255);
        }
        return DEFAULT_AFTERIMAGE_COLOR;
    }

    /**
     * 计算当前的“干扰等级”（0~1），表示硬幅能对速度的压制程度。
     * 若 FLUX_LEVEL_AFFECTS_SPEED 为 false，则始终返回 0。
     */
    protected float getDisruptionLevel(ShipAPI ship) {
        if (FLUX_LEVEL_AFFECTS_SPEED) {
            // 获取动态修正后的阈值
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

    /**
     * 维护状态栏显示的信息。
     * 若相位系统存在且效果等级足够，则显示时间流速改变、机动性提升、武器冷却恢复等提示。
     */
    protected void maintainStatus(ShipAPI playerShip, float effectLevel) {
        float f = VULNERABLE_FRACTION;
        ShipSystemAPI cloak = playerShip.getPhaseCloak();
        if (cloak == null) {
            cloak = playerShip.getSystem();
        }
        if (cloak != null) {
            if (effectLevel > f) {
                // 状态1：时间流速改变
                Global.getCombatEngine().maintainStatusForPlayerShip(
                        this.STATUSKEY1,
                        cloak.getSpecAPI().getIconSpriteName(),
                        cloak.getDisplayName(),
                        "时间流速改变",
                        false
                );
            }
            if (effectLevel > f) {
                // 状态2：机动性提升
                Global.getCombatEngine().maintainStatusForPlayerShip(
                        this.STATUSKEY2,
                        cloak.getSpecAPI().getIconSpriteName(),
                        cloak.getDisplayName(),
                        "最大航速与机动性大幅度增加",
                        false
                );
            }
            if (effectLevel > f) {
                // 状态3：武器恢复加速
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

    /**
     * 根据干扰等级计算速度倍率。
     * 若无干扰返回 1.0，否则在 MIN_SPEED_MULT 和 1.0 之间插值。
     */
    public float getSpeedMult(ShipAPI ship, float effectLevel) {
        return this.getDisruptionLevel(ship) <= 0.0F
                ? 1.0F
                : MIN_SPEED_MULT + (1.0F - MIN_SPEED_MULT)
                                   * (1.0F - this.getDisruptionLevel(ship) * effectLevel);
    }

    /**
     * 判断某个武器是否属于“允许开火”的白名单。
     * 通过 weapon.getSpec().getWeaponId() 获取 weapons.csv 中的武器 ID 进行比对，
     * 同时兼容 weapon.getId()。
     */
    private boolean isAllowedWeapon(WeaponAPI weapon) {
        if (weapon == null) return false;

        // 获取武器的规格 ID（weapons.csv 中的 id）
        String specId = null;
        WeaponSpecAPI spec = weapon.getSpec();
        if (spec != null) {
            specId = spec.getWeaponId();
        }
        // 获取武器的实例 ID
        String instanceId = weapon.getId();

        for (String allowedId : ALLOWED_WEAPON_IDS) {
            if (allowedId.equals(specId) || allowedId.equals(instanceId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 锁定白名单以外的所有武器：把它们的剩余冷却时间强制维持在一个较小值以上。
     * 由于冷却时间每帧都被刷新为"未就绪"，武器永远无法进入可开火状态，
     * 因此不依赖插件与武器判定的执行顺序，效果稳定。
     */
    private void lockOtherWeapons(ShipAPI ship) {
        if (ship == null) return;
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon == null) continue;
            // 白名单武器不受限制
            if (isAllowedWeapon(weapon)) continue;

            // 核心手段：抬高剩余冷却，使武器始终处于未就绪状态
            // 仅在当前冷却更短时抬高，避免"缩短"武器本身正在走的冷却
            float remaining = weapon.getCooldownRemaining();
            if (remaining < WEAPON_LOCK_COOLDOWN) {
                weapon.setRemainingCooldownTo(WEAPON_LOCK_COOLDOWN);
            }

            // 辅助手段：同时标记本帧禁止开火（作为额外保险）
            weapon.setForceNoFireOneFrame(true);
        }
    }

    /**
     * 创建武器开火限制插件。
     * 每帧遍历舰船全部武器，将不在白名单中的武器锁定为不可开火状态。
     */
    private void createWeaponLockPlugin(final ShipAPI ship) {
        if (weaponLockPlugin != null) return;
        weaponLockPlugin = new BaseEveryFrameCombatPlugin() {
            @Override
            public void advance(float amount, List<InputEventAPI> events) {
                // 舰船无效时不处理
                if (ship == null || !ship.isAlive()) return;
                lockOtherWeapons(ship);
            }
        };
        Global.getCombatEngine().addPlugin(weaponLockPlugin);
    }

    /** 移除武器开火限制插件。 */
    private void cleanupWeaponLockPlugin() {
        if (weaponLockPlugin != null) {
            Global.getCombatEngine().removePlugin(weaponLockPlugin);
            weaponLockPlugin = null;
        }
    }

    /**
     * 将白名单武器的弹药设置为指定数量。
     * 仅对使用弹药的武器有效；无弹药的武器调用不会有副作用。
     */
    private void setAllowedWeaponAmmo(ShipAPI ship, int ammo) {
        if (ship == null) return;
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon == null) continue;
            if (!isAllowedWeapon(weapon)) continue;
            // 设置弹药数量
            weapon.setAmmo(ammo);
        }
    }

    // ==================== 新增：弹头替换（射弹 → 战机联队） ====================

    /**
     * 判断某个射弹是否是需要被替换掉的目标弹头。
     * 优先比对射弹自身的规格 ID（projectiles.csv 中的 id），
     * 若不一致再兜底比对射弹所属武器的武器 ID（weapons.csv 中的 id），
     * 以兼容"弹丸 ID 直接沿用武器 ID"的写法。
     *
     * @param projectile 待检查的射弹
     * @return true 表示该射弹需要被替换
     */
    private boolean isTargetWarhead(DamagingProjectileAPI projectile) {
        if (projectile == null) return false;

        // 方式一：比对射弹规格 ID（最常用、最直接）
        if (TARGET_PROJECTILE_ID.equals(projectile.getProjectileSpecId())) {
            return true;
        }

        // 方式二：兜底比对武器 ID
        WeaponAPI weapon = projectile.getWeapon();
        if (weapon != null) {
            WeaponSpecAPI spec = weapon.getSpec();
            if (spec != null && TARGET_PROJECTILE_ID.equals(spec.getWeaponId())) {
                return true;
            }
            if (TARGET_PROJECTILE_ID.equals(weapon.getId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 创建弹头替换插件。
     * 该插件每帧扫描战场上属于本方的 "VIII_Fragmentation_warhead2" 射弹，
     * 将其移除并替换为 "VIII_attack_swarm_wing" 战机联队。
     * 系统关闭后插件不会立刻销毁，而是继续工作 WARHEAD_SWAP_LINGER 秒，
     * 以覆盖"系统关闭时仍有弹头在飞行途中"的情况；滞留结束后自行注销。
     */
    private void createWarheadSwapPlugin(final ShipAPI ship) {
        if (warheadSwapPlugin != null) return;

        warheadSwapPlugin = new BaseEveryFrameCombatPlugin() {
            @Override
            public void advance(float amount, List<InputEventAPI> events) {
                CombatEngineAPI engine = Global.getCombatEngine();
                // 极端情况下引擎不可用：直接注销，避免空转
                if (engine == null) {
                    cleanupWarheadSwapPlugin();
                    return;
                }
                // 暂停时不处理，同时不消耗滞留时间
                if (engine.isPaused()) return;
                // 舰船已被摧毁或已不在场：注销插件
                if (ship == null || !ship.isAlive()) {
                    cleanupWarheadSwapPlugin();
                    return;
                }

                // 系统激活期间：持续刷新滞留时间，保证扫描覆盖整段激活期
                if (systemActive) {
                    warheadSwapLinger = WARHEAD_SWAP_LINGER;
                } else {
                    // 系统已关闭：开始倒计滞留时间，归零后注销插件
                    warheadSwapLinger -= amount;
                    if (warheadSwapLinger <= 0f) {
                        cleanupWarheadSwapPlugin();
                        return;
                    }
                }

                // 扫描并替换战场上的目标弹头
                replaceWarheads(ship, engine);
            }
        };
        Global.getCombatEngine().addPlugin(warheadSwapPlugin);
    }

    /** 移除弹头替换插件，并清空滞留计时。 */
    private void cleanupWarheadSwapPlugin() {
        if (warheadSwapPlugin != null) {
            CombatEngineAPI engine = Global.getCombatEngine();
            if (engine != null) {
                engine.removePlugin(warheadSwapPlugin);
            }
            warheadSwapPlugin = null;
        }
        warheadSwapLinger = 0f;
    }

    /**
     * 遍历战场上所有射弹，把符合条件的目标弹头替换为战机联队。
     *
     * 注意：不能一边遍历 getProjectiles() 一边移除/生成实体，
     * 因此先把命中的射弹收集到临时列表，遍历结束后再统一处理。
     *
     * @param ship   系统所属舰船（提供阵营与速度参考）
     * @param engine 战斗引擎
     */
    private void replaceWarheads(ShipAPI ship, CombatEngineAPI engine) {
        // 收集阶段：把命中的射弹挑出来
        List<DamagingProjectileAPI> targets = new ArrayList<>();
        for (DamagingProjectileAPI proj : engine.getProjectiles()) {
            if (proj == null) continue;
            // 正在淡出的射弹（已经结束生命周期）跳过
            if (proj.isFading()) continue;
            // 只处理本方阵营发射的射弹
            if (SWAP_ONLY_FRIENDLY_PROJECTILES && proj.getOwner() != ship.getOwner()) continue;
            // 只处理指定 ID 的弹头
            if (!isTargetWarhead(proj)) continue;
            targets.add(proj);
        }

        // 处理阶段：逐个替换
        for (DamagingProjectileAPI proj : targets) {
            // 二次校验：处理前确认射弹仍在场上
            if (!engine.isEntityInPlay(proj)) continue;
            replaceWarheadWithWing(proj, ship, engine);
        }
    }

    /**
     * 把单个弹头替换为战机联队。
     * 替换逻辑参照 VIII_Fragmentation_MIRVonFireEffect：
     * 读取射弹快照 → 生成战机 → 移除原始射弹。
     *
     * @param projectile 目标弹头
     * @param ship       系统所属舰船
     * @param engine     战斗引擎
     */
    private void replaceWarheadWithWing(DamagingProjectileAPI projectile, ShipAPI ship, CombatEngineAPI engine) {
        // 取出快照数据（射弹马上就会被移除，必须先取好）
        Vector2f loc = new Vector2f(projectile.getLocation());
        float facing = projectile.getFacing();
        int owner = projectile.getOwner();
        Vector2f projectileVelocity = new Vector2f(projectile.getVelocity());
        Vector2f parentVelocity = (ship != null) ? new Vector2f(ship.getVelocity()) : new Vector2f();

        // 1. 在原位置生成战机联队
        spawnWingAt(owner, loc, facing, projectileVelocity, parentVelocity, engine);

        // 2. 移除原始射弹实体
        if (engine.isEntityInPlay(projectile)) {
            engine.removeEntity(projectile);
        }
    }

    /**
     * 在指定位置生成战机联队，并赋予"射弹速度 + 母舰速度"的初速。
     * 生成过程中临时屏蔽部署提示消息，避免刷屏。
     *
     * @param owner            阵营（射弹所有者）
     * @param loc              生成位置
     * @param facing           生成朝向（度）
     * @param projectileVelocity 原射弹速度
     * @param parentVelocity   母舰速度
     * @param engine           战斗引擎
     */
    private void spawnWingAt(int owner, Vector2f loc, float facing,
                             Vector2f projectileVelocity, Vector2f parentVelocity,
                             CombatEngineAPI engine) {
        // 获取对应阵营的舰队管理器
        CombatFleetManagerAPI fleetManager = engine.getFleetManager(owner);
        if (fleetManager == null) return;

        // 保存当前部署消息状态
        boolean wasSuppressed = fleetManager.isSuppressDeploymentMessages();
        // 禁止"XX 已部署"之类的提示消息
        fleetManager.setSuppressDeploymentMessages(true);

        try {
            // 在射弹位置生成指定的战机联队
            ShipAPI wing = fleetManager.spawnShipOrWing(SWAP_WING_ID, loc, facing);
            if (wing == null) return;

            // 设置碰撞类型为战斗机
            wing.setCollisionClass(CollisionClass.FIGHTER);

            // 计算初始速度（射弹速度 [+ 母舰速度]）
            Vector2f vel = new Vector2f(projectileVelocity);
            if (SWAP_ADD_SHIP_VELOCITY && parentVelocity != null) {
                Vector2f.add(vel, parentVelocity, vel);
            }
            wing.getVelocity().set(vel);
        } finally {
            // 恢复原始部署消息状态
            fleetManager.setSuppressDeploymentMessages(wasSuppressed);
        }
    }

    // ==================== 主应用逻辑 ====================

    /**
     * 每帧调用，根据系统状态和效果等级修改舰船属性、生成特效。
     *
     * @param stats       舰船可变属性
     * @param id          系统实例 ID（会被覆写为固定值）
     * @param state       当前系统状态
     * @param effectLevel 效果等级（0~1）
     */
    @Override
    public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
        ShipAPI ship = (ShipAPI) stats.getEntity();
        boolean player;
        if (stats.getEntity() instanceof ShipAPI) {
            ship = (ShipAPI) stats.getEntity();
            player = ship == Global.getCombatEngine().getPlayerShip();
            id = "VIII_DivingActuator"; // 强制使用固定 ID

            if (player) {
                this.maintainStatus(ship, effectLevel);
            }

            // ========== 系统开启时生成导弹（每 0.5 秒一轮，每次激活最多 3 轮） ==========
            if (!Global.getCombatEngine().isPaused()) {
                if (effectLevel > 0) {
                    // 累计本次激活已持续的时间
                    missileTimer += Global.getCombatEngine().getElapsedInLastFrame();

                    // 未达轮数上限且计时已到一个间隔，则生成一轮导弹
                    if (missilesFired < MAX_MISSILE_BURSTS && missileTimer >= MISSILE_INTERVAL) {
                        generateMissiles(ship);
                        missilesFired++;
                        // 减去一个间隔而非清零，避免长期累积误差
                        missileTimer -= MISSILE_INTERVAL;
                        if (missileTimer < 0f) {
                            missileTimer = 0f;
                        }
                    }
                } else {
                    // 系统关闭：重置计时与轮数，下一次激活从第一轮重新开始
                    // 计时器预置为一个间隔，使下一次激活的首帧立刻生成导弹
                    missileTimer = MISSILE_INTERVAL;
                    missilesFired = 0;
                }
            }

            // ========== 电弧生成（系统开启时生成 75 道） ==========
            if (!Global.getCombatEngine().isPaused()) {
                if (effectLevel > 0 && !arcsGenerated) {
                    generateArcs(ship);
                    arcsGenerated = true;
                } else if (effectLevel <= 0 && arcsGenerated) {
                    arcsGenerated = false;
                }
            }

            // ========== 光晕生成插件管理 ==========
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
                    // 机动性、射速、弹药恢复大幅提升
                    stats.getAcceleration().modifyMult(ACTIVE_ID, 20F);
                    stats.getDeceleration().modifyMult(ACTIVE_ID, 20F);
                    stats.getTurnAcceleration().modifyMult(ACTIVE_ID, 50F);
                    stats.getMaxTurnRate().modifyMult(ACTIVE_ID, 5F);
                    stats.getMissileRoFMult().modifyMult(ACTIVE_ID, 3F);
                    stats.getBallisticRoFMult().modifyMult(ACTIVE_ID, 3F);
                    stats.getEnergyRoFMult().modifyMult(ACTIVE_ID, 3F);
                    stats.getMissileAmmoRegenMult().modifyMult(ACTIVE_ID, 3F);
                    stats.getEnergyAmmoRegenMult().modifyMult(ACTIVE_ID, 3F);
                    stats.getBallisticAmmoRegenMult().modifyMult(ACTIVE_ID, 3F);

                    // ========== 武器开火限制与弹药设置 ==========
                    // 每帧锁定：除白名单外的武器均处于不可开火状态
                    if (weaponLockPlugin == null) {
                        createWeaponLockPlugin(ship);
                    }
                    // 立即锁定一次，避免激活瞬间留出开火窗口
                    lockOtherWeapons(ship);
                    // 每次激活仅设置一次弹药，避免每帧覆盖导致弹药永不消耗
                    if (!ammoSet) {
                        setAllowedWeaponAmmo(ship, ALLOWED_WEAPON_AMMO);
                        ammoSet = true;
                    }

                    // ========== 弹头替换插件管理（新增） ==========
                    // 标记系统处于激活状态，并（首次激活时）创建替换插件
                    systemActive = true;
                    if (warheadSwapPlugin == null) {
                        createWarheadSwapPlugin(ship);
                    }

                    // 先移除被动相位加成，避免叠加
                    removePassivePhaseBonus(stats);

                    ShipSystemAPI cloak = ship.getPhaseCloak();
                    if (cloak == null) {
                        cloak = ship.getSystem();
                    }
                    if (cloak != null) {
                        if (state == State.OUT) {
                            stats.getMaxSpeed().unmodify(id);
                        } else {
                            // 最大速度乘以5，加速度平坦增加1200
                            stats.getMaxSpeed().modifyMult(id, 5.0F);
                            stats.getAcceleration().modifyFlat(id, 1200.0F);
                        }
                        // 幅能影响速度
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
                        // 创建透明度和碰撞处理插件
                        if (alphaCollisionPlugin == null) {
                            createAlphaCollisionPlugin(ship);
                        }
                        // 时间流速修改：自身加速，玩家则全局减速
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
                    // 系统关闭后解除武器开火限制，并允许下次激活重新设置弹药
                    cleanupWeaponLockPlugin();
                    ammoSet = false;

                    // 标记系统已结束：弹头替换插件会在滞留计时结束后自行注销
                    // （不在此处直接移除，以确保系统关闭瞬间仍在飞行途中的弹头也能被替换）
                    systemActive = false;

                    // 如果舰船处于相位状态，则给予被动加成，否则移除
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

        // ========== 残影生成（每0.008秒一次） ==========
        if (ship != null && !Global.getCombatEngine().isPaused()
                && state != State.COOLDOWN && state != State.IDLE) {
            renderTimer += Global.getCombatEngine().getElapsedInLastFrame();
            if (renderTimer >= 0.008f) {
                renderTimer -= 0.008f;

                Color afterimageColor = getAfterimageColorForShip(ship);

                // 使用 MagicRender 绘制舰船精灵的残影
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
     * 生成 75 道电弧：起点为舰船中心，终点为碰撞半径 *5 范围内的随机点。
     * 用于增强视觉冲击力。
     */
    private void generateArcs(ShipAPI ship) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) return;
        Vector2f center = ship.getLocation();
        float radius = ship.getCollisionRadius() * 5f;
        Color fringe = getHitColorForShip(ship);
        Color core = Color.WHITE;
        float thickness = 15f;

        for (int i = 0; i < 75; i++) {
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
            mainArc.setSingleFlickerMode(true);        // 单段闪烁模式
            mainArc.setRenderGlowAtStart(false);        // 起点不渲染光晕
            mainArc.setRenderGlowAtEnd(true);           // 终点渲染光晕
            mainArc.setUpdateFromOffsetEveryFrame(false); // 不每帧更新起点
        }
    }

    /**
     * 创建光晕生成插件。
     * 该插件在持续时间内每 1.1 秒生成一次光晕。
     */
    private void createFlarePlugin(final ShipAPI ship) {
        if (flarePlugin != null) return;
        flarePlugin = new BaseEveryFrameCombatPlugin() {
            private float elapsed = 0f;
            private float nextFlareTime = 0f;
            private final float LIFECYCLE = 1.1f;  // 光晕生成间隔
            private final float DURATION = 2f;      // 插件总持续时间

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

    /** 移除光晕插件。 */
    private void cleanupFlarePlugin() {
        if (flarePlugin != null) {
            Global.getCombatEngine().removePlugin(flarePlugin);
            flarePlugin = null;
        }
    }

    /**
     * 生成四个固定大小的光晕（十字形排列）。
     */
    private void spawnFlares(ShipAPI ship) {
        Vector2f loc = new Vector2f(ship.getLocation());
        Color core = Color.WHITE;
        Color fringe = getHitColorForShip(ship);

        float width = 3200f;
        float width2 = 1500f;
        float width3 = 750f;
        float height = 150f;
        float height2 = 200f;
        float height3 = 120f;
        float in = 0.1f;
        float full = 0f;
        float out = 1.0f;
        float glowPower = 0.4f;
        float noisePower = 0.1f;

        // 分别沿 0°、90°、45°、-45° 方向生成光晕
        addSharpFlare_noFlick(loc, 0f, width, height, core, fringe, in, full, out, glowPower, noisePower);
        addSharpFlare_noFlick(loc, 90f, width2, height2, core, fringe, in, full, out, glowPower, noisePower);
        addSharpFlare_noFlick(loc, 45f, width3, height3, core, fringe, in, full, out, glowPower, noisePower);
        addSharpFlare_noFlick(loc, -45f, width3, height3, core, fringe, in, full, out, glowPower, noisePower);
    }

    /** 施加被动相位加成（最大航速、机动性 +50%）。 */
    private void applyPassivePhaseBonus(MutableShipStatsAPI stats) {
        stats.getMaxSpeed().modifyMult(PASSIVE_ID, 1.5F);
        stats.getAcceleration().modifyMult(PASSIVE_ID, 1.5F);
        stats.getDeceleration().modifyMult(PASSIVE_ID, 1.5F);
        stats.getTurnAcceleration().modifyMult(PASSIVE_ID, 1.5F);
        stats.getMaxTurnRate().modifyMult(PASSIVE_ID, 1.5F);
    }

    /** 移除被动相位加成。 */
    private void removePassivePhaseBonus(MutableShipStatsAPI stats) {
        stats.getMaxSpeed().unmodifyMult(PASSIVE_ID);
        stats.getAcceleration().unmodifyMult(PASSIVE_ID);
        stats.getDeceleration().unmodifyMult(PASSIVE_ID);
        stats.getTurnAcceleration().unmodifyMult(PASSIVE_ID);
        stats.getMaxTurnRate().unmodifyMult(PASSIVE_ID);
    }

    /** 移除所有激活状态的加成。 */
    private void removeActiveBonus(MutableShipStatsAPI stats) {
        stats.getMaxTurnRate().unmodifyMult(ACTIVE_ID);
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

    /**
     * 创建处理透明度与碰撞的每帧插件。
     * 每帧根据当前效果等级设置舰船额外透明度，并将碰撞类别设为 NONE（无法被击中）。
     */
    private void createAlphaCollisionPlugin(final ShipAPI ship) {
        if (alphaCollisionPlugin != null) return;
        alphaCollisionPlugin = new BaseEveryFrameCombatPlugin() {
            @Override
            public void advance(float amount, List<InputEventAPI> events) {
                if (ship != null && ship.isAlive()) {
                    float alpha = 1.0f - (1.0f - SHIP_ALPHA_MULT) * currentEffectLevel;
                    ship.setExtraAlphaMult(alpha);
                    ship.setApplyExtraAlphaToEngines(true);
                    ship.setCollisionClass(CollisionClass.NONE); // 无法被碰撞
                }
            }
        };
        Global.getCombatEngine().addPlugin(alphaCollisionPlugin);
    }

    /**
     * 清理透明度和碰撞插件，并恢复舰船的正常碰撞类别。
     */
    private void cleanupAlphaCollisionPlugin(ShipAPI ship) {
        if (alphaCollisionPlugin != null) {
            Global.getCombatEngine().removePlugin(alphaCollisionPlugin);
            alphaCollisionPlugin = null;
        }
        if (ship != null) {
            ship.setExtraAlphaMult(1.0f);
            ship.setApplyExtraAlphaToEngines(false);
            if (!ship.isPhased()) {
                // 根据是否战斗机恢复正确的碰撞类别
                if (ship.isFighter()) {
                    ship.setCollisionClass(CollisionClass.FIGHTER);
                } else {
                    ship.setCollisionClass(CollisionClass.SHIP);
                }
            }
        }
    }

    /**
     * 在每个系统槽位生成一枚 ID 为 "VIII_Fragmentation_MIRV" 的导弹。
     * 使用虚假武器，仅用于视觉和伤害判定。
     */
    private void generateMissiles(ShipAPI ship) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) return;

        WeaponAPI fakeWeapon = engine.createFakeWeapon(ship, "VIII_Fragmentation_MIRV");
        for (WeaponSlotAPI slot : ship.getHullSpec().getAllWeaponSlotsCopy()) {
            if (!slot.isSystemSlot()) continue;
            Vector2f loc = slot.computePosition(ship);
            float angle = slot.getAngle() + ship.getFacing();
            engine.spawnProjectile(ship, fakeWeapon, "VIII_Fragmentation_MIRV", loc, angle, ship.getVelocity());
        }
    }

    /**
     * 系统结束时移除所有属性修正和插件，重置状态。
     */
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
            // 移除武器开火限制插件
            cleanupWeaponLockPlugin();

            // 标记系统已结束：弹头替换插件会继续工作到滞留时间结束再自行注销，
            // 以保证系统关闭瞬间已在飞行途中的弹头同样能被替换
            systemActive = false;

            ShipSystemAPI cloak = ship.getPhaseCloak();
            if (cloak == null) {
                cloak = ship.getSystem();
            }
            if (cloak != null) {
                ((PhaseCloakSystemAPI) cloak).setMinCoilJitterLevel(0.0F);
            }
        }
        // 重置标志与计时器
        missileTimer = MISSILE_INTERVAL;   // 预置一个间隔，下次激活首帧立即生成第一轮
        missilesFired = 0;
        arcsGenerated = false;
        ammoSet = false;
        currentEffectLevel = 0f;
        renderTimer = 0f;
    }
}