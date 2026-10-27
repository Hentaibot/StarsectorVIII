package data.scripts.weapons;

import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.util.Misc;
import data.scripts.ai.VIII_OilModuleAI;
import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import org.lwjgl.util.vector.Vector2f;

/**
 * VIII_fireoil 的武器每帧效果：武器开火的那一瞬间，把母舰上所有 VIII_oil 模块直接脱离。
 * <p>
 * 触发判定：本武器只有 1 发弹药（weapon_data.csv 里 ammo=1），所以"弹药数变少"或
 * "正在开火"都说明已经打过一发；用一个舰船自定义数据标记做去重，确保一次战斗里
 * 只执行一次脱离。
 * <p>
 * 弹药恒定 1 发：母舰上只有 1 个 VIII_oil 模块，所以进入战斗后本效果会把该武器的
 * 最大弹药和当前弹药都钉成 1（见 {@link #enforceSingleAmmo(WeaponAPI)}），
 * 即便舰船吃到导弹专精 / 扩展发射架等 +ammo 加成也不会多出第二发。
 * <p>
 * 脱离做法：
 * 传 null 是引擎官方的"模块脱离"入口（反编译见 com.fs.starfarer.combat.entities.Ship
 * 的 setStationSlot 与 CombatEngine.updateStationModuleLocations：只有 stationSlot
 * 不为 null 的模块才会被母舰强制定位、速度才会被清零），所以清掉槽位之后模块自己
 * 的位置和速度就独立了。之后给它换上 {@link VIII_OilModuleAI}，让它只会往前加速。
 * <p>
 * 碰撞处理：模块刚脱开时还贴在母舰身上，如果马上恢复碰撞会立刻和母舰互相挤压
 * （轻则被弹开、重则卡住甚至撞爆），所以脱离瞬间先把碰撞类临时设为
 * {@link CollisionClass#NONE}，并在每帧检查它到母舰的距离，超过
 * {@link #COLLISION_RESTORE_DISTANCE} 之后再恢复成 {@link CollisionClass#SHIP}。
 * <p>
 * 另外本效果还负责给母舰上的每个 VIII_oil 模块挂 {@link VIII_OilDeathListener}：
 * 模块被摧毁时若自爆系统的爆炸还没触发过，就补一次同样的爆炸
 * （详见 {@link data.scripts.shipsystems.VIII_OilSelfDestruct#detonate(ShipAPI, CombatEngineAPI)}）。
 * <p>
 * 母舰加成：真的把油罐扔出去之后，母舰最大航速 +{@link #SPEED_BONUS_FLAT}、
 * 机动性 +{@link #MANEUVER_BONUS_PERCENT}%（加速度 / 减速度 / 转向加速 / 转向角速度
 * 四项同加），并在玩家驾驶时于左侧状态栏常驻一条"油罐抛投"说明。
 * 加成用固定 source 每帧幂等重写，战斗内 stats 重建也不会丢。
 */
public class VIII_FireOilEffect implements EveryFrameWeaponEffectPlugin {

    /** 去重标记：挂在母舰的 customData 上，避免每帧重复脱离 */
    private static final String FIRED_KEY = "VIII_fireoil_module_detached";

    /** 需要脱离的模块舰体 id */
    private static final String MODULE_HULL_ID = "VIII_oil";

    /** 脱离瞬间沿母舰舰首方向额外给模块的初速度 */
    private static final float LAUNCH_SPEED = 120.0F;

    /**
     * 强制弹药数：母舰上只有 1 个 VIII_oil 模块，所以这门"点油"武器无论吃到什么
     * 弹药加成（导弹专精、扩展发射架、各种 +ammo 的船插/技能），都只能有 1 发。
     */
    private static final int FORCED_AMMO = 1;

    /**
     * 恢复碰撞的距离阈值（像素）。模块碰撞半径 63 左右，母舰是驱逐舰，
     * 300 像素足够让两者彻底错开，又不会让"幽灵状态"持续太久。
     */
    private static final float COLLISION_RESTORE_DISTANCE = 300.0F;

    /** 母舰加成挂在 customData 上的标记：确实扔出过油罐才给加成 */
    private static final String BUFF_KEY = "VIII_fireoil_buff_granted";

    /** 母舰加成的修改来源，用于幂等重写（同 source 再 modify 是覆盖，不会叠加） */
    private static final String BUFF_SOURCE = "VIII_fireoil_buff";

    /** 加成：最大航速 +50（su，加法） */
    private static final float SPEED_BONUS_FLAT = 50.0F;

    /**
     * 加成：机动性 +75%（百分比）。
     * <p>
     * 原版对"机动性"的定义见 hull_mods.csv 里辅助推进器的描述：
     * "提高舰船机动性，如加速度/减速度/转向加速/转向角速度"，
     * 对应 {@code getAcceleration()} / {@code getDeceleration()} /
     * {@code getTurnAcceleration()} / {@code getMaxTurnRate()} 四个 stat，
     * 这里四个一起加 75%。
     */
    private static final float MANEUVER_BONUS_PERCENT = 75.0F;

    /** 左侧状态栏图标的贴图（原版辅助推进器的图标，语义正好是机动性） */
    private static final String STATUS_ICON = "graphics/hullmods/auxilliary_thrusters.png";

    /** 左侧状态栏条目的 key，必须唯一 */
    private static final String STATUS_KEY = "VIII_fireoil_buff";

    /** 本效果实例是否已经给母舰上过加成（战斗内 stats 重建时用于幂等重写） */
    private boolean buffApplied = false;

    /**
     * 已经脱离、但还在"幽灵"（{@link CollisionClass#NONE}）状态的模块。
     * 每帧检查它们到母舰的距离，够远就恢复碰撞并从表里移除。
     */
    private final List<ShipAPI> detachedModules = new ArrayList<ShipAPI>();

    /**
     * 每帧调用。
     *
     * @param amount 帧时间（秒）
     * @param engine 战斗引擎
     * @param weapon 挂着本效果的武器
     */
    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused() || weapon == null) {
            return;
        }
        ShipAPI ship = weapon.getShip();
        // 已脱离模块的碰撞恢复要先于母舰存活判断：母舰可能已经阵亡，
        // 此时更要保证还处于"幽灵"状态的油罐能立刻恢复碰撞
        updateDetachedModules();
        if (ship == null) {
            return;
        }
        // 给母舰上每个 VIII_oil 模块挂"阵亡补爆"监听器（同类型只挂一个，每帧调用是幂等的）
        attachDeathListeners(ship);
        if (!ship.isAlive()) {
            return;
        }
        // 进战斗的第一帧就把弹药钉成 1 发，之后每帧兜底（详见方法注释）
        enforceSingleAmmo(weapon);
        // 已经脱离过：之后每帧只负责把加成和左侧状态栏维持住
        if (ship.getCustomData().containsKey(FIRED_KEY)) {
            if (Boolean.TRUE.equals(ship.getCustomData().get(BUFF_KEY))) {
                buffApplied = true;
            }
            maintainMothershipBuff(ship, engine);
            return;
        }
        if (!hasFired(weapon)) {
            return;
        }
        ship.setCustomData(FIRED_KEY, Boolean.TRUE);
        int detached = detachOilModules(ship);
        if (detached > 0) {
            // 确实扔出去油罐才给母舰加成
            ship.setCustomData(BUFF_KEY, Boolean.TRUE);
            buffApplied = true;
            maintainMothershipBuff(ship, engine);
        }
    }

    /**
     * 脱离模块后给母舰的加成：最大航速 +50、机动性 +75%（四个机动 stat 各 +75%），
     * 对玩家驾驶的母舰还会在左侧状态栏常驻一条说明。
     * <p>
     * 每帧调用是安全的：{@code modifyFlat/modifyPercent} 以 {@link #BUFF_SOURCE}
     * 为 key，重复写入是覆盖而不是叠加，所以中途 stats 被重建也不会丢加成。
     */
    private void maintainMothershipBuff(ShipAPI ship, CombatEngineAPI engine) {
        if (!buffApplied || ship == null || !ship.isAlive()) {
            return;
        }
        MutableShipStatsAPI stats = ship.getMutableStats();
        stats.getMaxSpeed().modifyFlat(BUFF_SOURCE, SPEED_BONUS_FLAT);
        stats.getAcceleration().modifyPercent(BUFF_SOURCE, MANEUVER_BONUS_PERCENT);
        stats.getDeceleration().modifyPercent(BUFF_SOURCE, MANEUVER_BONUS_PERCENT);
        stats.getTurnAcceleration().modifyPercent(BUFF_SOURCE, MANEUVER_BONUS_PERCENT);
        stats.getMaxTurnRate().modifyPercent(BUFF_SOURCE, MANEUVER_BONUS_PERCENT);

        // 只有玩家自己的舰船才有左侧状态栏，对 AI 舰调用没有意义
        if (engine != null && ship == engine.getPlayerShip()) {
            engine.maintainStatusForPlayerShip(STATUS_KEY, STATUS_ICON, "油罐抛投",
                    "最大航速 +" + (int) SPEED_BONUS_FLAT + "，机动性 +" + (int) MANEUVER_BONUS_PERCENT + "%", false);
        }
    }

    /**
     * 把武器的弹药数强制压到 1 发（最大值和当前值都压）。
     * <p>
     * 为什么需要：武器基础弹药只有 1 发，但导弹类武器会受到"导弹专精"、
     * "扩展发射架"等全局加成抬高 max ammo，可能出现 2 发甚至更多；
     * 而母舰上只有一个 VIII_oil 模块，多出来的弹药会让"点油"能重复脱离，逻辑就破了。
     * <p>
     * 判定写成幂等的，每帧都可安全调用：
     * - 最大值不等于 1 → 先 setMaxAmmo(1)；
     * - 当前值大于 1 → 再 setAmmo(1)；
     * - 已经打空（0 发）时不动，避免变成无限开火。
     * 进战斗第一帧执行一次即可生效，之后每帧兜底防止中途被加成改回去。
     */
    private void enforceSingleAmmo(WeaponAPI weapon) {
        if (weapon.getMaxAmmo() != FORCED_AMMO) {
            weapon.setMaxAmmo(FORCED_AMMO);
        }
        if (weapon.getAmmo() > FORCED_AMMO) {
            weapon.setAmmo(FORCED_AMMO);
        }
    }

    /**
     * 判断武器是否已经开过火。本武器弹药只有 1 发，所以弹药减少就是铁证；
     * 另外把 isFiring() 也算进来，避免个别情况下弹药结算滞后一帧。
     */
    private boolean hasFired(WeaponAPI weapon) {
        return weapon.isFiring() || weapon.getAmmo() < weapon.getMaxAmmo();
    }

    /**
     * 给母舰上每个 VIII_oil 模块挂上"阵亡补爆"监听器：模块被摧毁时如果还没炸过，
     * 就由监听器补一次自爆效果。用 hasListenerOfClass 去重，每帧调用安全。
     */
    private void attachDeathListeners(ShipAPI parent) {
        List<ShipAPI> modules = parent.getChildModulesCopy();
        for (int i = 0; i < modules.size(); i++) {
            ShipAPI module = modules.get(i);
            if (module == null || module.getHullSpec() == null) {
                continue;
            }
            if (!MODULE_HULL_ID.equals(module.getHullSpec().getHullId())) {
                continue;
            }
            if (module.hasListenerOfClass(VIII_OilDeathListener.class)) {
                continue;
            }
            module.addListener(new VIII_OilDeathListener(module));
        }
    }

    /** 遍历母舰的子模块，把舰体 id 为 VIII_oil 的模块全部脱离，返回脱离的数量 */
    private int detachOilModules(ShipAPI parent) {
        int detached = 0;
        List<ShipAPI> modules = parent.getChildModulesCopy();
        for (int i = 0; i < modules.size(); i++) {
            ShipAPI module = modules.get(i);
            if (module == null || module.getHullSpec() == null) {
                continue;
            }
            if (!MODULE_HULL_ID.equals(module.getHullSpec().getHullId())) {
                continue;
            }
            detachModule(module, parent);
            detached++;
        }
        return detached;
    }

    /** 真正把单个模块摘下来：解除停靠、临时关碰撞、解锁控制、给初速度、换 AI */
    private void detachModule(ShipAPI module, ShipAPI parent) {
        // 清空停靠槽位：引擎不再把它钉在母舰上，也不再每帧清零它的速度
        module.setStationSlot(null);
        // 模块默认是操控锁定的，解锁以便自定义 AI 的指令能正常生效
        module.setControlsLocked(false);
        // 刚脱开时模块还贴在母舰上，先关掉碰撞避免和母舰互相挤压/撞爆，
        // 等它飞远了再由 updateDetachedModules() 恢复成 SHIP
        module.setCollisionClass(CollisionClass.NONE);
        detachedModules.add(module);

        float angle = parent.getFacing();
        Vector2f velocity = new Vector2f(module.getVelocity());
        velocity.x += (float) Math.cos(Math.toRadians(angle)) * LAUNCH_SPEED;
        velocity.y += (float) Math.sin(Math.toRadians(angle)) * LAUNCH_SPEED;
        module.getVelocity().set(velocity);

        module.setShipAI(new VIII_OilModuleAI(module));
    }

    /**
     * 每帧检查处于"幽灵"状态的模块：离母舰超过阈值（或母舰已经没了）就恢复碰撞。
     * <p>
     * 用 {@link ListIterator} 边遍历边移除，避免在 for-each 里删元素。
     * 模块阵亡/变成残骸时直接移出列表，没必要再操心碰撞。
     */
    private void updateDetachedModules() {
        if (detachedModules.isEmpty()) {
            return;
        }
        ListIterator<ShipAPI> iterator = detachedModules.listIterator();
        while (iterator.hasNext()) {
            ShipAPI module = iterator.next();
            if (module == null || !module.isAlive() || module.isHulk()) {
                iterator.remove();
                continue;
            }
            ShipAPI parent = module.getParentStation();
            if (parent == null || !parent.isAlive()) {
                module.setCollisionClass(CollisionClass.SHIP);
                iterator.remove();
                continue;
            }
            float distance = Misc.getDistance(module.getLocation(), parent.getLocation());
            if (distance > COLLISION_RESTORE_DISTANCE) {
                module.setCollisionClass(CollisionClass.SHIP);
                iterator.remove();
            }
        }
    }
}
