package data.scripts.shipsystems.subsystems;// 包路径：模组的 MagicLib 子系统目录

import java.awt.Color;
import java.util.List;

import org.lwjgl.util.vector.Vector2f;
import org.magiclib.subsystems.MagicSubsystem;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.EveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.impl.combat.MineStrikeStatsAIInfoProvider;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.WeightedRandomPicker;

import data.scripts.shipsystems.ai.VIII_MineStrikeSystemAI;

/**
 * 空雷突袭子系统（MagicLib 子系统），战斗行为逐条对齐原版
 * com.fs.starfarer.api.impl.combat.MineStrikeStats；HUD 部分严格按
 * {@link MagicSubsystem} 的钩子契约实现。
 * <p>
 * 与原版的对应关系：
 * - 原版 apply() 的抖动段        → 本类 advance() 里的 applyShipJitter()
 * - 原版 apply() 的布雷段        → 本类 spawnMineAtTarget()
 * - 原版 spawnMine()             → 本类 spawnMine()
 * - 原版 createMissileJitterPlugin() → 本类同名方法
 * - 原版 findClearLocation()/isLocationClear() → 本类同名方法
 * - 原版 getFuseTime()/getMineRange() → 本类实现 MineStrikeStatsAIInfoProvider 提供
 * - 原版由 .system 提供的"弹药 + 冷却" → 本类用 MagicLib 的充能槽（charges）实现
 * <p>
 * 【AI 的唯一驱动点：shouldActivateAI(float)】
 * MagicLib 只提供 shouldActivateAI(float) 这一个"AI 是否想开火"的询问入口；
 * 基类的 advance(amount, isPaused) 不会代为询问 AI，也不会代为激活。
 * 因此 {@link VIII_MineStrikeSystemAI} 只能在这里被驱动，写进其它方法
 * （构造器、advance、外部插件）的那个实例将永远不被调用。
 * 并且本方法内部自带"懒初始化"（ensureAIReady），不再依赖 init() 的调用时机。
 * <p>
 * 【本类同时是布雷 AI 的唯一数据源】
 * - getMineRange(ShipAPI)     投放距离（= getRange，含系统射程加成）
 * - getMaxDropRange()         落点距离上限（= 投放距离 + 自身碰撞半径）
 * - getFuseTime()             引信时间
 * - isReady() / getAmmo()     是否可以开火 / 还剩几颗雷（继承自 MagicSubsystem）
 * - getFluxCostFlatOnActivation() / getFluxCostPercentOnActivation()  启动幅能
 * <p>
 * 另提供 AI 与 HUD 共用的落点射程工具：
 * isDropPointInRange(...) / clampDropPointIntoRange(...)，
 * 这样"AI 算出来的落点"与"子系统判定的超距"用的是同一把尺子。
 */
public class VIII_MineStrikeSubsystem extends MagicSubsystem implements MineStrikeStatsAIInfoProvider {

    // ==================== 一、与原版数值完全一致的常量 ====================

    /** 原版的基础投放距离（会被舰船的"系统射程加成"修正） */
    public static final float MINE_RANGE = 1000f;

    /** 原版：落点与其它舰船的最小间距 */
    public static final float MIN_SPAWN_DIST = 75f;
    /** 原版：落点与护卫舰的最小间距（护卫舰判定更严） */
    public static final float MIN_SPAWN_DIST_FRIGATE = 110f;

    /** 原版：水雷从生成到可被引爆之间的存活时间（秒） */
    public static final float LIVE_TIME = 5f;

    /** 原版：引信时间（秒） */
    public static final float FUSE_TIME = 0.1f;

    /** 原版硬编码的水雷武器 id（这是标识符，不是文案，不能翻译） */
    public static final String MINE_WEAPON_ID = "VIII_nanominefield";

    /** 原版：水雷淡入时长（秒） */
    public static final float FADE_IN_TIME = 0.5f;

    /** 原版抖动颜色（带透明度） */
    public static final Color JITTER_COLOR = new Color(255, 155, 255, 75);
    public static final Color JITTER_UNDER_COLOR = new Color(255, 155, 255, 155);

    /** 原版：舰船抖动的最大范围加成 */
    public static final float JITTER_MAX_RANGE_BONUS = 25f;
    /** 原版：水雷抖动的最大范围加成 */
    public static final float MINE_JITTER_MAX_RANGE_BONUS = 50f;
    /** 原版水雷抖动的份数 */
    public static final int MINE_JITTER_COPIES = 15;

    // ---------- 原版内联的散布 / 扫描参数（抽成常量，数值不变） ----------
    /** 初始散布半径下限 */
    public static final float MINE_SCATTER_MIN = 30f;
    /** 初始散布半径的随机增量 */
    public static final float MINE_SCATTER_EXTRA = 30f;
    /** 环形扫描半径下限 */
    public static final float MINE_RING_MIN = 50f;
    /** 环形扫描半径的随机增量 */
    public static final float MINE_RING_EXTRA = 30f;
    /** 环形扫描的角步长 */
    public static final float MINE_RING_STEP = 30f;
    /** 环形扫描的角度上限（多转 30 度，保证起点那一圈也被测过） */
    public static final float MINE_RING_SCAN_EXTRA = 390f;
    /** 与已有水雷的避让余量 */
    public static final float MINE_AVOID_EXTRA = 40f;
    /** findClearLocation 的搜索半径步长 */
    public static final float CLEAR_SEARCH_INCR = 50f;
    /** 水雷抖动插件用的"宽度位置"参数（原版为 0，此处保留常量以便调整） */
    public static final float MINE_JITTER_RANGE_START = 0f;

    /**
     * ★ 落点钳制时的内缩余量（距离单位）。
     * AI 把落点钳回射程时会多退这么一点，避免因浮点误差恰好贴在边界上被判成超距。
     */
    public static float RANGE_CLAMP_MARGIN = 5f;

    /** 原版音效 id（标识符，不能翻译） */
    public static final String SPAWN_SOUND_ID = "mine_teleport";

    // ==================== 二、mod 侧配置 ====================

    /**
     * 子系统启用按键。左 Alt 在 LWJGL 中的键名是 "LMENU"。
     * 若实测左 Alt 无效，改成空字符串 "" 让 MagicLib 自动分配键位，
     * 再在 MagicLib 设置里把该热键改到左 Alt。
     */
    public static String ACTIVATION_KEY = "LMENU";

    /** 是否允许 AI 操控玩家舰船时也自行布雷（默认否：玩家舰船只认按键） */
    public static boolean AI_ON_PLAYER_SHIP = true;

    /**
     * 冷却时长（秒）。必须 > 0！
     * 状态条长度是按"状态已完成比例"算的，时长为 0 会让这个比例变成除以 0 的无效值。
     * 节奏主要由"水雷补充时间"控制，所以这里给一个很小的正数即可。
     */
    public static float MINE_COOLDOWN = 0.1f;

    /** 水雷（充能）上限 */
    public static int MINE_AMMO_MAX = 5;

    /** 每补充一枚水雷所需的秒数 */
    public static float MINE_AMMO_RECHARGE = 5f;

    /** 启动时消耗的幅能（按最大幅能的比例计） */
    public static float FLUX_COST_PERCENT = 0.1f;

    // ---------- 状态时长（必须全部 > 0） ----------
    /** 渐入时长（秒） */
    private static final float DURATION_IN = 0.25f;
    /**
     * 维持时长（秒）。布雷是瞬时动作，但绝不能写 0：
     * 时长为 0 时状态条的比例计算无效，界面就会画错。
     * 这里给 0.1 秒，让"激活"这一瞬在 HUD 上可见。
     */
    private static final float DURATION_ACTIVE = 0.1f;
    /** 渐出时长（秒） */
    private static final float DURATION_OUT = 0.25f;

    // ---------- 显示文案（全部中文） ----------
    /** 显示名（会显示在按键之后） */
    private static final String DISPLAY_TEXT = "空雷突袭";
    /** 简要说明（仅在 HUD 的"更多信息"模式显示） */
    private static final String BRIEF_TEXT = "在指定位置投放一枚空雷";

    /** 状态条左侧：就绪 */
    private static final String TEXT_STATE_READY = "就绪";
    /** 状态条左侧：进入中 */
    private static final String TEXT_STATE_IN = "激活";
    /** 状态条左侧：激活中 */
    private static final String TEXT_STATE_ACTIVE = "激活";
    /** 状态条左侧：退出中（不需要文字） */
    private static final String TEXT_STATE_OUT = "";
    /** 状态条左侧：冷却中（不需要文字） */
    private static final String TEXT_STATE_COOLDOWN = "";

    /** 状态条右侧：没有水雷了 */
    private static final String TEXT_NO_AMMO = "空雷耗尽";
    /** 状态条右侧：没有可用落点 */
    private static final String TEXT_NO_TARGET = "无目标";
    /** 状态条右侧：落点超出射程（原版 getInfoText 的"超出射程"） */
    private static final String TEXT_OUT_OF_RANGE = "超出射程";
    /** 状态条右侧：幅能过高（过载或排气中） */
    private static final String TEXT_FLUX_TOO_HIGH = "幅能过高";

    /** 状态条下方：弹药行前缀 */
    private static final String TEXT_AMMO_PREFIX = "空雷数量 ";

    /** 状态条行数：第一行状态条，第二行弹药/充能行（与 getAmmoText() 配套） */
    private static final int HUD_BAR_ROWS = 2;

    // ==================== 三、运行期状态 ====================

    /** 舰船的 AI 标记集合（缓存的引用；实际使用时优先用 resolveFlags() 取最新值） */
    protected ShipwideAIFlags flags;

    /** 舰船自身的系统：作为布雷 AI 的兜底数据源（正常流程不读取它的数值，可能为 null） */
    protected ShipSystemAPI system;

    /** 复用布雷决策 AI：负责"何时开火 + 打哪儿" */
    protected final VIII_MineStrikeSystemAI mineStrikeAI;

    /** ★ 布雷 AI 是否已经挂载完毕（懒初始化标记，保证只初始化一次） */
    protected boolean aiInitialized = false;

    /** AI 刚决定开火（一次性消费标记） */
    protected boolean aiWantsToFire = false;

    /** 本次激活是否已经布雷：保证一次激活只投一颗雷 */
    protected boolean mineSpawnedThisActivation = false;

    // ==================== 四、构造与生命周期 ====================

    /**
     * 构造器。这里**只**做对象创建与接线，驱动逻辑一律放在 shouldActivateAI 里。
     *
     * @param ship 本舰
     */
    public VIII_MineStrikeSubsystem(ShipAPI ship) {
        super(ship);

        // 绑定启用按键（左 Alt）
        setKey(ACTIVATION_KEY);

        // 用匿名子类接管 AI 的"开火"行为：
        // 不再给舰船下 USE_SYSTEM 指令（那会去开舰船主系统），
        // 而是先把落点钳进射程，再照原版 AI 的做法交回本子系统，由本子系统自己去布雷。
        this.mineStrikeAI = new VIII_MineStrikeSystemAI() {
            @Override
            public void useSystemAt(Vector2f point) {
                // 1) clampIntoRange(...) 保证转交给子系统的落点一定在射程内，
                //    否则子系统会判它超距、水雷还会被压回边界，落在敌人根本不在的位置；
                // 2) 必须用 外层类名.this 限定，否则会命中 AI 自己的同名方法。
                Vector2f safePoint = clampIntoRange(point);
                VIII_MineStrikeSubsystem.this.requestFireAt(safePoint);
            }
        };

        // 把本子系统登记成 AI 的数据源（真正注入到 AI 里由 ensureAIReady 负责）
        this.mineStrikeAI.setSubsystem(this);
    }

    /**
     * 按键绑定完成、子系统挂到舰船上之后调用一次（官方要求务必调用 super）。
     */
    @Override
    protected void init() {
        super.init();

        if (ship == null) {
            return;
        }

        // 这里既缓存标记引用，也顺便把 AI 挂起来；
        // 但即使这一步因为任何原因没跑到，shouldActivateAI 也会自己补上（懒初始化）。
        ensureAIReady(Global.getCombatEngine());
    }

    /**
     * ★ 保证布雷 AI 已挂载完成：把本舰、舰船系统、AI 标记集合与引擎注入 AI。
     * <p>
     * 为什么把它放进 shouldActivateAI 的调用链里：
     * 只依赖 init() 的话，一旦挂载流程被打断（或舰船本身没有主系统导致早退），
     * AI 的 ship/engine 就是 null，它会每帧在第一行返回 —— 现象就是"AI 从来没被调用"。
     * 这里做成幂等的懒初始化，任何一帧发现没挂上就当场补挂。
     *
     * @param engine 战斗引擎（可为 null，为 null 时只挂载不驱动）
     */
    protected void ensureAIReady(CombatEngineAPI engine) {
        if (aiInitialized || ship == null) {
            return;
        }

        this.flags = ship.getAIFlags();
        this.system = ship.getSystem(); // 可能为 null，这是合法情况（仅作兜底数据源）

        this.mineStrikeAI.setSubsystem(this);
        this.mineStrikeAI.init(ship, this.system, this.flags, engine);

        this.aiInitialized = true;

        if (VIII_MineStrikeSystemAI.DEBUG_LOG) {
            Global.getLogger(VIII_MineStrikeSubsystem.class).info(
                    "空雷突袭：布雷 AI 已挂载，舰船 = " + ship.getName()
                            + "，投放距离 = " + (int) getMineRange(ship));
        }
    }

    /**
     * 每帧调用。对应原版 apply() 的全部内容。
     * <p>
     * 注意：这里**不**驱动布雷 AI —— MagicLib 只为 AI 提供 shouldActivateAI 这一个入口，
     * 写在这里（或任何其它方法里）的调用都不会被执行。
     *
     * @param amount   上一帧耗时（秒）
     * @param isPaused 战斗是否暂停
     */
    @Override
    public void advance(float amount, boolean isPaused) {
        // 基类负责状态机、幅能、时长、冷却、充能补充与 HUD
        super.advance(amount, isPaused);

        if (isPaused) {
            return;
        }
        if (ship == null || !ship.isAlive()) {
            return;
        }

        // 舰船抖动：原版 apply() 中除布雷之外的那一段
        applyShipJitter();
    }

    /**
     * 状态切换回调。
     * 布雷时机放在这里：oldState == IN 表示"渐入刚结束、即将进入 ACTIVE"，
     * 这是一个确定性事件，不受帧率与时间加速影响，比"每帧检查效果值是否达到 1"更可靠。
     *
     * @param oldState 切换前的状态
     */
    @Override
    public void onStateSwitched(State oldState) {
        super.onStateSwitched(oldState);

        if (oldState == State.IN && !mineSpawnedThisActivation) {
            mineSpawnedThisActivation = true;

            CombatEngineAPI engine = Global.getCombatEngine();
            if (engine != null && ship != null && ship.isAlive()) {
                spawnMineAtTarget();
            }
        }
    }

    /**
     * 子系统被激活（按键或 AI）时执行。
     * 充能（= 水雷数）已经由基类的 activate() 扣掉，这里只重置本帧标记。
     */
    @Override
    public void onActivate() {
        mineSpawnedThisActivation = false;
    }

    /** 状态切换到 COOLDOWN 时执行，对应原版（基本为空）的 unapply() */
    @Override
    public void onFinished() {
        mineSpawnedThisActivation = false;
    }

    // ==================== 五、AI 接入：唯一驱动点 ====================

    /**
     * ★ MagicLib 每帧询问"AI 是否想开启本子系统"——这是布雷 AI 的唯一驱动入口。
     * <p>
     * 执行顺序：
     * 1) 环境检查（舰船存活、引擎未暂停、玩家舰船默认不自动布雷）；
     * 2) ensureAIReady(engine)：幂等懒初始化，保证 AI 一定挂得上；
     * 3) 没有水雷就直接退出（省掉一整轮全场遍历）；
     * 4) 交给 {@link VIII_MineStrikeSystemAI} 打分；它决定开火时会调用
     *    useSystemAt(...) → requestFireAt(...)，把落点写进 AI 标记并置位一次性标记；
     * 5) 复核 canActivate()，基类此刻不允许激活就撤销落点标记，不留残余坐标。
     *
     * @param amount 上一帧耗时（秒）
     * @return true 表示本次应当激活
     */
    @Override
    public boolean shouldActivateAI(float amount) {
        // ---------- ① 环境检查 ----------
        if (ship == null || !ship.isAlive()) {
            aiWantsToFire = false;
            return false;
        }

        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null || engine.isPaused()) {
            aiWantsToFire = false;
            return false;
        }

        // 玩家舰船默认只认按键；同时把本次可能残留的请求作废
        if (!AI_ON_PLAYER_SHIP && engine.getPlayerShip() == ship) {
            aiWantsToFire = false;
            return false;
        }

        // ---------- ② 保证布雷 AI 已挂载（不依赖 init 的调用时机） ----------
        ensureAIReady(engine);

        // ---------- ③ 没有水雷就别浪费 CPU 去打分 ----------
        if (getCharges() <= 0) {
            aiWantsToFire = false;
            return false;
        }

        // ---------- ④ 唯一驱动点：让布雷 AI 评估目标并决定是否开火 ----------
        mineStrikeAI.advance(amount, null, null, ship.getShipTarget());

        if (!aiWantsToFire) {
            return false;
        }
        aiWantsToFire = false; // 一次性消费

        // ---------- ⑤ 最后一道闸门 ----------
        // 基类此刻若不允许激活（幅能不足、舰船状态异常等），就撤销落点标记，
        // 避免一个 1 秒残留的坐标继续影响 HUD 与后续判断。
        if (!canActivate()) {
            ShipwideAIFlags f = resolveFlags();
            if (f != null) {
                f.unsetFlag(AIFlags.SYSTEM_TARGET_COORDS);
            }
            return false;
        }

        return true;
    }

    /**
     * 取当前生效的 AI 标记集合：优先每帧从舰船取最新引用，缓存字段只作兜底。
     * 这样即使子系统挂载时机有出入，也不会一直写在一个过期的对象上。
     *
     * @return AI 标记集合，可能为 null
     */
    protected ShipwideAIFlags resolveFlags() {
        if (ship != null && ship.getAIFlags() != null) {
            return ship.getAIFlags();
        }
        return flags;
    }

    /**
     * AI 请求在指定坐标布雷。
     * 与原版 AI 完全一致：先把落点写进 AIFlags.SYSTEM_TARGET_COORDS，
     * 再由子系统在实际布雷时读取该标记。
     *
     * @param point 落点（可为 null；调用方已保证在射程内）
     */
    protected void requestFireAt(Vector2f point) {
        ShipwideAIFlags f = resolveFlags();
        if (f != null) {
            if (point != null) {
                f.setFlag(AIFlags.SYSTEM_TARGET_COORDS, 1.0F, point);
            } else {
                f.unsetFlag(AIFlags.SYSTEM_TARGET_COORDS);
            }
        }
        aiWantsToFire = true;
    }

    /**
     * 撤销本次开火请求（只在本子系统确实写过标记时才清标记，避免误清别人的坐标）。
     */
    protected void cancelFireRequest() {
        if (!aiWantsToFire) {
            return;
        }
        aiWantsToFire = false;

        ShipwideAIFlags f = resolveFlags();
        if (f != null) {
            f.unsetFlag(AIFlags.SYSTEM_TARGET_COORDS);
        }
    }

    /**
     * 本舰是否由"玩家亲自操控"（而非 AI）。
     *
     * @return true 表示玩家舰船
     */
    protected boolean isPlayerShip() {
        CombatEngineAPI engine = Global.getCombatEngine();
        return engine != null && ship != null && engine.getPlayerShip() == ship;
    }

    /**
     * 本子系统是否允许在这艘船上走 AI 逻辑。
     * 玩家舰船默认只认按键（AI_ON_PLAYER_SHIP = true 时例外）。
     *
     * @return true 表示允许
     */
    protected boolean isAIAllowedForThisShip() {
        return AI_ON_PLAYER_SHIP || !isPlayerShip();
    }

    // ==================== 六、原版 apply() 的移植 ====================

    /**
     * 舰船抖动。对应原版 apply() 里除布雷之外的那部分（数值与份数完全一致）。
     */
    protected void applyShipJitter() {
        float jitterLevel = getEffectLevel();
        if (getState() == State.OUT) {
            // 原版：退出阶段抖动强度按平方衰减
            jitterLevel *= jitterLevel;
        }

        float jitterRangeBonus = jitterLevel * JITTER_MAX_RANGE_BONUS;

        ship.setJitterUnder(this, JITTER_UNDER_COLOR, jitterLevel, 11, 0f, 3f + jitterRangeBonus);
        ship.setJitter(this, JITTER_COLOR, jitterLevel, 4, 0f, 0f + jitterRangeBonus);
    }

    /**
     * 取当前的布雷落点。完全照抄原版的取点顺序：
     * 1) 默认用鼠标指向的位置（对 AI 舰船同样有效）；
     * 2) 若本舰由 AI 操控、且 AI 写了 SYSTEM_TARGET_COORDS，则改用该坐标。
     * <p>
     * ★ 本子系统自己写下的是"AI 内部工作坐标"，因此只在允许走 AI 的舰船上才读它：
     * 玩家舰船（且未开 AI_ON_PLAYER_SHIP）永远用鼠标指向，手动瞄准不会被劫持。
     *
     * @return 落点；取不到时返回 null
     */
    public Vector2f resolveTarget() {
        if (ship == null) {
            return null;
        }

        Vector2f target = ship.getMouseTarget();

        if (isTargetFromAI()) {
            ShipwideAIFlags f = ship.getAIFlags();
            Object custom = (f == null) ? null : f.getCustom(AIFlags.SYSTEM_TARGET_COORDS);
            // 原版直接强转；这里加 instanceof 保护，正常情形下行为一致
            if (custom instanceof Vector2f) {
                target = (Vector2f) custom;
            }
        }

        return target;
    }

    /**
     * 当前落点是否来自"AI 写入的标记"（而不是鼠标指向）。
     * 供 HUD 判断信息文字该怎么给：AI 的落点是内部工作坐标，
     * 不该拿来做"无目标 / 超出射程"这类面向玩家的提示。
     *
     * @return true 表示落点来自 AI 标记
     */
    public boolean isTargetFromAI() {
        if (ship == null || !isAIAllowedForThisShip()) {
            return false;
        }
        ShipwideAIFlags f = ship.getAIFlags();
        return f != null && f.hasFlag(AIFlags.SYSTEM_TARGET_COORDS);
    }

    /**
     * 对应原版 apply() 中 effectLevel >= 1 的那一段：
     * 取点 → 超距则沿方向钳制到最大距离 → 找安全落点 → 布雷。
     */
    protected void spawnMineAtTarget() {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null || ship == null) {
            return;
        }

        Vector2f target = resolveTarget();
        if (target == null) {
            return;
        }

        // 超出最大射程 → 沿该方向压回最大距离处。
        // 复用 clampDropPointIntoRange()，与 AI 侧钳制、HUD 超距判定共用同一套算法。
        Vector2f clamped = clampDropPointIntoRange(target);
        if (clamped != null) {
            target = clamped;
        }

        // 落点若压在别的舰船/小行星上，就找一个附近的空位
        target = findClearLocation(ship, target);

        if (target != null) {
            spawnMine(ship, target);
        }
    }

    // ==================== 七、原版 spawnMine() 的移植 ====================

    /**
     * 在指定位置投放一枚水雷（原版 spawnMine 的逐条移植）。
     *
     * @param source  投放者（用于计算伤害加成）
     * @param mineLoc 目标落点
     */
    public void spawnMine(ShipAPI source, Vector2f mineLoc) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) {
            return;
        }

        // ---------- 1) 选一个不与已有水雷重叠的生成点 ----------
        // 先取一个带随机偏移的点，然后沿环形以 30 度为步长扫描，
        // 直到找到一个与所有"己方水雷"都保持足够间距的位置。
        Vector2f currLoc = Misc.getPointAtRadius(mineLoc,
                MINE_SCATTER_MIN + (float) Math.random() * MINE_SCATTER_EXTRA);
        float start = (float) Math.random() * 360f;
        for (float angle = start; angle < start + MINE_RING_SCAN_EXTRA; angle += MINE_RING_STEP) {
            if (angle != start) {
                Vector2f loc = Misc.getUnitVectorAtDegreeAngle(angle);
                loc.scale(MINE_RING_MIN + (float) Math.random() * MINE_RING_EXTRA);
                currLoc = Vector2f.add(mineLoc, loc, new Vector2f());
            }

            for (MissileAPI other : engine.getMissiles()) {
                if (!other.isMine()) {
                    continue; // 只看水雷
                }
                float dist = Misc.getDistance(currLoc, other.getLocation());
                if (dist < other.getCollisionRadius() + MINE_AVOID_EXTRA) {
                    currLoc = null; // 太挤了，换下一圈
                    break;
                }
            }

            if (currLoc != null) {
                break;
            }
        }

        // 一圈扫下来都没找到空位 → 退回"随机偏移点"（原版同此兜底）
        if (currLoc == null) {
            currLoc = Misc.getPointAtRadius(mineLoc,
                    MINE_SCATTER_MIN + (float) Math.random() * MINE_SCATTER_EXTRA);
        }

        // ---------- 2) 生成水雷 ----------
        // 原版：武器传 null，随机朝向，初速传 null（随后会把速度清零）
        MissileAPI mine = (MissileAPI) engine.spawnProjectile(source, null,
                MINE_WEAPON_ID,
                currLoc,
                (float) Math.random() * 360f,
                null);
        if (mine == null) {
            return;
        }

        // 应用舰船的"导弹伤害"类加成：用"无武器"版本，避免依赖具体武器对象
        if (source != null) {
            engine.applyDamageModifiersToSpawnedProjectileWithNullWeapon(
                    source, WeaponType.MISSILE, false, mine.getDamage());
        }

        // ---------- 3) 表现：静止 + 淡入 + 抖动插件 ----------
        mine.getVelocity().scale(0);
        mine.fadeOutThenIn(FADE_IN_TIME);

        engine.addPlugin(createMissileJitterPlugin(mine, FADE_IN_TIME));

        // ---------- 4) 生命期：把飞行时间快进到"只留 LIVE_TIME" ----------
        mine.setFlightTime(mine.getMaxFlightTime() - LIVE_TIME);

        // ---------- 5) 音效 ----------
        Global.getSoundPlayer().playSound(SPAWN_SOUND_ID, 1f, 1f, mine.getLocation(), mine.getVelocity());
    }

    /**
     * 水雷淡入期间的抖动插件（原版 createMissileJitterPlugin 的逐条移植）。
     *
     * @param mine       目标水雷
     * @param fadeInTime 淡入时长
     * @return 每帧插件
     */
    protected EveryFrameCombatPlugin createMissileJitterPlugin(final MissileAPI mine, final float fadeInTime) {
        return new BaseEveryFrameCombatPlugin() {
            float elapsed = 0f;

            @Override
            public void advance(float amount, List<InputEventAPI> events) {
                CombatEngineAPI engine = Global.getCombatEngine();
                if (engine == null || engine.isPaused()) {
                    return;
                }

                elapsed += amount;

                // 把"当前基础透明度"映射成抖动强度：先 0→1，再 1→0
                float jitterLevel = mine.getCurrentBaseAlpha();
                if (jitterLevel < 0.5f) {
                    jitterLevel *= 2f;
                } else {
                    jitterLevel = (1f - jitterLevel) * 2f;
                }

                float jitterRange = 1f - mine.getCurrentBaseAlpha();
                float jitterRangeBonus = jitterRange * MINE_JITTER_MAX_RANGE_BONUS;

                Color c = Misc.setAlpha(JITTER_UNDER_COLOR, 70);
                mine.setJitter(this, c, jitterLevel, MINE_JITTER_COPIES,
                        jitterRangeBonus * MINE_JITTER_RANGE_START, jitterRangeBonus);

                if (jitterLevel >= 1 || elapsed > fadeInTime) {
                    engine.removePlugin(this);
                }
            }
        };
    }

    // ==================== 八、原版 findClearLocation() / isLocationClear() 的移植 ====================

    /**
     * 找一个"不会压在别人身上"的落点（原版逐条移植）。
     * <p>
     * 注意：搜索过程最多把落点移开 50×32 = 1600 距离单位，
     * 因此理论上搜索结果可能又跑到射程之外。这是原版行为，此处保持不动：
     * HUD 的超距判定看的是 AI/玩家给出的落点（findClearLocation 之前的值），
     * 而"宁可落点偏一点，也不要压在友军身上"更安全。
     *
     * @param ship 投放者（原版签名保留，逻辑中未使用）
     * @param dest 期望落点
     * @return 可用落点
     */
    private Vector2f findClearLocation(ShipAPI ship, Vector2f dest) {
        if (isLocationClear(dest)) {
            return dest;
        }

        WeightedRandomPicker<Vector2f> tested = new WeightedRandomPicker<Vector2f>();
        for (float distIndex = 1; distIndex <= 32f; distIndex *= 2f) {
            float start = (float) Math.random() * 360f;
            for (float angle = start; angle < start + 360; angle += 60f) {
                Vector2f loc = Misc.getUnitVectorAtDegreeAngle(angle);
                loc.scale(CLEAR_SEARCH_INCR * distIndex);
                Vector2f.add(dest, loc, loc);
                tested.add(loc);
                if (isLocationClear(loc)) {
                    return loc;
                }
            }
        }

        if (tested.isEmpty()) {
            return dest; // 理论上不会发生
        }

        return tested.pick();
    }

    /**
     * 判断某落点是否"干净"（原版逐条移植）。
     *
     * @param loc 待测落点
     * @return true 表示可以在这里布雷
     */
    private boolean isLocationClear(Vector2f loc) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) {
            return true;
        }

        for (ShipAPI other : engine.getShips()) {
            if (other.isShuttlePod()) {
                continue;
            }
            if (other.isFighter()) {
                continue;
            }

            Vector2f otherLoc = other.getShieldCenterEvenIfNoShield();
            float otherR = other.getShieldRadiusEvenIfNoShield();
            if (other.isPiece()) {
                // 碎片/部件没有可靠的护盾，用本体数据
                otherLoc = other.getLocation();
                otherR = other.getCollisionRadius();
            }

            float dist = Misc.getDistance(loc, otherLoc);
            float r = otherR;

            float checkDist = MIN_SPAWN_DIST;
            if (other.isFrigate()) {
                checkDist = MIN_SPAWN_DIST_FRIGATE;
            }

            if (dist < r + checkDist) {
                return false;
            }
        }

        for (CombatEntityAPI other : engine.getAsteroids()) {
            float dist = Misc.getDistance(loc, other.getLocation());
            if (dist < other.getCollisionRadius() + MIN_SPAWN_DIST) {
                return false;
            }
        }

        return true;
    }

    // ==================== 九、弹药：基类自带的充能槽 ====================
    // getNumHUDBars() 的默认值是"无充能 1 行、有充能 2 行"，
    // 只有真正启用充能，基类才会给弹药行预留高度，界面才不会叠字；
    // "没弹药就不能用"也由基类的 canActivateInternal() 自动处理。

    /** 启用充能（充能数 = 水雷数） */
    @Override
    public boolean hasCharges() {
        return true;
    }

    /** 充能基数 = 水雷上限 */
    @Override
    protected int getMaxCharges() {
        return MINE_AMMO_MAX;
    }

    /**
     * 实际充能上限：直接返回常量，不走"按舰船系统统计换算"的默认实现，
     * 避免被舰船主系统的数据影响。
     */
    @Override
    public int calcMaxCharges() {
        return MINE_AMMO_MAX;
    }

    /** 充能补充时长 = 每枚水雷的补充时间 */
    @Override
    public float getBaseChargeRechargeDuration() {
        return MINE_AMMO_RECHARGE;
    }

    /** 激活时消耗一枚充能（= 一枚水雷） */
    @Override
    public boolean usesChargesOnActivate() {
        return true;
    }

    /**
     * 如果希望本子系统完全不受"系统专精"等舰船系统统计影响，
     * 把本方法的返回值改成 0f 即可（基类文档说明：设为 0 即禁用该倍率）。
     * 默认返回 1f，保持与原版系统一样可被技能加成。
     */
    @Override
    public float getSystemStatsEffectMult() {
        return 1f;
    }

    /** ★ 剩余水雷数（= 剩余充能）。供布雷 AI 与 HUD 读取 */
    public int getAmmo() {
        return getCharges();
    }

    /** ★ 水雷上限。供布雷 AI 与 HUD 读取 */
    public int getMaxAmmo() {
        return calcMaxCharges();
    }

    // ==================== 十、落点射程工具（AI 与本类共用同一把尺子） ====================

    /**
     * ★ 落点距离上限 = 实际投放距离 + 自身碰撞半径。
     * <p>
     * 这是"射程判定"的唯一定义处：
     * - 布雷 AI 用它来限制候选落点（AI 侧同名方法是 getMaxDropRange()）；
     * - HUD 的 isTargetOutOfRange() 用它判定是否提示"超出射程"；
     * - spawnMineAtTarget() 用它把超距落点压回来。
     *
     * @return 落点距本舰的最大允许距离
     */
    public float getMaxDropRange() {
        if (ship == null) {
            return MINE_RANGE;
        }
        return getMaxRange(ship) + ship.getCollisionRadius();
    }

    /**
     * ★ 判断某落点是否在可投放范围内。
     *
     * @param point 待测落点
     * @return true 表示在射程内
     */
    public boolean isDropPointInRange(Vector2f point) {
        if (ship == null || point == null) {
            return false;
        }
        return Misc.getDistance(ship.getLocation(), point) <= getMaxDropRange();
    }

    /**
     * ★ 把任意落点沿"本舰 → 该点"方向拉回可投放范围之内。
     * 任何交给布雷系统或写进 AI 标记的坐标，都应该先过这一道；
     * 否则子系统会判它超距，水雷也会被压回边界，落在敌人根本不在的位置。
     *
     * @param point 原始落点
     * @return 钳制后的新向量；入参非法时返回 null
     */
    public Vector2f clampDropPointIntoRange(Vector2f point) {
        if (ship == null || point == null) {
            return null;
        }

        Vector2f result = new Vector2f(point);

        // 留一点内缩余量，避免恰好贴在边界上被浮点误差判成超距
        float max = getMaxDropRange() - RANGE_CLAMP_MARGIN;
        if (max < 1f) {
            max = 1f;
        }

        float dist = Misc.getDistance(ship.getLocation(), result);
        if (dist <= max) {
            return result; // 本来就在射程内，原样返回
        }

        // 沿方向压回最大距离处
        float dir = Misc.getAngleInDegrees(ship.getLocation(), result);
        Vector2f offset = Misc.getUnitVectorAtDegreeAngle(dir);
        offset.scale(max);
        Vector2f.add(ship.getLocation(), offset, result);
        return result;
    }

    // ==================== 十一、HUD：严格按 MagicSubsystem 的钩子契约实现 ====================

    /**
     * 状态条填充度，0~1。
     * 就绪 / 激活 = 满；启动中 = 已完成比例；收束中 = 1 - 已完成比例；冷却中 = 已完成比例（回升）。
     * <p>
     * 必须做除零保护：只要某个时长被设成 0，基类的"已完成比例"就是无效值，
     * 状态条会画错甚至不显示。
     */
    @Override
    public float getBarFill() {
        if (isReady()) {
            return 1f;
        }
        if (isActive()) {
            return 1f;
        }
        if (isIn()) {
            return getRatioSafe(getInDuration());
        }
        if (isOut()) {
            return 1f - getRatioSafe(getOutDuration());
        }
        if (isCooldown()) {
            return getRatioSafe(getCooldownDuration());
        }
        return 0f;
    }

    /**
     * 安全地取"当前状态已完成的秒数比例"（0~1）。时长为 0 时直接返回 1，避免除以 0。
     *
     * @param duration 当前状态的时长（秒）
     * @return 0~1 的比例
     */
    protected float getRatioSafe(float duration) {
        if (duration <= 0f) {
            return 1f;
        }

        float ratio = getStateCompleteRatio();
        if (Float.isNaN(ratio) || ratio < 0f) {
            return 0f;
        }
        if (ratio > 1f) {
            return 1f;
        }
        return ratio;
    }

    /**
     * 状态条左侧文字：子系统当前活动状态。
     */
    @Override
    public String getStateText() {
        if (isActive()) {
            return TEXT_STATE_ACTIVE;
        }
        if (isIn()) {
            return TEXT_STATE_IN;
        }
        if (isOut()) {
            return TEXT_STATE_OUT;
        }
        if (isCooldown()) {
            return TEXT_STATE_COOLDOWN;
        }
        return TEXT_STATE_READY;
    }

    /**
     * 状态条下方文字：剩余水雷 / 上限。
     * 必须与 getNumHUDBars() 一起看：只有请求了第二行高度，这行字才有地方放。
     */
    @Override
    public String getAmmoText() {
        return TEXT_AMMO_PREFIX + getCharges() + "/" + calcMaxCharges();
    }

    /**
     * 状态条占用的行数：第一行状态条，第二行弹药行。
     */
    @Override
    public int getNumHUDBars() {
        return HUD_BAR_ROWS;
    }

    /**
     * 状态条右侧文字：只在"确实用不了"时给出原因，其余情况返回 null。
     * <p>
     * 基类契约是"返回非空即常驻显示"，所以绝不能在就绪时也返回文字，
     * 否则状态条右侧会一直挂着一串字，和左侧的状态文字重复。
     * <p>
     * 落点来自 AI 标记时，不做"无目标 / 超出射程"这类提示：
     * 那是 AI 的内部工作坐标，且标记有 1 秒残留期，
     * 本舰一移动残留坐标就会被判成超距，会误导玩家。
     */
    @Override
    public String getExtraInfoText() {
        if (ship == null) {
            return null;
        }

        // 没有水雷了
        if (getCharges() <= 0) {
            return TEXT_NO_AMMO;
        }

        // 过载或排气中
        if (ship.getFluxTracker() != null && ship.getFluxTracker().isOverloadedOrVenting()) {
            return TEXT_FLUX_TOO_HIGH;
        }

        // 落点由 AI 决定 → 交给 AI 自己判断，HUD 不做基于落点的提示
        if (isTargetFromAI()) {
            return null;
        }

        // 手动操控时：只在"就绪 / 激活"这两个阶段提示落点相关问题
        if (!isReady() && !isActive()) {
            return null;
        }

        // 没有落点（鼠标没指向有效位置）
        if (resolveTarget() == null) {
            return TEXT_NO_TARGET;
        }

        // 落点超出射程（注意：与原版一致，这里只做提示，不阻止使用 ——
        // 超出时水雷会被投放到最大射程处）
        if (isTargetOutOfRange()) {
            return TEXT_OUT_OF_RANGE;
        }

        return null;
    }

    /**
     * 判断当前落点是否超出"最大射程 + 自身碰撞半径"。
     * 复用 isDropPointInRange()，保证与 AI 侧的钳制判定完全一致。
     *
     * @return true 表示超出射程
     */
    public boolean isTargetOutOfRange() {
        Vector2f target = resolveTarget();
        if (target == null) {
            return false;
        }
        return !isDropPointInRange(target);
    }

    /**
     * 原版 isUsable()：没有可用的落点就不允许使用。
     * 这里额外调用 super，保留基类对舰船状态的检查。
     */
    @Override
    public boolean canActivate() {
        if (ship == null || !ship.isAlive()) {
            return false;
        }
        if (!super.canActivate()) {
            return false;
        }
        return resolveTarget() != null;
    }

    // 注意：不要覆写 canActivateInternal()。
    // 基类的实现已经包含"状态是否就绪、是否有充能、幅能是否够、目标与射程检查"，
    // 自己再写一遍很容易与 HUD 提示对不上。

    // ==================== 十二、原版 getRange() / 接口实现 ====================

    /**
     * 原版静态方法：基础投放距离被舰船的"系统射程加成"修正。
     * 每次调用都重新计算（不缓存），射程加成在战斗中变化时也能跟上。
     *
     * @param ship 目标舰船，可为 null
     * @return 实际投放距离
     */
    public static float getRange(ShipAPI ship) {
        if (ship == null) {
            return MINE_RANGE;
        }
        return ship.getMutableStats().getSystemRangeBonus().computeEffective(MINE_RANGE);
    }

    /** 原版 getMaxRange(ship) → 直接等于投放距离 */
    protected float getMaxRange(ShipAPI ship) {
        return getMineRange(ship);
    }

    /** 原版 getFuseTime()：引信时间 3 秒；★ 布雷 AI 也读这个方法 */
    @Override
    public float getFuseTime() {
        return FUSE_TIME;
    }

    /** 原版 getMineRange(ship)：等于被射程加成修正后的投放距离；★ 布雷 AI 也读这个方法 */
    @Override
    public float getMineRange(ShipAPI ship) {
        return getRange(ship);
    }

    // ==================== 十三、剩余参数覆写 ====================

    /** 子系统优先级：同类中较高者优先分配键位，也决定 HUD 渲染顺序 */
    @Override
    public int getOrder() {
        return ORDER_SHIP_UNIQUE;
    }

    /** 需要键位 */
    @Override
    public boolean canAssignKey() {
        return true;
    }

    /** 渐入时长（必须 > 0） */
    @Override
    public float getBaseInDuration() {
        return DURATION_IN;
    }

    /** 维持时长（必须 > 0，否则状态条比例计算无效） */
    @Override
    public float getBaseActiveDuration() {
        return DURATION_ACTIVE;
    }

    /** 渐出时长（必须 > 0） */
    @Override
    public float getBaseOutDuration() {
        return DURATION_OUT;
    }

    /** 冷却时长（必须 > 0） */
    @Override
    public float getBaseCooldownDuration() {
        return MINE_COOLDOWN;
    }

    /** 启动时消耗的幅能（占最大幅能的比例）；★ 布雷 AI 也读这个方法 */
    @Override
    public float getFluxCostPercentOnActivation() {
        return FLUX_COST_PERCENT;
    }

    /** 过载中不可使用（与原版系统一致） */
    @Override
    public boolean canUseWhileOverloaded() {
        return false;
    }

    /** 排气中不可使用（与原版系统一致） */
    @Override
    public boolean canUseWhileVenting() {
        return false;
    }

    /** 显示名 */
    @Override
    public String getDisplayText() {
        return DISPLAY_TEXT;
    }

    /** 简要说明（只在 HUD 的"更多信息"模式显示） */
    @Override
    public String getBriefText() {
        return BRIEF_TEXT;
    }
}