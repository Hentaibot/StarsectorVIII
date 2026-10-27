package data.scripts.shipsystems;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;

/**
 * 波动推进（VIII_TravelDriveYamato）结束后的"余波"增益：
 * 全武器伤害 +25%（弹道 / 能量 / 导弹）、全武器射速 +25%，持续
 * {@link VIII_TravelDriveYamatoStats#POST_BUFF_DURATION} 秒。
 * <p>
 * 【为什么用监听器而不是直接写在 unapply 里】
 * 系统脚本的 apply / unapply 只在开关系统的那一瞬间被调用，没有"每帧推进"的入口，
 * 到期后需要有人撤销修正、并且每帧重写以抵抗 stats 重建（例如换装、舰长变动）。
 * AdvanceableListener 每帧都会被引擎调用，正好满足这两个需求。
 * <p>
 * 【开关与叠加】
 * 到期时间由 {@link VIII_TravelDriveYamatoStats#startPostBuff} 写进舰船自定义数据；
 * 玩家在增益期间重新开启系统时，系统脚本会把到期时间 key 删掉，
 * 本监听器下一帧看到 key 不存在就自动撤销加成，所以两段效果不会叠加。
 */
public class VIII_YamatoPostBuffListener implements AdvanceableListener {

    /** 到期时间（秒，相对 {@code engine.getTotalElapsedTime(false)}），由系统脚本写入 */
    public static final String UNTIL_KEY = "VIII_yamato_post_buff_until";

    /** stats 修正来源标识，用于精确撤销 */
    public static final String SOURCE_ID = "VIII_TravelDriveYamato_post";

    private static final String STATUS_KEY = "VIII_yamato_post_buff_status";
    private static final String STATUS_ICON = "graphics/icons/hullsys/combat_burn.png";

    private final ShipAPI ship;

    /** 当前是否已经写过修正 */
    private boolean applied = false;

    public VIII_YamatoPostBuffListener(ShipAPI ship) {
        this.ship = ship;
    }

    @Override
    public void advance(float amount) {
        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            clear();
            return;
        }
        if (Global.getCombatEngine() == null) {
            clear();
            return;
        }
        Object stored = ship.getCustomData().get(UNTIL_KEY);
        if (!(stored instanceof Float)) {
            clear();
            return;
        }
        float now = Global.getCombatEngine().getTotalElapsedTime(false);
        float until = ((Float) stored).floatValue();
        if (now >= until) {
            clear();
            ship.getCustomData().remove(UNTIL_KEY);
            return;
        }
        apply();
        showStatus(until - now);
    }

    /**
     * 每帧重写一遍加成：同一 source 重复 modifyMult 是覆盖而不是叠加，
     * 这样即使 stats 对象被重建也能自动补回。
     */
    private void apply() {
        MutableShipStatsAPI stats = ship.getMutableStats();
        if (stats == null) {
            return;
        }
        float damageMult = 1.0F + VIII_TravelDriveYamatoStats.POST_DAMAGE_BONUS;
        float rofMult = 1.0F + VIII_TravelDriveYamatoStats.POST_ROF_BONUS;
        stats.getBallisticWeaponDamageMult().modifyMult(SOURCE_ID, damageMult);
        stats.getEnergyWeaponDamageMult().modifyMult(SOURCE_ID, damageMult);
        stats.getMissileWeaponDamageMult().modifyMult(SOURCE_ID, damageMult);
        stats.getBallisticRoFMult().modifyMult(SOURCE_ID, rofMult);
        stats.getEnergyRoFMult().modifyMult(SOURCE_ID, rofMult);
        stats.getMissileRoFMult().modifyMult(SOURCE_ID, rofMult);
        applied = true;
    }

    /** 撤销全部加成 */
    private void clear() {
        if (!applied) {
            return;
        }
        MutableShipStatsAPI stats = ship == null ? null : ship.getMutableStats();
        if (stats != null) {
            stats.getBallisticWeaponDamageMult().unmodify(SOURCE_ID);
            stats.getEnergyWeaponDamageMult().unmodify(SOURCE_ID);
            stats.getMissileWeaponDamageMult().unmodify(SOURCE_ID);
            stats.getBallisticRoFMult().unmodify(SOURCE_ID);
            stats.getEnergyRoFMult().unmodify(SOURCE_ID);
            stats.getMissileRoFMult().unmodify(SOURCE_ID);
        }
        applied = false;
    }

    /** 玩家舰在左下角状态栏显示剩余时间 */
    private void showStatus(float remaining) {
        if (Global.getCombatEngine() == null || ship != Global.getCombatEngine().getPlayerShip()) {
            return;
        }
        String text = "全武器伤害 +" + Math.round(VIII_TravelDriveYamatoStats.POST_DAMAGE_BONUS * 100F) + "%，"
                + "射速 +" + Math.round(VIII_TravelDriveYamatoStats.POST_ROF_BONUS * 100F) + "%，"
                + "剩余 " + (Math.round(remaining * 10F) / 10F) + " 秒";
        Global.getCombatEngine().maintainStatusForPlayerShip(STATUS_KEY, STATUS_ICON, "波动余波", text, false);
    }
}
