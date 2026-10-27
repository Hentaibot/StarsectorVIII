// 包路径：远行星号游戏API实现中的战斗威胁包
package data.hullmods;

// 导入游戏状态枚举

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.util.ColorShifterUtil;
import com.fs.starfarer.api.util.Misc;
import data.scripts.VIII_threat.VIII_FragmentWeapon;
import data.scripts.VIII_threat.VIII_RoilingSwarmEffect;
import data.scripts.VIII_threat.VIII_RoilingSwarmEffect.VIII_RoilingSwarmParams;
import data.scripts.VIII_threat.VIII_SwarmLauncherEffect;
import data.scripts.VIII_threat.VIII_VoltaicDischargeOnFireEffect;
import data.scripts.shipsystems.subsystems.VIII_RoundShieldDroneSubsystem;
import org.magiclib.subsystems.MagicSubsystemsManager;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 创建舰船周围碎片群的船体插件。该碎片群是为“碎片”武器提供能量所必需的。
 *
 * @author Alex
 */
public class VIII_FragmentSwarmHullmod extends BaseHullMod {

	// 标准碎片群交换类（用于碎片群的“交换”行为，影响碎片成员间的相互作用）
	public static String STANDARD_SWARM_EXCHANGE_CLASS = "VIII_standard_swarm_exchange_class";
	// 标准碎片集群类（影响碎片群的群体行为）
	public static String STANDARD_SWARM_FLOCKING_CLASS = "VIII_standard_swarm_flocking_class";
	// 建造碎片集群类（用于建造群）
	public static String CONSTRUCTION_SWARM_FLOCKING_CLASS = "VIII_construction_swarm_flocking_class";
	// 回收碎片集群类（用于回收群）
	public static String RECLAMATION_SWARM_FLOCKING_CLASS = "VIII_reclamation_swarm_flocking_class";
	// 回收碎片交换类
	public static String RECLAMATION_SWARM_EXCHANGE_CLASS = "VIII_reclamation_swarm_exchange_class";

	// 是否在威胁舰船上显示覆盖层（默认为false，不显示）
	public static boolean SHOW_OVERLAY_ON_THREAT_SHIPS = false;

	// 内置插件（S-mod）的CR惩罚：-20%（0.2）
	public static float SMOD_CR_PENALTY = 0.2f;
	// 内置插件的维护补给惩罚：+50%
	public static float SMOD_MAINTENANCE_PENALTY = 50f;

	// UI状态键，用于在战斗中显示碎片数量
	public static Object STATUS_KEY1 = new Object();

	/**
	 * 在舰船创建之前应用效果（修改舰船基础属性）
	 * @param hullSize 舰船尺寸
	 * @param stats    可变舰船属性统计
	 * @param id       效果ID（用于标识此插件）
	 */
	@Override
	public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
		// 判断是否为内置插件（S-mod）
		boolean sMod = isSMod(stats);
		if (sMod) {
			// 如果是内置插件，减少最大战备值（CR）SMOD_CR_PENALTY
			stats.getMaxCombatReadiness().modifyFlat(id, -Math.round(SMOD_CR_PENALTY * 100f) * 0.01f, "纳米碎片集群");
			// 增加每月补给消耗 SMOD_MAINTENANCE_PENALTY%
			stats.getSuppliesPerMonth().modifyPercent(id, SMOD_MAINTENANCE_PENALTY);
		}
	}

//	/**
//	 * 在舰船创建之后应用效果（添加视觉覆盖层）
//	 * @param ship 舰船对象
//	 * @param id   效果ID
//	 */
//	@Override
//	public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
//		// 如果全局显示覆盖层开启，或者舰船没有威胁标签（非威胁舰船），则添加碎片群覆盖层
//		if (SHOW_OVERLAY_ON_THREAT_SHIPS || !ship.getHullSpec().hasTag(Tags.THREAT)) {
//			ship.setExtraOverlay(Global.getSettings().getSpriteName("misc", "fragment_swarm"));
//			ship.setExtraOverlayMatchHullColor(false);   // 不匹配船体颜色
//			ship.setExtraOverlayShadowOpacity(1f);       // 阴影不透明度为1（完全不透明）
//		}
//	}

	/**
	 * 战斗中每帧更新（核心逻辑：创建/更新碎片群，调整参数，显示UI状态）
	 * @param ship   舰船
	 * @param amount 时间增量（秒）
	 */
	@Override
	public void advanceInCombat(ShipAPI ship, float amount) {
		if (amount <= 0f || ship == null) return;

		// 获取舰船关联的翻涌群特效（碎片群）
		VIII_RoilingSwarmEffect swarm = VIII_RoilingSwarmEffect.getSwarmFor(ship);
		if (swarm == null) {
			// 如果不存在，则创建新的碎片群
			swarm = createSwarmFor(ship);
		}

		// 如果是战机，则不进行后续的尺寸/再生率调整（战机有单独逻辑）
		if (ship.isFighter()) return;

		// 判断是否为玩家舰船（用于显示UI状态）
		boolean playerShip = Global.getCurrentState() == GameState.COMBAT &&
				Global.getCombatEngine() != null && Global.getCombatEngine().getPlayerShip() == ship;

		// 获取碎片群的参数对象
		VIII_RoilingSwarmParams params = swarm.params;
		// 计算基础维持成员数：根据舰船尺寸的基础值，乘以碎片群规模修正器（来自其他船体插件等）
		params.baseMembersToMaintain = (int) ship.getMutableStats().getDynamic().getValue(
				Stats.FRAGMENT_SWARM_SIZE_MOD, getBaseSwarmSize(ship.getHullSize()));
		// 计算成员重生率：基础重生率（取决于尺寸）乘以碎片群重生率修正器
		params.memberRespawnRate = getBaseSwarmRespawnRateMult(ship.getHullSize()) *
				ship.getMutableStats().getDynamic().getValue(Stats.FRAGMENT_SWARM_RESPAWN_RATE_MULT);

		// 以下是被注释的特定舰船（HIVE_UNIT）的特殊逻辑，未启用
//        if (ship.getHullSpec().getHullId().equals(ThreatHullmod.HIVE_UNIT)) {
//            params.baseMembersToMaintain = SwarmLauncherEffect.FRAGMENT_NUM.get(SwarmLauncherEffect.ATTACK_SWARM_WING);
//            params.baseMembersToMaintain *= 8;
//            params.memberRespawnRate = 15 * ship.getMutableStats().getDynamic().getValue(Stats.FRAGMENT_SWARM_RESPAWN_RATE_MULT);
//        }

		// 设置最大成员数阈值：超过此数量会强制移除，为基础维持数的1.5倍
		params.maxNumMembersToAlwaysRemoveAbove = (int) (params.baseMembersToMaintain * 1.5f);
		// 初始成员数设为基础维持数
		params.initialMembers = params.baseMembersToMaintain;

		// 如果是玩家舰船，在UI中显示当前碎片数量
		if (playerShip) {
			int active = swarm.getNumActiveMembers();      // 当前活跃碎片数量

			int maxRequired = 0;
			// 遍历所有武器，找出所有碎片武器所需的碎片数量最大值
			for (WeaponAPI w : ship.getAllWeapons()) {
				if (w.getEffectPlugin() instanceof VIII_FragmentWeapon) {
					VIII_FragmentWeapon fw = (VIII_FragmentWeapon) w.getEffectPlugin();
					maxRequired = Math.max(maxRequired, fw.getNumFragmentsToFire());
				}
			}

			// 如果活跃碎片数小于最大需求，则显示为负面状态（debuff）
			boolean debuff = active < maxRequired;
			// 在玩家舰船的状态栏中维护一条状态信息：显示当前碎片数量
			Global.getCombatEngine().maintainStatusForPlayerShip(STATUS_KEY1,
					Global.getSettings().getSpriteName("ui", "icon_tactical_fragment_swarm"),
					spec.getDisplayName(),
					"纳米碎片: " + active,
					debuff);
		}
	}

	/**
	 * 为指定舰船创建翻涌群特效（碎片群）
	 * @param ship 舰船
	 * @return 创建的RoilingSwarmEffect实例
	 */
	public static VIII_RoilingSwarmEffect createSwarmFor(ShipAPI ship) {
		// 如果已经存在，则直接返回（避免重复创建）
		VIII_RoilingSwarmEffect existing = VIII_RoilingSwarmEffect.getSwarmFor(ship);
		if (existing != null) return existing;

		// 被注释的调试代码：创建测试用的Dweller群（未启用）
//        if (true) {
//            return SwarmLauncherEffect.createTestDwellerSwarmFor(ship);
//        }

		// 创建新的翻涌群参数对象
		VIII_RoilingSwarmParams params = new VIII_RoilingSwarmParams();

		// ==================== 战机专用配置 ====================
		if (ship.isFighter()) {
			float radius = 20f;                         // 默认半径20像素
			int numMembers = 50;                        // 默认成员数量50

			// 如果战机有联队ID，且SwarmLauncherEffect中有预定义半径和碎片数，则覆盖
			String wingId = ship.getWing() == null ? null : ship.getWing().getWingId();
			if (VIII_SwarmLauncherEffect.SWARM_RADIUS.containsKey(wingId)) {
				radius = VIII_SwarmLauncherEffect.SWARM_RADIUS.get(wingId);
			}
			if (VIII_SwarmLauncherEffect.FRAGMENT_NUM.containsKey(wingId)) {
				numMembers = VIII_SwarmLauncherEffect.FRAGMENT_NUM.get(wingId);
			}

			// 战机群的交换类、集群类
			params.memberExchangeClass = STANDARD_SWARM_EXCHANGE_CLASS;
			params.flockingClass = VIII_FragmentSwarmHullmod.STANDARD_SWARM_FLOCKING_CLASS;
			// 最大速度 = 舰船无加成最大速度 + max(无加成最大速度*0.25+50, 100)
			params.maxSpeed = ship.getMaxSpeedWithoutBoost() +
					Math.max(ship.getMaxSpeedWithoutBoost() * 0.25f + 50f, 100f);

			// 闪烁参数
			params.flashRateMult = 0.25f;               // 闪烁率倍率
			params.flashCoreRadiusMult = 0f;            // 核心半径倍率（0表示无核心）
			params.flashRadius = 120f;                  // 闪烁半径120像素
			params.flashFringeColor = new Color(0, 42, 255,40); // 边缘色：红色，透明度40
			params.flashCoreColor = new Color(0, 42, 255, 255); // 核心色：白色，透明度127

			// 注释：如果设为true且群正在发光，导弹碎片会显示在发光之上，看起来不好
			// params.renderFlashOnSameLayer = true;

			params.maxOffset = radius;                  // 最大偏移半径
			params.initialMembers = numMembers;         // 初始成员数
			params.baseMembersToMaintain = params.initialMembers; // 基础维持数 = 初始成员数
		}
		// ==================== 非战机（主力舰、巡洋舰等）配置 ====================
		else {
			MagicSubsystemsManager.addSubsystemToShip(ship, new VIII_RoundShieldDroneSubsystem(ship));
			params.memberExchangeClass = STANDARD_SWARM_EXCHANGE_CLASS;
			// 最大速度 = 舰船无加成最大速度 + max(无加成最大速度*0.25+50, 100) + 零幅能加速加成
			params.maxSpeed = ship.getMaxSpeedWithoutBoost() +
					Math.max(ship.getMaxSpeedWithoutBoost() * 0.25f + 50f, 100f) +
					ship.getMutableStats().getZeroFluxSpeedBoost().getModifiedValue();

			params.flashRateMult = 0.25f;
			params.flashCoreRadiusMult = 0f;
			params.flashRadius = 120f;
			params.flashFringeColor = new Color(0, 42, 255,40);
			params.flashCoreColor = new Color(0, 42, 255, 255);

			// 注释：如果设为true且群正在发光，导弹碎片会显示在发光之上，看起来不好
			// params.renderFlashOnSameLayer = true;

			params.minOffset = 200f;                      // 最小偏移半径
			// 最大偏移半径：取100像素和碰撞半径一半的最小值
			params.maxOffset = Math.min(200f, 240f/*ship.getCollisionRadius() * 0.5f*/);
			params.generateOffsetAroundAttachedEntityOval = true; // 围绕附着实体生成椭圆形偏移
			params.despawnSound = null;                 // 消失音效为空（由舰船爆炸代替）
			params.spawnOffsetMult = 0.33f;             // 生成偏移倍率
			params.spawnOffsetMultForInitialSpawn = 1f; // 初始生成的偏移倍率

			// 基础维持成员数、重生率等根据舰船尺寸获取
			params.baseMembersToMaintain = getBaseSwarmSize(ship.getHullSize());
			params.memberRespawnRate = getBaseSwarmRespawnRateMult(ship.getHullSize());
			params.maxNumMembersToAlwaysRemoveAbove = params.baseMembersToMaintain * 2;

			// params.offsetRerollFractionOnMemberRespawn = 0.05f; // 注释：成员重生时偏移重滚比例

            params.initialMembers = params.baseMembersToMaintain; // 初始成员设为基础维持数
			params.removeMembersAboveMaintainLevel = false;       // 不自动移除超出维持数的成员
		}

		// 收集所有带有 FRAGMENT_GLOW 标签且使用弹药的武器（即“碎片发光”武器）
		List<WeaponAPI> glowWeapons = new ArrayList<>();
		for (WeaponAPI w : ship.getAllWeapons()) {
			if (w.usesAmmo() && w.getSpec().hasTag(Tags.FRAGMENT_GLOW)) {
				glowWeapons.add(w);
			}
			// 对于带有 OVERSEER_CHARGE 标签的武器，或者战机且带有 OVERSEER_CHARGE_FIGHTER 标签的武器，将弹药设为0
			if (w.getSpec().hasTag(Tags.OVERSEER_CHARGE) ||
					(ship.isFighter() && w.getSpec().hasTag(Tags.OVERSEER_CHARGE_FIGHTER))) {
				w.setAmmo(0);
			}
		}

		// 注释：如果舰船有标签 FRAGMENT_SWARM_START_WITH_ZERO_FRAGMENTS，则初始成员为0（未启用）
//        if (ship.hasTag(Tags.FRAGMENT_SWARM_START_WITH_ZERO_FRAGMENTS)) {
//            params.initialMembers = 0;
//        }

		// 创建并返回翻涌群特效的匿名子类，增加额外的闪烁和颜色逻辑
		return new VIII_RoilingSwarmEffect(ship, params) {
			// 颜色偏移工具，用于动态改变闪烁边缘色（基于武器发光色）
            private final ColorShifterUtil glowColorShifter = new ColorShifterUtil(new Color(0, 6, 191,255));
			// 标记是否需要重置闪烁参数
            private boolean resetFlash = false;

			/**
			 * 获取需要维持的成员数量（战机版本：根据船体等级动态调整）
			 * @return 维持成员数
			 */
			@Override
			public int getNumMembersToMaintain() {
				if (ship.isFighter()) {
					// 战机：维持成员数 = (0.2 + 0.8 * 船体等级) * 父类计算的维持数
					return (int)Math.round(((0.2f + 0.8f * ship.getHullLevel()) * super.getNumMembersToMaintain()));
				}
				return super.getNumMembersToMaintain();
			}

			/**
			 * 每帧更新（覆写父类，增加闪烁颜色和频率的动态调整）
			 * @param amount 时间增量（秒）
			 */
			@Override
			public void advance(float amount) {
				super.advance(amount);   // 调用父类更新逻辑

				glowColorShifter.advance(amount);   // 推进颜色偏移器

				// 性能提示：实际渲染时相当消耗性能，至少使每个群的开销翻倍
				// （原注释：当flashFrequency是原来10倍且flashRateMult更短时，现在*2还可以接受）

				// 如果舰船处于“相位模式”（VoltaicDischargeOnFireEffect中定义），则强制高频闪烁
				if (VIII_VoltaicDischargeOnFireEffect.isSwarmPhaseMode(ship)) {
					params.flashFrequency = 4f;      // 闪烁频率4次/秒
					params.flashProbability = 1f;    // 闪烁概率100%
					resetFlash = true;                // 标记需要重置（退出相位模式时恢复）
				} else {
					// 如果有发光武器（FRAGMENT_GLOW）
					if (!glowWeapons.isEmpty()) {
						float ammoFractionTotal = 0f;   // 加权弹药比例总和
						float totalOP = 0f;             // 武器总OP
						for (WeaponAPI w : glowWeapons) {
							float f = w.getAmmo() / Math.max(1f, w.getMaxAmmo()); // 当前弹药比例
							Color glowColor = w.getSpec().getGlowColor();         // 武器发光色
							// 让颜色偏移器随时间在武器发光色之间变化（参数：目标颜色、时间、速率等）
							glowColorShifter.shift(w, glowColor, 0.5f, 0.5f, 1f);
							float weight = w.getSpec().getOrdnancePointCost(null); // 武器装配点（OP）
							ammoFractionTotal += f * weight;
							totalOP += weight;
						}

						float ammoFraction = ammoFractionTotal / Math.max(1f, totalOP); // 平均弹药比例
						// 闪烁频率 = (1 + 弹药比例) * 2，再根据基础维持数缩放（限制在1~2倍之间）
						params.flashFrequency = (1f + ammoFraction) * 2f;
						params.flashFrequency *= Math.max(1f, Math.min(2f, params.baseMembersToMaintain / 50f));
						params.flashProbability = 1f;
						if (ammoFraction <= 0f) {
							params.flashProbability = 0f;   // 无弹药时不闪烁
						}

						// 计算闪烁边缘色的透明度
						float glowAlphaBase = 30f;
						if (ship.isFighter()) {
							glowAlphaBase = 18f;            // 战机透明度更低
						}

						// 额外发光加成：根据总OP（超过10的部分）线性增加，最高1
						float extraGlow = (totalOP - 10f) / 90f;
						if (extraGlow < 0) extraGlow = 0;
						if (extraGlow > 1f) extraGlow = 1f;

						int glowAlpha = (int)(glowAlphaBase + glowAlphaBase * (ammoFraction + extraGlow * 0.5f));
						if (glowAlpha > 255) glowAlpha = 255;
						// 设置闪烁边缘色：使用颜色偏移器当前色，调整亮度为255，再设置透明度
						params.flashFringeColor = Misc.setBrightness(glowColorShifter.getCurr(), 255);
						params.flashFringeColor = Misc.setAlpha(params.flashFringeColor, glowAlpha);

						resetFlash = true;
					} else {
						// 如果没有发光武器，且是攻击群（或需要重置闪烁），则将闪烁概率设为0
						//if (ThreatSwarmAI.isAttackSwarm(ship)) {
						if (resetFlash) {
							params.flashProbability = 0f;
							resetFlash = false;
						}
					}
				}

				// 以下为调试代码（统计正在闪烁的成员数量），生产环境注释
//                int flashing = 0;
//                for (SwarmMember p : members) {
//                    if (p.flash != null) {
//                        flashing++;
//                    }
//                }
//                System.out.println("Flashing: " + flashing + ", total: " + members.size());
			}
		};
	}

	/**
	 * 根据舰船尺寸获取基础碎片群规模（维持成员数）
	 * @param size 舰船尺寸
	 * @return 基础维持成员数
	 */
	public static int getBaseSwarmSize(HullSize size) {
        return switch (size) {
            case CAPITAL_SHIP -> 100;   // 主力舰：100
            case CRUISER -> 60;         // 巡洋舰：60
            case DESTROYER -> 40;       // 驱逐舰：40
            case FRIGATE -> 20;         // 护卫舰：20
            case FIGHTER -> 50;         // 战机：50
            case DEFAULT -> 20;         // 默认：20
        };
	}

	/**
	 * 根据舰船尺寸获取基础碎片重生率倍率（每秒恢复的成员比例）
	 * @param size 舰船尺寸
	 * @return 重生率倍率
	 */
	public static float getBaseSwarmRespawnRateMult(HullSize size) {
        return switch (size) {
            case CAPITAL_SHIP -> 5f;    // 主力舰：5
            case CRUISER -> 3f;         // 巡洋舰：3
            case DESTROYER -> 2f;       // 驱逐舰：2
            case FRIGATE -> 1f;         // 护卫舰：1
            case FIGHTER -> 0f;         // 战机：0（不重生）
            default -> 0f;
        };
	}



	/**
	 * 获取插件的描述参数（用于UI显示）
	 * @param index    参数索引
	 * @param hullSize 舰船尺寸
	 * @return 参数字符串
	 */
	public String getDescriptionParam(int index, HullSize hullSize) {
		// 索引0-3：不同尺寸的基础碎片数
		if (index == 0) return "" + (int)getBaseSwarmSize(HullSize.FRIGATE);
		if (index == 1) return "" + (int)getBaseSwarmSize(HullSize.DESTROYER);
		if (index == 2) return "" + (int)getBaseSwarmSize(HullSize.CRUISER);
		if (index == 3) return "" + (int)getBaseSwarmSize(HullSize.CAPITAL_SHIP);

		// 索引4-7：不同尺寸的基础重生率倍率
		if (index == 4) return "" + (int)getBaseSwarmRespawnRateMult(HullSize.FRIGATE);
		if (index == 5) return "" + (int)getBaseSwarmRespawnRateMult(HullSize.DESTROYER);
		if (index == 6) return "" + (int)getBaseSwarmRespawnRateMult(HullSize.CRUISER);
		if (index == 7) return "" + (int)getBaseSwarmRespawnRateMult(HullSize.CAPITAL_SHIP);

		return null;
	}

	/**
	 * 获取内置插件（S-mod）的描述参数（显示惩罚）
	 * @param index    参数索引
	 * @param hullSize 舰船尺寸
	 * @param ship     舰船对象
	 * @return 参数字符串
	 */
	@Override
	public String getSModDescriptionParam(int index, HullSize hullSize, ShipAPI ship) {
		if (index == 0) return "" + (int) Math.round(SMOD_CR_PENALTY * 100f) + "%";   // CR惩罚
		if (index == 1) return "" + (int) Math.round(SMOD_MAINTENANCE_PENALTY) + "%"; // 维护消耗惩罚
		return null;
	}

	/**
	 * 表示内置插件效果是否为惩罚（而不是增益），用于UI显示红色文字
	 * @return true
	 */
	@Override
	public boolean isSModEffectAPenalty() {
		return true;
	}
}