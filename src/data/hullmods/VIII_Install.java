package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

/**
 * VIII_Install 插件
 * 功能：
 * 1. 舰船创建时若未安装 VIII_battle / VIII_hunt，自动安装 VIII_battle。
 * 2. 随时监听插件状态，保证始终安装二者之一：
 *    - VIII_battle 被移除 → 自动安装 VIII_hunt
 *    - VIII_hunt 被移除 → 自动安装 VIII_battle
 * 3. 通过变体标签记录“已移除的是哪个”，不同舰船独立运行，不会相互干扰。
 */
public class VIII_Install extends BaseHullMod {

    // 用于标记上一次存在的插件（存储在每个舰船自己的变体标签中）
    private static final String TAG_LAST_BATTLE = "viii_install_last_battle";
    private static final String TAG_LAST_HUNT   = "viii_install_last_hunt";

    // ========== 舰船创建前的初始化 ==========
    @Override
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
        ShipVariantAPI variant = stats.getVariant();
        syncModsAndTags(variant, true); // 首次初始化，允许默认安装 VIII_battle
    }

    // ========== 战役层每帧逻辑 ==========
    @Override
    public void advanceInCampaign(FleetMemberAPI member, float amount) {
        ShipVariantAPI variant = member.getVariant();
        if (variant != null) {
            syncModsAndTags(variant, false);
        }
    }

    // ========== 战斗层每帧逻辑 ==========
    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        ShipVariantAPI variant = ship.getVariant();
        if (variant != null) {
            syncModsAndTags(variant, false);
        }
    }

    // ========== 核心同步逻辑 ==========
    private void syncModsAndTags(ShipVariantAPI variant, boolean isInitial) {
        boolean hasBattle = variant.hasHullMod("VIII_battle");
        boolean hasHunt   = variant.hasHullMod("VIII_hunt");

        // 情况1: 两种都没有 → 根据标签决定安装哪一个
        if (!hasBattle && !hasHunt) {
            if (variant.hasTag(TAG_LAST_BATTLE)) {
                // 之前有 battle，说明 battle 被移除了 → 安装 hunt
                variant.addMod("VIII_hunt");
                // 更新标签：现在存在的是 hunt
                variant.removeTag(TAG_LAST_BATTLE);
                variant.addTag(TAG_LAST_HUNT);
            } else if (variant.hasTag(TAG_LAST_HUNT)) {
                // 之前有 hunt，说明 hunt 被移除了 → 安装 battle
                variant.addMod("VIII_battle");
                variant.removeTag(TAG_LAST_HUNT);
                variant.addTag(TAG_LAST_BATTLE);
            } else {
                // 完全没有标记，可能是新舰船 → 默认安装 VIII_battle
                variant.addMod("VIII_battle");
                variant.addTag(TAG_LAST_BATTLE);
            }
            return;
        }

        // 情况2: 只有 VIII_battle 存在
        if (hasBattle && !hasHunt) {
            // 确保标签记录为 battle，并清除 hunt 标签
            if (!variant.hasTag(TAG_LAST_BATTLE)) {
                variant.addTag(TAG_LAST_BATTLE);
            }
            variant.removeTag(TAG_LAST_HUNT);
            return;
        }

        // 情况3: 只有 VIII_hunt 存在
        if (!hasBattle) {
            if (!variant.hasTag(TAG_LAST_HUNT)) {
                variant.addTag(TAG_LAST_HUNT);
            }
            variant.removeTag(TAG_LAST_BATTLE);
            return;
        }

        // 情况4: 两个都存在（异常状态，禁止同时出现）
        // 保留一个，根据标签决定保留哪个（或直接移除 VIII_hunt）
        if (variant.hasTag(TAG_LAST_BATTLE) && !variant.hasTag(TAG_LAST_HUNT)) {
            // 之前是 battle，保留 battle，移除 hunt
            variant.removeMod("VIII_hunt");
        } else {
            // 其他情况统一保留 hunt，移除 battle
            variant.removeMod("VIII_battle");
            // 修正标签
            variant.removeTag(TAG_LAST_BATTLE);
            if (!variant.hasTag(TAG_LAST_HUNT)) {
                variant.addTag(TAG_LAST_HUNT);
            }
        }
    }
}