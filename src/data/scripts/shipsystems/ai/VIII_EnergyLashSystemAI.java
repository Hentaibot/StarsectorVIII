// 包路径：远行星号游戏API实现中的战斗威胁包
package data.scripts.shipsystems.ai;

// 导入全局游戏实例类
import com.fs.starfarer.api.Global;
// 导入战斗相关接口：舰船、战斗引擎、护盾、AI标志等
import com.fs.starfarer.api.combat.*;
// 导入舰船AI标志中的具体标志常量
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;
// 导入间隔计时工具类
import com.fs.starfarer.api.util.IntervalUtil;
// 导入杂项工具类（距离、角度等计算）
import com.fs.starfarer.api.util.Misc;
// 导入加权随机选择器
import com.fs.starfarer.api.util.WeightedRandomPicker;
// 导入LWJGL二维向量类
import data.scripts.VIII_threat.VIII_ThreatCombatStrategyAI;
import data.scripts.shipsystems.VIII_EnergyLashSystemScript;
import data.scripts.VIII_threat.VIII_RoilingSwarmEffect;
import data.scripts.VIII_threat.VIII_ThreatSwarmAI;
import data.scripts.VIII_threat.VIII_VoltaicDischargeOnFireEffect;
import org.lwjgl.util.vector.Vector2f;

// 导入Java标准库中的列表类
import java.util.ArrayList;
import java.util.List;

/**
 * 能量鞭笞系统AI脚本
 * 控制能量鞭笞（Energy Lash）系统的自动使用决策，根据敌方/友方目标的重要性加权选择目标并激活系统
 */
public class VIII_EnergyLashSystemAI implements ShipSystemAIScript {

	// 拥有该AI的舰船
	protected ShipAPI ship;
	// 战斗引擎引用
	protected CombatEngineAPI engine;
	// 舰船AI标志（用于存储临时目标等）
	protected ShipwideAIFlags flags;
	// 舰船系统（能量鞭笞系统）
	protected ShipSystemAPI system;
	// 系统脚本（EnergyLashSystemScript的实例）
	protected VIII_EnergyLashSystemScript script;

	// 决策计时器：每隔0.5~1秒评估一次是否使用系统
	protected IntervalUtil tracker = new IntervalUtil(0.5f, 1f);

	/**
	 * 初始化AI（由游戏引擎在舰船创建时调用）
	 * @param ship   舰船对象
	 * @param system 舰船系统对象
	 * @param flags  舰船AI标志
	 * @param engine 战斗引擎
	 */
	public void init(ShipAPI ship, ShipSystemAPI system, ShipwideAIFlags flags, CombatEngineAPI engine) {
		this.ship = ship;
		this.flags = flags;
		this.engine = engine;
		this.system = system;

		// 获取系统脚本（具体实现能量鞭笞逻辑）
		script = (VIII_EnergyLashSystemScript)system.getScript();
	}

	/**
	 * 每帧更新AI决策（由游戏引擎每帧调用）
	 * @param amount             时间增量（秒）
	 * @param missileDangerDir   导弹危险方向（未使用）
	 * @param collisionDangerDir 碰撞危险方向（未使用）
	 * @param target             当前主要攻击目标（由上级AI传入）
	 */
	public void advance(float amount, Vector2f missileDangerDir, Vector2f collisionDangerDir, ShipAPI target) {
		// 推进计时器
		tracker.advance(amount);

		// 如果计时器间隔未到，则跳过本次决策
		if (tracker.intervalElapsed()) {
			// 系统处于冷却中 -> 不能使用
			if (system.getCooldownRemaining() > 0) return;
			// 系统弹药已耗尽 -> 不能使用
			if (system.isOutOfAmmo()) return;
			// 系统已经激活 -> 无需再次激活
			if (system.isActive()) return;
			// 舰船过载或正在排辐能 -> 不能使用系统
			if (ship.getFluxTracker().isOverloadedOrVenting()) return;

			// 获取加权随机选择器，其中包含所有可能的目标及其权重
			WeightedRandomPicker<ShipAPI> picker = getWeightedTargets(target);
			// 选出权重最高的目标（如果多个权重相同，随机选一个）
			ShipAPI pick = picker.getItemWithHighestWeight();
			if (pick != null) {
				// 在AI标志中设置自定义标志CUSTOM1，持续1.5秒，并将目标存为关联对象
				ship.getAIFlags().setFlag(AIFlags.CUSTOM1, 1.5f, pick);
				// 命令舰船立即使用系统
				ship.giveCommand(ShipCommand.USE_SYSTEM, null, 0);
				// 调试输出（正式版可注释）
				//System.out.println("Lash target: " + pick);
			}
		}
	}

	/**
	 * 获取所有可能的鞭笞目标（敌方或友方中满足条件的舰船）
	 * @return 候选目标列表
	 */
	public List<ShipAPI> getPossibleTargets() {
		List<ShipAPI> result = new ArrayList<>();
		CombatEngineAPI engine = Global.getCombatEngine();

		// 遍历战斗中的所有舰船
		List<ShipAPI> ships = engine.getShips();
		for (ShipAPI other : ships) {
			if (other == ship) continue;                           // 跳过自己
			if (!script.isValidLashTarget(ship, other)) continue;  // 系统脚本判定无效目标
			if (!script.isInRange(ship, other)) continue;          // 超出射程
			result.add(other);
		}
		return result;
	}

	/**
	 * 根据目标（传入的当前攻击目标）构建加权随机选择器，为每个候选目标计算权重
	 * @param shipTarget 舰船当前的主要攻击目标（由上级AI传入，可能为null）
	 * @return 加权随机选择器，可用于随机选取或获取最高权重目标
	 */
	public WeightedRandomPicker<ShipAPI> getWeightedTargets(ShipAPI shipTarget) {
		WeightedRandomPicker<ShipAPI> picker = new WeightedRandomPicker<>();

		// 遍历所有可能的鞭笞目标
		for (ShipAPI other : getPossibleTargets()) {
			float w = 0f;  // 权重初始为0

			// 情况1：目标是友方（同阵营）
			if (ship.getOwner() == other.getOwner()) {
				// 如果友方舰船是“攻击群”（ThreatSwarmAI判定）
				if (VIII_ThreatSwarmAI.isAttackSwarm(other)) {
					VIII_RoilingSwarmEffect swarm = VIII_RoilingSwarmEffect.getSwarmFor(other);
					// 如果友方拥有翻涌群且未处于相位模式，则给予0.5权重；否则跳过（权重0）
					if (swarm != null && !VIII_VoltaicDischargeOnFireEffect.isSwarmPhaseMode(other)) {
						w = 0.5f;
					} else {
						continue; // 跳过该目标
					}
				} else {
					// ========== 修改：根据舰船大小设置基础权重 ==========
					ShipAPI.HullSize hullSize = other.getHullSize();
					if (hullSize == ShipAPI.HullSize.FIGHTER) {
						w = 0f; // 战斗机基础权重0（通常不会进入此分支）
					} else if (hullSize == ShipAPI.HullSize.FRIGATE) {
						w = 0.55f;
					} else if (hullSize == ShipAPI.HullSize.DESTROYER) {
						w = 0.5f;
					} else if (hullSize == ShipAPI.HullSize.CRUISER) {
						w = 0.45f;
					} else if (hullSize == ShipAPI.HullSize.CAPITAL_SHIP) {
						continue; // 主力舰不选择
					} else if ((VIII_ThreatCombatStrategyAI.isOverseer(other))) {
						continue; // 督军不选择
					} else {
						w = 0f; // 未知大小默认0
					}

					// ========== 新增：根据与最近敌方舰船的距离调整权重 ==========
					// 计算友方舰船与最近敌方舰船的距离
					float minDistToEnemy = Float.MAX_VALUE;
					List<ShipAPI> allShips = Global.getCombatEngine().getShips();
					for (ShipAPI otherShip : allShips) {
						if (otherShip.getOwner() != ship.getOwner() && otherShip.isAlive() && !otherShip.isHulk()) {
							float dist = Misc.getDistance(other.getLocation(), otherShip.getLocation());
							dist -= other.getCollisionRadius() + otherShip.getCollisionRadius();
							if (dist < 0) dist = 0;
							if (dist < minDistToEnemy) {
								minDistToEnemy = dist;
							}
						}
					}
					// 如果找到了敌方舰船，根据距离给予额外权重（距离越近越高）
					if (minDistToEnemy < Float.MAX_VALUE) {
						// 每100距离增加0.02，最大加成距离1500
						float extra = Math.max(0, (2500f - minDistToEnemy) / 100f * 0.02f);
						w += extra;
					}
					// ========== 修改结束 ==========
				}
			}
			// 情况2：目标是敌方
			else {
				// 获取敌方护盾状态
				ShieldAPI targetShield = other.getShield();
				// 判断敌方护盾是否朝向己方（护盾开启且面向角度覆盖我方位置）
				boolean targetShieldsFacingUs = targetShield != null &&
						targetShield.isOn() &&
						Misc.isInArc(targetShield.getFacing(), Math.max(30f, targetShield.getActiveArc()),
								other.getLocation(), ship.getLocation());
				// 如果护盾朝向己方，但能量鞭笞系统脚本的伤害值<=0（无法造成伤害），则跳过该目标
				if (targetShieldsFacingUs && VIII_EnergyLashSystemScript.DAMAGE <= 0) continue;

				// 计算舰船之间的距离（表面到表面，减去半径）
				float dist = Misc.getDistance(ship.getLocation(), other.getLocation());
				dist -= ship.getCollisionRadius() + other.getCollisionRadius();
				if (dist < 0) dist = 0;

				// 如果敌方正是当前主要攻击目标，额外增加0.25权重
				if (other == shipTarget) {
					w += 0.25f;
				}
				// 距离小于1000像素时，距离越近权重越高（线性插值，最大0.5）
				if (dist < 1000f) {
					w += (1f - (dist / 1000f)) * 0.5f;
				}
				// 如果敌方处于相位状态，增加0.5权重
				if (other.isPhased()) {
					w += 0.5f;
				}
				// 基础权重0.01，确保每个目标至少有一点权重（防止被完全忽略）
				w += 0.01f;
			}
			// 将目标及其权重加入选择器
			picker.add(other, w);
		}
		return picker;
	}
}