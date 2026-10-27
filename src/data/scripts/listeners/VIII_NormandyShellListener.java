package data.scripts.listeners;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;
import org.lwjgl.util.vector.Vector2f;

/**
 * 诺曼底（VIII_normandy）的时流应激：
 * 受到伤害时，若战术系统"时流之壳"没有开启，则立刻获得 30 倍舰船时间流速，
 * 持续 4 秒，冷却 15 秒（从触发瞬间开始计时）。
 * <p>
 * 【与战术系统的关系】
 * 系统本身沿用原版 temporalshell 的脚本（{@code com.fs.starfarer.api.impl.combat.TemporalShellStats}），
 * 所以正常开启时的手感、抖动、音效、引擎压暗、时间流速（最高 3 倍）与 AI 全部与原版一致；
 * 本监听器只额外提供"挨打时偷一段 30 倍时间"的被动，且系统一旦进入
 * 充能/生效/充能结束（{@link ShipSystemAPI#isActive()}）就立刻取消这段额外加速，
 * 避免和系统的 3 倍叠乘。
 * <p>
 * 【玩家舰的时间补偿】
 * 原版时流之壳在玩家亲自驾驶时会把战场时间反过来拖慢（{@code engine.getTimeMult()}
 * 乘 1/3），这样看起来是"敌人变慢"而不是"自己飞出去"。这里沿用同一套做法：
 * 玩家的舰船时间 ×30、战场时间 ×1/30，净效果是自己的操作保持正常速度、
 * 周围一切几乎静止。
 */
public class VIII_NormandyShellListener implements DamageTakenModifier, AdvanceableListener {

    /** stats 与战场时间修正的来源标识 */
    public static final String SOURCE_ID = "VIII_normandy_shell";

    /** 这套被动只对装了该系统的诺曼底生效 */
    public static final String SYSTEM_ID = "VIII_normandy_temporalshell";

    /** 受击触发时的舰船时间流速倍数 */
    public static final float TIME_MULT = 30.0F;

    /** 持续时间（秒） */
    public static final float SURGE_DURATION = 4.0F;

    /** 冷却时间（秒，从触发瞬间开始计时） */
    public static final float SURGE_COOLDOWN = 15.0F;

    private static final Color JITTER_COLOR = new Color(120, 190, 255, 60);
    private static final String STATUS_KEY = "VIII_normandy_shell_status";
    private static final String STATUS_ICON = "graphics/icons/hullsys/temporal_shell.png";

    private final ShipAPI ship;

    /** 剩余应激时间（秒），> 0 表示 30 倍时间生效中 */
    private float surgeRemaining = 0.0F;

    /** 剩余冷却时间（秒） */
    private float cooldownRemaining = 0.0F;

    /** 当前是否已经写过时间修正，避免无谓的 unmodify */
    private boolean surgeApplied = false;

    public VIII_NormandyShellListener(ShipAPI ship) {
        this.ship = ship;
    }

    /** 受到伤害时判定是否触发；不修改伤害，返回 null */
    @Override
    public String modifyDamageTaken(Object param, CombatEntityAPI target, DamageAPI damage,
                                    Vector2f point, boolean shieldHit) {
        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            return null;
        }
        if (damage == null) {
            return null;
        }
        if (damage.getBaseDamage() * damage.getMultiplier() <= 0.0F) {
            return null;
        }
        // 系统开启（充能 / 生效 / 充能结束）期间不触发
        if (isSystemActive()) {
            return null;
        }
        if (cooldownRemaining > 0.0F) {
            return null;
        }
        if (isFriendlyFire(param)) {
            return null;
        }
        triggerSurge();
        return null;
    }

    @Override
    public void advance(float amount) {
        if (ship == null) {
            return;
        }
        if (!ship.isAlive() || ship.isHulk()) {
            cancelSurge();
            return;
        }

        if (cooldownRemaining > 0.0F) {
            cooldownRemaining = Math.max(0.0F, cooldownRemaining - amount);
        }

        if (surgeRemaining <= 0.0F) {
            return;
        }

        // 系统一开就把额外时间让给系统，避免两段倍率相乘
        if (isSystemActive()) {
            cancelSurge();
            return;
        }

        surgeRemaining -= amount;
        if (surgeRemaining <= 0.0F) {
            cancelSurge();
            return;
        }

        applySurge();
        ship.setJitter(this, JITTER_COLOR, 0.6F, 6, 0.0F, 14.0F);
        showStatus();
    }

    /** 战术系统是否处于开启流程（充能 / 生效 / 充能结束） */
    private boolean isSystemActive() {
        ShipSystemAPI system = ship == null ? null : ship.getSystem();
        return system != null && system.isActive();
    }

    private void triggerSurge() {
        surgeRemaining = SURGE_DURATION;
        cooldownRemaining = SURGE_COOLDOWN;
        applySurge();
        showStatus();
    }

    /** 同一 source 重复写是覆盖，不会叠加；每帧重写可以在 stats 重建后自动补回 */
    private void applySurge() {
        MutableShipStatsAPI stats = ship.getMutableStats();
        if (stats != null) {
            stats.getTimeMult().modifyMult(SOURCE_ID, TIME_MULT);
        }
        if (Global.getCombatEngine() != null && ship == Global.getCombatEngine().getPlayerShip()) {
            Global.getCombatEngine().getTimeMult().modifyMult(SOURCE_ID, 1.0F / TIME_MULT);
        }
        surgeApplied = true;
    }

    /** 撤销舰船时间与战场时间的修正 */
    private void cancelSurge() {
        surgeRemaining = 0.0F;
        if (!surgeApplied) {
            return;
        }
        MutableShipStatsAPI stats = ship == null ? null : ship.getMutableStats();
        if (stats != null) {
            stats.getTimeMult().unmodify(SOURCE_ID);
        }
        if (Global.getCombatEngine() != null) {
            Global.getCombatEngine().getTimeMult().unmodify(SOURCE_ID);
        }
        surgeApplied = false;
    }

    /** 同阵营弹丸打到自己不算"受到攻击" */
    private boolean isFriendlyFire(Object param) {
        if (!(param instanceof DamagingProjectileAPI)) {
            return false;
        }
        DamagingProjectileAPI projectile = (DamagingProjectileAPI) param;
        return projectile.getOwner() == ship.getOwner();
    }

    private void showStatus() {
        if (Global.getCombatEngine() == null || ship != Global.getCombatEngine().getPlayerShip()) {
            return;
        }
        String remaining = "" + (Math.round(surgeRemaining * 10.0F) / 10.0F);
        Global.getCombatEngine().maintainStatusForPlayerShip(STATUS_KEY, STATUS_ICON, "时流应激",
                "舰船时间流速 ×" + (int) TIME_MULT + "，剩余 " + remaining + " 秒", false);
    }
}
