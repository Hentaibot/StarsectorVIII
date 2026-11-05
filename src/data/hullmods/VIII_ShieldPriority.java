package data.hullmods;

import com.fs.starfarer.api.combat.ShipAPI;

/**
 * 神经元力场（VIII_Shield）的生效层级判定。
 * 原工程（通用武器公司）用一张表管理六套镀层与屏障系统的先后顺序；
 * 本 mod 只有神经元力场这一套护盾改造，因此只需判断它自己这一层是否生效，
 * 判定条件与原实现中的 LAYER_MSR 一致：舰船（或主体）挂有护盾脚本，
 * 并且主体的护盾处于开启状态。
 */
public final class VIII_ShieldPriority {

    public static final int LAYER_NONE = 0;
    public static final int LAYER_MSR = 6;

    private VIII_ShieldPriority() {
    }

    /**
     * 判断指定层当前是否可以写入减伤倍率。
     *
     * @param ship  目标舰船（可以是模块）
     * @param layer 层级常量
     * @return 该层是否生效
     */
    public static boolean canApply(ShipAPI ship, int layer) {
        if (ship == null || layer != LAYER_MSR) {
            return false;
        }
        if (!hasShield(ship)) {
            return false;
        }
        ShipAPI parent = getParent(ship);
        if (parent == null) {
            return false;
        }
        return parent.getShield() != null && parent.getShield().isOn();
    }

    /**
     * 舰船或其主体是否挂有神经元力场的护盾脚本。
     */
    private static boolean hasShield(ShipAPI ship) {
        if (ship == null) {
            return false;
        }
        if (VIII_Shield.VIII_ShieldScript.hasShield(ship)) {
            return true;
        }
        ShipAPI parent = getParent(ship);
        return parent != ship && VIII_Shield.VIII_ShieldScript.hasShield(parent);
    }

    /**
     * 逐级向上查找顶层舰船（模块返回其主体）。
     */
    private static ShipAPI getParent(ShipAPI ship) {
        if (ship == null) {
            return null;
        }
        ShipAPI current = ship;
        while (current.getParentStation() != null) {
            current = current.getParentStation();
        }
        return current;
    }
}
