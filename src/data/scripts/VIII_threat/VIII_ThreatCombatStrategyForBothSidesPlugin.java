// 包路径：远行星号游戏API实现中的战斗威胁包
package data.scripts.VIII_threat;

// 导入游戏状态枚举

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.input.InputEventAPI;

import java.util.List;

/**
 * 双方威胁派系的战斗策略插件
 * 在战斗中同时为玩家侧和敌人侧的威胁派系舰队提供AI策略
 */
public class VIII_ThreatCombatStrategyForBothSidesPlugin extends BaseEveryFrameCombatPlugin {

	// 玩家侧（阵营0）的威胁战斗策略AI实例
	protected VIII_ThreatCombatStrategyAI playerSide;
	// 敌人侧（阵营1）的威胁战斗策略AI实例
	protected VIII_ThreatCombatStrategyAI enemySide;

	/**
	 * 构造函数：创建玩家侧和敌人侧的策略AI实例
	 */
	public VIII_ThreatCombatStrategyForBothSidesPlugin() {
		playerSide = new VIII_ThreatCombatStrategyAI(0);   // 阵营0（玩家）的策略AI
		enemySide = new VIII_ThreatCombatStrategyAI(1);    // 阵营1（敌人）的策略AI
	}

	/**
	 * 每帧更新方法（由战斗引擎每帧调用）
	 * @param amount 时间增量（秒）
	 * @param events 输入事件列表（未使用）
	 */
	@Override
	public void advance(float amount, List<InputEventAPI> events) {
		// 仅在战斗状态下执行（避免在战役或其他状态下误调）
		if (Global.getCurrentState() != GameState.COMBAT) return;

		// 更新玩家侧威胁策略AI
		playerSide.advance(amount);
		// 更新敌人侧威胁策略AI
		enemySide.advance(amount);
	}
}