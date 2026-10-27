package data.scripts.VIII_threat;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import data.scripts.VIII_threat.VIII_RoilingSwarmEffect.*;

import java.awt.*;

/**
 * UnstableFragmentMissileEffect - 不稳定碎片导弹效果
 *
 * 这个类实现了不稳定碎片导弹的特殊效果，继承自BaseFragmentMissileEffect
 * 主要特性：
 * 1. 导弹爆炸后产生特殊的蜂群效果
 * 2. 配置红色闪光效果
 * 3. 使用近炸引信AI控制导弹行为
 * 4. 主碎片闪烁效果
 *
 * 这种导弹通常用于产生不稳定的能量碎片，造成范围伤害
 */
public class VIII_UnstableFragmentMissileEffect extends VIII_BaseFragmentMissileEffect {

	/**
	 * 导弹发射时的处理
	 * @param projectile 导弹实体
	 * @param weapon 发射的武器
	 * @param engine 战斗引擎
	 */
	public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
		// 调用父类的onFire方法执行基础初始化
		super.onFire(projectile, weapon, engine);
	}

	/**
	 * 配置导弹产生的蜂群效果参数
	 * 重写父类方法，设置特定的闪光颜色
	 * @param params 蜂群效果参数
	 */
	protected void configureMissileSwarmParams(VIII_RoilingSwarmParams params) {
		// 设置闪光边缘颜色为红色（255,50,50,255）
		params.flashFringeColor = new Color(255, 50, 50, 255);
		// 设置闪光核心颜色为白色
		params.flashCoreColor = Color.white;
		// 设置闪光半径为100像素
		params.flashRadius = 100f;
		// 注释掉的代码：设置闪光核心半径倍率
		// params.flashCoreRadiusMult = 0.75f;
	}

	/**
	 * 获取要转移的其他成员数量
	 * 对于不稳定碎片导弹，不转移其他成员
	 * @return 要转移的成员数量（0）
	 */
	protected int getNumOtherMembersToTransfer() {
		return 0;
	}

	/**
	 * 获取EMP（电磁脉冲）抗性
	 * 对于不稳定碎片导弹，没有EMP抗性
	 * @return EMP抗性值（0）
	 */
	protected int getEMPResistance() {
		return 0;
	}

	/**
	 * 是否在导弹失效时爆炸
	 * 对于不稳定碎片导弹，由近炸引信AI处理爆炸
	 * @return 是否在失效时爆炸（false）
	 */
	protected boolean explodeOnFizzling() {
		return false; // 由近炸引信AI处理
	}

	/**
	 * 是否使主碎片发光
	 * 对于不稳定碎片导弹，不使主碎片发光
	 * @return 是否使主碎片发光（false）
	 */
	@Override
	protected boolean makePrimaryFragmentGlow() {
		return false;
	}

	/**
	 * 选择主碎片（攻击目标）
	 * 如果有制导目标，选择最靠近目标的碎片作为主碎片
	 * @return 选中的主碎片
	 */
	@Override
	protected VIII_SwarmMember pickPrimaryFragment() {
		// 如果导弹使用制导AI且有目标，选择最靠近目标的碎片
		if (missile.getAI() instanceof GuidedMissileAI) {
			GuidedMissileAI ai = (GuidedMissileAI) missile.getAI();
			if (ai.getTarget() != null) {
				// 在150像素范围内选择最靠近目标的碎片
				return pickOuterFragmentWithinRangeClosestTo(150f, ai.getTarget().getLocation());
			}
		}
		// 否则使用父类的默认选择方法
		return super.pickPrimaryFragment();
	}

	/**
	 * 蜂群效果更新方法
	 * 重写父类方法，添加近炸引信AI控制和主碎片闪烁效果
	 * @param amount 时间增量
	 * @param missile 导弹实体
	 * @param swarm 蜂群效果
	 */
	@Override
	protected void swarmAdvance(float amount, MissileAPI missile, VIII_RoilingSwarmEffect swarm) {
		// 如果还没有近炸引信AI，创建一个
		if (swarm.custom1 == null) {
			swarm.custom1 = Global.getCombatEngine().createProximityFuseAI(missile);
		}
		// 更新近炸引信AI
		((MissileAIPlugin)swarm.custom1).advance(amount);

		// 如果蜂群有成员，为主碎片添加闪烁效果
		if (!swarm.members.isEmpty()) {
			VIII_SwarmMember primary = swarm.members.get(0); // 获取第一个成员作为主碎片
			if (primary.flash == null) { // 如果还没有闪光效果
				primary.flash(); // 触发闪光
				primary.flash.setDuration(0.25f, 0.75f); // 设置闪光持续时间
				primary.flash.setBounce(true, true); // 设置反弹效果
			}
		}
	}

	/**
	 * 注释掉的代码：获取爆炸音效ID
	 * 原代码可能用于指定特定的爆炸音效
	 */
	// protected String getExplosionSoundId() {
	//     return "devastator_explosion";
	// }
}