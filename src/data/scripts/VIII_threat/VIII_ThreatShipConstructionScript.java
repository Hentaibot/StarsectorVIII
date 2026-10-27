package data.scripts.VIII_threat;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.impl.combat.RiftLanceEffect;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.util.vector.Vector2f;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * ThreatShipConstructionScript - 威胁舰船建造脚本
 * 这个类实现了一个舰船建造/召唤动画效果，用于在战斗中动态生成新的威胁派系舰船
 * 主要特性：
 * 1. 在指定位置生成新舰船
 * 2. 淡入动画效果
 * 3. 粒子效果和视觉特效
 * 4. 与RoilingSwarmEffect（滚动蜂群效果）集成
 * 5. 建造完成后替换源舰船
 * 这个效果通常用于威胁派系（THREAT faction）的特殊能力或事件
 */
public class VIII_ThreatShipConstructionScript extends BaseEveryFrameCombatPlugin {

	// ===================== 常量定义 =====================

	// 标签常量：用于标记正在建造蜂群的舰船
	public static String SWARM_CONSTRUCTING_SHIP = "VIII_swarm_constructing_ship";

	// 标签常量：用于标记正在被建造的舰船
	public static String SHIP_UNDER_CONSTRUCTION = "VIII_ship_under_construction";

	// 当舰船被摧毁时，淡入速度的倍率
	public static float FADE_IN_RATE_MULT_WHEN_DESTROYED = 10f;

	// ===================== 成员变量 =====================

	protected float elapsed = 0f;           // 经过的时间
	protected ShipAPI ship = null;          // 正在建造的舰船
	protected CollisionClass collisionClass; // 舰船原始的碰撞类型

	protected String variantId;             // 要建造的舰船变体ID
	protected ShipAPI source;               // 源舰船（进行建造的舰船）
	protected float delay;                  // 建造开始前的延迟时间
	protected float fadeInTime;             // 淡入动画的持续时间
	protected float origMaxSpeed = 500f;    // 源舰船蜂群效果的原始最大速度

	// 存储爆炸碎片的列表（当建造的舰船被摧毁时）
	protected List<ShipAPI> explodedPieces = new ArrayList<>();

	// 用于控制粒子生成间隔的计时器
	protected IntervalUtil interval = new IntervalUtil(0.075f, 0.125f);

	// ===================== 构造方法 =====================

	/**
	 * 构造函数
	 *
	 * @param variantId 要建造的舰船变体ID
	 * @param source 源舰船（进行建造的舰船）
	 * @param delay 建造开始前的延迟时间（秒）
	 * @param fadeInTime 淡入动画的持续时间（秒）
	 */
	public VIII_ThreatShipConstructionScript(String variantId, ShipAPI source, float delay, float fadeInTime) {
		this.variantId = variantId;
		this.source = source;
		this.delay = delay;
		this.fadeInTime = fadeInTime;

		// 强制计时器立即触发，以便立即开始生成粒子
		interval.forceIntervalElapsed();

		// 生成舰船
		spawnShip();
	}

	// ===================== 公共方法 =====================

	/**
	 * 获取正在建造的舰船
	 *
	 * @return 正在建造的舰船对象
	 */
	public ShipAPI getShip() {
		return ship;
	}

	// ===================== 舰船生成方法 =====================

	/**
	 * 生成新的舰船
	 *
	 * 这个方法负责：
	 * 1. 在源舰船附近生成新的舰船
	 * 2. 设置舰船的初始状态
	 * 3. 配置蜂群效果参数
	 * 4. 添加建造相关的标签
	 */
	protected void spawnShip() {
		// 随机化新舰船的朝向（相对于源舰船朝向±7.5度）
		float facing = source.getFacing() + 15f * ((float) Math.random() - 0.5f);

		// 使用源舰船的位置作为生成位置
		Vector2f loc = new Vector2f(source.getLocation());

		// 获取战斗引擎
		CombatEngineAPI engine = Global.getCombatEngine();

		// 获取源舰船所属的舰队管理器
		CombatFleetManagerAPI fleetManager = engine.getFleetManager(source.getOriginalOwner());

		// 临时抑制部署消息，避免在战斗中显示部署提示
		boolean wasSuppressed = fleetManager.isSuppressDeploymentMessages();
		fleetManager.setSuppressDeploymentMessages(true);

		// 生成舰船
		// 参数说明：变体ID、位置、朝向、速度、自定义数据
		ship = engine.getFleetManager(source.getOriginalOwner()).spawnShipOrWing(variantId, loc, facing, 0f, null);

		// 如果在战役模式或战役模拟中，为舰船分配威胁派系的随机名称
		if (Global.getCombatEngine().isInCampaign() || Global.getCombatEngine().isInCampaignSim()) {
			FactionAPI faction = Global.getSector().getFaction(Factions.THREAT);
			if (faction != null) {
				String name = faction.pickRandomShipName();
				ship.setName(name);
			}
		}

		// 恢复之前的部署消息抑制状态
		fleetManager.setSuppressDeploymentMessages(wasSuppressed);

		// 保存舰船的原始碰撞类型
		collisionClass = ship.getCollisionClass();

		// 获取新舰船和源舰船的蜂群效果
		VIII_RoilingSwarmEffect swarm = VIII_RoilingSwarmEffect.getSwarmFor(ship);
		VIII_RoilingSwarmEffect sourceSwarm = VIII_RoilingSwarmEffect.getSwarmFor(source);

		// 配置新舰船的蜂群效果
		if (swarm != null) {
			swarm.params.withInitialMembers = false;  // 不生成初始成员
			swarm.params.withRespawn = false;         // 禁用重生
		}

		// 配置源舰船的蜂群效果
		if (sourceSwarm != null) {
			// 保存源舰船蜂群效果的原始最大速度
			origMaxSpeed = sourceSwarm.params.maxSpeed;

			// 暂时降低源舰船蜂群效果的最大速度（被注释掉了）
			// sourceSwarm.params.maxSpeed *= 0.25f;

			// 设置蜂群成员不超出附着实体
			sourceSwarm.params.outspeedAttachedEntityBy = 0f;

			// 如果新舰船也有蜂群效果，同步一些参数
			if (swarm != null) {
                swarm.params.flashFringeColor = sourceSwarm.params.flashFringeColor;
			}
		}

		// 为新舰船添加"正在建造"标签
		ship.addTag(SHIP_UNDER_CONSTRUCTION);

		// 为源舰船添加"正在建造蜂群"标签
		source.addTag(SWARM_CONSTRUCTING_SHIP);

		// 禁用源舰船的碰撞，避免在建造过程中被击中
		source.setCollisionClass(CollisionClass.NONE);

		// 使源舰船暂时免疫船体伤害（伤害倍率设为0）
		source.getMutableStats().getHullDamageTakenMult().modifyMult("VIII_ThreatShipConstructionScript", 0f);

		// 禁用新舰船的AI
		ship.setShipAI(null);

		// 关闭新舰船的所有武器组
		for (WeaponGroupAPI g : ship.getWeaponGroupsCopy()) {
			g.toggleOff();
		}
	}

	// ===================== 帧更新方法 =====================

	// 跟踪舰船成为残骸的时间
	protected float hulkFor = 0f;

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

		// 如果正在建造的舰船变成了残骸
		if (ship.isHulk()) {
			hulkFor += amount;

			// 当舰船被摧毁时，加速淡入过程
			amount *= FADE_IN_RATE_MULT_WHEN_DESTROYED;

			// 舰船分裂成碎片不是立即发生的，需要等待一段时间
			if (explodedPieces.isEmpty() || hulkFor < 0.25f) {
				explodedPieces.clear();

				// 收集所有属于这个舰船或由其分裂出的碎片
				for (ShipAPI curr : Global.getCombatEngine().getShips()) {
					if (curr.getFleetMember() == ship.getFleetMember() ||
							(curr.getParentPieceId() != null && curr.getParentPieceId().equals(ship.getId()))) {
						explodedPieces.add(curr);
					}
				}
			}
			// 注释掉的代码：立即完成淡入（在爆炸效果隐藏时）
			// elapsed += delay + fadeInTime;
		}

		// 更新经过的时间
		elapsed += amount;

		// 如果还没到延迟时间，直接返回
		if (elapsed < delay) return;

		// 获取战斗引擎
		CombatEngineAPI engine = Global.getCombatEngine();

		// 计算建造进度（0.0到1.0）
		float progress = (elapsed - delay) / fadeInTime;
		if (progress > 1f) progress = 1f;

		// 计算剩余淡入时间
		float remaining = fadeInTime - (elapsed - delay);

		// 设置新舰船的透明度（从0到1）
		ship.setAlphaMult(progress);

		// 设置所有碎片的透明度
		for (ShipAPI curr : explodedPieces) {
			curr.setAlphaMult(progress);
		}

		// 设置有效装甲加成的倍率（随着建造进度增加）
		// 使用progress * progress使效果在开始时较弱，结束时较强
		ship.getMutableStats().getEffectiveArmorBonus().modifyMult("VIII_ThreatShipConstructionScript", progress * progress);

		// 播放建造循环音效
		Global.getSoundPlayer().playLoop("construction_swarm_loop", ship, 1f, 1f, ship.getLocation(), ship.getVelocity());

		// 如果剩余时间大于1秒，将源舰船移动到新舰船的位置
		if (remaining > 1f) {
			Vector2f deltaLoc = Vector2f.sub(ship.getLocation(), source.getLocation(), new Vector2f());
			source.getLocation().set(ship.getLocation());

			// 更新源舰船蜂群效果中所有成员的位置
			VIII_RoilingSwarmEffect sourceSwarm = VIII_RoilingSwarmEffect.getSwarmFor(source);
			if (sourceSwarm != null) {
				for (VIII_RoilingSwarmEffect.VIII_SwarmMember p : sourceSwarm.members) {
					Vector2f.add(p.loc, deltaLoc, p.loc);
				}
			}

			// 让新舰船减速
			ship.giveCommand(ShipCommand.DECELERATE, null, 0);
		}

		// ===================== 抖动效果计算 =====================

		// 计算抖动等级
		float jitterLevel = progress;
		if (fadeInTime <= 4f) {
			// 对于短时间的淡入，抖动在中间最强
			if (jitterLevel < 0.5f) {
				jitterLevel *= 2f;
			} else {
				jitterLevel = (1f - jitterLevel) * 2f;
			}
		} else {
			// 对于长时间的淡入，抖动在开始后持续，最后2秒减弱
			if (jitterLevel < 0.5f) {
				jitterLevel *= 2f;
			} else if (remaining <= 2f) {
				jitterLevel = remaining / 2f;
			} else {
				jitterLevel = 1f;
			}
		}

		// 对抖动等级进行平方根处理，使效果更平滑
		jitterLevel = (float) Math.sqrt(jitterLevel);

		// 计算抖动范围
		float jitterRange = 1f;
		if (remaining < 2f) {
			// 最后2秒逐渐减小抖动范围
			jitterRange = remaining / 2f;
		} else {
			// 前fadeInTime-2秒逐渐增加抖动范围
			jitterRange = (elapsed - delay) / Math.max(1f, fadeInTime - 2f);
		}

		// 抖动范围加成（最大25像素）
		float maxRangeBonus = 25f;
		float jitterRangeBonus = jitterRange * maxRangeBonus;

		// 设置抖动效果的颜色（使用EMP边缘颜色）
		Color c = VIII_VoltaicDischargeOnFireEffect.EMP_FRINGE_COLOR;
		c = Misc.setAlpha(c, 127);

		// 应用抖动效果到新舰船
		ship.setJitter(this, new Color(0, 51, 255, 255), jitterLevel, 3, 0f, jitterRangeBonus);

		// 淡出引擎颜色效果
		ship.getEngineController().fadeToOtherColor(this, Misc.zeroColor, Misc.zeroColor, 1f, 1f);

		// ===================== 源舰船蜂群效果更新 =====================

		VIII_RoilingSwarmEffect sourceSwarm = VIII_RoilingSwarmEffect.getSwarmFor(source);
		if (sourceSwarm != null) {
			// 根据建造进度调整蜂群速度
			float speedMult = 0.25f + 0.75f * Math.max(0f, 1f - (elapsed - delay) / 2f);
			sourceSwarm.params.maxSpeed = origMaxSpeed * speedMult;

			// 如果剩余时间大于3秒，增加闪光效果
			if (remaining > 3f) {
				// 根据蜂群成员数量计算闪光频率倍率
				float numFragMult = sourceSwarm.params.initialMembers / 150f;
				if (numFragMult < 0.25f) numFragMult = 0.25f;
				if (numFragMult > 1f) numFragMult = 1f;

				// 配置闪光效果参数
				sourceSwarm.params.flashFrequency = 5f * progress * 2f * numFragMult;
				sourceSwarm.params.flashProbability = 1f;
				sourceSwarm.params.flashFringeColor = VIII_VoltaicDischargeOnFireEffect.EMP_FRINGE_COLOR;
				sourceSwarm.params.flashFringeColor = Misc.setAlpha(sourceSwarm.params.flashFringeColor, 200);
				sourceSwarm.params.flashCoreRadiusMult = 1.5f;
				sourceSwarm.params.flashRadius = 50f;
				sourceSwarm.params.renderFlashOnSameLayer = true;
			} else {
				// 最后3秒减少闪光效果
				sourceSwarm.params.flashFrequency = 1f;
				sourceSwarm.params.flashProbability = 0f;
			}
		}

		// ===================== 生成粒子效果 =====================

		spawnParticles(amount);

		// ===================== 建造完成处理 =====================

		// 如果建造完成（经过延迟+淡入时间）
		if (elapsed > fadeInTime + delay) {
			// 恢复新舰船的AI
			ship.setDefaultAI(null);

			// 移除建造标签
			ship.removeTag(SHIP_UNDER_CONSTRUCTION);

			// 恢复完全可见
			ship.setAlphaMult(1f);

			// 允许开火
			ship.setHoldFire(false);

			// 恢复碰撞类型
			ship.setCollisionClass(collisionClass);

			// 移除装甲加成效果
			ship.getMutableStats().getEffectiveArmorBonus().unmodifyMult("VIII_ThreatShipConstructionScript");

			// 从战斗引擎中移除这个脚本
			engine.removePlugin(this);

			// 如果源舰船有蜂群效果，禁用其消失音效
			if (sourceSwarm != null) {
				sourceSwarm.getParams().despawnSound = null;
			}

			// 获取新舰船的蜂群效果
			VIII_RoilingSwarmEffect swarm = VIII_RoilingSwarmEffect.getSwarmFor(ship);

			// 如果新旧舰船都有蜂群效果，转移部分成员
			if (swarm != null && sourceSwarm != null) {
				int transfer = Math.min(swarm.params.baseMembersToMaintain, sourceSwarm.getNumActiveMembers());
				sourceSwarm.transferMembersTo(swarm, transfer);
			}

			// 启用新舰船的蜂群效果
			if (swarm != null) {
				swarm.params.withRespawn = true;
				swarm.params.withInitialMembers = true;
			}

			// 摧毁源舰船
			source.setHitpoints(0f);
			source.setSpawnDebris(false);

			// 应用能量伤害摧毁源舰船
			engine.applyDamage(source, source.getLocation(), 100f, DamageType.ENERGY, 0f, true, false, source, false);
		}
	}

	// ===================== 粒子效果生成方法 =====================

	/**
	 * 生成粒子效果
	 *
	 * @param amount 时间增量（秒）
	 */
	protected void spawnParticles(float amount) {
		if (ship == null) return;

		// 计算剩余淡入时间
		float remaining = fadeInTime - (elapsed - delay);

		// 更新间隔计时器
		interval.advance(amount);

		// 如果间隔已过且剩余时间大于1秒，生成粒子
		if (interval.intervalElapsed() && remaining > 1f) {
			// 获取源舰船的蜂群效果
			VIII_RoilingSwarmEffect swarm = VIII_RoilingSwarmEffect.getSwarmFor(source);

			// 随机偏移部分蜂群成员的位置
			if (swarm != null) {
				for (VIII_RoilingSwarmEffect.VIII_SwarmMember p : swarm.members) {
					if ((float) Math.random() > 0.9f) {  // 10%的概率
						p.rollOffset(swarm.params, ship);
					}
				}
			}

			// 获取战斗引擎
			CombatEngineAPI engine = Global.getCombatEngine();

			// 设置粒子颜色（使用EMP边缘颜色变暗）
			Color c = RiftLanceEffect.getColorForDarkening(VIII_VoltaicDischargeOnFireEffect.EMP_FRINGE_COLOR);
			c = Misc.setAlpha(c, 50);

			// 基础粒子持续时间
			float baseDuration = 2f;

			// 获取舰船速度
			Vector2f vel = new Vector2f(ship.getVelocity());

			// 粒子大小（基于舰船碰撞半径）
			float size = ship.getCollisionRadius() * 0.33f;

			// 额外持续时间（在最后1秒增加）
			float extraDur = 0f;

            // 生成多个粒子
			for (int i = 0; i < 11; i++) {
				// 在舰船半径75%范围内随机选择生成点
				Vector2f point = new Vector2f(ship.getLocation());
				point = Misc.getPointWithinRadiusUniform(point, ship.getCollisionRadius() * 0.75f, Misc.random);

				// 随机化粒子持续时间
				float dur = baseDuration + baseDuration * (float) Math.random();
				dur += extraDur;

				// 粒子大小

                // 在粒子大小范围内随机选择精确位置
				Vector2f pt = Misc.getPointWithinRadius(point, size * 0.5f);

				// 生成随机方向的速度
				Vector2f v = Misc.getUnitVectorAtDegreeAngle((float) Math.random() * 360f);
				v.scale(size + size * (float) Math.random() * 0.5f);
				v.scale(0.2f);  // 缩放速度
				Vector2f.add(vel, v, v);  // 加上舰船速度

				// 根据速度调整持续时间（速度越快持续时间越短）
				float maxSpeed = size * 1.5f * 0.2f;
				float minSpeed = size * 1f * 0.2f;
				float overMin = v.length() - minSpeed;
				if (overMin > 0) {
					float durMult = 1f - overMin / (maxSpeed - minSpeed);
					if (durMult < 0.1f) durMult = 0.1f;
					dur *= 0.5f + 0.5f * durMult;
				}

				// 添加负星云粒子（深色粒子）
				engine.addNegativeNebulaParticle(pt, v, size, 2f,
						0.5f / dur, 0f, dur, c);
			}
		}
	}
}