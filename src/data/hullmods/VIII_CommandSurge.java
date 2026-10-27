package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;

import data.scripts.listeners.VIII_CommandSurgeListener;

/**
 * 督军（VIII_command）的隐藏内置船插：只负责给舰船挂上
 * {@link VIII_CommandSurgeListener}，本身不改任何 stats。
 * <p>
 * 用船插而不是全局扫描，是因为这张舰体只内置这一个船插，
 * 换舰体/换变体都不会误挂，也不需要每帧遍历战场。
 */
public class VIII_CommandSurge extends BaseHullMod {

    /** 唯一会被挂上监听器的舰体 id */
    public static final String HULL_ID = "VIII_command";

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        if (ship == null || ship.getHullSpec() == null) {
            return;
        }
        if (!HULL_ID.equals(ship.getHullSpec().getHullId())) {
            return;
        }
        if (ship.hasListenerOfClass(VIII_CommandSurgeListener.class)) {
            return;
        }
        ship.addListener(new VIII_CommandSurgeListener(ship));
    }
}
