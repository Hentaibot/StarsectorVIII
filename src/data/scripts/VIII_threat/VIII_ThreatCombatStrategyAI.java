// 包路径：远行星号游戏API实现中的战斗威胁包
package data.scripts.VIII_threat;

// 导入全局游戏实例类

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI.AssignmentInfo;
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.impl.campaign.ids.HullMods;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 威胁派系的战斗策略AI
 * 不关心指挥点等常规机制，以不同的方式运作。可能使用指挥点/任务等作为实现细节，
 * 但概念上与人型舰队的工作方式完全不同。
 *
 * @author Alex
 */
public class VIII_ThreatCombatStrategyAI {

	// 静态常量：随机噪声基准值（60秒）
	public static float SND_BASE = 60f;
	// 静态常量：随机噪声计时器间隔（60秒）
	public static float SND_TIMER = 60f;
	// 静态常量：随机噪声触发概率比例（0.5）
	public static float SND_FRACTION = 0.5f;

	// 是否为玩家侧（阵营ID为0）
	protected boolean playerSide;
	// 战斗任务管理器（用于分配任务给舰队成员）
	protected CombatTaskManagerAPI taskManager;
	// 当前舰队管理器（本方舰队）
	protected CombatFleetManagerAPI fleetManager;
	// 敌方舰队管理器（对方舰队）
	protected CombatFleetManagerAPI enemyFleetManager;
	// 本AI所属的阵营ID（0=玩家，1=敌人）
	protected int owner;
	// 是否为盟友模式（用于任务管理器的构造参数）
	protected boolean allyMode = false;

	// 每秒计时器（间隔0.8~1.2秒随机），用于定期执行策略更新
	protected IntervalUtil everySecond = new IntervalUtil(0.8f, 1.2f);
	// 战斗引擎引用
	protected CombatEngineAPI engine;
	// 战斗地图宽度（像素）
	protected float mw, mh;

	// 是否中止AI（如果舰队目标是逃跑等）
	protected boolean abort = false;

	// 主要防御任务1（例如保护制造者）
	protected AssignmentInfo mainDefend1;
	// 主要防御任务2（另一个防御点）
	protected AssignmentInfo mainDefend2;

	// 剩余时间（秒），在此时间内所有目标点（指挥点）将被捕获
	protected float captureAllTimeRemaining;
	// 是否已下达初始命令（防止重复下达）
	protected boolean gaveInitialOrders = false;

	// 距离下次对骚扰单位下达“随机噪声”命令的剩余时间（秒）
	protected float untilSNDOnSkirmishUnits;

	/**
	 * 构造函数
	 * @param owner 阵营ID（0=玩家，1=敌人）
	 */
	public VIII_ThreatCombatStrategyAI(int owner) {
		engine = Global.getCombatEngine();          // 获取战斗引擎
		this.owner = owner;                         // 保存阵营ID
		playerSide = owner == 0;                    // 判断是否为玩家侧
		allyMode = playerSide;                      // 盟友模式与玩家侧相同（？可能用于任务管理器）
		//allyMode = false;                          // 被注释的旧代码
		fleetManager = engine.getFleetManager(owner);           // 获取本方舰队管理器
		taskManager = fleetManager.getTaskManager(allyMode);    // 获取任务管理器
		// 给任务管理器的指挥点统计添加一个巨大的平坦修正，使其基本不受限制
		taskManager.getCommandPointsStat().modifyFlat("VIII_ThreatCombatStrategyAI", 1000000000);

		// 获取敌方舰队管理器（阵营取反：0变1，1变0）
		enemyFleetManager = engine.getFleetManager(owner == 0 ? 1 : 0);

		// 如果本方或敌方的舰队目标是逃跑，则中止AI（不进行策略）
		if (fleetManager.getGoal() == FleetGoal.ESCAPE || enemyFleetManager.getGoal() == FleetGoal.ESCAPE) {
			abort = true;
		}

		// 记录战斗地图尺寸
		mw = engine.getMapWidth();
		mh = engine.getMapHeight();

		// 重置随机噪声计时器
		resetSNDTimer();
	}

	/**
	 * 重置“随机噪声”计时器，用于让骚扰单位偶尔忽略命令
	 */
	protected void resetSNDTimer() {
		// 下次触发时间：基础SND_TIMER乘以随机系数0.75~1.25，再加上SND_BASE
		untilSNDOnSkirmishUnits = SND_TIMER * (0.75f + (float) Math.random() * 0.5f);
		untilSNDOnSkirmishUnits += SND_BASE;
	}

	/**
	 * 管理“随机噪声”效果：让部分骚扰单位（Skirmish）暂时忽略命令
	 * @param amount 时间增量（秒）
	 */
	protected void manageSND(float amount) {
		untilSNDOnSkirmishUnits -= amount;   // 减少剩余时间
		if (captureAllTimeRemaining > 0) return; // 如果还在全捕获阶段，不执行
		if (untilSNDOnSkirmishUnits <= 0) {
			// 遍历所有已部署的本方舰队成员
			for (DeployedFleetMemberAPI member : fleetManager.getDeployedCopyDFM()) {
				ShipAPI ship = member.getShip();
				if (ship == null || ship.getAI() == null) continue;
				// 跳过正在建造中的舰船
				if (ship.hasTag(VIII_ThreatShipConstructionScript.SHIP_UNDER_CONSTRUCTION)) continue;
				// 只处理带有THREAT_SKIRMISH标签的舰船（骚扰单位）
				if (!ship.getHullSpec().hasTag("VIII_threat_skirmish")) continue;
				// 按SND_FRACTION比例随机选择
				if ((float) Math.random() > SND_FRACTION) continue;

				// 取消该成员当前的任何任务分配
				cancelOrders(member, false);
				// 设置忽略命令标志，持续随机时间（SND_BASE的0.75~1.25倍）
				ship.getAIFlags().setFlag(AIFlags.IGNORES_ORDERS, SND_BASE * (0.75f + (float) Math.random() * 0.5f));
			}
			resetSNDTimer();   // 重置计时器
		}
	}

	/**
	 * 下达初始命令：清空现有任务，命令所有舰队成员捕获所有战场目标点
	 */
	protected void giveInitialOrders() {
		// 如果舰队管理器有海军上将AI，则清除所有任务并设置无命令状态
		if (fleetManager.getAdmiralAI() != null) {
			taskManager.clearTasks();
			fleetManager.getAdmiralAI().setNoOrders(true);
		}

		captureAllTimeRemaining = 80f;   // 设置全捕获阶段持续80秒
		// 为每个战场目标点创建“捕获”任务
		for (BattleObjectiveAPI curr : engine.getObjectives()) {
			taskManager.createAssignment(CombatAssignmentType.CAPTURE, curr, false);
		}
	}

	/**
	 * 每帧更新AI策略（由外部每帧调用）
	 * @param amount 时间增量（秒）
	 */
	public void advance(float amount) {
		// 调试开关（如果为true则跳过所有更新）
		//if (true) return;
		if (abort) return;          // 如果中止标志为true，直接返回
		if (engine.isPaused()) return; // 战斗暂停时不更新

		captureAllTimeRemaining -= amount;   // 减少全捕获剩余时间

		manageSND(amount);          // 管理随机噪声

		everySecond.advance(amount);   // 推进每秒计时器
		if (everySecond.intervalElapsed()) {   // 如果达到间隔时间
			// 获取当前部署的所有成员（副本）
			List<DeployedFleetMemberAPI> deployed = fleetManager.getDeployedCopyDFM();
			if (deployed.isEmpty()) return;   // 没有部署单位，返回

			// 检查是否有非威胁派系的舰船被部署（例如玩家或普通势力单位）
			boolean someMatching = false;
			for (DeployedFleetMemberAPI member : deployed) {
				// 跳过战机联队，只考虑非战机且舰船存在且盟友模式匹配
				if (!member.isFighterWing() && member.getShip() != null &&
						member.isAlly() == allyMode &&
						!member.getShip().getVariant().hasHullMod("VIII_NanoFragments")) {
					abort = true;   // 发现非威胁单位，中止AI
					return;
				} else if (!member.isFighterWing() && member.getShip() != null &&
						member.isAlly() == allyMode) {
					someMatching = true;   // 至少有一个符合条件的威胁单位
				}
			}

			if (!someMatching) return;   // 没有符合条件的威胁单位，返回

			// 如果尚未下达初始命令，则下达
			if (!gaveInitialOrders) {
				giveInitialOrders();
				gaveInitialOrders = true;
			}

			// 计算方向符号：玩家侧（owner=0）为-1，敌人侧为1（用于地图上下方向）
			float sign = 1f;
			if (owner == 0) sign = -1;
			// 获取敌方单位群的中心（质心）
			Vector2f enemyCom = getEnemyCenterOfMass();
			// 制造者（Fabricator）的默认位置：地图中央偏上/下（根据阵营）
			Vector2f fabricatorLoc = new Vector2f(0, 0 + mh * 0.33f * sign);
			// 从制造者位置指向敌方质心的单位向量（轴线方向）
			Vector2f axis = Misc.getUnitVector(fabricatorLoc, enemyCom);
			// 垂直于轴线的向量（用于计算侧翼防御点）
			Vector2f perp = new Vector2f(axis.y, -axis.x);
			// 制造者到敌方质心的距离
			float distToEnemyCom = Misc.getDistance(fabricatorLoc, enemyCom);

			// 计算巢穴（Hive）的理想位置：沿轴线方向，距离制造者偏移（距离-6000）
			float hiveOffset = distToEnemyCom - 6000f;
			if (Math.abs(hiveOffset) < 2000f) {
				hiveOffset = Math.signum(hiveOffset) * 2000f;   // 确保偏移至少2000像素
			}
			if (hiveOffset > 2000) hiveOffset= 2000f;
			Vector2f hiveLoc = new Vector2f(axis);
			hiveLoc.scale(hiveOffset);
			Vector2f.add(hiveLoc, fabricatorLoc, hiveLoc);

			// 统计制造者附近3000像素内的敌方战斗权重
			float enemyWeightNearFabricatorLoc = Misc.countEnemyWeightInArcAroundLocation(
					owner, fabricatorLoc, 0f, 360f, 3000f, null, true, true);

			// 统计当前部署的制造者和巢穴数量
			int fabricators = 0;
			int hives = 0;
			for (DeployedFleetMemberAPI member : fleetManager.getDeployedCopyDFM()) {
				ShipAPI ship = member.getShip();
				if (ship == null || ship.getAI() == null) continue;
				if (isFabricator(ship)) {
					fabricators++;
				}
				if (isHive(ship)) {
					hives++;
				}
			}

			// 计算防御线的距离：敌方质心距离减2000
			float defDist = distToEnemyCom - 2000f;
			// 如果制造者附近敌方权重较高，则缩小防御距离（最大3000）
			if (enemyWeightNearFabricatorLoc >= 3f) {
				defDist = Math.min(defDist, 3000f);
			}
			// 如果没有制造者或巢穴，则清除防御任务
			if (fabricators == 0 && hives == 0) {
				if (mainDefend1 != null) {
					taskManager.removeAssignment(mainDefend1);
					mainDefend1 = null;
				}
				if (mainDefend2 != null) {
					taskManager.removeAssignment(mainDefend2);
					mainDefend2 = null;
				}
			}
			// 如果防御距离小于2000（敌方已逼近制造者），则进入“侵略模式”
			else if (defDist < 2000f) {
				// 检查现有防御任务是否过时（距离目标点大于1000）
				if (mainDefend1 != null) {
					float dist = Misc.getDistance(mainDefend1.getTarget().getLocation(), fabricatorLoc);
					if (dist > 1000f) {
						taskManager.removeAssignment(mainDefend1);
						mainDefend1 = null;
					}
				}
				if (mainDefend2 != null) {
					taskManager.removeAssignment(mainDefend2);
					mainDefend2 = null;
				}
				// 如果主防御任务不存在，则创建新的防御任务，目标为制造者位置
				if (mainDefend1 == null) {
					AssignmentTargetAPI wp = taskManager.createWaypoint2(fabricatorLoc, allyMode);
					mainDefend1 = taskManager.createAssignment(CombatAssignmentType.DEFEND, wp, false);
				}

				// 敌方已接近制造者，命令所有战斗单位进入“进攻模式”：设置AI标志，强制进攻
				for (DeployedFleetMemberAPI member : fleetManager.getDeployedCopyDFM()) {
					ShipAPI ship = member.getShip();
					if (ship == null || ship.getAI() == null) continue;
					if (isCombatUnit(ship) && ship.getAI() instanceof ShipAIPlugin) {
						// 注释部分：原本可以修改AI配置，现改为设置标志
						ship.getAIFlags().setFlag(AIFlags.DO_NOT_BACK_OFF, 2f);   // 不后退
						ship.getAIFlags().setFlag(AIFlags.DO_NOT_VENT, 2f);        // 不主动排辐能
						ship.getAIFlags().setFlag(AIFlags.IGNORES_ORDERS, 2f);     // 忽略命令
					}
				}
			}
			// 正常情况：防御距离足够远，设置两个侧翼防御点
			else {
				// 计算两个侧翼防御点的位置：沿防御线，垂直于轴线方向偏移1000像素
				Vector2f defLoc = new Vector2f(axis);
				defLoc.scale(defDist);
				Vector2f.add(defLoc, fabricatorLoc, defLoc);

				Vector2f defLoc1 = new Vector2f(perp);
				defLoc1.scale(1000f);
				Vector2f.add(defLoc1, defLoc, defLoc1);

				Vector2f defLoc2 = new Vector2f(perp);
				defLoc2.scale(-1000f);
				Vector2f.add(defLoc2, defLoc, defLoc2);

				// 检查现有防御任务是否匹配，如果不匹配则清除
				if (mainDefend1 != null) {
					float dist = Misc.getDistance(mainDefend1.getTarget().getLocation(), defLoc1);
					if (dist > 1000f) {
						taskManager.removeAssignment(mainDefend1);
						mainDefend1 = null;
					}
				}
				if (mainDefend2 != null) {
					float dist = Misc.getDistance(mainDefend2.getTarget().getLocation(), defLoc2);
					if (dist > 1000f) {
						taskManager.removeAssignment(mainDefend2);
						mainDefend2 = null;
					}
				}
				// 如果主防御任务不存在，则创建新的防御任务
				if (mainDefend1 == null) {
					AssignmentTargetAPI wp = taskManager.createWaypoint2(defLoc1, allyMode);
					mainDefend1 = taskManager.createAssignment(CombatAssignmentType.DEFEND, wp, false);
				}
				if (mainDefend2 == null) {
					AssignmentTargetAPI wp = taskManager.createWaypoint2(defLoc2, allyMode);
					mainDefend2 = taskManager.createAssignment(CombatAssignmentType.DEFEND, wp, false);
				}
			}

			// 全捕获阶段结束后，管理对目标点的控制（捕获/放弃）
			if (captureAllTimeRemaining <= 0f) {
				float axisAngle = Misc.getAngleInDegrees(axis);   // 轴线方向的角度
				// 收集当前所有已存在的捕获或控制任务的目标点
				List<AssignmentTargetAPI> withCaptures = new ArrayList<>();
				for (AssignmentInfo info : taskManager.getAllAssignments()) {
					if (info.getTarget() == null) continue;
					if (info.getType() == CombatAssignmentType.CAPTURE || info.getType() == CombatAssignmentType.CONTROL) {
						// 如果没有制造者/巢穴，或者该目标点不希望被控制，则移除该任务
						if ((fabricators == 0 && hives == 0) ||
								!wantsToControl(fabricatorLoc, axisAngle, distToEnemyCom, info.getTarget().getLocation())) {
							taskManager.removeAssignment(info);
						} else {
							withCaptures.add(info.getTarget());
						}
					}
				}

				// 如果有制造者或巢穴，则遍历所有战场目标点，为希望控制的目标点创建捕获任务
				if (fabricators > 0 || hives > 0) {
					for (BattleObjectiveAPI curr : engine.getObjectives()) {
						if (withCaptures.contains(curr)) continue;  // 已有任务则跳过
						if (wantsToControl(fabricatorLoc, axisAngle, distToEnemyCom, curr.getLocation())) {
							taskManager.createAssignment(CombatAssignmentType.CAPTURE, curr, false);
						}
					}
				}
			}

			// 记录已被护航的成员（避免重复护航）
			Set<DeployedFleetMemberAPI> escorted = new LinkedHashSet<>();

			// 遍历所有已部署成员，为制造者、巢穴、督军分配移动或护航任务
			for (DeployedFleetMemberAPI member : fleetManager.getDeployedCopyDFM()) {
				ShipAPI ship = member.getShip();
				if (ship == null || ship.getAI() == null) continue;
				if (ship.hasTag(VIII_ThreatShipConstructionScript.SHIP_UNDER_CONSTRUCTION)) continue; // 建造中的不处理

				// 搜索附近敌方的权重范围
				float enemyCheckRange = 3000f;
				if (isHive(ship)) {
					enemyCheckRange = 1000f;   // 巢穴的检测范围较小
				}

				float enemyWeight = Misc.countEnemyWeightInArcAroundLocation(owner, ship.getLocation(), 0f, 360f, enemyCheckRange, null, true, true);
				float shipWeight = Misc.getShipWeight(ship, true);
				boolean enemiesNear = enemyWeight >= shipWeight * 0.5f;   // 是否有敌方接近

				// 制造者：命令其移动到制造者集结点（fabricatorLoc），距离范围[min,max]
				if (isFabricator(ship)) {
					float min = 0f;
					float max = 1000f;
					if (enemiesNear) {
						min = 1000f;
						max = 2000f;   // 敌方接近时，让制造者后退到更远位置
					}
					giveMovementOrder(member, fabricatorLoc, min, max);
				}
				// 巢穴：移动到巢穴集结点，如果敌方接近则取消命令（让其自由战斗）
				else if (isHive(ship)) {
					if (enemiesNear) {
						cancelOrders(member, true);   // 敌方接近，取消任务，允许自由战斗
					} else {
						giveMovementOrder(member, hiveLoc, 1000f, 1500f);
					}
				}
				// 督军：寻找最近的友方战斗单位或巢穴进行护航
				else if (isOverseer(ship)) {
					DeployedFleetMemberAPI closest = null;
					float minDist = Float.MAX_VALUE;
					for (DeployedFleetMemberAPI other : fleetManager.getDeployedCopyDFM()) {
						if (other == member || other.getShip() == null || escorted.contains(other)) continue;

						// 根据目标类型计算额外距离分数，用于优先选择特定单位
						float extraDistScore = 0f;
						if (other.getShip().isCruiser() && isCombatUnit(other.getShip())) {
							extraDistScore = 0f;   // 巡洋舰优先
						} else if (other.getShip().isDestroyer() && isCombatUnit(other.getShip())) {
							extraDistScore = 100000f;   // 驱逐舰次之（分数大，距离远）
						} else if (other.getShip().isFrigate() && isCombatUnit(other.getShip())) {
							if (enemiesNear) {
								extraDistScore = 500000f;
							} else {
								extraDistScore = 1000000f;
							}
						} else if (isHive(other.getShip())) {
							if (enemiesNear) {
								extraDistScore = 100000f;
							} else {
								extraDistScore = 10000000f;
							}
						} else {
							continue;
						}
						float dist = Misc.getDistance(member.getLocation(), other.getLocation()) + extraDistScore;
						if (dist < minDist) {
							closest = other;
							minDist = dist;
						}
					}
					if (closest != null) {
						// 设置护航相关的AI标志
						member.getShip().getAIFlags().setFlag(AIFlags.TIMID_ESCORT, 2f);
						member.getShip().getAIFlags().setFlag(AIFlags.ESCORT_RANGE_MODIFIER, 2f, 300f);
						escort(member, closest);   // 执行护航命令
						escorted.add(closest);      // 标记该目标已被护航
					}
				}
			}

			// 清理空任务（没有成员分配的任务）
			cleanUpEmptyAssignments();
		}
	}

	/**
	 * 判断某个战场目标点是否应该被控制（捕获或保持）
	 * @param fabricatorLoc 制造者位置
	 * @param axisAngle     轴线方向角度（从制造者指向敌方质心）
	 * @param distToEnemyCom 制造者到敌方质心的距离
	 * @param objectiveLoc  目标点位置
	 * @return true表示应该控制，false表示不应控制
	 */
	protected boolean wantsToControl(Vector2f fabricatorLoc, float axisAngle, float distToEnemyCom, Vector2f objectiveLoc) {
		float dist = Misc.getDistance(fabricatorLoc, objectiveLoc);
		float angle = Misc.getAngleInDegrees(fabricatorLoc, objectiveLoc);
		float angleDiff = Misc.getAngleDiff(axisAngle, angle);

		float enemyWeight = Misc.countEnemyWeightInArcAroundLocation(owner, objectiveLoc, 0f, 360f, 3000f, null, true, true);
		// 如果目标点附近没有敌人且角度差大于30度，则控制它（可能用于战略扩张）
		if (enemyWeight <= 0f && angleDiff > 30f) return true;
		// 否则，如果距离小于5000，或者（距离小于敌方质心距离且角度差小于45度），则控制
		return dist < 5000 || (dist < distToEnemyCom && angleDiff < 45f);
	}

	/**
	 * 取消指定成员当前的所有任务，并可选择是否命令其“搜索并摧毁”
	 * @param member              舰队成员
	 * @param withSearchAndDestroy 是否同时命令其进入搜索摧毁模式
	 */
	protected void cancelOrders(DeployedFleetMemberAPI member, boolean withSearchAndDestroy) {
		AssignmentInfo curr = taskManager.getAssignmentFor(member.getShip());
		if (curr != null) {
			taskManager.removeAssignment(curr);
		}
		if (withSearchAndDestroy) {
			taskManager.orderSearchAndDestroy(member, false);
		}
	}

	/**
	 * 命令一个成员护航另一个成员（轻护航任务）
	 * @param member  护航方
	 * @param target  被护航方
	 */
	protected void escort(DeployedFleetMemberAPI member, DeployedFleetMemberAPI target) {
		if (member.getShip() == null) return;
		// 如果当前已有相同的护航任务，则不再重复分配
		AssignmentInfo curr = taskManager.getAssignmentFor(member.getShip());
		if (curr != null && curr.getType() == CombatAssignmentType.LIGHT_ESCORT &&
				taskManager.getAssignmentTargetFor(member.getShip()) == target) {
			return;
		}

		AssignmentInfo info = taskManager.createAssignment(CombatAssignmentType.LIGHT_ESCORT, target, false);
		taskManager.setAssignmentWeight(info, 0f);
		taskManager.giveAssignment(member, info, false);
	}

	/**
	 * 给成员下达移动命令（集结点），指定距离范围[minDist, maxDist]
	 * @param member  舰队成员
	 * @param loc     目标点位置
	 * @param minDist 最小距离（低于此距离则取消命令）
	 * @param maxDist 最大距离（超过此距离则创建命令）
	 */
	protected void giveMovementOrder(DeployedFleetMemberAPI member, Vector2f loc, float minDist, float maxDist) {
		AssignmentInfo curr = taskManager.getAssignmentFor(member.getShip());
		// 判断是否需要创建新任务：当前无任务或当前任务目标点距离新位置>100像素
		boolean needToMakeAssignment = curr == null || curr.getTarget() == null || Misc.getDistance(curr.getTarget().getLocation(), loc) > 100f;

		float dist = Misc.getDistance(member.getLocation(), loc);
		// 如果距离小于最小距离，则取消现有任务（已经足够近）
		if (dist < minDist) {
			if (curr != null && (curr.getType() == CombatAssignmentType.RALLY_CIVILIAN)) {
				taskManager.removeAssignment(curr);
			}
		}

		// 是否需要“拴绳”（即需要移动命令）：当前无任务且距离大于最小距离，或者当前有任务但距离大于最大距离
		boolean needToLeash = false;
		needToLeash |= curr == null && dist > minDist;
		needToLeash |= curr != null && dist > maxDist;

		if (needToMakeAssignment && needToLeash) {
			AssignmentTargetAPI wp = taskManager.createWaypoint2(loc, allyMode);
			AssignmentInfo task = taskManager.createAssignment(CombatAssignmentType.RALLY_CIVILIAN, wp, false);
			taskManager.giveAssignment(member, task, false);
		}
	}

	/**
	 * 清理空任务（没有任何成员分配的任务），并删除空的路径点
	 */
	public void cleanUpEmptyAssignments() {
		taskManager.reassign(); // 重新分配，确保已分配成员列表不为空
		List<AssignmentInfo> remove = new ArrayList<>();
		for (AssignmentInfo curr : taskManager.getAllAssignments()) {
			// 保留控制、捕获、防御任务（即使没有成员，也暂不删除？此处只删除其他类型）
			if (curr.getType() == CombatAssignmentType.CONTROL) continue;
			if (curr.getType() == CombatAssignmentType.CAPTURE) continue;
			if (curr.getType() == CombatAssignmentType.DEFEND) continue;
			if (curr.getAssignedMembers().isEmpty()) {
				remove.add(curr);
			}
		}
		for (AssignmentInfo curr : remove) {
			taskManager.removeAssignment(curr);
		}

		taskManager.clearEmptyWaypoints();
	}

	/**
	 * 计算敌方舰队的质心（中心位置）
	 * @return 质心向量
	 */
	protected Vector2f getEnemyCenterOfMass() {
		Vector2f com = new Vector2f();
		float weight = 0;
		for (DeployedFleetMemberAPI member : enemyFleetManager.getDeployedCopyDFM()) {
			if (member.isFighterWing()) continue;      // 忽略战机联队
			if (member.getShip() == null) continue;
			if (!engine.isAwareOf(owner, member.getShip())) continue; // 如果本阵营未发现该目标，忽略

			Vector2f.add(member.getLocation(), com, com);
			weight++; // 简单的权重（1 per ship），不按尺寸缩放
		}
		if (weight > 0) {
			com.scale(1f / weight);
		}
		return com;
	}

	// ==================== 静态工具方法：判断舰船类型 ====================

	/**
	 * 判断是否为战斗单位（带有THREAT_COMBAT标签）
	 * @param ship 舰船
	 * @return true表示是战斗单位
	 */
	public static boolean isCombatUnit(ShipAPI ship) {
		return ship.getHullSpec().hasTag("VIII_threat_combat");
	}

	/**
	 * 判断是否为督军（带有THREAT_OVERSEER标签）
	 * @param ship 舰船
	 * @return true表示是督军
	 */
	public static boolean isOverseer(ShipAPI ship) {
		return ship.getHullSpec().hasTag("VIII_threat_overseer");
	}

	/**
	 * 判断是否为巢穴（带有THREAT_HIVE标签）
	 * @param ship 舰船
	 * @return true表示是巢穴
	 */
	public static boolean isHive(ShipAPI ship) {
		return ship.getHullSpec().hasTag("VIII_threat_hive");
	}

	/**
	 * 判断是否为制造者（带有THREAT_FABRICATOR标签，或者拥有建造群系统）
	 * @param ship 舰船
	 * @return true表示是制造者
	 */
	public static boolean isFabricator(ShipAPI ship) {
		return ship.getHullSpec().hasTag("VIII_threat_fabricator");
		// 被注释的另一种判断方式：return ship.getSystem() != null && ship.getSystem().getId().equals(ShipSystems.CONSTRUCTION_SWARM);
	}
}