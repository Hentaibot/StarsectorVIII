package data.scripts.VIII_threat;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.WeightedRandomPicker;
import data.scripts.shipsystems.VIII_ConstructionSwarmSystemScript.VIII_SwarmConstructionData;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.List;

/**
 * ThreatSwarmAI - 威胁蜂群人工智能
 * 这个类实现了威胁派系蜂群单位的智能行为，包括：
 * 1. 群体运动（Flocking Behavior） - 模拟鸟类/鱼群的集体运动
 * 2. 不同类型蜂群的特定行为 - 攻击、建造、回收
 * 3. 武器组管理和目标选择
 * 4. 与蜂群效果（RoilingSwarmEffect）的深度集成
 * 这个AI控制三种类型的蜂群：
 * 1. 攻击蜂群（Attack Swarm）：战斗单位，具有多种武器策略
 * 2. 建造蜂群（Construction Swarm）：用于建造新舰船的单位
 * 3. 回收蜂群（Reclamation Swarm）：回收资源返回建造单位的单位
 */
public class VIII_ThreatSwarmAI implements ShipAIPlugin {

	// ===================== 常量定义 =====================

	// 相同编队吸引器的最大范围（非常大的值，几乎无限制）
	public static float ATTRACTOR_RANGE_MAX_SAME_WING = 1000000f;

	// 普通吸引器的最大范围
	public static float ATTRACTOR_RANGE_MAX = 500f;

	// 凝聚范围的最小和最大值（控制蜂群成员之间的聚集程度）
	public static float COHESION_RANGE_MIN = 150f;
	public static float COHESION_RANGE_MAX = 300f;

	// 排斥范围的最小和最大值（控制蜂群成员之间的最小距离）
	public static float REPEL_RANGE_MIN = 0f;
	public static float REPEL_RANGE_MAX = 150f;

	// 最大目标选择范围
	public static float MAX_TARGET_RANGE = 3000f;

	// ===================== 蜂群类型判断方法 =====================

	/**
	 * 判断是否为攻击蜂群
	 * @param ship 要检查的舰船
	 * @return 如果是攻击蜂群返回true
	 */
	public static boolean isAttackSwarm(ShipAPI ship) {
		return ship != null && ship.getVariant().getHullVariantId().equals(VIII_SwarmLauncherEffect.ATTACK_SWARM_VARIANT);
	}

	/**
	 * 判断是否为建造蜂群
	 * @param ship 要检查的舰船
	 * @return 如果是建造蜂群返回true
	 */
	public static boolean isConstructionSwarm(ShipAPI ship) {
		return ship != null && ship.getVariant().getHullVariantId().equals(VIII_SwarmLauncherEffect.CONSTRUCTION_SWARM_VARIANT);
	}

	/**
	 * 判断是否为回收蜂群
	 * @param ship 要检查的舰船
	 * @return 如果是回收蜂群返回true
	 */
	public static boolean isReclamationSwarm(ShipAPI ship) {
		return ship != null && ship.getVariant().getHullVariantId().equals(VIII_SwarmLauncherEffect.RECLAMATION_SWARM_VARIANT);
	}

	/**
	 * 新增判断方法：是否为暴雪攻击群
	 */
	public static boolean isBlizzardSwarm(ShipAPI ship) {
		return ship != null &&
				ship.getVariant().getHullVariantId().equals(VIII_SwarmLauncherEffect.BLIZZARD_SWARM_VARIANT);
	}

	// ===================== 内部类定义 =====================

	/**
	 * 共享蜂群编队数据
	 * 用于在同一编队中共享数据，如共同目标
	 */
	public static class VIII_SharedSwarmWingData {
		public ShipAPI target = null;  // 编队共享的目标
	}

	/**
	 * 群体行为数据
	 * 用于计算群体运动的基本单位
	 */
	public static class VIII_FlockingData {
		public Vector2f loc;                // 位置
		public Vector2f vel;                // 速度
		public float minA;                 // 最小吸引距离
		public float maxA;                 // 最大吸引距离
		public float minR;                 // 最小排斥距离
		public float maxR;                 // 最大排斥距离
		public float repelAtAngleDist;     // 角度排斥距离
		public float minC;                 // 最小凝聚距离
		public float maxC;                 // 最大凝聚距离
		public float attractWeight;        // 吸引权重
		public float repelWeight;          // 排斥权重
		public float cohesionWeight;       // 凝聚权重
		public float facing;               // 朝向
	}

	// ===================== 常量定义 =====================

	// 启用其他武器组的概率
	public static float PROB_ENABLE_OTHER_GROUP = 0.5f;

	// ===================== 成员变量 =====================

	protected ShipwideAIFlags flags = new ShipwideAIFlags();  // AI标志
	protected ShipAPI ship;                                   // 控制的舰船

	// 各种间隔计时器
	protected IntervalUtil updateInterval = new IntervalUtil(0.5f, 1.5f);          // 更新群体数据的间隔
	protected IntervalUtil headingInterval = new IntervalUtil(0.5f, 1.5f);         // 更新朝向的间隔
	protected IntervalUtil attackRangeMultInterval = new IntervalUtil(0.2f, 1.8f); // 攻击范围倍率更新间隔
	protected IntervalUtil reclamationReturnInterval = new IntervalUtil(0.2f, 1.8f); // 回收返回间隔

	protected float sinceTurnedOffFlash = 0f;  // 关闭闪光效果后的时间
	protected ShipAPI fabricator = null;       // 建造单位（回收蜂群的目标）

	protected List<VIII_FlockingData> VIIIFlockingData = new ArrayList<>();  // 群体行为数据列表
	protected float desiredHeading = 0f;       // 期望朝向
	protected float headingChangeRate = 0f;    // 朝向变化率
	protected float elapsedSincePrevHeadingUpdate = 0f;  // 上次更新朝向后的时间
	protected float attackRangeMult = 1f;      // 攻击范围倍率

	protected IntervalUtil enableOtherWeaponInterval = new IntervalUtil(5f, 15f);  // 启用其他武器间隔
	protected IntervalUtil priorityTargetPickerInterval = new IntervalUtil(1f, 3f); // 优先级目标选择间隔
	protected float enableOtherWeaponDuration = 0f;  // 启用其他武器的持续时间
	protected float elapsed = 0f;                    // 经过的总时间

	protected boolean startedConstruction = false;    // 是否已开始建造
	private VIII_SwarmConstructionData constructionData;   // 建造数据
	private VIII_ThreatShipConstructionScript constructionScript;  // 建造脚本

	protected boolean attackSwarm;     // 是否为攻击蜂群
	protected boolean constructionSwarm; // 是否为建造蜂群
	protected boolean reclamationSwarm; // 是否为回收蜂群

	// ---- 新增：暴雪群类型标识 ----
	protected boolean blizzardSwarm;     // 是否为暴雪攻击群

	// ===================== 构造方法 =====================

	/**
	 * 构造函数
	 * @param ship 要控制的蜂群舰船
	 */
	public VIII_ThreatSwarmAI(ShipAPI ship) {
		this.ship = ship;

		// 确定蜂群类型
		attackSwarm = isAttackSwarm(ship);
		constructionSwarm = isConstructionSwarm(ship);
		reclamationSwarm = isReclamationSwarm(ship);
		blizzardSwarm = isBlizzardSwarm(ship);   // 新增

		// 执行初始设置
		doInitialSetup();

		// 强制所有间隔计时器立即触发
		updateInterval.forceIntervalElapsed();
		headingInterval.forceIntervalElapsed();
		attackRangeMultInterval.forceIntervalElapsed();
		priorityTargetPickerInterval.forceIntervalElapsed();
	}

	/**
	 * 获取共享的编队数据
	 * 如果不存在则创建
	 * @return 共享编队数据
	 */
	public VIII_SharedSwarmWingData getShared() {
		if (ship.getWing() == null) return new VIII_SharedSwarmWingData();

		String key = "VIII_SharedSwarmWingData";
		VIII_SharedSwarmWingData data = (VIII_SharedSwarmWingData) ship.getWing().getCustomData().get(key);
		if (data == null) {
			data = new VIII_SharedSwarmWingData();
			ship.getWing().getCustomData().put(key, data);
		}
		return data;
	}



	// ===================== 初始设置方法 =====================

	/**
	 * 执行初始设置
	 * 根据蜂群类型配置初始状态
	 */
	protected void doInitialSetup() {
		if (attackSwarm || blizzardSwarm) {
			// 武器组索引说明：
			// 0: voltaic（伏特武器，持续攻击）
			// 1: unstable（不稳定武器，持续攻击）
			// 2: kinetic（动能武器，需要手动启用）
			// 3: seeker（寻的武器，需要手动启用）
			// 4: defabrication（解构武器，未使用）

			// 初始启用0和1组，禁用2、3、4组
			toggleOn(0);
			toggleOn(1);
			toggleOff(2);
			toggleOff(3);
			toggleOff(4);

			// 选择第6组武器（可能是特殊武器组）
			ship.giveCommand(ShipCommand.SELECT_GROUP, null, 6);
		}
	}

	/**
	 * 启用指定武器组
	 * @param groupNum 武器组索引
	 */
	protected void toggleOn(int groupNum) {
		List<WeaponGroupAPI> groups = ship.getWeaponGroupsCopy();
		if (groups.size() <= groupNum) return;
		groups.get(groupNum).toggleOn();
	}

	/**
	 * 禁用指定武器组
	 * @param groupNum 武器组索引
	 */
	protected void toggleOff(int groupNum) {
		List<WeaponGroupAPI> groups = ship.getWeaponGroupsCopy();
		if (groups.size() <= groupNum) return;
		groups.get(groupNum).toggleOff();
	}

	// ===================== 特定蜂群类型逻辑 =====================

	/**
	 * 根据蜂群类型执行特定的逻辑
	 * @param amount 时间增量
	 */
	protected void advanceForSpecificSwarmType(float amount) {
		// ===================== 攻击蜂群逻辑 =====================
		if (attackSwarm) {

			// 如果是编队长机，定期选择优先级目标
			if (ship.isWingLeader()) {
				priorityTargetPickerInterval.advance(amount);
				if (priorityTargetPickerInterval.intervalElapsed()) {
					pickPriorityTarget();
				}
			}

			// 更新攻击范围倍率
			attackRangeMultInterval.advance(amount * 0.1f);
			if (attackRangeMultInterval.intervalElapsed()) {
				updateAttackRangeMult();
			}

			// 如果其他武器组正在启用，倒计时持续时间
			if (enableOtherWeaponDuration > 0) {
				enableOtherWeaponDuration -= amount;
				if (enableOtherWeaponDuration <= 0) {
					// 持续时间结束，禁用其他武器组
					toggleOff(2);
					toggleOff(3);
					toggleOff(4);
				}
			} else {
				// 检查是否为相位模式

                // 定期决定是否启用其他武器组
				enableOtherWeaponInterval.advance(amount * 5f);
				if (enableOtherWeaponInterval.intervalElapsed()) {
					// 以一定概率启用其他武器组
					if ((float) Math.random() < PROB_ENABLE_OTHER_GROUP) {
						// 先禁用所有其他武器组
						toggleOff(2);
						toggleOff(3);
						toggleOff(4);

						// 获取当前机动目标
						ShipAPI target = (ShipAPI) flags.getCustom(AIFlags.MANEUVER_TARGET);

						// 根据目标和自身状态决定使用哪种武器
						boolean useSeeker = ship.getHullLevel() < 0.22f;  // 低血量时使用寻的武器
						boolean useKinetic = true;  // 默认使用动能武器

						// 如果目标为空或是战斗机，不使用寻的或动能武器
						if (target == null || target.isFighter()) {
							useSeeker = false;
							useKinetic = false;
						}

						// 启用相应武器组
						if (useSeeker) {
							toggleOn(3);
						} else if (useKinetic) {
							toggleOn(2);
						}

						// 设置启用持续时间
						enableOtherWeaponDuration = 0.5f + 0.5f * (float) Math.random();
					}
				}
			}
		}

		// 暴雪群：不选择目标、不启用额外武器组，仅保持被动
		if (blizzardSwarm) {
			// 不做任何主动攻击操作，所有武器组保持初始状态（持续开火）
			// 不调用 pickPriorityTarget()、updateAttackRangeMult() 等
			// 也不切换武器组
			// 空分支明确表示跳过攻击逻辑
		}

		// ===================== 建造蜂群逻辑 =====================
		if (constructionSwarm) {
			// 如果还没有获取建造数据，尝试从蜂群效果中获取
			if (constructionData == null) {
				VIII_RoilingSwarmEffect swarm = VIII_RoilingSwarmEffect.getSwarmFor(ship);
				if (swarm != null) {
					constructionData = (VIII_SwarmConstructionData) swarm.custom1;
				}
			}

			// 如果已获取建造数据且时间已到，开始建造
			if (constructionData != null) {
				if (elapsed > constructionData.preConstructionTravelTime && !startedConstruction) {
					startedConstruction = true;
					VIII_RoilingSwarmEffect swarm = VIII_RoilingSwarmEffect.getSwarmFor(ship);
					if (swarm != null) {
						// 创建建造脚本
						constructionScript = new VIII_ThreatShipConstructionScript(
								constructionData.variantId, ship, 0f, constructionData.constructionTime);
						Global.getCombatEngine().addPlugin(constructionScript);
					}
				}
			}
		}

		// ===================== 回收蜂群逻辑 =====================
		if (reclamationSwarm) {
			// 如果还没有找到建造单位，定期查找
			if (fabricator == null) {
				reclamationReturnInterval.advance(amount);
				if (reclamationReturnInterval.intervalElapsed()) {
					int owner = ship.getOriginalOwner();
					CombatEngineAPI engine = Global.getCombatEngine();

					// 查找附近的建造单位
					for (ShipAPI curr : engine.getShips()) {
						if (curr == ship || curr.getOwner() != owner) continue;
						if (curr.isHulk() || curr.getOwner() == 100) continue;
						if (!VIII_ThreatCombatStrategyAI.isFabricator(curr)) continue;

						// 检查距离是否足够近
						float dist = Misc.getDistance(curr.getLocation(), ship.getLocation());
						if (dist < curr.getCollisionRadius() + 200f) {
							// 关闭闪光效果
							VIII_RoilingSwarmEffect swarm = VIII_RoilingSwarmEffect.getSwarmFor(ship);
							swarm.params.flashFrequency = 0f;
							swarm.params.flashProbability = 0f;

							// 记录找到的建造单位
							fabricator = curr;
							break;
						}
					}
				}
			} else {
				// 如果已经找到建造单位
				sinceTurnedOffFlash += amount;
				if (sinceTurnedOffFlash > 3f) {
					CombatEngineAPI engine = Global.getCombatEngine();

					// 如果建造单位还活着，恢复其CR（战斗准备度）
					if (fabricator.isAlive()) {
						fabricator.setCurrentCR(Math.min(1f, fabricator.getCurrentCR() + 0.01f * ship.getHullLevel()));

						// 将蜂群成员转移到建造单位的蜂群中
						VIII_RoilingSwarmEffect swarm = VIII_RoilingSwarmEffect.getSwarmFor(ship);
						VIII_RoilingSwarmEffect swarmFabricator = VIII_RoilingSwarmEffect.getSwarmFor(fabricator);
						if (swarm != null && swarmFabricator != null) {
							swarm.transferMembersTo(swarmFabricator, swarm.getNumActiveMembers());
						}
					}

					// 摧毁当前蜂群舰船
					ship.setHitpoints(0f);
					ship.setSpawnDebris(false);
					engine.applyDamage(ship, ship.getLocation(), 100f, DamageType.ENERGY, 0f, true, false, ship, false);
				}
			}
		}
	}

	// ===================== 目标选择方法 =====================

	/**
	 * 选择优先级目标
	 * 只有编队长机调用此方法
	 */
	protected void pickPriorityTarget() {
		VIII_SharedSwarmWingData data = getShared();

		// 如果当前目标还活着，保持原目标
		if (data.target != null && data.target.isAlive()) {
			return;
		}

		// 创建目标选择器
		WeightedRandomPicker<ShipAPI> picker = new WeightedRandomPicker<>();
		CombatEngineAPI engine = Global.getCombatEngine();
		int owner = ship.getOriginalOwner();

		// 遍历所有舰船
		for (ShipAPI curr : engine.getShips()) {
			if (curr == ship) continue;
			if (curr.isFighter()) continue;  // 忽略战斗机
			if (curr.isHulk() || curr.getOwner() == 100) continue;  // 忽略残骸和中立单位

			// 只选择敌方且已知的舰船
			if (curr.getOwner() != owner && engine.isAwareOf(owner, curr)) {
				float weight = getShipWeight(curr);
				// 降低护卫舰的权重
				if (curr.isFrigate()) {
					weight *= 0.0001f;
				}
				picker.add(curr, weight);
			}
		}

		// 选择新目标
		data.target = picker.pick();
	}

	/**
	 * 更新攻击范围倍率
	 * 为蜂群添加一些随机性
	 */
	protected void updateAttackRangeMult() {
		attackRangeMult = 0.5f + (float) Math.random();
	}

	// ===================== 主要更新方法 =====================

	/**
	 * 每一帧调用的更新方法
	 * @param amount 时间增量
	 */
	@Override
	public void advance(float amount) {
		elapsed += amount;

		// 执行特定蜂群类型的逻辑
		advanceForSpecificSwarmType(amount);

		// 更新群体数据
		updateInterval.advance(amount);
		if (updateInterval.intervalElapsed()) {
			updateFlockingData();
		}

		// 更新期望朝向
		headingInterval.advance(amount * 5f);
		if (headingInterval.intervalElapsed()) {
			computeDesiredHeading();
			elapsedSincePrevHeadingUpdate = 0f;
		}

		// 执行移动命令
		giveMovementCommands();

		elapsedSincePrevHeadingUpdate += amount;
	}

	@Override
	public boolean needsRefit() {
		return false;
	}

	// ===================== 移动控制方法 =====================

	/**
	 * 执行移动命令
	 * 包括速度调整和朝向控制
	 */
	protected void giveMovementCommands() {
		// 如果正在建造，减速
		if (constructionScript != null && constructionScript.getShip() != null) {
			ship.giveCommand(ShipCommand.DECELERATE, null, 0);
			return;
		}

		// 调整非长机成员的速度以跟上长机
		String source = "VIII_swarm_wingman_catch_up_speed_bonus";
		MutableShipStatsAPI stats = ship.getMutableStats();

		if (ship.isWingLeader() || ship.getWingLeader() == null) {
			// 长机或没有长机，不使用速度加成
			stats.getMaxSpeed().unmodifyMult(source);
			stats.getAcceleration().unmodifyMult(source);
			stats.getDeceleration().unmodifyMult(source);
		} else {
			// 非长机成员，根据与长机的距离调整速度
			ShipAPI leader = ship.getWingLeader();
			float dist = Misc.getDistance(ship.getLocation(), leader.getLocation());
			float mult = (dist - COHESION_RANGE_MAX * 0.5f -
					ship.getCollisionRadius() * 0.5f - leader.getCollisionRadius() * 0.5f) / COHESION_RANGE_MAX;
			if (mult < 0f) mult = 0f;
			if (mult > 1f) mult = 1f;

			// 距离越远，速度加成越大
			stats.getMaxSpeed().modifyMult(source, 1f + .25f * mult);
			stats.getAcceleration().modifyMult(source, 1f + 0.5f * mult);
			stats.getDeceleration().modifyMult(source, 1f + 0.5f * mult);
		}

		// 应用期望朝向
		float useHeading = desiredHeading;
		CombatEngineAPI engine = Global.getCombatEngine();
		engine.headInDirectionWithoutTurning(ship, useHeading, 10000);
		Misc.turnTowardsFacingV2(ship, useHeading, 0f);
	}

	// ===================== 群体行为计算方法 =====================

	/**
	 * 计算期望朝向
	 * 基于群体行为数据计算舰船应该朝向的方向
	 */
	protected void computeDesiredHeading() {
		Vector2f loc = ship.getLocation();
		Vector2f vel = ship.getVelocity();
		float facing = ship.getFacing();

		Vector2f total = new Vector2f();

		// 遍历所有群体行为数据
		for (VIII_FlockingData curr : VIIIFlockingData) {
			float dist = Misc.getDistance(curr.loc, loc);

			// ===================== 排斥力计算 =====================
			if (curr.maxR > 0 && dist < curr.maxR) {
				float repelWeight = curr.repelWeight;

				// 根据距离调整排斥权重（越近排斥力越大）
				if (dist > curr.minR) {
					repelWeight = (dist - curr.minR) / (curr.maxR - curr.minR);
					if (repelWeight > 1f) repelWeight = 1f;
					repelWeight = 1f - repelWeight;  // 反转：越近值越大
					repelWeight *= curr.repelWeight;
				}

				// 计算排斥方向（从目标指向自己）
				Vector2f dir = Misc.getUnitVector(curr.loc, loc);

				// 角度排斥调整（避免直接对撞）
				float distIntoRepel = curr.maxR - dist;
				float repelAdjustmentAngle = 0f;
				if (distIntoRepel < curr.repelAtAngleDist && curr.repelAtAngleDist > 0) {
					float repelMult = (1f - distIntoRepel / curr.repelAtAngleDist);
					repelAdjustmentAngle = 90f * repelMult;
					repelWeight *= (1f - repelMult);

					// 根据速度方向决定转向方向
					float repelAngle = Misc.getAngleInDegrees(dir);
					float turnDir = Misc.getClosestTurnDirection(dir, vel);
					repelAdjustmentAngle *= turnDir;
					dir = Misc.getUnitVectorAtDegreeAngle(repelAngle + repelAdjustmentAngle);
				}

				// 累加排斥力
				dir.scale(repelWeight);
				Vector2f.add(total, dir, total);
			}

			// ===================== 吸引力计算 =====================
			if (curr.maxA > 0 && dist < curr.maxA) {
				float attractWeight = curr.attractWeight;

				// 根据距离调整吸引权重（越远吸引力越大）
				if (dist > curr.minA) {
					attractWeight = (dist - curr.minA) / (curr.maxA - curr.minA);
					if (attractWeight > 1f) attractWeight = 1f;
					attractWeight = 1f - attractWeight;  // 反转：越远值越大
					attractWeight *= curr.attractWeight;
				}

				// 计算吸引方向（从自己指向目标）
				Vector2f dir = Misc.getUnitVector(loc, curr.loc);
				dir.scale(attractWeight);
				Vector2f.add(total, dir, total);
			}

			// ===================== 凝聚力计算 =====================
			if (curr.maxC > 0 && dist < curr.maxC) {
				float cohesionWeight = curr.cohesionWeight;

				// 根据距离调整凝聚权重（越远凝聚力越大）
				if (dist > curr.minC) {
					cohesionWeight = (dist - curr.minC) / (curr.maxC - curr.minC);
					if (cohesionWeight > 1f) cohesionWeight = 1f;
					cohesionWeight = 1f - cohesionWeight;  // 反转：越远值越大
					cohesionWeight *= curr.cohesionWeight;
				}

				// 凝聚方向是目标的运动方向（保持队形）
				Vector2f dir = new Vector2f(curr.vel);
				Misc.normalise(dir);
				dir.scale(cohesionWeight);
				Vector2f.add(total, dir, total);
			}
		}

		// 如果没有有效的影响，保持当前朝向
		if (total.length() <= 0) {
			desiredHeading = ship.getFacing();
			headingChangeRate = ship.getAngularVelocity() * 0.5f;
		} else {
			// 计算期望朝向和变化率
			float prev = desiredHeading;
			desiredHeading = Misc.getAngleInDegrees(total);

			if (elapsedSincePrevHeadingUpdate > 0) {
				headingChangeRate = Misc.getAngleDiff(prev, desiredHeading) / elapsedSincePrevHeadingUpdate;
			} else {
				headingChangeRate = ship.getAngularVelocity() * 0.5f;
			}
		}
	}

	// ===================== 群体数据更新方法 =====================

	/**
	 * 更新群体行为数据
	 * 收集所有影响当前蜂群单位的其他实体信息
	 */
	protected void updateFlockingData() {
		VIIIFlockingData.clear();

		CombatEngineAPI engine = Global.getCombatEngine();

		// 如果正在建造，不更新群体数据
		if (constructionScript != null && constructionScript.getShip() != null) {
			return;
		}

		// 对于暴雪群，只需要对母舰产生吸引力，忽略所有敌方单位
		if (blizzardSwarm) {
			ShipAPI sourceShip = ship.getWing() != null ? ship.getWing().getSourceShip() : null;
			if (sourceShip != null && sourceShip.isAlive()) {
				// 添加母舰为唯一吸引点，保持在一定距离内
				float radius = ship.getCollisionRadius() * 0.25f;
				VIII_RoilingSwarmEffect swarm = VIII_RoilingSwarmEffect.getSwarmFor(ship);
				if (swarm != null) radius = swarm.params.maxOffset;
				VIII_FlockingData data = new VIII_FlockingData();
				data.facing = sourceShip.getFacing();
				data.loc = sourceShip.getLocation();
				data.vel = sourceShip.getVelocity();
				data.attractWeight = 15f;                 // 较强吸引
				data.repelWeight = data.attractWeight * 3f; // 较弱排斥，防止重叠
				data.minA = radius;
				data.maxA = radius;
				data.minR = 0F;
				data.maxR = 0F;
				data.repelAtAngleDist = 0f;
				VIIIFlockingData.add(data);
				// 可选：与其他同型暴雪群保持编队
				// 这里简化处理，暂不添加其他 swarm 的 flocking 数据
			}
			// 不再处理任何其他实体（敌方/友方）
			return;
		}

		int owner = ship.getOriginalOwner();
		FighterWingAPI wing = ship.getWing();
		if (wing == null) return;

		Vector2f loc = ship.getLocation();
		boolean wingLeader = ship.isWingLeader();

		// 获取攻击范围
		float attackRange = wing.getSpec().getAttackRunRange();
		attackRange *= attackRangeMult;
		float radius = ship.getCollisionRadius() * 0.5f;

		// 如果存在蜂群效果，使用其最大偏移量作为半径
		VIII_RoilingSwarmEffect swarm = VIII_RoilingSwarmEffect.getSwarmFor(ship);
		if (swarm != null) {
			radius = swarm.params.maxOffset;
		}

		VIII_SharedSwarmWingData shared = getShared();

		ShipAPI target = null;
		float targetDist = Float.MAX_VALUE;

		// ===================== 处理所有舰船 =====================
		for (ShipAPI curr : engine.getShips()) {
			if (curr == ship) continue;

			// 对于战斗机，如果是非长机且不是攻击蜂群的目标，则跳过
			if (curr.isFighter() && (!curr.isWingLeader() || curr.getOwner() == owner || !attackSwarm)) continue;

			// ===================== 建造蜂群的特殊逻辑 =====================
			// 建造蜂群寻找空旷区域，避开所有其他实体
			if (constructionSwarm) {
				float currRadius = curr.getCollisionRadius() * 2f;
				VIII_FlockingData data = new VIII_FlockingData();
				data.facing = curr.getFacing();
				data.loc = curr.getLocation();
				data.vel = curr.getVelocity();
				data.attractWeight = 0f;
				data.repelWeight = getShipWeight(curr);
				data.minA = 0f;
				data.maxA = 0f;
				data.minR = radius + currRadius;
				data.maxR = radius + currRadius + Math.min(100f, currRadius);
				data.repelAtAngleDist = (data.maxR - data.minR) * 0.5f;
				VIIIFlockingData.add(data);
				continue;
			}

			if (curr.isHulk() || curr.getOwner() == 100) continue;

			// ===================== 回收蜂群的特殊逻辑 =====================
			// 回收蜂群只被建造单位吸引
			if (reclamationSwarm) {
				if (!VIII_ThreatCombatStrategyAI.isFabricator(curr) || curr.getOwner() != owner) continue;
				float currRadius = curr.getCollisionRadius() * 0.5f;
				VIII_FlockingData data = new VIII_FlockingData();
				data.facing = curr.getFacing();
				data.loc = curr.getLocation();
				data.vel = curr.getVelocity();
				data.attractWeight = getShipWeight(curr) * (1f - ship.getCurrentCR());
				data.repelWeight = data.attractWeight * 10f;
				data.minA = radius + currRadius;
				data.maxA = 1000000f;
				data.minR = radius + currRadius;
				data.maxR = radius + currRadius + 100f;
				data.repelAtAngleDist = (data.maxR - data.minR) * 0.5f;
				VIIIFlockingData.add(data);
				continue;
			}

			float currRadius = curr.getCollisionRadius() * 0.5f;

			// ===================== 敌方单位处理 =====================
			if (curr.getOwner() != owner && engine.isAwareOf(owner, curr)) {
				VIII_FlockingData data = new VIII_FlockingData();
				data.facing = curr.getFacing();
				data.loc = curr.getLocation();
				data.vel = curr.getVelocity();
				data.attractWeight = getShipWeight(curr);
				data.repelWeight = data.attractWeight * 10f;
				data.minA = attackRange + radius + currRadius;
				data.maxA = 1000000f;
				data.repelAtAngleDist = Math.min(attackRange * 0.5f, 400f);
				data.minR = radius + currRadius;
				data.maxR = attackRange + radius + currRadius;

				// 如果是优先级目标，调整参数
				if (curr == shared.target) {
					// 检查是否在目标前方
					float angleDiffFromFront = Misc.getAngleDiff(curr.getFacing(),
							Misc.getAngleInDegrees(curr.getLocation(), ship.getLocation()));
					float maxDiff = 45f;

					// 如果在目标前方，增大排斥范围
					if (angleDiffFromFront < maxDiff) {
						data.minR = data.minR + (data.maxR - data.minR) * 0.5f;
						data.minR += 500f * (1f - angleDiffFromFront / maxDiff);
						data.maxR += 500f * (1f - angleDiffFromFront / maxDiff);
						data.repelAtAngleDist += 500f * (1f - angleDiffFromFront / maxDiff);
					}

					// 增加权重
					data.attractWeight += 200f;
					data.repelWeight += 600f;
				}

				VIIIFlockingData.add(data);

				// 更新最近目标
				float dist = Misc.getDistance(loc, curr.getLocation());
				if (dist < targetDist && dist < MAX_TARGET_RANGE) {
					target = curr;
					targetDist = dist;
				}

				// 为大型敌舰在后方添加额外吸引点（鼓励绕后攻击）
				if ((curr.isDestroyer() || curr.isCruiser() || curr.isCapital()) && curr == shared.target) {
					data = new VIII_FlockingData();
					Vector2f dir = Misc.getUnitVectorAtDegreeAngle(curr.getFacing() + 180f);
					dir.scale(curr.getCollisionRadius() * 0.5f + attackRange * attackRangeMult);
					data.facing = curr.getFacing();
					data.loc = Vector2f.add(curr.getLocation(), dir, new Vector2f());
					data.vel = curr.getVelocity();
					data.attractWeight = getShipWeight(curr);
					data.minA = attackRange + radius + currRadius;
					data.maxA = 1000000f;

					if (curr == shared.target) {
						data.attractWeight += 200f;
					}
					VIIIFlockingData.add(data);
				}
			}
			// ===================== 友方单位处理 =====================
			else if (curr.getOwner() == owner) {
				VIII_FlockingData data = new VIII_FlockingData();
				data.facing = curr.getFacing();
				data.loc = curr.getLocation();
				data.vel = curr.getVelocity();
				data.attractWeight = getShipWeight(curr) * 0.1f;
				data.repelWeight = data.attractWeight * 50f;
				data.minA = attackRange + radius + currRadius;
				data.maxA = 1000000f;
				data.minR = radius + currRadius;
				data.maxR = attackRange * 0.75f + radius + currRadius;
				data.repelAtAngleDist = Math.min(attackRange * 0.5f, 400f);
				VIIIFlockingData.add(data);
			}
		}

		// ===================== 设置机动目标 =====================
		if (target != null) {
			flags.setFlag(AIFlags.MANEUVER_TARGET, 3f, target);
		} else {
			flags.unsetFlag(AIFlags.MANEUVER_TARGET);
		}

		// ===================== 如果没有群体数据，添加默认吸引点 =====================
		if (VIIIFlockingData.isEmpty() && !constructionSwarm) {
			VIII_FlockingData data = new VIII_FlockingData();
			data.facing = 0f;
			data.loc = new Vector2f();
			data.vel = new Vector2f();
			data.attractWeight = 5f;
			data.repelWeight = data.attractWeight * 10f;
			data.minA = 1000f;
			data.maxA = 1000000f;
			data.minR = 1000f;
			data.maxR = 3000f;
			data.repelAtAngleDist = 1000f;
			VIIIFlockingData.add(data);
		}

		// ===================== 处理其他蜂群效果 =====================
		if (swarm != null && swarm.params.flockingClass != null && swarm.attachedTo != null) {
			for (VIII_RoilingSwarmEffect curr : VIII_RoilingSwarmEffect.getFlockingMap().getList(swarm.params.flockingClass)) {
				if (curr == swarm) continue;
				if (curr.attachedTo == ship || curr.attachedTo == null ||
						curr.attachedTo.getOwner() != owner) {
					continue;
				}

				// 相同集群类的蜂群
				if (swarm.params.flockingClass.equals(curr.params.flockingClass)) {
					// ===================== 建造蜂群避开其他建造蜂群 =====================
					if (constructionSwarm) {
						float currRadius = curr.params.maxOffset;
						VIII_FlockingData data = new VIII_FlockingData();
						data.facing = curr.attachedTo.getFacing();
						data.loc = curr.attachedTo.getLocation();
						data.vel = curr.attachedTo.getVelocity();
						data.attractWeight = 0f;
						data.repelWeight = 8f;
						data.minA = 0f;
						data.maxA = 0f;
						data.minR = radius + currRadius;
						data.maxR = radius + currRadius + Math.min(100f, currRadius);
						data.repelAtAngleDist = (data.maxR - data.minR) * 0.5f;
						VIIIFlockingData.add(data);
						continue;
					}

					// 检查是否为同一编队
					boolean sameWing = wing == ((ShipAPI)curr.attachedTo).getWing();
					boolean otherWingLeader = ((ShipAPI)curr.attachedTo).isWingLeader();

					// 长机不等候其他成员
					if (wingLeader && sameWing) continue;

					// 不同编队且距离太远则跳过
					if (!sameWing) {
						float dist = Misc.getDistance(loc, curr.attachedTo.getLocation());
						if (dist > ATTRACTOR_RANGE_MAX + 500f) continue;
					}

					// 添加蜂群群体数据
					float currRadius = curr.attachedTo.getCollisionRadius() * 0.5f;
					VIII_FlockingData data = new VIII_FlockingData();
					data.facing = curr.attachedTo.getFacing();
					data.loc = curr.attachedTo.getLocation();
					data.vel = curr.attachedTo.getVelocity();
					data.attractWeight = 1f;
					data.repelWeight = 10f;
					data.cohesionWeight = 1f;

					// 同一编队有更大的吸引范围
					if (sameWing) {
                        data.attractWeight = 3f;
                        data.minA = 0f + radius + currRadius;
						data.maxA = ATTRACTOR_RANGE_MAX_SAME_WING + radius + currRadius;
					} else {
						data.minA = 0f + radius + currRadius;
						data.maxA = ATTRACTOR_RANGE_MAX + radius + currRadius;
					}

					data.minR = REPEL_RANGE_MIN + radius + currRadius;
					data.maxR = REPEL_RANGE_MAX + radius + currRadius;

					// 长机之间的排斥范围更大
					if (wingLeader && otherWingLeader) {
						data.maxR = ATTRACTOR_RANGE_MAX + radius + currRadius;
					}

					data.minC = COHESION_RANGE_MIN + radius + currRadius;
					data.maxC = COHESION_RANGE_MAX + radius + currRadius;

					// 回收蜂群的排斥范围更小
					if (reclamationSwarm) {
						data.minR *= 0.33f;
						data.maxR *= 0.33f;
					}

					VIIIFlockingData.add(data);
				}
			}
		}
	}

	// ===================== AI接口方法 =====================

	@Override
	public ShipwideAIFlags getAIFlags() {
		return flags;
	}

	@Override
	public void cancelCurrentManeuver() {

	}

	@Override
	public ShipAIConfig getConfig() {
		return null;
	}

	// ===================== 工具方法 =====================

	/**
	 * 计算舰船权重
	 * @param ship 要计算权重的舰船
	 * @return 权重值
	 */
	public static float getShipWeight(ShipAPI ship) {
		return getShipWeight(ship, true);
	}

	/**
	 * 计算舰船权重（带非战斗调整参数）
	 * @param ship 要计算权重的舰船
	 * @param adjustForNonCombat 是否为非战斗舰船调整权重
	 * @return 权重值
	 */
	public static float getShipWeight(ShipAPI ship, boolean adjustForNonCombat) {
		boolean nonCombat = ship.isNonCombat(false);
		float weight = 0;

		// 根据舰船大小分配基础权重
		switch (ship.getHullSize()) {
			case CAPITAL_SHIP: weight += 8; break;
			case CRUISER: weight += 4; break;
			case DESTROYER: weight += 2; break;
			case FRIGATE: weight += 1; break;
			case FIGHTER: weight += 1; break;
		}

		// 为非战斗舰船降低权重
		if (nonCombat && adjustForNonCombat) weight *= 0.25f;

		// 为无人机降低权重
		if (ship.isDrone()) weight *= 0.1f;

		return weight;
	}

	// ===================== 未使用的AI接口方法 =====================

	public void setDoNotFireDelay(float amount) {}

	@Override
	public void forceCircumstanceEvaluation() {

	}

}