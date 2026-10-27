package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.combat.CombatUtils;
import org.lwjgl.util.vector.Vector2f;
import org.magiclib.util.MagicRender;

import java.awt.Color;
import java.util.List;

public class VTemparcEffect implements OnHitEffectPlugin {

    // 电弧效果参数
    private static final float ARC_RANGE = 250f;
    private static final float ARC_DAMAGE_MULTIPLIER = 0.5f; // 初始伤害的一半
    private static final float ARC_EMP_MULTIPLIER = 0.5f;    // 初始EMP伤害的一半
    private static final Color ARC_FRINGE_COLOR = new Color(100, 150, 255, 255); // 电弧边缘颜色
    private static final Color ARC_CORE_COLOR = new Color(200, 220, 255, 255);   // 电弧核心颜色
    private static final float ARC_THICKNESS = 3f;           // 电弧厚度

    @Override
    public void onHit(DamagingProjectileAPI projectile, CombatEntityAPI target,
                      Vector2f point, boolean shieldHit, ApplyDamageResultAPI damageResult,
                      CombatEngineAPI engine) {

        // 检查关键对象是否有效
        if (projectile == null || projectile.getWeapon() == null ||
                projectile.getSource() == null || !projectile.getSource().isAlive()) {
            return;
        }

        WeaponAPI weapon = projectile.getWeapon();
        ShipAPI sourceShip = projectile.getSource();

        // 触发电弧效果
        triggerArcEffect(projectile, point, sourceShip, weapon, engine);
    }

    /**
     * 触发电弧效果，对范围内所有敌方目标释放EMP电弧
     */
    private void triggerArcEffect(DamagingProjectileAPI triggeringProj, Vector2f impactLocation,
                                  ShipAPI sourceShip, WeaponAPI weapon, CombatEngineAPI engine) {

        // 验证关键对象
        if (sourceShip == null || !sourceShip.isAlive()) {
            return;
        }

        // 创建电弧视觉和音效
        createArcVisualEffects(impactLocation, engine);

        // 获取武器的基础伤害和EMP伤害
        float baseDamage = weapon.getDamage().getDamage();
        float baseEmpDamage = weapon.getDamage().getFluxComponent();

        // 计算电弧的伤害值，确保至少有一定伤害
        float arcDamage = Math.max(baseDamage * ARC_DAMAGE_MULTIPLIER, 50f);
        float arcEmpDamage = Math.max(baseEmpDamage * ARC_EMP_MULTIPLIER, 50f);

        // 对范围内的所有敌方舰船生成EMP电弧
        List<ShipAPI> shipsInRange = CombatUtils.getShipsWithinRange(impactLocation, ARC_RANGE);
        for (ShipAPI target : shipsInRange) {
            if (isValidEnemy(target, sourceShip)) {
                spawnEmpArcToTarget(sourceShip, impactLocation, target,
                        arcDamage, arcEmpDamage, weapon.getDamageType(), engine);
            }
        }

        // 对范围内的所有敌方导弹生成EMP电弧
        List<MissileAPI> missilesInRange = CombatUtils.getMissilesWithinRange(impactLocation, ARC_RANGE);
        for (MissileAPI missile : missilesInRange) {
            if (missile.getOwner() != sourceShip.getOwner()) {
                spawnEmpArcToTarget(sourceShip, impactLocation, missile,
                        arcDamage, arcEmpDamage, weapon.getDamageType(), engine);
            }
        }
    }

    /**
     * 检查目标是否为有效敌人
     */
    private boolean isValidEnemy(ShipAPI target, ShipAPI sourceShip) {
        return target != sourceShip &&
                target.getOwner() != sourceShip.getOwner() &&
                target.isAlive() &&
                !target.isPhased();
    }

    /**
     * 生成EMP电弧连接到目标
     */
    private void spawnEmpArcToTarget(ShipAPI sourceShip, Vector2f sourceLocation,
                                     CombatEntityAPI target, float damage, float empDamage,
                                     DamageType damageType, CombatEngineAPI engine) {

        // 确保目标有效
        if (target == null || target.isExpired()) {
            return;
        }

        // 计算到目标的距离
        float distance = MathUtils.getDistance(sourceLocation, target.getLocation());
        if (distance > ARC_RANGE) {
            return;
        }

        try {
            // 使用EMP电弧接口生成电弧
            engine.spawnEmpArcPierceShields(
                    sourceShip,           // 伤害来源舰船
                    sourceLocation,       // 电弧起始位置
                    sourceShip,           // 起始位置锚点实体
                    target,              // 目标实体
                    damageType,          // 伤害类型
                    damage,              // 伤害值
                    empDamage,           // EMP伤害值
                    ARC_RANGE * 1.5f,    // 最大射程（稍大于检测范围）
                    "tachyon_lance_emp_impact", // 命中音效
                    ARC_THICKNESS,       // 电弧厚度
                    ARC_FRINGE_COLOR,    // 电弧边缘颜色
                    ARC_CORE_COLOR       // 电弧核心颜色
            );

            // 在目标位置添加额外的视觉效果
            createTargetHitEffect(target.getLocation(), engine);
        } catch (Exception e) {
            // 忽略电弧生成错误，继续执行
        }
    }

    /**
     * 创建主要的电弧视觉效果
     */
    private void createArcVisualEffects(Vector2f location, CombatEngineAPI engine) {
        try {
            // 中心爆炸效果
            engine.spawnExplosion(location, new Vector2f(0, 0), ARC_CORE_COLOR, 80f, 0.6f);

            // 检查MagicRender是否可用
            if (Global.getSettings().getModManager().isModEnabled("MagicLib")) {
                // 电弧能量核心
                SpriteAPI arcCore = Global.getSettings().getSprite("misc", "fx_particles1");
                if (arcCore != null) {
                    MagicRender.battlespace(
                            arcCore, location, new Vector2f(0, 0),
                            new Vector2f(60f, 60f), new Vector2f(30f, 30f),
                            0f, 360f, new Color(150, 200, 255, 200),
                            true, 0.1f, 0.3f, 0.5f
                    );
                }

                // 电磁脉冲环状效果
                createEmpRingEffect(location, engine);
            }

            // 播放电弧音效
            Global.getSoundPlayer().playSound("system_emp_emitter_impact", 1f, 0.8f, location, new Vector2f(0, 0));
        } catch (Exception e) {
            // 忽略视觉效果错误
        }
    }

    /**
     * 创建目标命中效果
     */
    private void createTargetHitEffect(Vector2f location, CombatEngineAPI engine) {
        try {
            // 在目标位置添加小范围EMP效果
            if (Global.getSettings().getModManager().isModEnabled("MagicLib")) {
                SpriteAPI empSpark = Global.getSettings().getSprite("misc", "fx_radial");
                if (empSpark != null) {
                    MagicRender.battlespace(
                            empSpark, location, new Vector2f(0, 0),
                            new Vector2f(25f, 25f), new Vector2f(15f, 15f),
                            0f, 180f, new Color(100, 150, 255, 150),
                            false, 0f, 0.1f, 0.2f
                    );
                }
            }

            // 添加命中粒子
            engine.addHitParticle(location, new Vector2f(0, 0), 15f, 0.8f, 0.15f, ARC_CORE_COLOR);
        } catch (Exception e) {
            // 忽略效果错误
        }
    }

    /**
     * 创建EMP环状效果
     */
    private void createEmpRingEffect(Vector2f location, CombatEngineAPI engine) {
        try {
            // 外层EMP环
            SpriteAPI empRing = Global.getSettings().getSprite("misc", "fx_radial");
            if (empRing != null) {
                MagicRender.battlespace(
                        empRing, location, new Vector2f(0, 0),
                        new Vector2f(120f, 120f), new Vector2f(80f, 80f),
                        0f, 360f, new Color(100, 150, 255, 100),
                        false, 0f, 0.2f, 0.4f
                );
            }

            // 内层能量环
            SpriteAPI energyRing = Global.getSettings().getSprite("misc", "fx_particles1");
            if (energyRing != null) {
                MagicRender.battlespace(
                        energyRing, location, new Vector2f(0, 0),
                        new Vector2f(80f, 80f), new Vector2f(40f, 40f),
                        0f, 360f, new Color(150, 200, 255, 150),
                        false, 0.1f, 0.3f, 0.5f
                );
            }

            // 随机EMP粒子
            for (int i = 0; i < 8; i++) {
                float angle = (float) (Math.random() * 360f);
                float distance = (float) (Math.random() * 100f);
                Vector2f particleLoc = MathUtils.getPointOnCircumference(location, distance, angle);

                Vector2f velocity = MathUtils.getPointOnCircumference(new Vector2f(0, 0),
                        (float) (Math.random() * 50f + 50f), angle);

                engine.addHitParticle(particleLoc, velocity,
                        12f, 0.8f, 0.4f, new Color(150, 200, 255, 200));
            }
        } catch (Exception e) {
            // 忽略环状效果错误
        }
    }

}