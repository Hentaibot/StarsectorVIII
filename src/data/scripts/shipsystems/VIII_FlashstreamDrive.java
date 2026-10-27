package data.scripts.shipsystems;// 包路径：模组的舰船系统脚本目录，实现“裂光闪流驱动（Flashstream Drive）”系统

// ==================== 导入 ====================
// 导入游戏全局工具类：用于获取战斗引擎等全局对象
import com.fs.starfarer.api.Global;
// 导入战斗引擎接口：用于读写“战场时间流速”、获取玩家驾驶的舰船
import com.fs.starfarer.api.combat.*;
// 导入战斗实体接口：用于从舰船属性反查舰船本体（stats.getEntity()）
// 导入可变舰船属性接口：系统的所有数值加成都写在这里
// 导入护盾接口：用于读取护盾类型、强制收盾、以及可选的“彻底禁用”
// 导入舰船接口：用于判断是否玩家驾驶、是否存活、是否是残骸、取护盾等
// 导入舰船指令枚举：本系统需要拦截“切换护盾 / 相位斗篷”指令
// 导入舰船系统脚本基类：继承它并覆盖 apply / unapply / getStatusData 即可
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
// 导入舰船系统脚本接口：用于引用其嵌套的 State（状态）与 StatusData（HUD 状态条目）
import com.fs.starfarer.api.plugins.ShipSystemStatsScript;
import org.lwjgl.util.vector.Vector2f;
import org.magiclib.util.MagicRender;

// 导入集合类：登记已写入的“战场时间流速”键，便于兜底清理
import java.awt.*;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 裂光闪流驱动（舰船系统脚本）
 * 系统开启时，对所属舰船生效的加成为：
 *   1. 舰船时间流速 × 8（舰船在战斗时间轴上快 8 倍）；
 *   2. 最大航速 +100%；
 *   3. 机动性 +100%（转向速度、转向加速度、加速度、减速度四项同时提升）；
 *   4. 幅能耗散 +50%；
 *   5. 能量武器射速 +300%（常规射速的 4 倍）；
 *   6. 武器射程 +25%（能量 / 实弹 / 导弹；光束是否也吃这份加成见开关）；
 *   7. 【新增】禁止使用护盾：强制收盾 + 拦截开盾指令（可选叠加“彻底禁用”）；
 *   8. 【玩家驾驶时额外生效】把“战场时间流速”按同样的倍率放慢（1/8）。
 * 关于第 8 条（本系统最核心的手感设计）：
 *   单位在屏幕上的实际节奏 = 舰船时间流速 × 战场时间流速。
 *   - 玩家驾驶：8 × 1/8 = 1 倍 → 玩家操控的手感不变；
 *     其余所有单位 = 1 × 1/8 → 全部慢 8 倍，于是形成“子弹时间”观感。
 *   - AI 驾驶：只加舰船时间流速，不动战场时间流速 → AI 舰船真的比全场快 8 倍。
 *   （若觉得 AI 侧过强，可自行把 AI 的倍率降低后再调用，或给 AI 单独一套数值。）
 * 关于第 7 条（本次新增）：
 *   实现分三道防线，层层加固：
 *     ① 每帧强制收盾：只要护盾处于展开状态就立即关闭；
 *     ② 拦截本帧的“切换护盾 / 相位斗篷”指令：让开盾操作根本不生效；
 *     ③ 可选（默认关闭）：把护盾类型改写为 NONE，连 HUD 与判定一起消失。
 *   关键避坑：
 *     - 拦截指令②作用的指令同时管护盾与相位斗篷，因此必须先识别 ShieldType.PHASE
 *       并直接跳过，否则会把相位舰的相位功能一起禁掉；
 *     - 系统脚本实例是“按系统规格共享”的（同一系统的所有舰船共用同一个脚本对象），
 *       因此任何“每艘船各一份”的状态都不能存成普通实例字段，本类改用
 *       ship.getId() 现算键 / 静态 Map 以 shipId 为键来保存。
 * 其它实现要点与坑：
 *   - 所有数值加成统一乘以 effectLevel（启动 0→1、生效 1、关闭 1→0），起停因此是平滑的；
 *   - 每项加成使用“id + 固定后缀”作为修饰键，unapply 时按同样的键精确移除；
 *   - 战场时间流速是全局属性，键里带 ship.getId()，避免同型号多舰互相覆盖/互相误删；
 *   - 舰船属性通过 stats.getEntity() 反查（该方法只在战斗中返回非 null）。
 */
public class VIII_FlashstreamDrive extends BaseShipSystemScript {

    // ==================== 可调参数：效果数值 ====================

    // 舰船自身的时间流速倍率：10 表示全效时是 10 倍
    // 提醒：该属性会放大舰船的一切行为，因此下面的百分比加成在观感上会被再放大 10 倍
    public static float TIME_FLOW_MULT = 50.0F;

    // 最大航速加成（百分比）：100 = +100%（即翻倍）
    public static float MAX_SPEED_BONUS_PERCENT = 100.0F;

    // 机动性加成（百分比）：100 = +100%，同时作用于转向与加减速四项
    public static float MANEUVER_BONUS_PERCENT = 100.0F;

    // 幅能耗散加成（百分比）：50 = +50%
    public static float FLUX_DISSIPATION_BONUS_PERCENT = 50.0F;

    // 能量武器射速加成（百分比）：300 = 常规射速的 4 倍
    public static float ENERGY_ROF_BONUS_PERCENT = 200.0F;

    // 武器射程加成（百分比）：25 = +25%
    public static float WEAPON_RANGE_BONUS_PERCENT = 25.0F;

    // 残影生成计时器
    private float renderTimer = 0f;

    // 是否把射程加成同时写给“光束武器专用射程加成”这个独立属性：
    //   能量 / 实弹 / 导弹三种射程加成通常已经覆盖对应类型的光束武器，
    //   默认关闭，避免同一门光束被叠加两次（变成 +50%）。
    //   若实测中发现光束武器没吃到 +25%，再把它改成 true。
    public static boolean ALSO_APPLY_BEAM_RANGE_BONUS = false;

    // 玩家驾驶时是否额外放慢战场时间流速（需求要求的“子弹时间”）
    public static boolean SLOW_BATTLE_TIME_FOR_PLAYER = true;

    // 【新增】系统开启时是否禁止使用护盾（主开关）
    public static boolean DISABLE_SHIELD_WHILE_ACTIVE = true;

    // 【新增】是否使用“彻底禁用”模式（把护盾类型改写为 NONE）
    //   启用后：护盾的 HUD 提示、展开视觉、伤害判定都会一并消失，效果最干净；
    //   风险：改写了护盾类型，必须在系统结束/舰船移除时恢复原类型；
    //         且恢复动作依赖能取到护盾对象，因此需要配合本类的静态 Map 使用。
    //   建议：先只依赖默认的“强制收盾 + 拦截指令”两道防线，确认手感后再决定是否开启本项。
    public static boolean HARD_DISABLE_SHIELD = false;

    // ==================== 修饰键后缀 ====================
    // 说明：真正写入引擎的键 = 引擎传入的 id + 下面的后缀。
    //      给每一项分配独立后缀，unapply 时才能逐项精确移除。
    protected static final String KEY_TIME_FLOW = "_time_flow";        // 舰船时间流速
    protected static final String KEY_MAX_SPEED = "_max_speed";        // 最大航速
    protected static final String KEY_TURN_RATE = "_turn_rate";        // 最大转向速度
    protected static final String KEY_TURN_ACCEL = "_turn_accel";      // 转向加速度
    protected static final String KEY_ACCEL = "_accel";                // 前进加速度
    protected static final String KEY_DECEL = "_decel";                // 减速（刹车）加速度
    protected static final String KEY_FLUX_DISS = "_flux_diss";        // 幅能耗散
    protected static final String KEY_ENERGY_ROF = "_energy_rof";      // 能量武器射速
    protected static final String KEY_RANGE = "_range";                // 武器射程
    protected static final String KEY_BATTLE_TIME = "_battle_time";    // 战场时间流速（全局）

    // ==================== 运行时状态（静态，按舰船 id 区分） ====================
    // 重要：脚本实例由 ShipSystemSpecAPI.getStatsScript() 提供，同一系统的所有舰船共用同一个实例，
    //       所以这里不能用普通实例字段保存“每艘船”的状态，必须用 ship.getId() 做键。

    // 登记表：记录所有写入过的战场时间流速键，供兜底清理使用（见 clearAllBattleTimeDilation）
    protected static final Set<String> BATTLE_TIME_KEYS = new LinkedHashSet<String>();

    // 登记表：记录“彻底禁用”模式改掉的护盾原类型，键为 ship.getId()
    protected static final Map<String, ShieldAPI.ShieldType> SAVED_SHIELD_TYPES =
            new HashMap<String, ShieldAPI.ShieldType>();

    // ==================== 系统效果应用 ====================

    /**
     * 系统效果应用（每帧被引擎调用一次）。
     *
     * @param stats       舰船的可变属性对象（所有加成写进这里）
     * @param id          引擎传入的系统标识（本系统对应的 .system 文件里的 id），用作修饰键前缀
     * @param state       系统当前状态（IN 启动 / ACTIVE 生效 / OUT 关闭 / COOLDOWN 冷却 / IDLE 空闲）
     * @param effectLevel 效果强度：启动中 0→1、生效 1、关闭中 1→0
     */
    @Override
    public void apply(MutableShipStatsAPI stats, String id, ShipSystemStatsScript.State state, float effectLevel) {
        // 空值保护：任何一项缺失都直接返回，避免在极端情况下抛异常
        if (stats == null || id == null) return;

        // 把 effectLevel 限制到 [0, 1]，并用它作为所有加成的强度系数，
        // 这样系统的启动 / 关闭过程是平滑过渡的，而不是瞬间跳变。
        float level = clamp01(effectLevel);

        // ---------- 1. 舰船自身时间流速：1 倍 → TIME_FLOW_MULT 倍 ----------
        float timeMult = lerp(1.0F, TIME_FLOW_MULT, level);
        stats.getTimeMult().modifyMult(id + KEY_TIME_FLOW, timeMult);

        // ---------- 2. 最大航速 +100% ----------
        stats.getMaxSpeed().modifyPercent(id + KEY_MAX_SPEED, MAX_SPEED_BONUS_PERCENT * level);

        // ---------- 3. 机动性 +100%（转向与加减速四项一起给，才是完整的“机动性”） ----------
        stats.getMaxTurnRate().modifyPercent(id + KEY_TURN_RATE, MANEUVER_BONUS_PERCENT * level);
        stats.getTurnAcceleration().modifyPercent(id + KEY_TURN_ACCEL, MANEUVER_BONUS_PERCENT * level);
        stats.getAcceleration().modifyPercent(id + KEY_ACCEL, MANEUVER_BONUS_PERCENT * level);
        stats.getDeceleration().modifyPercent(id + KEY_DECEL, MANEUVER_BONUS_PERCENT * level);

        // ---------- 4. 幅能耗散 +50% ----------
        stats.getFluxDissipation().modifyPercent(id + KEY_FLUX_DISS, FLUX_DISSIPATION_BONUS_PERCENT * level);

        // ---------- 5. 能量武器射速 +300% ----------
        stats.getEnergyRoFMult().modifyPercent(id + KEY_ENERGY_ROF, ENERGY_ROF_BONUS_PERCENT * level);

        // ---------- 6. 武器射程 +25% ----------
        float rangeBonus = WEAPON_RANGE_BONUS_PERCENT * level;
        stats.getEnergyWeaponRangeBonus().modifyPercent(id + KEY_RANGE, rangeBonus);       // 能量武器
        stats.getBallisticWeaponRangeBonus().modifyPercent(id + KEY_RANGE, rangeBonus);    // 实弹武器
        stats.getMissileWeaponRangeBonus().modifyPercent(id + KEY_RANGE, rangeBonus);      // 导弹武器
        if (ALSO_APPLY_BEAM_RANGE_BONUS) {
            // 光束武器专用射程加成（默认关闭，原因见常量注释）
            stats.getBeamWeaponRangeBonus().modifyPercent(id + KEY_RANGE, rangeBonus);
        }

        // 反查舰船本体：第 7、8 项都只与具体舰船有关（与 stats 的数值加成无关）
        ShipAPI ship = getShipFromStats(stats);
        if (ship == null) return;

        // ---------- 7. 禁止使用护盾 ----------
        applyShieldSuppression(ship, state);

        // ---------- 8. 玩家驾驶时的“战场时间减速” ----------
        if (SLOW_BATTLE_TIME_FOR_PLAYER && isPlayerPiloted(ship) && ship.isAlive() && !ship.isHulk()) {
            // 玩家在驾驶且舰船完好 → 写入 / 刷新战场时间减速
            applyBattleTimeDilation(id, ship, timeMult, level);
        } else {
            // 非玩家驾驶、玩家换船、舰船已损毁 → 立刻撤掉减速，
            // 否则整场战斗会被一直拖慢（这是本系统最容易出的严重 bug）
            clearBattleTimeDilation(id, ship);
        }

        // 使用 MagicRender 绘制舰船精灵的"残影"
        if (!Global.getCombatEngine().isPaused() && state != State.COOLDOWN && state != State.IDLE) {
            renderTimer += Global.getCombatEngine().getElapsedInLastFrame();
            if (renderTimer >= 0.1f) {
                renderTimer -= 0.1f;

                Color afterimageColor = new Color(107, 0, 161, 255);

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
     * 系统效果移除（系统关闭 / 舰船被移除时由引擎调用）。
     * 必须把上面每一处 modify 都用同样的键 unmodify 掉，否则加成会永久残留。
     *
     * @param stats 舰船的可变属性对象
     * @param id    引擎传入的系统标识（与 apply 一致）
     */
    @Override
    public void unapply(MutableShipStatsAPI stats, String id) {
        if (stats != null && id != null) {
            // 舰船时间流速（用的是 modifyMult，因此用 unmodifyMult 移除）
            stats.getTimeMult().unmodifyMult(id + KEY_TIME_FLOW);

            // 百分比类加成（用的是 modifyPercent，因此用 unmodifyPercent 移除）
            stats.getMaxSpeed().unmodifyPercent(id + KEY_MAX_SPEED);
            stats.getMaxTurnRate().unmodifyPercent(id + KEY_TURN_RATE);
            stats.getTurnAcceleration().unmodifyPercent(id + KEY_TURN_ACCEL);
            stats.getAcceleration().unmodifyPercent(id + KEY_ACCEL);
            stats.getDeceleration().unmodifyPercent(id + KEY_DECEL);
            stats.getFluxDissipation().unmodifyPercent(id + KEY_FLUX_DISS);
            stats.getEnergyRoFMult().unmodifyPercent(id + KEY_ENERGY_ROF);

            // 射程加成（StatBonus 同样用 unmodifyPercent 移除）
            stats.getEnergyWeaponRangeBonus().unmodifyPercent(id + KEY_RANGE);
            stats.getBallisticWeaponRangeBonus().unmodifyPercent(id + KEY_RANGE);
            stats.getMissileWeaponRangeBonus().unmodifyPercent(id + KEY_RANGE);
            if (ALSO_APPLY_BEAM_RANGE_BONUS) {
                stats.getBeamWeaponRangeBonus().unmodifyPercent(id + KEY_RANGE);
            }
        }

        // 从属性对象反查舰船本体：护盾恢复与战场时间流速清理都需要它
        ShipAPI ship = getShipFromStats(stats);

        // 恢复被“彻底禁用”模式改掉的护盾类型（未启用该模式时这里什么也不做）
        restoreShield(ship);

        // 移除本舰的战场时间减速（键按 ship.getId() 现算，因此不需要实例字段）
        if (ship != null && id != null) {
            clearBattleTimeDilation(id, ship);
        }
    }

    // ==================== 护盾禁用处理 ====================

    /**
     * 禁止使用护盾：三道防线叠加。
     * 判定规则：
     *   - 只在系统“启动中（IN）”与“生效中（ACTIVE）”禁用护盾；
     *     收招阶段（OUT）与冷却 / 空闲阶段立即解禁，让玩家在系统结束后马上能举盾。
     *   - ShieldType.PHASE 表示相位斗篷（不是常规护盾）→ 直接跳过，
     *     避免连同相位功能一起被禁掉。
     *   - ShieldType.NONE 表示本来就没有护盾 → 无需处理。
     *
     * @param ship  所属舰船
     * @param state 系统当前状态
     */
    protected void applyShieldSuppression(ShipAPI ship, ShipSystemStatsScript.State state) {
        // 主开关关闭 → 顺便恢复可能被改写的护盾类型，然后退出
        if (!DISABLE_SHIELD_WHILE_ACTIVE) {
            restoreShield(ship);
            return;
        }

        // 只有启动中与生效中才禁用护盾；其它状态视为“已解禁”
        boolean suppress = (state == ShipSystemStatsScript.State.IN
                || state == ShipSystemStatsScript.State.ACTIVE);
        if (!suppress) {
            restoreShield(ship);
            return;
        }

        ShieldAPI shield = ship.getShield();
        if (shield == null) return;

        ShieldAPI.ShieldType type = shield.getType();

        // 相位斗篷：类型为 PHASE 时它代表的是相位系统，不是常规护盾。
        // 注意拦截指令 TOGGLE_SHIELD_OR_PHASE_CLOAK 同时管护盾与相位斗篷，
        // 所以这里必须直接返回，否则相位舰会被连带禁用相位。
        if (type == ShieldAPI.ShieldType.PHASE) return;

        // 本来就没有护盾（含已被“彻底禁用”模式改为 NONE 的情况）→ 不再重复处理
        if (type == ShieldAPI.ShieldType.NONE) return;

        // ---------- 防线①：只要护盾开着就强制关闭 ----------
        // 这是最可靠的一道：无论玩家、AI 还是其它脚本在什么时机把盾打开，
        // 下一帧都会被收掉，因此不会出现“漏网之鱼”。
        if (shield.isOn()) {
            shield.toggleOff();
        }

        // ---------- 防线②：拦截本帧的“切换护盾 / 相位斗篷”指令 ----------
        // 让玩家按右键 / Shift 以及 AI 的开盾指令根本不生效，手感与 HUD 保持一致。
        // （有常规护盾的舰船不可能是相位舰，所以这里不会误伤相位斗篷。）
        ship.blockCommandForOneFrame(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK);

        // ---------- 防线③：可选的“彻底禁用” ----------
        // 把护盾类型改写为 NONE，让护盾的 HUD、视觉与判定一并消失。
        // 原类型按 ship.getId() 存入静态 Map，系统结束时由 restoreShield 恢复。
        if (HARD_DISABLE_SHIELD) {
            String shipKey = ship.getId();
            if (shipKey != null && !SAVED_SHIELD_TYPES.containsKey(shipKey)) {
                SAVED_SHIELD_TYPES.put(shipKey, type); // 只记录第一次看到的原始类型
            }
            shield.setType(ShieldAPI.ShieldType.NONE);
        }
    }

    /**
     * 恢复被“彻底禁用”模式改写的护盾类型。
     * 未启用该模式、或没记录过该舰船时，本方法不做任何事。
     *
     * @param ship 舰船（可为 null，此时直接返回）
     */
    protected void restoreShield(ShipAPI ship) {
        if (ship == null) return;

        String shipKey = ship.getId();
        if (shipKey == null) return;

        // remove 会同时取出并删除条目，避免重复恢复
        ShieldAPI.ShieldType saved = SAVED_SHIELD_TYPES.remove(shipKey);
        if (saved == null) return;

        ShieldAPI shield = ship.getShield();
        if (shield != null) {
            shield.setType(saved);
        }
    }

    // ==================== 战场时间流速处理 ====================

    /**
     * 计算“战场时间流速”修饰键：带舰船 id，保证多舰之间互不干扰。
     *
     * @param id   引擎传入的系统标识
     * @param ship 舰船
     * @return 用于 modifyMult / unmodifyMult 的唯一键
     */
    protected static String getBattleTimeKey(String id, ShipAPI ship) {
        return id + KEY_BATTLE_TIME + "_" + ship.getId();
    }

    /**
     * 写入“战场时间减速”。
     *
     * @param id       引擎传入的系统标识
     * @param ship     玩家驾驶的舰船（用于构造唯一键）
     * @param timeMult 当前舰船时间流速倍率（8 倍时 → 战场时间乘 1/8）
     * @param level    效果强度（为 0 时视为无效，直接清理）
     */
    protected void applyBattleTimeDilation(String id, ShipAPI ship, float timeMult, float level) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null || ship == null || id == null) return;

        // 效果强度为 0（系统已完全关闭）→ 直接清理，不留痕迹
        if (level <= 0.0001F) {
            clearBattleTimeDilation(id, ship);
            return;
        }

        String key = getBattleTimeKey(id, ship);

        // 战场时间乘 1/舰船时间流速：
        //   玩家舰船有效节奏 = 8 × 1/8 = 1 倍（操控手感不变）
        //   其他单位节奏 = 1 × 1/8 = 慢 8 倍（玩家看到“子弹时间”）
        engine.getTimeMult().modifyMult(key, 1.0F / timeMult);

        BATTLE_TIME_KEYS.add(key);
    }

    /**
     * 移除本舰写入的“战场时间减速”（只移除自己那一条，不影响别人）。
     *
     * @param id   引擎传入的系统标识
     * @param ship 舰船（为 null 时无法构造键，直接返回）
     */
    protected void clearBattleTimeDilation(String id, ShipAPI ship) {
        if (ship == null || id == null) return;

        String key = getBattleTimeKey(id, ship);

        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine != null) {
            engine.getTimeMult().unmodifyMult(key);
        }
        BATTLE_TIME_KEYS.remove(key);
    }


    // ==================== 提示文本与可用性 ====================

    /**
     * HUD 状态条目：返回 null 表示“没有更多条目”。
     * 引擎会按 index 递增的方式不断调用，直到拿到 null 为止。
     *
     * @param index       条目序号（从 0 开始）
     * @param state       系统当前状态
     * @param effectLevel 效果强度
     * @return 状态条目；没有更多时返回 null
     */
    @Override
    public ShipSystemStatsScript.StatusData getStatusData(int index, ShipSystemStatsScript.State state, float effectLevel) {
        if (index == 0) return new ShipSystemStatsScript.StatusData("时间流速 " + (int) TIME_FLOW_MULT + " 倍", false);
        if (index == 1) return new ShipSystemStatsScript.StatusData("最大航速 +" + (int) MAX_SPEED_BONUS_PERCENT + "%", false);
        if (index == 2) return new ShipSystemStatsScript.StatusData("机动性 +" + (int) MANEUVER_BONUS_PERCENT + "%", false);
        if (index == 3) return new ShipSystemStatsScript.StatusData("幅能耗散 +" + (int) FLUX_DISSIPATION_BONUS_PERCENT + "%", false);
        if (index == 4) return new ShipSystemStatsScript.StatusData("能量武器射速 +" + (int) ENERGY_ROF_BONUS_PERCENT + "%", false);
        if (index == 5) return new ShipSystemStatsScript.StatusData("武器射程 +" + (int) WEAPON_RANGE_BONUS_PERCENT + "%", false);
        if (index == 6) {
            // 护盾禁用只在系统启动中与生效中展示；判定用的是状态（而不是舰船对象），
            // 因为 getStatusData 的签名里拿不到舰船，而脚本实例又是多舰共享的。
            boolean suppress = DISABLE_SHIELD_WHILE_ACTIVE
                    && (state == ShipSystemStatsScript.State.IN
                    || state == ShipSystemStatsScript.State.ACTIVE);
            if (suppress) {
                // isDebuff = true：以红色负面效果样式显示，提醒玩家“这段时间没有护盾可挡”
                return new ShipSystemStatsScript.StatusData("护盾已禁用", true);
            }
            return null;
        }
        return null; // 没有更多条目
    }

    // ==================== 工具方法 ====================

    /**
     * 从舰船属性对象反查舰船本体。
     * 注意：stats.getEntity() 只在战斗中返回非 null（战役/装配界面为 null）。
     *
     * @param stats 舰船属性对象
     * @return 舰船；取不到时返回 null
     */
    protected ShipAPI getShipFromStats(MutableShipStatsAPI stats) {
        if (stats == null) return null;
        CombatEntityAPI entity = stats.getEntity();
        return (entity instanceof ShipAPI) ? (ShipAPI) entity : null;
    }

    /**
     * 判断该舰船是否由玩家驾驶。
     * 判定顺序：先比对“本场战斗中玩家操控的舰船”，再用“舰长是否是玩家本人”兜底。
     *
     * @param ship 待判断的舰船
     * @return true 表示由玩家驾驶
     */
    protected boolean isPlayerPiloted(ShipAPI ship) {
        if (ship == null) return false;

        CombatEngineAPI engine = Global.getCombatEngine();
        // 最可靠的判定：本场战斗中玩家正在操控的那一艘
        if (engine != null && ship == engine.getPlayerShip()) return true;

        // 兜底判定：舰长就是玩家（例如某些特殊场景下 getPlayerShip() 未及时更新）
        return ship.getCaptain() != null && ship.getCaptain().isPlayer();
    }

    /**
     * 线性插值：把 t 限制在 [0, 1] 后，在 from 与 to 之间取中间值。
     * 用于让系统效果随 effectLevel 平滑地由“无加成”过渡到“满加成”。
     *
     * @param from 起值（效果强度为 0 时的值）
     * @param to   终值（效果强度为 1 时的值）
     * @param t    效果强度
     * @return 插值结果
     */
    protected static float lerp(float from, float to, float t) {
        return from + (to - from) * clamp01(t);
    }

    /**
     * 把数值限制到 [0, 1] 区间。
     *
     * @param value 原始数值
     * @return 限制后的数值
     */
    protected static float clamp01(float value) {
        if (value < 0.0F) return 0.0F;
        if (value > 1.0F) return 1.0F;
        return value;
    }
}