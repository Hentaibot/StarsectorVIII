// 包路径：远行星号游戏API实现中的战斗威胁包
package data.scripts.VIII_threat;

// 导入全局游戏实例类
import com.fs.starfarer.api.Global;
// 导入战斗相关接口
import com.fs.starfarer.api.combat.*;
// 导入伤害结果监听器接口
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
// 导入伤害修改器接口
import com.fs.starfarer.api.combat.listeners.DamageDealtModifier;
// 导入翻涌群特效参数和成员类
import data.scripts.VIII_threat.VIII_RoilingSwarmEffect.*;
// 导入杂项工具类
import com.fs.starfarer.api.util.Misc;
// 导入LWJGL二维向量类
import org.lwjgl.util.vector.Vector2f;

// 导入AWT颜色类
import java.awt.*;

/**
 * 吞噬群导弹效果类
 * 继承自基础碎片导弹效果，并实现命中效果插件
 * 特性：导弹命中后附加解体者（Disintegrator）持续伤害效果，并根据伤害产生新的碎片
 */
public class VIII_DevouringSwarmMissileEffect extends VIII_BaseFragmentMissileEffect implements OnHitEffectPlugin {

	// 静态常量：拆卸群标签，用于标记翻涌群参数中的特殊行为
	public static String DISMANTLING_SWARM = "VIII_dismantling_swarm";

	// 静态常量：持续伤害的滴答次数（约10秒，每次滴答约1秒，共11次）
	public static int NUM_TICKS = 11; // about 10 seconds (duration of damage over time effect)

	/**
	 * 拆卸碎片基础伤害否定器类
	 * 实现DamageDealtModifier接口，用于将带有DISMANTLING_SWARM标签的碎片伤害归零
	 */
	public static class VIII_DismantlingFragmentBaseDamageNegator implements DamageDealtModifier {
		/**
		 * 修改造成的伤害
		 * @param param      参数（通常为造成伤害的实体，如导弹）
		 * @param target     受伤害目标
		 * @param damage     伤害API对象
		 * @param point      伤害点
		 * @param shieldHit  是否命中护盾
		 * @return 修改器标识（如果应用了修改则返回标识，否则返回null）
		 */
		@Override
		public String modifyDamageDealt(Object param, CombatEntityAPI target, DamageAPI damage, Vector2f point,
										boolean shieldHit) {
			// 如果命中护盾，不进行修改
			if (shieldHit) {
				return null;
			}
			// 如果参数是导弹
			if (param instanceof MissileAPI) {
				MissileAPI missile = (MissileAPI) param;
				// 获取导弹关联的翻涌群特效
				VIII_RoilingSwarmEffect swarm = VIII_RoilingSwarmEffect.getSwarmFor(missile);
				// 如果翻涌群存在且带有DISMANTLING_SWARM标签
				if (swarm != null && swarm.params.tags.contains(DISMANTLING_SWARM)) {
					// 将伤害倍率修改为0（即不造成伤害）
					damage.getModifier().modifyMult(DISMANTLING_SWARM, 0f);
					return DISMANTLING_SWARM; // 返回修改器标识
				}
			}
			return null;
		}
	}

	/**
	 * 开火时调用（重写父类方法）
	 * @param projectile 投射物
	 * @param weapon     武器
	 * @param engine     战斗引擎
	 */
	public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
		// 调用父类开火逻辑
		super.onFire(projectile, weapon, engine);
		// 如果武器所属舰船存在且尚未添加DismantlingFragmentBaseDamageNegator监听器
		if (weapon.getShip() != null &&
				!weapon.getShip().hasListenerOfClass(VIII_DismantlingFragmentBaseDamageNegator.class)) {
			// 为舰船添加伤害否定器监听器
			weapon.getShip().addListener(new VIII_DismantlingFragmentBaseDamageNegator());
		}
	}

	/**
	 * 配置导弹翻涌群参数（重写父类方法）
	 * @param params 翻涌群参数对象
	 */
	protected void configureMissileSwarmParams(VIII_RoilingSwarmParams params) {
		// 以下为注释掉的旧配置
//        params.flashFringeColor = new Color(183,65,13,255);
//        params.flashCoreColor = Color.white;
//        params.flashRadius = 40f;
//        params.flashCoreRadiusMult = 0.75f;

		// 为翻涌群参数添加拆卸群标签
		params.tags.add(DISMANTLING_SWARM);

//        params.flashFringeColor = new Color(183,65,13,80);
//        params.flashCoreColor = new Color(183,65,13,127);
		// 设置闪烁边缘颜色：浅绿色，透明度50
		params.flashFringeColor = new Color(130,165,50,50);
		// 设置闪烁核心颜色：浅绿色，透明度127
		params.flashCoreColor = new Color(130,165,50,127);
		//params.flashCoreColor = new Color(50,165,50,127);

//        params.flashFringeColor = new Color(100,165,100,127);
//        params.flashCoreColor = Color.white;

		// 设置核心半径倍率为0（不显示核心）
		params.flashCoreRadiusMult = 0f;
		// 在同一图层渲染闪烁效果
		params.renderFlashOnSameLayer = true;
		// 闪烁半径为40像素
		params.flashRadius = 40f;
		// 闪烁前延迟随机0~0.5秒
		params.preFlashDelay = 0.5f * (float) Math.random();

		// 闪烁频率为40（较高，产生快速闪烁）
		params.flashFrequency = 40f;
		// 闪烁概率为1（每次检查必然闪烁）
		params.flashProbability = 1f;
	}

	/**
	 * 获取要转移的其他碎片数量（重写父类方法）
	 * @return 转移碎片数量
	 */
	protected int getNumOtherMembersToTransfer() {
		return 5;   // 转移5个碎片
		//return 0;
	}

	/**
	 * 获取要额外添加的其他碎片数量（重写父类方法）
	 * @return 添加碎片数量
	 */
	@Override
	protected int getNumOtherMembersToAdd() {
		return 0;   // 不添加新碎片
		//return 5;
	}

	/**
	 * 获取导弹的EMP抗性（重写父类方法）
	 * @return EMP抗性值
	 */
	protected int getEMPResistance() {
		return 1;   // EMP抗性为1（很低）
	}

	/**
	 * 是否在导弹熄火时爆炸（重写父类方法）
	 * @return false表示不爆炸
	 */
	protected boolean explodeOnFizzling() {
		return false;
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
		CombatEngineAPI engine = Global.getCombatEngine();  // 获取战斗引擎
		Color color = swarm.params.flashFringeColor;        // 获取翻涌群闪烁边缘色
		color = Misc.setAlpha(color, 255);                  // 将透明度设置为255（完全不透明）
		float size = swarm.params.flashRadius * 2f;         // 粒子大小 = 闪烁半径的2倍
		// 添加命中粒子效果（边缘色，半透明）
		engine.addHitParticle(p.loc, new Vector2f(), size, 0.5f, color);
		// 添加核心粒子效果（白色，全亮）
		engine.addHitParticle(p.loc, new Vector2f(), size * 0.25f, 1f, Color.white);
	}

	/**
	 * 命中目标时的处理（实现OnHitEffectPlugin接口）
	 * @param projectile    命中目标的投射物
	 * @param target        被命中的实体
	 * @param point         命中点（世界坐标）
	 * @param shieldHit     是否命中护盾
	 * @param damageResult  伤害结果
	 * @param engine        战斗引擎
	 */
	@Override
	public void onHit(DamagingProjectileAPI projectile, CombatEntityAPI target, Vector2f point, boolean shieldHit,
					  ApplyDamageResultAPI damageResult, CombatEngineAPI engine) {
		// 如果命中护盾，则不处理后续效果
		if (shieldHit) return;
		// 如果投射物伤害值<=0，则返回
		if (projectile.getDamageAmount() <= 0f) return;
		// 如果目标不是舰船，则返回
		if (!(target instanceof ShipAPI)) return;

		// 获取发射源舰船
		ShipAPI source = projectile.getSource();
		// 获取发射源舰船的翻涌群特效
		VIII_RoilingSwarmEffect sourceSwarm = VIII_RoilingSwarmEffect.getSwarmFor(source);

		// 计算命中点相对于目标舰船中心的偏移向量（局部坐标）
		Vector2f offset = Vector2f.sub(point, target.getLocation(), new Vector2f());
		// 将偏移向量旋转到舰船局部坐标系（减去舰船朝向）
		offset = Misc.rotateAroundOrigin(offset, -target.getFacing());

		// 创建解体者效果（持续伤害效果），使用匿名内部类重写部分方法
		VIII_DisintegratorEffect effect = new VIII_DisintegratorEffect(projectile, (ShipAPI) target, offset) {
			/**
			 * 获取总伤害值（每次滴答伤害之和）
			 * @return 投射物伤害值
			 */
			protected float getTotalDamage() {
				return projectile.getDamageAmount();
			}
			/**
			 * 获取滴答次数（持续伤害的段数）
			 * @return 固定为NUM_TICKS（11次）
			 */
			protected int getNumTicks() {
				return NUM_TICKS;
			}
			/**
			 * 是否可以对船体造成伤害
			 * @return true表示可以
			 */
			protected boolean canDamageHull() {
				return true;
			}
			/**
			 * 每次滴答产生的粒子数量
			 * @return 5个粒子
			 */
			protected int getNumParticlesPerTick() {
				return 5;
			}
			/**
			 * 获取循环音效ID
			 * @return 吞噬群循环音效
			 */
			protected String getSoundLoopId() {
				return "devouring_swarm_loop";
			}
			/**
			 * 添加粒子效果（每次滴答时调用）
			 */
			protected void addParticle() {
				// 创建粒子数据：大小25f，半径3~5f，亮度1f
				VIII_ParticleData p = new VIII_ParticleData(25f, 3f + (float) Math.random() * 2f, 1f);
				// 设置粒子颜色：淡紫色，透明度25
				p.color = new Color(125,100,200,25);
//                p.color = RiftLanceEffect.getColorForDarkening(VoltaicDischargeOnFireEffect.EMP_FRINGE_COLOR);
//                p.color = Misc.setAlpha(p.color, 25);
				particles.add(p);                // 添加到粒子列表
				p.offset = Misc.getPointWithinRadius(p.offset, 10f); // 随机偏移
			}
			/**
			 * 造成伤害后的回调（每次滴答造成伤害时调用）
			 * @param loc         伤害位置（世界坐标）
			 * @param hullDamage  造成的船体伤害值
			 * @param armorDamage 造成的装甲伤害值
			 */
			protected void damageDealt(Vector2f loc, float hullDamage, float armorDamage) {
				// 如果源舰船翻涌群为空、源舰船为空或已死亡，则返回
				if (sourceSwarm == null || !source.isAlive()) return;
				// 如果源舰船是战机，则返回（战机不产生碎片）
				if (source.isFighter()) return;

				// 25%概率不产生碎片
				if ((float) Math.random() < 0.25f) return;
				// 只有当造成了船体伤害或装甲伤害时，才产生新碎片
				if (hullDamage > 0 || armorDamage > 0) {
					// 向源翻涌群添加一个新碎片成员
					VIII_SwarmMember p = sourceSwarm.addMember();
					p.loc.set(loc);                      // 设置碎片位置为伤害点
					p.fader.setDurationIn(0.3f);         // 碎片淡入时间0.3秒
				}
			}
		};
		// 将解体者效果添加到战斗引擎的渲染层
		CombatEntityAPI e = engine.addLayeredRenderingPlugin(effect);
		// 设置效果实体的位置为投射物的当前位置（确保跟随导弹）
		e.getLocation().set(projectile.getLocation());

		// 如果投射物是导弹
		if (projectile instanceof MissileAPI missile) {
            // 标记导弹已经造成伤害（防止重复触发）
			missile.setDidDamage(true);
			// 播放命中舰船的音效
			Global.getSoundPlayer().playSound("devouring_swarm_hit_ship", 1f, 1f, point,
					missile.getVelocity());
		}
	}

	/**
	 * 是否生成EMP电弧（重写父类方法）
	 * @return 调用父类方法的结果
	 */
	protected boolean withEMPArc() {
		return super.withEMPArc();
	}

	/**
	 * 获取EMP电弧边缘颜色（重写父类方法）
	 * @return 缩放亮度后的武器发光色
	 */
	protected Color getEMPFringeColor() {
		Color c = weapon.getSpec().getGlowColor();  // 获取武器规格的发光色
		c = Misc.scaleColorOnly(c, 0.5f);           // 颜色亮度减半
		return c;
		//return super.getEMPFringeColor();
	}

	/**
	 * 获取EMP电弧核心颜色（重写父类方法）
	 * @return 缩放亮度后的白色
	 */
	protected Color getEMPCoreColor() {
		Color c = Color.white;
		c = Misc.scaleColorOnly(c, 0.5f);           // 亮度减半
		return c;
		//return super.getEMPCoreColor();
	}

// 注释掉的爆炸音效ID
//    protected String getExplosionSoundId() {
//        return "devastator_explosion";
//    }
}