// 包路径：远行星号游戏API实现中的战斗威胁包
package data.scripts.shipsystems;

// 导入舰船API接口
import com.fs.starfarer.api.combat.ShipAPI;

/**
 * 能量鞭笞激活系统接口
 * 定义了被能量鞭笞（Energy Lash）命中时应该触发的行为，以及评估当前系统对鞭笞激活的“有用程度”
 */
public interface VIII_EnergyLashActivatedSystem {
	// 以下是被注释掉的方法：在舰船创建后重置系统状态（未使用）
	// public void resetAfterShipCreation(ShipAPI ship);

	/**
	 * 当被能量鞭笞命中时调用
	 * @param overseer 发起鞭笞的舰船（监督者）
	 * @param ship     被命中的舰船（即拥有该系统的舰船）
	 */
	public void hitWithEnergyLash(ShipAPI overseer, ShipAPI ship);

	/**
	 * 评估当前系统被能量鞭笞激活的“有用程度”，范围0~1。
	 * 数值越高，表示AI越希望该系统被鞭笞激活（例如弹药不足、急需充能等）
	 * @param overseer 监督者舰船（可能为null或同一条船）
	 * @param ship     拥有该系统的舰船
	 * @return 有用程度（0~1）
	 */
	public float getCurrentUsefulnessLevel(ShipAPI overseer, ShipAPI ship);
}