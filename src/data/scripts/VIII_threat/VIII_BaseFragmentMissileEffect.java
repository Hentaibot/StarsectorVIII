// 包路径：远行星号游戏API实现中的战斗威胁包
package data.scripts.VIII_threat;

// 导入全局游戏实例类
import com.fs.starfarer.api.Global;
// 导入战斗相关接口
import com.fs.starfarer.api.combat.*;
// 导入EMP电弧参数内部类
import com.fs.starfarer.api.combat.EmpArcEntityAPI.EmpArcParams;
// 导入武器AI提示枚举
import com.fs.starfarer.api.combat.WeaponAPI.AIHints;
import data.scripts.VIII_threat.VIII_RoilingSwarmEffect.*;
// 导入杂项工具类
import com.fs.starfarer.api.util.Misc;
// 导入加权随机选择器
import com.fs.starfarer.api.util.WeightedRandomPicker;
// 导入LWJGL二维向量类
import org.lwjgl.util.vector.Vector2f;

// 导入AWT颜色类
import java.awt.*;
// 导入链接哈希集合和集合接口
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 基础碎片导弹效果类
 * 实现开火效果插件、每帧武器效果插件和碎片武器接口
 * 负责处理碎片导弹的生成、转移、碰撞、特效等逻辑
 */
public class VIII_BaseFragmentMissileEffect implements OnFireEffectPlugin, EveryFrameWeaponEffectPlugin, VIII_FragmentWeapon {

	// 内部枚举：碎片命中后的行为模式
	public enum VIII_FragmentBehaviorOnImpact {
		STOP_AND_FADE,      // 停止并淡出
		STOP_AND_FLASH,     // 停止并闪烁
		KEEP_GOING,         // 继续前进
	}

	// 当前投射物（可能是导弹）
	protected DamagingProjectileAPI projectile;
	// 当前武器
	protected WeaponAPI weapon;
	// 战斗引擎实例
	protected CombatEngineAPI engine;
	// 源翻涌群特效（发射舰船周围的碎片群）
	protected VIII_RoilingSwarmEffect sourceSwarm;
	// 当前导弹实例
	protected MissileAPI missile;
	// 发射舰船
	protected ShipAPI ship;

	// 无参构造函数
	public VIII_BaseFragmentMissileEffect() {
	}

	// 每帧更新方法（EveryFrameWeaponEffectPlugin接口）
	@Override
	public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
		// 获取武器所属舰船
		ShipAPI ship = weapon.getShip();
		if (ship == null) return;   // 无舰船则返回

		// 获取该舰船的翻涌群特效
		VIII_RoilingSwarmEffect swarm = VIII_RoilingSwarmEffect.getSwarmFor(ship);
		// 活跃成员数量（碎片数量）
		int active = swarm == null ? 0 : swarm.getNumActiveMembers();
		// 需要发射的碎片数量
		int required = getNumFragmentsToFire();
		// 如果活跃碎片不足所需，则禁用武器
		boolean disable = active < required;
		weapon.setForceDisabled(disable);

		// 显示无碎片群警告（可能用于调试或UI提示）
		showNoFragmentSwarmWarning(weapon, ship);
	}

	// 开火时调用（OnFireEffectPlugin接口）
	public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
		// 保存参数到成员变量
		this.projectile = projectile;
		this.weapon = weapon;
		this.engine = engine;

		// 如果投射物不是导弹，则移除并返回
		if (!(projectile instanceof MissileAPI)) {
			engine.removeEntity(projectile);
			return;
		}
		missile = (MissileAPI) projectile;
		// 导弹没有发射源，移除
		if (missile.getSource() == null) {
			engine.removeEntity(projectile);
			return;
		}

		// 获取发射舰船
		ship = missile.getSource();
		// 获取发射舰船关联的翻涌群特效
		sourceSwarm = VIII_RoilingSwarmEffect.getSwarmFor(missile.getSource());
		if (sourceSwarm == null) {
			engine.removeEntity(projectile);  // 无碎片群，无法生成碎片导弹
			return;
		}

		// 设置导弹的EMP抗性（从子类获取）
		missile.setEmpResistance(getEMPResistance());

		// 选取主要碎片（作为导弹的核心）
		VIII_SwarmMember fragment = pickPrimaryFragment();
		if (fragment == null) {
			engine.removeEntity(projectile);  // 无可用碎片，移除导弹
			return;
		}

		// 如果导弹的武器没有AI提示RANGE_FROM_SHIP_RADIUS，则设置导弹的起始位置（实际未使用start字段？）
		if (missile.getWeapon() == null || !missile.getWeapon().hasAIHint(AIHints.RANGE_FROM_SHIP_RADIUS)) {
			missile.setStart(new Vector2f(missile.getLocation()));
		}
		// 将导弹位置设置为碎片的位置
		missile.getLocation().set(fragment.loc);

		// 如果不应该选择速度匹配的主要碎片（即需要继承碎片速度），则设置导弹速度为碎片速度
		if (!shouldPickVelocityMatchingPrimaryFragment()) {
			missile.getVelocity().set(fragment.vel);
			boolean setFacing = false;
			// 如果需要让导弹面朝目标（子类可重写）
			if (shouldMakeMissileFaceTargetOnSpawnIfAny()) {
				// 如果导弹AI是制导AI且存在目标，则设置导弹朝向目标
				if (missile.getAI() instanceof GuidedMissileAI ai) {
                    if (ai.getTarget() != null) {
						missile.setFacing(Misc.getAngleInDegrees(fragment.loc, ai.getTarget().getLocation()));
						setFacing = true;
					}
				}
			}
			// 如果未设置朝向且碎片速度不为零，则设置导弹朝向为速度方向
			if (!setFacing && fragment.vel.length() > 0.1f) {
				missile.setFacing(Misc.getAngleInDegrees(fragment.vel));
			}
		}

		// 创建导弹自身携带的翻涌群特效参数
		VIII_RoilingSwarmParams params = new VIII_RoilingSwarmParams();
		params.despawnSound = null;                     // 无消失音效
		params.maxSpeed = missile.getMaxSpeed() + 100f; // 最大速度略高于导弹最大速度
		params.baseMembersToMaintain = 0;               // 不维持基础成员数
		params.removeMembersAboveMaintainLevel = false; // 不自动移除超出维持数的成员
		params.keepProxBasedScaleForAllMembers = true;  // 所有成员保持基于距离的缩放
		params.initialMembers = 0;                      // 初始成员0
		params.maxOffset = missile.getCollisionRadius() * 1.5f; // 最大偏移半径

		// 允许子类修改参数
		configureMissileSwarmParams(params);

		// 保存需要在匿名内部类中使用的局部变量（因为成员变量可能变化）
		MissileAPI missile2 = missile;
		VIII_FragmentBehaviorOnImpact behavior = getOtherFragmentBehaviorOnImpact();
		boolean explodeOnFizzling = explodeOnFizzling();
		String explosionSoundId = getExplosionSoundId();
		// 创建导弹的翻涌群特效实例
		VIII_RoilingSwarmEffect missileSwarm = new VIII_RoilingSwarmEffect(missile2, params) {
			boolean exploded = false;                   // 是否已爆炸标记
			final Set<VIII_SwarmMember> stopped = new LinkedHashSet<>(); // 已停止的碎片集合
			int origMembers = 0;                        // 原始成员数
			boolean inited = false;                     // 是否已初始化

			@Override
			public void advance(float amount) {
				super.advance(amount);                  // 调用父类advance
				// 如果导弹因失去结构值而需要移除碎片（子类可配置）
				if (removeFragmentsWhenMissileLosesHitpoints() && !missile2.didDamage()) {
					if (!inited) {
						origMembers = members.size();   // 记录初始成员数
						inited = true;
					}
					if (origMembers > 0 && members.size() > 1 && missile2.getMaxHitpoints() > 0) {
						float max = missile2.getMaxHitpoints();
						float hpPerMember = max / origMembers;   // 每个成员代表的结构值
						float hpLost = max - missile2.getHitpoints(); // 已损失结构
						int loseMembers = (int) (hpLost / hpPerMember); // 应损失的成员数
						int num = members.size();
						int alreadyLost = origMembers - num;     // 已损失的数量
						// 统计正在淡出的成员数
						for (VIII_SwarmMember p : members) {
							if (p.fader.isFadingOut()) {
								alreadyLost++;
							}
						}
						int lose = loseMembers - alreadyLost;
						if (lose > 0) {
							despawnMembers(lose, false);   // 移除指定数量的成员
						}
					}
				}

				// 将主要碎片的位置和速度同步到导弹
				fragment.loc.set(missile2.getLocation());
				fragment.vel.set(missile2.getVelocity());
				// 如果导弹正在熄火，同步亮度
				if (missile2.isFizzling() && engine.isMissileAlive(missile2)) {
					fragment.fader.setBrightness(missile2.getCurrentBaseAlpha());
				}
				// 如果导弹已造成伤害（命中目标）
				if (missile2.didDamage()) {
					// 如果行为不是继续前进，则处理其他碎片的命中
					if (behavior != VIII_FragmentBehaviorOnImpact.KEEP_GOING) {
						CombatEntityAPI target = null;
						if (missile2.getDamageTarget() != null) {
							target = missile2.getDamageTarget();
						}
						for (VIII_SwarmMember p : members) {
							// 跳过主要碎片和已停止的碎片
							if (p == fragment || stopped.contains(p)) {
								continue;
							}
							boolean hit = false;
							// 如果有目标且有精确边界
							if (target != null && target.getExactBounds() != null) {
								if (target instanceof ShipAPI ship) {
                                    if (ship.getShield() != null) {
										// 检查是否在护盾弧内且距离小于护盾半径
										boolean inArc = ship.getShield().isWithinArc(p.loc);
										if (inArc) {
											hit = Misc.getDistance(p.loc, ship.getShieldCenterEvenIfNoShield()) <
													ship.getShieldRadiusEvenIfNoShield();
										}
									}
								}
								if (!hit) {
									hit = target.isPointInBounds(p.loc); // 点在边界内即命中
								}
							} else {
								// 无目标时，判断碎片是否在导弹飞行方向的前方
								Vector2f toP = Vector2f.sub(p.loc, fragment.loc, new Vector2f());
								hit = Vector2f.dot(toP, fragment.vel) > 0;
							}
							if (hit) {
								p.vel.set(new Vector2f());  // 停止碎片速度
								if (behavior == VIII_FragmentBehaviorOnImpact.STOP_AND_FLASH) {
									p.flash();              // 闪烁特效
								}
								reportFragmentHit(missile2, p, this, target); // 报告碎片命中
								stopped.add(p);            // 标记为已停止
							}
						}
					}
				}
				// 如果需要爆炸且导弹熄火/生命归零但未造成伤害，且尚未爆炸
				if (explodeOnFizzling && explosionSoundId != null) {
					if ((missile2.isFizzling() || (missile2.getHitpoints() <= 0 && !missile2.didDamage())) && !exploded) {
						exploded = true;
						// 播放爆炸音效
						Global.getSoundPlayer().playSound(explosionSoundId, 1f, 1f, missile2.getLocation(), missile2.getVelocity());
						missile2.interruptContrail();   // 中断尾迹
						engine.removeEntity(missile2);  // 移除导弹
						missile2.explode();             // 触发爆炸效果
					}
				}

				// 如果导弹正在熄火或生命归零但未造成伤害且尚未爆炸，则强制让碎片群消失
				if ((missile2.isFizzling() || missile2.getHitpoints() <= 0) && !missile2.didDamage() && !exploded) {
					params.minDespawnTime = 0.5f;
					params.maxDespawnTime = 1f;
					params.minFadeoutTime = 0.5f;
					params.maxFadeoutTime = 1f;
					setForceDespawn(true);
				}

				// 调用子类扩展的advance逻辑
				swarmAdvance(amount, missile2, this);
			}
		};

		// 从源碎片群中移除选中的主要碎片
		sourceSwarm.removeMember(fragment);
		// 将主要碎片添加到导弹的碎片群
		missileSwarm.addMember(fragment);
		// 让碎片在导弹周围随机偏移
		fragment.rollOffset(missileSwarm.params, missile);

		// 如果需要让主要碎片发光
		if (makePrimaryFragmentGlow()) {
			if (fragment.flash != null) {
				fragment.flash = null;
			}
			fragment.flashNext = null;
			fragment.flash();               // 触发闪烁
			fragment.flash.setBounceDown(false); // 禁止向下弹跳
		}

		// 获取需要转移的其他碎片数量
		int transfer = getNumOtherMembersToTransfer();
		if (transfer > 0) {
			// 从源碎片群转移指定数量的碎片到导弹碎片群，限定在碎片附近范围内
			sourceSwarm.transferMembersTo(missileSwarm, transfer, fragment.loc, getRangeForNearbyFragments());
		}

		// 获取需要额外添加的碎片数量
		int add = getNumOtherMembersToAdd();
		// 如果启用“数量不足时添加新成员”且转移后实际成员数少于预期，则补足差额
		if (addNewMembersIfNotEnoughToTransfer() && missileSwarm.members.size() - 1 < transfer) {
			add += transfer - (missileSwarm.members.size() - 1);
		}
		if (add > 0) {
			missileSwarm.addMembers(add);   // 添加新成员
		}

		// 通知子类碎片群已创建
		swarmCreated(missile, missileSwarm, sourceSwarm);

		// 计算发射舰船因转移碎片而损失的结构值
		float hpLoss = getHPLossPerTransferredMember();
		hpLoss *= 1 + transfer;  // 主要碎片 + 转移碎片数
		if (hpLoss > 0) {
			ship.setHitpoints(ship.getHitpoints() - hpLoss); // 减少舰船结构
			// 如果舰船结构归零，则摧毁舰船
			if (ship.getHitpoints() <= 0) {
				ship.setSpawnDebris(false);  // 不生成残骸
				engine.applyDamage(ship, ship.getLocation(), 100f, DamageType.ENERGY, 0f, true, false, missile, false);
			}
		}

		// 如果需要生成EMP电弧（子类可配置）
		if (withEMPArc()) {
			spawnEMPArc();
		}
	}

	// 碎片群创建后的钩子方法（子类可覆盖）
	protected void swarmCreated(MissileAPI missile, VIII_RoilingSwarmEffect missileSwarm, VIII_RoilingSwarmEffect sourceSwarm) {

	}

	// 碎片命中目标时的报告钩子（子类可覆盖）
	protected void reportFragmentHit(MissileAPI missile, VIII_SwarmMember p, VIII_RoilingSwarmEffect swarm, CombatEntityAPI target) {

	}

	// 获取每个转移碎片导致的舰船结构损失值（战机专用）
	protected float getHPLossPerTransferredMember() {
		if (!ship.isFighter()) return 0f;   // 非战机不损失结构
		// 损失值 = 舰船最大结构 / (源碎片群维持成员数 * 0.8)
        return ship.getMaxHitpoints() / (sourceSwarm.params.baseMembersToMaintain * 0.8f);
	}

	// 配置导弹碎片群的参数（子类可覆盖）
	protected void configureMissileSwarmParams(VIII_RoilingSwarmParams params) {
		params.flashFringeColor = new Color(255,50,50,255); // 闪烁边缘色：红色
		params.flashCoreColor = Color.white;                // 闪烁核心色：白色
		params.flashRadius = 60f;                           // 闪烁半径
		params.flashCoreRadiusMult = 0.75f;                 // 核心半径倍率
	}

	// 是否应该选择速度匹配的主要碎片（子类可覆盖）
	protected boolean shouldPickVelocityMatchingPrimaryFragment() {
		if (missile.getAI() instanceof GuidedMissileAI ai) {
            // 有目标时不要求速度匹配
            return ai.getTarget() == null;    // 无目标时选择速度匹配的碎片
		}
		return true;
	}

	// 是否应该让导弹在生成时面朝目标（子类可覆盖）
	protected boolean shouldMakeMissileFaceTargetOnSpawnIfAny() {
		return false;
	}

	// 选择主要碎片（优先按速度匹配或按距离选择）
	protected VIII_SwarmMember pickPrimaryFragment() {
		if (shouldPickVelocityMatchingPrimaryFragment()) {
			return pickVelocityMatchingFragmentWithinRange(getRangeFromSourceToPickFragments());
		}
		return pickOuterFragmentWithinRange(getRangeFromSourceToPickFragments());
	}

	// 在指定范围内选择最靠外的碎片（距离源中心最远）
	protected VIII_SwarmMember pickOuterFragmentWithinRange(float range) {
		VIII_SwarmMember best = null;
		float maxDist = -Float.MAX_VALUE;
		// 获取源碎片群的加权随机选择器（仅包含可见成员）
		WeightedRandomPicker<VIII_SwarmMember> picker = sourceSwarm.getPicker(true, true);
		while (!picker.isEmpty()) {
			VIII_SwarmMember p = picker.pickAndRemove();
			float dist = Misc.getDistance(p.loc, sourceSwarm.getAttachedTo().getLocation());
			// 如果碎片群在附着的实体周围生成椭圆形偏移，则调整距离计算
			if (sourceSwarm.params.generateOffsetAroundAttachedEntityOval) {
				dist -= Misc.getTargetingRadius(p.loc, sourceSwarm.attachedTo, false) + sourceSwarm.params.maxOffset - range * 0.5f;
			}
			if (dist > maxDist && dist < range) {
				best = p;
				maxDist = dist;
			}
		}
		return best;
	}

	// 在指定范围内选择速度与导弹最接近的碎片
	protected VIII_SwarmMember pickVelocityMatchingFragmentWithinRange(float range) {
		Vector2f vel = missile.getVelocity();
		VIII_SwarmMember best = null;
		float maxVelDiff = 0f;
		WeightedRandomPicker<VIII_SwarmMember> picker = sourceSwarm.getPicker(true, true);
		while (!picker.isEmpty()) {
			VIII_SwarmMember p = picker.pickAndRemove();
			float dist = Misc.getDistance(p.loc, sourceSwarm.getAttachedTo().getLocation());
			if (sourceSwarm.params.generateOffsetAroundAttachedEntityOval) {
				dist -= Misc.getTargetingRadius(p.loc, sourceSwarm.attachedTo, false) + sourceSwarm.params.maxOffset - range * 0.5f;
			}
			float velDiff = Misc.getDistance(p.vel, vel);  // 速度差
			if (velDiff > maxVelDiff && dist < range) {
				best = p;
				maxVelDiff = dist;
			}
		}
		return best;
	}

	// 在指定范围内选择距离另一个点最近的碎片（未使用）
	protected VIII_SwarmMember pickOuterFragmentWithinRangeClosestTo(float range, Vector2f otherLoc) {
		VIII_SwarmMember best = null;
		float minDist = Float.MAX_VALUE;
		WeightedRandomPicker<VIII_SwarmMember> picker = sourceSwarm.getPicker(true, true);
		while (!picker.isEmpty()) {
			VIII_SwarmMember p = picker.pickAndRemove();
			float dist = Misc.getDistance(p.loc, sourceSwarm.getAttachedTo().getLocation());
			if (sourceSwarm.params.generateOffsetAroundAttachedEntityOval) {
				dist -= Misc.getTargetingRadius(p.loc, sourceSwarm.attachedTo, false) + sourceSwarm.params.maxOffset - range * 0.5f;
			}
			if (dist > range) continue;
			dist = Misc.getDistance(p.loc, otherLoc);
			if (dist < minDist) {
				best = p;
				minDist = dist;
			}
		}
		return best;
	}

	// 是否当导弹损失结构值时移除碎片（子类可覆盖）
	protected boolean removeFragmentsWhenMissileLosesHitpoints() {
		return true;
	}

	// 是否让主要碎片发光（子类可覆盖）
	protected boolean makePrimaryFragmentGlow() {
		return true;
	}

	// 获取转移碎片时的附近范围（像素）
	protected float getRangeForNearbyFragments() {
		return 75f;
	}

	// 从源碎片群选择碎片时的搜索范围（像素）
	protected float getRangeFromSourceToPickFragments() {
		return 150f;
	}

	// 需要转移的其他碎片数量（子类可覆盖）
	protected int getNumOtherMembersToTransfer() {
		return 0;
	}

	// 如果转移数量不足是否添加新成员（子类可覆盖）
	protected boolean addNewMembersIfNotEnoughToTransfer() {
		return true;
	}

	// 需要额外添加的碎片数量（子类可覆盖）
	protected int getNumOtherMembersToAdd() {
		return 0;
	}

	// 导弹的EMP抗性（子类可覆盖）
	protected int getEMPResistance() {
		return 0;
	}

	// 其他碎片命中后的行为（子类可覆盖）
	protected VIII_FragmentBehaviorOnImpact getOtherFragmentBehaviorOnImpact() {
		return VIII_FragmentBehaviorOnImpact.STOP_AND_FLASH;
	}

	// 需要发射的碎片总数（实现FragmentWeapon接口）
	@Override
	public int getNumFragmentsToFire() {
		return 1 + getNumOtherMembersToTransfer();  // 主要碎片 + 转移碎片
	}

	// 是否在导弹熄火时爆炸（子类可覆盖）
	protected boolean explodeOnFizzling() {
		return false;
	}

	// 爆炸音效ID（子类可覆盖）
	protected String getExplosionSoundId() {
		return null;
	}

	// 碎片群每帧advance的扩展钩子（子类可覆盖）
	protected void swarmAdvance(float amount, MissileAPI missile, VIII_RoilingSwarmEffect swarm) {

	}

	// 是否生成EMP电弧（子类可覆盖，战机不生成）
	protected boolean withEMPArc() {
		return !ship.isFighter();
	}

	// 获取EMP电弧边缘颜色（从武器规格的发光色获取）
	protected Color getEMPFringeColor() {
        //c = Misc.setAlpha(c, 127);
		//c = Misc.scaleColorOnly(c, 0.75f);
		return weapon.getSpec().getGlowColor();
	}

	// 获取EMP电弧核心颜色（白色）
	protected Color getEMPCoreColor() {
		return Color.white;
	}

	// 生成EMP电弧（视觉特效）
	protected void spawnEMPArc() {
		// 从武器开火点发射电弧到导弹位置
		Vector2f from = weapon.getFirePoint(0);

		EmpArcParams params = new EmpArcParams();
		params.segmentLengthMult = 4f;          // 分段长度倍率
		params.glowSizeMult = 0.5f;             // 光晕大小倍率
		params.brightSpotFadeFraction = 0.33f;  // 高亮点淡出比例
		params.brightSpotFullFraction = 0.5f;   // 高亮点完全显现比例
		params.movementDurMax = 0.2f;           // 最大移动持续时间
		params.flickerRateMult = 0.5f;          // 闪烁率倍率

		float dist = Misc.getDistance(from, missile.getLocation());
		float minBright = 100f;
		// 调整高亮点比例，确保近距离时有足够的亮度
		if (dist * params.brightSpotFullFraction < minBright) {
			params.brightSpotFullFraction = minBright / Math.max(minBright, dist);
		}

		float thickness = 20f;  // 电弧厚度

		// 生成视觉EMP电弧（不造成伤害）
		EmpArcEntityAPI arc = engine.spawnEmpArcVisual(from, weapon.getShip(),
				missile.getLocation(),
				missile,
				thickness,                    // 厚度
				getEMPFringeColor(),          // 边缘颜色
				getEMPCoreColor(),            // 核心颜色
				params
		);
		// 设置单次闪烁模式
		arc.setSingleFlickerMode(true);
		// 每帧更新电弧位置（跟随导弹）
		arc.setUpdateFromOffsetEveryFrame(true);
	}

}