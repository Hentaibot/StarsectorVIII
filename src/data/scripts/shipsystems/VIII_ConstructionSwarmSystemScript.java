// 包路径：远行星号游戏API实现中的战斗威胁包
package data.scripts.shipsystems;

// 导入全局游戏实例类

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BoundsAPI.SegmentAPI;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipSystemAPI.SystemState;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.util.CountingMap;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.WeightedRandomPicker;
import data.hullmods.VIII_FragmentSwarmHullmod;
import data.scripts.VIII_threat.*;
import org.lwjgl.util.vector.Vector2f;

import java.util.ArrayList;
import java.util.List;

/**
 * 建造群系统脚本
 * 继承自BaseShipSystemScript，实现舰船系统的核心逻辑：消耗碎片和部署点来建造新的作战单位（战机/舰船）
 */
public class VIII_ConstructionSwarmSystemScript extends BaseShipSystemScript {

	// ==================== 常量定义（部分被注释，但保留在代码中） ====================
	// 最小冷却时间（未使用）
//    public static float MIN_COOLDOWN = 2f;
	// 最大冷却时间（未使用）
//    public static float MAX_COOLDOWN = 10f;
	// 冷却时间与部署点的倍数关系（未使用）
//    public static float COOLDOWN_DP_MULT = 0.25f;
	// 基础碎片消耗基数（用于计算建造不同单位所需的碎片数量）
	public static int BASE_FRAGMENTS = 50;

	// 每有一个督军级单位，其他舰船的权重值（用于AI决策建造督军）
	public static float OTHER_SHIP_WEIGHT_PER_OVERSEER = 6f;

	// 建造群移动速度倍率（建造中的单位速度减慢）
	public static float CONSTRUCTION_SWARM_SPEED_MULT = 0.33f;
	// 基础建造时间（秒）
//    public static float BASE_CONSTRUCTION_TIME = 2f;
//    public static float CONSTRUCTION_TIME_DP_MULT = 1f;
	public static float BASE_CONSTRUCTION_TIME = 5f;                     // 实际基础建造时间5秒
	public static float CONSTRUCTION_TIME_DP_MULT = 1.5f;                // 每点部署点增加1.5秒建造时间
	public static float CONSTRUCTION_TIME_OVERSEER_EXTRA = 13f;          // 督军级额外增加13秒（总30秒）

	// 巡洋舰+主力舰数量相对于驱逐舰的比例（如果低于此比例则优先建造大型单位）
	public static float NUM_LARGE_AS_FRACTION_OF_DESTROYERS = 0.5f;
	// 驱逐舰数量相对于护卫舰的比例（如果低于此比例则优先建造中型单位）
	public static float NUM_DESTROYERS_AS_FRACTION_OF_FRIGATES = 0.6f;

	// 快速建造护卫舰的最大数量（前几个护卫舰可快速建造）
	public static int FAST_CONSTRUCTION_FRIGATES_MAX = 2;

	// 最小CR、最小DP、最小碎片数量（通过init()动态计算）
	public static float MIN_CR;
	public static float MIN_DP;
	public static int MIN_FRAGMENTS;
	public static int MAX_FRAGMENTS;

	// ==================== 内部枚举：可建造单位类型 ====================
	public enum VIII_SwarmConstructableType {
		COMBAT_UNIT,   // 战斗单位（普通作战舰船/战机）
		OVERSEER,      // 督军级单位（特殊指挥单位）
		HIVE,          // 巢穴级单位（生产中心）
	}

	// ==================== 内部类：可建造单位的变种（包含类型、变种ID、CR、DP、碎片需求等） ====================
	public static class VIII_SwarmConstructableVariant {
		public VIII_SwarmConstructableType type;   // 单位类型
		public String variantId;              // 变种ID（对应ship_data.csv中的variant）
		public float cr;                      // 建造消耗的CR值（0~1，例如0.02表示消耗2%CR）
		public float dp;                      // 建造消耗的部署点
		public int fragments;                 // 建造消耗的碎片数量
		public HullSize size;                 // 舰船尺寸（护卫、驱逐、巡洋、主力）

		// 构造函数：根据变种ID自动计算cr、dp、fragments
		public VIII_SwarmConstructableVariant(VIII_SwarmConstructableType type, String variantId) {
			this.type = type;
			this.variantId = variantId;

			// 获取变种的规格
			ShipVariantAPI v = Global.getSettings().getVariant(variantId);
			dp = v.getHullSpec().getSuppliesToRecover();          // 部署点 = 恢复所需的补给数
			size = v.getHullSize();                               // 舰船尺寸

			// 根据尺寸设置基础CR消耗
			cr = 0.01f;
			if (v.getHullSize() == HullSize.FRIGATE) {
				cr = 0.02f;      // 护卫舰消耗2%CR
			} else if (v.getHullSize() == HullSize.DESTROYER) {
				cr = 0.04f;      // 驱逐舰消耗4%CR
			} else if (v.getHullSize() == HullSize.CRUISER) {
				cr = 0.06f;      // 巡洋舰消耗6%CR
			} else if (v.getHullSize() == HullSize.CAPITAL_SHIP) {
				cr = 0.1f;       // 主力舰消耗10%CR
			}

			// 特殊类型额外增加CR消耗
			if (type == VIII_SwarmConstructableType.HIVE) {
				cr += 0.02f;     // 巢穴额外+2%CR
			}
			if (type == VIII_SwarmConstructableType.OVERSEER) {
				cr += 0.01f;     // 督军额外+1%CR
			}
			// 计算所需碎片数量（调用静态方法）
			fragments = getFragmentCost(dp, size);
		}
	}

	// 所有可建造单位变种的列表
	public static List<VIII_SwarmConstructableVariant> VIII_CONSTRUCTABLE = new ArrayList<>();

	// 静态初始化标志（防止重复初始化）
	protected static boolean inited1 = false;

	/**
	 * 静态初始化方法
	 * 注意：不能在静态块中执行，因为AI脚本加载时会引用此类，导致游戏启动崩溃。
	 * 因此需要在使用前手动调用init()。
	 */
	public static void inits() {
		if (inited1) return;   // 已初始化则直接返回
		inited1 = true;
		// 添加各种战斗单位变种（ID来源于游戏数据）
		VIII_CONSTRUCTABLE.add(new VIII_SwarmConstructableVariant(VIII_SwarmConstructableType.COMBAT_UNIT, "VIII_musketeer_ATK"));
		VIII_CONSTRUCTABLE.add(new VIII_SwarmConstructableVariant(VIII_SwarmConstructableType.COMBAT_UNIT, "VIII_renhai_ATK"));
		VIII_CONSTRUCTABLE.add(new VIII_SwarmConstructableVariant(VIII_SwarmConstructableType.COMBAT_UNIT, "VIII_renhai_MISSILE"));
		VIII_CONSTRUCTABLE.add(new VIII_SwarmConstructableVariant(VIII_SwarmConstructableType.COMBAT_UNIT, "VIII_blizzard_ATK"));
		VIII_CONSTRUCTABLE.add(new VIII_SwarmConstructableVariant(VIII_SwarmConstructableType.COMBAT_UNIT, "VIII_blizzard_ATK_II"));
		VIII_CONSTRUCTABLE.add(new VIII_SwarmConstructableVariant(VIII_SwarmConstructableType.COMBAT_UNIT, "VIII_blizzard_ATK_III"));

		// 添加督军级和巢穴级变种
		VIII_CONSTRUCTABLE.add(new VIII_SwarmConstructableVariant(VIII_SwarmConstructableType.OVERSEER, "VIII_command_ATK"));
		VIII_CONSTRUCTABLE.add(new VIII_SwarmConstructableVariant(VIII_SwarmConstructableType.HIVE, "VIII_array_variant"));

		// 初始化全局最小/最大值
		MIN_CR = 1f;
		MIN_DP = 100f;
		MIN_FRAGMENTS = 500;
		MAX_FRAGMENTS = 0;

		// 遍历所有可建造变种，计算全局最小CR、最小DP、最小/最大碎片需求
		for (VIII_SwarmConstructableVariant v : VIII_CONSTRUCTABLE) {
			MIN_CR = Math.min(v.cr, MIN_CR);
			MIN_DP = Math.min(v.dp, MIN_DP);
			MIN_FRAGMENTS = Math.min(v.fragments, MIN_FRAGMENTS);
			MAX_FRAGMENTS = Math.max(v.fragments, MAX_FRAGMENTS);
		}
	}

	// ==================== 内部类：建造数据（存储在建单位的建造信息） ====================
	public static class VIII_SwarmConstructionData {
		public String variantId;               // 正在建造的变种ID
		public float constructionTime = 10f;   // 总建造时间（秒）
		public float preConstructionTravelTime = 3f; // 建造前飞行时间（秒，舰船从发射点飞到建造位置）
	}

	// 武器槽位选择器（用于确定发射建造群的位置）
	protected WeightedRandomPicker<WeaponSlotAPI> slots;
	// 是否准备好发射（防止重复发射）
	protected boolean readyToFire = true;
	// 剩余快速建造护卫舰的次数（初始为2）
	protected int fastConstructionLeft = FAST_CONSTRUCTION_FRIGATES_MAX;

	/**
	 * 查找舰船上的系统槽位（用于发射建造群）
	 * @param ship 目标舰船
	 */
	protected void findSlots(ShipAPI ship) {
		if (slots != null) return;   // 已找到则直接返回
		slots = new WeightedRandomPicker<>();
		// 遍历所有武器槽位，只选择系统槽位（system slot）
		for (WeaponSlotAPI slot : ship.getHullSpec().getAllWeaponSlotsCopy()) {
			if (slot.isSystemSlot()) {
				slots.add(slot);
			}
		}
	}

	/**
	 * 应用系统效果（由父类BaseShipSystemScript在系统激活、持续、关闭时调用）
	 * @param stats 舰船可变属性统计
	 * @param id    效果ID
	 * @param state 系统状态（IDLE/ACTIVE/OUT/COOLDOWN）
	 * @param effectLevel 效果强度（0~1）
	 */
	public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
		ShipAPI ship = null;
		// 判断属性统计关联的实体是否为舰船
		if (stats.getEntity() instanceof ShipAPI) {
			ship = (ShipAPI) stats.getEntity();
		} else {
			return;   // 非舰船实体无法处理
		}

		// 确保静态数据已初始化
		inits();

		// 当系统处于空闲、冷却或效果强度为0时，重置readyToFire标志
		if (state == State.IDLE || state == State.COOLDOWN || effectLevel <= 0f) {
			readyToFire = true;
		}

		// 当效果强度达到1（系统完全激活）且可以发射时，发射建造群
		if (effectLevel == 1 && readyToFire) {
			readyToFire = false;
			launchSwarm(ship);
		}
	}

	/**
	 * 发射建造群（核心逻辑）
	 * @param ship 发射舰船
	 */
	protected void launchSwarm(ShipAPI ship) {
		// 在 launchSwarm 开头增加
		CombatEngineAPI engine = Global.getCombatEngine();
		if (!engine.isEntityInPlay(ship)) return; // 防止 ship 不在战场
		// 查找发射槽位
		findSlots(ship);

		// 建造群战机联队的ID（在setting中定义）
		String wingId = VIII_SwarmLauncherEffect.CONSTRUCTION_SWARM_WING;


		CombatFleetManagerAPI manager = engine.getFleetManager(ship.getOwner()); // 获取阵营的舰队管理器
		manager.setSuppressDeploymentMessages(true);   // 临时抑制部署消息（避免UI刷屏）

		// 随机选择一个系统槽位作为发射点
		WeaponSlotAPI slot = slots.pick();
		// 计算槽位的世界坐标
		Vector2f loc = slot.computePosition(ship);
		// 计算槽位的中心角度
		float facing = slot.computeMidArcAngle(ship);

		// 生成建造群战机（实际是一个临时舰船，用于承载建造过程）
		ShipAPI fighter = manager.spawnShipOrWing(wingId, loc, facing, 0f, null);
		fighter.getWing().setSourceShip(ship);   // 设置来源舰船

		manager.setSuppressDeploymentMessages(false); // 恢复部署消息

		// 修改建造群的最大速度（乘以速度倍率，使建造单位移动缓慢）
		fighter.getMutableStats().getMaxSpeed().modifyMult("construction_swarm1", CONSTRUCTION_SWARM_SPEED_MULT);

		// 计算起飞速度向量（沿发射方向）
		Vector2f takeoffVel = Misc.getUnitVectorAtDegreeAngle(facing);
		takeoffVel.scale(fighter.getMaxSpeed());

		// 设置建造群战机不可见（DoNotRender），无爆炸特效，无残骸，音量降低等
		fighter.setDoNotRender(true);
		fighter.setExplosionScale(0f);
		fighter.setHulkChanceOverride(0f);
		fighter.setImpactVolumeMult(VIII_SwarmLauncherEffect.IMPACT_VOLUME_MULT);
		fighter.getArmorGrid().clearComponentMap();   // 清除装甲网格映射，避免武器/引擎受损
		// 添加起飞速度
		Vector2f.add(fighter.getVelocity(), takeoffVel, fighter.getVelocity());

		// 获取发射舰船的源碎片群（翻涌群）
		VIII_RoilingSwarmEffect sourceSwarm = VIII_RoilingSwarmEffect.getSwarmFor(ship);
		if (sourceSwarm == null) return;

		// 为建造群战机创建新的碎片群（翻涌群）
		VIII_RoilingSwarmEffect swarm = VIII_FragmentSwarmHullmod.createSwarmFor(fighter);
		swarm.params.flashFringeColor = VIII_VoltaicDischargeOnFireEffect.EMP_FRINGE_COLOR; // 设置闪烁颜色
		// 从原群系映射中移除（改变群系类别）
		VIII_RoilingSwarmEffect.getFlockingMap().remove(swarm.params.flockingClass, swarm);
		swarm.params.flockingClass = VIII_FragmentSwarmHullmod.CONSTRUCTION_SWARM_FLOCKING_CLASS; // 设置为建造群专属群系
		VIII_RoilingSwarmEffect.getFlockingMap().add(swarm.params.flockingClass, swarm);

		// 选择一个可建造的变种（根据当前战况）
		VIII_SwarmConstructableVariant pick = pickVariant(ship);
		if (pick == null) return;

		String variantId = pick.variantId;   // 要建造的变种ID
		// 以下注释掉的代码用于调试时强制指定某种单位
//        variantId = "standoff_unit_Type300";
//        variantId = "overseer_unit_Type250";
//        variantId = "skirmish_unit_Type100";
//        variantId = "assault_unit_Type200";
//        variantId = "assault_unit_Type201"; // no swarm
//        variantId = "hive_unit_Type350";

		// 获取变种规格
		ShipVariantAPI variant = Global.getSettings().getVariant(variantId);
		if (variant == null) return;

		// 消耗发射舰船的CR（战备值）
		ship.setCurrentCR(ship.getCurrentCR() - pick.cr);

		// 获取部署点消耗
		float dp = variant.getHullSpec().getSuppliesToRecover();

		// 计算建造所需碎片数量
		int numFragments = pick.fragments;
		// 根据不同尺寸设置缩放系数（半径、碰撞、血量、建造前飞行时间）
		float radiusMult = 1f;
		float collisionMult = 2f;
		float hpMult = 1f;
		float travelTime = 3f;

		if (variant.getHullSize() == HullSize.DESTROYER) {
			radiusMult = 2f;
			collisionMult = 4f;
			hpMult = radiusMult;
			travelTime = 4f;
		} else if (variant.getHullSize() == HullSize.CRUISER) {
			radiusMult = 3.5f;
			collisionMult = 6f;
			hpMult = radiusMult;
			travelTime = 5f;
		} else if (variant.getHullSize() == HullSize.CAPITAL_SHIP) {
			radiusMult = 4;
			collisionMult = 8f;
			hpMult = radiusMult;
			travelTime = 6f;
		}

		// 调整建造群战机的碰撞边界（放大）
		for (SegmentAPI s : fighter.getExactBounds().getOrigSegments()) {
			s.getP1().scale(collisionMult);
			s.getP2().scale(collisionMult);
			s.set(s.getP1().x, s.getP1().y, s.getP2().x, s.getP2().y);
		}
		fighter.setCollisionRadius(fighter.getCollisionRadius() * collisionMult);

		// 调整建造群战机的生命值（按比例放大）
		fighter.setMaxHitpoints(fighter.getMaxHitpoints() * hpMult);
		fighter.setHitpoints(fighter.getHitpoints() * hpMult);

		// 调整碎片群的参数：最大偏移半径、初始成员数、维持成员数
		swarm.params.maxOffset *= radiusMult;
		swarm.params.initialMembers = numFragments;
		swarm.params.baseMembersToMaintain = numFragments;

		// 判断要建造的单位是否为督军级（根据标签）
		boolean overseer = variant.getHullSpec().hasTag("VIII_threat_overseer");

		// 创建建造数据对象
		VIII_SwarmConstructionData data = new VIII_SwarmConstructionData();
		data.variantId = variantId;
		// 建造时间 = 基础时间 + 部署点 * 倍率，督军额外增加
		data.constructionTime = BASE_CONSTRUCTION_TIME + dp * CONSTRUCTION_TIME_DP_MULT;
		if (overseer) {
			data.constructionTime += CONSTRUCTION_TIME_OVERSEER_EXTRA;
		}
		data.preConstructionTravelTime = travelTime;

		// 如果还有快速建造次数且建造的是护卫舰，则建造时间缩短为2秒，并减少剩余次数
		if (fastConstructionLeft > 0) {
			if (pick.size == HullSize.FRIGATE) {
				fastConstructionLeft--;
				data.constructionTime = 2f;
			} else {
				fastConstructionLeft = 0;
			}
		}

		// 将建造数据存入碎片群的自定义数据字段
		swarm.custom1 = data;

		// 从源碎片群转移碎片到新碎片群
		int transfer = Math.min(numFragments, sourceSwarm.getNumActiveMembers());
		if (transfer > 0) {
			// 计算转移目标位置（在建造群战机前方偏移起飞速度的一半）
			loc = new Vector2f(takeoffVel);
			loc.scale(0.5f);
			Vector2f.add(loc, fighter.getLocation(), loc);
			sourceSwarm.transferMembersTo(swarm, transfer, loc, 100f);
		}

		// 如果转移数量不足，则添加新碎片
		int add = numFragments - transfer;
		if (add > 0) {
			swarm.addMembers(add);
		}
	}

	/**
	 * 根据当前战况选择合适的可建造变种
	 * @param ship 发射舰船
	 * @return 选中的变种，如果没有合适的则返回null
	 */
	public VIII_SwarmConstructableVariant pickVariant(ShipAPI ship) {
		inits();   // 确保静态数据已初始化

		// 调试用：强制返回某个变种
//        if (true) {
//            return new VIII_SwarmConstructableVariant(VIII_SwarmConstructableType.COMBAT_UNIT, "standoff_unit_Type302");
//        }

		CombatEngineAPI engine = Global.getCombatEngine();
		CombatFleetManagerAPI manager = engine.getFleetManager(ship.getOwner());
		if (manager == null) return null;

		// 获取发射舰船当前的碎片群，获取活跃碎片数量
		VIII_RoilingSwarmEffect swarm = VIII_RoilingSwarmEffect.getSwarmFor(ship);
		int fragments = swarm == null ? 0 : swarm.getNumActiveMembers();

		// 剩余部署点 = 最大强度 - 当前强度
		int dpLeft = manager.getMaxStrength() - manager.getCurrStrength();
		// 当前舰船的CR值
		float cr = ship.getCurrentCR();

		// 统计已部署的督军、巢穴、建造者数量及战斗权重
		int overseers = getNumOverseersDeployed(manager);
		int hives = getNumHivesDeployed(manager);
		int fabricators = getNumFabricatorsDeployed(manager);
		float combatWeight = getCombatWeightDeployed(manager);

		// 根据战斗权重计算期望的督军数量（每6权重需要一个督军）
		int wantOverseers = (int) Math.round(combatWeight / OTHER_SHIP_WEIGHT_PER_OVERSEER);
		if (wantOverseers < 1) wantOverseers = 1;

		// 计算期望的巢穴数量：战斗权重/16，加上建造者权重加成
		combatWeight += Math.max(0, fabricators - 1f) * 16f;
		int wantHives = (int) (combatWeight / 16f);
		if (wantHives < 1) wantHives = 1;
		if (wantHives > 2) wantHives = 2;

		// 还需要建造的督军和巢穴数量 = 期望值 - 已部署数量
		wantOverseers -= overseers;
		wantHives -= hives;

		// 统计已部署的各种尺寸战斗单位数量
		float frigates = getCombatDeployed(manager, HullSize.FRIGATE);
		float destroyers = getCombatDeployed(manager, HullSize.DESTROYER);
		float cruisers = getCombatDeployed(manager, HullSize.CRUISER);
		float capitals = getCombatDeployed(manager, HullSize.CAPITAL_SHIP);
		float large = cruisers + capitals;

		// 如果已有2个以上护卫舰，则不能再使用快速建造（fastConstructionLeft已无意义）
		if (frigates >= 2) {
			fastConstructionLeft = 0;
		}

		// 统计所有战斗单位变种中每种尺寸的数量（用于权重分配）
		CountingMap<HullSize> numCombatVariants = new CountingMap<>();
		for (VIII_SwarmConstructableVariant curr : VIII_CONSTRUCTABLE) {
			if (curr.type == VIII_SwarmConstructableType.COMBAT_UNIT) {
				numCombatVariants.add(curr.size);
			}
		}

		// 创建四个加权选择器：巢穴、督军、小型（护卫）、中型（驱逐）、大型（巡洋+主力）
		WeightedRandomPicker<VIII_SwarmConstructableVariant> hivePicker = new WeightedRandomPicker<>();
		WeightedRandomPicker<VIII_SwarmConstructableVariant> overseerPicker = new WeightedRandomPicker<>();
		WeightedRandomPicker<VIII_SwarmConstructableVariant> smallPicker = new WeightedRandomPicker<>();
		WeightedRandomPicker<VIII_SwarmConstructableVariant> mediumPicker = new WeightedRandomPicker<>();
		WeightedRandomPicker<VIII_SwarmConstructableVariant> largePicker = new WeightedRandomPicker<>();

		// 遍历所有可建造变种，根据资源条件（DP、CR、碎片）加入对应的选择器
		for (VIII_SwarmConstructableVariant curr : VIII_CONSTRUCTABLE) {
			if (curr.dp > dpLeft) continue;          // 部署点不足
			if (curr.cr > cr) continue;              // CR不足
			if (curr.fragments > fragments) continue; // 碎片不足

			if (curr.type == VIII_SwarmConstructableType.HIVE) {
				// 巢穴的权重与DP成反比（越便宜越优先）
				hivePicker.add(curr, 1f / curr.dp);
			} else if (curr.type == VIII_SwarmConstructableType.OVERSEER) {
				overseerPicker.add(curr, 1f / curr.dp);
			} else {
				// 战斗单位的权重：基础权重1/DP，再除以该尺寸的变种数量（避免某尺寸变种过多导致权重过高）
				float wMult = 1f / Math.max(1f, numCombatVariants.getCount(curr.size));
				if (curr.size == HullSize.FRIGATE) {
					smallPicker.add(curr, 1f / curr.dp * wMult);
				} else if (curr.size == HullSize.DESTROYER) {
					mediumPicker.add(curr, 1f / curr.dp * wMult);
				} else {
					largePicker.add(curr, 1f / curr.dp * wMult);
				}
			}
		}

		// 如果护卫舰数量不超过1，且有小单位可选，则优先建造护卫舰（快速铺开）
		if (frigates <= 1 && !smallPicker.isEmpty()) {
			return smallPicker.pick();
		}

		// 如果需要督军或巢穴，优先建造它们（根据需求数量决定优先级）
		if (wantOverseers > 0 || wantHives > 0) {
			if (wantOverseers >= wantHives && !overseerPicker.isEmpty()) {
				return overseerPicker.pick();
			} else if (!hivePicker.isEmpty()) {
				return hivePicker.pick();
			}
		}

		// 如果大型单位比例低于驱逐舰的50%，且有大单位可选，则建造大型单位
		if (large <= destroyers * NUM_LARGE_AS_FRACTION_OF_DESTROYERS && !largePicker.isEmpty()) {
			return largePicker.pick();
		}

		// 如果驱逐舰比例低于护卫舰的60%，且有中型单位可选，则建造中型单位
		if (destroyers <= frigates * NUM_DESTROYERS_AS_FRACTION_OF_FRIGATES && !mediumPicker.isEmpty()) {
			return mediumPicker.pick();
		}

		// 默认选择小型单位
		return smallPicker.pick();
	}

	/**
	 * 静态方法：判断某个建造群是否正在建造指定标签和尺寸的单位
	 * @param ship 建造群战机
	 * @param tag  标签（例如THREAT_OVERSEER）
	 * @param size 尺寸（可为null表示不限制）
	 * @return true表示正在建造匹配的单位
	 */
	public static boolean constructionSwarmWillBuild(ShipAPI ship, String tag, HullSize size) {
		// 不是战机 或 已有建造标记的舰船，返回false
		if (!ship.isFighter() || ship.hasTag(VIII_ThreatShipConstructionScript.SWARM_CONSTRUCTING_SHIP)) {
			return false;
		}
		VIII_RoilingSwarmEffect swarm = VIII_RoilingSwarmEffect.getSwarmFor(ship);
		if (swarm == null) {
			return false;
		}
		// 检查自定义数据是否包含建造数据
		if (swarm.custom1 instanceof VIII_SwarmConstructionData data) {
            ShipVariantAPI v = Global.getSettings().getVariant(data.variantId);
			// 如果变种拥有指定标签，且（尺寸为null或匹配），则返回true
			if (v.getHullSpec().hasTag(tag)) {
				return size == null || v.getHullSize() == size;
			}
		}
		return false;
	}

	// ==================== 以下为各种统计方法，用于获取已部署单位的信息 ====================

	/**
	 * 获取已部署的建造者（FABRICATOR）数量
	 * @param manager 舰队管理器
	 * @return 数量
	 */
	public static int getNumFabricatorsDeployed(CombatFleetManagerAPI manager) {
		inits();
		int count = 0;
		for (DeployedFleetMemberAPI dfm : manager.getDeployedCopyDFM()) {
			ShipAPI ship = dfm.getShip();
			if (ship == null) continue;
			// 检查舰船规格是否有THREAT_FABRICATOR标签
			if (ship.getHullSpec().hasTag("VIII_threat_fabricator")) {
				count++;
			}
		}
		return count;
	}

	/**
	 * 获取已部署的督军（OVERSEER）数量（包括正在建造中的）
	 * @param manager 舰队管理器
	 * @return 数量
	 */
	public static int getNumOverseersDeployed(CombatFleetManagerAPI manager) {
		inits();
		int count = 0;
		for (DeployedFleetMemberAPI dfm : manager.getDeployedCopyDFM()) {
			ShipAPI ship = dfm.getShip();
			if (ship == null) continue;
			// 检查是否正在建造督军
			if (constructionSwarmWillBuild(ship, "VIII_threat_overseer", null)) {
				count++;
				continue;
			}
			if (ship.isFighter()) continue;  // 非建造中的战机不计入
			// 检查舰船规格是否有THREAT_OVERSEER标签
			if (ship.getHullSpec().hasTag("VIII_threat_overseer")) {
				count++;
			}
		}
		return count;
	}

	/**
	 * 获取已部署的巢穴（HIVE）数量（包括正在建造中的）
	 * @param manager 舰队管理器
	 * @return 数量
	 */
	public static int getNumHivesDeployed(CombatFleetManagerAPI manager) {
		inits();
		int count = 0;
		for (DeployedFleetMemberAPI dfm : manager.getDeployedCopyDFM()) {
			ShipAPI ship = dfm.getShip();
			if (ship == null) continue;
			if (constructionSwarmWillBuild(ship, "VIII_threat_hive", null)) {
				count++;
				continue;
			}
			if (ship.isFighter()) continue;
			if (ship.getHullSpec().hasTag("VIII_threat_hive")) {
				count++;
			}
		}
		return count;
	}

	/**
	 * 获取已部署单位的战斗权重（用于AI决策）
	 * @param manager 舰队管理器
	 * @return 总权重
	 */
	public static float getCombatWeightDeployed(CombatFleetManagerAPI manager) {
		inits();
		float weight = 0;
		for (DeployedFleetMemberAPI dfm : manager.getDeployedCopyDFM()) {
			ShipAPI ship = dfm.getShip();
			if (ship == null) continue;

			// 处理正在建造中的单位（战机形态，包含建造数据）
			if (ship.isFighter() && !ship.hasTag(VIII_ThreatShipConstructionScript.SWARM_CONSTRUCTING_SHIP)) {
				VIII_RoilingSwarmEffect swarm = VIII_RoilingSwarmEffect.getSwarmFor(ship);
				if (swarm != null && swarm.custom1 instanceof VIII_SwarmConstructionData data) {
                    ShipVariantAPI v = Global.getSettings().getVariant(data.variantId);
					if (v.getHullSpec().hasTag("VIII_threat_combat")) {
						// 根据尺寸增加权重
						switch (v.getHullSize()) {
							case CAPITAL_SHIP: weight += 8; break;
							case CRUISER: weight += 4; break;
							case DESTROYER: weight += 2; break;
							case FRIGATE: weight += 1; break;
							case FIGHTER: weight += 1; break;
						}
					}
				}
				continue;
			}

			// 普通战斗单位，通过Misc.getShipWeight获取权重
			if (ship.getHullSpec().hasTag("VIII_threat_combat")) {
				weight += Misc.getShipWeight(ship, false);
			}
		}
		return weight;
	}

	/**
	 * 获取已部署的特定尺寸的战斗单位数量（包括正在建造中的）
	 * @param manager 舰队管理器
	 * @param size    舰船尺寸
	 * @return 数量
	 */
	public static int getCombatDeployed(CombatFleetManagerAPI manager, HullSize size) {
		inits();
		int count = 0;
		for (DeployedFleetMemberAPI dfm : manager.getDeployedCopyDFM()) {
			ShipAPI ship = dfm.getShip();
			if (ship == null) continue;

			if (constructionSwarmWillBuild(ship, "VIII_threat_combat", size)) {
				count++;
				continue;
			}
			if (ship.isFighter() || ship.getHullSize() != size) continue;
			if (ship.getHullSpec().hasTag("VIII_threat_combat")) {
				count++;
			}
		}
		return count;
	}

	/**
	 * 根据部署点和尺寸计算建造该单位所需的碎片数量
	 * @param dp   部署点
	 * @param size 舰船尺寸
	 * @return 碎片数量
	 */
	public static int getFragmentCost(float dp, HullSize size) {
		// 基础倍数：1 * dp/5
		float numMult = dp / 5f;
		if (size == HullSize.DESTROYER) {
			numMult = 2f * dp / 20f;   // 驱逐舰倍数降低（实际上2/20=0.1，但原意可能不同）
		} else if (size == HullSize.CRUISER) {
			numMult = 3f * dp / 20f;
		} else if (size == HullSize.CAPITAL_SHIP) {
			numMult = 5f * dp / 40f;
		}
		return (int) Math.round(BASE_FRAGMENTS * numMult);
	}

	// ==================== 以下为UI提示和可用性检查 ====================

	/**
	 * 获取系统在UI上显示的文字（鼠标悬停时）
	 * @param system 系统对象
	 * @param ship   舰船
	 * @return 提示文字（如果系统不可用则返回原因，否则返回"READY"）
	 */
	@Override
	public String getInfoText(ShipSystemAPI system, ShipAPI ship) {
		inits();
		if (system.isOutOfAmmo()) return null;
		if (system.getState() != SystemState.IDLE) return null;

		if (!enoughFragments(system, ship)) {
			return "低碎片数量";    // 碎片不足
		}
		if (!enoughDP(system, ship)) {
			return "低部署点";           // 部署点不足
		}
		if (!enoughCR(system, ship)) {
			return "低战备值";           // 战备值不足
		}
		return "就绪";                // 可用
	}

	/**
	 * 检查是否有足够的CR（战备值）
	 */
	public boolean enoughCR(ShipSystemAPI system, ShipAPI ship) {
		return ship.getCurrentCR() >= MIN_CR;
	}

	/**
	 * 检查是否有足够的部署点（考虑正在建造的单位占用的DP）
	 */
	public boolean enoughDP(ShipSystemAPI system, ShipAPI ship) {
		CombatEngineAPI engine = Global.getCombatEngine();
		CombatFleetManagerAPI manager = engine.getFleetManager(ship.getOwner());
		if (manager == null) return true;

		// 计算剩余部署点
		int dpLeft = manager.getMaxStrength() - manager.getCurrStrength();

		// 减去正在建造中的单位将来会占用的部署点
		for (DeployedFleetMemberAPI dfm : manager.getDeployedCopyDFM()) {
			ShipAPI ship2 = dfm.getShip();
			if (ship2 == null) continue;
			// 只考虑战机且不是建造中标记的（即正在建造的）
			if (!ship2.isFighter() || ship2.hasTag(VIII_ThreatShipConstructionScript.SWARM_CONSTRUCTING_SHIP)) {
				continue;
			}
			VIII_RoilingSwarmEffect swarm = VIII_RoilingSwarmEffect.getSwarmFor(ship2);
			if (swarm == null) continue;
			if (swarm.custom1 instanceof VIII_SwarmConstructionData data) {
                ShipVariantAPI v = Global.getSettings().getVariant(data.variantId);
				dpLeft -= (int) v.getHullSpec().getSuppliesToRecover(); // 减去将要消耗的DP
			}
		}

		return dpLeft >= MIN_DP;
	}

	/**
	 * 检查是否有足够的碎片
	 */
	public boolean enoughFragments(ShipSystemAPI system, ShipAPI ship) {
		VIII_RoilingSwarmEffect swarm = VIII_RoilingSwarmEffect.getSwarmFor(ship);
		int active = swarm == null ? 0 : swarm.getNumActiveMembers();
		int required = MIN_FRAGMENTS;
		return active >= required;
	}

	/**
	 * 判断系统是否可用（被UI调用）
	 */
	@Override
	public boolean isUsable(ShipSystemAPI system, ShipAPI ship) {
		inits();
		return enoughFragments(system, ship) && enoughDP(system, ship) && enoughCR(system, ship);
	}
}