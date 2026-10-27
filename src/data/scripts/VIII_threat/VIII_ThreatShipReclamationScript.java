package data.scripts.VIII_threat;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.BoundsAPI.SegmentAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.impl.combat.RiftLanceEffect;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.WeightedRandomPicker;
import data.hullmods.VIII_FragmentSwarmHullmod;
import data.hullmods.VIII_NanoFragments;
import data.scripts.shipsystems.VIII_ConstructionSwarmSystemScript;
import data.scripts.shipsystems.VIII_ConstructionSwarmSystemScript.VIII_SwarmConstructableVariant;
import org.lwjgl.util.vector.Vector2f;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * ThreatShipReclamationScript - 威胁舰船回收脚本
 * 这个类实现了一个舰船回收/分解动画效果，用于在战斗中将被摧毁的威胁派系舰船分解为蜂群
 * 主要特性：
 * 1. 将舰船碎片逐渐淡出消失
 * 2. 生成回收蜂群（Reclamation Swarms）
 * 3. 粒子效果和视觉特效
 * 4. 与ConstructionSwarmSystemScript（建造蜂群系统脚本）联动
 * 这个效果通常用于威胁派系（THREAT faction）的资源回收机制
 */
public class VIII_ThreatShipReclamationScript extends BaseEveryFrameCombatPlugin {

	// ===================== 常量定义 =====================

	// 每个回收蜂群提供的CR（战斗准备度）值
	public static float CR_PER_RECLAMATION_SWARM = 0.02f;

	// 回收蜂群的速度倍率
	public static float RECLAMATION_SWARM_SPEED_MULT = 0.67f;

	// 回收蜂群的碰撞范围倍率
	public static float RECLAMATION_SWARM_COLLISION_MULT = 1.5f;

	// 回收蜂群的活动半径倍率
	public static float RECLAMATION_SWARM_RADIUS_MULT = 2f;

	// 回收蜂群的生命值倍率
	public static float RECLAMATION_SWARM_HP_MULT = 2f;

	// 回收蜂群的碎片大小倍率
	public static float RECLAMATION_SWARM_FRAGMENT_SIZE_MULT = 0.67f;

	// ===================== 成员变量 =====================

	protected float elapsed = 0f;           // 经过的时间
	protected ShipAPI primary = null;       // 主舰船（被回收的舰船）
	protected List<ShipAPI> pieces = new ArrayList<>();  // 所有碎片（包括主舰船和分裂出的碎片）
	protected List<ShipAPI> swarms = new ArrayList<>();  // 生成的蜂群舰船列表
	protected float delay;                  // 回收开始前的延迟时间
	protected float fadeOutTime;            // 淡出动画的持续时间
	protected float origMaxSpeed = 500f;    // 原始最大速度（未使用）

	// 用于控制粒子生成间隔的计时器
	protected IntervalUtil interval = new IntervalUtil(0.075f, 0.125f);

	// 用于控制蜂群成员生成间隔的计时器
	protected IntervalUtil interval2 = new IntervalUtil(0.075f, 0.125f);

	// 标记是否已经生成了蜂群
	protected boolean spawnedSwarms = false;

	// ===================== 构造方法 =====================

	/**
	 * 构造函数
	 *
	 * @param ship 要回收的舰船（通常是残骸）
	 * @param delay 回收开始前的延迟时间（秒）
	 */
	public VIII_ThreatShipReclamationScript(ShipAPI ship, float delay) {
		this.delay = delay;
		this.primary = ship;

		// 收集所有属于同一舰队成员的碎片（包括主舰船）
		for (ShipAPI curr : Global.getCombatEngine().getShips()) {
			if (curr.getFleetMember() == ship.getFleetMember()) {
				pieces.add(curr);
			}
		}

		// 根据舰船大小设置不同的淡出时间
		switch (ship.getHullSize()) {
			case CAPITAL_SHIP:
				this.fadeOutTime = 15f;
				break;
			case CRUISER:
				this.fadeOutTime = 12f;
				break;
			case DESTROYER:
				this.fadeOutTime = 9f;
				break;
			case FRIGATE:
				this.fadeOutTime = 7f;
				break;
			default:
				this.fadeOutTime = 7f;
				break;
		}

		// 额外增加3秒淡出时间
		this.fadeOutTime += 3f;

		// 强制计时器立即触发，以便立即开始生成粒子
		interval.forceIntervalElapsed();

		// 为所有碎片添加"正在被回收"标签
		for (ShipAPI curr : pieces) {
			curr.addTag(VIII_NanoFragments.SHIP_BEING_RECLAIMED);
		}
	}

	// ===================== 公共方法 =====================

	/**
	 * 获取所有碎片列表
	 *
	 * @return 碎片列表
	 */
	public List<ShipAPI> getPieces() {
		return pieces;
	}

	// ===================== 帧更新方法 =====================

	/**
	 * 每一帧调用的更新方法
	 *
	 * @param amount 时间增量（秒）
	 * @param events 输入事件列表
	 */
	@Override
	public void advance(float amount, List<InputEventAPI> events) {
		// 如果战斗暂停，直接返回
		if (Global.getCombatEngine().isPaused()) return;

		// 更新经过的时间
		elapsed += amount;

		// 如果还没到延迟时间，直接返回
		if (elapsed < delay) return;

		// 获取战斗引擎
		CombatEngineAPI engine = Global.getCombatEngine();

		// 计算回收进度（0.0到1.0）
		float progress = (elapsed - delay) / fadeOutTime;
		if (progress < 0f) progress = 0f;
		if (progress > 1f) progress = 1f;

		// 计算剩余淡出时间
		float remaining = fadeOutTime - (elapsed - delay);

		// 如果经过2秒且剩余时间大于4秒，生成蜂群
		if (elapsed > delay + 2f && remaining > 4f) {
			spawnSwarms(amount);
		}

		// 处理所有碎片
		boolean first = true;  // 标记是否是第一个碎片（用于播放音效）
		boolean anyInEngine = false;  // 标记是否有任何碎片还在引擎中

		for (ShipAPI ship : pieces) {
			// 如果碎片不在引擎中，跳过
			if (!engine.isInEngine(ship)) continue;

			anyInEngine = true;

			// ===================== 物理模拟 =====================
			// 模拟减速效果，使碎片逐渐停止
			Vector2f vel = ship.getVelocity();
			Vector2f acc = new Vector2f(vel);
			if (acc.length() != 0) {
				acc.normalise();
				acc.scale(-1f);
				acc.scale(amount * ship.getDeceleration());
				Vector2f.add(vel, acc, vel);
				float speed = vel.length();
				if (speed <= 1 || speed < acc.length()) {
					vel.set(0, 0);
				}
			}

			// ===================== 透明度淡出 =====================
			float alpha = 1f;
			if (progress > 0.5f) {
				// 后半段从1.0线性淡出到0.0
				alpha = (1f - progress) * 2f;
			}
			ship.setAlphaMult(alpha);

			// ===================== 音效播放 =====================
			// 只在第一个碎片上播放音效
			if (first) {
				Global.getSoundPlayer().playLoop("reclamation_loop", ship, 1f, 1f, ship.getLocation(), ship.getVelocity());
				first = false;
			}

            // ===================== 生成粒子效果 =====================
			spawnParticles(ship, amount, progress);
		}

		// ===================== 回收完成处理 =====================
		// 如果淡出完成或没有碎片在引擎中，结束回收
		if (elapsed > fadeOutTime + delay || !anyInEngine) {
			for (ShipAPI ship : pieces) {
				// 从引擎中移除碎片
				engine.removeEntity(ship);
				// 确保透明度为0
				ship.setAlphaMult(0f);
			}
			// 从战斗引擎中移除这个脚本
			engine.removePlugin(this);
		}
	}

	// ===================== 粒子效果生成方法 =====================

	/**
	 * 为单个碎片生成粒子效果
	 *
	 * @param ship 要生成粒子的碎片
	 * @param amount 时间增量（秒）
	 * @param progress 回收进度（0.0到1.0）
	 */
	protected void spawnParticles(ShipAPI ship, float amount, float progress) {
		if (ship == null) return;

		// 计算剩余淡出时间
		float remaining = fadeOutTime - (elapsed - delay);

		// 更新间隔计时器
		interval.advance(amount);

		// 如果间隔已过，生成粒子
		if (interval.intervalElapsed()) {
			CombatEngineAPI engine = Global.getCombatEngine();

			// 设置粒子颜色（使用EMP边缘颜色变暗）
			Color c = RiftLanceEffect.getColorForDarkening(VIII_VoltaicDischargeOnFireEffect.EMP_FRINGE_COLOR);
			c = Misc.setAlpha(c, 50);

			// 基础粒子持续时间
			float baseDuration = 2f;

			// 获取碎片速度
			Vector2f vel = new Vector2f(ship.getVelocity());

			// 粒子大小（基于碎片碰撞半径）
			float nSize = ship.getCollisionRadius() * 0.33f;

			// 额外持续时间（在最后1秒增加）
			float extraDur = 0f;
			if (remaining < 1f) extraDur = 1f;

			// 生成多个粒子
			for (int i = 0; i < 11; i++) {
				// 在碎片半径75%范围内随机选择生成点
				Vector2f point = new Vector2f(ship.getLocation());
				point = Misc.getPointWithinRadiusUniform(point, ship.getCollisionRadius() * 0.75f, Misc.random);

				// 随机化粒子持续时间
				float dur = baseDuration + baseDuration * (float) Math.random();
				dur += extraDur;



                // 在粒子大小范围内随机选择精确位置
				Vector2f pt = Misc.getPointWithinRadius(point, nSize * 0.5f);

				// 生成随机方向的速度
				Vector2f v = Misc.getUnitVectorAtDegreeAngle((float) Math.random() * 360f);
				v.scale(nSize + nSize * (float) Math.random() * 0.5f);
				v.scale(0.2f);  // 缩放速度
				Vector2f.add(vel, v, v);  // 加上碎片速度

				// 根据速度调整持续时间（速度越快持续时间越短）
				float maxSpeed = nSize * 1.5f * 0.2f;
				float minSpeed = nSize * 1f * 0.2f;
				float overMin = v.length() - minSpeed;
				if (overMin > 0) {
					float durMult = 1f - overMin / (maxSpeed - minSpeed);
					if (durMult < 0.1f) durMult = 0.1f;
					dur *= 0.5f + 0.5f * durMult;
				}

				// 添加负星云粒子（深色粒子）
				engine.addNegativeNebulaParticle(pt, v, nSize, 2f,
						0.5f / dur, 0f, dur, c);
			}
		}
	}

	// ===================== 蜂群生成方法 =====================

	/**
	 * 生成回收蜂群
	 *
	 * @param amount 时间增量（秒）
	 */
	protected void spawnSwarms(float amount) {
		// 如果还没有生成蜂群，生成初始蜂群
		if (!spawnedSwarms) {
			// 默认蜂群数量
			int numSwarms = 3;

			// 从可建造变体列表中查找当前舰船变体，确定蜂群数量
			for (VIII_SwarmConstructableVariant curr : VIII_ConstructionSwarmSystemScript.VIII_CONSTRUCTABLE) {
				if (curr.variantId.equals(primary.getVariant().getHullVariantId())) {
					// 根据CR（战斗准备度）计算蜂群数量
					numSwarms = Math.round(curr.cr * 100f);
					break;
				}
			}

			// 生成指定数量的蜂群
			for (int i = 0; i < numSwarms; i++) {
				ShipAPI curr = launchSwarm();
				swarms.add(curr);
			}
			spawnedSwarms = true;
		}

		// 定期向蜂群中添加成员
		interval2.advance(amount * 2f);
		if (interval2.intervalElapsed()) {
			// 创建碎片选择器
			WeightedRandomPicker<ShipAPI> picker = new WeightedRandomPicker<>();
			picker.addAll(pieces);

			// 为每个蜂群添加新成员
			for (ShipAPI curr : swarms) {
				VIII_RoilingSwarmEffect swarm = VIII_RoilingSwarmEffect.getSwarmFor(curr);
				if (swarm == null) continue;

				// 如果蜂群成员数量已经达到维护基数，跳过
				if (swarm.getNumActiveMembers() > swarm.params.baseMembersToMaintain) continue;

				// 添加新成员
				VIII_RoilingSwarmEffect.VIII_SwarmMember p = swarm.addMember();

				// 随机选择一个碎片作为生成位置
				ShipAPI piece = picker.pick();
				if (piece == null) continue;

				// 在碎片半径50%范围内随机选择位置
				Vector2f loc = Misc.getPointWithinRadius(piece.getLocation(), piece.getCollisionRadius() * 0.5f);
				p.loc.set(loc);

				// 设置淡入持续时间
				p.fader.setDurationIn(0.3f);
			}
		}
	}

	// ===================== 单个蜂群生成方法 =====================

	/**
	 * 生成单个回收蜂群
	 *
	 * @return 生成的蜂群舰船
	 */
	protected ShipAPI launchSwarm() {
		// 回收蜂群的舰船变体ID
		String wingId = VIII_SwarmLauncherEffect.RECLAMATION_SWARM_WING;

		// 获取战斗引擎和舰队管理器
		CombatEngineAPI engine = Global.getCombatEngine();
		CombatFleetManagerAPI manager = engine.getFleetManager(primary.getOriginalOwner());

		// 临时抑制部署消息
		manager.setSuppressDeploymentMessages(true);

		// 在主舰船位置附近生成蜂群
		Vector2f loc = primary.getLocation();
		float facing = (float) Math.random() * 360f;

		// 生成蜂群舰船
		ShipAPI fighter = manager.spawnShipOrWing(wingId, loc, facing, 0f, null);

		// 设置蜂群的源舰船
		fighter.getWing().setSourceShip(primary);

		// 恢复部署消息设置
		manager.setSuppressDeploymentMessages(false);

		// 降低蜂群的最大速度
		fighter.getMutableStats().getMaxSpeed().modifyMult("VIII_construction_swarm", RECLAMATION_SWARM_SPEED_MULT);

		// 设置起飞速度
		Vector2f takeoffVel = Misc.getUnitVectorAtDegreeAngle(facing);
		takeoffVel.scale(fighter.getMaxSpeed());

		// ===================== 蜂群特殊设置 =====================

		// 不渲染蜂群舰船（只渲染其蜂群效果）
		fighter.setDoNotRender(true);

		// 降低爆炸规模
		fighter.setExplosionScale(0f);

		// 覆盖残骸生成几率为0
		fighter.setHulkChanceOverride(0f);

		// 设置碰撞音量倍率
		fighter.setImpactVolumeMult(VIII_SwarmLauncherEffect.IMPACT_VOLUME_MULT);

		// 清除装甲网格的组件映射（保护武器和引擎不受伤害）
		fighter.getArmorGrid().clearComponentMap();

		// 添加起飞速度到当前速度
		Vector2f.add(fighter.getVelocity(), takeoffVel, fighter.getVelocity());

		// ===================== 创建蜂群效果 =====================

		// 为蜂群舰船创建蜂群效果
		VIII_RoilingSwarmEffect swarm = VIII_FragmentSwarmHullmod.createSwarmFor(fighter);

		// 从原始集群类中移除，并添加到回收蜂群集群类
		VIII_RoilingSwarmEffect.getFlockingMap().remove(swarm.params.flockingClass, swarm);
		swarm.params.flockingClass = VIII_FragmentSwarmHullmod.RECLAMATION_SWARM_FLOCKING_CLASS;
		VIII_RoilingSwarmEffect.getFlockingMap().add(swarm.params.flockingClass, swarm);

		// 设置成员交换类
		swarm.params.memberExchangeClass = VIII_FragmentSwarmHullmod.RECLAMATION_SWARM_EXCHANGE_CLASS;

		// ===================== 蜂群效果参数设置 =====================

		// 设置闪光效果参数
		swarm.params.flashFrequency = 5f;
		swarm.params.flashProbability = 1f;

		// 设置闪光边缘颜色（棕红色/铁锈色）
		// 原注释：brownish/rusty（棕红色/铁锈色）
		swarm.params.flashFringeColor = new Color(85, 0, 255, 255);
		swarm.params.flashCoreRadiusMult = 0f;

		// 设置弹簧拉伸倍率
		swarm.params.springStretchMult = 1f;

		// 碰撞范围和生命值调整
		float collisionMult = RECLAMATION_SWARM_COLLISION_MULT;
		float hpMult = RECLAMATION_SWARM_HP_MULT;

		// 调整精确边界的所有线段
		for (SegmentAPI s : fighter.getExactBounds().getOrigSegments()) {
			s.getP1().scale(collisionMult);
			s.getP2().scale(collisionMult);
			s.set(s.getP1().x, s.getP1().y, s.getP2().x, s.getP2().y);
		}

		// 调整碰撞半径
		fighter.setCollisionRadius(fighter.getCollisionRadius() * collisionMult);

		// 调整生命值
		fighter.setMaxHitpoints(fighter.getMaxHitpoints() * hpMult);
		fighter.setHitpoints(fighter.getHitpoints() * hpMult);

		// 调整蜂群最大偏移量
		swarm.params.maxOffset *= RECLAMATION_SWARM_RADIUS_MULT;

		// 设置初始成员数和基础维护成员数
		swarm.params.initialMembers = 0;
		swarm.params.baseMembersToMaintain = 50;

		return fighter;
	}
}