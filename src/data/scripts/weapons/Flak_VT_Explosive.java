package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.loading.DamagingExplosionSpec;
import org.lazywizard.lazylib.combat.CombatUtils;
import org.lwjgl.util.vector.Vector2f;
import org.magiclib.util.MagicRender;
import java.awt.Color;
import java.util.ArrayList;

public class Flak_VT_Explosive implements EveryFrameWeaponEffectPlugin {
    private ArrayList<DamagingProjectileAPI> activeProjectiles = new ArrayList<>();
    private ArrayList<DamagingProjectileAPI> finishedProjectiles = new ArrayList<>();
    private DamagingExplosionSpec explosionSpec = createExplosionSpec();

    private DamagingExplosionSpec createExplosionSpec() {
        return new DamagingExplosionSpec(0.05f, 0.1f, 0.1f, 0.0f, 0.0f,
                CollisionClass.PROJECTILE_FF, CollisionClass.PROJECTILE_FIGHTER,
                0.1f, 0.1f, 0.05f, 1,
                new Color(215, 215, 200, 170), new Color(215, 150, 75, 55));
    }

    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 收集新发射的炮弹
        if (weapon.getChargeLevel() == 1.0f && weapon.isFiring()) {
            for (DamagingProjectileAPI projectile : CombatUtils.getProjectilesWithinRange(weapon.getLocation(), 100f)) {
                if (projectile.getWeapon() == weapon && !activeProjectiles.contains(projectile)) {
                    activeProjectiles.add(projectile);
                }
            }
        }

            // 命中触发
            for (DamagingProjectileAPI proj : activeProjectiles) {
                if (proj.didDamage()) {
                    triggerExplosion(proj, engine, weapon);
                }
            }


        // 清理已处理的炮弹
        activeProjectiles.removeAll(finishedProjectiles);
        finishedProjectiles.clear();
    }

    private void triggerExplosion(DamagingProjectileAPI proj, CombatEngineAPI engine, WeaponAPI weapon) {
        Vector2f location = proj.getLocation();
        Vector2f zeroVelocity = new Vector2f(0f, 0f);
        float randomAngle = (float) (Math.random() * 360f);

        // 根据武器尺寸设置爆炸参数
        ExplosionConfig config = getExplosionConfig(weapon.getSize());

        // 渲染爆炸效果
        renderExplosionEffects(location, zeroVelocity, randomAngle, config);

        // 处理伤害爆炸
        handleDamageExplosion(proj, weapon, location, engine, config);

        // 标记炮弹为已处理
        finishedProjectiles.add(proj);
    }

    private ExplosionConfig getExplosionConfig(WeaponAPI.WeaponSize size) {
        ExplosionConfig config = new ExplosionConfig();

        switch (size) {
            case LARGE:
                config.mainSize = new Vector2f(150f, 150f);
                config.mainGrowth = new Vector2f(45f, 45f);
                config.coreSize = new Vector2f(100f, 100f);
                config.coreGrowth = new Vector2f(10f, 10f);
                config.duration = 1.0f;
                break;
            case MEDIUM:
                config.mainSize = new Vector2f(125f, 125f);
                config.mainGrowth = new Vector2f(30f, 30f);
                config.coreSize = new Vector2f(100f, 100f);
                config.coreGrowth = new Vector2f(15f, 15f);
                config.duration = 0.8f;
                break;
            default: // SMALL
                config.mainSize = new Vector2f(70f, 70f);
                config.mainGrowth = new Vector2f(20f, 20f);
                config.coreSize = new Vector2f(55f, 55f);
                config.coreGrowth = new Vector2f(10f, 10f);
                config.duration = 0.4f;
                config.isSmall = true;
                break;
        }

        return config;
    }

    private void renderExplosionEffects(Vector2f location, Vector2f velocity, float angle, ExplosionConfig config) {
        SpriteAPI mainSprite = Global.getSettings().getSprite("flak_puff", "puff_sprite1");
        SpriteAPI coreSprite = Global.getSettings().getSprite("flak_puff", "puff_sprite2");

        MagicRender.battlespace(mainSprite, location, velocity, config.mainSize, config.mainGrowth,
                angle, 0f, new Color(50, 50, 50, 250), true, 0.2f, config.duration, 1f);

        MagicRender.battlespace(coreSprite, location, velocity, config.coreSize, config.coreGrowth,
                angle, 0f, new Color(254, 132, 84, 170), false, 0.1f, 0.2f, 0.2f);
    }

    private void handleDamageExplosion(DamagingProjectileAPI proj, WeaponAPI weapon, Vector2f location,
                                       CombatEngineAPI engine, ExplosionConfig config) {


        // 设置爆炸伤害
        explosionSpec.setMinDamage(proj.getDamage().getDamage() / 5f);
        explosionSpec.setMaxDamage(proj.getDamage().getDamage() / 3f);
        explosionSpec.setDamageType(DamageType.FRAGMENTATION);

        // 生成主爆炸
        engine.spawnDamagingExplosion(explosionSpec, weapon.getShip(), location, false);

        // 小型武器生成额外爆炸点
        if (config.isSmall) {
            for (int i = 0; i < 1; i++) {
                Vector2f randomLocation = org.lazywizard.lazylib.MathUtils.getRandomPointInCircle(location, 50f);
                engine.spawnDamagingExplosion(explosionSpec, weapon.getShip(), randomLocation, false);
                renderExplosionEffects(randomLocation, new Vector2f(0f, 0f),
                        (float) (Math.random() * 360f), config);
            }
        }
    }

    // 配置数据类
    private static class ExplosionConfig {
        Vector2f mainSize;
        Vector2f mainGrowth;
        Vector2f coreSize;
        Vector2f coreGrowth;
        float duration;
        boolean isSmall = false;
    }
}