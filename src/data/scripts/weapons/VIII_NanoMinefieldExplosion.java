package data.scripts.weapons;// 包路径：模组的武器脚本目录

import java.util.List;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.FighterWingAPI;
import com.fs.starfarer.api.combat.ProximityExplosionEffect;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.util.Misc;

// 说明：原文件里未使用的导入（java.awt.Color、NegativeExplosionVisual.NEParams、
// RiftCascadeMineExplosion）已移除，避免编译告警。若你后续要在本类里加视觉表现，
// 再按需把对应导入加回来即可。

/**
 * 纳米雷场／裂隙闪电雷的爆炸效果类，并在爆炸触发时投放一支"攻击蜂群"战机编队。
 * <p>
 * 由 .proj 中的 behaviorSpec.onExplosionEffect 反射调用，因此：
 * 1) 必须有 public 无参构造器；
 * 2) 类名大小写必须与字符串完全一致。
 * <p>
 * 【功能】
 * 爆炸发生时生成 {@link #SWARM_COUNT} 架装配 id 为 {@link #SWARM_WING_ID} 的战机。
 * <p>
 * 【关键实现选择】
 * - 生成入口用的是"归属方的舰队管理器"上的 spawnShipOrWing(specId, location, facing)：
 *   它接受规格或变体 id，且当预备队里没有对应编队时会临时创建一个 FleetMemberAPI
 *   塞进预备队，因此不要求母舰是航母、也不要求船上有预备战机。
 * - 归属来源优先取"水雷的发射舰"（投射物的 getSource()），取不到才退回投射物自身的
 *   归属数值（0 = 玩家方，1 = 敌方，100 = 中立）。
 * - 战机不会生成在爆炸正中，而是散布在中心外围；并且默认延迟一小段时间再生成，
 *   防止刚出生的战机被这次爆炸（同一个 .proj 的 explosionSpec）直接打死。
 * - 生成期间临时屏蔽该方的"部署信息"（例："已部署 XXX"），生成完立刻按原状态还原：
 *   雷场刷出来的机群属于凭空出现，不该在战斗信息栏里刷屏；同时又不能长期改变
 *   引擎／其他模组的这个全局开关，所以只在生成的那一瞬间按下、随后还原。
 * - 伤害结算仍然全部由 .proj 的 explosionSpec 负责，本类只负责"表现 + 生成机群"。
 */
public class VIII_NanoMinefieldExplosion implements ProximityExplosionEffect {

    // ==================== 一、可调参数 ====================

    /**
     * 战机装配（变体）id。
     * 必须与 data/variants/fighters/ 下的文件名完全一致（这是标识符，不是文案，不能翻译）。
     */
    public static final String SWARM_WING_ID = "VIII_attack_swarm_wing";

    /** 每次爆炸生成几架战机（需求为 3 架） */
    public static int SWARM_COUNT = 3;

    /**
     * 生成位置相对爆炸中心的散布半径（距离单位）。
     * 该值应当大于本次爆炸的伤害半径，否则新战机一出生就吃自己的爆炸伤害。
     * 请在 .proj 的 explosionSpec 里确认爆炸半径后再微调本值。
     */
    public static float SWARM_SPAWN_RADIUS = 130f;

    /**
     * 生成朝向相对"母舰 → 生成点"方向的随机偏差（度）。
     * 作用：三架战机不会以完全相同的朝向并排冲出，看起来更像蜂群。
     */
    public static float SWARM_FACING_JITTER = 45f;

    /**
     * 延迟生成时间（秒）。0 表示在爆炸回调里立即生成。
     * 默认 0.1 秒：既让爆炸伤害先结算完，又完全看不出延迟。
     */
    public static float SWARM_SPAWN_DELAY = 0.1f;

    /**
     * 生成战机的那一瞬间是否隐藏"部署信息"：默认隐藏。
     * 隐藏时段只覆盖"生成调用本身"，生成结束（含中途失败）立即还原成调用前的状态。
     */
    public static boolean HIDE_DEPLOYMENT_MESSAGES = true;

    /** 中立归属（owner = 100，例如舰船残骸）的投射物是否也生成战机：默认否 */
    public static boolean SPAWN_FOR_NEUTRAL = false;

    /** 生成出来的战机归属与目标归属不一致时，是否强制改写归属：默认是 */
    public static boolean FORCE_OWNER = true;

    /** 调试日志开关：开启后会在 starsector.log 里记录生成结果，正式发布时保持 false */
    public static boolean DEBUG_LOG = false;

    // ==================== 二、内部常量 ====================

    /** 中立归属数值：0 = 玩家方，1 = 敌方，100 = 中立（例如舰船残骸） */
    protected static final int NEUTRAL_OWNER = 100;

    /** "变体 id 不存在"的警告是否已经打过：避免每颗雷都刷一遍日志 */
    protected static boolean warnedMissingWing = false;

    // ==================== 三、爆炸回调 ====================

    /**
     * 引信触发时被调用。
     *
     * @param explosion        爆炸生成的投射物对象（携带位置、归属等信息，常用于挂载视觉效果）
     * @param originalProjectile 原始的投射物（即那颗水雷）。部分实现中它与 explosion 是同一对象，
     *                           不要假定两者一定不同
     */
    @Override
    public void onExplosion(DamagingProjectileAPI explosion, DamagingProjectileAPI originalProjectile) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null || engine.isPaused()) {
            return; // 非战斗环境 / 战斗暂停：不做任何事
        }

        // ---------- 1) 确定生成中心 ----------
        Vector2f center = resolveExplosionCenter(explosion, originalProjectile);
        if (center == null) {
            return; // 两个投射物都拿不到坐标，理论上前提已不成立
        }

        // ---------- 2) 确定归属 ----------
        ShipAPI source = resolveSourceShip(explosion, originalProjectile);
        int owner = resolveOwner(explosion, originalProjectile, source);
        if (owner == NEUTRAL_OWNER && !SPAWN_FOR_NEUTRAL) {
            return; // 中立方的雷不刷攻击机群
        }

        if (DEBUG_LOG) {
            Global.getLogger(VIII_NanoMinefieldExplosion.class).info(
                    "空雷爆炸：准备生成 " + SWARM_COUNT + " 架战机，归属 = " + owner
                            + "，中心 = (" + (int) center.x + ", " + (int) center.y + ")"
                            + "，来源舰船 = " + ((source == null) ? "无" : source.getName()));
        }

        // ---------- 3) 生成（可延迟，避免被自己的爆炸打死） ----------
        if (SWARM_SPAWN_DELAY > 0f) {
            engine.addPlugin(createDelayedSpawnPlugin(center, owner, source, SWARM_SPAWN_DELAY));
        } else {
            spawnSwarm(engine, center, owner, source);
        }
    }

    // ==================== 四、取点 / 取归属 ====================

    /**
     * 取爆炸中心坐标：优先用爆炸投射物自己的位置（那才是真正的爆点），
     * 取不到再退回原始投射物（水雷）的位置。
     * 复制成新向量，避免后续混淆引擎内部状态。
     *
     * @param explosion        爆炸投射物，可为 null
     * @param originalProjectile 原始投射物，可为 null
     * @return 爆点坐标；都取不到时返回 null
     */
    protected Vector2f resolveExplosionCenter(DamagingProjectileAPI explosion, DamagingProjectileAPI originalProjectile) {
        if (explosion != null && explosion.getLocation() != null) {
            return new Vector2f(explosion.getLocation());
        }
        if (originalProjectile != null && originalProjectile.getLocation() != null) {
            return new Vector2f(originalProjectile.getLocation());
        }
        return null;
    }

    /**
     * 取"投放这颗雷的舰船"。
     * 优先用爆炸投射物，再退回原始投射物；两者都不提供来源时返回 null。
     *
     * @param explosion        爆炸投射物，可为 null
     * @param originalProjectile 原始投射物，可为 null
     * @return 来源舰船，可能为 null
     */
    protected ShipAPI resolveSourceShip(DamagingProjectileAPI explosion, DamagingProjectileAPI originalProjectile) {
        ShipAPI source = null;

        if (explosion != null) {
            source = explosion.getSource();
        }
        if (source == null && originalProjectile != null) {
            source = originalProjectile.getSource();
        }

        return source;
    }

    /**
     * 决定新机群的归属方。
     * <p>
     * 优先级：
     * 1) 来源舰船的归属（最准确：雷是它扔的）；
     * 2) 原始投射物（水雷）自身的归属；
     * 3) 爆炸投射物自身的归属；
     * 4) 都取不到 → 按中立处理（此时若未开启 SPAWN_FOR_NEUTRAL 就不会生成）。
     * <p>
     * 注意归属数值语义：0 = 玩家方，1 = 敌方，100 = 中立。
     *
     * @param explosion        爆炸投射物，可为 null
     * @param originalProjectile 原始投射物，可为 null
     * @param source           来源舰船，可为 null
     * @return 归属数值
     */
    protected int resolveOwner(DamagingProjectileAPI explosion, DamagingProjectileAPI originalProjectile, ShipAPI source) {
        if (source != null) {
            return source.getOwner();
        }
        if (originalProjectile != null) {
            return originalProjectile.getOwner();
        }
        if (explosion != null) {
            return explosion.getOwner();
        }
        return NEUTRAL_OWNER;
    }

    // ==================== 五、取朝向 ====================

    /**
     * 计算一架机群的初始朝向。
     * <p>
     * 规则：
     * 1) 有母舰时：朝向 = "母舰 → 生成点"的方向，也就是背朝母舰向外冲出，
     *    看上去像被这次爆炸抛出去的一样；
     * 2) 没有母舰时（投射物的来源可以为 null）：退化为随机朝向，
     *    避免整批战机都朝着同一个方向；
     * 3) 最后叠加一个 ±SWARM_FACING_JITTER 度的随机偏差并归一化到 [0, 360)，
     *    让三架战机不会像排队一样并排冲出。
     *
     * @param source   投放这颗雷的舰船，可为 null
     * @param spawnLoc 本架战机的生成点（非 null）
     * @return 初始朝向（度）
     */
    protected float pickFacing(ShipAPI source, Vector2f spawnLoc) {
        float facing;

        if (source != null && source.getLocation() != null) {
            // 从母舰指向生成点的角度（度）
            facing = Misc.getAngleInDegrees(source.getLocation(), spawnLoc);
        } else {
            // 没有母舰：随机朝向
            facing = (float) Math.random() * 360f;
        }

        if (SWARM_FACING_JITTER > 0f) {
            // Math.random() 落在 [0, 1)，乘 2 减 1 后落在 [-1, 1)
            float jitter = ((float) Math.random() * 2f - 1f) * SWARM_FACING_JITTER;
            facing += jitter;
        }

        // 归一化到 [0, 360)，避免出现负数角度或超过 360 的角度传给引擎
        return Misc.normalizeAngle(facing);
    }

    // ==================== 六、生成机群 ====================

    /**
     * 在爆炸中心外围生成一整支战机编队。
     * <p>
     * 本方法会：生成前临时隐藏该方的"部署信息"，生成结束后（含中途失败）按原状态还原。
     *
     * @param engine 战斗引擎（非 null）
     * @param center 爆炸中心
     * @param owner  归属方的 owner 数值
     * @param source 投放者（母舰），可为 null
     */
    protected void spawnSwarm(CombatEngineAPI engine, Vector2f center, int owner, ShipAPI source) {
        if (engine == null || center == null || SWARM_COUNT <= 0) {
            return;
        }

        // 用"归属方的舰队管理器"投放，保证刷出来的战机自动站在正确的一方
        CombatFleetManagerAPI manager = engine.getFleetManager(owner);
        if (manager == null) {
            warnOnce("纳米雷场爆炸：拿不到归属 " + owner + " 的舰队管理器，本次不生成战机。");
            return;
        }

        // ---------- 0) 暂时隐藏部署信息 ----------
        // 返回值表示"这次开关是我们按下去的"，只有为 true 时我们才负责还原；
        // 若外面本来就是隐藏状态，返回 false，我们不去动别人的设置。
        boolean changedMessages = hideDeploymentMessages(manager);

        try {
            for (int i = 0; i < SWARM_COUNT; i++) {
                // ---------- 1) 散布生成点：避开爆炸中心，三架不叠在同一位置 ----------
                float radius = SWARM_SPAWN_RADIUS * (0.5f + (float) Math.random() * 0.5f);
                Vector2f spawnLoc = Misc.getPointAtRadius(center, radius);

                // ---------- 2) 取得朝向：背着母舰向外冲出，并加一点随机偏差 ----------
                float facing = pickFacing(source, spawnLoc);

                // ---------- 3) 真正生成 ----------
                // spawnShipOrWing 接受"规格 id 或变体 id"：
                // 预备队里没有对应编队时，它会临时创建一个 FleetMemberAPI 并放进预备队，
                // 所以本调用不要求母舰是航母，也不消耗玩家/敌方原有的预备编队。
                ShipAPI leader = manager.spawnShipOrWing(SWARM_WING_ID, spawnLoc, facing);

                if (leader == null) {
                    // 最常见的原因：data/variants/fighters/ 下不存在该变体 id
                    warnOnce("纳米雷场爆炸：未能生成战机，请检查变体 id \"" + SWARM_WING_ID
                            + "\" 是否存在于 data/variants/fighters/ 下。");
                    return; // 注意：这里直接返回，部署信息的还原交给下面的 finally
                }

                // ---------- 4) 绑定归属与母舰 ----------
                bindSpawnedShip(leader, owner, source);
            }

            if (DEBUG_LOG) {
                Global.getLogger(VIII_NanoMinefieldExplosion.class).info(
                        "纳米雷场爆炸：已生成 " + SWARM_COUNT + " 架 " + SWARM_WING_ID + "。");
            }
        } finally {
            // ---------- 5) 生成完毕，恢复部署信息 ----------
            // 必须放在 finally 里：即使上面提前 return 或抛异常，也绝不会让
            // 部署信息一直处于被屏蔽的状态（否则整场战斗的信息栏都会不完整）。
            restoreDeploymentMessages(manager, changedMessages);
        }
    }

    /**
     * 把刚生成的战机（含整队成员）与归属方、母舰对齐。
     * <p>
     * - 归属对齐：避免出现"战机是这边的、编队归属却是那边的"这种不一致状态；
     * - setLaunchingShip(source)：让"由舰船投放的战机"类加成（例如 hullmod 的
     *   applyEffectsToFighterSpawnedByShip）能够正确生效。
     *
     * @param ship  刚生成的舰船/战机（战机编队时为队长）
     * @param owner 目标归属
     * @param source 母舰，可为 null
     */
    protected void bindSpawnedShip(ShipAPI ship, int owner, ShipAPI source) {
        if (ship == null) {
            return;
        }

        if (FORCE_OWNER && ship.getOwner() != owner) {
            ship.setOwner(owner);
        }
        if (source != null) {
            ship.setLaunchingShip(source);
        }

        FighterWingAPI wing = ship.getWing();
        if (wing == null) {
            return; // 生成出来的不是战机（理论上不会发生），无需处理编队
        }

        // 编队归属单独存在，需要一并对齐
        if (FORCE_OWNER && wing.getWingOwner() != owner) {
            wing.setWingOwner(owner);
        }

        List<ShipAPI> members = wing.getWingMembers();
        if (members == null) {
            return;
        }
        for (int i = 0; i < members.size(); i++) {
            ShipAPI member = members.get(i);
            if (member == null) {
                continue;
            }
            if (FORCE_OWNER && member.getOwner() != owner) {
                member.setOwner(owner);
            }
            if (source != null) {
                member.setLaunchingShip(source);
            }
        }
    }

    // ==================== 七、部署信息开关 ====================

    /**
     * 临时隐藏某一方的部署信息（例：战斗信息栏里的"已部署 XXX"）。
     * <p>
     * 关键点：先读取原状态，只有"原本是显示着的"才由我们按下开关，
     * 并返回 true 表示"记得还原"；若原本就已经是隐藏状态，
     * 直接返回 false（既不改动，也不在事后替别人还原）。
     *
     * @param manager 归属方的舰队管理器
     * @return true = 本方法确实把开关从"显示"改成了"隐藏"，调用方必须还原；
     *         false = 无需还原（未开启功能、manager 为 null，或本来就是隐藏的）
     */
    protected boolean hideDeploymentMessages(CombatFleetManagerAPI manager) {
        if (!HIDE_DEPLOYMENT_MESSAGES || manager == null) {
            return false; // 功能关闭或没有可操作的管理器
        }

        if (manager.isSuppressDeploymentMessages()) {
            return false; // 原本就已经隐藏：不是我们按下的，不负责还原
        }

        manager.setSuppressDeploymentMessages(true);

        if (DEBUG_LOG) {
            Global.getLogger(VIII_NanoMinefieldExplosion.class).info("纳米雷场爆炸：已暂时隐藏部署信息。");
        }
        return true;
    }

    /**
     * 还原部署信息开关（只在 hideDeploymentMessages 返回 true 时才真正动手）。
     *
     * @param manager 归属方的舰队管理器
     * @param changed hideDeploymentMessages 的返回值
     */
    protected void restoreDeploymentMessages(CombatFleetManagerAPI manager, boolean changed) {
        if (!changed || manager == null) {
            return; // 我们没改过这个开关，就不要去动它
        }

        manager.setSuppressDeploymentMessages(false);

        if (DEBUG_LOG) {
            Global.getLogger(VIII_NanoMinefieldExplosion.class).info("纳米雷场爆炸：部署信息已恢复显示。");
        }
    }

    // ==================== 八、延迟生成插件 ====================

    /**
     * 造一个"等待几帧之后再生成机群"的极短插件。
     * <p>
     * 存在的意义：onExplosion 与爆炸伤害的结算几乎在同一时刻，若在爆点原地立即生成，
     * 新战机极可能被这次爆炸直接打残甚至打死。延迟 0.1 秒（约数帧）在观感上看不出来，
     * 却能保证机群完好地出现在爆点外围。
     * <p>
     * 注意：匿名内部类只能访问 final 的局部变量/参数，因此四个参数都标了 final。
     *
     * @param center 生成中心（已复制，不受后续引擎状态变化影响）
     * @param owner  归属
     * @param source 母舰，可为 null
     * @param delay  延迟秒数（> 0）
     * @return 每帧插件
     */
    protected EveryFrameCombatPlugin createDelayedSpawnPlugin(final Vector2f center, final int owner,
                                                              final ShipAPI source, final float delay) {
        return new BaseEveryFrameCombatPlugin() {
            float elapsed = 0f;

            @Override
            public void advance(float amount, List<InputEventAPI> events) {
                CombatEngineAPI engine = Global.getCombatEngine();
                if (engine == null) {
                    return; // 已经在战役层了：无法移除自己，直接什么都不做
                }
                if (engine.isPaused()) {
                    return; // 暂停期间不计时
                }

                elapsed += amount;
                if (elapsed < delay) {
                    return;
                }

                engine.removePlugin(this); // 先把自己摘掉，保证只生成一次
                spawnSwarm(engine, center, owner, source); // 部署信息的隐藏/还原在方法内部成对处理
            }
        };
    }

    // ==================== 九、日志辅助 ====================

    /**
     * 打一次警告（同一条只打一次），用于提示"变体 id 不存在"这类配置问题。
     *
     * @param message 中文提示信息
     */
    protected void warnOnce(String message) {
        if (warnedMissingWing) {
            return;
        }
        warnedMissingWing = true;
        Global.getLogger(VIII_NanoMinefieldExplosion.class).warn(message);
    }

    // ==================== 十、备选写法（按需启用，默认不参与编译） ====================
    /*
     * 备选 1：引擎自带的战机生成方法（部分版本提供，本次检索到的 API 文档中未收录）
     *   ShipAPI leader = engine.spawnFighter(SWARM_WING_ID, spawnLoc, facing, owner);
     * 与当前实现的区别：它不经过预备队、不产生临时 FleetMemberAPI。
     * 若你的游戏版本里存在该方法，并且你不想让临时编队进入预备队，可改用它。
     *
     * 备选 2：LazyLib 的投放方法
     *   CombatUtils.spawnShipOrWingDirectly(SWARM_WING_ID, FleetMemberType.FIGHTER_WING,
     *           FleetSide.ENEMY, 1f, spawnLoc, facing);
     * 它用 FleetSide 而不是 owner 整数，且官方注释提醒"战役层可能战后崩溃"，
     * 因此仅建议在战斗中、且你确认过版本行为时使用。
     *
     * 备选 3：想要"更均匀"的散布与朝向，可把 Math.random() 换成 LazyLib 的
     *   MathUtils.getRandomNumberInRange(min, max)（LazyLib 2.0+ 提供 float/int 两种重载）。
     * 用 Math.random() 的好处是不引入额外依赖，你不装 LazyLib 也能编译。
     *
     * 备选 4：若实测发现"部署信息还是闪了一下"，说明你那个版本不是生成瞬间判定、
     *   而是隔帧才渲染这条信息。此时可把隐藏时段拉长到跨帧：
     *   在 createDelayedSpawnPlugin 的 advance 里，用同一个 changed 思路
     *   在生成前隐藏、并再挂一个只做"还原"的短插件，等 1~3 帧后再 restore。
     *   代价是这几帧内该方所有真实增援的部署信息也会被一起吞掉，请自行权衡。
     */
}