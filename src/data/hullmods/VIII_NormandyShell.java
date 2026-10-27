package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;

import data.scripts.listeners.VIII_NormandyShellListener;

/**
 * 诺曼底（VIII_normandy）的隐藏内置船插：只负责给舰船挂上
 * {@link VIII_NormandyShellListener}（受击 30 倍时流应激），本身不改任何 stats。
 * <p>
 * 战术系统本身（VIII_normandy_temporalshell）依旧走原版时流之壳的脚本，
 * 这张船插只负责被动部分，所以系统开与不开都不影响监听器的挂载。
 */
public class VIII_NormandyShell extends BaseHullMod {

    /** 唯一会被挂上监听器的舰体 id */
    public static final String HULL_ID = "VIII_normandy";

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        if (ship == null || ship.getHullSpec() == null) {
            return;
        }
        if (!HULL_ID.equals(ship.getHullSpec().getHullId())) {
            return;
        }
        if (ship.hasListenerOfClass(VIII_NormandyShellListener.class)) {
            return;
        }
        ship.addListener(new VIII_NormandyShellListener(ship));
    }
}
