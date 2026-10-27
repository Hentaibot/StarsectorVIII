// 包路径：远行星号游戏API实现中的战斗威胁包
package data.scripts.shipsystems.ai;

// 导入全局游戏实例类

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.util.IntervalUtil;
import data.scripts.VIII_threat.VIII_RoilingSwarmEffect;
import data.scripts.shipsystems.VIII_ConstructionSwarmSystemScript;
import org.lwjgl.util.vector.Vector2f;

/**
 * 建造群系统AI脚本
 * 控制舰船“建造群系统”的自动使用逻辑，根据碎片数量、部署点、幅能水平等条件决定是否激活系统
 */
public class VIII_ConstructionSwarmSystemAI implements ShipSystemAIScript {

	// 正常使用系统所需的剩余部署点阈值（35点）
	public static float REQUIRED_DP_FOR_NORMAL_USE = 35;

	// 当前舰船引用
	protected ShipAPI ship;
	// 战斗引擎引用
	protected CombatEngineAPI engine;
	// 舰船AI标志（用于获取威胁目标等）
	protected ShipwideAIFlags flags;
	// 当前舰船系统（建造群系统）
	protected ShipSystemAPI system;
	// 注释掉的字段：script = ConstructionSwarmSystemScript（因类加载顺序问题无法在此处初始化）

	// 计时器：每隔0.5~1秒评估一次是否使用系统
	protected IntervalUtil tracker = new IntervalUtil(0.5f, 1f);

	// 持续使用系统的时间（秒），>0时强制使用
	protected float keepUsingFor = 0f;
	// 碎片数量处于高水平的累计时间（秒）
	protected float timeSpentAtHighFragmentLevel = 0f;

	/**
	 * 初始化AI（由游戏引擎在舰船创建时调用）
	 * @param ship   舰船对象
	 * @param system 舰船系统对象
	 * @param flags  舰船AI标志
	 * @param engine 战斗引擎
	 */
	public void init(ShipAPI ship, ShipSystemAPI system, ShipwideAIFlags flags, CombatEngineAPI engine) {
		this.ship = ship;           // 保存舰船引用
		this.flags = flags;         // 保存AI标志
		this.engine = engine;       // 保存战斗引擎
		this.system = system;       // 保存系统对象

		// 无法在此处直接获取系统脚本，因为类加载顺序依赖会导致游戏启动崩溃
		// script = (ConstructionSwarmSystemScript)system.getScript();
	}

	/**
	 * 每帧更新AI决策（由游戏引擎每帧调用）
	 * @param amount             时间增量（秒）
	 * @param missileDangerDir   导弹危险方向（未使用）
	 * @param collisionDangerDir 碰撞危险方向（未使用）
	 * @param target             当前主要攻击目标（未使用）
	 */
	public void advance(float amount, Vector2f missileDangerDir, Vector2f collisionDangerDir, ShipAPI target) {
		// 推进间隔计时器
		tracker.advance(amount);

		// 减少持续使用系统的剩余时间
		keepUsingFor -= amount;

		// 如果计时器间隔未到，则跳过本次决策
		if (tracker.intervalElapsed()) {
			// 如果系统处于冷却中，不能使用
			if (system.getCooldownRemaining() > 0) return;
			// 如果系统弹药已耗尽，不能使用
			if (system.isOutOfAmmo()) return;
			// 如果系统已经激活，无需再次激活
			if (system.isActive()) return;

			// 获取系统脚本（建造群系统具体逻辑）
			VIII_ConstructionSwarmSystemScript script = (VIII_ConstructionSwarmSystemScript)system.getScript();
			// 如果系统不可用（例如碎片群未就绪），则返回
			if (!script.isUsable(system, ship)) {
				return;
			}

			// 获取舰船所属阵营的舰队管理器
			CombatFleetManagerAPI manager = Global.getCombatEngine().getFleetManager(ship.getOriginalOwner());
			int dpLeft = 0;
			if (manager != null) {
				// 计算剩余可用的部署点（最大强度 - 当前强度）
				dpLeft = manager.getMaxStrength() - manager.getCurrStrength();
			}

			// 获取当前舰船的CR值（战备）
			float cr = ship.getCurrentCR();
			// 获取软幅能水平（0-1）
			float softFlux = ship.getFluxLevel();
			// 获取硬幅能水平（0-1）
			float hardFlux = ship.getHardFluxLevel();

			// 如果CR过低（基础0.2 + 硬幅能水平*0.2），则不能使用系统（防止CR崩溃）
			if (cr < 0.2f + hardFlux * 0.2f) {
				return;
			}

			// 获取舰船周围的翻涌群特效（碎片群）
			VIII_RoilingSwarmEffect swarm = VIII_RoilingSwarmEffect.getSwarmFor(ship);
			if (swarm == null) return;

			// 当前活跃的碎片数量
			int active = swarm.getNumActiveMembers();
			// 系统需要维持的最大碎片数量
			int max = swarm.getNumMembersToMaintain();

			// 如果当前碎片数量达到最大值的90%以上
			if (active >= max * 0.9f) {
				// 累计高水平碎片持续时间
				timeSpentAtHighFragmentLevel += tracker.getIntervalDuration();
			} else {
				// 否则重置计时
				timeSpentAtHighFragmentLevel = 0f;
			}

			// 条件：碎片数量已满或高水平持续超过10秒，且剩余部署点足够（>=35）
			if ((active >= max || timeSpentAtHighFragmentLevel >= 10f) && dpLeft >= REQUIRED_DP_FOR_NORMAL_USE) {
				// 持续使用系统3~5秒（随机）
				keepUsingFor = 3f + (float) Math.random() * 2f;
			}

			// 如果持续使用时间已用完，但幅能水平过高（硬幅能>0.5或软幅能>0.9），则强制再使用0.5秒（紧急泄能？）
			if (keepUsingFor <= 0f && (hardFlux > 0.5f || softFlux > 0.9f)) {
				keepUsingFor = 0.5f;
			}

			// 如果持续使用时间大于0，则下达使用系统的命令
			if (keepUsingFor > 0f) {
				ship.giveCommand(ShipCommand.USE_SYSTEM, null, 0);
			}
		}
	}
}