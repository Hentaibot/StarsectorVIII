package data.scripts.shipsystems;

import java.awt.Color;
import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.FighterLaunchBayAPI;
import com.fs.starfarer.api.combat.FighterWingAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import org.dark.shaders.distortion.DistortionShader;
import org.dark.shaders.distortion.RippleDistortion;
import org.lwjgl.util.vector.Vector2f;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.plugins.ShipSystemStatsScript;

import data.scripts.VIII_threat.VIII_RoilingSwarmEffect;

/**
 * 日蚀（VIII_eclipse）专属战术系统：分流发生器。
 * <p>
 * 开启时消耗 30% 的最大幅能，并把本舰所有舰载机的编制翻倍（多出来的数量作为
 * "额外部署"发射，最多维持 2 倍数量）；前摇/持续/后摇期间，本舰放出的舰载机与
 * 碎片集群获得和「时流之壳」相同的时流倍率（1 + 2 × 等级²，满级 3 倍）。
 * 进入后摇（OUT）时立即取消额外编制并命令多出来的舰载机返回母舰。
 * <p>
 * 实现要点：
 * - 时流倍率写进舰载机的 {@code MutableShipStatsAPI.getTimeMult()}；引擎在
 *   CombatEngine.advance 里对 timeMult &gt; 1 的舰船做额外的 advance 迭代
 *   （"Advancing fast-time ships"），所以这是真正的加速，不只是视觉。
 * - 碎片集群（{@link VIII_RoilingSwarmEffect}）不是 ShipAPI，没有 stats；
 *   这里用该类的 timeMult 字段整体缩放它的每帧更新量。
 * - 舰载机数量翻倍照抄原版 ReserveWingStats（后备部署）的做法：
 *   extraDeploymentLimit = 2 × 编制、extraDeployments = 还差多少架、
 *   fastReplacements 加 1 倍编制、extraDuration 给时限，并 makeCurrentIntervalFast()
 *   让当前补充间隔立刻结束——缺这一步额外舰载机根本不会出来。
 * - 舰载机获得时流的同时叠加原版「时流之壳」的抖动；碎片集群只加速、不加抖动。
 * - 幅能消耗只能写在脚本里：0.98 的引擎没有读取 ship_systems.csv 的 flux/use 列。
 */
public class VIII_EclipseManifold extends BaseShipSystemScript {

    /** 修正来源标识，用于 unmodify */
    public static final String SOURCE_ID = "VIII_eclipse_manifold";

    /** 系统 id，与 VIII_eclipse_manifold.system 一致 */
    public static final String SYSTEM_ID = "VIII_eclipse_manifold";

    /** 开启时消耗的幅能占最大幅能的比例 */
    public static final float FLUX_COST_FRACTION = 0.30F;

    /** 舰载机/碎片集群的时流倍率上限（与「时流之壳」一致：1 + 2 × 等级²） */
    public static final float MAX_TIME_MULT = 3.0F;

    private static final String CHARGED_KEY = "VIII_eclipse_manifold_charged";
    private static final String WINGS_DOUBLED_KEY = "VIII_eclipse_manifold_wings_doubled";
    private static final String STATUS_KEY = "VIII_eclipse_manifold_status";

    private static final String STATUS_ICON = "graphics/icons/hullsys/temporal_shell.png";

    private static final Color JITTER_COLOR = new Color(120, 190, 255, 60);

    /** 额外部署的存活时限（秒）：覆盖 20 秒持续 + 10 秒后摇，回收仍由本脚本负责 */
    /** 额外部署重新武装的节流间隔（秒），以及记录上次武装时间的键 */
    /** 原版「时流之壳」的系统 id：舰载机自带它并开启时，自己会加抖动，本系统不再叠加 */
    private static final String TEMPORAL_SHELL_ID = "temporalshell";

    private static final float DEPLOY_TICK_INTERVAL = 0.5F;

    private static final String DEPLOY_TICK_KEY = "VIII_eclipse_manifold_deploy_tick";

    private static final float EXTRA_FIGHTER_DURATION = 60.0F;

    /** 原版 ReserveWingStats 的「该机翼不允许额外舰载机」标签 */
    public static final String RD_NO_EXTRA_CRAFT = "rd_no_extra_craft";

    /** 时流观感改走 GraphicsLib 失真（GPU）：每 0.28 秒补一圈、单圈寿命 0.45 秒 */
    private static final String DISTORTION_TICK_KEY = "VIII_eclipse_manifold_distortion_tick";

    private static final float DISTORTION_INTERVAL = 0.28F;

    private static final float DISTORTION_LIFETIME = 0.45F;

    private static final float DISTORTION_INTENSITY = 0.10F;

    private static final float DISTORTION_SIZE_MULT = 1.8F;

    @Override
    public void apply(MutableShipStatsAPI stats, String id, ShipSystemStatsScript.State state, float effectLevel) {
        ShipAPI ship = getShip(stats);
        if (ship == null) {
            return;
        }
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null || engine.isPaused()) {
            return;
        }

        if (state == ShipSystemStatsScript.State.IN) {
            chargeFluxOnce(ship);
        } else if (state == ShipSystemStatsScript.State.OUT) {
            recallExtraFighters(ship);
        } else if (state != ShipSystemStatsScript.State.ACTIVE) {
            return;
        }

        float level = clamp(effectLevel);
        if (state == ShipSystemStatsScript.State.IN || state == ShipSystemStatsScript.State.ACTIVE) {
            deployExtraFighters(ship, level);
        }
        applyTimeFlow(ship, engine, level, state);
        applyJitter(ship, level);
        showStatus(ship, engine, level);
    }

    @Override
    public void unapply(MutableShipStatsAPI stats, String id) {
        ShipAPI ship = getShip(stats);
        if (ship == null) {
            return;
        }
        CombatEngineAPI engine = Global.getCombatEngine();
        clearTimeFlow(ship, engine);
        recallExtraFighters(ship);
        if (ship.getCustomData() != null) {
            ship.setCustomData(CHARGED_KEY, Boolean.FALSE);
            ship.setCustomData(WINGS_DOUBLED_KEY, Boolean.FALSE);
        }
    }

    // ==================== 幅能消耗 ====================

    /**
     * 开启首帧扣一次幅能（30% 最大幅能）。用舰船 customData 记录，避免脚本实例
     * 被多艘舰共享时重复扣费。
     */
    private void chargeFluxOnce(ShipAPI ship) {
        if (ship.getCustomData() == null) {
            return;
        }
        if (Boolean.TRUE.equals(ship.getCustomData().get(CHARGED_KEY))) {
            return;
        }
        float cost = ship.getMaxFlux() * FLUX_COST_FRACTION;
        if (cost > 0.0F) {
            ship.getFluxTracker().increaseFlux(cost, false);
        }
        ship.setCustomData(CHARGED_KEY, Boolean.TRUE);
    }

    // ==================== 舰载机数量翻倍 / 回收 ====================

    /**
     * 照抄原版 ReserveWingStats（后备部署）的写法把舰载机编制临时翻倍。
     * 原版里这几步缺一不可，尤其是 makeCurrentIntervalFast()：没有它，机库要等
     * 当前完整的替换周期走完才会放出额外舰载机，表现就是“系统开了但飞机没变多”。
     * 只在 effectLevel 达到 1 时执行（原版同样以 effectLevel == 1.0f 为条件）。
     */
    private void deployExtraFighters(ShipAPI ship, float level) {
        if (level < 0.999F || ship.getCustomData() == null) {
            return;
        }
        // 0.5 秒节流：允许额外舰载机阵亡后按缺口补回，但不会每帧重复武装
        float now = Global.getCombatEngine().getTotalElapsedTime(false);
        Object lastRaw = ship.getCustomData().get(DEPLOY_TICK_KEY);
        if (lastRaw instanceof Float && now - ((Float) lastRaw).floatValue() < DEPLOY_TICK_INTERVAL) {
            return;
        }
        ship.setCustomData(DEPLOY_TICK_KEY, Float.valueOf(now));
        List<FighterLaunchBayAPI> bays = ship.getLaunchBaysCopy();
        if (bays == null || bays.isEmpty()) {
            return;
        }
        for (FighterLaunchBayAPI bay : bays) {
            if (bay == null || bay.getWing() == null || bay.getWing().getSpec() == null) {
                continue;
            }
            FighterWingSpecAPI spec = bay.getWing().getSpec();
            int base = spec.getNumFighters();
            if (base <= 0) {
                continue;
            }
            int addForWing = spec.hasTag(RD_NO_EXTRA_CRAFT) ? 0 : base;
            int maxTotal = base + addForWing;
            bay.setExtraDeploymentLimit(maxTotal);
            bay.makeCurrentIntervalFast();
            int actualAdd = maxTotal - bay.getWing().getWingMembers().size();
            if (actualAdd <= 0) {
                continue;
            }
            if (bay.getFastReplacements() < addForWing) {
                bay.setFastReplacements(addForWing);
            }
            bay.setExtraDeployments(actualAdd);
            bay.setExtraDuration(EXTRA_FIGHTER_DURATION);
        }
        if (ship.getCustomData() != null) {
            ship.setCustomData(WINGS_DOUBLED_KEY, Boolean.TRUE);
        }
    }

    /**
     * 取消额外编制（上限归零、待部署清零），并让超出原编制的舰载机返航。
     */
    private void recallExtraFighters(ShipAPI ship) {
        if (ship.getCustomData() == null) {
            return;
        }
        if (!Boolean.TRUE.equals(ship.getCustomData().get(WINGS_DOUBLED_KEY))) {
            return;
        }
        List<FighterLaunchBayAPI> bays = ship.getLaunchBaysCopy();
        if (bays != null) {
            for (FighterLaunchBayAPI bay : bays) {
                if (bay == null) {
                    continue;
                }
                bay.setExtraDeployments(0);
                bay.setExtraDeploymentLimit(0);
                bay.setExtraDuration(0.0F);
                bay.setFastReplacements(0);
                FighterWingAPI wing = bay.getWing();
                if (wing == null || wing.getSpec() == null) {
                    continue;
                }
                int base = wing.getSpec().getNumFighters();
                List<ShipAPI> members = wing.getWingMembers();
                if (members == null) {
                    continue;
                }
                for (int i = base; i < members.size(); i++) {
                    ShipAPI member = members.get(i);
                    if (member == null || !member.isAlive() || member.isHulk()) {
                        continue;
                    }
                    wing.orderReturn(member);
                }
            }
        }
        ship.setCustomData(WINGS_DOUBLED_KEY, Boolean.FALSE);
    }

    // ==================== 时流效果 ====================

    private void applyTimeFlow(ShipAPI ship, CombatEngineAPI engine, float level,
            ShipSystemStatsScript.State state) {
        float mult = timeMultFor(level);
        if (mult <= 1.001F) {
            return;
        }
        boolean spawnDistortion = consumeDistortionTick(ship);
        List<ShipAPI> ships = engine.getShips();
        if (ships != null) {
            for (ShipAPI other : ships) {
                if (!isOwnFighterOf(other, ship)) {
                    continue;
                }
                other.getMutableStats().getTimeMult().modifyMult(SOURCE_ID, mult);
                // 时流观感改用 GraphicsLib 的失真着色器（GPU）：舰载机不再逐个
                // setJitter/setJitterUnder，避免每帧几十份精灵拷贝的 CPU 开销。
                // 舰载机自己的系统就是「时流之壳」正在开启时，它已经自带抖动，这里也不再叠加。
                if (spawnDistortion && !hasOwnTemporalShell(other)) {
                    addTimeFlowDistortion(other, level);
                }
                setSwarmTimeMult(other, mult);
            }
        }
        setSwarmTimeMult(ship, mult);
    }

    /**
     * 该舰载机自己的战术系统是不是原版「时流之壳」，并且正处于开启流程中。
     * <p>
     * 是的话 TemporalShellStats 已经在每帧给这架舰载机加抖动（setJitter +
     * setJitterUnder），本系统再叠一份会让抖动源数量翻倍，实测会严重卡顿。
     * 用 {@link ShipSystemAPI#isActive()}（充能/生效/充能结束任一段）而不是
     * isOn()，因为原版在 IN / ACTIVE / OUT 三个阶段都会继续加抖动。
     */
    private static boolean hasOwnTemporalShell(ShipAPI fighter) {
        if (fighter == null) {
            return false;
        }
        ShipSystemAPI system = fighter.getSystem();
        if (system == null) {
            return false;
        }
        if (!TEMPORAL_SHELL_ID.equals(system.getId())) {
            return false;
        }
        return system.isActive();
    }

    /**
     * 每帧最多触发一次「给所有己方舰载机补一圈失真」的节流判断。
     * 用舰船 customData 记录上次触发时间，避免脚本实例被多艘舰共享时状态串味。
     */
    private static boolean consumeDistortionTick(ShipAPI ship) {
        if (ship == null || ship.getCustomData() == null) {
            return false;
        }
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) {
            return false;
        }
        float now = engine.getTotalElapsedTime(false);
        Object raw = ship.getCustomData().get(DISTORTION_TICK_KEY);
        if (raw instanceof Float && now - ((Float) raw).floatValue() < DISTORTION_INTERVAL) {
            return false;
        }
        ship.setCustomData(DISTORTION_TICK_KEY, Float.valueOf(now));
        return true;
    }

    /**
     * 在舰载机位置放一个短寿命的环形失真（GraphicsLib 的 DistortionShader，一次 GPU 后处理），
     * 代替原来的 setJitter/setJitterUnder —— 每个失真只是一份统一参数 + 一个四边形，
     * 由 GPU 统一绘制，CPU 成本与舰载机数量基本无关。
     */
    private static void addTimeFlowDistortion(ShipAPI fighter, float level) {
        if (fighter == null) {
            return;
        }
        float clamped = clamp(level);
        if (clamped <= 0.01F) {
            return;
        }
        Vector2f location = new Vector2f(fighter.getLocation());
        Vector2f velocity = new Vector2f(fighter.getVelocity());
        RippleDistortion ripple = new RippleDistortion(location, velocity);
        float size = Math.max(24.0F, fighter.getCollisionRadius() * DISTORTION_SIZE_MULT);
        float intensity = DISTORTION_INTENSITY * clamped;
        ripple.setSize(size);
        ripple.setMaxSize(size * 1.15F);
        ripple.setIntensity(intensity);
        ripple.setMaxIntensity(intensity);
        ripple.setLifetime(DISTORTION_LIFETIME);
        ripple.fadeOutIntensity(DISTORTION_LIFETIME);
        ripple.fadeOutSize(DISTORTION_LIFETIME);
        DistortionShader.addDistortion(ripple);
    }

    private void clearTimeFlow(ShipAPI ship, CombatEngineAPI engine) {
        if (engine != null) {
            List<ShipAPI> ships = engine.getShips();
            if (ships != null) {
                for (ShipAPI other : ships) {
                    if (!isOwnFighterOf(other, ship)) {
                        continue;
                    }
                    other.getMutableStats().getTimeMult().unmodify(SOURCE_ID);
                    setSwarmTimeMult(other, 1.0F);
                }
            }
        }
        setSwarmTimeMult(ship, 1.0F);
    }

    private boolean isOwnFighterOf(ShipAPI other, ShipAPI ship) {
        if (other == null || other == ship || !other.isAlive() || other.isHulk()) {
            return false;
        }
        if (!other.isFighter()) {
            return false;
        }
        FighterWingAPI wing = other.getWing();
        return wing != null && wing.getSourceShip() == ship;
    }

    private void setSwarmTimeMult(ShipAPI target, float mult) {
        if (target == null) {
            return;
        }
        VIII_RoilingSwarmEffect swarm = VIII_RoilingSwarmEffect.getSwarmFor(target);
        if (swarm != null) {
            swarm.timeMult = mult;
        }
    }

    // ==================== 视觉与状态栏 ====================

    private void applyJitter(ShipAPI ship, float level) {
        if (level <= 0.01F) {
            return;
        }
        ship.setJitter(this, JITTER_COLOR, level, 3, 0.0F, 0.0F);
    }

    private void showStatus(ShipAPI ship, CombatEngineAPI engine, float level) {
        if (engine.getPlayerShip() != ship) {
            return;
        }
        float mult = timeMultFor(level);
        int rounded = Math.round(mult * 10.0F);
        String multText = (rounded / 10) + "." + (rounded % 10);
        String data = "舰载机与碎片集群时流 ×" + multText + "，额外舰载机已部署";
        engine.maintainStatusForPlayerShip(STATUS_KEY, STATUS_ICON, "分流发生器", data, false);
    }

    // ==================== 工具 ====================

    /** 与「时流之壳」相同的换算：1 + 2 × 等级² */
    public static float timeMultFor(float level) {
        float clamped = clamp(level);
        return 1.0F + (MAX_TIME_MULT - 1.0F) * clamped * clamped;
    }

    private static float clamp(float value) {
        if (value < 0.0F) {
            return 0.0F;
        }
        if (value > 1.0F) {
            return 1.0F;
        }
        return value;
    }

    private static ShipAPI getShip(MutableShipStatsAPI stats) {
        if (stats == null || stats.getEntity() == null) {
            return null;
        }
        if (!(stats.getEntity() instanceof ShipAPI)) {
            return null;
        }
        return (ShipAPI) stats.getEntity();
    }
}
