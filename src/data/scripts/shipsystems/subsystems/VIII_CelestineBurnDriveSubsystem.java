package data.scripts.shipsystems.subsystems;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.magiclib.subsystems.MagicShipSystemSubsystem;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.EngineSlotAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI;
import com.fs.starfarer.api.combat.ShipSystemSpecAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.plugins.ShipSystemStatsScript;
import com.fs.starfarer.api.util.Misc;

/**
 * 塞莱斯延（VIII_Celestine）专属子系统：烈焰驱动器。
 * <p>
 * 【为什么用 MagicShipSystemSubsystem】
 * 原版烈焰驱动器是舰船系统 burndrive：效果本身写在
 * starsector-core/data/shipsystems/scripts/BurnDriveStats.java 里（janino 源码），
 * 由 .system 文件的 statsScript 字段指定。MagicShipSystemSubsystem 的作用就是
 * "把某个原版舰船系统的脚本当成子系统来跑"：构造时按 {@link #getShipSystemId()}
 * 取到 ShipSystemSpecAPI，再取它的 statsScript 实例，之后每一帧用
 * apply(stats, id, 状态, 效果强度) 驱动，进入冷却时 unapply()。
 * <p>
 * 因此本类只要返回 "burndrive"，就自动获得：
 * - 原版效果：最大航速 +200*effectLevel、加速度 +200*effectLevel（BurnDriveStats.apply）；
 * - 原版名称与图标："烈焰驱动器" + graphics/icons/hullsys/burn_drive.png；
 * - 原版状态文字："提高引擎出力"（BurnDriveStats.getStatusData）；
 * - 原版式切换：buff 只在燃烧期间存在，收油瞬间解除。
 * <p>
 * 在此基础上本类还做了五处定制：
 * - 前摇 / 持续 / 后摇显式定为 2 / 5 / 1 秒（不依赖 ship_systems.csv 的表数据）；
 * - 开启期间每帧自动补一条加速指令（对应原版 alwaysAccelerate:true）并封锁减速指令；
 * - 开启期间锁死减速，只能加速与转向（原版的 noStrafing/noAccel 等开关属于
 *   真正的舰船系统，MagicLib 子系统拿不到，只能压 getDeceleration()）；
 * - 不再显示充能次数栏（{@link #hasCharges()} 恒为 false）；
 * - 尾焰拉伸只作用于与装饰武器槽 E01 重合的引擎（原版 extendFlame 是全船生效的，
 *   这里改用逐引擎的 setFlameLevel / setGlowSizeMult，见 {@link #updateFlameStretch(float)}）。
 * <p>
 * 【为什么 AI 要自己写】
 * 原版烈焰驱动器的 AI 不是脚本，而是引擎按 .system 的 "aiType":"BURN_DRIVE_TOGGLE"
 * 在 com.fs.starfarer.loading.specs.do.createSystemAI() 里硬编码 new 出来的混淆类
 * （同方法还会按 aiType 造其它系统的 AI），mod 既拿不到该类也无法实例化它。
 * 所以这里按原版 AI 的行为特征复刻判断（见 {@link #shouldActivateAI(float)}）：
 * 目标在正前方、距离远超交战距离、幅能安全时才点火。
 * <p>
 * 【定时触发，非开关式（isToggle）】
 * 本子系统的时序被显式固定为 2 秒前摇 / 5 秒持续 / 1 秒后摇（见 {@link #IN_DURATION} 等），
 * 因此 isToggle() 返回 false，让 MagicLib 的状态机在持续时间结束后自动 ACTIVE → OUT；
 * 再用 {@link #canActivate()} 钉死"只有 READY 才能激活"，避免按住按键重刷时序。
 * <p>
 * 本类只由 VIII_FleetLogistics 在 applyEffectsAfterShipCreation 里、且仅当舰体 id
 * 为 VIII_Celestine 时安装，其它舰船不会获得该子系统。
 */
public class VIII_CelestineBurnDriveSubsystem extends MagicShipSystemSubsystem {

    /** 复用的原版舰船系统 id（注意：不是 burn_drive，官方 id 是 burndrive） */
    public static final String SHIP_SYSTEM_ID = "burndrive";

    /** 唯一允许安装本子系统的舰体 id */
    public static final String SHIP_HULL_ID = "VIII_Celestine";

    /** "目标在正前方"的半角（度）：原版趋势是只朝船头方向点火 */
    public static final float BURN_ARC_DEGREES = 60.0F;

    /** 未燃烧时，幅能高于该比例就不点火（留余量给护盾与武器） */
    public static final float FLUX_START_LIMIT = 0.50F;

    /** 目标距离必须超过"交战距离 × 该倍数"才值得点火 */
    public static final float BURN_DISTANCE_FACTOR = 1.40F;

    /** 交战距离下限：防止舰船没有可用武器时算出一个过小的距离 */
    public static final float MIN_ENGAGE_RANGE = 600.0F;

    /**
     * 尾焰拉伸参数，取自原版 burndrive.system 的 engineGlow*Mult。
     * <p>
     * 长度与辉光走逐引擎接口，只作用于"与装饰武器槽 E01 重合的引擎"
     * （见 {@link #FLAME_SLOT_ID}）：长度用 {@code setFlameLevel}，辉光用
     * {@code setGlowSizeMult}。
     * <p>
     * 宽度则只能整舰生效：引擎在"正在加速"分支里会把逐引擎宽度因子
     * 钳在 1.0（{@code G} 的渲染代码：{@code f13 = min(level, 0.8) / 0.8} 平方后
     * {@code max(f13, 0.45)}），而烈焰驱动器必须一直加速，所以逐引擎改宽度
     * 根本不会变宽；唯一的宽度旋钮是全船的 {@code extendFlame} 宽度参数，
     * 效果等同于原版 burndrive 的 engineGlowWidthMult —— 整舰所有喷口一起变宽，
     * 但只在燃烧期间（与长度同一时间窗）。{@code EngineSlotAPI} 也没有宽度 setter。
     */
    public static final float FLAME_LENGTH_MULT = 4F;
    public static final float FLAME_WIDTH_MULT = 5F;
    public static final float FLAME_GLOW_MULT = 5F;

    /** 尾焰拉伸的作用锚点：VIII_Celestine 的主喷口就压在 id 为 E01 的装饰武器槽上 */
    public static final String FLAME_SLOT_ID = "E01";

    /** 判定"引擎与槽位重合"的容差（像素） */
    private static final float FLAME_SLOT_TOLERANCE = 1.0F;

    /** 开启前摇（秒）：点火读条，期间效果强度从 0 爬升 */
    public static final float IN_DURATION = 2.0F;

    /** 持续时间（秒）：全功率燃烧 */
    public static final float ACTIVE_DURATION = 5.0F;

    /** 后摇（秒）：熄火收尾，期间推力回落到 0 */
    public static final float OUT_DURATION = 1.0F;

    /**
     * 减速锁使用的 stat 来源 id。
     * 故意与效果脚本的 id（"burndrive"）区分开，避免双向 unmodify 打架。
     */
    private static final String DECEL_LOCK_SOURCE = "VIII_CelestineBurnDrive_decel";

    /**
     * 启动按键：固定绑定 B。
     * <p>
     * MagicLib 的键位分配（MagicSubsystemsManager.reassignKeys）只处理 key 仍为
     * {@code MagicSubsystem.BLANK_KEY} 的子系统；构造时把 key 设成 "B"，本子系统就会被
     * 跳过、不再占用 MagicLib 自动分配的槽位，HUD 上显示为 "B"。
     */
    public static final String ACTIVATION_KEY = "B";

    /** 状态条最多读取条数：防止第三方 StatsScript 的 getStatusData 永不返回 null 时死循环 */
    private static final int MAX_STATUS_ENTRIES = 8;

    /** 原版 burndrive 的规格与脚本（基类字段是 private，这里另取一份缓存自用） */
    private static ShipSystemSpecAPI burnDriveSpec = null;
    private static ShipSystemStatsScript burnDriveScript = null;

    /** 缓存的装饰武器槽 E01：舰体固定，找到一次即可 */
    private WeaponSlotAPI flameSlot = null;
    private boolean flameSlotResolved = false;

    /** 被拉伸引擎的原始辉光尺寸，燃烧结束后据此还原 */
    private final Map<EngineSlotAPI, Float> baseGlowSize = new HashMap<EngineSlotAPI, Float>();

    /**
     * 构造器。基类会在这里按 {@link #getShipSystemId()} 去取原版系统规格与脚本，
     * 所以舰船对象必须有效。
     *
     * @param ship 本舰
     */
    public VIII_CelestineBurnDriveSubsystem(ShipAPI ship) {
        super(ship);
        // 固定键位：必须在 addSubsystemToShip 之前设置，才会被 reassignKeys 跳过
        setKey(ACTIVATION_KEY);
    }

    /**
     * 复用的原版舰船系统 id。基类拿它去 Global.getSettings().getShipSystemSpec(...)。
     */
    @Override
    public String getShipSystemId() {
        return SHIP_SYSTEM_ID;
    }

    /**
     * 前摇 2 秒（基类默认从 burndrive 的 ship_systems.csv 读，这里改为显式常量，
     * 不再受原版表数据变动影响）。
     */
    @Override
    public float getBaseInDuration() {
        return IN_DURATION;
    }

    /**
     * 持续时间 5 秒。
     */
    @Override
    public float getBaseActiveDuration() {
        return ACTIVE_DURATION;
    }

    /**
     * 后摇 1 秒。
     */
    @Override
    public float getBaseOutDuration() {
        return OUT_DURATION;
    }

    /**
     * 不再使用充能次数：本子系统定时触发，没有"可用次数"的概念。
     * <p>
     * MagicLib 的 HUD 会按 {@link #hasCharges()} 决定是否多画一条充能条
     * （{@code getNumHUDBars()} 在 hasCharges 时返回 2），并额外渲染
     * "剩余次数 / 充能进度" 的辅助条；这里返回 false 即可整条隐藏。
     */
    @Override
    public boolean hasCharges() {
        return false;
    }

    /**
     * 与 {@link #hasCharges()} 配套：最大次数恒为 0，避免任何地方再把充能条算出来。
     */
    @Override
    public int getMaxCharges() {
        return 0;
    }

    /**
     * 非开关式：按下后自动走完"前摇 → 持续 → 后摇"，5 秒烧完自动熄火。
     * <p>
     * 原版 burndrive 是 toggle 式，但本子系统的时序被固定成 2 / 5 / 1 秒，
     * 所以这里返回 false，让 MagicLib 的状态机在持续时间结束后自动
     * ACTIVE → OUT；重复按键则由 {@link #canActivate()} 挡掉。
     */
    @Override
    public boolean isToggle() {
        return false;
    }

    /**
     * 只有 READY 状态才允许激活。
     * <p>
     * MagicLib 的 canActivateInternal 末尾本身就有 {@code return state == State.READY;}，
     * 这里再显式覆盖一道：一是把"一次点火必须完整走完 2 + 5 + 1 秒"写成明面约束，
     * 二是防止将来基类行为变化（比如允许在 IN 期间重复激活）把时序重刷。
     */
    @Override
    public boolean canActivate() {
        return getState() == State.READY;
    }

    /**
     * 舰船专属子系统：优先级排在阵营/模块级之后，避免抢占别的子系统的键位。
     */
    @Override
    public int getOrder() {
        return ORDER_SHIP_UNIQUE;
    }

    /**
     * 需要占用一个可绑定按键（MagicLib 会自动分配，也可在设置里改）。
     */
    @Override
    public boolean canAssignKey() {
        return true;
    }

    /**
     * HUD "更多信息"里的一行说明。
     */
    @Override
    public String getBriefText() {
        return "短时间内大幅提升最大航速与加速度";
    }

    /**
     * AI 判断入口：MagicLib 每帧询问一次"要不要点火"。
     * <p>
     * 非开关式（{@link #isToggle()} 返回 false）下只有 READY 状态有意义：
     * 点火后会自动走完 2 秒前摇、5 秒持续、1 秒后摇，中途不能取消，
     * 所以不在 READY 时直接返回 false（与 {@link #canActivate()} 双保险）。
     * <p>
     * 点火条件对齐原版烈焰驱动器的 AI 行为：
     * 1) 过载/排散中不点火；
     * 2) 必须有存活的敌方目标；
     * 3) 幅能低于 50%（留余量给护盾与武器）；
     * 4) 目标在船头正前方 ±60 度内；
     * 5) 距离超过交战距离的 1.4 倍（追到武器够得着就不烧了）。
     *
     * @param amount 上一帧耗时（秒）
     * @return true 表示本次应当点火
     */
    @Override
    public boolean shouldActivateAI(float amount) {
        if (ship == null || !ship.isAlive()) {
            return false;
        }
        if (ship.getFluxTracker() == null || ship.getFluxTracker().isOverloadedOrVenting()) {
            return false;
        }
        if (!isReady()) {
            return false;
        }

        ShipAPI target = ship.getShipTarget();
        if (target == null || !target.isAlive() || target.getOwner() == ship.getOwner()) {
            return false;
        }
        if (ship.getFluxLevel() >= FLUX_START_LIMIT) {
            return false;
        }
        if (!isTargetAhead(target)) {
            return false;
        }
        float distance = Misc.getDistance(ship.getLocation(), target.getLocation());
        if (distance < getEngagementRange() * BURN_DISTANCE_FACTOR) {
            return false;
        }
        return true;
    }

    /**
     * 每帧回调：驱动原版 BurnDriveStats，再补一个原版式尾焰，并在玩家亲自驾驶时挂状态条。
     * <p>
     * 【为什么不能直接调 super.advance】
     * MagicLib 的 MagicShipSystemSubsystem.advance 在"本舰就是玩家舰"分支里是：
     * <pre>
     * int statusIndex = 0;
     * do {
     *     statusData = system.getStatusData(statusIndex, ...);
     *     if (statusData != null) { maintainStatusForPlayerShip(...); }
     * } while (statusData != null);
     * </pre>
     * statusIndex 在循环体内从不自增（反汇编已确认没有 iinc），而原版
     * {@code BurnDriveStats.getStatusData(0, ...)} 恒定返回 "提高引擎出力"，
     * 于是这个 do/while 永不退出 —— 玩家一接管本舰，主线程就卡死在这个循环里。
     * 所以这里完整重写 advance：效果仍交给原版脚本，状态条改成有界循环。
     *
     * @param amount   上一帧耗时（秒）
     * @param isPaused 战斗是否暂停
     */
    @Override
    public void advance(float amount, boolean isPaused) {
        ShipSystemSpecAPI spec = getBurnDriveSpec();
        ShipSystemStatsScript script = getBurnDriveScript();
        if (spec == null || script == null || ship == null) {
            return;
        }

        State magicState = getState();
        ShipSystemStatsScript.State scriptState = translateState(magicState);
        if (scriptState == null) {
            return;
        }
        float level = getEffectLevel();

        // 与原版一致：IN/ACTIVE/OUT 期间持续叠加效果（OUT 时 BurnDriveStats 自己会 unmodify 最大航速）
        if (!isPaused && magicState != State.READY && magicState != State.COOLDOWN) {
            script.apply(ship.getMutableStats(), spec.getId(), scriptState, level);
        }

        // 开启期间（前摇 / 持续 / 后摇）锁死减速：只能加速与转向，不能主动刹车或倒车
        applyDecelerationLock(isOn());
        // 开启期间每帧补一条加速指令（复刻原版 burndrive.system 的 alwaysAccelerate:true）
        if (!isPaused && isOn() && ship != null && ship.isAlive()) {
            applyAccelerateCommand();
        }

        // 状态条：只在玩家亲自驾驶时显示；有界循环，规避 MagicLib 的死循环
        if (ship == Global.getCombatEngine().getPlayerShip()) {
            for (int i = 0; i < MAX_STATUS_ENTRIES; i++) {
                ShipSystemStatsScript.StatusData statusData = script.getStatusData(i, scriptState, level);
                if (statusData == null) {
                    break;
                }
                Global.getCombatEngine().maintainStatusForPlayerShip(spec.getId() + i,
                        spec.getIconSpriteName(), spec.getName(), statusData.text, statusData.isDebuff);
            }
        }

        if (isPaused) {
            return;
        }
        if (!ship.isAlive()) {
            restoreFlameStretch();
            return;
        }
        if (!isOn() || level <= 0.0F || ship.getEngineController() == null) {
            restoreFlameStretch();
            return;
        }

        updateFlameStretch(level);
    }

    /**
     * 只把尾焰拉伸作用到与装饰武器槽 E01 重合的引擎上（容差 1 像素）。
     * <p>
     * 原版 {@code extendFlame} 是全船生效的：它把三个系数 shift 到全船共享的
     * ValueShifter 上，渲染时每个引擎槽都会套用（反编译见
     * com.fs.starfarer.combat.entities.G 的 extendFlame/渲染段）。所以这里不用
     * extendFlame，而是逐引擎改两样东西：
     * <ul>
     * <li>火焰长度：{@code ShipEngineControllerAPI.setFlameLevel(slot, level)}，
     * 渲染时长度因子 = 0.2 + 0.8 × max(0, level - 0.8) / 0.2，
     * 反解见 {@link #flameLevelForLength(float)}；</li>
     * <li>喷口辉光：{@code EngineSlotAPI.setGlowSizeMult(...)}，渲染时每帧读取，
     * 是逐引擎实时生效的。</li>
     * </ul>
     * 引擎控制器在舰船 advance 里推进、本子系统由 CombatPlugin 在其后推进，
     * 所以我们写进去的火焰等级能赢过本帧渲染。
     *
     * @param effectLevel 当前效果强度 0~1
     */
    private void updateFlameStretch(float effectLevel) {
        ShipEngineControllerAPI controller = ship.getEngineController();
        WeaponSlotAPI slot = getFlameSlot();
        if (controller == null || slot == null) {
            return;
        }
        Vector2f slotPos = slot.computePosition(ship);
        if (slotPos == null) {
            return;
        }

        float lengthMult = 1.0F + (FLAME_LENGTH_MULT - 1.0F) * effectLevel;
        float glowMult = 1.0F + (FLAME_GLOW_MULT - 1.0F) * effectLevel;
        float flameLevel = flameLevelForLength(lengthMult);
        float widthShift = (FLAME_WIDTH_MULT - 1.0F) * effectLevel;

        // 宽度只能整舰放大：加速态下引擎把逐引擎宽度因子钳在 1.0，没有别的入口。
        // 长度/辉光参数传 0，避免盖掉下面逐引擎写入的长度与辉光。
        controller.extendFlame(this, 0.0F, widthShift, 0.0F);

        List<ShipEngineControllerAPI.ShipEngineAPI> engines = controller.getShipEngines();
        for (int i = 0; i < engines.size(); i++) {
            ShipEngineControllerAPI.ShipEngineAPI engine = engines.get(i);
            if (engine == null || engine.getEngineSlot() == null) {
                continue;
            }
            EngineSlotAPI engineSlot = engine.getEngineSlot();
            if (!isSameSpot(engineSlot, slotPos)) {
                restoreFlameStretch(engineSlot);
                continue;
            }
            if (!baseGlowSize.containsKey(engineSlot)) {
                baseGlowSize.put(engineSlot, Float.valueOf(engineSlot.getGlowSizeMult()));
            }
            controller.setFlameLevel(engineSlot, flameLevel);
            engineSlot.setGlowSizeMult(baseGlowSize.get(engineSlot).floatValue() * glowMult);
        }
    }

    /** 引擎槽位的世界坐标是否与火焰锚点重合（容差 {@link #FLAME_SLOT_TOLERANCE} 像素） */
    private boolean isSameSpot(EngineSlotAPI engineSlot, Vector2f slotPos) {
        Vector2f enginePos = engineSlot.computePosition(ship.getLocation(), ship.getFacing());
        return enginePos != null && Misc.getDistance(enginePos, slotPos) <= FLAME_SLOT_TOLERANCE;
    }

    /**
     * 把"目标长度倍数"换算成引擎的火焰等级。
     * <p>
     * 渲染代码在"正在加速"分支里（烈焰驱动器一直加速，必然走这个分支）：
     * <pre>
     * f = max(0, level - 0.8) / 0.2
     * 长度因子 = 0.2 + 0.8 * f * f
     * </pre>
     * 也就是**平方**关系，而不是线性。想让长度乘以 mult：
     * {@code 0.2 + 0.8 * f² = mult ⇒ f = √((mult - 0.2) / 0.8) ⇒ level = 0.8 + 0.2 * f}。
     * <p>
     * 例：mult = 1 → level = 1.0（与满推力原版一致）；mult = 3.5 → level ≈ 1.206。
     * 引擎自己每帧只把 level 往上推到 1.0 就停，而我们是在它之后（CombatPlugin 阶段）
     * 写入，所以大于 1.0 的值能保留到本帧渲染。
     *
     * @param lengthMult 目标长度倍数（1 = 原样）
     * @return 应写入的火焰等级
     */
    private static float flameLevelForLength(float lengthMult) {
        float f = (float) Math.sqrt(Math.max(0.0F, (lengthMult - 0.2F) / 0.8F));
        return 0.8F + 0.2F * f;
    }

    /** 燃烧结束后把所有被改过的引擎辉光还原成原始值，并把整舰宽度加成归零 */
    private void restoreFlameStretch() {
        ShipEngineControllerAPI controller = ship == null ? null : ship.getEngineController();
        if (controller != null) {
            // 宽度是整舰级 shift，即使没有任何引擎命中也要归零，否则会一直保持变宽
            controller.extendFlame(this, 0.0F, 0.0F, 0.0F);
        }
        if (baseGlowSize.isEmpty()) {
            return;
        }
        if (controller == null) {
            baseGlowSize.clear();
            return;
        }
        List<ShipEngineControllerAPI.ShipEngineAPI> engines = controller.getShipEngines();
        for (int i = 0; i < engines.size(); i++) {
            ShipEngineControllerAPI.ShipEngineAPI engine = engines.get(i);
            if (engine != null && engine.getEngineSlot() != null) {
                restoreFlameStretch(engine.getEngineSlot());
            }
        }
    }

    /** 单个引擎的辉光尺寸还原（火焰等级交给引擎控制器自己回落，避免突然熄灭） */
    private void restoreFlameStretch(EngineSlotAPI engineSlot) {
        Float base = baseGlowSize.remove(engineSlot);
        if (base != null) {
            engineSlot.setGlowSizeMult(base.floatValue());
        }
    }

    /** 惰性查找装饰武器槽 E01 */
    private WeaponSlotAPI getFlameSlot() {
        if (flameSlotResolved) {
            return flameSlot;
        }
        flameSlotResolved = true;
        if (ship == null || ship.getHullSpec() == null) {
            return null;
        }
        flameSlot = ship.getHullSpec().getWeaponSlotAPI(FLAME_SLOT_ID);
        if (flameSlot == null) {
            // 只在第一次解析失败时记一条，方便排查"改了船体但游戏里没生效"这类问题
            Global.getLogger(VIII_CelestineBurnDriveSubsystem.class).warn(
                    "舰体 " + ship.getHullSpec().getHullId() + " 缺少装饰武器槽 " + FLAME_SLOT_ID
                            + "，尾焰拉伸不会生效（请确认游戏实际加载的 data/hulls 里有该槽位）");
        }
        return flameSlot;
    }

    /**
     * 开启期间把减速压成 0：舰船只能加速与转向，按 S 也不会刹车 / 倒车。
     * <p>
     * 原版的这类限制（noTurning / noStrafing / noAccel / noShield）是引擎在
     * {@code SpecStore} 里读 ship_systems.csv 后挂到"真正的舰船系统"上的，
     * MagicLib 子系统不是舰船系统，拿不到那套开关，只能在 stats 上自己压：
     * {@code getDeceleration().modifyMult(id, 0f)}。离开燃烧状态立刻还原，
     * 不影响之后的正常机动。
     *
     * @param locked true = 锁死减速，false = 还原
     */
    private void applyDecelerationLock(boolean locked) {
        MutableShipStatsAPI stats = ship == null ? null : ship.getMutableStats();
        if (stats == null) {
            return;
        }
        if (locked) {
            if (!stats.getDeceleration().getMultMods().containsKey(DECEL_LOCK_SOURCE)) {
                stats.getDeceleration().modifyMult(DECEL_LOCK_SOURCE, 0.0F);
            }
        } else {
            if (stats.getDeceleration().getMultMods().containsKey(DECEL_LOCK_SOURCE)) {
                stats.getDeceleration().unmodify(DECEL_LOCK_SOURCE);
            }
        }
    }

    /**
     * 开启期间给本舰补一条"加速"指令，同时封锁"减速"指令，做到不按键也一直往前冲。
     * <p>
     * 原版 burndrive.system 里有 {@code alwaysAccelerate:true}，引擎的做法是在系统脚本里
     * 每帧 {@code this.ship.giveCommand(new Ship.Oo(..., true))} 造一条"强制加速"命令
     * （反编译见 com.fs.starfarer.combat.systems.F 第 350 行附近）；那个内部类是混淆类，
     * MOD 无法实例化，所以这里用公开 API
     * {@link ShipAPI#giveCommand(ShipCommand, Object, int)} 复刻同样的效果：
     * 命令进入本舰的待处理命令队列，引擎在下一帧执行 processCommands 时消费，
     * 因此加速手感上只延迟一帧（与船插 advanceInCombat 里发命令的时序一致）。
     * <p>
     * 封锁减速用 {@link ShipAPI#blockCommandForOneFrame(ShipCommand)}：它只对"当前帧"
     * 生效，所以必须在燃烧期间每帧调用；被封锁的指令会在引擎处理命令队列时剔除，
     * 玩家按 S 也无法刹车 / 倒车，配合 {@link #applyDecelerationLock(boolean)}
     * 一起实现"只能加速与转向"。
     */
    private void applyAccelerateCommand() {
        if (ship == null) {
            return;
        }
        ship.giveCommand(ShipCommand.ACCELERATE, null, 0);
        ship.blockCommandForOneFrame(ShipCommand.DECELERATE);
    }

    /**
     * 一轮走完（OUT → COOLDOWN）或舰船阵亡时回调：确保减速锁一定被解除，
     * 不会因为子系统不再 advance 而把 0 倍减速永久留在 stats 上。
     */
    @Override
    public void onFinished() {
        applyDecelerationLock(false);
        super.onFinished();
    }

    /**
     * 原版 burndrive 的系统规格（懒加载缓存；基类同名字段是 private，这里另取一份）。
     *
     * @return 系统规格，取不到时为 null
     */
    private static ShipSystemSpecAPI getBurnDriveSpec() {
        if (burnDriveSpec == null && Global.getSettings() != null) {
            burnDriveSpec = Global.getSettings().getShipSystemSpec(SHIP_SYSTEM_ID);
        }
        return burnDriveSpec;
    }

    /**
     * 原版 burndrive 的系统脚本实例（懒加载缓存）。
     *
     * @return 系统脚本，取不到时为 null
     */
    private static ShipSystemStatsScript getBurnDriveScript() {
        if (burnDriveScript == null) {
            ShipSystemSpecAPI spec = getBurnDriveSpec();
            if (spec != null) {
                burnDriveScript = spec.getStatsScript();
            }
        }
        return burnDriveScript;
    }

    /**
     * MagicLib 的子系统状态 → 原版舰船系统状态。
     *
     * @param magicState MagicLib 状态
     * @return 原版状态；未知状态返回 null
     */
    private static ShipSystemStatsScript.State translateState(State magicState) {
        if (magicState == State.ACTIVE) {
            return ShipSystemStatsScript.State.ACTIVE;
        }
        if (magicState == State.IN) {
            return ShipSystemStatsScript.State.IN;
        }
        if (magicState == State.OUT) {
            return ShipSystemStatsScript.State.OUT;
        }
        if (magicState == State.COOLDOWN) {
            return ShipSystemStatsScript.State.COOLDOWN;
        }
        if (magicState == State.READY) {
            return ShipSystemStatsScript.State.IDLE;
        }
        return null;
    }

    /**
     * 交战距离 = 本舰射程最远的非装饰、非导弹武器的射程，并设下限。
     * 用来判断"距离够远才值得点火"。
     *
     * @return 交战距离（距离单位）
     */
    protected float getEngagementRange() {
        float best = 0.0F;
        List<WeaponAPI> weapons = ship.getAllWeapons();
        for (WeaponAPI weapon : weapons) {
            if (weapon == null || weapon.isDecorative()) {
                continue;
            }
            if (weapon.getType() == WeaponType.MISSILE) {
                continue;
            }
            float range = weapon.getRange();
            if (range > best) {
                best = range;
            }
        }
        if (best < MIN_ENGAGE_RANGE) {
            best = MIN_ENGAGE_RANGE;
        }
        return best;
    }

    /**
     * 目标是否在船头正前方 ±{@link #BURN_ARC_DEGREES} 度以内。
     *
     * @param target 目标舰船
     * @return true 表示在正前方
     */
    protected boolean isTargetAhead(ShipAPI target) {
        float angleToTarget = Misc.getAngleInDegrees(ship.getLocation(), target.getLocation());
        float diff = Math.abs(Misc.normalizeAngle(angleToTarget - ship.getFacing()));
        return diff <= BURN_ARC_DEGREES;
    }
}
