/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package data.scripts.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI.ShipEngineAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.util.Misc;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;

/**
 * @author 49747
 */
public class TVCEngine implements EveryFrameWeaponEffectPlugin, EngineController {

    ShipEngineAPI CombinedEngine = null;
    private float enginenowLenght = 0f;
    private float engineDefAngle = 180f;
    boolean noEngine = false;
    private float target = 180;
    private float turnrate = 0;
    float turningDelay = 0f;
    float lastfacing = 0;
    private Acc AccelType = Acc.None;

    // === 新增：物理模拟相关变量 ===
    private boolean runOnce = false;
    private float THRUST_TO_TURN = 0.0F;
    private float TURN_RIGHT_ANGLE = 0.0F;
    private float NEUTRAL_ANGLE = 180f; // 默认角度
    private float time = 0.0F;

    @Override
    public float getTarget() {
        return target;
    }

    @Override
    public float getDefault() {
        return engineDefAngle;
    }

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused()) {
            return;
        }
        ShipAPI ship = weapon.getShip();
        if (ship == null || !ship.isAlive()) {
            return;
        }

        // === 新增：初始化物理模拟参数 ===
        if (!runOnce) {
            runOnce = true;
            this.NEUTRAL_ANGLE = weapon.getSlot().getAngle();
            this.TURN_RIGHT_ANGLE = MathUtils.clampAngle(VectorUtils.getAngle(ship.getLocation(), weapon.getLocation()));
            this.THRUST_TO_TURN = smooth(MathUtils.getDistance(ship.getLocation(), weapon.getLocation()) / ship.getCollisionRadius());
        }

        if (CombinedEngine == null && !noEngine) {
            for (ShipEngineAPI eng : weapon.getShip().getEngineController().getShipEngines()) {
                if (Misc.getDistance(weapon.getLocation(), eng.getLocation()) < 1f) {
                    CombinedEngine = eng;
                    engineDefAngle = eng.getEngineSlot().getAngle();
                    ship.setCustomData(weapon.getSlot().getId().substring(0, 2) + "Engine", this);
                    turnrate = weapon.getTurnRate();
                    enginenowLenght = 0.4f;
                    break;
                }
            }
            if (CombinedEngine == null) {
                noEngine = true;
            }
        }
        if (CombinedEngine == null) {
            return;
        }
        if (CombinedEngine.isDisabled()) {
            return;
        }
        ShipEngineControllerAPI Controller = ship.getEngineController();
        float arc = weapon.getSlot().getArc() * 0.5f;
        if (arc == 0) {
            return;
        }

        // === 火焰长度控制逻辑 ===
        float needLength;
        if (ship.getTravelDrive().isActive()) {
            needLength = 0;
            AccelType = Acc.None;
        } else {
            if (Controller.isAccelerating()) {
                needLength = 1f;
                AccelType = Acc.Acc;
            } else if (Controller.isDecelerating() || Controller.isAcceleratingBackwards()) {
                needLength = 1f;
                AccelType = Acc.DeAcc;
            } else if (Controller.isTurningLeft() || Controller.isTurningRight() ||
                    Controller.isStrafingLeft() || Controller.isStrafingRight()) {
                needLength = 1f;
                AccelType = Acc.Acc;
            } else {
                needLength = 0.1f;
                AccelType = Acc.None;
            }
        }

        // 平滑过渡火焰长度
        if (Math.abs(enginenowLenght - needLength) < amount * 2) {
            enginenowLenght = needLength;
        } else {
            if (needLength - enginenowLenght > 0) {
                enginenowLenght += amount;
            } else {
                enginenowLenght -= amount;
            }
        }

        // 设置引擎火焰长度
        ship.getEngineController().setFlameLevel(CombinedEngine.getEngineSlot(), enginenowLenght);

        // === 新增：基于物理模拟的角度计算 ===
        time += amount;
        float FREQ = 0.05F;
        if (time >= FREQ) {
            time = 0.0F;

            float accelerateAngle = NEUTRAL_ANGLE;
            float turnAngle = NEUTRAL_ANGLE;
            float thrust = 0.0F;
            boolean accel = false;
            boolean turn = false;

            // 检测加速状态
            if (Controller.isAccelerating()) {
                accelerateAngle = 180.0F; // 向后加速
                thrust = 1.5F;
                accel = true;
            } else if (Controller.isAcceleratingBackwards()) {
                accelerateAngle = 0.0F; // 向前加速（倒车）
                thrust = 1.5F;
                accel = true;
            } else if (Controller.isDecelerating()) {
                // 减速时向加速度反方向偏转
                accelerateAngle = (NEUTRAL_ANGLE + 180f) % 360f; // 反方向
                thrust = 0.8F;
                accel = true;
            }

            // 检测平移状态
            if (Controller.isStrafingLeft()) {
                accelerateAngle = -90.0F;
                thrust = Math.max(1.0F, thrust);
                accel = true;
            } else if (Controller.isStrafingRight()) {
                accelerateAngle = 90.0F;
                thrust = Math.max(1.0F, thrust);
                accel = true;
            }

            // 检测转向状态
            if (Controller.isTurningRight()) {
                turnAngle = TURN_RIGHT_ANGLE;
                thrust = Math.max(1.0F, thrust);
                turn = true;
            } else if (Controller.isTurningLeft()) {
                turnAngle = MathUtils.clampAngle(180.0F + TURN_RIGHT_ANGLE);
                thrust = Math.max(1.0F, thrust);
                turn = true;
            }

            // 计算最终角度
            float finalAngle;
            if (thrust > 0.0F) {
                if (!turn) {
                    // 只有加速/减速
                    finalAngle = accelerateAngle;
                } else if (!accel) {
                    // 只有转向
                    finalAngle = turnAngle;
                } else {
                    // 同时加速和转向 - 使用合成角度
                    float clampedThrustToTurn = THRUST_TO_TURN * Math.min(1.0F, Math.abs(ship.getAngularVelocity()) / 10.0F);
                    clampedThrustToTurn = smooth(clampedThrustToTurn);

                    // 合成加速和转向角度
                    float combinedAngle = NEUTRAL_ANGLE;
                    combinedAngle = MathUtils.clampAngle(combinedAngle + (1.0F - clampedThrustToTurn) *
                            MathUtils.getShortestRotation(NEUTRAL_ANGLE, accelerateAngle));
                    combinedAngle = MathUtils.clampAngle(combinedAngle + clampedThrustToTurn *
                            MathUtils.getShortestRotation(NEUTRAL_ANGLE, turnAngle));

                    finalAngle = combinedAngle;
                }
            } else {
                finalAngle = NEUTRAL_ANGLE;
            }

            // 将物理计算的角度转换为偏移量
            float needAngle = MathUtils.getShortestRotation(NEUTRAL_ANGLE, finalAngle);
            // 限制在武器弧范围内
            needAngle = Math.max(-arc, Math.min(arc, needAngle));

            // 原有的延迟逻辑
            ship.getAngularVelocity();
            ship.getAngularVelocity();

            // 应用延迟逻辑到计算出的角度
            float target_ = engineDefAngle + needAngle;

            if (target_ != engineDefAngle) {
                if (lastfacing != target_) {
                    if (turningDelay < 0.2f) {
                        turningDelay += amount;
                        target = engineDefAngle;
                        lastfacing = engineDefAngle;
                    } else {
                        turningDelay = 0f;
                        target = target_;
                        lastfacing = target_;
                    }
                } else {
                    turningDelay = 0f;
                    target = target_;
                }
            } else {
                target = engineDefAngle;
                turningDelay = 0f;
                lastfacing = engineDefAngle;
            }
        }

        // 应用角度变化
        float ang = normalizeAngle(CombinedEngine.getEngineSlot().getAngle() - target);
        if (Math.abs(ang) < getTurnRate() * amount) {
            CombinedEngine.getEngineSlot().setAngle(target);
        } else {
            float angle;
            if (ang > 0) {
                angle = CombinedEngine.getEngineSlot().getAngle() - (getTurnRate() * amount);
            } else {
                angle = CombinedEngine.getEngineSlot().getAngle() + (getTurnRate() * amount);
            }
            CombinedEngine.getEngineSlot().setAngle(angle);
        }
    }

    // === 新增：平滑函数 ===
    public float smooth(float x) {
        return 0.5F - (float)(Math.cos((double)x * Math.PI) / (double)2.0F);
    }

    @Override
    public Acc getAcc(ShipAPI ship) {
        return AccelType;
    }

    @Override
    public float getTurnRate() {
        return turnrate;
    }

    public static float normalizeAngle(float ang) {
        while ((ang > 180f || ang < -180f)) {
            ang = normalize(ang);
        }
        return ang;
    }

    private static float normalize(float ang) {
        if (ang > 180f) {
            ang = ang - 360f;
        } else if (ang < -180f) {
            ang = ang + 360f;
        }
        return ang;
    }
}