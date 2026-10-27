package data.scripts.shipsystems;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import data.scripts.VIII_threat.VIII_SwarmLauncherEffect;

/**
 * VIII系列“纺织填充”舰船系统。
 *
 * <p>系统激活时：
 * <ul>
 *   <li>所有武器射速提升，幅能产生降低，弹药再生加快，碎片蜂群重生加速。</li>
 *   <li>为本舰发射的攻击群成员增加50%武器伤害与50%最大航速。</li>
 * </ul>
 * </p>
 */
public class VIII_Textilefiller extends BaseShipSystemScript {

    public static final float ROF_BONUS = 1.2f;
    public static final float FLUX_REDUCTION = 65f;

    @Override
    public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
        ShipAPI ship = null;
        if (stats.getEntity() instanceof ShipAPI) {
            ship = (ShipAPI) stats.getEntity();
        }

        float mult = 1f + ROF_BONUS * effectLevel;
        stats.getBallisticRoFMult().modifyMult(id, mult);
        stats.getEnergyRoFMult().modifyMult(id, mult);
        stats.getMissileRoFMult().modifyMult(id, mult);

        stats.getBallisticWeaponFluxCostMod().modifyMult(id, 1f - (FLUX_REDUCTION * 0.01f));
        stats.getEnergyWeaponFluxCostMod().modifyMult(id, 1f - (FLUX_REDUCTION * 0.01f));
        stats.getMissileWeaponFluxCostMod().modifyMult(id, 1f - (FLUX_REDUCTION * 0.01f));

        stats.getBallisticAmmoRegenMult().modifyMult(id, mult);
        stats.getEnergyAmmoRegenMult().modifyMult(id, mult);
        stats.getMissileAmmoRegenMult().modifyMult(id, mult);

        stats.getDynamic().getMod(Stats.FRAGMENT_SWARM_RESPAWN_RATE_MULT).modifyMult(id, 2f);

        // 新增：处理攻击群增益
        if (ship != null) {
            applySwarmBuffs(ship, id, effectLevel > 0f);
        }
    }

    @Override
    public void unapply(MutableShipStatsAPI stats, String id) {
        stats.getBallisticRoFMult().unmodify(id);
        stats.getEnergyRoFMult().unmodify(id);
        stats.getMissileRoFMult().unmodify(id);
        stats.getBallisticWeaponFluxCostMod().unmodify(id);
        stats.getEnergyWeaponFluxCostMod().unmodify(id);
        stats.getMissileWeaponFluxCostMod().unmodify(id);
        stats.getBallisticAmmoRegenMult().unmodify(id);
        stats.getEnergyAmmoRegenMult().unmodify(id);
        stats.getMissileAmmoRegenMult().unmodify(id);
        stats.getDynamic().getMod(Stats.FRAGMENT_SWARM_RESPAWN_RATE_MULT).unmodify(id);

        // 清理攻击群增益
        if (stats.getEntity() instanceof ShipAPI) {
            applySwarmBuffs((ShipAPI) stats.getEntity(), id, false);
        }
    }

    /**
     * 遍历战场上属于本舰的攻击群成员，并为其施加或移除 +50% 武器伤害和 +50% 最大航速的增益。
     *
     * @param ship   母舰
     * @param id     修正标识
     * @param active true=施加增益，false=移除增益
     */
    private void applySwarmBuffs(ShipAPI ship, String id, boolean active) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) return;

        for (ShipAPI curr : engine.getShips()) {
            // 只处理战斗机且存活非残骸
            if (!curr.isFighter() || !curr.isAlive() || curr.isHulk()) continue;

            // 必须属于当前母舰
            Object owner = curr.getCustomData().get(VIII_SwarmLauncherEffect.ATTACK_OWNER_KEY);
            if (owner != ship) continue;

            // 必须是攻击群标记
            Object isAttack = curr.getCustomData().get(VIII_SwarmLauncherEffect.ATTACK_TAG_KEY);
            if (!(isAttack instanceof Boolean && (Boolean) isAttack)) continue;

            MutableShipStatsAPI swarmStats = curr.getMutableStats();
            if (active) {
                // 武器伤害 +50%（所有类型）
                swarmStats.getBallisticWeaponDamageMult().modifyMult(id, 1.5f);
                swarmStats.getEnergyWeaponDamageMult().modifyMult(id, 1.5f);
                swarmStats.getMissileWeaponDamageMult().modifyMult(id, 1.5f);
                // 最大航速 +50%
                swarmStats.getMaxSpeed().modifyMult(id, 1.5f);
            } else {
                swarmStats.getBallisticWeaponDamageMult().unmodify(id);
                swarmStats.getEnergyWeaponDamageMult().unmodify(id);
                swarmStats.getMissileWeaponDamageMult().unmodify(id);
                swarmStats.getMaxSpeed().unmodify(id);
            }
        }
    }

    @Override
    public StatusData getStatusData(int index, State state, float effectLevel) {
        float mult = 1f + ROF_BONUS * effectLevel;
        float bonusPercent = (int) ((mult - 1f) * 100f);

        return switch (index) {
            case 0 -> new StatusData("所有武器射速 +" + bonusPercent + "%", false);
            case 1 -> new StatusData("所有武器幅能产生 -" + (int) FLUX_REDUCTION + "%", false);
            case 2 -> new StatusData("纳米碎片制造速度 +100%", false);
            case 3 ->
                // 新增一行显示攻击群增益（可选）
                    new StatusData("攻击群伤害/航速 +50%", false);
            default -> null;
        };
    }
}