package data.scripts.weapons;// 包路径：模组的武器脚本目录，实现“裂隙闪电（Rift Lightning）”武器的特效逻辑

// 导入游戏核心API：全局工具、战斗实体/引擎/投射物/电弧/武器相关接口、伤害结果监听器等
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import com.fs.starfarer.api.util.Misc;

// 导入 LazyLib 工具：碰撞检测、向量旋转、战斗实体查询（与参考实现保持一致的判定方式）
import org.lazywizard.lazylib.CollisionUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lazywizard.lazylib.combat.CombatUtils;

import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * 裂隙闪电武器特效插件（多电弧版）
 * 实现开火/命中/每帧更新三个接口（三个字段都必须在，否则引擎强制转型时报 ClassCastException），
 * 核心功能：投射物仅作为“载体”，由本插件生成“三条”裂隙闪电电弧（主电弧 + 两道额外电弧）并各自附带延迟地雷。
 * 【历史修改·修复“一次开火出现两道电弧”】
 * 原因：电弧原来有两条互不知情的生成路径——
 *   路径A：onFire 里沿武器朝向来一条；
 *   路径B（原版逻辑）：onHit（命中）或 advance（超出射程兜底）里再来一条 + 地雷；
 *   两者之间没有互斥标记，于是同一次射击会各画一条。
 * 修法：
 *   1）所有电弧生成收敛到唯一入口 fireRiftArcVolley(...) / spawnSingleRiftArc(...)；
 *   2）一炮一组：onFire 生成后立刻在投射物 customData 写入 RIFT_LIGHTNING_FIRED_TAG，
 *      onHit / advance 进来先查该标记，已生成则直接返回（唯一可靠的互斥方式，
 *      因为 OnHitEffectPlugin 实例是“每投射物一个”，无法用实例字段共享状态）；
 *   3）地雷搬进唯一入口：位置 = 电弧终点（可配置为“被挡下的命中点”），延迟 = 电弧生长时长 × 系数。
 * 【历史修改·电弧造成伤害 + 伤害数字分流】
 *   1）新增：电弧“被舰船挡下”时，对该舰船造成“武器伤害 × RIFT_ARC_SHIP_DAMAGE_MULT”的伤害；
 *      伤害归属传发射舰船（source = 本舰），因此引擎会按原版规则弹出伤害数字。
 *   2）新增：伤害数字兜底。若引擎自身不会为这次伤害弹数字（例如发射舰不是玩家舰船），
 *      在 RIFT_ARC_FORCE_DAMAGE_FLOATY 开启时手动补一个 addFloatingDamageText 数字，
 *      保证“电弧伤害可见”。若引擎自己会弹，则不重复补。
 *   3）保持：被穿透的目标（战斗机 / 陨石 / 导弹）依旧“不显示伤害数字”——
 *      方法仍是把伤害归属传 null（引擎缺少归属方时不弹数字），且绝不为它们调用补数字接口。
 *   4）命中点改用 CollisionUtils.getCollisionPoint（精确边界优先）并做“确保落在碰撞圆内”修正，
 *      因为 applyDamage 的 point 必须落在舰船精灵内，装甲才会被正确处理。
 * 【本次修改·电弧不再吸附目标 + 新增两道额外电弧】
 *   一、不再吸附目标（修复“武器朝向与目标有偏差时电弧吸附到目标精确边界”）
 *      原因：旧代码把“电弧终点”直接替换成目标的命中点，而命中点可能来自
 *            ① CollisionUtils.getCollisionPoint 的“精确边界交点”，
 *            ② 目标没有精确边界时它返回的“目标中心”，
 *            ③ ensurePointInside 把点朝目标中心推进后的结果（已经偏离武器朝向射线）；
 *            于是电弧的长度与落点全部由目标决定，看起来就是“自动锁定/吸附”到目标边界。
 *      修法：把“电弧几何”与“伤害结算”解耦——
 *            · 电弧终点只由“起点 + 朝向 × 射程”决定：getRayEnd(from, 朝向, 射程)；
 *            · 伤害结算照旧用射线做碰撞扫描，applyDamage 仍用 ensurePointInside 修正后的点；
 *            · 可选的两项“旧手感”开关都只在射线上做截断，绝不产生横向偏移：
 *              RIFT_ARC_SNAP_VISUAL_TO_HIT_POINT（完全恢复旧行为，默认关）
 *              RIFT_ARC_VISUAL_STOP_AT_FIRST_SHIP（长度在第一艘挡路舰船处截断，默认关）
 *   二、新增两道额外电弧
 *      · 朝向 = 主电弧朝向 ± 随机 5~25 度（默认一左一右，可改为每道独立随机左右）；
 *      · 长度、厚度、核心宽度、颜色、伤害、穿透伤害、地雷延迟与主电弧完全一致；
 *      · 每条电弧独立扫描碰撞、独立结算伤害、独立生成一颗地雷。
 * 【电弧碰撞规则】
 * - 电弧“不会”与战斗机、陨石（小行星）、导弹发生碰撞（不会被打断、不会停在它们身上）；
 * - 但路径上的它们会受到“武器伤害 × RIFT_ARC_PASS_THROUGH_DAMAGE_MULT”的穿透伤害（不显示数字）；
 * - 普通舰船（非战斗机）会阻挡电弧的伤害延伸（RIFT_ARC_STOPPED_BY_SHIPS 控制），并在被击中的位置受到伤害（显示数字）。
 * 说明：本文件中的所有字符串常量都是“投射物ID / 自定义数据键 / 修饰器键”这类内部标识，
 *       不是玩家可见的显示文本，因此保持原样不翻译（改动它们会导致存档与其它脚本失效）。
 */
public class VIII_riftlightningEffect implements OnHitEffectPlugin, OnFireEffectPlugin, EveryFrameWeaponEffectPlugin {
    // 裂隙闪电的颜色
    public static Color RIFT_LIGHTNING_COLOR = new Color(28, 0, 255, 255);
    // 闪电移动速度（10000单位/秒，近乎瞬时；当前版本电弧生长时长由 params.movementDurOverride 直接指定）
    public static float RIFT_LIGHTNING_SPEED = 10000.0F;
    // 投射物伤害移除的修饰器键（用于将投射物基础伤害设为0）
    public static String RIFT_LIGHTNING_DAMAGE_REMOVER = "rift_lightning_damage_remover";
    // 电弧已生成的标记键（投射物自定义数据）：一次射击只允许生成一组电弧（主 + 额外）与对应地雷
    public static String RIFT_LIGHTNING_FIRED_TAG = "rift_lightning_fired_tag";
    // 存储源武器的自定义数据键（关联投射物与发射武器）
    public static String RIFT_LIGHTNING_SOURCE_WEAPON = "rift_lightning_source_weapon";
    // 存储本次射击“武器基础伤害”的自定义数据键
    // （投射物伤害会被清零，且命中时机模式下 OnHitEffectPlugin 是另一个实例，因此必须另存一份）
    public static String RIFT_ARC_DAMAGE_TAG = "rift_arc_damage";
    // 存储本次射击“伤害类型”的自定义数据键
    public static String RIFT_ARC_DAMAGE_TYPE_TAG = "rift_arc_damage_type";

    // ========== 电弧外观常量 ==========
    // 电弧超射程冗余距离（与武器射程相加，作为电弧的最大长度）
    public static float RIFT_ARC_SLOP_RANGE = 0.0F;
    // 电弧基础厚度
    public static float RIFT_ARC_THICKNESS = 80.0F;
    // 电弧核心宽度（覆盖默认核心宽度）
    public static float RIFT_ARC_CORE_WIDTH = 40.0F;

    // ========== 电弧伤害常量 ==========
    // 电弧对“普通舰船”的伤害倍数（1.0 = 与武器单发基础伤害相同；设为 0 可关闭舰船伤害）
    public static float RIFT_ARC_SHIP_DAMAGE_MULT = 1.0F;
    // 穿透伤害倍数：被打穿的目标（战斗机 / 陨石 / 导弹）受到“武器伤害 × 该倍数”的伤害
    public static float RIFT_ARC_PASS_THROUGH_DAMAGE_MULT = 100.0F;
    // 电弧基础伤害覆盖值：> 0 时用它取代“从武器读到的伤害”，
    // 用于武器伤害读不到（为 0）或想给电弧单独指定伤害值的情况；<= 0 表示不覆盖。
    public static float RIFT_ARC_DAMAGE_OVERRIDE = -1.0F;
    // 是否播放命中音效（对应 applyDamage 的 playSound 参数；只影响舰船伤害）
    public static boolean RIFT_ARC_PLAY_DAMAGE_SOUND = false;
    // 电弧是否会被普通舰船（非战斗机）阻挡伤害延伸：
    //   true （默认）则在第一艘挡路舰船处结束伤害扫描；
    //   false 则继续贯穿（沿途每艘舰船都吃伤害）。
    // 注意：本次修改后本开关只影响“伤害是否继续结算”，不再影响电弧画到哪里。
    public static boolean RIFT_ARC_STOPPED_BY_SHIPS = true;

    // ========== 【本次修改】电弧几何开关（“不吸附目标”相关） ==========
    // 电弧视觉终点是否“吸附到被命中目标的边界/中心”（旧行为）。
    //   false（默认，需求要求）：终点只由“起点 + 朝向 × 射程”决定，与目标无关，绝不吸附；
    //   true ：恢复旧行为，电弧终点取命中点（会横向偏离武器朝向，即“吸附”）。
    public static boolean RIFT_ARC_SNAP_VISUAL_TO_HIT_POINT = false;
    // 不吸附时，电弧的“长度”是否在第一艘挡路舰船处截断。
    //   false（默认，需求要求）：电弧整条射线满射程，视觉上会穿过沿途目标继续延伸；
    //   true ：电弧到第一艘挡路舰船处就停，但截断点“严格位于朝向射线上”，
    //          因此方向依旧等于武器朝向，不会产生横向吸附。
    public static boolean RIFT_ARC_VISUAL_STOP_AT_FIRST_SHIP = true;
    // 地雷落点是否强制使用“被挡下的命中点”（该点同样严格位于朝向射线上）。
    //   false（默认）：地雷落在“电弧终点”（与电弧生长终点一致，三条电弧各一颗）；
    //   true ：有舰船挡路时，地雷改落在被挡下的命中点（更接近旧版的玩法手感）。
    public static boolean RIFT_ARC_MINE_AT_BLOCKER_POINT = false;

    // ========== 【本次修改】额外电弧常量（新增两道电弧） ==========
    // 额外电弧数量（默认 2 道；设为 0 即退化为“只有主电弧”）
    public static int RIFT_ARC_EXTRA_COUNT = 2;
    // 额外电弧相对主电弧的最小偏差角度（度）——需求要求 5 度
    public static float RIFT_ARC_EXTRA_MIN_DEVIATION = 1.0F;
    // 额外电弧相对主电弧的最大偏差角度（度）——需求要求 25 度
    public static float RIFT_ARC_EXTRA_MAX_DEVIATION = 6.0F;
    // 偏差方向是否“一左一右”：
    //   true （默认）：第 1 道偏左、第 2 道偏右（偏差角度各自在 5~25 度之间独立随机），
    //                  保证两道电弧分居主电弧两侧；
    //   false        ：每道电弧独立随机选择偏左或偏右。
    public static boolean RIFT_ARC_EXTRA_MIRRORED_SIDES = true;
    // 额外电弧的伤害倍数（1.0 = 与主电弧完全一致，符合“其他属性与主电弧一致”）；
    // 若发现三道电弧同时命中同一目标伤害过高，可把它调到 0.3 左右来削弱额外电弧。
    public static float RIFT_ARC_EXTRA_DAMAGE_MULT = 1.0F;

    // ========== 伤害数字常量 ==========
    // 穿透伤害（战斗机 / 陨石 / 导弹）是否把伤害归属给发射舰船：
    //  - false（默认，本体要求）：归属传 null —— 引擎缺少伤害归属方时不会弹出伤害数字
    //    （注意：这也意味着这次伤害不计入发射舰的击杀/伤害统计）。
    //  - true：伤害正常归属发射舰（可统计），但穿透目标也会开始显示伤害数字（不符合本体要求）。
    public static boolean RIFT_ARC_ATTRIBUTE_PASS_THROUGH_TO_SOURCE = false;

    // 是否手动补伤害数字：
    //  引擎自身不会为这次伤害弹数字时（例如发射舰不是玩家舰船），手动补一个数字，保证电弧伤害可见。
    //  设为 false 则完全依赖引擎自身的显示规则（只有玩家舰船相关的伤害才会有数字）。
    public static boolean RIFT_ARC_FORCE_DAMAGE_FLOATY = false;
    // 手动补伤害数字时，是否忽略游戏设置里的“显示伤害数字”开关：
    //  false（默认）：玩家关闭了设置时就不补，尊重玩家偏好；
    //  true：即使玩家关闭设置也补，强制可见。
    public static boolean RIFT_ARC_IGNORE_FLOATY_SETTING = false;
    // 手动补出来的伤害数字颜色。
    //  这里用的是偏原版的浅黄色；想要更贴近原版观感可以换成：
    //  护盾伤害：Misc.FLOATY_SHIELD_DAMAGE_COLOR，装甲伤害：Misc.FLOATY_ARMOR_DAMAGE_COLOR，
    //  结构伤害：Misc.FLOATY_HULL_DAMAGE_COLOR，EMP 伤害：Misc.FLOATY_EMP_DAMAGE_COLOR。
    public static Color RIFT_ARC_DAMAGE_FLOATY_COLOR = new Color(255, 240, 160, 255);

    // 同一武器两次电弧生成的最小间隔（秒）：避免一次齐射/点射生成多条重复电弧
    // （OnFireEffectPlugin 实例是每武器一个，因此这个限流天然是“每武器”的；
    //   本次修改后，一组电弧（主 + 额外）共用同一次限流判定）
    public static float RIFT_ARC_MIN_INTERVAL = 0.0F;

    // 电弧生成时机：
    //  true （默认）= 开火瞬间生成（“朝向终点 + 碰撞结算”逻辑）
    //  false        = 原版时机（投射物命中或超出射程时生成），同样使用新的碰撞结算逻辑
    public static boolean RIFT_ARC_FIRE_ON_WEAPON_FIRE = true;

    // 地雷延迟系数：地雷在电弧“生长”到终点后再爆炸（延迟 = 电弧生长时长 × 本系数）
    public static float RIFT_ARC_MINE_DELAY_MULT = 0.8F;

    // 是否移除投射物本体（默认 false，保留投射物作为视觉载体）
    public static boolean RIFT_ARC_SUPPRESS_PROJECTILE = false;

    // 电弧“不碰撞”但会被穿透伤害的碰撞类别集合
    // 战斗机、导弹的碰撞类别是固定值，直接用碰撞类别判断最稳
    protected static final EnumSet<CollisionClass> RIFT_ARC_PASS_THROUGH_CLASSES = EnumSet.of(
            CollisionClass.FIGHTER,        // 战斗机 / 无人机
            CollisionClass.MISSILE_FF,     // 会友军误伤的导弹
            CollisionClass.MISSILE_NO_FF   // 不会友军误伤的导弹
    );

    // 已发射的闪电投射物数据列表（仅在“原版时机”模式下用于跟踪未结算的投射物）
    protected List<FiredLightningProjectile> fired = new ArrayList<FiredLightningProjectile>();

    // 上一次生成电弧的战斗时间（用于限流；-1 表示尚未生成过）
    protected float lastFacingArcTime = -1.0F;

    // ==================== 接口实现 ====================

    /**
     * 武器开火时的逻辑（OnFireEffectPlugin接口实现）
     * 1. 读取本次射击的原始伤害与伤害类型（投射物伤害随后会被清零，必须先读）；
     * 2. 把投射物伤害乘数设为0（投射物只作为视觉载体）；
     * 3. 把源武器、伤害、伤害类型写入投射物自定义数据（供命中时机模式与兼容接口使用）；
     * 4. 【本次修改】生成一组电弧（主电弧 + 两道额外电弧，各带一颗地雷），
     *    并在投射物上打标记，使后续 onHit / advance 不会再补出第二组电弧。
     *
     * @param projectile 发射的投射物
     * @param weapon     当前武器实例
     * @param engine     战斗引擎实例
     */
    public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        if (projectile == null || weapon == null || engine == null) return;

        // 取出本次射击的原始伤害与伤害类型（必须在清零之前读取）
        float weaponDamage = projectile.getDamage().getDamage();        // 武器单发基础伤害（DamageAPI.getDamage）
        DamageType weaponDamageType = projectile.getDamage().getType(); // 武器伤害类型（DamageAPI.getType）

        // 将投射物伤害乘数设为0（投射物仅触发特效，无直接伤害）
        projectile.getDamage().getModifier().modifyMult(RIFT_LIGHTNING_DAMAGE_REMOVER, 0.0F);

        // 记录关联数据：源武器 + 本次射击伤害 + 伤害类型
        projectile.setCustomData(RIFT_LIGHTNING_SOURCE_WEAPON, weapon);
        projectile.setCustomData(RIFT_ARC_DAMAGE_TAG, weaponDamage);
        projectile.setCustomData(RIFT_ARC_DAMAGE_TYPE_TAG, weaponDamageType);

        if (RIFT_ARC_FIRE_ON_WEAPON_FIRE) {
            // ---------- 模式一（默认）：开火瞬间生成一组电弧（主 + 额外） ----------
            fireRiftArcVolley(weapon, weaponDamage, weaponDamageType, engine, true);

            // 关键：无论电弧是否因限流被跳过，都视作“本次射击已处理”，
            // 这样 onHit / advance 永远不会再补出第二条电弧。
            projectile.setCustomData(RIFT_LIGHTNING_FIRED_TAG, true);

            // 可选：移除投射物本体（默认关闭）
            if (RIFT_ARC_SUPPRESS_PROJECTILE && engine.isEntityInPlay(projectile)) {
                engine.removeEntity(projectile);
            }
        } else {
            // ---------- 模式二：保留原版时机（命中/超射程时再生成电弧） ----------
            FiredLightningProjectile data = new FiredLightningProjectile();
            data.projectile = projectile;
            this.fired.add(data);
        }
    }

    /**
     * 投射物命中时的逻辑（OnHitEffectPlugin接口实现）
     * 【必须保留本方法】武器/弹丸文件的 onHitEffect 字段会强制转型为 OnHitEffectPlugin，
     * 删掉本接口或本方法就会在开火瞬间抛出 ClassCastException。
     * 这里不再无条件生成电弧：
     * - 开火时机模式下，电弧已在 onFire 生成（tag 已存在）→ 直接返回，避免第二道电弧；
     * - 原版时机模式下，才在这里调用唯一入口生成电弧（同样受 tag 保护，只生成一组）。
     *
     * @param projectile   命中的投射物
     * @param target       被命中的实体
     * @param point        命中位置
     * @param shieldHit    是否命中护盾
     * @param damageResult 伤害结果数据
     * @param engine       战斗引擎实例
     */
    public void onHit(DamagingProjectileAPI projectile, CombatEntityAPI target, Vector2f point, boolean shieldHit,
                      ApplyDamageResultAPI damageResult, CombatEngineAPI engine) {
        if (projectile == null || engine == null) return;

        // 本次射击的电弧已经生成过（开火时机模式的常态）→ 什么也不做
        if (projectile.getCustomData().containsKey(RIFT_LIGHTNING_FIRED_TAG)) return;

        // 仅“原版时机”模式才会走到这里；即使走到，也仍然使用新电弧 + 新碰撞逻辑
        if (RIFT_ARC_FIRE_ON_WEAPON_FIRE) return;

        WeaponAPI weapon = getStoredWeapon(projectile);
        if (weapon == null) return;

        boolean spawned = fireRiftArcVolley(weapon, getStoredDamage(projectile), getStoredDamageType(projectile),
                engine, false);
        if (spawned) {
            projectile.setCustomData(RIFT_LIGHTNING_FIRED_TAG, true);
        }
    }

    /**
     * 每帧更新逻辑（EveryFrameWeaponEffectPlugin接口实现）
     * 本方法不负责“画电弧”，只做两件事：
     * 1. 开火时机模式下：直接跳过（此模式下所有电弧都在 onFire 生成完毕，跟踪列表为空）；
     * 2. 原版时机模式下：跟踪投射物，命中/离场/超出射程时调用唯一入口补发一组电弧，并清理数据。
     *
     * @param amount 时间增量（秒）
     * @param engine 战斗引擎实例
     * @param weapon 当前武器实例
     */
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 快进状态下不处理（避免快进时批量误触发）
        if (engine == null || weapon == null || engine.isInFastTimeAdvance()) return;
        // 无待跟踪投射物时直接返回（开火时机模式下恒为空，零开销）
        if (this.fired.isEmpty()) return;

        ShipAPI ship = weapon.getShip();
        if (ship == null || !ship.isAlive()) {
            this.fired.clear();
            return;
        }

        float maxRange = weapon.getRange(); // 武器最大射程
        List<FiredLightningProjectile> remove = new ArrayList<FiredLightningProjectile>();

        for (FiredLightningProjectile data : this.fired) {
            DamagingProjectileAPI proj = data.projectile;
            if (proj == null) {
                remove.add(data);
                continue;
            }

            boolean firedAlready = proj.getCustomData().containsKey(RIFT_LIGHTNING_FIRED_TAG);
            boolean stillInPlay = engine.isEntityInPlay(proj);
            float dist = Misc.getDistance(proj.getSpawnLocation(), proj.getLocation());

            if (!stillInPlay || proj.didDamage() || dist > maxRange) {
                remove.add(data);
                if (!firedAlready) {
                    // 未触发过电弧则补发（原版“未命中兜底”效果），但走的是新的唯一入口
                    boolean spawned = fireRiftArcVolley(weapon, getStoredDamage(proj),
                            getStoredDamageType(proj), engine, false);
                    if (spawned) {
                        proj.setCustomData(RIFT_LIGHTNING_FIRED_TAG, true);
                    }
                }
            }
        }

        this.fired.removeAll(remove);
    }

    // ==================== 电弧生成（唯一入口） ====================

    /**
     * 【唯一入口·一组电弧】生成“主电弧 1 道 + 额外电弧 RIFT_ARC_EXTRA_COUNT 道”，每条各带一颗地雷。
     * 主电弧朝向 = 武器当前朝向；额外电弧朝向 = 武器当前朝向 ± 随机偏差角度。
     * 本模组所有电弧都必须经过本方法（或它内部调用的 spawnSingleRiftArc），
     * 任何其它地方都不得再自行调用 spawnEmpArcVisual + spawnMine。
     *
     * @param weapon           触发开火的武器
     * @param weaponDamage     本次射击的武器基础伤害
     * @param weaponDamageType 本次射击的伤害类型
     * @param engine           战斗引擎
     * @param applyThrottle    是否启用“最小间隔限流”（只在开火瞬间调用时启用；
     *                         命中/超射程补发时不启用，避免真实命中被误吞）。
     *                         限流一次只判定一次，因此三道电弧不会被拆散。
     * @return true 表示本次调用确实生成了电弧（含地雷）
     */
    protected boolean fireRiftArcVolley(WeaponAPI weapon, float weaponDamage, DamageType weaponDamageType,
                                        CombatEngineAPI engine, boolean applyThrottle) {
        if (engine == null || weapon == null) return false;

        ShipAPI ship = weapon.getShip();
        if (ship == null || !ship.isAlive()) return false;

        // ---------- 1. 限流：整次射击只判定一次 ----------
        if (applyThrottle && !allowFiringNow(engine)) return false;

        // 主电弧的基准朝向 = 武器当前朝向（角度制，单位：度）
        float baseFacing = weapon.getCurrAngle();

        boolean firedAny = false;

        // ---------- 2. 主电弧：偏差 0 度，伤害倍数 1.0 ----------
        if (spawnSingleRiftArc(weapon, baseFacing, 0.0F, weaponDamage, weaponDamageType, 1.0F, engine)) {
            firedAny = true;
        }

        // ---------- 3. 额外电弧：朝向偏移 5~25 度，其余属性与主电弧一致 ----------
        for (int i = 0; i < RIFT_ARC_EXTRA_COUNT; i++) {
            float deviation = getExtraArcDeviation(i); // 带正负号的偏差角度（负=一侧，正=另一侧）
            if (spawnSingleRiftArc(weapon, baseFacing, deviation, weaponDamage, weaponDamageType,
                    RIFT_ARC_EXTRA_DAMAGE_MULT, engine)) {
                firedAny = true;
            }
        }

        return firedAny;
    }

    /**
     * 【兼容入口·只发主电弧】生成一条沿武器朝向的裂隙闪电（不含额外电弧）。
     * 保留此方法是为了让旧的调用点/子类仍可工作；需要三道电弧时请改用 fireRiftArcVolley(...)。
     *
     * @param weapon           触发开火的武器
     * @param weaponDamage     本次射击的武器基础伤害
     * @param weaponDamageType 本次射击的伤害类型
     * @param engine           战斗引擎
     * @param applyThrottle    是否启用“最小间隔限流”
     * @return true 表示确实生成了电弧（含地雷）
     */
    protected boolean fireSingleRiftArc(WeaponAPI weapon, float weaponDamage, DamageType weaponDamageType,
                                        CombatEngineAPI engine, boolean applyThrottle) {
        if (engine == null || weapon == null) return false;

        ShipAPI ship = weapon.getShip();
        if (ship == null || !ship.isAlive()) return false;

        if (applyThrottle && !allowFiringNow(engine)) return false;

        return spawnSingleRiftArc(weapon, weapon.getCurrAngle(), 0.0F, weaponDamage, weaponDamageType, 1.0F, engine);
    }

    /**
     * 限流判定：距上一次电弧生成是否已过 RIFT_ARC_MIN_INTERVAL 秒。
     *
     * @param engine 战斗引擎
     * @return true 表示允许生成（并会刷新时间戳）
     */
    protected boolean allowFiringNow(CombatEngineAPI engine) {
        if (engine == null) return false;
        float now = engine.getTotalElapsedTime(false);
        if (lastFacingArcTime >= 0.0F && (now - lastFacingArcTime) < RIFT_ARC_MIN_INTERVAL) {
            return false;
        }
        lastFacingArcTime = now;
        return true;
    }

    /**
     * 【本次修改·单条电弧生成】生成一条裂隙闪电（含碰撞伤害结算与终点地雷）。
     *
     * 与旧版的关键区别（“不吸附目标”）：
     *   旧版：电弧终点 = 目标的命中点（可能落在目标精确边界上、甚至被推到目标中心）→ 视觉吸附。
     *   新版：电弧终点 = 起点 +（武器朝向 + 偏差角度）× 射程，只由武器朝向与射程决定，
     *         与任何目标实体无关；目标的命中点只用于伤害结算（以及可选的旧手感开关）。
     *
     * @param weapon           触发开火的武器
     * @param baseFacing       基准朝向（度）——主电弧即武器当前朝向
     * @param deviationDeg     本条电弧相对基准朝向的偏差角度（度）：负=一侧，正=另一侧，0=主电弧
     * @param weaponDamage     本次射击的武器基础伤害
     * @param weaponDamageType 本次射击的伤害类型
     * @param damageMult       本条电弧的伤害倍数（主电弧 1.0，额外电弧取 RIFT_ARC_EXTRA_DAMAGE_MULT）
     * @param engine           战斗引擎
     * @return true 表示确实生成了电弧（含地雷）
     */
    protected boolean spawnSingleRiftArc(WeaponAPI weapon, float baseFacing, float deviationDeg,
                                         float weaponDamage, DamageType weaponDamageType,
                                         float damageMult, CombatEngineAPI engine) {
        if (engine == null || weapon == null) return false;

        ShipAPI ship = weapon.getShip();
        if (ship == null || !ship.isAlive()) return false;

        // ---------- 1. 起点：武器发射点（考虑硬点/炮塔的开火偏移） ----------
        Vector2f from = getWeaponFirePoint(weapon);
        if (from == null) return false;

        // ---------- 2. 本条电弧的实际朝向 = 武器朝向 + 偏差 ----------
        float arcFacing = baseFacing + deviationDeg;

        // 电弧最大长度：武器射程 + 冗余距离
        float maxRange = Math.max(1.0F, weapon.getRange() + RIFT_ARC_SLOP_RANGE);

        // ---------- 3. 【核心修改】射线终点只由“起点 + 朝向 × 射程”决定 ----------
        // 这里不查询任何目标实体，因此电弧的方向永远等于武器朝向，绝不会被目标“拉”过去。
        Vector2f rayEnd = getRayEnd(from, arcFacing, maxRange);

        // ---------- 4. 统一电弧基础伤害 ----------
        // 优先使用外部指定的覆盖值（> 0 时生效），否则用从武器读到的伤害；再乘本条电弧的伤害倍数
        float baseDamage = (RIFT_ARC_DAMAGE_OVERRIDE > 0.0F) ? RIFT_ARC_DAMAGE_OVERRIDE : weaponDamage;
        baseDamage *= Math.max(0.0F, damageMult);

        // 穿透伤害（战斗机 / 陨石 / 导弹，不显示数字）
        float passThroughDamage = baseDamage * RIFT_ARC_PASS_THROUGH_DAMAGE_MULT;
        // 舰船伤害（命中舰船时结算，正常归属 → 显示数字）
        float shipDamage = baseDamage * RIFT_ARC_SHIP_DAMAGE_MULT;

        // ---------- 5. 碰撞判定与伤害结算 ----------
        // 返回值只用于：① 可选的旧手感（视觉截断/地雷落点定位），
        //               ② 记录第一艘挡路舰船；
        // 它“不会”再改写电弧的朝向与射程。
        ArcCollisionResult result = resolveArcCollision(ship, from, rayEnd, maxRange,
                passThroughDamage, shipDamage, weaponDamageType, engine);

        // ---------- 6. 计算电弧视觉终点（默认 = 射线满射程点） ----------
        Vector2f arcEnd = calcArcVisualEnd(rayEnd, result);

        // ---------- 7. 生成电弧（纯视觉），并取回电弧“生长”时长 ----------
        float travelTime = spawnRiftArcVisual(ship, from, arcEnd, engine);

        // ---------- 8. 在电弧终点（或可选：被挡下的命中点）生成延迟地雷 ----------
        Vector2f mineLoc = arcEnd;
        if (RIFT_ARC_MINE_AT_BLOCKER_POINT && result.blockedRayPoint != null) {
            mineLoc = result.blockedRayPoint;
        }
        spawnMine(ship, mineLoc, travelTime * RIFT_ARC_MINE_DELAY_MULT);

        return true;
    }

    /**
     * 【本次新增】计算“本条电弧”的视觉终点。
     * 三种取值按优先级判断，且除“旧行为”一项外，终点永远严格位于朝向射线上：
     *   1) RIFT_ARC_SNAP_VISUAL_TO_HIT_POINT = true → 旧行为：用伤害结算用的命中点（会横向吸附，不推荐）；
     *   2) RIFT_ARC_VISUAL_STOP_AT_FIRST_SHIP = true 且有舰船挡路 → 在挡下的射线上截断；
     *   3) 默认 → 射线满射程点。
     *
     * @param rayEnd 射线原始终点（满射程，严格位于朝向射线上）
     * @param result 碰撞结算结果（可为 null）
     * @return 电弧视觉终点（总是新对象）
     */
    protected Vector2f calcArcVisualEnd(Vector2f rayEnd, ArcCollisionResult result) {
        if (result == null) return new Vector2f(rayEnd);

        // 1) 旧行为：终点吸附到命中点（可能被推到舰体内部，偏离武器朝向）
        if (RIFT_ARC_SNAP_VISUAL_TO_HIT_POINT && result.blockedShipPoint != null) {
            return new Vector2f(result.blockedShipPoint);
        }

        // 2) 折中：长度截断，但截断点严格位于射线上（方向依旧等于武器朝向）
        if (RIFT_ARC_VISUAL_STOP_AT_FIRST_SHIP && result.blockedRayPoint != null) {
            return new Vector2f(result.blockedRayPoint);
        }

        // 3) 默认：整条射线满射程
        return new Vector2f(rayEnd);
    }

    /**
     * 【本次新增】求“起点 + 朝向 × 长度”的射线终点。
     * 使用 LazyLib 的 VectorUtils.rotate（角度制，第三个参数是结果容器，可与被旋转对象相同）[cite:a19e5609-1]。
     *
     * @param from   起点
     * @param facing 朝向右（角度制，单位：度）
     * @param length 长度
     * @return 终点（总是新对象）
     */
    protected Vector2f getRayEnd(Vector2f from, float facing, float length) {
        Vector2f dir = new Vector2f(length, 0.0F);  // 朝右（0 度）方向、长度 = length 的向量
        VectorUtils.rotate(dir, facing, dir);       // 旋转到目标朝向
        return Vector2f.add(from, dir, null);       // 起点 + 方向 = 终点
    }

    /**
     * 【本次新增】求“第 index 道额外电弧”相对主电弧的偏差角度。
     * 偏差绝对值在 [RIFT_ARC_EXTRA_MIN_DEVIATION, RIFT_ARC_EXTRA_MAX_DEVIATION] 之间随机；
     * 方向由 RIFT_ARC_EXTRA_MIRRORED_SIDES 决定：
     *   true  → 一左一右（第 1 道偏一侧、第 2 道偏另一侧，保证分布对称）；
     *   false → 每道独立随机左右。
     * 角度符号说明：本游戏的角度为“顺时针为正”，因此负值 = 一个旋转方向、正值 = 相反方向。
     *
     * @param index 额外电弧序号（从 0 开始）
     * @return 带正负号的偏差角度（度）
     */
    protected static float getExtraArcDeviation(int index) {
        // 容忍配置写反（min > max）：先归一化再取值
        float min = Math.min(RIFT_ARC_EXTRA_MIN_DEVIATION, RIFT_ARC_EXTRA_MAX_DEVIATION);
        float max = Math.max(RIFT_ARC_EXTRA_MIN_DEVIATION, RIFT_ARC_EXTRA_MAX_DEVIATION);
        if (min < 0.0F) min = 0.0F;
        if (max < 0.0F) max = 0.0F;

        // 偏差绝对值：5 ~ 25 度之间随机
        float absDeviation = min + (float) Math.random() * (max - min);

        // 偏差方向
        float side;
        if (RIFT_ARC_EXTRA_MIRRORED_SIDES) {
            side = (index % 2 == 0) ? -1.0F : 1.0F; // 一左一右
        } else {
            side = (Math.random() < 0.5) ? -1.0F : 1.0F; // 每道独立随机
        }

        return absDeviation * side;
    }

    /**
     * 电弧路径的碰撞判定与伤害结算。
     * 【本次修改】本方法不再返回“电弧应该画到哪里”，而是返回一个结果对象，包含：
     *   - rayEnd         ：射线原始终点（满射程点，严格位于朝向射线上）；
     *   - blockedRayPoint：被舰船挡下时，严格位于射线上的命中点（供可选的视觉截断/地雷定位使用）；
     *   - blockedShipPoint：被舰船挡下时，用于伤害结算的命中点（可能被推进舰体内，绝不用于画电弧）。
     * 侧作用（共有两类伤害）：
     *   - 穿透伤害：对战斗机 / 陨石 / 导弹，属于“可穿透类别”的目标结算（不显示数字）；
     *   - 舰船伤害：对挡路的普通舰船结算（正常归属 → 显示数字）。
     *
     * @param ship              电弧所属舰船（用于阵营判定与伤害来源）
     * @param from              电弧起点
     * @param rayEnd            射线原始终点（满射程点）
     * @param maxRange          电弧最大长度
     * @param passThroughDamage 穿透伤害数值（武器伤害 × 穿透倍数）
     * @param shipDamage        舰船伤害数值（武器伤害 × 舰船倍数）
     * @param damageType        伤害类型
     * @param engine            战斗引擎
     * @return 碰撞结算结果
     */
    protected ArcCollisionResult resolveArcCollision(ShipAPI ship, Vector2f from, Vector2f rayEnd, float maxRange,
                                                     float passThroughDamage, float shipDamage,
                                                     DamageType damageType, CombatEngineAPI engine) {
        ArcCollisionResult result = new ArcCollisionResult();
        result.rayEnd = new Vector2f(rayEnd);

        // 取出路径范围内的所有实体：包含舰船、投射物（含导弹）、陨石
        List<CombatEntityAPI> entitiesToCheck = CombatUtils.getEntitiesWithinRange(from, maxRange);
        if (entitiesToCheck.isEmpty()) {
            return result;
        }

        // 过滤：排除空引用、已失效实体、自身、以及同阵营目标（避免误伤己方战斗机与己方导弹）
        for (int i = entitiesToCheck.size() - 1; i >= 0; i--) {
            CombatEntityAPI entity = entitiesToCheck.get(i);
            if (entity == null || entity.isExpired() || entity == ship || entity.getOwner() == ship.getOwner()) {
                entitiesToCheck.remove(i);
            }
        }

        // 按到起点的距离由近到远排序，保证“先被电弧扫到”的目标先结算
        entitiesToCheck.sort((o1, o2) -> {
            float d1 = Misc.getDistance(from, o1.getLocation());
            float d2 = Misc.getDistance(from, o2.getLocation());
            return Float.compare(d1, d2);
        });

        for (CombatEntityAPI entity : entitiesToCheck) {
            // 粗筛：线段是否可能穿过该实体的碰撞圆
            if (!CollisionUtils.getCollides(from, rayEnd, entity.getLocation(), entity.getCollisionRadius())) {
                continue;
            }

            // 求命中点：优先使用 LazyLib 的“精确边界”交点（无边界时它会退回碰撞圆判定，
            // 甚至直接返回实体中心；线段起点已在目标内部时返回起点），拿不到时再用线段×碰撞圆解析求交兜底。
            Vector2f hitPoint = getImpactPoint(from, rayEnd, entity);

            // ---------- 情况一：战斗机 / 陨石 / 导弹 ----------
            // 电弧不与它们发生碰撞（不会停下、不会被改向），
            // 但路径上的它们会被“武器伤害 × 穿透倍数”击中。
            // 伤害归属传 null（见 applyPassThroughDamage），因此“不显示伤害数字”。
            if (isPassThroughEntity(entity)) {
                if (passThroughDamage > 0.0F) {
                    applyPassThroughDamage(engine, ship, entity, ensurePointInside(entity, hitPoint),
                            passThroughDamage, damageType);
                }
                continue; // 继续向前扫，伤害延伸不变
            }

            // ---------- 情况二：普通舰船（非战斗机） ----------
            if (entity instanceof ShipAPI) {
                ShipAPI targetShip = (ShipAPI) entity;

                // 把命中点修正到目标碰撞圆内：applyDamage 的 point 必须落在舰船精灵内，
                // 否则装甲不会正确参与结算。
                Vector2f shipHitPoint = ensurePointInside(targetShip, hitPoint);

                // 对挡路的舰船造成伤害（正常归属 → 引擎会显示伤害数字）
                if (targetShip.isAlive() && shipDamage > 0.0F) {
                    applyShipDamage(engine, ship, targetShip, shipHitPoint, shipDamage, damageType);
                }

                // 记录“第一艘”挡路舰船（已按距离排序，因此这就是最近的那一艘）
                if (result.blockedShipPoint == null) {
                    // 结算用的命中点（可能被推进舰体内，仅用于伤害/可选旧行为）
                    result.blockedShipPoint = new Vector2f(shipHitPoint);
                    // 严格位于射线上、用于可选视觉截断与地雷定位的点（投影 + 长度夹取，绝不横向偏移）
                    result.blockedRayPoint = projectOntoRay(from, rayEnd, hitPoint);
                }

                if (RIFT_ARC_STOPPED_BY_SHIPS) {
                    // 伤害延伸被舰船挡住：结束本轮扫描（电弧的“长相”不受影响）
                    break;
                }
                // 不阻挡时贯穿：继续向前扫，后续舰船同样会吃到伤害
                continue;
            }

            // ---------- 情况三：其他类型（例如普通弹丸） ----------
            // 既不属于可穿透类别，也不是舰船：完全忽略，既不停下也不改向。
        }

        return result;
    }

    /**
     * 【本次新增】把任意点“投影”到射线上，并把投影长度夹取到 [0, 射线长度]。
     * 用途：即使命中点来自“目标中心”这类偏离射线的结果，最终得到的
     *      blockedRayPoint 也一定严格落在武器朝向射线上，因此可选的视觉截断与地雷落点
     *      不会产生任何横向吸附。
     *
     * @param from   射线起点
     * @param rayEnd 射线终点
     * @param point  待投影的点
     * @return 投影点（总是新对象）
     */
    protected Vector2f projectOntoRay(Vector2f from, Vector2f rayEnd, Vector2f point) {
        if (from == null || rayEnd == null) {
            return (point == null) ? new Vector2f() : new Vector2f(point);
        }
        if (point == null) return new Vector2f(rayEnd);

        // 射线方向与长度
        float dx = rayEnd.x - from.x;
        float dy = rayEnd.y - from.y;
        float rayLength = (float) Math.sqrt(dx * dx + dy * dy);
        if (rayLength <= 0.0001F) return new Vector2f(rayEnd);

        // 单位方向向量
        float ux = dx / rayLength;
        float uy = dy / rayLength;

        // 点在射线上的投影长度（t 可能为负或超出射程）
        float vx = point.x - from.x;
        float vy = point.y - from.y;
        float t = vx * ux + vy * uy;

        // 夹取到 [0, 射线长度]
        if (t < 0.0F) t = 0.0F;
        if (t > rayLength) t = rayLength;

        return new Vector2f(from.x + ux * t, from.y + uy * t);
    }

    /**
     * 生成裂隙闪电的纯视觉电弧。
     * 使用 spawnEmpArcVisual：它只负责画电弧（第2、4个参数是“锚点实体”，可传 null），
     * 不参与任何碰撞判定与伤害结算，因此“碰撞与伤害”完全由 resolveArcCollision 决定；
     * 电弧画到哪里则完全由传入的 to 决定（本次修改后恒为射线上的点）。
     *
     * @param ship   电弧归属舰船
     * @param from   电弧起点
     * @param to     电弧终点（严格位于电弧自己的朝向射线上）
     * @param engine 战斗引擎
     * @return 电弧“生长”时长（秒），供地雷延迟使用
     */
    protected float spawnRiftArcVisual(ShipAPI ship, Vector2f from, Vector2f to, CombatEngineAPI engine) {
        // 配置EMP电弧参数（控制闪电视觉表现）；每条电弧各用一份，互不干扰
        EmpArcEntityAPI.EmpArcParams params = new EmpArcEntityAPI.EmpArcParams();
        params.segmentLengthMult = 5F;      // 闪电段长度倍数
        params.maxZigZagMult = 0.5F;        // 最大锯齿倍数
        params.zigZagReductionFactor = 5F;  // 锯齿衰减因子
        params.fadeOutDist = 200F;          // 末端淡出距离
        params.minFadeOutMult = 1F;         // 最小淡出倍数
        params.flickerRateMult = 0.3F;      // 闪烁频率倍数
        params.brightSpotFullFraction = 0.5F; // 亮点占比
        params.movementDurOverride = 0.5F;  // 电弧“生长”时长

        EmpArcEntityAPI arc = engine.spawnEmpArcVisual(
                from, ship,                    // 起点位置与起点锚点（归属舰船）
                to, null,                      // 终点位置与终点锚点（无目标 → 纯视觉，不做碰撞）
                RIFT_ARC_THICKNESS,            // 电弧厚度
                RIFT_LIGHTNING_COLOR,          // 电弧边缘颜色
                new Color(255, 255, 255, 255), // 电弧核心颜色（白色）
                params                         // 自定义参数
        );

        if (arc != null) {
            arc.setCoreWidthOverride(RIFT_ARC_CORE_WIDTH); // 核心宽度
            arc.setRenderGlowAtStart(false);               // 起点不渲染光晕
            arc.setFadedOutAtStart(true);                  // 起点淡出（从遮蔽中渐显）
            arc.setSingleFlickerMode(true);                // 单次闪烁模式
        }

        return params.movementDurOverride; // 返回生长时长
    }

    // ==================== 地雷 ====================

    /**
     * 延迟生成裂隙闪电地雷（保持不变，供内部与外部脚本使用）
     * 本次修改后，三道电弧会各自调用本方法一次，因此一次射击会生成三颗地雷
     * （除非把 RIFT_ARC_EXTRA_COUNT 设为 0）。
     *
     * @param source  地雷所属舰船
     * @param mineLoc 地雷生成位置（默认 = 本条电弧的终点；可配置为被挡下的命中点）
     * @param delay   延迟时间（秒）
     */
    public static void spawnMine(ShipAPI source, Vector2f mineLoc, float delay) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null || mineLoc == null) return;

        // 生成“VIII_lightning_minelayer”（投射物ID，不要翻译）类型的地雷投射物
        MissileAPI mine = (MissileAPI) engine.spawnProjectile(
                source, null,
                "VIII_lightning_minelayer",
                mineLoc,
                (float) Math.random() * 360.0F, // 随机朝向
                null // 初始速度为0
        );

        if (mine == null) return;

        // 应用伤害修饰器（适配源舰船的伤害加成；无武器时用能量武器类型代替）
        if (source != null) {
            engine.applyDamageModifiersToSpawnedProjectileWithNullWeapon(
                    source, WeaponType.ENERGY, false, mine.getDamage()
            );
        }

        float fadeInTime = 0.05F; // 地雷淡入时间
        mine.getVelocity().scale(0.0F); // 强制地雷静止
        mine.fadeOutThenIn(fadeInTime); // 先淡出再淡入（隐藏生成过程）

        // 设置地雷的存活时间（延迟后爆炸）
        float liveTime = Math.max(delay, 0.0F);
        mine.setFlightTime(mine.getMaxFlightTime() - liveTime); // 调整飞行时间以控制爆炸时机
        mine.addDamagedAlready(source); // 避免地雷伤害自身
        mine.setNoMineFFConcerns(true); // 关闭友军误伤顾虑（地雷不伤害友军）

        // 延迟极短时直接爆炸
        if (liveTime <= 0.016F) {
            mine.explode();
        }
    }

    // ==================== 判定与伤害 ====================

    /**
     * 判断实体是否属于“电弧不碰撞、但会被穿透伤害”的类别。
     * 战斗机 / 导弹：按碰撞类别判断（CollisionClass.FIGHTER / MISSILE_FF / MISSILE_NO_FF）；
     * 陨石（小行星）：按“战斗层”类型判断——必须用 com.fs.starfarer.api.combat.CombatAsteroidAPI，
     *              战役层的 com.fs.starfarer.api.campaign.AsteroidAPI 在战斗中永远不会命中，属于常见踩坑；
     *              这里额外用 CollisionClass.ASTEROID 兜底，双保险。
     *
     * @param entity 待判断的实体
     * @return true 表示该实体应被电弧穿透并受到高额伤害
     */
    protected boolean isPassThroughEntity(CombatEntityAPI entity) {
        if (entity == null) return false;
        // 陨石 / 小行星（战斗层接口）
        if (entity instanceof CombatAsteroidAPI) {
            return true;
        }
        // 陨石 / 小行星（碰撞类别兜底）
        if (entity.getCollisionClass() == CollisionClass.ASTEROID) {
            return true;
        }
        // 战斗机（含无人机）、导弹（含诱饵弹）
        return RIFT_ARC_PASS_THROUGH_CLASSES.contains(entity.getCollisionClass());
    }

    /**
     * 对“被电弧击中的舰船”造成伤害，并让它显示伤害数字。
     * 伤害数字的完整逻辑（重点）：
     * 1. 这里把伤害**正常归属**给发射舰船（source = 本舰），
     *    于是引擎会按原版规则自动弹出伤害数字（玩家舰船相关的伤害会自动显示）；
     * 2. 若引擎自身不会弹数字（例如发射舰不是玩家舰船，或伤害归属缺失），
     *    则在 RIFT_ARC_FORCE_DAMAGE_FLOATY 开启时手动补一个数字（addFloatingDamageText），
     *    保证“电弧伤害可见”；若引擎自己会弹，则不重复补，避免出现两个数字；
     * 3. 手动补的数字显示的是“名义伤害”（我们传入的数值），不是护盾/装甲结算后的实际伤害，
     *    因为 applyDamage 没有返回值，引擎也没有提供查询接口；
     * 4. RIFT_ARC_IGNORE_FLOATY_SETTING 为 false 时，会尊重游戏设置里的“显示伤害数字”开关
     *    （SettingsAPI.isShowDamageFloaties），玩家关掉就直接不补。
     *
     * @param engine     战斗引擎
     * @param source     伤害来源舰船（发射电弧的舰船，正常归属）
     * @param target     被电弧击中的舰船
     * @param point      命中点（调用前已被修正到目标碰撞圆内）
     * @param damage     伤害数值
     * @param damageType 伤害类型
     */
    protected void applyShipDamage(CombatEngineAPI engine, ShipAPI source, ShipAPI target,
                                   Vector2f point, float damage, DamageType damageType) {
        if (engine == null || target == null || damage <= 0.0F) return;

        // 再次确保命中点落在目标碰撞圆内：applyDamage 的 point 必须落在舰船精灵内，装甲才会被正确计算
        Vector2f hitPoint = ensurePointInside(target, point);

        // 正常归属的伤害结算：引擎会按原版规则自动给出伤害数字（玩家舰船相关的伤害）
        engine.applyDamage(
                source,                    // 伤害修饰参数（传发射舰船，便于监听器识别来源；也可按需传 weapon 或 null）
                target,                    // 受击舰船
                hitPoint,                  // 命中点（引擎绝对坐标，必须落在舰船精灵内）
                damage,                    // 伤害数值
                damageType,                // 伤害类型
                0.0F,                      // EMP 伤害
                false,                     // 是否完全无视护盾（false = 由护盾正常吸收）
                false,                     // 命中护盾时是否转化为软幅能
                source,                    // 伤害最终归属对象（应为 ShipAPI，可计入击杀/伤害统计）
                RIFT_ARC_PLAY_DAMAGE_SOUND // 是否播放命中音效
        );

        // ---------- 伤害数字兜底：保证“电弧伤害可见” ----------
        if (!RIFT_ARC_FORCE_DAMAGE_FLOATY) return;      // 未启用兜底 → 完全依赖引擎自身规则
        if (source == null) return;                     // 没有归属方时无法构造数字
        if (Misc.shouldShowDamageFloaty(source, target)) return; // 引擎自己会弹 → 不重复补
        if (!RIFT_ARC_IGNORE_FLOATY_SETTING
                && !Global.getSettings().isShowDamageFloaties()) {
            return;                                     // 尊重玩家设置：关了“显示伤害数字”就不补
        }

        engine.addFloatingDamageText(
                hitPoint,                          // 数字出现的位置
                damage,                            // 显示的伤害数值（名义伤害）
                RIFT_ARC_DAMAGE_FLOATY_COLOR,      // 数字颜色
                target,                            // 数字跟随的实体
                source                             // 伤害来源
        );
    }

    /**
     * 造成穿透伤害（战斗机 / 陨石 / 导弹）。
     * 关于“不显示伤害数字”（本体要求，务必保持）：
     * applyDamage 的最后一个布尔参数是 playSound（是否播放命中音效），并不是“是否显示伤害数字”；
     * 伤害浮动数字由引擎根据 SettingsAPI.isShowDamageFloaties() 与伤害归属共同决定，
     * 公开 API 没有逐次伤害的开关。因此这里的做法是：
     * - 把伤害归属传 null（引擎缺少归属方时不会弹出数字）——由 RIFT_ARC_ATTRIBUTE_PASS_THROUGH_TO_SOURCE 控制；
     * - 并且绝不调用 addFloatingDamageText，避免主动补数字；
     * 另外该方法只接受 ShipAPI 目标，因此陨石与导弹本来就不会弹数字，只有战斗机（也是 ShipAPI）需要注意。
     * 若把 RIFT_ARC_ATTRIBUTE_PASS_THROUGH_TO_SOURCE 改为 true，伤害会正常归属发射舰（可统计），
     * 但穿透目标也会开始显示伤害数字，与本体要求冲突。
     *
     * @param engine     战斗引擎
     * @param source     伤害来源舰船
     * @param target     受击实体
     * @param point      受击位置（调用前已被修正到目标碰撞圆内）
     * @param damage     伤害数值
     * @param damageType 伤害类型
     */
    protected void applyPassThroughDamage(CombatEngineAPI engine, ShipAPI source, CombatEntityAPI target,
                                          Vector2f point, float damage, DamageType damageType) {
        // 伤害归属：默认传 null，避免弹出伤害数字（这也是“穿透目标不显示数字”的实现手段）
        Object attribution = RIFT_ARC_ATTRIBUTE_PASS_THROUGH_TO_SOURCE ? source : null;

        engine.applyDamage(
                attribution,                     // 伤害修饰参数（可为 null）
                target,                          // 受击实体
                ensurePointInside(target, point),// 受击位置（必须以引擎绝对坐标给出，且尽量落在目标内）
                damage,                          // 伤害数值
                damageType,                      // 伤害类型
                0.0F,                            // EMP 伤害
                false,                           // 是否完全无视护盾
                false,                           // 命中护盾时是否转化为软幅能
                attribution,                     // 伤害最终归属对象（可为 null；为 null 时通常不显示数字）
                RIFT_ARC_PLAY_DAMAGE_SOUND       // 是否播放命中音效
        );
        // 注意：这里刻意不调用 addFloatingDamageText —— 穿透目标必须保持“无伤害数字”。
    }

    // ==================== 几何与数据工具 ====================

    /**
     * 获取武器发射点。
     * 优先使用武器规格中定义的硬点/炮塔开火偏移，没有偏移时退回武器自身位置。
     *
     * @param weapon 目标武器
     * @return 世界坐标下的发射点（总是返回新对象，避免意外修改引擎内部向量）
     */
    protected Vector2f getWeaponFirePoint(WeaponAPI weapon) {
        if (weapon == null) return null;

        if (!weapon.getSpec().getHardpointFireOffsets().isEmpty()) {
            // 硬点武器：使用第一个硬点开火偏移
            Vector2f fireOffset = new Vector2f(weapon.getSpec().getHardpointFireOffsets().get(0));
            Vector2f result = new Vector2f();
            Vector2f.add(weapon.getLocation(), Misc.rotateAroundOrigin(fireOffset, weapon.getCurrAngle()), result);
            return result;
        } else if (!weapon.getSpec().getTurretFireOffsets().isEmpty()) {
            // 炮塔武器：使用第一个炮塔开火偏移
            Vector2f fireOffset = new Vector2f(weapon.getSpec().getTurretFireOffsets().get(0));
            Vector2f result = new Vector2f();
            Vector2f.add(weapon.getLocation(), Misc.rotateAroundOrigin(fireOffset, weapon.getCurrAngle()), result);
            return result;
        }
        // 无偏移定义：直接使用武器位置
        return new Vector2f(weapon.getLocation());
    }

    /**
     * 求电弧射线与目标实体的命中点（**仅用于伤害结算与可选的旧手感开关**，不再用于决定电弧画到哪里）。
     * 优先使用 LazyLib 的精确边界求交，保证命中点尽量落在舰船精灵上（装甲结算需要）：
     * - 目标有精确边界 → 返回边界上的最近交点；
     * - 目标没有边界（例如导弹）→ LazyLib 会退回碰撞圆判定，并直接返回**实体中心**；
     * - 线段起点已经完全位于目标内部（贴脸开火）→ 直接返回起点。
     * 拿不到交点时再用“线段 × 碰撞圆”的解析算法兜底，仍失败则退回目标中心。
     *
     * @param from     射线起点
     * @param endpoint 射线终点
     * @param entity   目标实体
     * @return 命中点（总是返回新对象）
     */
    protected Vector2f getImpactPoint(Vector2f from, Vector2f endpoint, CombatEntityAPI entity) {
        if (entity == null) return new Vector2f(from);

        // 1) 优先 LazyLib：精确边界优先，无边界时退回碰撞圆（该情况下它会返回实体中心）
        Vector2f p = CollisionUtils.getCollisionPoint(from, endpoint, entity);
        if (p != null) {
            return new Vector2f(p);
        }

        // 2) 兜底：线段 × 碰撞圆的解析求交
        p = getCollisionRayCircle(from, endpoint, entity.getLocation(), entity.getCollisionRadius());
        if (p != null) {
            return p;
        }

        // 3) 最后兜底：目标中心（保证 applyDamage 的点一定有效）
        return new Vector2f(entity.getLocation());
    }

    /**
     * 确保命中点落在目标碰撞圆内。
     * applyDamage 的 point 必须落在舰船（当前位置与朝向下）的精灵范围内，装甲才会被正确计入，
     * 因此当解析出来的点落在碰撞圆外（例如线段只是擦过碰撞圆）或为空时，
     * 把点朝目标中心推进一半距离；仍无法推进时直接使用目标中心。
     * 注意：本方法的结果**只用于伤害结算**，绝不用于决定电弧的绘制终点
     *      （否则就会重新出现“电弧被推向目标中心”的吸附观感）。
     *
     * @param entity 目标实体
     * @param point  原始命中点（可为 null）
     * @return 修正后的命中点（总是返回新对象）
     */
    protected Vector2f ensurePointInside(CombatEntityAPI entity, Vector2f point) {
        if (entity == null) return (point == null) ? new Vector2f() : new Vector2f(point);

        Vector2f p = (point == null) ? new Vector2f(entity.getLocation()) : new Vector2f(point);

        // 已在碰撞圆内 → 直接使用（绝大多数情况）
        if (CollisionUtils.isPointWithinCollisionCircle(p, entity)) {
            return p;
        }

        // 不在圆内：朝目标中心推进一半距离
        Vector2f center = new Vector2f(entity.getLocation());
        Vector2f dir = Vector2f.sub(center, p, null);
        float len = dir.length();
        if (len > 0.001F) {
            dir.scale((len * 0.5F) / len);
            Vector2f.add(p, dir, p);
        } else {
            p.set(center);
        }
        return p;
    }

    /**
     * 射线与圆的求交（与参考实现 GWC_BasePrismSpearBeamEffect 中的算法一致）。
     *
     * @param start  线段起点
     * @param end    线段终点
     * @param circle 圆心
     * @param radius 圆半径
     * @return 线段与圆的交点（取离起点较近的那个）；无交点时返回 null
     */
    protected Vector2f getCollisionRayCircle(Vector2f start, Vector2f end, Vector2f circle, float radius) {
        float x1 = start.x - circle.x;
        float x2 = end.x - circle.x;
        float y1 = start.y - circle.y;
        float y2 = end.y - circle.y;

        float dx = x2 - x1;
        float dy = y2 - y1;
        float dr_sqrd = dx * dx + dy * dy;
        if (dr_sqrd <= 0.000001F) {
            return null; // 起点与终点重合，无法求交
        }
        float D = x1 * y2 - x2 * y1;
        float delta = radius * radius * dr_sqrd - D * D;

        if (delta < 0.0F) {
            // 无交点
            return null;
        } else if (delta > 0.0F) {
            // 两个交点：取离起点较近的那个
            float x_sub = Math.signum(dy) * dx * (float) Math.sqrt(delta);
            float x_a = (D * dy + x_sub) / dr_sqrd;
            float x_b = (D * dy - x_sub) / dr_sqrd;

            float y_sub = Math.abs(dy) * (float) Math.sqrt(delta);
            float y_a = (-D * dx + y_sub) / dr_sqrd;
            float y_b = (-D * dx - y_sub) / dr_sqrd;

            float dax = x_a - x1;
            float dbx = x_b - x1;
            float day = y_a - y1;
            float dby = y_b - y1;
            float dist_a_sqrt = dax * dax + day * day;
            float dist_b_sqrt = dbx * dbx + dby * dby;

            return dist_a_sqrt < dist_b_sqrt
                    ? new Vector2f(x_a + circle.x, y_a + circle.y)
                    : new Vector2f(x_b + circle.x, y_b + circle.y);
        } else {
            // 相切：只有一个交点
            float x = D * dy / dr_sqrd;
            float y = -D * dx / dr_sqrd;
            return new Vector2f(x + circle.x, y + circle.y);
        }
    }

    /**
     * 从实体自定义数据中取回“发射本次电弧的武器”。
     *
     * @param entity 投射物（或任何带有自定义数据的战斗实体）
     * @return 源武器；不存在时返回 null
     */
    protected static WeaponAPI getStoredWeapon(CombatEntityAPI entity) {
        if (entity == null) return null;
        Object o = entity.getCustomData().get(RIFT_LIGHTNING_SOURCE_WEAPON);
        return (o instanceof WeaponAPI) ? (WeaponAPI) o : null;
    }

    /**
     * 从实体自定义数据中取回“本次射击的武器基础伤害”
     * （投射物自身的伤害已被清零，所以必须读这里另存的数值）。
     *
     * @param entity 投射物（或任何带有自定义数据的战斗实体）
     * @return 伤害数值；不存在时返回 0
     */
    protected static float getStoredDamage(CombatEntityAPI entity) {
        if (entity == null) return 0.0F;
        Object o = entity.getCustomData().get(RIFT_ARC_DAMAGE_TAG);
        return (o instanceof Float) ? ((Float) o) : 0.0F;
    }

    /**
     * 从实体自定义数据中取回“本次射击的伤害类型”。
     *
     * @param entity 投射物（或任何带有自定义数据的战斗实体）
     * @return 伤害类型；不存在时返回能量伤害
     */
    protected static DamageType getStoredDamageType(CombatEntityAPI entity) {
        if (entity == null) return DamageType.ENERGY;
        Object o = entity.getCustomData().get(RIFT_ARC_DAMAGE_TYPE_TAG);
        return (o instanceof DamageType) ? (DamageType) o : DamageType.ENERGY;
    }

    /**
     * 内部类：存储已发射的闪电投射物数据（仅在“原版时机”模式下使用）
     */
    public static class FiredLightningProjectile {
        public DamagingProjectileAPI projectile; // 关联的投射物实例
    }

    /**
     * 【本次新增】内部类：单条电弧的碰撞结算结果。
     * 把“电弧画到哪里”与“谁挨打”彻底分开，是“不吸附目标”的关键数据结构。
     */
    public static class ArcCollisionResult {
        // 射线原始终点（满射程点，严格位于朝向射线上）
        public Vector2f rayEnd = null;
        // 被舰船挡下时，严格位于射线上的命中点（供可选的视觉截断与地雷定位使用；无阻挡为 null）
        public Vector2f blockedRayPoint = null;
        // 被舰船挡下时，用于伤害结算的命中点（可能被推进舰体内，绝不用于画电弧；无阻挡为 null）
        public Vector2f blockedShipPoint = null;
    }
}