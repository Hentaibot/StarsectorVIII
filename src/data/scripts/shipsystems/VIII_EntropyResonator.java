package data.scripts.shipsystems;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.plugins.ShipSystemStatsScript;
import data.scripts.VIII_threat.VIII_SwarmLauncherEffect; // 新增导入

import java.awt.*;

/**
 * 熵谐振器系统
 * 原功能：提高母舰时间流速，附加抖动视觉效果，改变引擎颜色。
 * 新增功能：
 * - 时间流速倍率同步作用于自身所有舰载机，并赋予相同的抖动效果。
 * - 时间流速倍率同步作用于本舰发射的攻击群（VIII_attack_swarm_wing），不添加抖动。
 * - 自动识别内置 (BUILT_IN) 武器，使其射速提高 100%（全武器类型覆盖）。
 */
public class VIII_EntropyResonator extends BaseShipSystemScript {
    public static final Color JITTER_COLOR = new Color(90, 165, 255, 55);
    public static final Color JITTER_UNDER_COLOR = new Color(90, 165, 255, 155);

    public VIII_EntropyResonator() {
    }

    public void apply(MutableShipStatsAPI stats, String id, ShipSystemStatsScript.State state, float effectLevel) {
        ShipAPI ship;
        boolean player;
        if (stats.getEntity() instanceof ShipAPI) {
            ship = (ShipAPI) stats.getEntity();
            player = ship == Global.getCombatEngine().getPlayerShip();
            id = id + "_" + ship.getId();

            // ---------- 基础抖动和时间倍率（母舰） ----------
            float jitterLevel = effectLevel;
            float jitterRangeBonus = 0.0F;
            float maxRangeBonus = 10.0F;
            if (state == State.IN) {
                jitterLevel = effectLevel / (1.0F / ship.getSystem().getChargeUpDur());
                if (jitterLevel > 1.0F) {
                    jitterLevel = 1.0F;
                }
                jitterRangeBonus = jitterLevel * maxRangeBonus;
            } else if (state == State.ACTIVE) {
                jitterLevel = 1.0F;
                jitterRangeBonus = maxRangeBonus;
            } else if (state == State.OUT) {
                jitterRangeBonus = effectLevel * maxRangeBonus;
            }

            jitterLevel = (float) Math.sqrt(jitterLevel);
            effectLevel *= effectLevel;

            // 母舰抖动
            ship.setJitter(this, JITTER_COLOR, jitterLevel, 3, 0.0F, 0.0F + jitterRangeBonus);
            ship.setJitterUnder(this, JITTER_UNDER_COLOR, jitterLevel, 25, 0.0F, 7.0F + jitterRangeBonus);

            float shipTimeMult = 1.0F + 2.0F * effectLevel;
            stats.getTimeMult().modifyMult(id, shipTimeMult);
            if (player) {
                Global.getCombatEngine().getTimeMult().modifyMult(id, 1.0F / shipTimeMult);
            } else {
                Global.getCombatEngine().getTimeMult().unmodify(id);
            }

            // 引擎效果
            ship.getEngineController().fadeToOtherColor(this, JITTER_COLOR, new Color(0, 0, 0, 0), effectLevel, 0.5F);
            ship.getEngineController().extendFlame(this, -0.25F, -0.25F, -0.25F);

            // ---------- 舰载机同步（航母甲板上的舰载机） ----------
            for (FighterWingAPI wing : ship.getAllWings()) {
                for (ShipAPI fighter : wing.getWingMembers()) {
                    if (fighter != null && fighter.isAlive()) {
                        // 相同的抖动视觉效果
                        fighter.setJitter(this, JITTER_COLOR, jitterLevel, 3, 0.0F, 0.0F + jitterRangeBonus);
                        fighter.setJitterUnder(this, JITTER_UNDER_COLOR, jitterLevel, 25, 0.0F, 7.0F + jitterRangeBonus);
                        // 相同的时间流速倍率
                        fighter.getMutableStats().getTimeMult().modifyMult(id + "_fighter", shipTimeMult);
                    }
                }
            }

            // ---------- 新增：攻击群同步（不添加抖动） ----------
            String attackSwarmId = id + "_attack_swarm";
            for (ShipAPI other : Global.getCombatEngine().getShips()) {
                // 只处理战斗机且存活非残骸
                if (!other.isFighter() || !other.isAlive() || other.isHulk()) continue;

                // 必须属于当前母舰
                Object owner = other.getCustomData().get(VIII_SwarmLauncherEffect.ATTACK_OWNER_KEY);
                if (owner != ship) continue;

                // 必须是攻击群标记
                Object isAttack = other.getCustomData().get(VIII_SwarmLauncherEffect.ATTACK_TAG_KEY);
                if (!(isAttack instanceof Boolean && (Boolean) isAttack)) continue;

                // 应用相同的时间流速倍率（无抖动）
                other.getMutableStats().getTimeMult().modifyMult(attackSwarmId, shipTimeMult);
            }

            // ---------- 内置武器射速提高 100% ----------
            String rofId = id + "_builtin_rof";
            stats.getBallisticRoFMult().modifyMult(rofId, 2.0F);
            stats.getEnergyRoFMult().modifyMult(rofId, 2.0F);
            stats.getMissileRoFMult().modifyMult(rofId, 2.0F);
        }
    }

    public void unapply(MutableShipStatsAPI stats, String id) {
        ShipAPI ship;
        if (stats.getEntity() instanceof ShipAPI) {
            ship = (ShipAPI) stats.getEntity();
            id = id + "_" + ship.getId();

            // 恢复全局时间倍率
            Global.getCombatEngine().getTimeMult().unmodify(id);
            stats.getTimeMult().unmodify(id);

            // 移除舰载机的时间倍率修改
            for (FighterWingAPI wing : ship.getAllWings()) {
                for (ShipAPI fighter : wing.getWingMembers()) {
                    if (fighter != null && fighter.isAlive()) {
                        fighter.getMutableStats().getTimeMult().unmodify(id + "_fighter");
                    }
                }
            }

            // 移除攻击群的时间倍率修改
            String attackSwarmId = id + "_attack_swarm";
            for (ShipAPI other : Global.getCombatEngine().getShips()) {
                if (!other.isFighter() || !other.isAlive() || other.isHulk()) continue;

                Object owner = other.getCustomData().get(VIII_SwarmLauncherEffect.ATTACK_OWNER_KEY);
                if (owner != ship) continue;

                Object isAttack = other.getCustomData().get(VIII_SwarmLauncherEffect.ATTACK_TAG_KEY);
                if (!(isAttack instanceof Boolean && (Boolean) isAttack)) continue;

                other.getMutableStats().getTimeMult().unmodify(attackSwarmId);
            }

            // 移除内置武器射速修改
            String rofId = id + "_builtin_rof";
            stats.getBallisticRoFMult().unmodify(rofId);
            stats.getEnergyRoFMult().unmodify(rofId);
            stats.getMissileRoFMult().unmodify(rofId);
        }
    }

    public ShipSystemStatsScript.StatusData getStatusData(int index, ShipSystemStatsScript.State state, float effectLevel) {
        if (index == 0) {
            return new ShipSystemStatsScript.StatusData("时间流速改变", false);
        } else if (index == 1) {
            return new ShipSystemStatsScript.StatusData("武器射速 +100%", false);
        }
        return null;
    }
}