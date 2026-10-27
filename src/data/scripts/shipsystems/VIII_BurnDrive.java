package data.scripts.shipsystems;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.plugins.ShipSystemStatsScript;
import com.fs.starfarer.api.util.IntervalUtil;
import data.scripts.util.VIII_TipVortexVisual;
import org.dark.shaders.light.LightShader;
import org.dark.shaders.light.StandardLight;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.combat.CombatUtils;
import org.lwjgl.util.vector.Vector2f;

import java.awt.*;
import java.util.HashMap;
import java.util.Map;

import static data.scripts.util.VIII_BoxBasedUtil.addNebulaSmoothParticle;

public class VIII_BurnDrive extends BaseShipSystemScript {
    // 贴图配置
    private static final String SPRITE_OUTLINE = "misc"; // 轮廓贴图类别
    private static final String SPRITE_OUTLINE_ID = "slipstream0"; // 轮廓贴图ID
    private static final String SPRITE_SMOKE = "misc"; // 烟雾贴图类别
    private static final String SPRITE_SMOKE_ID = "slipstream_edge"; // 烟雾贴图ID

    private final Object STATUSKEY1 = new Object();

    // 颜色配置
    private static final Color JITTER_COLOR = new Color(100, 165, 255, 255); // 抖动颜色
    private static final Color PARTICLE_COLOR = new Color(100, 165, 255, 200); // 粒子颜色
    private static final Color LIGHT_COLOR = new Color(125, 175, 255, 255); // 光源颜色

    // 光源参数
    private static final boolean ENABLE_LIGHT = true; // 是否启用光源
    private static final float LIGHT_INTENSITY_MIN = 0.0f; // 最小光源强度（effectLevel=0时）
    private static final float LIGHT_INTENSITY_MAX = 2.0f; // 最大光源强度（effectLevel=1时）
    private static final float LIGHT_SIZE_MIN = 0f; // 最小光源大小（effectLevel=0时）
    private static final float LIGHT_SIZE_MAX = 750f; // 最大光源大小（effectLevel=1时）

    private static final float SPEED_FACTOR = 1f; // 推力系数
    private static final boolean ENABLE_THRUST = false; // 是否启用推力（false则只有速度加成）

    // 灼烧伤害参数
    private static final float BURN_DPS = 2000f; // 每秒灼烧伤害
    private static final float DPS_DURATION = 0.01f; // 伤害间隔
    private static final boolean ENABLE_BURN_DAMAGE = true; // 是否启用灼烧伤害

    // 视觉效果参数 - 宽度速度联动控制
    private static final float WIDTH_STANDARD_SPEED = 100f; // 宽度标准速度（速度达到此值时宽度倍率=1.0）
    private static final float WIDTH_MIN_MULT = 0.5f; // 宽度最小倍率（速度很低时的下限）
    private static final float WIDTH_MAX_MULT = 3.0f; // 宽度最大倍率（速度很高时的上限）

    // 视觉效果参数 - 长度速度联动控制
    private static final float LENGTH_STANDARD_SPEED = 55; // 长度标准速度（速度达到此值时长度倍率=1.0）
    private static final float LENGTH_MIN_MULT = 2f; // 长度最小倍率（速度很低时的下限）
    private static final float LENGTH_MAX_MULT = 2f; // 长度最大倍率（速度很高时的上限）

    // 特效位置参数（负数=向后，正数=向前，相对于飞船朝向）
    private static final float OUTLINE_START_OFFSET = -30f; // 轮廓起点偏移（effectLevel=0时）
    private static final float OUTLINE_END_OFFSET = -45f; // 轮廓终点偏移（effectLevel=1时）
    private static final float SMOKE_START_OFFSET = -30f; // 烟雾起点偏移（effectLevel=0时）
    private static final float SMOKE_END_OFFSET = -60f; // 烟雾终点偏移（effectLevel=1时）

    // 特效尺寸参数（倍率相对于基础尺寸，会叠加速度倍率）
    private static final float SMOKE_WIDTH_START_MULT = 0.5f; // 烟雾起始宽度倍率（effectLevel=0时）
    private static final float SMOKE_WIDTH_END_MULT = 1.2f; // 烟雾终点宽度倍率（effectLevel=1时）
    private static final float SMOKE_LENGTH_START_MULT = 0.9f; // 烟雾起始长度倍率（effectLevel=0时）
    private static final float SMOKE_LENGTH_END_MULT = 1.0f; // 烟雾终点长度倍率（effectLevel=1时）

    private static final float OUTLINE_WIDTH_START_MULT = 0.5f; // 轮廓起始宽度倍率（effectLevel=0时）
    private static final float OUTLINE_WIDTH_END_MULT = 1.2f; // 轮廓终点宽度倍率（effectLevel=1时）
    private static final float OUTLINE_LENGTH_START_MULT = 0.9f; // 轮廓起始长度倍率（effectLevel=0时）
    private static final float OUTLINE_LENGTH_END_MULT = 1.0f; // 轮廓终点长度倍率（effectLevel=1时）

    // 粒子效果参数
    private static final float PARTICLE_INTERVAL = 10f; // 粒子生成间隔倍率
    private static final float PARTICLE_SIZE = 100f; // 粒子大小
    private static final float PARTICLE_DURATION = 3f; // 粒子持续时间
    private static final float PARTICLE_ALPHA = 0.2f; // 粒子透明度

    // 抖动效果参数
    private static final float JITTER_RANGE = 3f; // 抖动范围
    private static final int JITTER_COPIES = 5; // 抖动副本数

    // ==================== 内部变量 ====================

    private VIII_TipVortexVisual visualOutline = null;
    private VIII_TipVortexVisual visualSmoke = null;
    private SpriteAPI sprite = null;
    private StandardLight light = null; // 光源对象

    private float jitterClock = 0f;
    private float burnClock = 0f;

    private final IntervalUtil interval = new IntervalUtil(1f, 1f);

    private static final Map<ShipAPI.HullSize, Float> mag = new HashMap<>();

    static {
        mag.put(ShipAPI.HullSize.FIGHTER, 0.33F);
        mag.put(ShipAPI.HullSize.FRIGATE, 0.33F);
        mag.put(ShipAPI.HullSize.DESTROYER, 0.33F);
        mag.put(ShipAPI.HullSize.CRUISER, 0.5F);
        mag.put(ShipAPI.HullSize.CAPITAL_SHIP, 0.5F);
    }

    public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
        float mult = mag.get(ShipAPI.HullSize.CRUISER);
        if (stats.getVariant() != null) {
            mult = mag.get(stats.getVariant().getHullSize());
        }

        ShipAPI ship = null;
        boolean player = false;
        if (stats.getEntity() instanceof ShipAPI) {
            ship = (ShipAPI)stats.getEntity();
            player = ship == Global.getCombatEngine().getPlayerShip();
        }

        if (player) {
            ShipSystemAPI system = getDamper(ship);
            if (system != null) {
                float percent = (1.0F - mult) * effectLevel * 100.0F;
                Global.getCombatEngine().maintainStatusForPlayerShip(this.STATUSKEY1, system.getSpecAPI().getIconSpriteName(), system.getDisplayName(), Math.round(percent) + "% 降低受到的伤害", false);
            }
        }
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine.isPaused())
            return;

        if(ship !=null) {

            if (sprite == null) {
                sprite = ship.getSpriteAPI();
            }

            if (!ship.isPhased()) {
                ship.setPhased(true); // 仅当不在相位时进入

                // 检测模块船并设置模块相位状态
                if (ship.isShipWithModules()) {
                    for (ShipAPI module : ship.getChildModulesCopy()) {
                        if (module != null && !module.isPhased()) {
                            module.setPhased(true);
                        }
                    }
                }
            }

            stats.getFluxDissipation().modifyMult(id, 1.1f);
            stats.getMaxSpeed().modifyFlat(id, 350f * effectLevel);
            stats.getAcceleration().modifyFlat(id, 125f * effectLevel);
            if (state == ShipSystemStatsScript.State.OUT) {
                ship.setCollisionClass(CollisionClass.SHIP);
            } else {
                ship.setCollisionClass(CollisionClass.NONE);
                if (ship.isShipWithModules()) {
                    for (ShipAPI module : ship.getChildModulesCopy()) {
                        if (module != null && !module.isPhased()) {
                            module.setCollisionClass(CollisionClass.NONE);
                        }
                    }
                }
            }

            if (ENABLE_THRUST) {
                float force = ship.getMassWithModules() * SPEED_FACTOR * effectLevel * effectLevel;
                if (effectLevel < 0.5f)
                    force = 0f;
                CombatUtils.applyForce(ship, ship.getFacing(), force);
            }

            // 初始化视觉效果
            if (visualOutline == null) {
                SpriteAPI faw = Global.getSettings().getSprite(SPRITE_OUTLINE, SPRITE_OUTLINE_ID);
                SpriteAPI faw3 = Global.getSettings().getSprite(SPRITE_SMOKE, SPRITE_SMOKE_ID);

                visualOutline = new VIII_TipVortexVisual(ship, faw, faw, faw);
                engine.addLayeredRenderingPlugin(visualOutline);

                visualSmoke = new VIII_TipVortexVisual(ship, faw3, faw, faw3);
                engine.addLayeredRenderingPlugin(visualSmoke);

                // 创建光源
                if (ENABLE_LIGHT) {
                    light = new StandardLight(ship.getLocation(), new Vector2f(0, 0), new Vector2f(0, 0), ship);
                    light.setColor(LIGHT_COLOR);
                    light.setIntensity(LIGHT_INTENSITY_MIN); // 初始强度为最小值
                    light.setSize(LIGHT_SIZE_MIN); // 初始大小为最小值
                    light.makePermanent();
                    LightShader.addLight(light);
                }
            }

            // 更新视觉效果
            visualOutline.setEffectLevel(effectLevel);
            visualSmoke.setEffectLevel(effectLevel);

            // 计算独立的宽度和长度速度倍率
            float currentSpeed = ship.getVelocity().length();
            float widthSizeMult = currentSpeed / WIDTH_STANDARD_SPEED;
            if (widthSizeMult < WIDTH_MIN_MULT)
                widthSizeMult = WIDTH_MIN_MULT;
            if (widthSizeMult > WIDTH_MAX_MULT)
                widthSizeMult = WIDTH_MAX_MULT;

            float lengthSizeMult = currentSpeed / LENGTH_STANDARD_SPEED;
            if (lengthSizeMult < LENGTH_MIN_MULT)
                lengthSizeMult = LENGTH_MIN_MULT;
            if (lengthSizeMult > LENGTH_MAX_MULT)
                lengthSizeMult = LENGTH_MAX_MULT;

            // 使用较大的倍率作为sizeMult（用于getRenderRadius和基础尺寸计算）
            float baseSizeMult = Math.max(widthSizeMult, lengthSizeMult);

            // 轮廓涡流：位置、宽度、长度都从起点过渡到终点
            visualOutline.setSizeMult(baseSizeMult);
            float outlineOffset = OUTLINE_START_OFFSET + (OUTLINE_END_OFFSET - OUTLINE_START_OFFSET) * effectLevel;
            visualOutline.setOffset(outlineOffset);

            float outlineWidthMult = OUTLINE_WIDTH_START_MULT
                    + (OUTLINE_WIDTH_END_MULT - OUTLINE_WIDTH_START_MULT) * effectLevel;
            float outlineLengthMult = OUTLINE_LENGTH_START_MULT
                    + (OUTLINE_LENGTH_END_MULT - OUTLINE_LENGTH_START_MULT) * effectLevel;
            // 在widthMult和lengthMult中补偿sizeMult，实现独立控制
            visualOutline.setWidthMult((widthSizeMult / baseSizeMult) * outlineWidthMult);
            visualOutline.setLengthMult((lengthSizeMult / baseSizeMult) * outlineLengthMult);

            // 烟雾涡流：位置、宽度、长度都从起点过渡到终点
            visualSmoke.setSizeMult(baseSizeMult);
            float smokeOffset = SMOKE_START_OFFSET + (SMOKE_END_OFFSET - SMOKE_START_OFFSET) * effectLevel;
            visualSmoke.setOffset(smokeOffset);

            float smokeWidthMult = SMOKE_WIDTH_START_MULT + (SMOKE_WIDTH_END_MULT - SMOKE_WIDTH_START_MULT) * effectLevel;
            float smokeLengthMult = SMOKE_LENGTH_START_MULT
                    + (SMOKE_LENGTH_END_MULT - SMOKE_LENGTH_START_MULT) * effectLevel;
            // 在widthMult和lengthMult中补偿sizeMult，实现独立控制
            visualSmoke.setWidthMult((widthSizeMult / baseSizeMult) * smokeWidthMult);
            visualSmoke.setLengthMult((lengthSizeMult / baseSizeMult) * smokeLengthMult);

            // 生成引擎粒子效果
            float amount = engine.getElapsedInLastFrame();
            interval.advance(PARTICLE_INTERVAL * amount);
            if (interval.intervalElapsed()) {
                for (ShipEngineControllerAPI.ShipEngineAPI eng : ship.getEngineController().getShipEngines()) {
                    if (eng.isDisabled())
                        continue;
                    Vector2f vel = MathUtils.getPoint(new Vector2f(), ship.getMaxSpeed() * 0.25f,
                            eng.getEngineSlot().computeMidArcAngle(ship.getFacing()));

                    // 使用BoxUtil粒子效果
                    addNebulaSmoothParticle(eng.getLocation(), vel,
                            eng.getContribution() * PARTICLE_SIZE,
                            3f, 0.5f, effectLevel * PARTICLE_ALPHA, PARTICLE_DURATION,
                            eng.getContrailColor());
                }
            }

            // 灼烧伤害
            if (ENABLE_BURN_DAMAGE) {
                burnClock += engine.getElapsedInLastFrame() * effectLevel;
                if (burnClock >= DPS_DURATION) {
                    burnClock -= DPS_DURATION;

                    float burnDamage = BURN_DPS * DPS_DURATION;
                    float burnRange = visualSmoke.getBasicWidth() * visualSmoke.getWidthMult();

                    for (ShipAPI target : CombatUtils.getShipsWithinRange(ship.getLocation(), burnRange)) {
                        if (target.getOwner() == ship.getOwner())
                            continue;
                        if (target.isPhased())
                            continue;

                        float distance = Vector2f.sub(ship.getLocation(), target.getLocation(), null).length();

                        Vector2f hitPoint = new Vector2f(target.getLocation());
                        if (distance > 0) {
                            Vector2f direction = Vector2f.sub(ship.getLocation(), target.getLocation(), null);
                            direction.normalise();
                            // 将命中点从船舰中心向爆炸中心方向移动碰撞半径的距离
                            hitPoint.x -= direction.x * target.getCollisionRadius();
                            hitPoint.y -= direction.y * target.getCollisionRadius();
                        }


                        engine.applyDamage(target, hitPoint, burnDamage,
                                DamageType.HIGH_EXPLOSIVE, 0f, false, false, ship, false);
                        engine.applyDamage(target, hitPoint, burnDamage,
                                DamageType.KINETIC, 0f, false, false, ship, false);
                    }

                    for (MissileAPI target : CombatUtils.getMissilesWithinRange(ship.getLocation(), burnRange)) {
                        if (target.getOwner() == ship.getOwner())
                            continue;
                        engine.applyDamage(target, target.getLocation(), burnDamage,
                                DamageType.HIGH_EXPLOSIVE, 0f, false, false, ship, false);
                    }
                }
            }

            // 抖动效果
            if (state == State.ACTIVE)
                jitterClock += engine.getElapsedInLastFrame();
            float rate = effectLevel * 0.5f + jitterClock / ship.getSystem().getChargeActiveDur() * 0.5f;
            ship.setJitter(this, JITTER_COLOR, rate * rate, JITTER_COPIES, rate * JITTER_RANGE);

            // 更新光源
            if (ENABLE_LIGHT && light != null) {
                // 强度从最小值平滑过渡到最大值
                float currentIntensity = LIGHT_INTENSITY_MIN + (LIGHT_INTENSITY_MAX - LIGHT_INTENSITY_MIN) * effectLevel;
                // 大小从最小值平滑过渡到最大值
                float currentSize = LIGHT_SIZE_MIN + (LIGHT_SIZE_MAX - LIGHT_SIZE_MIN) * effectLevel;

                // 每帧更新所有光源属性（GraphicsLib需要每帧设置）
                light.setColor(LIGHT_COLOR);
                light.setIntensity(currentIntensity);
                light.setSize(currentSize);
                light.setLocation(ship.getLocation());
            }
        }
    }

    public void unapply(MutableShipStatsAPI stats, String id) {
        ShipAPI ship = (ShipAPI) stats.getEntity();
        if (ship != null) {
            stats.getMaxSpeed().unmodify(id);
            stats.getMaxTurnRate().unmodify(id);
            stats.getTurnAcceleration().unmodify(id);
            stats.getAcceleration().unmodify(id);
            stats.getDeceleration().unmodify(id);
            stats.getFluxDissipation().unmodify(id);

            stats.getTimeMult().unmodify(id);
            stats.getBallisticRoFMult().unmodify(id);
            stats.getBallisticWeaponDamageMult().unmodify(id);
            stats.getEnergyRoFMult().unmodify(id);
            stats.getEnergyWeaponDamageMult().unmodify(id);

            stats.getEnergyDamageTakenMult().unmodify(id);
            stats.getFragmentationDamageTakenMult().unmodify(id);
            stats.getKineticDamageTakenMult().unmodify(id);
            stats.getHighExplosiveDamageTakenMult().unmodify(id);
            stats.getEmpDamageTakenMult().unmodify(id);

            if (ship.getVariant().hasHullMod("PolariphaseDrive") && ship.isPhased()) {
                ship.setPhased(false);

                // 检测模块船并取消模块相位状态
                if (ship.isShipWithModules()) {
                    for (ShipAPI module : ship.getChildModulesCopy()) {
                        if (module != null && module.isPhased()) {
                            module.setPhased(false);
                        }
                    }
                }
            }

            sprite = null;

            if (visualOutline != null) {
                visualOutline.setEffectLevel(0f);
                visualSmoke.setEffectLevel(0f);
            }

            // 清理光源
            if (light != null) {
                LightShader.removeLight(light);
                light = null;
            }
        }
    }

    public static ShipSystemAPI getDamper(ShipAPI ship) {
        ShipSystemAPI system = ship.getPhaseCloak();
        if (system != null && system.getId().equals("DamperBurn")) {
            return system;
        }else {
            return system != null && system.getSpecAPI() != null && system.getSpecAPI().hasTag("uses_damper_ai") ? system : ship.getSystem();
        }
    }

    public StatusData getStatusData(int index, State state, float effectLevel) {
        if (index == 0) {
            return new StatusData("提高引擎出力", false);
        }
        return null;
    }
}