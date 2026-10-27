package data.scripts.shipsystems.ai;// 包路径：模组的舰船系统/AI 脚本目录

// 游戏核心 API：战斗引擎、舰船/系统/编队/武器/指令等接口
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.FighterWingAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipSystemAIScript;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.AIHints;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponSize;
// 官方 API 包内的"布雷系统参数提供者"接口（仅在未绑定子系统时作为兜底数据源）
import com.fs.starfarer.api.impl.combat.MineStrikeStatsAIInfoProvider;
// 通用工具：随机区间计时器、超时跟踪器、杂项数学工具
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.TimeoutTracker;

import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.List;

// ★ 本 AI 的唯一数据源 —— 子系统本体
import data.scripts.shipsystems.subsystems.VIII_MineStrikeSubsystem;

/**
 * 空雷突袭子系统的布雷决策 AI。
 * <p>
 * 本类由原版内部类 com.fs.starfarer.combat.ai.system.MineStrikeSystemAI 的反编译代码重写而来，
 * 所有内部包引用都已替换为 com.fs.starfarer.api.* 中的公开接口。
 * <p>
 * 【调用方式（非常重要）】
 * 本类**必须**由 {@link VIII_MineStrikeSubsystem#shouldActivateAI(float)} 逐帧驱动。
 * 原因：MagicLib 只提供 shouldActivateAI(float) 这一个"AI 是否想开火"的询问入口，
 * 基类的 advance(amount, isPaused) 只是留给你写自己逐帧逻辑的钩子，不会代为询问 AI，
 * 也不会代为激活子系统。因此凡是把它写进其它方法（构造器、advance、外部插件）的做法，
 * 都会得到一个"永远不会被调用"的死对象。
 * <p>
 * 【数据源】射程 / 引信时间 / 就绪状态 / 剩余水雷 / 启动幅能消耗全部向子系统索取：
 * - 投放距离      → subsystem.getMineRange(ship)      （每次决策现算，跟随射程加成）
 * - 落点距离上限  → subsystem.getMaxDropRange()       （= 投放距离 + 自身碰撞半径）
 * - 引信时间      → subsystem.getFuseTime()
 * - 是否可以开火  → subsystem.isReady() + 剩余水雷 > 0
 * - 剩余水雷      → subsystem.getAmmo() / getMaxAmmo()
 * - 单次幅能占比  → 子系统固定消耗 + 百分比消耗 × 最大幅能
 * <p>
 * 【入口守卫的修正】
 * 旧版本第一行写的是 "if (ship == null || system == null) return;"，
 * 而 system 来自 ship.getSystem()（舰船主系统）。对于船体本身没有主系统的船，
 * getSystem() 返回 null，于是哪怕 shouldActivateAI 每帧都在调用本类，
 * 本类也会每帧当场返回 —— 现象就是"AI 完全没反应"。
 * 现在守卫只要求"本舰非空 + 有数据源"，舰船主系统仅作兜底。
 */
public class VIII_MineStrikeSystemAI implements ShipSystemAIScript {

    // ==================== 一、可调参数（全部为 mod 侧常量，方便平衡） ====================

    /**
     * 调试开关。开启后会在"决定布雷"时写一条日志，用于确认 AI 真的被调用、
     * 以及它到底往哪儿扔雷。正式发布时请保持 false。
     */
    public static boolean DEBUG_LOG = false;

    // 决策节流：每 0.125~0.25 秒（随机）才做一次完整评估。
    // 这是性能优化的关键——底下要遍历全场舰船与武器，不能每帧都跑。
    public static float DECISION_MIN_INTERVAL = 0.125F;
    public static float DECISION_MAX_INTERVAL = 0.25F;

    // 开了这一次就会逼近过载的判定线（幅能水位 + 单次消耗 ≥ 该值时忍住不开）
    public static float MAX_FLUX_LEVEL_TO_FIRE = 0.95F;

    // ---------- 第二层（战机编队）相关常量 ----------
    // 编队离本舰超出"落点距离上限 + 该冗余"就不考虑投雷
    public static float WING_RANGE_SLOP = 200.0F;
    // 统计"编队里有几架飞机真的聚在编队坐标附近"的半径
    public static float WING_CLUSTER_RADIUS = 300.0F;
    // 轰炸机威胁最大，分数倍数
    public static float WING_BOMBER_SCORE_MULT = 2.0F;
    // 编队已经逼到该距离以内 → 加分（它的鱼雷/炸弹随时会落在本舰身上）
    public static float WING_CLOSE_DIST = 500.0F;
    public static float WING_CLOSE_BONUS = 3.0F;
    // 落点自保下限：至少离自己"该值 + 自身碰撞半径"，别把自己炸了
    public static float WING_MIN_SAFE_DIST = 250.0F;
    // 刚被雷照顾过的编队锁定时间额外值（秒）：锁定总时长 = 引信时间 + 该值
    public static float WING_LOCK_EXTRA = 5.0F;
    // 原版留出的"额外提前量"，反编译后为常量 0，这里保留为可调项
    public static float WING_EXTRA_LEAD = 0.0F;

    // ---------- 第三层（舰船）相关常量 ----------
    // 超出"落点距离上限 + 对方碰撞半径 + 该冗余"视为打不到
    public static float SHIP_RANGE_SLOP = 100.0F;
    // 候选落点相对目标中心的偏移距离
    public static float SHIP_DROP_OFFSET = 100.0F;
    // "沿自身朝向前进"的速度分量门限（距离单位/秒），用于判断对方是在冲锋还是横移
    public static float SHIP_FORWARD_SPEED_THRESHOLD = 30.0F;
    // 非满弹时，舰船得分必须达到该值才值得开系统；满弹时可直接开
    public static float SHIP_SCORE_TO_FIRE = 5.0F;
    // 有效近防火力占比低于该值 → 判定对方防空瘫痪
    public static float PD_BROKEN_FRACTION = 0.34F;
    // 对方幅能水位偏高的门限
    public static float HIGH_FLUX_LEVEL = 0.75F;
    // "最近受过伤害"的时间窗（秒）：用于近似原版的"正在被集火"判定
    public static float RECENT_DAMAGE_WINDOW = 10.0F;

    // ---------- 安全投放相关常量 ----------
    // 落点必须离本方非战机友军至少这么远
    public static float FRIENDLY_SAFETY_RADIUS = 500.0F;
    // 落点离"自己"只需这么远（打分阶段已经把"250 + 自身碰撞半径"算进落点了）
    public static float SELF_SAFETY_RADIUS = 200.0F;

    // ---------- 兜底参数（没有绑定子系统时才会用到） ----------
    // 默认引信时间（秒）：从投放到自爆的时长，用于计算提前量
    public static float DEFAULT_FUSE_TIME = 2.0F;
    // 默认投放距离：读不到子系统时使用
    public static float DEFAULT_MINE_RANGE = 700.0F;

    // ==================== 二、运行期状态 ====================

    // 本舰（由 init 或子系统的懒初始化注入）
    protected ShipAPI ship;
    /** 舰船自身的主系统：仅用于"未绑定子系统"时的兜底，正常流程不读取它的任何数值 */
    protected ShipSystemAPI system;
    // 本舰的 AI 状态标记集合（用于把"落点坐标"告诉子系统）
    protected ShipwideAIFlags flags;
    protected CombatEngineAPI engine;

    /**
     * ★ 本子系统实例：AI 的唯一数据源。
     * 由 {@link VIII_MineStrikeSubsystem} 通过 {@link #setSubsystem} 注入（每次注入都覆盖）。
     */
    protected VIII_MineStrikeSubsystem subsystem;

    // 决策节流器：每 0.125~0.25 秒（随机）评估一次（替代内部 IntervalTracker）
    protected IntervalUtil decisionInterval = new IntervalUtil(DECISION_MIN_INTERVAL, DECISION_MAX_INTERVAL);

    // 兜底用：开一次"舰船主系统"消耗的幅能 ÷ 本舰最大幅能（仅在未绑定子系统时使用）
    protected float fluxPerUseFraction = 0.0F;

    // 兜底用的引信时间与投放距离（仅在未绑定子系统、且舰船系统脚本也没提供时使用）
    protected float fuseTime = DEFAULT_FUSE_TIME;
    protected float mineRange = DEFAULT_MINE_RANGE;

    // 记录"某个敌方战机编队刚被雷照顾过"，在一段时间内降低它的优先级，
    // 避免连扔两发雷炸同一群已经散开的敌机。
    protected TimeoutTracker<FighterWingAPI> wingTimeoutTracker = new TimeoutTracker<FighterWingAPI>();

    // ==================== 三、数据源注入与初始化 ====================

    /**
     * ★ 由子系统调用，把子系统自身交给本 AI 当唯一数据源。
     * 访问器每次都现算，不缓存数值，因此调用顺序无关紧要。
     *
     * @param subsystem 本子系统实例
     */
    public void setSubsystem(VIII_MineStrikeSubsystem subsystem) {
        this.subsystem = subsystem;
    }

    /**
     * 注入本舰、舰船系统、AI 标记集合与战斗引擎。
     * <p>
     * 注意：如果本 AI 由 {@link VIII_MineStrikeSubsystem} 驱动，这里的 system 参数是
     * "舰船自身的主系统"，我们**不再**把它当作射程/弹药/冷却的来源，它只是兜底。
     * system 允许为 null（船体本身没有主系统是合法情况）。
     *
     * @param ship   本舰
     * @param system 本舰的舰船系统（可为 null，仅作兜底）
     * @param flags  本舰的 AI 标记集合（可为 null）
     * @param engine 战斗引擎（可为 null，但那样本帧不会做任何事）
     */
    public void init(ShipAPI ship, ShipSystemAPI system, ShipwideAIFlags flags, CombatEngineAPI engine) {
        this.ship = ship;
        this.system = system;
        this.flags = flags;
        this.engine = engine;

        // 每次初始化都重置计时与锁定状态，防止实例复用导致的状态串味
        this.decisionInterval = new IntervalUtil(DECISION_MIN_INTERVAL, DECISION_MAX_INTERVAL);
        this.wingTimeoutTracker = new TimeoutTracker<FighterWingAPI>();

        // 兜底数据复位
        this.fuseTime = DEFAULT_FUSE_TIME;
        this.mineRange = DEFAULT_MINE_RANGE;
        this.fluxPerUseFraction = 0.0F;

        if (ship != null && system != null) {
            // 单次开主系统的幅能占比（MaxFlux 为 0 的概率极低，但仍做保护，避免除零）
            float maxFlux = Math.max(ship.getMaxFlux(), 1.0F);
            this.fluxPerUseFraction = system.getFluxPerUse() / maxFlux;
        }

        // ---------- 只有"没绑定子系统"时才去读舰船主系统当兜底 ----------
        if (this.subsystem == null && system != null && ship != null) {
            boolean readFromScript = false;

            // 先取脚本（静态类型是 ShipSystemStatsScript），再用 Object 做 instanceof
            Object script = system.getScript();
            if (script instanceof MineStrikeStatsAIInfoProvider) {
                MineStrikeStatsAIInfoProvider info = (MineStrikeStatsAIInfoProvider) script;
                this.fuseTime = info.getFuseTime();
                this.mineRange = info.getMineRange(ship);
                readFromScript = true;
            }

            // 脚本没提供射程时，退而使用主系统规格里的射程（公开接口）
            if (!readFromScript && system.getSpecAPI() != null) {
                float specRange = system.getSpecAPI().getRange(ship.getMutableStats());
                if (specRange > 0.0F) {
                    this.mineRange = specRange;
                }
            }
        }

        // 兜底值合法性保护
        if (this.fuseTime <= 0.0F) {
            this.fuseTime = DEFAULT_FUSE_TIME;
        }
        if (this.mineRange <= 0.0F) {
            this.mineRange = DEFAULT_MINE_RANGE;
        }
    }

    // ==================== 四、射程 / 引信 / 弹药 / 幅能：统一向子系统索取 ====================
    // 注意：全部是"方法"而不是缓存的字段值。
    // 原因：投放距离由舰船的"系统射程加成"决定，而该加成可能在战斗中变化，
    //       在 init() 里算一次就冻结的话，射程一变就永远用错值。

    /**
     * 本 AI 是否有可用的数据源。
     * 只要绑定了子系统，或者还能退回去读舰船系统，就算有；
     * ★ 注意不要把"舰船自身主系统存在"当作硬性前提 —— 很多船体根本没有主系统。
     *
     * @return true 表示至少有一个可取数的来源
     */
    public boolean hasDataSource() {
        return this.subsystem != null || this.system != null;
    }

    /**
     * 实际投放距离（距离单位）。
     *
     * @return 优先取子系统的值；未绑定子系统时取兜底值
     */
    public float getMineRange() {
        if (this.subsystem != null && this.ship != null) {
            float range = this.subsystem.getMineRange(this.ship);
            if (range > 0.0F) {
                return range;
            }
        }
        return this.mineRange;
    }

    /**
     * ★ 落点距离上限 = 投放距离 + 自身碰撞半径。
     * 与子系统的超距判定（isDropPointInRange）使用同一把尺子，
     * 保证本 AI 算出来的落点永远不会被子系统判为超距。
     *
     * @return 落点距本舰的最大允许距离
     */
    public float getMaxDropRange() {
        if (this.subsystem != null) {
            float range = this.subsystem.getMaxDropRange();
            if (range > 0.0F) {
                return range;
            }
        }
        float selfRadius = (this.ship != null) ? this.ship.getCollisionRadius() : 0.0F;
        return getMineRange() + selfRadius;
    }

    /**
     * 水雷引信时间（秒）。
     *
     * @return 优先取子系统的值
     */
    public float getFuseTime() {
        if (this.subsystem != null) {
            float fuse = this.subsystem.getFuseTime();
            if (fuse > 0.0F) {
                return fuse;
            }
        }
        return this.fuseTime;
    }

    /**
     * 当前是否可以开火：子系统处于"就绪"且还有水雷；未绑定子系统时退回旧逻辑。
     *
     * @return true 表示可以开火
     */
    protected boolean isSubsystemReady() {
        if (this.subsystem != null) {
            return this.subsystem.isReady() && this.subsystem.getAmmo() > 0;
        }

        // 兜底：未绑定子系统时退回"读舰船系统"的老逻辑
        if (this.system == null) {
            return false;
        }
        return this.system.getCooldownRemaining() <= 0.0F && !this.system.isOutOfAmmo();
    }

    /**
     * 剩余水雷数。
     *
     * @return 优先取子系统的充能数
     */
    protected int getCharges() {
        if (this.subsystem != null) {
            return this.subsystem.getAmmo();
        }
        return (this.system == null) ? 0 : this.system.getAmmo();
    }

    /**
     * 水雷上限。
     *
     * @return 优先取子系统的上限
     */
    protected int getMaxCharges() {
        if (this.subsystem != null) {
            return Math.max(this.subsystem.getMaxAmmo(), 1);
        }
        return (this.system == null) ? 1 : Math.max(this.system.getMaxAmmo(), 1);
    }

    /**
     * 开一次系统消耗的幅能占最大幅能的比例。
     *
     * @return 0~1 的比例；优先按子系统的固定消耗 + 百分比消耗计算
     */
    protected float getFluxCostFraction() {
        if (this.subsystem != null && this.ship != null) {
            float maxFlux = Math.max(this.ship.getMaxFlux(), 1.0F);
            float cost = this.subsystem.getFluxCostFlatOnActivation()
                    + this.subsystem.getFluxCostPercentOnActivation() * maxFlux;
            return cost / maxFlux;
        }
        return this.fluxPerUseFraction;
    }

    // ==================== 五、ShipSystemAIScript 接口实现 ====================

    /**
     * 逐帧决策。**只能由 {@link VIII_MineStrikeSubsystem#shouldActivateAI(float)} 调用**，
     * 或者在你把它注册成舰船主系统的 aiScript 时由引擎调用（那种情况下走兜底数据源）。
     *
     * @param amount             自上一帧以来的游戏时间（秒）
     * @param missileDangerDir   导弹危险方向（未使用）
     * @param collisionDangerDir 碰撞危险方向（未使用）
     * @param target             本舰当前主目标（可能为 null）
     */
    public void advance(float amount, Vector2f missileDangerDir, Vector2f collisionDangerDir, ShipAPI target) {
        // ★ 守卫只要求"本舰存在 + 有数据源"。
        //   旧版本在这里写 "|| this.system == null"，而 system 是舰船主系统：
        //   船体没有主系统时 getSystem() 返回 null，整段 AI 就会每帧当场返回，
        //   表现得就像"这个 AI 从来没有被调用过"。
        if (this.ship == null) {
            return;
        }
        if (!hasDataSource()) {
            return;
        }

        // 战斗暂停时不推进任何计时（避免暂停期间反复决策）
        if (this.engine != null && this.engine.isPaused()) {
            return;
        }

        // 舰船已阵亡/离场 → 直接放弃
        if (!this.ship.isAlive()) {
            return;
        }

        this.decisionInterval.advance(amount);
        this.wingTimeoutTracker.advance(amount);

        // 还没到下一个决策时刻 → 本帧什么都不做
        if (!this.decisionInterval.intervalElapsed()) {
            return;
        }

        // ================= 第一层：硬性资格检查（全部走子系统） =================

        // 子系统还在冷却 / 进入中 / 激活中 / 收束中，或者没有水雷了
        if (!isSubsystemReady()) {
            return;
        }
        // 幅能追踪器拿不到（理论上不会发生）→ 放弃，避免空指针
        if (this.ship.getFluxTracker() == null) {
            return;
        }
        // 过载或排气中，此时无法使用系统
        if (this.ship.getFluxTracker().isOverloadedOrVenting()) {
            return;
        }
        // 开了这一次就会逼近过载（幅能水位 + 单次消耗 ≥ 阈值）→ 忍住不开
        float fluxLevel = this.ship.getFluxTracker().getFluxLevel();
        if (fluxLevel + getFluxCostFraction() >= MAX_FLUX_LEVEL_TO_FIRE) {
            return;
        }

        // ★ 本次决策的两把尺子：一次性算好，供下面两层使用
        float maxDropRange = getMaxDropRange();
        float fuse = getFuseTime();

        // 取全场舰船一次，供下面两层复用
        List<ShipAPI> allShips = (this.engine != null) ? this.engine.getShips() : new ArrayList<ShipAPI>();
        if (allShips == null || allShips.isEmpty()) {
            return;
        }

        // ================= 第二层：给敌方【战机编队】打分 =================
        FighterWingAPI bestWing = null;
        Vector2f bestWingPoint = null;
        float bestWingScore = 0.0F;

        List<FighterWingAPI> wingsChecked = new ArrayList<FighterWingAPI>();

        for (ShipAPI wingLeader : allShips) {
            // 只看敌方战机编队的队长；舰船留给第三层处理
            if (!isEnemyOfUs(wingLeader) || !wingLeader.isFighter() || !wingLeader.isAlive()) {
                continue;
            }

            FighterWingAPI wing = wingLeader.getWing();
            if (wing == null) {
                continue; // 无人机等没有编队归属的单位跳过
            }
            if (wing.getLeader() != wingLeader) {
                continue; // 同一编队只处理一次（只处理队长）
            }
            if (wingsChecked.contains(wing)) {
                continue;
            }
            wingsChecked.add(wing);

            // 编队离本舰太远（超出落点距离上限 + 容差）→ 跳过
            float distToWing = Misc.getDistance(this.ship.getLocation(), wingLeader.getLocation());
            if (distToWing > maxDropRange + WING_RANGE_SLOP) {
                continue;
            }

            // 统计"编队里有几架飞机真的聚在编队坐标附近"，聚得越密，一发雷的收益越高
            float clustered = 0.0F;
            List<ShipAPI> members = wing.getWingMembers();
            if (members != null) {
                for (ShipAPI memberShip : members) {
                    if (memberShip == null || !memberShip.isAlive()) {
                        continue; // 已被击落的成员不计入
                    }
                    float distToMember = Misc.getDistance(wingLeader.getLocation(), memberShip.getLocation());
                    if (distToMember <= WING_CLUSTER_RADIUS) {
                        clustered += 1.0F;
                    }
                }
            }
            // 一架都没聚在这 → 编队已散开，没有一网打尽的价值
            if (clustered <= 0.0F) {
                continue;
            }

            // 轰炸机威胁最大，分数翻倍
            if (wing.getSpec() != null && wing.getSpec().isBomber()) {
                clustered *= WING_BOMBER_SCORE_MULT;
            }
            // 已经逼到近距离 → 它的鱼雷/炸弹随时会落在本舰身上，加分
            if (distToWing < WING_CLOSE_DIST) {
                clustered += WING_CLOSE_BONUS;
            }
            // 刚刚才对这个编队扔过雷 → 减去"锁定剩余时间"作为惩罚
            clustered -= this.wingTimeoutTracker.getRemaining(wing);

            if (clustered > bestWingScore) {
                // 引信燃尽时敌机大概会飞到哪：上限 = 最大速度 × 引信时间（+ 额外提前量）
                float leadLimit = wingLeader.getMaxSpeed() * (fuse + WING_EXTRA_LEAD);
                // 下限：至少离自己"安全距离 + 自身碰撞半径"，别把自己炸了
                float leadDist = Math.max(distToWing, WING_MIN_SAFE_DIST + this.ship.getCollisionRadius());
                leadDist = Math.min(leadDist, leadLimit);

                // ★ 再按"剩余可用距离"封顶：由三角不等式可知
                //   dist(本舰, 预测点) ≤ distToWing + leadDist，
                //   所以只要 leadDist ≤ maxDropRange - distToWing，
                //   预测点就必然落在射程之内，不会被子系统判为超距。
                float room = maxDropRange - distToWing;
                if (room < 0.0F) {
                    room = 0.0F;
                }
                leadDist = Math.min(leadDist, room);

                Vector2f wingLoc = toVec(wingLeader.getLocation());

                // 候选落点：沿敌机速度方向预测，拦在它前面
                Vector2f predicted = computePointAlongVelocity(wingLoc, wingLeader.getVelocity(), leadDist);

                // 如果这群敌机正在攻击本舰，就把"预测点"和"朝我而来的方向点"折中一半
                // （两点都在射程圆盘内，圆盘是凸集，折中点必然也在射程内）
                if (wingLeader.getShipTarget() == this.ship) {
                    Vector2f towardMe = getPointInDirection(wingLoc,
                            Misc.getAngleInDegrees(wingLeader.getLocation(), this.ship.getLocation()),
                            leadDist);
                    Vector2f.add(predicted, towardMe, predicted);
                    predicted.scale(0.5F);
                }

                // 检查落点是否会覆盖本方友军（内部会先钳进射程再做友军检查）；
                // 若预测点不可用，退而求其次——直接把雷扔在编队当前坐标上
                Vector2f drop = findPointAwayFromFriendlies(predicted, wingLoc);
                if (drop != null) {
                    bestWingScore = clustered;
                    bestWing = wing;
                    bestWingPoint = drop;
                }
            }
        }

        // ================= 第三层：给敌方【舰船】打分 =================
        ShipAPI bestShip = null;
        Vector2f bestShipPoint = null;
        float bestShipScore = 0.0F;

        for (ShipAPI enemy : allShips) {
            if (enemy == null || !enemy.isAlive()) {
                continue;
            }
            if (!isEnemyOfUs(enemy)) {
                continue; // 含：自己人、中立残骸（owner=100）、已失效单位
            }
            if (enemy.isShuttlePod()) {
                continue; // 穿梭舱（撤退单位）不值得浪费雷
            }
            if (enemy.isFighter()) {
                continue; // 战机已在上一段处理过
            }
            if (enemy.isHulk()) {
                continue; // 已经变成残骸
            }

            // 超出"落点距离上限 + 对方碰撞半径 + 容差"→ 打不到
            float dist = Misc.getDistance(this.ship.getLocation(), enemy.getLocation());
            if (dist > maxDropRange + enemy.getCollisionRadius() + SHIP_RANGE_SLOP) {
                continue;
            }

            // ----- 评分项 A：对方还剩多少近防火力 -----
            float pdAlive = 0.0F;
            float pdTotal = 1.0E-4F; // 分母加一个极小值，防止除零
            List<WeaponAPI> weapons = enemy.getAllWeapons();
            if (weapons != null) {
                for (WeaponAPI weapon : weapons) {
                    if (weapon == null || weapon.isDecorative()) {
                        continue; // 装饰性武器不参与计算
                    }

                    // 武器尺寸权重：小=1、中=2、大=4
                    float sizeMult = 1.0F;
                    WeaponSize size = weapon.getSize();
                    if (size == WeaponSize.MEDIUM) {
                        sizeMult = 2.0F;
                    } else if (size == WeaponSize.LARGE) {
                        sizeMult = 4.0F;
                    }

                    if (weapon.hasAIHint(AIHints.PD)) {
                        if (!weapon.isDisabled()) {
                            pdAlive += sizeMult; // 分子：只有还能开火的近防武器才算数
                        }
                        pdTotal += sizeMult;     // 分母：不管坏没坏都计入总量
                    }
                }
            }

            // 有效近防火力占比低于阈值 → 对方防空基本瘫痪，雷能飞得进去
            boolean pdBroken = (pdAlive / pdTotal) < PD_BROKEN_FRACTION;
            // 对方过载或正在排气 → 无法拦截，也无法闪避
            boolean overloaded = (enemy.getFluxTracker() != null)
                    && enemy.getFluxTracker().isOverloadedOrVenting();
            // 是不是本舰当前的主目标（正在打的那个）
            boolean isCurrentTarget = (enemy == target);
            // 幅能水位偏高
            boolean highFlux = (enemy.getFluxTracker() != null)
                    && enemy.getFluxTracker().getFluxLevel() >= HIGH_FLUX_LEVEL;
            // 【与原版的差异】"最近 10 秒吃到的伤害 ≥ 自身最大结构值 20%"需要内部伤害统计接口，
            // 公开 API 不提供；这里用 getSinceLastDamageTaken()（距上次受伤的秒数）来近似
            boolean beingFocused = enemy.getSinceLastDamageTaken() <= RECENT_DAMAGE_WINDOW;

            // ----- 评分项 B：加权汇总 -----
            float score = 1.0F;
            if (pdBroken) {
                score += 2.0F;                  // 防空瘫痪，+2
            }
            if (overloaded) {
                score += 4.0F;                  // 过载/排气，+4（最高权重）
            }
            if (highFlux && beingFocused) {
                score += 3.0F;                  // 又热又挨打，+3
            }
            if (isCurrentTarget) {
                score *= 2.0F;                  // 当前主目标，总分翻倍
            }

            if (score > bestShipScore) {
                Vector2f enemyLoc = toVec(enemy.getLocation());

                // 候选落点 1：沿对方速度方向预测（拦路）
                Vector2f alongVel = computePointAlongVelocity(enemyLoc, enemy.getVelocity(),
                        enemy.getCollisionRadius() + SHIP_DROP_OFFSET);
                // 候选落点 2：对方正后方（朝向 +180 度）
                Vector2f behind = getPointInDirection(enemyLoc, enemy.getFacing() + 180.0F,
                        enemy.getCollisionRadius() + SHIP_DROP_OFFSET);

                // 对方朝向的单位向量与速度点乘 → 得到"沿自身朝向前进的速度分量"
                Vector2f facingUnit = Misc.getUnitVectorAtDegreeAngle(enemy.getFacing());
                float forwardSpeed = Vector2f.dot(facingUnit, enemy.getVelocity());

                Vector2f drop;
                if (enemy.isRetreating() || (forwardSpeed > SHIP_FORWARD_SPEED_THRESHOLD && Math.random() > 0.5D)) {
                    // 情况一：对方在撤退，或在向前冲锋（且随机命中一半）→ 优先"拦路"
                    if (!enemy.isRetreating() && Math.random() > 0.5D) {
                        // 交换两个候选点，把"对方当前位置"也纳入候选
                        Vector2f tmp = enemyLoc;
                        enemyLoc = alongVel;
                        alongVel = tmp;
                    }
                    // 按优先级依次尝试：拦路点 → 当前位置 → 后方点
                    drop = findPointAwayFromFriendlies(alongVel, enemyLoc, behind);
                } else {
                    // 情况二：对方正在靠近或横向机动 → 优先"堵屁股"，逼它不敢回头
                    if (Math.random() > 0.5D) {
                        Vector2f tmp = enemyLoc;
                        enemyLoc = behind;
                        behind = tmp;
                    }
                    drop = findPointAwayFromFriendlies(behind, alongVel, enemyLoc);
                }

                if (drop != null) {
                    bestShip = enemy;
                    bestShipScore = score;
                    bestShipPoint = drop;
                }
            }
        }

        // ================= 第四层：在"打编队"和"打舰船"之间做决断 =================
        // 弹药是满的（一次都没用过）→ 放宽开火门槛，不浪费装填机会
        boolean fullAmmo = (getCharges() >= getMaxCharges());

        // 战机编队得分更高，且满足"值得出手"的条件：
        //   是轰炸机 或 编队里还有 ≥2 架存活 或 弹药满（满弹时可以顺手清一架侦察机）
        if (bestWingScore > bestShipScore && bestWing != null && bestWingScore > 0.0F
                && (isBomberWing(bestWing) || countAliveWingMembers(bestWing) >= 2 || fullAmmo)) {
            useSystemAt(bestWingPoint);
            // 给这个编队加锁：引信时间 + 额外值 秒内不再优先照顾它
            this.wingTimeoutTracker.add(bestWing, fuse + WING_LOCK_EXTRA);
            return;
        }

        // 打舰船：满弹直接打；否则得分必须达标才值得（低于阈值说明只是普通目标）
        if (bestShip != null && bestShipScore > 0.0F && (fullAmmo || bestShipScore >= SHIP_SCORE_TO_FIRE)) {
            useSystemAt(bestShipPoint);
        }
    }

    // ==================== 六、开火与坐标写入 ====================

    /**
     * 请求在指定坐标布雷。
     * <p>
     * 由 {@link VIII_MineStrikeSubsystem} 驱动时，本方法会被子系统的匿名子类覆写，
     * 改成"把落点交回子系统"而不是给舰船下 USE_SYSTEM 指令
     * （后者开的是舰船主系统，不是本子系统）。
     *
     * @param point 布雷落点
     */
    public void useSystemAt(Vector2f point) {
        if (this.ship == null || point == null) {
            return;
        }

        // 最后一道保险：任何路径交给系统之前，落点都被钳进射程内
        Vector2f safe = clampIntoRange(point);
        if (safe == null) {
            safe = new Vector2f(point);
        }

        if (DEBUG_LOG) {
            Global.getLogger(VIII_MineStrikeSystemAI.class).info(
                    "空雷突袭 AI：决定布雷，落点 = ("
                            + (int) safe.x + ", " + (int) safe.y + ")，距离 = "
                            + (int) Misc.getDistance(this.ship.getLocation(), safe)
                            + "，投放距离 = " + (int) getMineRange());
        }

        // 告诉系统"目标坐标就在这里"：绝大多数瞄准型舰船系统会读这个 flag
        if (this.flags != null) {
            this.flags.setFlag(AIFlags.SYSTEM_TARGET_COORDS, 1.0F, safe);
        }

        // 真正下令开系统（giveCommand 仅应由 AI 使用，而本类正是系统的 AI）
        this.ship.giveCommand(ShipCommand.USE_SYSTEM, null, 0);
    }

    // ==================== 七、落点钳制与安全检查 ====================

    /**
     * ★ 把任意点钳制到"本舰可投放范围"之内。
     * 优先交给子系统处理，保证 AI 与 HUD 的"超距"判定用同一把尺子、同一份射程加成。
     *
     * @param point 原始点
     * @return 钳制后的点；入参非法时返回 null
     */
    public Vector2f clampIntoRange(Vector2f point) {
        if (this.ship == null || point == null) {
            return null;
        }

        Vector2f p = new Vector2f(point);

        if (this.subsystem != null) {
            return this.subsystem.clampDropPointIntoRange(p);
        }

        // 兜底实现：沿"本舰 → 该点"方向压回最大距离处
        float max = this.getMaxDropRange();
        if (max < 1.0F) {
            max = 1.0F;
        }

        float dist = Misc.getDistance(this.ship.getLocation(), p);
        if (dist <= max) {
            return p;
        }

        float dir = Misc.getAngleInDegrees(this.ship.getLocation(), p);
        Vector2f offset = Misc.getUnitVectorAtDegreeAngle(dir);
        offset.scale(max);

        Vector2f result = new Vector2f();
        Vector2f.add(this.ship.getLocation(), offset, result);
        return result;
    }

    /**
     * ★ 在若干候选落点中，返回第一个"先钳进射程、再确认远离友军"的点。
     * 传入顺序 = 优先级顺序；全部不安全则返回 null（放弃本次投雷）。
     *
     * @param candidates 候选落点（按优先级排列）
     * @return 安全落点，或 null
     */
    public Vector2f findPointAwayFromFriendlies(Vector2f... candidates) {
        if (candidates == null) {
            return null;
        }

        for (Vector2f point : candidates) {
            if (point == null) {
                continue;
            }

            // ① 先钳进射程
            Vector2f inRange = clampIntoRange(point);
            if (inRange == null) {
                continue;
            }

            // ② 再做友军安全检查
            if (isPointAwayFromFriendlies(inRange, FRIENDLY_SAFETY_RADIUS)) {
                return inRange;
            }
        }
        return null;
    }

    /**
     * 判断某点是否远离本方友军，并且不会炸到自己。
     * 只检查"非战机"的本方单位——战机是可以承受被雷炸的代价的，
     * 若连战机一并计入，AI 永远找不到安全落点，雷就扔不出去。
     *
     * @param point   候选落点
     * @param minDist 与友军的最小安全距离
     * @return true 表示安全
     */
    protected boolean isPointAwayFromFriendlies(Vector2f point, float minDist) {
        if (this.ship == null || point == null) {
            return true;
        }

        // ---------- ① 先检查自己：别把雷扔在自己脚下 ----------
        float selfDist = Misc.getDistance(point, this.ship.getLocation());
        if (selfDist <= SELF_SAFETY_RADIUS + this.ship.getCollisionRadius()) {
            return false;
        }

        // ---------- ② 再检查本方其它非战机单位 ----------
        List<ShipAPI> allShips = (this.engine != null) ? this.engine.getShips() : null;
        if (allShips == null) {
            return true; // 拿不到战场信息 → 假定安全（宁可扔出去也别死锁）
        }

        for (ShipAPI friendly : allShips) {
            if (friendly == null || friendly == this.ship) {
                continue;
            }
            if (friendly.getOwner() != this.ship.getOwner()) {
                continue; // 只看本方单位
            }
            if (friendly.isFighter()) {
                continue; // 战机不计入（见方法注释）
            }
            if (!friendly.isAlive() || friendly.isHulk() || friendly.isExpired()) {
                continue; // 残骸/离场单位不构成误伤风险
            }

            float dist = Misc.getDistance(point, friendly.getLocation());
            if (dist <= minDist + friendly.getCollisionRadius()) {
                return false;
            }
        }
        return true;
    }

    // ==================== 八、几何辅助 ====================

    /**
     * 判断某个目标是否属于本舰的敌人。
     * 依据公开的 owner 语义：0 = 玩家方、1 = 敌方、100 = 中立（例如舰船残骸）。
     *
     * @param other 待判断的舰船
     * @return true 表示应当作为攻击目标
     */
    protected boolean isEnemyOfUs(ShipAPI other) {
        if (this.ship == null || other == null) {
            return false;
        }
        if (other.isExpired() || !other.isAlive()) {
            return false;
        }
        if (other == this.ship) {
            return false;
        }
        if (other.getOwner() == this.ship.getOwner()) {
            return false; // 自己人
        }
        if (other.getOwner() == 100) {
            return false; // 中立单位（残骸等）
        }
        return true;
    }

    /**
     * 统计编队里还活着的成员数量。
     *
     * @param wing 战机编队
     * @return 存活成员数
     */
    protected int countAliveWingMembers(FighterWingAPI wing) {
        if (wing == null) {
            return 0;
        }
        List<ShipAPI> members = wing.getWingMembers();
        if (members == null) {
            return 0;
        }

        int count = 0;
        for (ShipAPI member : members) {
            if (member != null && member.isAlive()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 判断某个编队是否属于轰炸机编队（威胁最高）。
     *
     * @param wing 战机编队
     * @return true 表示是轰炸机
     */
    protected boolean isBomberWing(FighterWingAPI wing) {
        if (wing == null || wing.getSpec() == null) {
            return false;
        }
        return wing.getSpec().isBomber();
    }

    /**
     * 从 from 点沿 velocity 方向前进 dist 距离（所有"提前量"计算的基础工具）。
     *
     * @param from     起点
     * @param velocity 速度向量（其方向即预测方向）
     * @param dist     前进距离
     * @return 预测点（总是新对象）
     */
    public Vector2f computePointAlongVelocity(Vector2f from, Vector2f velocity, float dist) {
        if (from == null) {
            return new Vector2f();
        }
        Vector2f v = (velocity == null) ? new Vector2f() : velocity;
        return getPointInDirection(from, Misc.getAngleInDegrees(v), dist);
    }

    /**
     * 从 from 点朝 degrees 方向走 dist 距离，返回新点。
     *
     * @param from    起点
     * @param degrees 方向（角度制，度）
     * @param dist    距离
     * @return 目标点（总是新对象）
     */
    public Vector2f getPointInDirection(Vector2f from, float degrees, float dist) {
        Vector2f unit = Misc.getUnitVectorAtDegreeAngle(degrees);
        unit.scale(dist);
        return Vector2f.add(from, unit, new Vector2f());
    }

    /**
     * 把引擎内部向量复制成自己的向量，避免误改引擎状态。
     *
     * @param v 源向量（可为 null）
     * @return 副本（源为 null 时返回零向量）
     */
    protected Vector2f toVec(Vector2f v) {
        return (v == null) ? new Vector2f() : new Vector2f(v);
    }

    // ==================== 九、只读访问器（便于调试/其它脚本调用） ====================

    /** @return 本舰的舰船系统（仅作兜底数据源，可能为 null） */
    public ShipSystemAPI getSystem() {
        return this.system;
    }

    /** @return 本 AI 绑定的子系统（可能为 null） */
    public VIII_MineStrikeSubsystem getSubsystem() {
        return this.subsystem;
    }

    /** @return 本 AI 当前使用的投放距离（距离单位） */
    public float getMineRangeValue() {
        return getMineRange();
    }

    /** @return 本 AI 当前使用的引信时间（秒） */
    public float getFuseTimeValue() {
        return getFuseTime();
    }
}