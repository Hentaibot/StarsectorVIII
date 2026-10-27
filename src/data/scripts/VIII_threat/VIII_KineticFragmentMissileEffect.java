// 包路径：远行星号游戏API实现中的战斗威胁包
package data.scripts.VIII_threat;

// 导入全局游戏实例类
import com.fs.starfarer.api.Global;
// 导入战斗引擎接口
import com.fs.starfarer.api.combat.CombatEngineAPI;
// 导入战斗实体接口
import com.fs.starfarer.api.combat.CombatEntityAPI;
// 导入伤害类型枚举
import com.fs.starfarer.api.combat.DamageType;
// 导入导弹接口
import com.fs.starfarer.api.combat.MissileAPI;
// 导入翻涌群特效参数和成员类
import data.scripts.VIII_threat.VIII_RoilingSwarmEffect.*;
// 导入LWJGL二维向量类
import org.lwjgl.util.vector.Vector2f;

// 导入AWT颜色类
import java.awt.*;

/**
 * 动能碎片导弹效果类
 * 继承自基础碎片导弹效果，实现动能类型的碎片导弹：碎片为绿色色调，造成动能伤害，命中时生成绿色粒子效果。
 */
public class VIII_KineticFragmentMissileEffect extends VIII_BaseFragmentMissileEffect {

	// 静态常量：碎片边缘颜色（淡黄绿色，不透明）
	public static Color FRINGE_COLOR = new Color(235,255,215,235);
	// 静态常量：碎片核心颜色（更淡的黄绿色，略透明）
	public static Color CORE_COLOR = new Color(225,255,205,200);

	// 静态常量：其他碎片造成的伤害值（100点动能伤害）
	public static float OTHER_FRAGMENT_DAMAGE = 100;

	/**
	 * 配置导弹翻涌群参数（重写父类方法）
	 * @param params 翻涌群参数对象
	 */
	protected void configureMissileSwarmParams(VIII_RoilingSwarmParams params) {
		// 被注释的旧代码：设置闪烁边缘色和核心色（已被下面的赋值替代）
//        params.flashFringeColor = new Color(235,255,215,235);
//        params.flashCoreColor = new Color(225,255,205,200);

		// 被注释：增加基础弹簧常数和弹簧常数负范围（未启用）
//        params.baseSpringConstant *= 2f;
//        params.springConstantNegativeRange *= 2f;
		// 设置碎片最大偏移半径（40像素）
		params.maxOffset = 40f;

		// 设置碎片最大速度：导弹最大速度 + 10
		params.maxSpeed = missile.getMaxSpeed() + 10f;
		// 不强制超出附着实体速度（设为0表示不超出）
		params.outspeedAttachedEntityBy = 0f;

		// 设置闪烁边缘色和核心色（使用静态常量）
		params.flashFringeColor = FRINGE_COLOR;
		params.flashCoreColor = CORE_COLOR;

		// 核心半径倍率为0（不显示核心）
		params.flashCoreRadiusMult = 0f;
		// 被注释：在同一图层渲染闪烁（未启用）
		//params.renderFlashOnSameLayer = true;
		// 闪烁半径30像素
		params.flashRadius = 30f;
		// 启用自动缩放（根据距离调整闪烁大小）
		params.autoscale = true;
		// 被注释：闪烁前随机延迟（未启用）
		//params.preFlashDelay = 0.5f * (float) Math.random();

		// 被注释：闪烁频率和概率（未启用）
//        params.flashFrequency = 40f;
//        params.flashProbability = 1f;
	}

	/**
	 * 碎片群创建后的钩子（重写父类方法）
	 * 用于在导弹生成后立即对所有碎片成员进行初始化：设置亮度、触发闪烁等
	 * @param missile      导弹对象
	 * @param missileSwarm 导弹携带的碎片群
	 * @param sourceSwarm  源碎片群（发射舰船周围的碎片群）
	 */
	protected void swarmCreated(MissileAPI missile, VIII_RoilingSwarmEffect missileSwarm, VIII_RoilingSwarmEffect sourceSwarm) {
		boolean first = true;   // 标记是否为第一个碎片（主要碎片）
		// 遍历导弹碎片群的所有成员
		for (VIII_SwarmMember p : missileSwarm.members) {
			// 将碎片缩放器的亮度设置为碎片的当前缩放值（保持初始大小）
			p.scaler.setBrightness(p.scale);
			if (first) {
				// 主要碎片：禁止向下弹跳，并淡入（逐渐显现）
				p.scaler.setBounceDown(false);
				p.scaler.fadeIn();
			} else {
				// 其他碎片：保持当前缩放值不变（不淡入淡出）
//                p.scaler.setBounceUp(false);
//                p.scaler.fadeOut();
				p.keepScale = true;
			}
			// 清空已有的闪烁效果，然后重新触发闪烁（使所有碎片都闪烁一次）
			p.flash = null;
			p.flash();
			// 禁止闪烁向下弹跳（保持简单闪烁）
			p.flash.setBounceDown(false);
			first = false;   // 标记后续不再是第一个
		}
	}

	/**
	 * 获取要转移的其他碎片数量（重写父类方法）
	 * @return 转移碎片数量（4个）
	 */
	protected int getNumOtherMembersToTransfer() {
		return 4;
	}

	/**
	 * 获取导弹的EMP抗性（重写父类方法）
	 * @return EMP抗性值（3，较高抗性）
	 */
	protected int getEMPResistance() {
		return 3;
	}

	/**
	 * 是否在导弹熄火时爆炸（重写父类方法）
	 * @return false表示不爆炸
	 */
	protected boolean explodeOnFizzling() {
		return false;
	}

	/**
	 * 是否让导弹在生成时面朝目标（重写父类方法）
	 * @return true表示朝向目标
	 */
	protected boolean shouldMakeMissileFaceTargetOnSpawnIfAny() {
		return true;
	}

	/**
	 * 获取其他碎片命中后的行为（重写父类方法）
	 * @return 停止并淡出
	 */
	@Override
	protected VIII_FragmentBehaviorOnImpact getOtherFragmentBehaviorOnImpact() {
		return VIII_FragmentBehaviorOnImpact.STOP_AND_FADE;
	}

	/**
	 * 报告碎片命中时的处理（重写父类方法）
	 * @param missile 导弹对象
	 * @param p       命中的碎片成员
	 * @param swarm   碎片群对象
	 * @param target  被命中的目标
	 */
	protected void reportFragmentHit(MissileAPI missile, VIII_SwarmMember p, VIII_RoilingSwarmEffect swarm, CombatEntityAPI target) {
		CombatEngineAPI engine = Global.getCombatEngine();
		Color color = FRINGE_COLOR;   // 使用边缘色作为粒子颜色
		// 被注释：将颜色透明度设为255（不透明），但未启用
		//color = Misc.setAlpha(color, 255);
		float size = 80f;  // 粒子大小（不是半径）
		// 添加命中粒子效果（边缘色，半透明）
		engine.addHitParticle(p.loc, new Vector2f(), size, 0.5f, color);
		// 添加核心粒子效果（核心色，全亮）
		engine.addHitParticle(p.loc, new Vector2f(), size * 0.25f, 1f, CORE_COLOR);

		// 计算伤害倍率：基于碎片的淡入淡出亮度（亮度越高伤害越大）
		float mult = p.fader.getBrightness();
		if (mult > 0.8f) mult = 1f;   // 如果亮度超过0.8，则视为满伤害（1倍）
		// 对目标造成动能伤害（伤害值 = 基础伤害 * 倍率），无EMP伤害
		engine.applyDamage(target, p.loc, OTHER_FRAGMENT_DAMAGE * mult, DamageType.KINETIC, 0f,
				false, false, missile.getSource(), true);

		// 被注释：添加负向粒子效果（未启用）
//        engine.addNegativeParticle(p.loc, new Vector2f(), 300f, 0f, 0.5f, Color.white);
	}

	// 被注释的爆炸音效ID（未使用）
//    protected String getExplosionSoundId() {
//        return "devastator_explosion";
//    }
}