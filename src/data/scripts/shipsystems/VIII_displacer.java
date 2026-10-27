package data.scripts.shipsystems;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.input.InputEventAPI;

import java.util.List;

/**
 * VIII_displacer 舰船系统 —— 独立插件版
 * 效果：
 *   - 系统激活期间：无特殊效果。
 *   - 系统结束后（进入冷却时）：获得持续 3 秒的“全武器射速 +50%”增益。
 *     增益不可叠加（重新激活系统会立即取消旧增益并开始新的计时），
 *     无冷却时间（每次退出都会触发）。
 * 实现方式：使用 BaseEveryFrameCombatPlugin 独立管理增益生命周期，
 * 避免依赖系统自身的 apply/unapply 状态切换，确保增益稳定生效。
 */
public class VIII_displacer extends BaseShipSystemScript {

    private static final String BUFF_ID = "VIII_displacer_rof_buff";
    private static final float BUFF_DURATION = 3f;
    private static final float ROF_MULT = 1.5f;

    /** 存储在舰船自定义数据中的状态，包含活跃的增益插件和显示计时 */
    private static class BuffState {
        boolean buffActive;                // 增益是否正在生效（用于UI）
        float buffTimer;                   // 剩余时间（用于UI）
        BaseEveryFrameCombatPlugin plugin; // 当前管理增益的插件（null 表示无增益）
    }

    @Override
    public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
        if (!(stats.getEntity() instanceof ShipAPI ship)) return;
        if (ship.isHulk()) return;

        BuffState stateData = getState(ship);
        boolean active = (state == State.IN || state == State.ACTIVE || state == State.OUT);

        // 系统激活时：强制取消之前可能存在的增益，确保不叠加
        if (active && stateData.plugin != null) {
            cancelBuff(stateData, stats);
        }
    }

    @Override
    public void unapply(MutableShipStatsAPI stats, String id) {
        if (!(stats.getEntity() instanceof ShipAPI ship)) return;
        BuffState stateData = getState(ship);

        // 系统结束 → 启动新的定时增益插件（仅在当前无增益时）
        if (stateData.plugin == null) {
            startBuffViaPlugin(ship, stateData, stats);
        }
    }

    /**
     * 通过向战斗引擎添加一个一次性插件来实现定时增益。
     * 插件完全独立于系统状态机，保证增益稳定执行。
     */
    private void startBuffViaPlugin(ShipAPI ship, BuffState stateData, MutableShipStatsAPI stats) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) return;

        // 立即应用射速修改
        startBuff(stats);
        stateData.buffActive = true;
        stateData.buffTimer = BUFF_DURATION;

        // 创建管理插件
        BaseEveryFrameCombatPlugin plugin = new BaseEveryFrameCombatPlugin() {
            @Override
            public void advance(float amount, List<InputEventAPI> events) {
                if (engine.isPaused()) return;

                // 如果舰船死亡，立即清理
                if (!ship.isAlive()) {
                    endBuff(stats);
                    stateData.buffActive = false;
                    stateData.plugin = null;
                    engine.removePlugin(this);
                    return;
                }

                // 更新计时器
                stateData.buffTimer -= amount;
                if (stateData.buffTimer <= 0f) {
                    endBuff(stats);
                    stateData.buffActive = false;
                    stateData.plugin = null;
                    engine.removePlugin(this);

                }
                displayStatus(ship);
            }
        };

        // 记录插件引用，以便取消时移除

        stateData.plugin = plugin;
        engine.addPlugin(plugin);

    }
    private void displayStatus(ShipAPI ship) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null)
            return;
        // 只对玩家舰船显示状态
        if (ship == engine.getPlayerShip()) {
            engine.maintainStatusForPlayerShip(
                    "VIII_displacer",
                    "graphics/icons/hullsys/displacer.png",
                    "突击闪现",
                    "武器射速 +50%",
                    false);
        }
    }

    /** 取消当前增益（移除插件并清除属性修改） */
    private void cancelBuff(BuffState stateData, MutableShipStatsAPI stats) {
        if (stateData.plugin != null) {
            Global.getCombatEngine().removePlugin(stateData.plugin);
            stateData.plugin = null;
        }
        endBuff(stats);
        stateData.buffActive = false;
        stateData.buffTimer = 0f;
    }


    private BuffState getState(ShipAPI ship) {
        final String key = "VIII_displacer_state";
        Object obj = ship.getCustomData().get(key);
        if (obj instanceof BuffState) {
            return (BuffState) obj;
        }
        BuffState newState = new BuffState();
        ship.setCustomData(key, newState);
        return newState;
    }

    private void startBuff(MutableShipStatsAPI stats) {
        stats.getBallisticRoFMult().modifyMult(BUFF_ID, ROF_MULT);
        stats.getEnergyRoFMult().modifyMult(BUFF_ID, ROF_MULT);
        stats.getMissileRoFMult().modifyMult(BUFF_ID, ROF_MULT);
    }

    private void endBuff(MutableShipStatsAPI stats) {
        stats.getBallisticRoFMult().unmodify(BUFF_ID);
        stats.getEnergyRoFMult().unmodify(BUFF_ID);
        stats.getMissileRoFMult().unmodify(BUFF_ID);
    }
}