// 声明本类所在的包路径
package data.hullmods;

// 导入游戏全局访问接口，用于获取战斗引擎等
import com.fs.starfarer.api.Global;
// 导入战斗相关核心接口
import com.fs.starfarer.api.combat.*;
// 可推进监听器，用于每帧执行
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
// 承受伤害修改器，用于修改受到的伤害
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;
// 渐变器工具，用于平滑过渡数值
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.util.FaderUtil;
// 杂项工具，提供角度、颜色等方法
import com.fs.starfarer.api.util.Misc;
// 超时追踪器，用于管理临时效果的生命周期
import com.fs.starfarer.api.util.TimeoutTracker;
// Lazylib向量工具，提供向量旋转、夹角计算等
import org.lazywizard.lazylib.VectorUtils;
// 二维向量类
import org.lwjgl.util.vector.Vector2f;

// 颜色类
import java.awt.*;
import java.util.List;

// 导入自定义渲染类中的常量RENDER_KEY（用于在引擎自定义数据中标记渲染器）
import static data.hullmods.VIII_ShieldRendering.RENDER_KEY;

/**
 * 神经元力场：一种特殊的护盾船体插件。
 * 不直接绘制护盾，而是通过监听器接管伤害逻辑、粒子视觉效果以及
 * 自定义的护盾展开状态。搭配 VIII_ShieldRendering 进行粒子渲染。
 * 增强功能：支持模块化舰船（子模块）的监听器管理，确保模块也能正确吸收伤害，
 * 并且护盾渲染统一覆盖主体及其所有子模块。
 */
public class VIII_Shield extends BaseHullMod {

    // 在舰船自定义数据中存储父舰（主体）的键
    public static final String PARENT_SHIP_KEY = "VIII_Shield_parent";
    // 标记是否为模块的键
    public static final String IS_MODULE_KEY = "VIII_Shield_is_module";

    /** 护盾接管插件在引擎自定义数据中的键，用于每场战斗只注册一次。 */
    private static final String CONTROL_PLUGIN_KEY = "VIII_Shield_control_plugin";

    // 船体插件应用效果钩子：创建并注册护盾脚本实例（确保每船一个）
    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        // 检测是否为模块（有父船）
        ShipAPI parent = ship.getParentStation();
        if (parent != null) {
            // 这是模块，标记并关联到主体
            ship.getCustomData().put(PARENT_SHIP_KEY, parent);
            ship.getCustomData().put(IS_MODULE_KEY, true);
            // 模块也需要监听器来吸收伤害
            if (!VIII_ShieldScript.hasShield(ship)) {
                VIII_ShieldScript.getInstance(ship);
                //Global.getLogger(VIII_Shield.class).info("Module " + ship.getName() + " shield listener added.");
            }
            // 模块不再需要独立的渲染器，由主体渲染器统一处理
            return;
        }

        // 主体舰船：确保自身监听器存在，并初始化所有子模块的监听器
        VIII_ShieldScript.getInstance(ship);
        // 为所有存活的子模块初始化监听器
        for (ShipAPI module : ship.getChildModulesCopy()) {
            if (module.isAlive() && !VIII_ShieldScript.hasShield(module)) {
                // 标记模块的父舰并添加监听器
                module.getCustomData().put(PARENT_SHIP_KEY, ship);
                module.getCustomData().put(IS_MODULE_KEY, true);
                VIII_ShieldScript.getInstance(module);
                //Global.getLogger(VIII_Shield.class).info("Module " + ship.getName() + " shield listener added.");
            }
        }
    }

    /**
     * 在舰船创建前应用效果
     * @param hullSize 舰船尺寸
     * @param stats 可变的舰船状态API
     * @param id 效果ID
     */
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
        // 大幅提升护盾展开速率
        stats.getShieldUnfoldRateMult().modifyMult(id, 3.5f);
    }

    // 每战斗帧调用：确保渲染器实例存在（用于自定义护盾绘制）
    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        CombatEngineAPI engine = Global.getCombatEngine();
        // 如果引擎的自定义数据中还没有渲染键，则初始化渲染器
        if (engine != null && !engine.getCustomData().containsKey(RENDER_KEY))
            VIII_ShieldRendering.getInstance();

        // 注册护盾接管插件：插件在所有舰船推进之后执行，是每帧最后写入护盾状态的一方
        ensureControlPlugin(engine);

        // 如果是主体舰船，每帧清理已死亡的子模块上的监听器，并确保存活模块有监听器
        if (ship.getParentStation() == null) { // 主体
            cleanupDeadModules(ship);
            ensureAliveModulesHaveListener(ship);
        }
    }

    /** 出现威胁、开始展开力场的敌舰半径 */
    private static final float AI_RAISE_RANGE = 1400f;
    /** 已经展开时，维持力场的敌舰半径（比 AI_RAISE_RANGE 大，避免在边界反复开合） */
    private static final float AI_KEEP_RANGE = 2000f;
    /** 敌方弹体被视为威胁的半径 */
    private static final float AI_PROJECTILE_RANGE = 1000f;
    /** 最近受到伤害视为威胁的秒数 */
    private static final float AI_RECENT_DAMAGE_TIME = 1.0f;
    /** 展开后至少维持的秒数，避免与 AI 判定抖动 */
    private static final float AI_MIN_ON_TIME = 1.2f;
    /** 最短维持计时器在舰船自定义数据中的键 */
    private static final String AI_HOLD_KEY = "VIII_Shield_ai_hold";

    /**
     * AI 舰船的力场开关逻辑。
     *
     * <p>原版护盾的渲染被关闭、覆盖范围也被改写后，原版 AI 判断不出力场是否有效，
     * 会反复下发开/关护盾指令，表现为护盾频繁闪烁。这里屏蔽这些指令，并用接近
     * 原版护盾 AI 的观感驱动力场：有威胁（敌舰进入 <code>AI_RAISE_RANGE</code>、
     * 敌方弹体靠近、最近刚挨打）时展开；已经展开时只要附近还有敌舰
     * （<code>AI_KEEP_RANGE</code>）就维持，威胁离开后收束；过载、排散、阵亡、
     * 残骸时关闭；展开后至少维持 <code>AI_MIN_ON_TIME</code> 秒，避免一开一关抖动。</p>
     */
    private static void updateAIShieldControl(ShipAPI ship, float amount) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null || ship == null) {
            return;
        }
        // 玩家亲自驾驶的舰船保持手动控制；开启自动操作（自动驾驶）时按 AI 舰船处理。
        // 注意：不能用 engine.isUIAutopilotOn() 判断——反编译显示它返回的是 !autopilot，
        // 语义与实际相反；改用玩家舰是否被挂上 AI（自动驾驶会 setAI，手动驾驶为 null）。
        if (ship == engine.getPlayerShip() && ship.getAI() == null) {
            return;
        }
        // 模块的护盾状态跟随主体，不单独控制
        if (ship.getParentStation() != null) {
            return;
        }
        ShieldAPI shield = ship.getShield();
        if (shield == null) {
            return;
        }
        // 相位舰的同一指令控制相位，交回原版 AI
        if (ship.getPhaseCloak() != null) {
            return;
        }

        // 屏蔽原版 AI 本帧的开/关护盾指令。本方法由战斗插件在舰船推进之后调用，
        // 这条屏蔽会保留到下一帧的指令过滤阶段，AI 的开/关指令不会生效。
        ship.blockCommandForOneFrame(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK);

        FluxTrackerAPI flux = ship.getFluxTracker();
        boolean usable = ship.isAlive() && !ship.isHulk()
                && !flux.isOverloaded() && !flux.isVenting();

        ShipwideAIFlags flags = ship.getAIFlags();
        boolean keepFlag = flags != null && flags.hasFlag(ShipwideAIFlags.AIFlags.KEEP_SHIELDS_ON);

        float hold = getHoldTimer(ship);
        if (hold > 0f) {
            hold = Math.max(0f, hold - amount);
        }

        boolean threatened = usable && isThreatened(engine, ship);

        boolean wantOn;
        if (!usable) {
            // 无法维持防御：立即关闭
            wantOn = false;
            hold = 0f;
        } else if (keepFlag || threatened) {
            // 原版 AI 要求保持护盾，或出现威胁：展开力场并刷新最短维持时间
            wantOn = true;
            hold = AI_MIN_ON_TIME;
        } else if (hold > 0f) {
            // 刚展开不久：威胁暂时离开也先维持，避免反复开合
            wantOn = true;
        } else {
            // 已经展开且附近仍有敌舰则维持，否则与原版 AI 一样收束力场
            wantOn = shield.isOn() && isEnemyNearby(engine, ship, AI_KEEP_RANGE);
        }

        setHoldTimer(ship, hold);

        if (wantOn) {
            if (!shield.isOn()) {
                shield.toggleOn();
            }
        } else if (shield.isOn()) {
            shield.toggleOff();
        }
    }

    /** 是否存在威胁：最近挨打、敌舰进入展开半径、或敌方弹体进入弹体半径。 */
    private static boolean isThreatened(CombatEngineAPI engine, ShipAPI ship) {
        if (ship.getSinceLastDamageTaken() <= AI_RECENT_DAMAGE_TIME) {
            return true;
        }
        if (isEnemyNearby(engine, ship, AI_RAISE_RANGE)) {
            return true;
        }
        Vector2f location = ship.getLocation();
        float range2 = AI_PROJECTILE_RANGE * AI_PROJECTILE_RANGE;
        List<DamagingProjectileAPI> projectiles = engine.getProjectiles();
        if (projectiles != null) {
            for (DamagingProjectileAPI projectile : projectiles) {
                if (projectile == null || projectile.getOwner() == ship.getOwner()) {
                    continue;
                }
                if (distanceSq(location, projectile.getLocation()) <= range2) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 半径内是否有存活的敌对舰船。 */
    private static boolean isEnemyNearby(CombatEngineAPI engine, ShipAPI ship, float range) {
        Vector2f location = ship.getLocation();
        float range2 = range * range;
        for (ShipAPI other : engine.getShips()) {
            if (other == ship || other.getOwner() == ship.getOwner()) {
                continue;
            }
            if (!other.isAlive() || other.isHulk()) {
                continue;
            }
            if (distanceSq(location, other.getLocation()) <= range2) {
                return true;
            }
        }
        return false;
    }

    /** 不取平方根的平方距离，用于逐帧距离比较。 */
    private static float distanceSq(Vector2f a, Vector2f b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return dx * dx + dy * dy;
    }

    /** 读取力场最短维持计时器。 */
    private static float getHoldTimer(ShipAPI ship) {
        Object value = ship.getCustomData().get(AI_HOLD_KEY);
        return value instanceof Float ? (Float) value : 0f;
    }

    /** 写回力场最短维持计时器。 */
    private static void setHoldTimer(ShipAPI ship, float value) {
        ship.getCustomData().put(AI_HOLD_KEY, value);
    }

    /** 每场战斗只注册一次护盾接管插件。 */
    private static void ensureControlPlugin(CombatEngineAPI engine) {
        if (engine == null || engine.getCustomData().containsKey(CONTROL_PLUGIN_KEY)) {
            return;
        }
        ShieldControlPlugin plugin = new ShieldControlPlugin();
        engine.addPlugin(plugin);
        engine.getCustomData().put(CONTROL_PLUGIN_KEY, plugin);
    }

    /**
     * 护盾接管插件：在战斗引擎推进完所有舰船之后执行，是每帧最后写入护盾状态的一方。
     * 它按接近原版护盾 AI 的观感控制带本船插的舰船的力场开关，避免原版 AI 反复开关。
     */
    public static class ShieldControlPlugin extends BaseEveryFrameCombatPlugin {
        @Override
        public void advance(float amount, List<InputEventAPI> events) {
            CombatEngineAPI engine = Global.getCombatEngine();
            if (engine == null || engine.isPaused()) {
                return;
            }
            for (ShipAPI ship : engine.getShips()) {
                if (ship == null || !VIII_ShieldScript.hasShield(ship)) {
                    continue;
                }
                updateAIShieldControl(ship, amount);
            }
        }
    }

    /**
     * 清理所有已死亡子模块上的结构护盾监听器及相关自定义数据。
     * @param parent 主体舰船
     */
    private void cleanupDeadModules(ShipAPI parent) {
        for (ShipAPI module : parent.getChildModulesCopy()) {
            if (!module.isAlive() && VIII_ShieldScript.hasShield(module)) {
                // 移除模块上的护盾监听器
                module.removeListenerOfClass(VIII_ShieldScript.class);
                // 清理模块自定义数据中的相关标记
                module.getCustomData().remove(PARENT_SHIP_KEY);
                module.getCustomData().remove(IS_MODULE_KEY);
            }
        }
    }

    /**
     * 确保所有存活的子模块都已注册护盾监听器。
     * @param parent 主体舰船
     */
    private void ensureAliveModulesHaveListener(ShipAPI parent) {
        for (ShipAPI module : parent.getChildModulesCopy()) {
            if (module.isAlive() && !VIII_ShieldScript.hasShield(module)) {
                module.getCustomData().put(PARENT_SHIP_KEY, parent);
                module.getCustomData().put(IS_MODULE_KEY, true);
                VIII_ShieldScript.getInstance(module);
            }
        }
    }

    /**
     * 内部静态类：护盾脚本，实现了伤害修改和每帧更新。
     * 负责控制护盾的角度、半径、吸收伤害、发射粒子以及管理免疫EMP。
     */
    public static class VIII_ShieldScript implements DamageTakenModifier, AdvanceableListener {
        private final ShipAPI ship;                 // 所属舰船

        public ShipAPI getShip() {
            return ship;
        }
        public static final String KEY = "VIII_Shield_Absorb"; // 伤害修饰键
        // 护盾展开程度的渐变器，用于平滑展开动画
        private final FaderUtil shieldSpread = new FaderUtil(0, 4f);
        private float particleTimer;

        // 伤害来源参数类型枚举，用于区分弹丸、光束、电弧等
        public enum ParamType {
            ARC, SHIP, PROJ, BEAM;
        }

        /**
         * 获取或创建舰船上的护盾脚本实例（单例模式，确保每船只有一个）
         * @param ship 要获取或绑定的舰船
         * @return 该舰船的MSRShieldScript实例
         */
        public static VIII_ShieldScript getInstance(ShipAPI ship) {
            if (hasShield(ship)) {
                // 已存在则直接返回第一个实例
                return ship.getListeners(VIII_ShieldScript.class).get(0);
            } else {
                VIII_ShieldScript l = new VIII_ShieldScript(ship);
                ship.addListener(l);
                return l;
            }
        }

        // 检查舰船是否已经拥有MSRShieldScript监听器
        public static boolean hasShield(ShipAPI ship) {
            return ship.hasListenerOfClass(VIII_ShieldScript.class);
        }

        // 构造函数，绑定舰船并初始化护盾参数
        public VIII_ShieldScript(ShipAPI ship) {
            this.ship = ship;
            init(ship);
        }

        // 超时追踪器，用于管理光束伤害的修饰状态（避免在光束照射结束后还保留伤害修正）
        private final TimeoutTracker<BeamAPI> effected = new TimeoutTracker<>();

        /**
         * 初始化护盾属性：设定护盾角度为0（全向）、半径等于碰撞半径微调、
         * 以及完全透明的护盾视觉（因为自定义渲染接管显示）。
         * 对于模块，跳过这些修改（模块的护盾由主体统一控制，且模块不会渲染独立护盾）。
         */
        public void init(ShipAPI ship) {
            if (isModule(ship)) {
//                // 模块：禁用原版护盾以避免干扰
//                if (ship.getShield() != null) {
//                    Global.getLogger(VIII_Shield.class).info("Module " + ship.getName() + " original shield disabled.");
//                }
                return;
            }

            if (ship.getShield() != null) {
                ship.getShield().setArc(0f);  // 0度表示360度全覆盖
                ship.getShield().setRadius(Math.max(10f, ship.getCollisionRadius() - ship.getHullSize().ordinal() * 10f));
                ship.getShield().setSkipRendering(true);  // 禁用原版护盾渲染
                ship.getShield().setInnerColor(new Color(0, 0, 0, 0));
                ship.getShield().setRingColor(new Color(0, 0, 0, 0));
            }
        }

        /**
         * 判断舰船是否为模块（有父舰）
         */
        private boolean isModule(ShipAPI ship) {
            // 优先使用缓存
            Object cached = ship.getCustomData().get(IS_MODULE_KEY);
            if (cached instanceof Boolean) return (Boolean) cached;
            // 否则通过父舰判断
            boolean isMod = ship.getParentStation() != null;
            if (isMod) {
                ship.getCustomData().put(IS_MODULE_KEY, true);
            }
            return isMod;
        }

        /**
         * 获取顶层舰船（如果是模块则递归查找父舰，否则返回自身）
         */
        private ShipAPI getParentShip(ShipAPI ship) {
            // 如果缓存中有且存活，直接返回
            if (isModule(ship)) {
                Object cached = ship.getCustomData().get(PARENT_SHIP_KEY);
                if (cached instanceof ShipAPI && ((ShipAPI) cached).isAlive()) {
                    return (ShipAPI) cached;
                }
            }
            // 向上递归查找顶层（直到没有父舰）
            ShipAPI current = ship;
            while (current.getParentStation() != null) {
                current = current.getParentStation();
            }
            // 如果找到了顶层且不是自身，则缓存并标记为模块（若尚未标记）
            if (current != ship) {
                ship.getCustomData().put(PARENT_SHIP_KEY, current);
                ship.getCustomData().put(IS_MODULE_KEY, true);
            }
            return current;
        }

        // 光束控制器，用于记录当前正在处理的光束，防止重复加通量
        private final TimeoutTracker<BeamAPI> beamController = new TimeoutTracker<>();

        /**
         * 判断一个世界坐标点是否处于护盾的防御范围内（基于护盾展开程度）。
         * 通过将点转换到舰船局部坐标，检查其Y分量是否超过阈值。
         * 对于模块，使用主体的展开程度（护盾视觉统一）。
         */
        private boolean isPointWithInShieldDefense(Vector2f point) {
            ShipAPI effectiveShip = getParentShip(ship); // 使用主体计算展开程度
            Vector2f s = Vector2f.sub(point, effectiveShip.getLocation(), null);
            VectorUtils.rotateAroundPivot(s, new Vector2f(0, 0), (-effectiveShip.getFacing() + 90f));
            float level = s.getY() / effectiveShip.getSpriteAPI().getHeight();
            // 护盾展开程度取自主体
            VIII_ShieldScript parentScript = VIII_ShieldScript.getInstance(effectiveShip);
            return level >= -parentScript.getSpreadLevel() + 0.5f;
        }

        public String modifyDamageTaken(Object param, CombatEntityAPI target,
                                        DamageAPI damage, Vector2f point, boolean shieldHit) {
            damage.getModifier().unmodify(KEY);
            // 按五套镀层的统一优先级判断本层是否生效
            if (!VIII_ShieldPriority.canApply(ship, VIII_ShieldPriority.LAYER_MSR)) return null;

            // 获取主体舰船（模块转交主体）
            ShipAPI parentShip = getParentShip(ship);
            // ===== 日志：打印主体护盾状态 =====
//            Global.getLogger(VIII_Shield.class).info(
//                    String.format("[%s] parentShip=%s, shieldOn=%s, isModule=%s",
//                            ship.getName(),
//                            parentShip.getName(),
//                            parentShip.getShield() != null ? parentShip.getShield().isOn() : "NO_SHIELD",
//                            isModule(ship))
//            );

            if (isModule(ship)) {
                // 在获取 parentShip 后，重新判断是否为模块
                boolean isMod = isModule(ship) || (getParentShip(ship) != ship);
                if (isMod) {
                    // 主体护盾未开启 → 不进行转移，让模块正常受伤
                    if (parentShip.getShield() == null || !parentShip.getShield().isOn()) {
                        return null;
                    }
                } else {
                    //Global.getLogger(VIII_Shield.class).info("[%s]主体护盾未开启，模块正常受伤");
                    return null; // 不转移，模块正常受伤
                }
            } else{
            if (!parentShip.isAlive())
                return null; // 主体无效，模块正常受伤
            }

            // ===== 核心修改：模块转移伤害必须依赖主体护盾开启 =====
            if (isModule(ship)) {
                // 主体护盾未开启 → 不进行转移，让模块正常受伤
                if (parentShip.getShield() == null || !parentShip.getShield().isOn()) {
                    return null;
                }
            } else {
                // 主体舰船自身：护盾未开启则不吸收
                if (ship.getShield() == null || !ship.getShield().isOn()) {
                    return null;
                }
                // 主体还需要几何范围检查
                if (!isPointWithInShieldDefense(point)) return null;
            }

            ParamType type = getType(param, damage);
            ShipAPI source = null;
            boolean isBeam = false;
            switch (type) {
                case BEAM:
                    source = ((BeamAPI) param).getSource();
                    isBeam = true;
                    break;
                case PROJ:
                    source = ((DamagingProjectileAPI) param).getSource();
                    isBeam = ((DamagingProjectileAPI) param).isFading();
                    break;
                case SHIP:
                    break;
                default:
                    break;
            }
            if (damage.isDps()) isBeam = true;

            // 计算伤害值（使用主体的属性）
            float factor = parentShip.getHullSpec().getShieldSpec().getFluxPerDamageAbsorbed() *
                    parentShip.getMutableStats().getShieldAbsorptionMult().getModifiedValue() *
                    parentShip.getMutableStats().getShieldDamageTakenMult().getModifiedValue();
            float damageTaken = computeDamageToShield(parentShip, damage, source, isBeam) * factor;
            boolean beamVisual = isBeam;
            if (damage.isForceHardFlux()) isBeam = false;

            if (damageTaken > 0) {
                VIII_ShieldRendering.addHit(parentShip, point, damageTaken, beamVisual);
                // 为主体增加通量
                if (!isBeam) {
                    float softConversion = parentShip.getMutableStats().getShieldSoftFluxConversion().getModifiedValue();
                    parentShip.getFluxTracker().increaseFlux(damageTaken * (1f - softConversion), true);
                    parentShip.getFluxTracker().increaseFlux(damageTaken * softConversion, false);
                } else {
                    parentShip.getFluxTracker().increaseFlux(damageTaken, false);
                }

                // 显示漂浮伤害数字
                Global.getCombatEngine().addFloatingDamageText(point, damageTaken,
                        Misc.FLOATY_SHIELD_DAMAGE_COLOR, parentShip,
                        (damage.getStats() != null ? damage.getStats().getEntity() : null));

                // 模块伤害完全免疫
                damage.getModifier().modifyMult(KEY, 0f);

                // 光束超时管理（仅主体）
                if (!isModule(ship) && type == ParamType.BEAM) {
                    BeamAPI beam = (BeamAPI) param;
                    if (effected.contains(beam)) {
                        effected.set(beam, 1.1f);
                    } else {
                        effected.add(beam, 1.1f);
                    }
                }

                return KEY;
            }

            // 如果伤害值为0，且是模块，也设伤害为0（避免模块受伤）
            if (isModule(ship)) {
                damage.getModifier().modifyMult(KEY, 0f);
                return KEY;
            }

            return null;
        }

        /**
         * 每帧更新：处理护盾展开、EMP免疫、光束修饰超时移除、命中数据更新等。
         * 模块的更新逻辑由主体统一处理，模块本身只需要推进光束超时等。
         */
        public void advance(float amount) {
            shieldSpread.fadeIn(); // 确保渐变器处于激活状态
            // 处理已过期的光束修饰：当光束超时计时器剩余小于1秒时，移除伤害修饰
            if (!effected.getItems().isEmpty()) {
                for (BeamAPI b : effected.getItems()) {
                    if (effected.getRemaining(b) < 1f) {
                        b.getDamage().getModifier().unmodify(KEY);
                    }
                }
            }
            // 超时追踪器推进（光束效果和控制器）
            effected.advance(1);
            beamController.advance(amount);
            if (Global.getCombatEngine().isPaused())
                return;

            // 模块不执行护盾主动效果（由主体统一管理）
            if (isModule(ship)) return;

            // 主体舰船：如果护盾存在且开启，执行护盾主动效果
            if (ship.getShield() != null) {
                if (ship.getShield().isOn()) {
                    immuneEMPEffects(true);
                    // 推进展开动画
                    shieldSpread.advance(amount * ship.getMutableStats().getShieldUnfoldRateMult().getModifiedValue());
                    // 强制护盾朝向舰船后方（180度相对），实现全向护盾视觉效果
                    ship.getShield().forceFacing(Misc.normalizeAngle(ship.getFacing() + 180f));
                    ship.getMutableStats().getEmpDamageTakenMult().modifyMult(getKey(), 0f);
                    if (VIII_ShieldPriority.canApply(ship, VIII_ShieldPriority.LAYER_MSR)
                            && getSpreadLevel() > 0.05f) {
                        float interval = Math.max(0.15f, VIII_ShieldRendering.PARTICLE_INTERVAL);
                        particleTimer += amount;
                        if (particleTimer >= interval) {
                            int emit = (int) (particleTimer / interval);
                            particleTimer -= emit * interval;
                            VIII_ShieldRendering.emitFor(ship, Math.min(emit, 8));
                        }
                    }
                    // 玩家舰船显示HUD状态
                    if (ship == Global.getCombatEngine().getPlayerShip()) {
                        Global.getCombatEngine().maintainStatusForPlayerShip("VIII_Shield", "graphics/icons/hullsys/fortress_shield.png", "护盾",
                                "激活", false);
                    }
                } else {
                    immuneEMPEffects(false);
                    if (ship == Global.getCombatEngine().getPlayerShip()) {
                        Global.getCombatEngine().maintainStatusForPlayerShip("VIII_Shield", "graphics/icons/hullsys/fortress_shield.png", "护盾",
                                "关闭", true);
                    }
                    ship.getMutableStats().getEmpDamageTakenMult().unmodify(getKey());
                    particleTimer = 0f;
                }
            }

            // 护盾关闭或不存在时重置展开程度为0
            if (ship.getShield() == null || ship.getShield().isOff()) {
                shieldSpread.setBrightness(0f);
                shieldSpread.fadeIn();
            }
        }

        /**
         * 根据伤害源参数和伤害对象判断伤害来源类型
         */
        protected ParamType getType(Object param, DamageAPI damage) {
            if (param instanceof DamagingProjectileAPI) return ParamType.PROJ;
            if (param instanceof BeamAPI) return ParamType.BEAM;
            if (param instanceof EmpArcEntityAPI) return ParamType.ARC;
            return ParamType.SHIP;
        }

        /**
         * 计算对护盾造成的实际伤害值，考虑伤害类型修正、舰船尺寸伤害加成、各类伤害减免等。
         * @param targetShip 承受伤害的目标舰船（主体或模块）—— 用于获取舰船尺寸和抗性
         * @param damage 原始伤害对象
         * @param source 伤害来源（可能为null）
         * @param isBeam 是否为光束伤害
         * @return 经过所有修正后的护盾伤害值
         */
        private float computeDamageToShield(ShipAPI targetShip, DamageAPI damage, ShipAPI source, boolean isBeam) {
            // 使用未经监听器修正的原始伤害，避免多插件叠加时互相读取减伤后的值
            float base = damage.getBaseDamage() * damage.getMultiplier();
            if (damage.isDps()) {
                float duration = damage.getDpsDuration();
                if (duration <= 0f) {
                    duration = Math.max(0f, Global.getCombatEngine().getElapsedInLastFrame());
                }
                base *= duration;
            }
            base *= damage.getType().getShieldMult();
            if (base <= 0f) return 0;
            float mult = 1;
            if (source != null) {
                switch (targetShip.getHullSize()) {
                    case FIGHTER:
                        mult *= source.getMutableStats().getDamageToFighters().getModifiedValue();
                        break;
                    case FRIGATE:
                        mult *= source.getMutableStats().getDamageToFrigates().getModifiedValue();
                        break;
                    case DESTROYER:
                        mult *= source.getMutableStats().getDamageToDestroyers().getModifiedValue();
                        break;
                    case CRUISER:
                        mult *= source.getMutableStats().getDamageToCruisers().getModifiedValue();
                        break;
                    case CAPITAL_SHIP:
                        mult *= source.getMutableStats().getDamageToCapital().getModifiedValue();
                        break;
                }
            }
            switch (damage.getType()) {
                case KINETIC:
                    mult *= targetShip.getMutableStats().getKineticDamageTakenMult().getModifiedValue();
                    break;
                case HIGH_EXPLOSIVE:
                    mult *= targetShip.getMutableStats().getHighExplosiveDamageTakenMult().getModifiedValue();
                    break;
                case FRAGMENTATION:
                    mult *= targetShip.getMutableStats().getFragmentationDamageTakenMult().getModifiedValue();
                    mult *= 0.25f;
                    break;
                case ENERGY:
                    mult *= targetShip.getMutableStats().getEnergyDamageTakenMult().getModifiedValue();
                    break;
            }
            if (isBeam) {
                mult *= targetShip.getMutableStats().getBeamDamageTakenMult().getModifiedValue();
            } else {
                mult *= targetShip.getMutableStats().getProjectileDamageTakenMult().getModifiedValue();
            }
            return base * mult;
        }

        /**
         * 设置舰船是否免疫EMP
         * @param isImmune true则设置EMP承受倍率为0，false则移除修改
         */
        public void immuneEMPEffects(boolean isImmune) {
            if (isImmune) {
                ship.getMutableStats().getEmpDamageTakenMult().modifyMult(KEY, 0f);
            } else {
                ship.getMutableStats().getEmpDamageTakenMult().unmodify(KEY);
            }
        }

        public static String getKey() {
            return KEY;
        }

        // 获取护盾展开程度（0~1），由渐变器控制
        public float getSpreadLevel() {
            return shieldSpread.getBrightness();
        }
    }
}
