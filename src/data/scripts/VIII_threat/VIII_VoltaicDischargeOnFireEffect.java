package data.scripts.VIII_threat;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.EmpArcEntityAPI.EmpArcParams;
import com.fs.starfarer.api.combat.WeaponAPI.AIHints;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.WeightedRandomPicker;
import org.lwjgl.util.vector.Vector2f;
import data.scripts.VIII_threat.VIII_RoilingSwarmEffect.VIII_SwarmMember;

import java.awt.*;
import java.util.Iterator;

/**
 * VoltaicDischargeOnFireEffect - 伏特放电开火效果
 *
 * 这个类实现了伏特放电武器的特殊效果，包括：
 * 1. 从舰船的蜂群中选取碎片发射EMP电弧
 * 2. 支持相位模式和普通模式两种效果
 * 3. 根据蜂群活跃成员数量控制武器可用性
 * 4. 智能目标选择和电弧生成
 *
 * 这是一个复杂的武器效果系统，与蜂群系统深度集成，
 * 允许舰船从其蜂群中发射EMP电弧攻击敌人。
 */
public class VIII_VoltaicDischargeOnFireEffect implements OnFireEffectPlugin, EveryFrameWeaponEffectPlugin,
        VIII_FragmentWeapon {

	// ===================== 常量定义 =====================

	// 相位模式标签，用于标记蜂群处于相位模式
	public static String SWARM_TAG_PHASE_MODE = "VIII_swarm_tag_phase_mode";

	// 明亮的EMP边缘颜色（使用LWJGL颜色类，用于特定渲染）
	public static Color EMP_FRINGE_COLOR_BRIGHT = new Color(0, 6, 191,255);

	// EMP边缘颜色（RGB:130,155,145，淡绿灰色）
	public static Color EMP_FRINGE_COLOR = new Color(0, 6, 191,255);

	// 相位模式边缘颜色（RGB:120,110,185，淡紫色）
	public static Color PHASE_FRINGE_COLOR = new Color(120,110,185,255);

	// 相位模式核心颜色（白色，50%透明度）
	public static Color PHASE_CORE_COLOR = new Color(0, 6, 191,255);

	// 额外射击角度范围（360度，即全周）
	public static float EXTRA_ARC = 360f;

	// 需要发射的碎片数量（每次射击需要10个活跃蜂群成员）
	public static int FRAGMENTS_TO_FIRE = 10;

	// ===================== 蜂群相位模式相关方法 =====================

	/**
	 * 检查舰船的蜂群是否处于相位模式
	 * @param ship 要检查的舰船
	 * @return 如果蜂群存在且包含相位模式标签，返回true
	 */
	public static boolean isSwarmPhaseMode(ShipAPI ship) {
		VIII_RoilingSwarmEffect swarm = VIII_RoilingSwarmEffect.getSwarmFor(ship);
		return swarm != null && swarm.params.tags.contains(SWARM_TAG_PHASE_MODE);
	}

	/**
	 * 设置蜂群为相位模式（通过启动脚本）
	 * 注意：当前代码被注释，实际通过AttackSwarmPhaseModeScript处理
	 * @param ship 要设置的舰船
	 */
	public static void setSwarmPhaseMode(ShipAPI ship) {
		new VIII_AttackSwarmPhaseModeScript(ship);

		// 以下代码被注释，原本用于直接修改蜂群参数
		// RoilingSwarmEffect swarm = RoilingSwarmEffect.getSwarmFor(ship);
		// if (swarm != null) {
		// 	Color color = Misc.setAlpha(VoltaicDischargeOnFireEffect.PHASE_FRINGE_COLOR, 60);
		// 	swarm.params.flashFringeColor = color;
		// 	swarm.params.flashRadius = 180f;
		// 	swarm.params.tags.add(VoltaicDischargeOnFireEffect.SWARM_TAG_PHASE_MODE);
		//
		// 	// 修改舰船武器弹药
		// 	for (WeaponAPI w : ship.getAllWeapons()) {
		// 		if (w.usesAmmo() && w.getSpec().hasTag(Tags.FRAGMENT_GLOW)) {
		// 			w.setAmmo(Integer.MAX_VALUE);
		// 			w.setMaxAmmo(Integer.MAX_VALUE);
		// 		}
		// 		if (w.getSpec().hasTag(Tags.OVERSEER_CHARGE) ||
		// 				(ship.isFighter() && w.getSpec().hasTag(Tags.OVERSEER_CHARGE_FIGHTER))) {
		// 			w.setAmmo(w.getMaxAmmo());
		// 		}
		// 	}
		// }
	}

	// ===================== EveryFrameWeaponEffectPlugin 接口方法 =====================

	/**
	 * 每帧更新方法
	 * 检查蜂群活跃成员数量，如果不足则禁用武器
	 * @param amount 时间增量
	 * @param engine 战斗引擎
	 * @param weapon 武器实例
	 */
	@Override
	public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
		ShipAPI ship = weapon.getShip();
		if (ship == null) return;

		// 获取舰船的蜂群效果
		VIII_RoilingSwarmEffect swarm = VIII_RoilingSwarmEffect.getSwarmFor(ship);
		int active = swarm == null ? 0 : swarm.getNumActiveMembers();
		int required = getNumFragmentsToFire();
		boolean disable = active < required;

		// 根据蜂群活跃成员数量强制禁用/启用武器
		weapon.setForceDisabled(disable);

		// 显示无碎片蜂群警告（如果存在此方法）
		showNoFragmentSwarmWarning(weapon, ship);
	}

	// ===================== FragmentWeapon 接口方法 =====================

	/**
	 * 获取需要发射的碎片数量
	 * @return 需要发射的碎片数量（10）
	 */
	@Override
	public int getNumFragmentsToFire() {
		return FRAGMENTS_TO_FIRE;
	}

	// ===================== OnFireEffectPlugin 接口方法 =====================

	/**
	 * 武器发射时调用
	 * 从蜂群中选取碎片，发射EMP电弧攻击目标
	 * @param projectile 发射的抛射体（可能用于获取位置和伤害数据）
	 * @param weapon 发射的武器
	 * @param engine 战斗引擎
	 */
	public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
		// ARC = 30f; // 注释掉的代码

		// 获取抛射体的EMP伤害和实体伤害
		float emp = projectile.getEmpAmount();
		float dam = projectile.getDamageAmount();

		// 获取发射者舰船的蜂群效果
		VIII_RoilingSwarmEffect swarm = VIII_RoilingSwarmEffect.getSwarmFor(projectile.getSource());
		if (swarm == null || swarm.getAttachedTo() == null) return;

		// 寻找目标
		CombatEntityAPI target = findTarget(projectile, weapon, engine);

		// 如果没有找到目标，选择一个随机方向发射
		Vector2f to = null;
		if (target == null) to = pickNoTargetDest(projectile, weapon, engine);

		// 确定电弧方向（朝向目标或随机方向）
		Vector2f towards = to;
		if (target != null) towards = target.getLocation();

		// 从蜂群中选取一个碎片，该碎片朝向指定点且在150像素范围内
		VIII_SwarmMember pick = pickFragmentTowardsPointWithinRange(swarm, towards, 150f);
		if (pick == null) return;

		// 标记该碎片为最近被选中（冷却时间1秒）
		pick.setRecentlyPicked(1f);

		// ===================== 电弧参数配置 =====================
		float thickness = 30f; // 电弧厚度
		Color color = EMP_FRINGE_COLOR; // 默认EMP颜色
		Color coreColor = Color.white; // 默认核心颜色

		// 检查是否处于相位模式
		boolean phaseMode = isSwarmPhaseMode(projectile.getSource());
		if (phaseMode) {
			color = PHASE_FRINGE_COLOR; // 相位模式使用紫色
			// 如果目标是相位舰船，使用特殊的相位核心颜色
			if (target instanceof ShipAPI && ((ShipAPI)target).isPhased()) {
				coreColor = PHASE_CORE_COLOR;
			}
		}

		float coreWidthMult = 0.75f; // 核心宽度倍率

		// EMP电弧参数配置
		EmpArcParams params = new EmpArcParams();
		params.segmentLengthMult = 8f;       // 段长度倍率
		params.zigZagReductionFactor = 0.5f; // Z字形减少因子
		params.flickerRateMult = 1f;         // 闪烁速率倍率
		params.fadeOutDist = 1000f;          // 淡出距离
		params.minFadeOutMult = 1f;          // 最小淡出倍率
		params.glowSizeMult = 0.5f;          // 辉光大小倍率
		params.glowAlphaMult = 0.75f;        // 辉光透明度倍率

		// 注释掉的参数：是否点燃导弹（认为当前设置合适）
		// params.flamesOutMissiles = false; // 考虑到射速和普遍性，点燃导弹可能有点过分

		// ===================== 碎片闪光效果 =====================
		pick.flash(); // 触发碎片闪光
		pick.flash.forceIn(); // 强制淡入
		pick.flash.setDurationOut(0.25f); // 设置淡出持续时间

		// weapon.setAmmo(20); // 注释掉的弹药设置代码

		// ===================== 生成电弧 =====================
		if (target != null) {
			// 生成攻击性EMP电弧（对目标造成伤害）
			EmpArcEntityAPI arc = engine.spawnEmpArc(
					projectile.getSource(), // 伤害来源
					pick.loc,               // 电弧起点（碎片位置）
					weapon.getShip(),       // 电弧来源（舰船）
					target,                 // 电弧目标
					DamageType.ENERGY,      // 伤害类型
					dam,                    // 伤害值
					emp,                    // EMP伤害值
					100000f,                // 最大范围
					"voltaic_discharge_emp_impact", // 命中音效
					thickness,              // 电弧厚度
					color,                  // 边缘颜色
					coreColor,              // 核心颜色
					params);                // 电弧参数

			// 设置电弧属性
			arc.setCoreWidthOverride(thickness * coreWidthMult);
			arc.setSingleFlickerMode(); // 设置单次闪烁模式
			arc.setUpdateFromOffsetEveryFrame(true); // 每帧从偏移更新
			arc.setRenderGlowAtStart(false); // 起点不渲染辉光
			arc.setFadedOutAtStart(true); // 起点已淡出
		} else {
			// 没有目标时，生成视觉电弧（不造成伤害）

            // 随机目标点
            EmpArcEntityAPI arc = engine.spawnEmpArcVisual(
					pick.loc,               // 电弧起点
					weapon.getShip(),       // 来源
					to,                     // 目标点
					weapon.getShip(),       // 目标实体
					thickness,              // 厚度
					color,                  // 边缘颜色
					coreColor,              // 核心颜色
					params);                // 电弧参数

			// 设置电弧属性（与攻击性电弧相同）
			arc.setCoreWidthOverride(thickness * coreWidthMult);
			arc.setSingleFlickerMode();
			arc.setUpdateFromOffsetEveryFrame(true);
			arc.setRenderGlowAtStart(false);
			arc.setFadedOutAtStart(true);

			// 注释掉的音效播放代码
			// Global.getSoundPlayer().playSound("shock_repeater_emp_impact", 1f, 1f, to, new Vector2f());
		}
	}

	// ===================== 辅助方法 =====================

	/**
	 * 当没有有效目标时，随机选择一个发射方向
	 * @param projectile 抛射体
	 * @param weapon 武器
	 * @param engine 战斗引擎
	 * @return 随机目标点坐标
	 */
	public Vector2f pickNoTargetDest(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
		float spread = 50f; // 散布范围
		float range = Math.min(weapon.getRange() - spread, 150f); // 有效射程
		Vector2f from = projectile.getLocation(); // 发射位置

		// 计算随机方向（在武器当前角度±180度范围内）
		Vector2f dir = Misc.getUnitVectorAtDegreeAngle(
				weapon.getCurrAngle() + (EXTRA_ARC/2f - EXTRA_ARC * (float) Math.random()));
		dir.scale(range); // 扩展到射程

		// 计算目标点
		Vector2f.add(from, dir, dir);
		dir = Misc.getPointWithinRadius(dir, spread); // 添加随机散布
		return dir;
	}

	/**
	 * 寻找攻击目标
	 * @param projectile 抛射体
	 * @param weapon 武器
	 * @param engine 战斗引擎
	 * @return 最佳目标实体，如果没有则返回null
	 */
	public CombatEntityAPI findTarget(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
		float range = weapon.getRange() + 50f; // 搜索范围（武器射程+50）
		Vector2f from = projectile.getLocation(); // 搜索起点

		// 获取搜索区域内的所有战斗实体
		Iterator<Object> iter = Global.getCombatEngine().getAllObjectGrid().getCheckIterator(
				from, range * 2f, range * 2f);

		int owner = weapon.getShip().getOwner(); // 发射者阵营
		CombatEntityAPI best = null; // 最佳目标
		float minScore = Float.MAX_VALUE; // 最小分数（距离）

		ShipAPI ship = weapon.getShip();
		// 检查是否忽略诱饵弹
		boolean ignoreFlares = ship != null &&
				ship.getMutableStats().getDynamic().getValue(Stats.PD_IGNORES_FLARES, 0) >= 1;
		ignoreFlares |= weapon.hasAIHint(AIHints.IGNORES_FLARES);

		boolean phaseMode = isSwarmPhaseMode(ship); // 是否相位模式

		// 遍历所有实体
		while (iter.hasNext()) {
			Object o = iter.next();
			// 只考虑导弹和舰船（不包括小行星）
			if (!(o instanceof MissileAPI) && !(o instanceof ShipAPI)) continue;

			CombatEntityAPI other = (CombatEntityAPI) o;
			if (other.getOwner() == owner) continue; // 跳过友军

			boolean phaseHit = false; // 是否允许攻击相位目标

			// 如果是舰船
			if (other instanceof ShipAPI otherShip) {
                if (otherShip.isHulk()) continue; // 跳过残骸
				if (otherShip.isPhased()) { // 如果是相位状态
					if (phaseMode) {
						phaseHit = true; // 相位模式下可以攻击相位目标
					} else {
						continue; // 否则跳过
					}
				}
				if (!otherShip.isTargetable()) continue; // 跳过不可目标单位
			}

			// 如果不是相位命中且碰撞类为NONE，跳过
			if (!phaseHit && other.getCollisionClass() == CollisionClass.NONE) continue;

			// 如果忽略诱饵弹且是导弹诱饵，跳过
			if (ignoreFlares && other instanceof MissileAPI missile) {
                if (missile.isFlare()) continue;
			}

			// 计算实际距离（考虑目标半径）
			float radius = Misc.getTargetingRadius(from, other, false);
			float score = Misc.getDistance(from, other.getLocation()) - radius;
			if (score > range) continue; // 超出范围，跳过

			// 检查是否在射击角度内（360度全周或武器角度±180度）
			if (!Misc.isInArc(weapon.getCurrAngle(), EXTRA_ARC, from, other.getLocation())) continue;

			// 使用距离作为评分标准（距离越近分数越低）

            // 更新最佳目标
			if (score < minScore) {
				minScore = score;
				best = other;
			}
		}
		return best;
	}

	/**
	 * 从蜂群中选取一个碎片，该碎片朝向指定点且在指定范围内
	 * @param swarm 蜂群效果
	 * @param towards 朝向点
	 * @param maxRange 最大范围
	 * @return 选中的碎片，如果没有则返回null
	 */
	public static VIII_SwarmMember pickFragmentTowardsPointWithinRange(VIII_RoilingSwarmEffect swarm, Vector2f towards, float maxRange) {
		// 获取加权随机选择器（排除最近被选中的碎片）
		WeightedRandomPicker<VIII_SwarmMember> picker = swarm.getPicker(true, true, towards);

		// 遍历选择器，直到找到符合条件的碎片
		while (!picker.isEmpty()) {
			VIII_SwarmMember p = picker.pickAndRemove();
			// 检查碎片距离（从蜂群附着点）
			float dist = Misc.getDistance(p.loc, swarm.getAttachedTo().getLocation());
			if (dist > maxRange) continue; // 超出范围，跳过
			return p; // 返回符合条件的碎片
		}
		return null; // 没有符合条件的碎片
	}
}