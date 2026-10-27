// 包路径：远行星号游戏API实现中的战斗威胁包
package data.scripts.VIII_threat;

// 导入战斗引擎接口
import com.fs.starfarer.api.combat.CombatEngineAPI;
// 导入伤害性投射物接口
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
// 导入导弹接口
import com.fs.starfarer.api.combat.MissileAPI;
// 导入武器接口
import com.fs.starfarer.api.combat.WeaponAPI;
// 导入翻涌群特效参数和成员类
import data.scripts.VIII_threat.VIII_RoilingSwarmEffect.*;

// 导入AWT颜色类
import java.awt.*;

/**
 * 追寻者碎片导弹效果类
 * 继承自基础碎片导弹效果，实现“追寻者”类型导弹的特殊配置：碎片群为黄色闪烁，转移更多碎片等。
 */
public class VIII_SeekerFragmentMissileEffect extends VIII_BaseFragmentMissileEffect {

	/**
	 * 开火时调用（重写父类方法）
	 * @param projectile 投射物（导弹）
	 * @param weapon     发射武器
	 * @param engine     战斗引擎
	 */
	public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
		// 调用父类的开火逻辑，完成基础初始化（设置碎片群、转移碎片等）
		super.onFire(projectile, weapon, engine);
	}

	/**
	 * 配置导弹翻涌群参数（重写父类方法）
	 * @param params 翻涌群参数对象
	 */
	protected void configureMissileSwarmParams(VIII_RoilingSwarmParams params) {
		// 被注释的旧配置：红色或橙色的边缘色
//        params.flashFringeColor = new Color(255,50,50,255);
//        params.flashFringeColor = new Color(255,165,30,255);
		// 设置闪烁边缘色为亮黄色（RGB 255,255,50，完全不透明）
		params.flashFringeColor = new Color(255,255,50,255);
		// 设置闪烁核心色为白色
		params.flashCoreColor = Color.white;
		// 设置闪烁半径为70像素
		params.flashRadius = 70f;
		// 设置核心半径倍率为0.75（核心大小为闪烁半径的0.75倍）
		params.flashCoreRadiusMult = 0.75f;
	}

	/**
	 * 碎片群创建后的钩子（重写父类方法）
	 * @param missile      导弹对象
	 * @param missileSwarm 导弹携带的碎片群
	 * @param sourceSwarm  源碎片群（发射舰船周围的碎片群）
	 */
	protected void swarmCreated(MissileAPI missile, VIII_RoilingSwarmEffect missileSwarm, VIII_RoilingSwarmEffect sourceSwarm) {
		// 如果导弹碎片群非空
		if (!missileSwarm.members.isEmpty()) {
			// 获取第一个碎片（主要碎片）
			VIII_SwarmMember p = missileSwarm.members.get(0);
			// 将缩放器的亮度设置为碎片的当前缩放值（保持初始大小）
			p.scaler.setBrightness(p.scale);
			// 禁止向下弹跳（只做淡入动画，不反弹）
			p.scaler.setBounceDown(false);
			// 开始淡入（逐渐显现）
			p.scaler.fadeIn();
		}
	}

	/**
	 * 获取要转移的其他碎片数量（重写父类方法）
	 * @return 转移碎片数量（9个）
	 */
	protected int getNumOtherMembersToTransfer() {
		return 9;          // 转移9个额外碎片，加上主要碎片共10个
		//return 0;        // 被注释的旧返回值
		//return 12;       // 被注释的旧返回值
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

	// 被注释的爆炸音效ID（未使用）
//    protected String getExplosionSoundId() {
//        return "devastator_explosion";
//    }
}