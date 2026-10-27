// 包路径：远行星号游戏API实现中的战斗威胁包
package data.scripts.VIII_threat;

// 导入武器接口
import com.fs.starfarer.api.combat.WeaponAPI;

/**
 * 表示一种无法手动控制、自动发射碎片群（Swarm）的武器接口。
 * 此类武器由AI或系统自动触发，用于发射攻击群、建造群等。
 */
public interface VIII_SwarmLaunchingWeapon {

	/**
	 * 获取该武器在一次开火中“偏好”消耗的碎片数量。
	 * 该数值用于AI决策和碎片资源管理，可能小于实际所需碎片数，
	 * 用于在资源紧张时优先保障关键武器的发射。
	 * @param weapon 对应的武器实例
	 * @return 偏好的碎片消耗数量（整数）
	 */
	int getPreferredNumFragmentsToFire(WeaponAPI weapon);
}