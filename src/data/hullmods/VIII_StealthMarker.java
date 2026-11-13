package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

/**
 * 隐秘行动模组用到的隐藏标记船插。
 *
 * <p>转换型号时由 {@link VIII_StealthOps} 一并挂到舰上。主船插被卸下之后它仍然留在变体上，
 * 引擎重建统计（装配界面确认、舰队同步、进战斗前）时照样会调用它的钩子，由它把舰体换回 a 型。
 * 这样「卸下就回退」不再依赖舰队钩子，生涯模式、战役模式和独立装配里都成立。</p>
 */
public class VIII_StealthMarker extends BaseHullMod {

    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        VIII_StealthOps.markerTick(stats.getFleetMember(), stats.getVariant());
    }

    @Override
    public void advanceInCampaign(FleetMemberAPI member, float amount) {
        VIII_StealthOps.markerTick(member, member == null ? null : member.getVariant());
    }

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        return true;
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        return null;
    }
}
