package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import data.scripts.ai.VIII_BoomShipAI;
import data.scripts.listeners.VIII_BoomDeathListener;
import data.scripts.shipsystems.VIII_BoomSelfDestruct;

/**
 * 爆破单元（VIII_BOOM）专属的隐藏内置船插：只负责给本舰装上定制舰船 AI。
 * <p>
 * 为什么挂在船插里：VIII_BOOM 是普通舰体，没有模块/系统脚本可以顺便设置 AI；
 * 船插的 {@link #advanceInCombat(ShipAPI, float)} 每帧都会被引擎调用，
 * 在这里替换 AI 既不用担心舰船创建顺序，也能确保入战第一帧之后立刻接管。
 * <p>
 * 用舰船 customData 标记"已安装"，避免每次 stats 重建 / 每帧重复 new 一个 AI 实例。
 */
public class VIII_BoomAIHullmod extends BaseHullMod {

    /** 唯一生效的舰体 id */
    public static final String HULL_ID = "VIII_BOOM";

    /** 舰船 customData 里的安装标记（旧的一次性标记，保留兼容） */
    private static final String AI_KEY = "VIII_boom_ai_attached";

    /**
     * 心跳超时（秒）：超过这么久没收到 {@link VIII_BoomShipAI} 的心跳，
     * 就认为本舰 AI 已被引擎换回默认 AI（BasicShipAI），需要重新安装。
     */
    private static final float HEARTBEAT_MAX_AGE = 0.5F;

    /** 重装 AI 的最小间隔（秒）：防止 AI 被引擎每帧重置时每帧 new 实例造成卡顿 */
    private static final float INSTALL_RETRY_INTERVAL = 0.5F;

    /** 上一次尝试重装 AI 的战斗时间 */
    private static final String LAST_INSTALL_TRY_KEY = "VIII_boom_ai_last_install_try";

    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        if (!isBoom(ship)) {
            return;
        }
        if (!ship.isAlive() || ship.isHulk()) {
            return;
        }
        // "阵亡补爆"监听器：装配时没装上（比如中途读档）就在这里补装
        attachDeathListener(ship);
        if (ship.getCustomData() == null) {
            return;
        }
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) {
            return;
        }
        // 主菜单背景战斗（TitleScreenState 的 CombatEngine）里不要跑 AI：
        // 那里没有玩家舰、AI 会被引擎反复重设，心跳逻辑会每帧重装 AI，造成卡顿
        if (!VIII_BoomSelfDestruct.isRealBattle(engine)) {
            return;
        }
        float now = engine.getTotalElapsedTime(false);
        if (!isOurAIRunning(ship, now)) {
            installAI(ship, engine, now);
        }
    }

    /**
     * 本 AI 是否仍在运行：看上一帧的心跳够不够新。
     * <p>
     * 为什么不能只看一次性标记：引擎在舰船出场、建造完成（
     * {@code VIII_ThreatShipConstructionScript} 结尾会调 {@code setDefaultAI(null)}）、
     * 部署等时机都会重设 AI，而 {@code pickShipAIPlugin} 在没人接管时回退成
     * {@code BasicShipAI}——那正是"只绕圈、不加速"的表现。
     */
    private boolean isOurAIRunning(ShipAPI ship, float now) {
        Object raw = ship.getCustomData().get(VIII_BoomShipAI.HEARTBEAT_KEY);
        if (!(raw instanceof Float)) {
            return false;
        }
        float last = ((Float) raw).floatValue();
        return now - last <= HEARTBEAT_MAX_AGE;
    }

    /** 装上本舰专用 AI，并立刻写一次心跳（避免下一帧又判定为过期） */
    private void installAI(ShipAPI ship, CombatEngineAPI engine, float now) {
        // 重装节流：万一 AI 一直被外部重置，最多每 0.5 秒重装一次，避免每帧 new 造成卡顿
        Object rawTry = ship.getCustomData().get(LAST_INSTALL_TRY_KEY);
        if (rawTry instanceof Float) {
            float lastTry = ((Float) rawTry).floatValue();
            if (now - lastTry < INSTALL_RETRY_INTERVAL) {
                return;
            }
        }
        ship.setCustomData(LAST_INSTALL_TRY_KEY, Float.valueOf(now));

        VIII_BoomShipAI ai = new VIII_BoomShipAI(ship);
        ship.setShipAI(ai);
        ai.initSystemAI(engine);
        ship.setCustomData(AI_KEY, Boolean.TRUE);
        ship.setCustomData(VIII_BoomShipAI.HEARTBEAT_KEY, Float.valueOf(now));
    }

    /**
     * 舰船创建完成时调用：先挂上"阵亡补爆"监听器（比第一帧 advanceInCombat 更早），
     * 这样即使爆破单元在入战后第一帧就被打爆，也能补上爆炸。
     * <p>
     * 同时尝试装一次 AI。引擎有可能在随后的出场流程里把它换回默认 AI，
     * 所以真正的保障是 {@link #advanceInCombat(ShipAPI, float)} 里的心跳检测。
     */
    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        if (!isBoom(ship)) {
            return;
        }
        attachDeathListener(ship);
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null || ship.getCustomData() == null) {
            return;
        }
        if (!VIII_BoomSelfDestruct.isRealBattle(engine)) {
            return;
        }
        if (!isOurAIRunning(ship, engine.getTotalElapsedTime(false))) {
            installAI(ship, engine, engine.getTotalElapsedTime(false));
        }
    }

    /** 是否是 VIII_BOOM（本船插只对这一个舰体生效） */
    private boolean isBoom(ShipAPI ship) {
        if (ship == null || ship.getHullSpec() == null) {
            return false;
        }
        return HULL_ID.equals(ship.getHullSpec().getHullId());
    }

    /** 挂"阵亡补爆"监听器（同类型只挂一个） */
    private void attachDeathListener(ShipAPI ship) {
        if (ship == null) {
            return;
        }
        if (ship.hasListenerOfClass(VIII_BoomDeathListener.class)) {
            return;
        }
        ship.addListener(new VIII_BoomDeathListener(ship));
    }

    /**
     * 隐藏内置船插：不在装配界面显示描述。
     */
    @Override
    public boolean shouldAddDescriptionToTooltip(ShipAPI.HullSize hullSize, ShipAPI ship, boolean isForModSpec) {
        return false;
    }
}
