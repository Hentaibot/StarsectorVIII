// 包路径：远行星号游戏API实现中的战斗威胁包
package data.scripts.VIII_threat;

// 导入游戏状态枚举

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.impl.combat.threat.RoilingSwarmEffect;

/**
 * 碎片武器接口
 * 所有“碎片”类型的武器（即消耗碎片群成员的武器）需要实现此接口，
 * 用于声明每次开火所需的碎片数量，并在碎片不足时显示警告信息。
 */
public interface VIII_FragmentWeapon {

	/**
	 * 获取每次开火（或每次使用）需要消耗/使用的碎片数量。
	 * 用于计算当前舰船的碎片群是否足够支持该武器正常开火。
	 * @return 需要的碎片数量
	 */
	public int getNumFragmentsToFire();

	/**
	 * 默认方法：当碎片不足时，在玩家舰船的状态栏显示警告。
	 * 该方法在武器开火检测到碎片群不存在或碎片数量不足时调用。
	 * @param w    当前武器实例
	 * @param ship 武器所属的舰船
	 */
	default void showNoFragmentSwarmWarning(WeaponAPI w, ShipAPI ship) {
		// 判断当前是否为玩家舰船（仅在战斗中且当前舰船为玩家舰船时才显示警告）
		boolean playerShip = Global.getCurrentState() == GameState.COMBAT &&
				Global.getCombatEngine() != null && Global.getCombatEngine().getPlayerShip() == ship;

		if (playerShip) {
			// 获取舰船关联的翻涌群特效（碎片群）
			VIII_RoilingSwarmEffect swarm = VIII_RoilingSwarmEffect.getSwarmFor(ship);
			// 如果碎片群不存在（为空）且舰船已完全部署超过0.1秒，则显示警告
			if (swarm == null && ship.getFullTimeDeployed() > 0.1f) {
				// 在玩家舰船的状态栏中维护一条状态信息：显示“需要碎片群”
				Global.getCombatEngine().maintainStatusForPlayerShip(w.getSpec(),
						Global.getSettings().getSpriteName("ui", "icon_tactical_fragment_swarm"),
						w.getDisplayName(),
						"需要纳米碎片",   // 提示文字：需要碎片群
						true);                   // true表示这是一个负面/警告状态
			}
		}
	}
}