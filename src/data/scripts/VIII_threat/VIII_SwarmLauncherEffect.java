// 包路径：远行星号游戏API实现中的战斗威胁包
package data.scripts.VIII_threat;

// 导入游戏状态枚举

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.impl.campaign.ids.HullMods;
import com.fs.starfarer.api.impl.campaign.ids.Stats;
import com.fs.starfarer.api.util.Misc;
import data.hullmods.VIII_FragmentSwarmHullmod;
import data.scripts.shipsystems.VIII_DroneStrikeStats;
import org.lwjgl.util.vector.Vector2f;

import java.util.HashMap;
import java.util.Map;

/**
 * 碎片群发射器效果类
 * 实现开火效果插件、每帧武器效果插件和碎片群发射武器接口
 * 负责从碎片群发射器武器发射攻击群（战机联队），并转移碎片群成员到新生成的战机上。
 */
public class VIII_SwarmLauncherEffect implements OnFireEffectPlugin, EveryFrameWeaponEffectPlugin, VIII_SwarmLaunchingWeapon {

	// 建造群战机联队ID（用于建造任务）
	public static String CONSTRUCTION_SWARM_WING = "VIII_construction_swarm_wing";
	// 建造群变种ID（在setting中定义的战机变种）
	public static String CONSTRUCTION_SWARM_VARIANT = "VIII_attack_swarm_Construction";
	// 回收群战机联队ID
	public static String RECLAMATION_SWARM_WING = "VIII_reclamation_swarm_wing";
	// 回收群变种ID
	public static String RECLAMATION_SWARM_VARIANT = "VIII_attack_swarm_Reclamation";

	// 攻击群船体ID（用于舰船版本）
	public static String ATTACK_SWARM_HULL = "VIII_attack_swarm";
	// 攻击群战机联队ID（标准攻击群）
	public static String ATTACK_SWARM_WING = "VIII_attack_swarm_wing";
	// 攻击群变种ID
	public static String ATTACK_SWARM_VARIANT = "VIII_attack_swarm_Attack";
	// 碎片群发射器武器ID
	public static String SWARM_LAUNCHER = "VIII_swarm_launcher";

	// ---- 新增：暴雪攻击群常量 ----
	public static String BLIZZARD_SWARM_WING = "VIII_blizzard_swarm_wing";
	public static String BLIZZARD_SWARM_VARIANT = "VIII_blizzard_swarm_Attack"; // 推测的变种ID
	public static String BLIZZARD_LAUNCHER_WEAPON = "VIII_swarm_blizzard";

	// ========== 新增：攻击群标识常量 ==========
	/** 攻击群来源舰船的自定义数据键 */
	public static final String ATTACK_OWNER_KEY = "VIII_attack_swarm_owner";
	/** 攻击群类型标识的自定义数据键 */
	public static final String ATTACK_TAG_KEY = "VIII_attack_swarm_tag";

	// 冲击音量倍率（发射时战机的音量降低到33%）
	public static float IMPACT_VOLUME_MULT = 0.33f;

	// 初始生成延迟（秒），发射器激活后等待一段时间才可发射
	public static float INITIAL_SPAWN_DELAY = 1f;

	// 静态映射：战机联队ID -> 所需的碎片数量
	public static Map<String, Integer> FRAGMENT_NUM = new HashMap<>();
	// 静态映射：战机联队ID -> 碎片群围绕半径（像素）
	public static Map<String, Integer> SWARM_RADIUS = new HashMap<>();
	// 静态映射：战机联队ID -> 联队最大成员数（同时可存在的战机数量）
	public static Map<String, Integer> WING_SIZE = new HashMap<>();
	// 静态映射：武器ID -> 对应发射的战机联队ID
	public static Map<String, String> WING_IDS = new HashMap<>();

	static {
		// 原有攻击群配置
		FRAGMENT_NUM.put(ATTACK_SWARM_WING, 50);
		SWARM_RADIUS.put(ATTACK_SWARM_WING, 20);
		WING_SIZE.put(ATTACK_SWARM_WING, 4);
		WING_IDS.put(SWARM_LAUNCHER, ATTACK_SWARM_WING);
		// 建造群配置
		FRAGMENT_NUM.put(CONSTRUCTION_SWARM_WING, 50);
		SWARM_RADIUS.put(CONSTRUCTION_SWARM_WING, 50);
		// ---- 新增：暴雪群配置 ----
		FRAGMENT_NUM.put(BLIZZARD_SWARM_WING, 30);
		SWARM_RADIUS.put(BLIZZARD_SWARM_WING, 20);  // 与攻击群相同的半径
		WING_SIZE.put(BLIZZARD_SWARM_WING, 3);
		WING_IDS.put(BLIZZARD_LAUNCHER_WEAPON, BLIZZARD_SWARM_WING);
	}

	// 当前激活的战机联队（已发射但未完全消耗的）
	protected FighterWingAPI currWing = null;
	// 是否等待只剩一架战机时才允许发射新联队（避免累积过多）
	protected boolean waitUntilOneLeft = false;
	// 自武器可用后经过的时间（秒），用于初始延迟判断
	protected float elapsed = 0f;

	/**
	 * 构造函数（空）
	 */
	public VIII_SwarmLauncherEffect() {

	}

	/**
	 * 每帧更新方法（EveryFrameWeaponEffectPlugin接口）
	 * @param amount 时间增量（秒）
	 * @param engine 战斗引擎
	 * @param weapon 当前武器
	 */
	@Override
	public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
		ShipAPI ship = weapon.getShip();   // 获取武器所属舰船
		if (ship == null) return;

		// 如果武器消耗弹药且弹药已耗尽，则返回（不能发射）
		if (weapon.usesAmmo() && weapon.getAmmo() <= 0) return;

		elapsed += amount;
		// 如果尚未度过初始延迟，或者舰船正处于“建造中”状态，则强制禁用武器
		if (elapsed < INITIAL_SPAWN_DELAY || ship.hasTag(VIII_ThreatShipConstructionScript.SHIP_UNDER_CONSTRUCTION)) {
			weapon.setForceDisabled(true);
			return;
		}

		// 获取舰船的碎片群
		VIII_RoilingSwarmEffect swarm = VIII_RoilingSwarmEffect.getSwarmFor(ship);
		int active = swarm == null ? 0 : swarm.getNumActiveMembers();  // 当前活跃碎片数
		int required = FRAGMENT_NUM.get(getWingId(weapon));            // 发射所需碎片数
		boolean disable = active < required;                           // 如果碎片不足则禁用武器

		int max = getWingSize(weapon);                    // 最大允许同时存在的联队成员数
		int swarmsActive = 0;                             // 当前联队中活跃的战机数量
		if (currWing != null) swarmsActive = currWing.getWingMembers().size();
		// 如果当前联队存在且已达到最大规模，则等待直到只剩一架
		waitUntilOneLeft |= currWing != null && swarmsActive >= max;
		// 特殊情况：如果只剩一架战机且距离武器超过1000像素，也强制等待（让战机返回）
		if (currWing != null && currWing.getWingMembers().size() == 1 && currWing.getLeader() != null) {
			float dist = Misc.getDistance(currWing.getLeader().getLocation(), weapon.getLocation());
			if (dist > 1000f) {
				waitUntilOneLeft = true;
			}
		}
		if (waitUntilOneLeft) {
			// 如果满足等待结束条件（联队已不足一架且最大规模>1，或者联队为空且最大规模<=1），则重置等待标志
			if (currWing != null && (swarmsActive <= 1 && max > 1 || swarmsActive == 0)) {
				waitUntilOneLeft = false;
				currWing = null;
			} else {
				disable = true;   // 等待期间禁用武器
			}
		}

		// 如果没有当前联队，且碎片群存在，则检查所有碎片武器所需的碎片数是否足够
		if (currWing == null && swarm != null) {
			int preferred = getPreferredNumFragmentsToFireConsideringAllWeapons(ship);
			preferred = (int) Math.min(preferred, swarm.params.baseMembersToMaintain * 0.9f);
			if (active < preferred ) {
				disable = true;   // 碎片不足时禁用
			}
		}

		weapon.setForceDisabled(disable);   // 应用禁用状态

		// 如果是玩家舰船，在UI状态栏显示当前活跃的联队数量
		boolean playerShip = Global.getCurrentState() == GameState.COMBAT &&
				Global.getCombatEngine() != null && Global.getCombatEngine().getPlayerShip() == ship;
		if (playerShip) {
			Global.getCombatEngine().maintainStatusForPlayerShip(this,
					Global.getSettings().getSpriteName("ui", "icon_tactical_swarm_launcher"),
					weapon.getDisplayName(),
					"集群已激活: " + swarmsActive,
					swarmsActive <= 0);   // 如果无活跃联队则显示为警告状态
		}

		// 将当前联队存储到武器的自定义数据中（供其他逻辑使用）
		weapon.setCustom(currWing);

		// 如果舰船安装了威胁派系船体插件，则武器弹药无限（自动补满）
		if (ship.getVariant().hasHullMod("VIII_FragmentSwarmHullmod")) {
			weapon.setAmmo(weapon.getMaxAmmo());
		}

		if (disable) {
			return;   // 武器禁用，不执行开火
		}

		// 强制武器在一帧内开火（触发onFire）
		weapon.setForceFireOneFrame(true);
	}

	/**
	 * 获取当前武器对应的战机联队ID
	 * @param weapon 武器对象
	 * @return 联队ID
	 */
	public String getWingId(WeaponAPI weapon) {
		// 首先尝试根据武器ID查找映射
		String wingId = WING_IDS.get(weapon.getId());
		if (wingId != null) return wingId;
		// 默认返回标准攻击群
		return ATTACK_SWARM_WING;
	}

	/**
	 * 获取当前武器允许的最大联队规模（同时存在的战机数量）
	 * @param weapon 武器对象
	 * @return 联队规模（整数）
	 */
	public int getWingSize(WeaponAPI weapon) {
		float wingSize = WING_SIZE.get(getWingId(weapon));  // 基础规模
		if (weapon.getShip() != null) {
			// 应用动态修正（来自船体插件等）
			wingSize = weapon.getShip().getMutableStats().getDynamic().getValue(
					Stats.SWARM_LAUNCHER_WING_SIZE_MOD, wingSize);
		}
		return (int) Math.round(wingSize);
	}

	/**
	 * 武器开火时的处理（OnFireEffectPlugin接口）
	 * @param projectile 发射的投射物（但实际会被移除，仅作为触发器）
	 * @param weapon     发射武器
	 * @param engine     战斗引擎
	 */
	public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
		// 获取舰队管理器
		CombatFleetManagerAPI manager = engine.getFleetManager(projectile.getOwner());
		manager.setSuppressDeploymentMessages(true);   // 临时抑制部署消息
		// 生成战机联队（领导者战机）
		ShipAPI leader = manager.spawnShipOrWing(getWingId(weapon),
				projectile.getLocation(), projectile.getFacing(), 0f, null);
		leader.getWing().setSourceShip(projectile.getSource());   // 设置来源舰船
		manager.setSuppressDeploymentMessages(false);  // 恢复消息
		// 计算起飞速度（沿武器朝向，乘以最大速度）
		Vector2f takeoffVel = Misc.getUnitVectorAtDegreeAngle(projectile.getFacing());
		takeoffVel.scale(leader.getMaxSpeed());
		// 为联队中所有战机应用配置：不可见、无爆炸、无残骸、音量降低、无武器/引擎损伤
		for (ShipAPI curr : leader.getWing().getWingMembers()) {
			curr.setDoNotRender(true);
			curr.setExplosionScale(0f);
			curr.setHulkChanceOverride(0f);
			curr.setImpactVolumeMult(IMPACT_VOLUME_MULT);
			curr.getArmorGrid().clearComponentMap(); // 防止武器/引擎受损
			// --- 标记暴雪攻击群成员（原有逻辑）---
			String wingId = getWingId(weapon);
			if (BLIZZARD_SWARM_WING.equals(wingId)) {
				curr.setCustomData(VIII_DroneStrikeStats.BLIZZARD_OWNER_KEY, weapon.getShip());
				curr.setCustomData(VIII_DroneStrikeStats.BLIZZARD_TAG_KEY, Boolean.TRUE);
			}
			// --- 新增：标记标准攻击群成员 ---
			else if (ATTACK_SWARM_WING.equals(wingId)) {
				curr.setCustomData(ATTACK_OWNER_KEY, weapon.getShip());
				curr.setCustomData(ATTACK_TAG_KEY, Boolean.TRUE);
			}
			// ---------------------------------
			// 如果已有当前联队（currWing非空），则将新生成的战机加入现有联队
			if (currWing != null) {
				if (curr.getWing() != null) {
					curr.getWing().removeMember(curr);
					// 重要：必须从管理器移除原联队，否则资源无法正确清理
					manager.removeDeployed(curr.getWing(), false);
				}
				curr.setWing(currWing);
				currWing.addMember(curr);
			}
			// 添加起飞速度
			Vector2f.add(curr.getVelocity(), takeoffVel, curr.getVelocity());
		}
		// 更新当前联队引用（如果currWing为空，则新生成联队成为当前联队）
		currWing = leader.getWing();
		// 从发射舰船的碎片群转移碎片到新生成的战机上
		VIII_RoilingSwarmEffect sourceSwarm = VIII_RoilingSwarmEffect.getSwarmFor(weapon.getShip());
		if (sourceSwarm != null) {
			// 为领导战机创建碎片群
			VIII_RoilingSwarmEffect swarm = VIII_FragmentSwarmHullmod.createSwarmFor(leader);
			// 所需碎片数量
			int transfer = FRAGMENT_NUM.get(getWingId(weapon));
			if (transfer > 0) {
				// 计算转移位置：在战机前方起飞速度方向的一半处
				Vector2f loc = new Vector2f(takeoffVel);
				loc.scale(0.5f);
				Vector2f.add(loc, leader.getLocation(), loc);
				sourceSwarm.transferMembersTo(swarm, transfer, loc, 100f);
			}
		}
		// 移除原投射物（因为武器本身不发射真实弹丸，只是触发）
		engine.removeEntity(projectile);
	}

	/**
	 * 考虑所有碎片武器，计算总共需要的碎片数（优先发射）
	 * @param ship 舰船
	 * @return 需要的碎片总数
	 */
	public int getPreferredNumFragmentsToFireConsideringAllWeapons(ShipAPI ship) {
		VIII_RoilingSwarmEffect swarm = VIII_RoilingSwarmEffect.getSwarmFor(ship);
		if (swarm == null) return 0;

		int req = 0;
		for (WeaponAPI w : ship.getAllWeapons()) {
			// 只考虑同样是碎片群发射器的武器（SwarmLaunchingWeapon）
			if (w.getEffectPlugin() instanceof VIII_SwarmLaunchingWeapon effect) {
				if (w.isFiring()) continue;   // 正在开火的武器跳过（避免重复计数）
                req += effect.getPreferredNumFragmentsToFire(w);
			}
		}
		return req;
	}

	/**
	 * 获取当前武器偏好的碎片消耗数（用于AI决策）
	 * @param weapon 武器对象
	 * @return 消耗碎片数量
	 */
	@Override
	public int getPreferredNumFragmentsToFire(WeaponAPI weapon) {
		Integer required = FRAGMENT_NUM.get(getWingId(weapon));
		if (required == null) return 0;
		int wingSize = getWingSize(weapon);
		int num = Math.min(wingSize, 2);   // 每次开火最多消耗2倍联队规模的碎片？实际为min(规模,2)
		return required * num;
	}

	// ==================== 被注释的测试方法（用于调试） ====================
	// 该方法用于创建一个测试用的“居住者”碎片群，此处不再详细注释
//    public static RoilingSwarmEffect createTestDwellerSwarmFor(ShipAPI ship) { ... }
}