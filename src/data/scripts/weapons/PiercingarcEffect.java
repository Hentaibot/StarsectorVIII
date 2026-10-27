package data.scripts.weapons;

import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import org.lwjgl.util.vector.Vector2f;

public class PiercingarcEffect implements OnHitEffectPlugin {
    private final IntervalUtil fireInterval = new IntervalUtil(0.2F, 0.5F);
    private static final float BASE_EMP_CHANCE = 0.5f;

    @Override
    public void onHit(DamagingProjectileAPI projectile, CombatEntityAPI target, Vector2f point, boolean shieldHit, ApplyDamageResultAPI damageResult, CombatEngineAPI engine) {
        if(target != null && point != null) {
            if (Math.random() < BASE_EMP_CHANCE) {
                engine.spawnEmpArcPierceShields(projectile.getSource(), point, null, target, DamageType.ENERGY, 0.0F, 1500.0F, 100000.0F, "tachyon_lance_emp_impact", 20.0F, projectile.getProjectileSpec().getFringeColor(), projectile.getProjectileSpec().getCoreColor());
            }
        }
        if (this.fireInterval.intervalElapsed()) {
            ShipAPI ship = (ShipAPI) target;
            boolean hitShield = false;
            if (target != null) {
                hitShield = target.getShield() != null && target.getShield().isWithinArc(projectile.getSpawnLocation());
            }
            float pierceChance = 0;
            if (target != null) {
                pierceChance = ((ShipAPI) target).getHardFluxLevel() - 0.1F;
            }
            if (ship != null) {
                pierceChance *= ship.getMutableStats().getDynamic().getValue("shield_pierced_mult");
            }
            boolean piercedShield = hitShield && (float) Math.random() < pierceChance;


            if (!hitShield || piercedShield) {
                Vector2f loc = (Vector2f) projectile.getDamageTarget();
                float emp = projectile.getDamage().getFluxComponent() * 0.5F;
                float dam = projectile.getDamage().getDamage() * 0.25F;
                engine.spawnEmpArcPierceShields(projectile.getSource(), loc, projectile.getDamageTarget(), projectile.getDamageTarget(), DamageType.ENERGY, dam/2, emp/2, 100000.0F, "tachyon_lance_emp_impact", 15.0F, projectile.getProjectileSpec().getFringeColor(), projectile.getProjectileSpec().getCoreColor());
                engine.spawnEmpArcPierceShields(projectile.getSource(), loc, projectile.getDamageTarget(), projectile.getDamageTarget(), DamageType.ENERGY, dam/2, emp/2, 100000.0F, "tachyon_lance_emp_impact", 15.0F, projectile.getProjectileSpec().getFringeColor(), projectile.getProjectileSpec().getCoreColor());
            }
        }
    }
}