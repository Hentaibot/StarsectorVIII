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

/**
 * @author 49747
 */
public class PCsubEngine implements EveryFrameWeaponEffectPlugin, EngineController {

    ShipEngineAPI CombinedEngine = null;
    private float enginenowLenght = 0f;
    private float target = 180;
    private float engineDefAngle = 180f;
    private boolean noEngine = false;
    private Side side = Side.MIDDLE;
    private Acc AccelType = Acc.None;
    private float turnrate = 0;

    @Override
    public float getTarget() {
        return target;
    }

    @Override
    public float getDefault() {
        return engineDefAngle;
    }

    @Override
    public Acc getAcc(ShipAPI ship) {
        return AccelType;
    }

    private enum Side {
        LEFT,
        MIDDLE,
        RIGHT
    }

    private float angularVel = 0;

    @Override
    public float getTurnRate() {
        return turnrate;
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
        if (CombinedEngine == null && !noEngine) {
            for (ShipEngineAPI eng : weapon.getShip().getEngineController().getShipEngines()) {
                if (Misc.getDistance(weapon.getLocation(), eng.getLocation()) < 1f) {
                    CombinedEngine = eng;
                    ship.setCustomData(weapon.getSlot().getId().substring(0, 2) + "Engine", this);
                    //    engineDefLength = eng.getEngineSlot().getLength();
                    engineDefAngle = eng.getEngineSlot().getAngle();
                    if (weapon.getSlot().getId().contains("Left")) {
                        side = Side.LEFT;
                    } else if (weapon.getSlot().getId().contains("Right")) {
                        side = Side.RIGHT;
                    }
                    enginenowLenght = 0.4f;
                    target = weapon.getSlot().getAngle();
                    turnrate = weapon.getTurnRate();
                    break;
                }
            }
            if (CombinedEngine == null) {
                noEngine = true;
            }
            angularVel = ship.getAngularVelocity();
        }
        if (CombinedEngine == null) {
            return;
        }
        if (CombinedEngine.isDisabled()) {
            return;
        }
        ShipEngineControllerAPI Controller = ship.getEngineController();
        float needLength;
        boolean stopTurnLeft = false;
        boolean stopTurnRight = false;
        if (angularVel >= 0) {
            if (ship.getAngularVelocity() < angularVel) {
                stopTurnLeft = true;
            }
        } else {
            if (ship.getAngularVelocity() > angularVel) {
                stopTurnRight = true;
            }
        }
        angularVel = ship.getAngularVelocity();
        if (ship.getTravelDrive().isActive()) {
            needLength = 0;
        } else {
            if (Controller.isTurningLeft() || stopTurnRight) {
                if (side == Side.LEFT) {
                    needLength = 0.2f;
                    AccelType = Acc.DeAcc;
                    if (Controller.isAccelerating() || Controller.isStrafingRight()) {
                        needLength = 0.2f;
                        AccelType = Acc.Acc;
                    } else if (Controller.isDecelerating() || Controller.isAcceleratingBackwards()) {
                        needLength = 1f;  // 修改：减速时增加火焰长度
                        AccelType = Acc.DeAcc;
                    }

                } else if (side == Side.RIGHT) {
                    needLength = 0.2f;
                    AccelType = Acc.Acc;
                    if (Controller.isDecelerating() || Controller.isAcceleratingBackwards()) {
                        needLength = 1f;  // 修改：减速时保持较高火焰长度
                        AccelType = Acc.DeAcc;
                    }
                } else {
                    needLength = 0.4f;
                    AccelType = Acc.None;
                    if (Controller.isAccelerating()) {
                        needLength = 0.3f;
                        AccelType = Acc.Acc;
                    } else if (Controller.isDecelerating() || Controller.isAcceleratingBackwards()) {
                        needLength = 1f;  // 修改：减速时增加火焰长度
                        AccelType = Acc.DeAcc;
                    }
                }
            } else if (Controller.isTurningRight() || stopTurnLeft) {
                if (side == Side.LEFT) {
                    needLength = 0.3f;
                    AccelType = Acc.Acc;
                    if (Controller.isDecelerating() || Controller.isAcceleratingBackwards()) {
                        needLength = 1f;  // 修改：减速时保持较高火焰长度
                        AccelType = Acc.DeAcc;
                    }
                } else if (side == Side.RIGHT) {
                    needLength = 0.2f;
                    AccelType = Acc.DeAcc;
                    if (Controller.isAccelerating() || Controller.isStrafingLeft()) {
                        needLength = 0.2f;
                        AccelType = Acc.Acc;
                    } else if (Controller.isDecelerating() || Controller.isAcceleratingBackwards()) {
                        needLength = 1f;  // 修改：减速时增加火焰长度
                        AccelType = Acc.DeAcc;
                    }
                } else {
                    needLength = 0.5f;
                    AccelType = Acc.None;
                    if (Controller.isAccelerating()) {
                        needLength = 0.3f;
                        AccelType = Acc.Acc;
                    } else if (Controller.isDecelerating() || Controller.isAcceleratingBackwards()) {
                        needLength = 1f;  // 修改：减速时增加火焰长度
                        AccelType = Acc.DeAcc;
                    }
                }
            } else {
                if (side == Side.LEFT) {
                    if (ship.getEngineController().isAccelerating() || Controller.isStrafingRight()) {
                        AccelType = Acc.Acc;
                        needLength = 0.3f;
                    } else if (ship.getEngineController().isDecelerating() || ship.getEngineController().isAcceleratingBackwards()) {
                        needLength = 1f;  // 修改：减速时增加火焰长度
                        AccelType = Acc.DeAcc;
                    } else {
                        needLength = 0.5f;
                        AccelType = Acc.None;
                    }
                } else if (side == Side.RIGHT) {
                    if (ship.getEngineController().isAccelerating() || Controller.isStrafingLeft()) {
                        AccelType = Acc.Acc;
                        needLength = 0.3f;
                    } else if (ship.getEngineController().isDecelerating() || ship.getEngineController().isAcceleratingBackwards()) {
                        needLength = 1f;  // 修改：减速时增加火焰长度
                        AccelType = Acc.DeAcc;
                    } else {
                        needLength = 0.5f;
                        AccelType = Acc.None;
                    }
                } else {
                    if (Controller.isAccelerating()) {
                        needLength = 0.3f;
                        AccelType = Acc.Acc;
                    } else if (Controller.isDecelerating() || Controller.isAcceleratingBackwards()) {
                        needLength = 1f;  // 修改：减速时增加火焰长度
                        AccelType = Acc.DeAcc;
                    } else {
                        needLength = 0.5f;
                        AccelType = Acc.None;
                    }
                }
            }
        }
        if (Math.abs(enginenowLenght - needLength) < amount * 2) {
            enginenowLenght = needLength;
        } else {
            if (needLength - enginenowLenght > 0) {
                enginenowLenght += amount;
            } else {
                enginenowLenght -= amount;
            }
        }
        ship.getEngineController().setFlameLevel(CombinedEngine.getEngineSlot(), enginenowLenght);
    }

}