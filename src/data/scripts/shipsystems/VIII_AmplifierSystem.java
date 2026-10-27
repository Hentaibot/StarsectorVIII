package data.scripts.shipsystems;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.input.InputEventAPI;
import data.scripts.VIII_threat.VIII_SwarmLauncherEffect;

import java.util.List;
import java.util.Map;

/**
 * 棱镜系列舰船的战术系统：激活时碎片群规模 / 重生率提升，所有武器射程提升，
 * 并为本舰发射的攻击群附加武器伤害加成。
 * 生命周期设计（重要）：
 * - ShipSystemStatsScript 的契约是 apply 施加、unapply 移除，而 apply 只在 IN/ACTIVE/OUT 阶段调用。
 *   一旦某次关闭没有走到 unapply（舰船中途被摧毁、系统被强制中断、退出末尾 effectLevel 恰为 0 等），
 *   加成就会永久残留。因此本类不把清理押在 unapply 上，而是：
 *   1) apply 中每帧刷新一次"激活心跳"时间戳；
 *   2) 由守护插件（每帧运行）监测心跳，超时即做全量清理并自我注销。
 * - 攻击群加成使用"每艘母舰独立的修饰键 + 幂等全量清理"，
 *   不依赖任何写在成员身上的标记，成员被回收、重生、标记丢失都不会造成残留。
 * - 本类不保存任何实例字段，与某艘舰船相关的数据一律存放在该舰船的 getCustomData() 中，
 *   因为同一个系统 ID 的多艘舰船可能共用同一个脚本实例。
 */
public class VIII_AmplifierSystem extends BaseShipSystemScript {

    // ==================== 固定键 ====================

    /** 母舰自身加成的修饰键 */
    private static final String ID = "VIII_PrismAmplifier";
    /** 攻击群伤害加成的修饰键前缀（实际键会拼上母舰身份哈希，避免多舰互相干扰） */
    private static final String SWARM_DAMAGE_ID_PREFIX = ID + "_swarm_damage_";
    /** 记录"本舰上次处于生效状态的时间"（写在母舰 customData 上） */
    private static final String LAST_ACTIVE_KEY = "VIII_AmplifierSystem_last_active";
    /** 记录"上次同步攻击群加成的时间"（写在母舰 customData 上） */
    private static final String LAST_SYNC_KEY = "VIII_AmplifierSystem_last_sync";
    /** 记录"本舰的守护插件"（写在母舰 customData 上） */
    private static final String GUARD_PLUGIN_KEY = "VIII_AmplifierSystem_guard_plugin";

    // ==================== 数值配置 ====================

    /** 碎片群规模倍率（2 表示 ×2） */
    private static final float SWARM_SIZE_MULT = 2f;
    /** 碎片重生率倍率（4 表示 ×4） */
    private static final float SWARM_RESPAWN_MULT = 16f;
    /** 武器射程加成百分比（100 表示 +100%） */
    private static final float RANGE_BONUS_PERCENT = 100f;
    /** 攻击群武器伤害倍率（2 表示 +100%） */
    private static final float SWARM_DAMAGE_MULT = 2f;

    /** 攻击群加成同步间隔（秒）：避免每帧遍历全场舰船 */
    private static final float SYNC_INTERVAL = 0.25f;
    /** 守护插件判定"系统已关闭"的静默时长（秒） */
    private static final float GUARD_TIMEOUT = 0.5f;

    /** HUD 状态栏键（脚本实例可能被多舰共用，故声明为静态常量） */
    private static final Object STATUS_KEY_1 = new Object();
    private static final Object STATUS_KEY_2 = new Object();
    private static final Object STATUS_KEY_3 = new Object();

    // ==================== 主逻辑 ====================

    /**
     * 每帧调用：施加加成、刷新激活心跳、确保守护插件在运行。
     *
     * @param stats       舰船可变属性
     * @param id          引擎传入的键（本类忽略，统一改用固定常量 ID）
     * @param state       当前系统状态
     * @param effectLevel 效果等级（0~1）
     */
    @Override
    public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
        // 空值保护（用显式强转，避免依赖较新的语言特性）
        if (stats == null) return;
        if (!(stats.getEntity() instanceof ShipAPI ship)) return;

        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) return;

        // 只有玩家旗舰需要维护 HUD 状态栏
        if (ship == engine.getPlayerShip()) {
            maintainStatus(ship, effectLevel);
        }

        // ---------- 系统未生效：立即全量清理，不依赖 unapply 是否被调用 ----------
        if (effectLevel <= 0f) {
            cleanupAll(ship, stats);
            return;
        }

        // ---------- 系统生效中 ----------
        // 母舰自身加成（重复调用同一键为覆盖，天然幂等）
        applyMothershipBonus(stats);

        // 刷新激活心跳，供守护插件判断系统是否仍在运行
        Map<String, Object> shipData = ship.getCustomData();
        if (shipData != null) {
            shipData.put(LAST_ACTIVE_KEY, engine.getTotalElapsedTime(false));
        }

        // 确保守护插件在运行（负责攻击群加成的同步与超时兜底清理）
        ensureGuardPlugin(ship);
    }

    /**
     * 系统结束时移除全部加成（快速路径；即使此方法未被调用，守护插件也会兜底）。
     *
     * @param stats 舰船可变属性
     * @param id    引擎传入的键（本类忽略）
     */
    @Override
    public void unapply(MutableShipStatsAPI stats, String id) {
        if (stats == null) return;

        ShipAPI ship = null;
        if (stats.getEntity() instanceof ShipAPI) {
            ship = (ShipAPI) stats.getEntity();
        }
        cleanupAll(ship, stats);
    }

    // ==================== 加成施加 / 移除 ====================

    /**
     * 施加母舰自身加成：碎片规模、碎片重生率、三类武器射程。
     *
     * @param stats 舰船可变属性
     */
    private void applyMothershipBonus(MutableShipStatsAPI stats) {
        if (stats == null) return;

        stats.getDynamic().getMod(Stats.FRAGMENT_SWARM_SIZE_MOD).modifyMult(ID, SWARM_SIZE_MULT);
        stats.getDynamic().getMod(Stats.FRAGMENT_SWARM_RESPAWN_RATE_MULT).modifyMult(ID, SWARM_RESPAWN_MULT);

        stats.getEnergyWeaponRangeBonus().modifyPercent(ID, RANGE_BONUS_PERCENT);
        stats.getBallisticWeaponRangeBonus().modifyPercent(ID, RANGE_BONUS_PERCENT);
        stats.getMissileWeaponRangeBonus().modifyPercent(ID, RANGE_BONUS_PERCENT);
    }

    /**
     * 移除母舰自身加成（键必须与 applyMothershipBonus 完全一致）。
     *
     * @param stats 舰船可变属性
     */
    private void removeMothershipBonus(MutableShipStatsAPI stats) {
        if (stats == null) return;

        stats.getDynamic().getMod(Stats.FRAGMENT_SWARM_SIZE_MOD).unmodifyMult(ID);
        stats.getDynamic().getMod(Stats.FRAGMENT_SWARM_RESPAWN_RATE_MULT).unmodifyMult(ID);

        stats.getEnergyWeaponRangeBonus().unmodify(ID);
        stats.getBallisticWeaponRangeBonus().unmodify(ID);
        stats.getMissileWeaponRangeBonus().unmodify(ID);
    }

    /**
     * 一次性清理全套效果：母舰加成 + 攻击群加成 + 守护插件 + 心跳时间戳。
     * 幂等：重复调用无副作用。
     *
     * @param ship  母舰（可为 null）
     * @param stats 母舰属性（可为 null）
     */
    private void cleanupAll(ShipAPI ship, MutableShipStatsAPI stats) {
        removeMothershipBonus(stats);
        clearSwarmDamage(ship);
        removeGuardPlugin(ship);
    }

    /**
     * 取得"本舰专属"的攻击群伤害加成修饰键。
     * 键名附带母舰的身份哈希，多艘棱镜舰同时开系统时互不覆盖、互不误删。
     *
     * @param mothership 母舰
     * @return 修饰键
     */
    private String swarmDamageIdFor(ShipAPI mothership) {
        return SWARM_DAMAGE_ID_PREFIX + System.identityHashCode(mothership);
    }

    /**
     * 按当前成员名单同步攻击群加成：
     * 是本舰攻击群的成员 → 施加（幂等覆盖）；其他的 → 摘掉本舰的修饰键（没装过也无副作用）。
     *
     * @param mothership 母舰
     * @param engine     战斗引擎
     */
    private void syncSwarmBonuses(ShipAPI mothership, CombatEngineAPI engine) {
        if (mothership == null || engine == null) return;

        String key = swarmDamageIdFor(mothership);
        List<ShipAPI> ships = engine.getShips();
        // 使用索引遍历，避免列表在遍历中被修改导致异常
        for (ShipAPI other : ships) {
            if (other == null || other == mothership) continue;

            if (isSwarmMemberOf(mothership, other)) {
                applySwarmDamage(other, key);
            } else {
                unmodifySwarmDamage(other, key);
            }
        }
    }

    /**
     * 全量摘除本舰为攻击群施加的加成，并清空本舰的同步 / 心跳时间戳。
     * 不依赖任何存活标记，因此成员标记丢失也不会造成残留。
     *
     * @param mothership 母舰（可为 null）
     */
    private void clearSwarmDamage(ShipAPI mothership) {
        if (mothership == null) return;

        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine != null) {
            String key = swarmDamageIdFor(mothership);
            List<ShipAPI> ships = engine.getShips();
            for (ShipAPI other : ships) {
                if (other == null || other == mothership) continue;
                unmodifySwarmDamage(other, key);
            }
        }

        Map<String, Object> shipData = mothership.getCustomData();
        if (shipData != null) {
            shipData.remove(LAST_SYNC_KEY);
            shipData.remove(LAST_ACTIVE_KEY);
        }
    }

    /**
     * 判断 other 是否为本舰发射的攻击群成员。
     *
     * @param mothership 母舰
     * @param other      待判断的舰船
     * @return 是则返回 true
     */
    private boolean isSwarmMemberOf(ShipAPI mothership, ShipAPI other) {
        if (other == null || other == mothership) return false;

        // 必须是存活的战斗机，且不是残骸或穿梭舱
        if (!other.isFighter() || !other.isAlive() || other.isHulk() || other.isShuttlePod()) {
            return false;
        }

        Map<String, Object> data = other.getCustomData();
        if (data == null) return false;

        // 必须由本舰发射（按引用比较同一艘 ShipAPI 实例）
        if (data.get(VIII_SwarmLauncherEffect.ATTACK_OWNER_KEY) != mothership) return false;

        // 必须带有攻击群标记（而不是普通战机 / 常规无人机）
        Object tag = data.get(VIII_SwarmLauncherEffect.ATTACK_TAG_KEY);
        return (tag instanceof Boolean) && (Boolean) tag;
    }

    /**
     * 施加攻击群武器伤害加成。
     *
     * @param member 攻击群成员
     * @param key    本舰专属修饰键
     */
    private void applySwarmDamage(ShipAPI member, String key) {
        if (member == null) return;
        MutableShipStatsAPI memberStats = member.getMutableStats();
        if (memberStats == null) return;

        memberStats.getBallisticWeaponDamageMult().modifyMult(key, SWARM_DAMAGE_MULT);
        memberStats.getEnergyWeaponDamageMult().modifyMult(key, SWARM_DAMAGE_MULT);
        memberStats.getMissileWeaponDamageMult().modifyMult(key, SWARM_DAMAGE_MULT);
    }

    /**
     * 移除攻击群武器伤害加成。使用 unmodify(key) 一次性清除该键下所有加成，与加成类型无关。
     *
     * @param member 攻击群成员
     * @param key    本舰专属修饰键
     */
    private void unmodifySwarmDamage(ShipAPI member, String key) {
        if (member == null) return;
        MutableShipStatsAPI memberStats = member.getMutableStats();
        if (memberStats == null) return;

        memberStats.getBallisticWeaponDamageMult().unmodify(key);
        memberStats.getEnergyWeaponDamageMult().unmodify(key);
        memberStats.getMissileWeaponDamageMult().unmodify(key);
    }

    // ==================== 守护插件 ====================

    /**
     * 确保母舰的守护插件正在运行。
     * 插件职责：按间隔同步攻击群加成；心跳超时或母舰离场时执行全量清理并自我注销。
     *
     * @param ship 母舰
     */
    private void ensureGuardPlugin(final ShipAPI ship) {
        if (ship == null) return;

        final Map<String, Object> shipData = ship.getCustomData();
        if (shipData == null) return;
        // 已存在则不重复注册
        if (shipData.get(GUARD_PLUGIN_KEY) != null) return;

        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) return;

        BaseEveryFrameCombatPlugin guard = new BaseEveryFrameCombatPlugin() {
            @Override
            public void advance(float amount, List<InputEventAPI> events) {
                CombatEngineAPI engine = Global.getCombatEngine();
                if (engine == null) return;
                // 暂停期间不做任何判定，避免误清理
                if (engine.isPaused()) return;

                // 母舰已离场：全量清理后注销自己
                if (!engine.isEntityInPlay(ship) || ship.isHulk()) {
                    cleanupAll(ship, ship.getMutableStats());
                    removeGuardPlugin(ship);
                    return;
                }

                Map<String, Object> data = ship.getCustomData();
                if (data == null) {
                    removeGuardPlugin(ship);
                    return;
                }

                // 心跳超时 → 系统已不再生效（apply 不再被调用），执行全量清理
                float now = engine.getTotalElapsedTime(false);
                Object activeObj = data.get(LAST_ACTIVE_KEY);
                float lastActive = (activeObj instanceof Float) ? (Float) activeObj : -1f;
                if (lastActive < 0f || now - lastActive > GUARD_TIMEOUT) {
                    cleanupAll(ship, ship.getMutableStats());
                    removeGuardPlugin(ship);
                    return;
                }

                // 按固定间隔同步攻击群加成
                Object syncObj = data.get(LAST_SYNC_KEY);
                float lastSync = (syncObj instanceof Float)
                        ? (Float) syncObj
                        : Float.NEGATIVE_INFINITY;
                if (now - lastSync < SYNC_INTERVAL) return;

                data.put(LAST_SYNC_KEY, now);
                syncSwarmBonuses(ship, engine);
            }
        };

        shipData.put(GUARD_PLUGIN_KEY, guard);
        engine.addPlugin(guard);
    }

    /**
     * 注销母舰的守护插件。
     * 插件在自己的 advance 中调用本方法（即移除自身）是可行做法，引擎会安全处理。
     *
     * @param ship 母舰
     */
    private void removeGuardPlugin(ShipAPI ship) {
        if (ship == null) return;

        Map<String, Object> shipData = ship.getCustomData();
        if (shipData == null) return;

        Object obj = shipData.remove(GUARD_PLUGIN_KEY);
        if (!(obj instanceof BaseEveryFrameCombatPlugin)) return;

        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine != null) {
            engine.removePlugin((BaseEveryFrameCombatPlugin) obj);
        }
    }

    // ==================== 状态栏 ====================

    /**
     * 维护玩家旗舰的状态栏文本（必须每帧调用才会持续显示）。
     *
     * @param playerShip  玩家舰船
     * @param effectLevel 效果等级
     */
    protected void maintainStatus(ShipAPI playerShip, float effectLevel) {
        if (playerShip == null) return;

        ShipSystemAPI cloak = playerShip.getPhaseCloak();
        if (cloak == null) {
            cloak = playerShip.getSystem();
        }
        if (cloak == null) return;

        // 效果等级为 0 时不显示任何条目
        if (effectLevel <= 0f) return;

        String icon = cloak.getSpecAPI().getIconSpriteName();
        String title = cloak.getDisplayName();

        Global.getCombatEngine().maintainStatusForPlayerShip(
                STATUS_KEY_1, icon, title, "碎片规模 ×2 · 重生率 ×4", false);
        Global.getCombatEngine().maintainStatusForPlayerShip(
                STATUS_KEY_2, icon, title, "全部武器射程 +100%", false);
        Global.getCombatEngine().maintainStatusForPlayerShip(
                STATUS_KEY_3, icon, title, "攻击群武器伤害 +100%", false);
    }
}