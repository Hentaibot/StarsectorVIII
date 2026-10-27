//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SoundPlayerAPI;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.BoundsAPI;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.FluxTrackerAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponSize;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import java.awt.Color;
import java.util.*;

import org.lazywizard.lazylib.CollisionUtils;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lazywizard.lazylib.combat.CombatUtils;
import org.lwjgl.util.vector.Vector2f;

public class VIII_SpecializedArmor extends BaseHullMod {
    Map<WeaponAPI.WeaponSize, String> projectile_sounds = new HashMap<>();
    Map<WeaponAPI.WeaponSize, String> beam_sounds;
    Map<WeaponAPI.WeaponSize, Vector2f> puff_size;
    Map<WeaponAPI.WeaponSize, Vector2f> spark_size;
    List<DamagingProjectileAPI> tracked_beams;
    List<DamagingProjectileAPI> remove_tracked;
    List<DamagingProjectileAPI> reflected_beams;
    SpriteAPI smoke_sprite;
    private Float previous_hitpoints;
    private final Float HE_KINETIC_DAMAGE_REDUCTION;
    private final Float ENERGY_DAMAGE_REDUCTION;
    private final Float BEAM_DAMAGE_REDUCTION;
    private final Float EMP_DAMAGE_NEGATION;
    private final Float HEALTH_FLUX_CONVERSION;

    public VIII_SpecializedArmor() {
        this.projectile_sounds.put(WeaponSize.SMALL, "uaf_ricochet_projectile_small");
        this.projectile_sounds.put(WeaponSize.MEDIUM, "uaf_ricochet_projectile_medium");
        this.projectile_sounds.put(WeaponSize.LARGE, "uaf_ricochet_projectile_large");
        this.beam_sounds = new HashMap<>();
        this.beam_sounds.put(WeaponSize.SMALL, "uaf_ricochet_energy_small");
        this.beam_sounds.put(WeaponSize.MEDIUM, "uaf_ricochet_energy_medium");
        this.beam_sounds.put(WeaponSize.LARGE, "uaf_ricochet_energy_large");
        this.puff_size = new HashMap<>();
        this.puff_size.put(WeaponSize.SMALL, new Vector2f(15.0F, 15.0F));
        this.puff_size.put(WeaponSize.MEDIUM, new Vector2f(35.0F, 35.0F));
        this.puff_size.put(WeaponSize.LARGE, new Vector2f(55.0F, 55.0F));
        this.spark_size = new HashMap<>();
        this.spark_size.put(WeaponSize.SMALL, new Vector2f(1.0F, 1.5F));
        this.spark_size.put(WeaponSize.MEDIUM, new Vector2f(1.5F, 2.0F));
        this.spark_size.put(WeaponSize.LARGE, new Vector2f(2.0F, 2.5F));
        this.tracked_beams = new ArrayList<>();
        this.remove_tracked = new ArrayList<>();
        this.reflected_beams = new ArrayList<>();
        this.smoke_sprite = Global.getSettings().getSprite("flak_puff", "puff_sprite1");
        this.previous_hitpoints = null;
        this.HE_KINETIC_DAMAGE_REDUCTION = 0.45F;
        this.ENERGY_DAMAGE_REDUCTION = 0.1F;
        this.BEAM_DAMAGE_REDUCTION = 0.25F;
        this.EMP_DAMAGE_NEGATION = 0.4F;
        this.HEALTH_FLUX_CONVERSION = 0.9F;
    }

    public void advanceInCombat(ShipAPI ship, float amount) {
        float _TRESHOLD = 2.5F;
        //if (ship != null && ship.getCustomData() != null && !ship.getCustomData().containsKey("uaf_enhance_reflecta")) {
            //_TRESHOLD = 2.0F;
        //}

        if (ship != null) {
            for(DamagingProjectileAPI beams : CombatUtils.getProjectilesWithinRange(ship.getLocation(), ship.getCollisionRadius())) {
                WeaponAPI weapon_source = beams.getWeapon();
                if ((weapon_source == null || weapon_source.getShip() != ship) && !beams.didDamage() && this.shouldBeMarked(beams) && !this.tracked_beams.contains(beams)) {
                    float _tdice = MathUtils.getRandomNumberInRange(0.0F, 10.0F);
                    beams.setCustomData("viii_projectile_treshold", _tdice);
                    this.tracked_beams.add(beams);
                }
            }
        }

        for(DamagingProjectileAPI tracked : this.tracked_beams) {
            Vector2f p1 = tracked.getLocation();
            Vector2f p2 = this.getVectorBy(p1, tracked.getFacing(), 20.0F);
            Vector2f collision_point = null;
            BoundsAPI.SegmentAPI intersected_segment = null;

            if (ship != null) {
                for(BoundsAPI.SegmentAPI segment : ship.getExactBounds().getSegments()) {
                    //collision_point = CollisionUtils.getCollisionPoint(p1, p2, segment.getP1(), segment.getP2());
                    // 改进碰撞检测，考虑投射物尺寸
                    Vector2f[] collisionPoints = new Vector2f[]{CollisionUtils.getCollisionPoint(
                            p1, p2, segment.getP1(), segment.getP2()
                    )};
                    // 使用最近的碰撞点
                    collision_point = collisionPoints[0];
                    if (collision_point != null) {
                        intersected_segment = segment;
                        break;
                    }
                }
            }

            float dice = (Float)tracked.getCustomData().get("viii_projectile_treshold");
            if (collision_point != null && dice < _TRESHOLD && !tracked.getCustomData().containsKey("reflected")) {
                Vector2f directional = VectorUtils.getDirectionalVector(intersected_segment.getP1(), intersected_segment.getP2());
                float __x = directional.x;
                float __y = directional.y;
                double _x = (double)(-__y) / Math.sqrt(__x * __x + __y * __y);
                double _y = (double)__x / Math.sqrt(__x * __x + __y * __y);
                Vector2f n = new Vector2f((float)_x, (float)_y);
                Vector2f vel = tracked.getVelocity();
                double scalar = (double)-2.0F * dot(n, vel);
                Vector2f scalar_n = new Vector2f((float)(_x * scalar), (float)(_y * scalar));
                vel.setX((vel.x + scalar_n.x) / 2.0F);
                vel.setY((vel.y + scalar_n.y) / 2.0F);
                float angleVariance = MathUtils.getRandomNumberInRange(-5f, 5f);
                tracked.setFacing(VectorUtils.getFacing(vel)+angleVariance);
                tracked.setCustomData("collision_status", tracked.getCollisionClass());
                tracked.setCollisionClass(CollisionClass.NONE);
                tracked.setCustomData("collision_point", collision_point);
                tracked.setCustomData("has_been_reflected", true);
                this.reflected_beams.add(tracked);
                SoundPlayerAPI sound_player = Global.getSoundPlayer();
                WeaponAPI.WeaponSize weapon_size;

                try {
                    weapon_size = tracked.getWeapon().getSize();
                } catch (Exception var29) {
                    throw new RuntimeException(var29);
                }

                if (tracked.getDamageType() != DamageType.ENERGY && tracked.getDamageType() != DamageType.OTHER) {
                    sound_player.playSound(this.projectile_sounds.get(weapon_size), 1.0F, 1.0F, collision_point, vel);
                } else if (tracked.getDamageType() == DamageType.ENERGY) {
                    sound_player.playSound(this.beam_sounds.get(weapon_size), 1.0F, 1.0F, collision_point, vel);
                }

                this.drawSfx(tracked, collision_point, vel);
                this.healthConversionv2(tracked, ship);
            } else {
                if (tracked.getDamageTarget() == ship && tracked.didDamage()) {
                    if (ship != null) {
                        this.healthConversionv2(tracked, ship);
                    }

                    this.remove_tracked.add(tracked);
                }

                this.remove_tracked.add(tracked);
            }
        }

        //this.tracked_beams.removeAll(this.remove_tracked);
        Iterator<DamagingProjectileAPI> iterator = tracked_beams.iterator();
        while (iterator.hasNext()) {
            DamagingProjectileAPI tracked = iterator.next();
            if (remove_tracked.contains(tracked)) {
                iterator.remove();
            }
        }
        this.remove_tracked.clear();

        for(DamagingProjectileAPI reflected : this.reflected_beams) {
            this.tracked_beams.remove(reflected);

            Vector2f collision_point = (Vector2f)reflected.getCustomData().get("collision_point");
            float distance = MathUtils.getDistance(collision_point, reflected.getLocation());
            if (distance > 150.0F) {
                reflected.setCollisionClass((CollisionClass)reflected.getCustomData().get("collision_status"));
                reflected.setCustomData("reflected", true);
            }
        }

        this.removeIrrelevantProjectiles();
        this.removeIrrelevantProjectilesv2();
    }

    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
        stats.getHighExplosiveDamageTakenMult().modifyMult(id, 1.0F - this.HE_KINETIC_DAMAGE_REDUCTION);
        stats.getKineticDamageTakenMult().modifyMult(id, 1.0F - this.HE_KINETIC_DAMAGE_REDUCTION);
        stats.getEnergyDamageTakenMult().modifyMult(id, 1.0F - this.ENERGY_DAMAGE_REDUCTION);
        stats.getBeamDamageTakenMult().modifyMult(id, 1.0F - this.BEAM_DAMAGE_REDUCTION);
        stats.getEmpDamageTakenMult().modifyMult(id, 1.0F - this.EMP_DAMAGE_NEGATION);
    }

    private void healthConversion(ShipAPI ship) {
        if (this.previous_hitpoints == null) {
            this.previous_hitpoints = ship.getHitpoints();
        } else {
            Float current_hitpoints = ship.getHitpoints();
            if (current_hitpoints < this.previous_hitpoints) {
                float difference = this.previous_hitpoints - current_hitpoints;
                difference = difference * this.HEALTH_FLUX_CONVERSION;
                FluxTrackerAPI flux_tracker = ship.getFluxTracker();
                flux_tracker.increaseFlux(difference, false);
            }

            this.previous_hitpoints = current_hitpoints;
        }

    }

    private void healthConversionv2(DamagingProjectileAPI proj, ShipAPI ship) {
        float soft = proj.getBaseDamageAmount();
        soft = soft * this.HEALTH_FLUX_CONVERSION;
        FluxTrackerAPI flux_tracker = ship.getFluxTracker();
        flux_tracker.increaseFlux(soft, false);
    }

    public String getDescriptionParam(int index, ShipAPI.HullSize hullSize) {
        if (index == 0) {
            return "" + Float.valueOf(this.HE_KINETIC_DAMAGE_REDUCTION * 100.0F).intValue() + "%";
        } else if (index == 1) {
            return "" + Float.valueOf(this.ENERGY_DAMAGE_REDUCTION * 100.0F).intValue() + "%";
        } else {
            return index == 2 ? "" + Float.valueOf(this.BEAM_DAMAGE_REDUCTION * 100.0F).intValue() + "%" : null;
        }
    }

    private void drawSfx(DamagingProjectileAPI proj, Vector2f point, Vector2f vel) {
        WeaponAPI.WeaponSize weapon_size;

        try {
            weapon_size = proj.getWeapon().getSize();
        } catch (Exception var19) {
            throw new RuntimeException(var19);
        }

        float clone_x = vel.x;
        float clone_y = vel.y;
        float[] denominator = new float[]{75.0F, 100.0F, 125.0F, 150.0F, 175.0F};
        float[] denominator2 = new float[]{25.0F, 30.0F, 35.0F, 40.0F, 45.0F};
        CombatEngineAPI engine = Global.getCombatEngine();
        int smoke_number = MathUtils.getRandomNumberInRange(1, 3);

        for(int i = 0; i < smoke_number; ++i) {
            Vector2f range = this.puff_size.get(weapon_size);
            float size = MathUtils.getRandomNumberInRange(range.x, range.y);
            float opacity = MathUtils.getRandomNumberInRange(0.05F, 0.1F);
            float duration = MathUtils.getRandomNumberInRange(0.1F, 0.3F);
            int denomination = MathUtils.getRandomNumberInRange(0, 4);
            Vector2f vel_clone = new Vector2f((float)(denomination * 5) + clone_x / denominator[denomination], (float)(denomination * 5) + clone_y / denominator[denomination]);
            engine.addSmokeParticle(point, vel_clone, size, opacity, duration, Color.darkGray);
        }

        int fire_number = MathUtils.getRandomNumberInRange(5, 10);

        for(int i = 0; i < fire_number; ++i) {
            Vector2f range = this.spark_size.get(weapon_size);
            float size = MathUtils.getRandomNumberInRange(range.x, range.y);
            float opacity = MathUtils.getRandomNumberInRange(0.3F, 0.6F);
            float duration = MathUtils.getRandomNumberInRange(0.3F, 0.7F);
            int denomination = MathUtils.getRandomNumberInRange(0, 4);
            Vector2f vel_clone = new Vector2f(clone_x / denominator2[denomination], clone_y / denominator2[denomination]);
            engine.addHitParticle(point, vel_clone, size, opacity, duration, Color.YELLOW);
        }

    }

    static double dot(Vector2f a, Vector2f b) {
        return a.x * b.x + a.y * b.y;
    }

    private void removeIrrelevantProjectiles() {
        CombatEngineAPI engine = Global.getCombatEngine();
        List<DamagingProjectileAPI> trash = new ArrayList<>();

        for(DamagingProjectileAPI tracked : this.tracked_beams) {
            if (!engine.isEntityInPlay(tracked)) {
                trash.add(tracked);
            }

            if (!this.shouldBeMarked(tracked) && !trash.contains(tracked)) {
                trash.add(tracked);
            }
        }

        this.tracked_beams.removeAll(trash);
        trash.clear();
    }

    private void removeIrrelevantProjectilesv2() {
        CombatEngineAPI engine = Global.getCombatEngine();
        List<DamagingProjectileAPI> trash = new ArrayList<>();

        for(DamagingProjectileAPI reflected : this.reflected_beams) {
            if (!engine.isEntityInPlay(reflected)) {
                trash.add(reflected);
            }

            if (!this.shouldBeMarked(reflected) && !trash.contains(reflected)) {
                trash.add(reflected);
            }
        }

        this.reflected_beams.removeAll(trash);
        trash.clear();
    }

    private void cleanupProjectiles(List<DamagingProjectileAPI> list) {
        CombatEngineAPI engine = Global.getCombatEngine();

        list.removeIf(proj -> !engine.isEntityInPlay(proj) || !shouldBeMarked(proj));
    }

    private Boolean shouldBeMarked(DamagingProjectileAPI proj) {
        Map<String, Object> custom_data = proj.getCustomData();
        return !custom_data.containsKey("reflected");
    }

    private Vector2f getVectorBy(Vector2f point, float angle, float distance) {
        float processed_angle = (float)((double)angle * Math.PI / (double)180.0F);
        float posX = (float)((double)distance * Math.cos(processed_angle) + (double)point.x);
        float posY = (float)((double)distance * Math.sin(processed_angle) + (double)point.y);
        return new Vector2f(posX, posY);
    }


    public void addPostDescriptionSection(TooltipMakerAPI tooltip, ShipAPI.HullSize hullSize, ShipAPI ship, float width, boolean isForModSpec) {
        Color green = new Color(55, 245, 65, 255);
        Color warning = new Color(199, 78, 78, 155);
        Color flavor = new Color(110, 110, 110, 255);
        tooltip.addSectionHeading("Details", Alignment.MID, 10.0F);
        tooltip.addPara("Reflecta Armor allows greater survivability even when armor are fully depleted as the plating was built-in with the hull.\n\n• Deflection depends on %s and %s up to %s chance. Incoming damage are reduced by %s and also converted into soft flux by %s.", 10.0F, Misc.getHighlightColor(), new String[]{"velocity", "type of projectile", "50%", "50%", "10% of base value"});
        tooltip.addPara("• Kinetic Damage Reduced by %s • Energy Beam Damage Reduced by %s • Energy Damage Reduced by %s • EMP Resistance Increased by %s", 10.0F, green, new String[]{Misc.getRoundedValue(this.HE_KINETIC_DAMAGE_REDUCTION * 100.0F) + "%", Misc.getRoundedValue(this.ENERGY_DAMAGE_REDUCTION * 100.0F) + "%", Misc.getRoundedValue(this.BEAM_DAMAGE_REDUCTION * 100.0F) + "%", Misc.getRoundedValue(this.EMP_DAMAGE_NEGATION * 100.0F) + "%"});
        tooltip.addSectionHeading("Warning", Misc.getHighlightColor(), warning, Alignment.MID, 10.0F);
        TooltipMakerAPI incompat_text = tooltip.beginImageWithText("graphics/icons/tooltip/uaf_hullmod_incompatible.png", 40.0F);
        incompat_text.addPara("The armor plating %s and %s weaponry, it also doesn't fully protect against %s.", 10.0F, Misc.getNegativeHighlightColor(), new String[]{"does poorly against beam", "high explosive", "missile and other explosive ordnances."});
        tooltip.addImageWithText(10.0F);
        tooltip.addPara("%s", 10.0F, flavor, new String[]{"That one bounced! We couldn't penetrate their armor. What is it made of?!"}).italicize();
        tooltip.addPara("%s", 2.0F, flavor, new String[]{"         — Former pirate survivor quoted from an encounter with the Fusoreina class battlecruiser."});
    }
}
