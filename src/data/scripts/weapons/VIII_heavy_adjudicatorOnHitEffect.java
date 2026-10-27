package data.scripts.weapons;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import org.lwjgl.util.vector.Vector2f;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 重型裁决者命中效果
 * 命中时生成四道电弧：四道不穿盾电弧从武器位置射向命中目标，四道穿盾电弧从命中点射向周围随机敌舰的武器/引擎
 * 内置冷却：同一武器每6秒最多触发一次电弧效果
 */
public class VIII_heavy_adjudicatorOnHitEffect implements OnHitEffectPlugin {

    // 预定义四种颜色
    private static final Color ARC_COLORS = new Color(52, 93, 255,255);

    private static final Random RAND = new Random();
    private static final float SEARCH_RANGE = 750f;          // 穿盾电弧搜索范围
    private static final float COOLDOWN_SECONDS = 6f;       // 冷却时间

    // 冷却记录存储键
    private static final String COOLDOWN_MAP_KEY = "VIII_heavy_adjudicator_cooldown";

    @Override
    public void onHit(DamagingProjectileAPI projectile, CombatEntityAPI target, Vector2f point,
                      boolean shieldHit, ApplyDamageResultAPI damageResult, CombatEngineAPI engine) {

        // 获取发射舰船和武器
        ShipAPI sourceShip = projectile.getSource();
        WeaponAPI weapon = projectile.getWeapon();
        if (sourceShip == null || weapon == null) return;

        // 冷却检查：若未冷却完毕，直接返回
        if (!isCooldownComplete(weapon, engine)) {
            return;
        }
        // 更新冷却时间为当前时间
        setCooldown(weapon, engine);

        // 获取武器基础伤害
        float baseDamage = projectile.getDamage().getDamage();
        float baseEmpDamage = projectile.getEmpAmount();
        float nonPierceDamage = baseDamage * 0.5f;
        float pierceDamage = baseDamage * 0.25f;
        float nonPierceEmpDamage = baseEmpDamage * 0.5f;
        float pierceEmpDamage = baseEmpDamage * 0.25f;

        Vector2f weaponLocation = weapon.getLocation();

        // ========== 1. 生成四道不穿盾电弧，从武器位置指向命中目标 ==========
        for (int i = 0; i < 4; i++) {
            EmpArcEntityAPI.EmpArcParams mainArcParams = new EmpArcEntityAPI.EmpArcParams();
            mainArcParams.segmentLengthMult = 8f; // 闪电分段长度倍率
            mainArcParams.zigZagReductionFactor = 0.15f; // 闪电曲折度
            mainArcParams.fadeOutDist = 50f; // 闪电淡出距离
            mainArcParams.minFadeOutMult = 10f; // 最小淡出倍率
            mainArcParams.flickerRateMult = 0.3f; // 闪电闪烁频率倍率
            // 闪电移动时长（按距离计算，确保速度固定）
            mainArcParams.movementDurOverride = Math.max(0.05f,0.1f);

            EmpArcEntityAPI mainArc = engine.spawnEmpArc(
                    sourceShip,
                    weaponLocation,
                    sourceShip,
                    target,
                    DamageType.ENERGY,
                    nonPierceDamage,
                    nonPierceEmpDamage,
                    10000f,
                    null,
                    20f,
                    ARC_COLORS,
                    Color.WHITE,
                    mainArcParams
            );
            mainArc.setRenderGlowAtStart(false); // 不渲染起点光晕
            mainArc.setSingleFlickerMode(true); // 单段闪烁模式
        }

        // ========== 2. 生成四道穿盾电弧，从命中点射向周围随机敌舰的武器/引擎 ==========
        List<ShipAPI> enemyShips = new ArrayList<>();
        for (ShipAPI ship : engine.getShips()) {
            if (ship.getOwner() != sourceShip.getOwner() && ship.isAlive() && !ship.isHulk()) {
                float dist = com.fs.starfarer.api.util.Misc.getDistance(point, ship.getLocation());
                if (dist <= SEARCH_RANGE) {
                    enemyShips.add(ship);
                }
            }
        }

        if (!enemyShips.isEmpty()) {
            for (int i = 0; i < 4; i++) {
                ShipAPI targetShip = enemyShips.get(RAND.nextInt(enemyShips.size()));
                Vector2f arcEndPoint = getRandomWeaponOrEngineLocation(targetShip);
                EmpArcEntityAPI.EmpArcParams mainArcParams = new EmpArcEntityAPI.EmpArcParams();
                mainArcParams.segmentLengthMult = 8f; // 闪电分段长度倍率
                mainArcParams.zigZagReductionFactor = 0.15f; // 闪电曲折度
                mainArcParams.fadeOutDist = 50f; // 闪电淡出距离
                mainArcParams.minFadeOutMult = 10f; // 最小淡出倍率
                mainArcParams.flickerRateMult = 0.3f; // 闪电闪烁频率倍率
                // 闪电移动时长（按距离计算，确保速度固定）
                mainArcParams.movementDurOverride = Math.max(0.05f,0.1f);

                EmpArcEntityAPI mainArc = engine.spawnEmpArcPierceShields(
                        sourceShip,
                        point,
                        null,
                        targetShip,
                        DamageType.ENERGY,
                        pierceDamage,
                        pierceEmpDamage,
                        10000f,
                        null,
                        20f,
                        ARC_COLORS,
                        Color.WHITE,
                        mainArcParams
                );
                mainArc.setRenderGlowAtStart(false); // 不渲染起点光晕
                mainArc.setSingleFlickerMode(true); // 单段闪烁模式
            }
        }
    }

    /**
     * 检查武器是否冷却完毕（距上次触发超过6秒）
     */
    private boolean isCooldownComplete(WeaponAPI weapon, CombatEngineAPI engine) {
        Map<WeaponAPI, Float> cooldownMap = getCooldownMap(engine);
        Float lastTriggerTime = cooldownMap.get(weapon);
        if (lastTriggerTime == null) {
            return true;   // 从未触发过，可以立即触发
        }
        float currentTime = engine.getTotalElapsedTime(false);
        return (currentTime - lastTriggerTime) >= COOLDOWN_SECONDS;
    }

    /**
     * 记录武器的本次触发时间
     */
    private void setCooldown(WeaponAPI weapon, CombatEngineAPI engine) {
        Map<WeaponAPI, Float> cooldownMap = getCooldownMap(engine);
        cooldownMap.put(weapon, engine.getTotalElapsedTime(false));
    }

    /**
     * 获取或创建冷却记录Map（存储在引擎自定义数据中）
     */
    @SuppressWarnings("unchecked")
    private Map<WeaponAPI, Float> getCooldownMap(CombatEngineAPI engine) {
        Map<WeaponAPI, Float> map = (Map<WeaponAPI, Float>) engine.getCustomData().get(COOLDOWN_MAP_KEY);
        if (map == null) {
            map = new HashMap<>();
            engine.getCustomData().put(COOLDOWN_MAP_KEY, map);
        }
        return map;
    }

    /**
     * 获取舰船上一个随机的武器或引擎的位置（世界坐标）
     */
    private Vector2f getRandomWeaponOrEngineLocation(ShipAPI ship) {
        List<Vector2f> points = new ArrayList<>();

        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (!weapon.isDecorative()) {
                points.add(weapon.getLocation());
            }
        }
        for (ShipEngineControllerAPI.ShipEngineAPI engine : ship.getEngineController().getShipEngines()) {
            points.add(engine.getLocation());
        }

        if (points.isEmpty()) {
            return ship.getLocation(); // 回退到舰船中心
        }
        return points.get(RAND.nextInt(points.size()));
    }
}