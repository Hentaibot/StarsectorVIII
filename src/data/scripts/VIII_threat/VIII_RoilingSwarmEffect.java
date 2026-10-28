// 包路径：远行星号游戏API实现中的战斗威胁包
package data.scripts.VIII_threat;

// 导入全局游戏实例类

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.util.*;
import org.lwjgl.util.vector.Vector2f;

import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * 翻涌群特效（Roiling Swarm Effect）
 * 实现围绕附着实体（舰船、导弹等）旋转/飘浮的碎片群，具有物理模拟（弹簧力、摩擦）、成员交换、闪烁效果等。
 * 该特效是威胁派系碎片武器系统的核心视觉和功能组件。
 */
public class VIII_RoilingSwarmEffect extends BaseCombatLayeredRenderingPlugin {

	// 内部接口：用于动态修改碎片成员的偏移向量
	public interface VIII_SwarmMemberOffsetModifier {
		void modifyOffset(VIII_SwarmMember p);
	}

	/**
	 * 翻涌群参数类
	 * 封装了所有控制碎片群行为的可配置参数，包括视觉、物理、生成、交换等。
	 */
	public static class VIII_RoilingSwarmParams {
		// 精灵图目录和键名（默认使用 misc/threat_swarm_pieces）
		public String spriteCat = "misc";
		public String spriteKey = "VIII_NanoFragments";
		// 消散音效ID
		public String despawnSound = "threat_swarm_destroyed";

		/**
		 * 成员交换类标识：如果非空，则会与附近相同类的其他碎片群交换成员。
		 * 要求交换的群使用相同的精灵表和发光颜色。
		 */
		public String memberExchangeClass = null;
		public float memberExchangeRange = 500f;      // 交换范围（像素）
		public int minMembersToExchange = 1;          // 最少交换数量
		public int maxMembersToExchange = 3;          // 最多交换数量
		public float memberExchangeRate = 0.1f;       // 交换频率（每秒尝试次数）

		// 集群类标识（用于群体行为分组，如 flocking）
		public String flockingClass = null;

		public float baseSpriteSize = 20f;            // 基础精灵大小（像素）

		public float baseDur = 100000f;               // 基础持续时间（秒，极大值）
		public float durRange = 0f;                   // 持续时间随机范围
		public float despawnDist = 0f;                // 消散距离（超出则消散，0表示禁用）

		public float baseScale = 0.5f;                // 基础缩放比例
		public float scaleRange = 0.5f;               // 缩放随机范围
		public float baseFriction = 100f;             // 基础摩擦力
		public float frictionRange = 500f;            // 摩擦力随机范围
		public float baseSpringConstant = 50f;        // 基础弹簧常数
		public float springConstantNegativeRange = 20f; // 弹簧常数负向随机范围
		public float baseSpringFreeLength = 20f;      // 基础弹簧自由长度
		public float springFreeLengthRange = 20f;     // 自由长度随机范围

		public float offsetRotationDegreesPerSecond = 0f; // 偏移旋转速度（度/秒）

		public float lateralFrictionFactor = 20f;     // 横向摩擦因子
		public float lateralFrictionTurnRateFactor = 0; // 横向摩擦随角速度增加因子
		//public float lateralFrictionFactor = 0.5f;
		//public float lateralFrictionTurnRateFactor = 0.2f;

		public float minSpeedForFriction = 25f;       // 启用摩擦的最小速度

		public float flashRateMult = 1f;              // 闪烁率倍率
		public float flashRadius = 100f;              // 闪烁半径（像素）
		public float flashCoreRadiusMult = 1f;        // 闪烁核心半径倍率
		public float flashFrequency = 1f;             // 闪烁频率（每秒次数）
		public int numToFlash = 1;                    // 每次闪烁的成员数量
		public int numToRespawn = 1;                  // 每次重生的成员数量
		public float preFlashDelay = 0f;              // 闪烁前延迟（秒）
		public float flashProbability = 0f;           // 闪烁概率（0~1）
		public boolean renderFlashOnSameLayer = false; // 是否在同一图层渲染闪烁（否则在单独图层）
		public Color flashFringeColor = new Color(0, 42, 255,255); // 闪烁边缘色（红色）
		public Color flashCoreColor = new Color(0, 42, 255,255);     // 闪烁核心色（白色）

		public float alphaMult = 1f;                  // 全局透明度倍率
		public float alphaMultBase = 1f;              // 基础透明度倍率
		public float alphaMultFlash = 1f;             // 闪烁透明度倍率
		public Color color = Color.white;             // 基础颜色

		public float minFadeoutTime = 1f;             // 最小淡出时间（秒）
		public float maxFadeoutTime = 1f;             // 最大淡出时间（秒）
		public float minDespawnTime = 2f;             // 最小消散时间（秒）
		public float maxDespawnTime = 1f;             // 最大消散时间（秒）

		public boolean autoscale = false;             // 是否自动缩放（根据缩放器）

		/**
		 * 拉伸量乘以该值后开方，用于弹簧力计算中的非线性修正。
		 */
		public float springStretchMult = 10f;

		public float swarmLeadsByFractionOfVelocity = 0.03f; // 群领先速度的比例（提前量）

		public float outspeedAttachedEntityBy = 100f; // 碎片速度超出附着实体的数值（像素/秒）

		public float visibleRange = 500f;             // 可见范围（像素）
		public float maxTurnRate = 60f;               // 最大旋转速率（度/秒）
		public float spawnOffsetMult = 0f;            // 生成偏移倍率
		public float spawnOffsetMultForInitialSpawn = -1f; // 初始生成偏移倍率（-1表示使用spawnOffsetMult）
		public float maxSpeed = 500f;                 // 最大速度（像素/秒）
		public float minOffset = 0f;                  // 最小偏移半径（像素）
		public float maxOffset = 20f;                 // 最大偏移半径（像素）
		public boolean generateOffsetAroundAttachedEntityOval = false; // 是否围绕附着实体生成椭圆偏移

		public VIII_SwarmMemberOffsetModifier offsetModifier = null; // 自定义偏移修改器

		public boolean withInitialMembers = true;     // 是否生成初始成员
		public boolean withRespawn = true;            // 是否允许重生
		public int initialMembers = 50;               // 初始成员数量
		public int baseMembersToMaintain = 50;        // 基础维持成员数
		public boolean removeMembersAboveMaintainLevel = true; // 是否移除超出维持数的成员
		public int maxNumMembersToAlwaysRemoveAbove = -1; // 超过该数量时强制移除（-1表示不强制）
		public float memberRespawnRate = 1f;          // 成员重生率（每秒重生比例）
		public float offsetRerollFractionOnMemberRespawn = 0f; // 成员重生时偏移重滚的比例

		public Set<String> tags = new LinkedHashSet<>(); // 标签集合（用于标记特殊行为）

		public boolean keepProxBasedScaleForAllMembers = false; // 是否对所有成员保持基于距离的缩放
	}

	/**
	 * 碎片成员类
	 * 表示群中的一个单独碎片，包含位置、速度、精灵、动画状态等。
	 */
	public static class VIII_SwarmMember {
		public SpriteAPI sprite;          // 碎片精灵

		public Vector2f offset = new Vector2f(); // 相对于附着实体的偏移（局部坐标）
		public Vector2f loc = new Vector2f();    // 世界坐标位置
		public Vector2f vel = new Vector2f();    // 速度向量（像素/秒）

		public float scale = 1f;          // 当前缩放
		public float turnRate = 1f;       // 旋转速率（度/秒）
		public float angle = 1f;          // 当前角度（度）
		public float recentlyPicked = 0f; // 最近被选中的时间（用于避免重复选中）

		public float dur;                 // 剩余持续时间（秒）
		public FaderUtil fader;           // 淡入淡出控制器
		public FaderUtil flash;           // 当前闪烁控制器
		public FaderUtil flashNext;       // 下一个闪烁控制器（用于排队）

		public FaderUtil scaler;          // 缩放控制器
		public float minScale;            // 最小缩放（用于自动缩放）
		public boolean keepScale = false; // 是否保持当前缩放（不自动变化）

		/**
		 * 构造函数：创建新碎片成员，随机初始化精灵纹理、偏移、速度、生命周期等。
		 * @param startingLoc 起始位置（世界坐标）
		 * @param params      翻涌群参数
		 * @param attachedTo  附着实体
		 */
		public VIII_SwarmMember(Vector2f startingLoc, VIII_RoilingSwarmParams params, CombatEntityAPI attachedTo) {
			// 注释掉的备选纹理代码
			//sprite = Global.getSettings().getSprite("misc", "nebula_particles");
			//fx_particles2 - swirly
			// nebula_particles2 - smooth, but 2x2
			// dust_particles - smooth

			// 加载精灵，并从4x4纹理图集中随机选择一个子图（0~3行和列）
			sprite = Global.getSettings().getSprite(params.spriteCat, params.spriteKey);
//			float i = Misc.random.nextInt(4);
//			float j = Misc.random.nextInt(4);
//			sprite.setTexWidth(0.25f);
//			sprite.setTexHeight(0.25f);
//			sprite.setTexX(i * 0.25f);
//			sprite.setTexY(j * 0.25f);

			//sprite.setAdditiveBlend();   // 注释：加法混合（未启用）
			sprite.setNormalBlend();        // 使用常规混合模式

			angle = (float) Math.random() * 360f; // 随机初始角度

			// 计算偏移（基于参数和附着实体）
			rollOffset(params, attachedTo);

			// 根据生成偏移倍率调整起始位置
			Vector2f spawnOffset = new Vector2f(offset);
			spawnOffset.scale(params.spawnOffsetMult);
			if (params.spawnOffsetMult != 0) {
				spawnOffset = Misc.rotateAroundOrigin(spawnOffset, attachedTo.getFacing());
			}
			Vector2f.add(startingLoc, spawnOffset, loc);

			// 随机初始速度（最大速度的0~25%）
			vel = Misc.getPointWithinRadius(new Vector2f(), params.maxSpeed * 0.25f);

			// 以下为调试代码（强制特定速度和位置），被注释
//            params.maxSpeed = 1200;
//            vel = new Vector2f(0, -1400f);
//            Vector2f.add(startingLoc, new Vector2f(0, 500f), loc);

			// 随机持续时间
			dur = params.baseDur + (float) Math.random() * params.durRange;
			scale = 1f;   // 初始缩放为1

			// 随机旋转速率（正或负，范围[-maxTurnRate, maxTurnRate]）
			turnRate = Math.signum((float) Math.random() - 0.5f) * params.maxTurnRate * (float) Math.random();

			// 淡入淡出控制器：初始透明度0，淡入时间0.5~1秒，淡出时间在minFadeoutTime~maxFadeoutTime之间随机
			fader = new FaderUtil(0f, 0.5f + (float) Math.random() * 0.5f,
					params.minFadeoutTime + (params.maxFadeoutTime - params.minFadeoutTime) * (float) Math.random());
			fader.fadeIn();   // 开始淡入

			// 缩放控制器：用于自动缩放动画（如果启用autoscale）
			scaler = new FaderUtil(0f, 0.5f + (float) Math.random() * 0.5f, 0.5f + (float) Math.random() * 0.5f);
			scaler.setBounce(true, true); // 启用反弹（来回缩放）
			scaler.fadeIn();
		}

		/**
		 * 重新计算偏移量（通常在成员转移到新群时调用）
		 * @param params      新群的参数
		 * @param attachedTo  新群的附着实体
		 */
		public void rollOffset(VIII_RoilingSwarmParams params, CombatEntityAPI attachedTo) {
			// 如果参数要求生成椭圆偏移且附着实体是舰船，则使用基于舰船碰撞半径的复杂偏移算法
			if (params.generateOffsetAroundAttachedEntityOval && attachedTo instanceof ShipAPI ship) {

                float angle = (float) Math.random() * 360f;
				Vector2f dir = Misc.getUnitVectorAtDegreeAngle(angle);
				Vector2f from = new Vector2f(dir);
				from.scale(ship.getCollisionRadius() + 1000f);
				Vector2f.add(from, ship.getLocation(), from);

				float min = Misc.getTargetingRadius(from, ship, false);
				float max = min + params.maxOffset;
				min += params.minOffset;

				float f = min/(Math.max(1f, max));
				f = Math.max(0.1f, f * 0.75f);

				// 采用接受-拒绝采样法生成均匀分布半径（避免中心聚集）
				float r = -1f;
				for (int i = 0; i < 10; i++) {
					float test = (float) Math.sqrt(Math.random());
					if (test >= f) {
						r = test;
						break;
					}
				}
				if (r < 0f) {
					r = f + (1f - f) * (float) Math.random();
				}

				dir.scale(max * r);
				offset = dir;
				offset = Misc.rotateAroundOrigin(offset, -attachedTo.getFacing());
				// 注释：初始偏移与普通生成偏移倍率不同的处理（未启用）
//                if (params.spawnOffsetMultForInitialSpawn != params.spawnOffsetMult) {
//                    offset = Misc.rotateAroundOrigin(offset, (float) Math.random() * 360f);
//                }
				//offset = new Vector2f(200f, 0f);
			} else {
				// 简单情况：在[minOffset, maxOffset]范围内均匀随机偏移（极坐标均匀分布）
				offset = Misc.getPointWithinRadiusUniform(new Vector2f(), params.minOffset, params.maxOffset, Misc.random);
			}

			// 如果有自定义偏移修改器，则调用它
			if (params.offsetModifier != null) {
				params.offsetModifier.modifyOffset(this);
			}
		}

		/**
		 * 每帧更新碎片的位置、角度、生命周期、闪烁等
		 * @param amount 时间增量（秒）
		 * @param params 翻涌群参数
		 */
		public void advance(float amount, VIII_RoilingSwarmParams params) {
			loc.x += vel.x * amount;
			loc.y += vel.y * amount;

			angle += turnRate * amount;

			dur -= amount;
			if (dur <= 0) fader.fadeOut();   // 寿命结束，开始淡出

			recentlyPicked -= amount;
			if (recentlyPicked < 0) recentlyPicked = 0f;

			fader.advance(amount);
			if (flash != null) {
				flash.advance(amount * params.flashRateMult);
				if (flash.isFadedOut()) {
					flash = null;   // 闪烁动画结束，清除
				}
			}
			if (flash == null && flashNext != null) {
				flash = flashNext;   // 切换到下一个排队闪烁
				flashNext = null;
			}

			// 如果启用自动缩放且不是保持缩放状态，则更新缩放控制器
			if (params.autoscale && !keepScale) {
				scaler.advance(amount * 0.5f);
				scale = minScale + (1f - minScale) * scaler.getBrightness() * scaler.getBrightness();
			}
		}

		/** 立即触发闪烁效果（淡入淡出一次） */
		public void flash() {
			if (flash == null) {
				flash = new FaderUtil(0f, 0.25f, 1f);
				flash.setBounceDown(true); // 仅淡入后淡出，不反弹
				flash.fadeIn();
			}
		}

		/** 排入下一个闪烁（在当前闪烁结束后自动开始） */
		public void flashNext() {
			flashNext = new FaderUtil(0f, 0.25f, 1f);
			flashNext.setBounceDown(true);
			flashNext.fadeIn();
		}

		/** 设置最近被选中的时间（用于避免短时间内重复选中） */
		public void setRecentlyPicked(float pickDuration) {
			recentlyPicked = Math.max(recentlyPicked, pickDuration);
		}
	}

	// ==================== 静态方法：管理全局的碎片群映射 ====================

	/**
	 * 获取附着实体关联的翻涌群特效
	 * @param entity 实体（舰船、导弹等）
	 * @return 翻涌群实例，如果不存在则返回null
	 */
	public static VIII_RoilingSwarmEffect getSwarmFor(CombatEntityAPI entity) {
		if (entity == null) return null;
		return getShipMap().get(entity);
	}

	// 自定义数据键：舰船到翻涌群的映射
	public static String KEY_SHIP_MAP = "VIII_RoilingSwarmEffect_shipMap_key";
	// 自定义数据键：集群类到翻涌群列表的映射
	public static String KEY_FLOCKING_MAP = "VIII_RoilingSwarmEffect_flockingMap_key";
	// 自定义数据键：交换类到翻涌群列表的映射
	public static String KEY_EXCHANGE_MAP = "VIII_RoilingSwarmEffect_exchangeMap_key";

	/**
	 * 获取舰船到翻涌群的映射（存储在战斗引擎的自定义数据中）
	 */
	@SuppressWarnings("unchecked")
	public static LinkedHashMap<CombatEntityAPI, VIII_RoilingSwarmEffect> getShipMap() {
		LinkedHashMap<CombatEntityAPI, VIII_RoilingSwarmEffect> map =
				(LinkedHashMap<CombatEntityAPI, VIII_RoilingSwarmEffect>) Global.getCombatEngine().getCustomData().get(KEY_SHIP_MAP);
		if (map == null) {
			map = new LinkedHashMap<>();
			Global.getCombatEngine().getCustomData().put(KEY_SHIP_MAP, map);
		}
		return map;
	}

	/**
	 * 获取集群类到翻涌群列表的映射
	 */
	public static ListMap<VIII_RoilingSwarmEffect> getFlockingMap() {
		return getStringToSwarmMap(KEY_FLOCKING_MAP);
	}

	/**
	 * 获取交换类到翻涌群列表的映射
	 */
	public static ListMap<VIII_RoilingSwarmEffect> getExchangeMap() {
		return getStringToSwarmMap(KEY_EXCHANGE_MAP);
	}

	/**
	 * 通用方法：获取字符串键到翻涌群列表的映射（ListMap）
	 * @param key 存储键
	 */
	@SuppressWarnings("unchecked")
	public static ListMap<VIII_RoilingSwarmEffect> getStringToSwarmMap(String key) {
		ListMap<VIII_RoilingSwarmEffect> map =
				(ListMap<VIII_RoilingSwarmEffect>) Global.getCombatEngine().getCustomData().get(key);
		if (map == null) {
			map = new ListMap<>();
			Global.getCombatEngine().getCustomData().put(key, map);
		}
		return map;
	}

	// ==================== 实例字段 ====================

	public VIII_RoilingSwarmParams params;           // 参数配置
	protected List<VIII_SwarmMember> members = new ArrayList<VIII_SwarmMember>(); // 成员列表

	protected CombatEntityAPI attachedTo;          // 附着实体（舰船/导弹等）
	protected float elapsed = 0f;                  // 累计经过时间（秒）
	protected IntervalUtil flashChecker;           // 闪烁触发计时器
	protected IntervalUtil respawnChecker;         // 重生触发计时器
	protected IntervalUtil transferChecker;        // 成员交换触发计时器
	protected boolean spawnedInitial = false;      // 是否已生成初始成员
	protected boolean despawning = false;          // 是否正在消散（已触发消散音效）
	protected boolean forceDespawn = false;        // 强制消散标志
	protected float sinceExchange = 0f;            // 距离上次交换的时间（秒）
	protected float maxDistFromCenterToFragment = 0f; // 最远碎片到中心的距离（用于渲染范围）

	public Object custom1;   // 自定义数据槽1（供外部使用）
	public Object custom2;   // 自定义数据槽2
	public Object custom3;   // 自定义数据槽3

	/**
	 * 时流倍率：整体缩放每帧更新量（飞行、摩擦、重生、闪烁、交换等）。
	 * 默认 1（正常时间流速）；由外部（如「分流发生器」子系统）临时提高，
	 * 用来表现碎片集群获得时流加速。非 ShipAPI 的特效没有 stats.timeMult，
	 * 所以只能在这里手动缩放。
	 */
	public float timeMult = 1f;

	// ==================== 构造函数 ====================

	/**
	 * 使用默认参数创建翻涌群特效
	 * @param attachedTo 附着实体
	 */
	public VIII_RoilingSwarmEffect(CombatEntityAPI attachedTo) {
		this(attachedTo, new VIII_RoilingSwarmParams());
	}

	/**
	 * 使用自定义参数创建翻涌群特效
	 * @param attachedTo 附着实体
	 * @param params     参数对象
	 */
	public VIII_RoilingSwarmEffect(CombatEntityAPI attachedTo, VIII_RoilingSwarmParams params) {
		// 输出调试信息（生产环境可注释）
		//System.out.println("Creating swarm for " + attachedTo);

		// 将本特效添加到战斗引擎的渲染层插件中，并获取对应的实体（用于定位）
		CombatEntityAPI e = Global.getCombatEngine().addLayeredRenderingPlugin(this);
		e.getLocation().set(attachedTo.getLocation());

		this.attachedTo = attachedTo;
		this.params = params;



		// 初始化计时器
		flashChecker = new IntervalUtil(0.5f, 1.5f);
		respawnChecker = new IntervalUtil(0.5f, 1.5f);
		transferChecker = new IntervalUtil(0.2f, 1.8f);

		// 注册到全局映射
		getShipMap().put(attachedTo, this);
		if (params.flockingClass != null) {
			getFlockingMap().add(params.flockingClass, this);
		}
		if (params.memberExchangeClass != null) {
			getExchangeMap().add(params.memberExchangeClass, this);
		}
	}

	/**
	 * 初始化（覆盖父类方法，设置实体位置）
	 */
	public void init(CombatEntityAPI entity) {
		super.init(entity);
	}

	/**
	 * 获取渲染半径（用于视口剔除）
	 * @return 半径（像素）
	 */
	public float getRenderRadius() {
		float extra = 0f;
		if (sinceExchange < 3f) {
			extra = 500f - sinceExchange * 100f;  // 交换后短暂增大渲染范围
		}
		extra = Math.max(extra, maxDistFromCenterToFragment);
		return params.visibleRange + extra;
	}

	// 指定该特效渲染的图层：战机层和粒子层下方
	protected EnumSet<CombatEngineLayers> layers = EnumSet.of(CombatEngineLayers.FIGHTERS_LAYER,
			CombatEngineLayers.ABOVE_PARTICLES_LOWER);
	@Override
	public EnumSet<CombatEngineLayers> getActiveLayers() {
		return layers;
	}

	// ==================== 成员管理方法 ====================

	/** 创建一个新成员并添加到群中 */
	public VIII_SwarmMember addMember() {
		VIII_SwarmMember sm = new VIII_SwarmMember(attachedTo.getLocation(), params, attachedTo);
		addMember(sm);
		return sm;
	}

	/** 添加已有成员到群 */
	public void addMember(VIII_SwarmMember sm) {
		members.add(sm);
	}

	/** 移除成员 */
	public void removeMember(VIII_SwarmMember sm) {
		members.remove(sm);
	}

	/** 添加指定数量的新成员 */
	public void addMembers(int num) {
		for (int i = 0; i < num; i++) {
			addMember();
		}
	}

	/** 转移一定比例的成员到另一个群 */
	public void transferMembersTo(VIII_RoilingSwarmEffect other, float fraction) {
		int num = (int) (members.size() * fraction);
		transferMembersTo(other, num);
	}

	/** 转移指定数量的成员到另一个群（不限制位置） */
	public void transferMembersTo(VIII_RoilingSwarmEffect other, int num) {
		transferMembersTo(other, num, null, 0f);
	}

	/**
	 * 转移指定数量的成员到另一个群，并可限制位置范围
	 * @param other 目标群
	 * @param num 转移数量
	 * @param point 限制中心点（可选）
	 * @param maxRangeFromPoint 限制范围（像素）
	 */
	public void transferMembersTo(VIII_RoilingSwarmEffect other, int num, Vector2f point, float maxRangeFromPoint) {
		if (num <= 0) return;
		WeightedRandomPicker<VIII_SwarmMember> picker = getPicker(true, true);
		if (point != null) {
			picker = getPicker(true, true, point, maxRangeFromPoint);
		}
		for (int i = 0; i < num; i++) {
			VIII_SwarmMember pick = picker.pickAndRemove();
			if (pick == null) break;

			removeMember(pick);
			other.addMember(pick);
			pick.rollOffset(other.params, other.attachedTo); // 重新计算偏移以适应新群
		}
	}

	/** 消散指定数量的成员（强制淡出） */
	public void despawnMembers(int num) {
		despawnMembers(num, true);
	}

	/**
	 * 消散指定数量的成员，可选择是否允许消散第一个成员（主要碎片）
	 * @param num 数量
	 * @param allowFirst 是否允许消散第一个成员（索引0）
	 */
	public void despawnMembers(int num, boolean allowFirst) {
		WeightedRandomPicker<VIII_SwarmMember> picker = getPicker(false, false);
		if (!allowFirst && !members.isEmpty()) {
			picker.remove(members.get(0)); // 排除第一个成员
		}
		for (int i = 0; i < num; i++) {
			VIII_SwarmMember pick = picker.pickAndRemove();
			if (pick == null) break;
			pick.fader.fadeOut();
		}
	}

	/**
	 * 随机选取一个成员，并标记其被选中的持续时间（避免短时间内重复选中）
	 * @param pickDuration 标记持续时间（秒）
	 * @return 选中的成员
	 */
	public VIII_SwarmMember pick(float pickDuration) {
		VIII_SwarmMember pick = getPicker(true, true).pick();
		if (pick != null) {
			pick.setRecentlyPicked(pickDuration);
		}
		return pick;
	}

	/**
	 * 获取加权随机选择器，优先选择不闪烁、未被选中的成员，且可倾向于朝向某个点的方向
	 * @param preferNonFlashing 是否优先选择不闪烁的成员
	 * @param preferNonPicked   是否优先选择最近未被选中的成员
	 * @param towards           朝向点（用于方向加权）
	 * @return 选择器
	 */
	public WeightedRandomPicker<VIII_SwarmMember> getPicker(boolean preferNonFlashing, boolean preferNonPicked,
															Vector2f towards) {
		WeightedRandomPicker<VIII_SwarmMember> picker = new WeightedRandomPicker<>();
		float angle = Misc.getAngleInDegrees(attachedTo.getLocation(), towards);
		for (VIII_SwarmMember p : members) {
			if (p.fader.isFadingOut() || p.fader.isFadedOut()) continue; // 忽略正在淡出或已消失的
			float w = 1000f;
			if (preferNonFlashing && p.flash != null) w *= 0.001f;   // 闪烁的成员权重极低
			if (preferNonPicked && p.recentlyPicked > 0) w *= 0.001f;

			float curr = Misc.getAngleInDegrees(attachedTo.getLocation(), p.loc);
			float diff = Misc.getAngleDiff(angle, curr);
			if (diff > 90f) {
				float f = Misc.normalizeAngle(diff - 90f) / 90f;
				if (f > 0.9999f) f = 0.9999f;
				w *= 1f - f;
				w *= 0.05f;
			}

			picker.add(p, w);
		}
		return picker;
	}

	/**
	 * 获取加权随机选择器，额外限制成员距离指定点的范围
	 * @param preferNonFlashing 是否优先不闪烁
	 * @param preferNonPicked   是否优先未被选中
	 * @param point             中心点
	 * @param preferMaxRangeFromPoint 最大距离（超出该距离的成员权重降低）
	 * @return 选择器
	 */
	public WeightedRandomPicker<VIII_SwarmMember> getPicker(boolean preferNonFlashing, boolean preferNonPicked,
															Vector2f point, float preferMaxRangeFromPoint) {
		WeightedRandomPicker<VIII_SwarmMember> picker = new WeightedRandomPicker<>();
		for (VIII_SwarmMember p : members) {
			if (p.fader.isFadingOut() || p.fader.isFadedOut()) continue;
			float w = 1000f;
			if (preferNonFlashing && p.flash != null) w *= 0.001f;
			if (preferNonPicked && p.recentlyPicked > 0) w *= 0.001f;

			float dist = Misc.getDistance(point, p.loc);
			if (dist > preferMaxRangeFromPoint) {
				float f = (dist - preferMaxRangeFromPoint) / Math.max(1f, preferMaxRangeFromPoint);
				if (f > 0.9999f) f = 0.9999f;
				w *= 1f - f;
			} else {
				w *= 0.25f + 0.75f * (1f - dist / Math.max(1f, preferMaxRangeFromPoint));
			}

			picker.add(p, w);
		}
		return picker;
	}

	/** 获取基本加权随机选择器（不限制方向或位置） */
	public WeightedRandomPicker<VIII_SwarmMember> getPicker(boolean preferNonFlashing, boolean preferNonPicked) {
		WeightedRandomPicker<VIII_SwarmMember> picker = new WeightedRandomPicker<>();
		for (VIII_SwarmMember p : members) {
			if (p.fader.isFadingOut() || p.fader.isFadedOut()) continue;
			float w = 1000f;
			if (preferNonFlashing && p.flash != null) w *= 0.001f;
			if (preferNonPicked && p.recentlyPicked > 0) w *= 0.001f;
			picker.add(p, w);
		}
		return picker;
	}

	/** 获取当前活跃（未淡出）的成员数量 */
	public int getNumActiveMembers() {
		return getPicker(false, false).getItems().size();
	}

	/** 获取指定成员的发光强度（基于闪烁动画亮度） */
	public float getGlowForMember(VIII_SwarmMember p) {
		float glow = 0f;
		if (p.flash != null) {
			glow = p.flash.getBrightness();
			glow *= glow;
		}
		return glow;
	}

	/** 获取需要维持的成员数量（子类可覆盖） */
	public int getNumMembersToMaintain() {
		return params.baseMembersToMaintain;
	}

	// ==================== 核心每帧更新逻辑 ====================

	/**
	 * 每帧更新（覆盖父类方法）
	 * @param amount 时间增量（秒）
	 */
	public void advance(float amount) {
		// 调试开关（如果为true则跳过所有更新）
		//if (true) return;

		// 战斗暂停、实体无效或已过期则跳过
		if (Global.getCombatEngine().isPaused() || entity == null || isExpired()) return;

		// 时流倍率（默认 1）：整体缩放本帧更新量，供分流发生器等外部提速使用
		if (timeMult != 1f) {
			amount *= timeMult;
		}

		// 初始生成成员（仅执行一次）
		if (!spawnedInitial && params.withInitialMembers) {
			float origSpawnOffsetMult = params.spawnOffsetMult;
			if (params.spawnOffsetMultForInitialSpawn >= 0) {
				params.spawnOffsetMult = params.spawnOffsetMultForInitialSpawn;
			}
			addMembers(params.initialMembers - getNumActiveMembers());
			params.spawnOffsetMult = origSpawnOffsetMult;
			spawnedInitial = true;
		}

		// 以下为被注释的调试代码（强制设置碰撞类和伤害倍率）
//        attachedTo.setCollisionClass(CollisionClass.SHIP);
//        ((ShipAPI)attachedTo).getMutableStats().getHullDamageTakenMult().modifyMult("efwefwefwe", 0f);

		// 将渲染插件实体的位置同步到附着实体
		entity.getLocation().set(attachedTo.getLocation());

		elapsed += amount;

		// 计算提前量（速度向量乘以系数）
		Vector2f aVel = attachedTo.getVelocity();
		float aSpeed = aVel.length();
		float leadAmount = aSpeed * params.swarmLeadsByFractionOfVelocity;

		// 获取朝向单位向量：优先使用速度方向，若速度很小则使用朝向角度
		Vector2f facingDir = Misc.getUnitVectorAtDegreeAngle(attachedTo.getFacing());
		if (attachedTo.getVelocity().length() > 1f) {
			facingDir = Misc.normalise(new Vector2f(attachedTo.getVelocity()));
		}

		Vector2f aLoc = new Vector2f(attachedTo.getLocation());
		// 注释：如果生成椭圆偏移且是舰船，可以使用护盾中心（未启用）
//        if (params.generateOffsetAroundAttachedEntityOval && attachedTo instanceof ShipAPI) {
//            ShipAPI ship = (ShipAPI) attachedTo;
//            aLoc = new Vector2f(ship.getShieldCenterEvenIfNoShield());
//        }

		List<VIII_SwarmMember> remove = new ArrayList<>(); // 待移除的成员列表

		// 计算最大速度（如果要求超出附着实体速度，则动态调整）
		float maxSpeed = params.maxSpeed;
		if (params.outspeedAttachedEntityBy != 0) {
			float minMaxSpeed = attachedTo.getVelocity().length() + params.outspeedAttachedEntityBy;
			if (minMaxSpeed > maxSpeed) maxSpeed = minMaxSpeed;
		}

		// 判断是否应该全部消散（附着实体已死亡或消失）
		boolean despawnAll = shouldDespawnAll();

		// 最大偏移（用于距离比例计算）
		float maxOffsetForProx = params.maxOffset;
		if (params.generateOffsetAroundAttachedEntityOval) {
			maxOffsetForProx += attachedTo.getCollisionRadius() * 0.75f;
		}

		// 调试代码：统计正在闪烁的成员数（已注释）
//        int flashing = 0;
//        for (VIII_SwarmMember p : members) {
//            if (p.flash != null) flashing++;
//        }
//        System.out.println("Flashing: " + flashing + " / " + members.size());

		float maxDistSq = 0f;
		maxDistFromCenterToFragment = 0f;
		// 遍历所有成员，更新位置、速度、弹簧力、摩擦等
		for (VIII_SwarmMember p : members) {
			float distSq = (aLoc.x - p.loc.x) * (aLoc.x - p.loc.x) + (aLoc.y - p.loc.y) * (aLoc.y - p.loc.y);
			maxDistSq = Math.max(maxDistSq, distSq);
			if (params.despawnDist > 0 && params.despawnDist * params.despawnDist < distSq) {
				p.fader.fadeOut(); // 超出消散距离，淡出
			}

			if (!despawnAll) {
				// 计算偏移向量，并旋转（如果设置了旋转速度）
				Vector2f offset = new Vector2f(p.offset);
				//offset.y *= p.offsetDrift;
				//offset.y = p.offsetDrift * params.maxOffset;
				//offset = Misc.rotateAroundOrigin(offset, attachedTo.getFacing() + elapsed * 5f);

				// 根据偏移长度计算距离比例（prox），用于弹簧常数、自由长度等
				float prox = offset.length() / maxOffsetForProx;
				prox = 1f - prox;

				offset = Misc.rotateAroundOrigin(offset, attachedTo.getFacing() + elapsed * params.offsetRotationDegreesPerSecond);
				//offset = Misc.rotateAroundOrigin(offset, attachedTo.getFacing());
				offset.x += facingDir.x * leadAmount;
				offset.y += facingDir.y * leadAmount;

				// 如果不是保持所有成员基于距离缩放，则动态调整缩放
				if (!params.keepProxBasedScaleForAllMembers) {
					p.scale = params.baseScale + (1f - prox) * params.scaleRange;
					if (p.scale > 1f) p.scale = 1f;
				}

				// 目标位置（附着实体位置 + 偏移）
				Vector2f dest = new Vector2f(aLoc);
				Vector2f.add(dest, offset, dest);
				float dist = Misc.getDistance(p.loc, dest);

				// 方向向量和垂直向量
				Vector2f dirToDest = Misc.getUnitVector(p.loc, dest);
				Vector2f perp = new Vector2f(-dirToDest.y, dirToDest.x);

				// 摩擦力随距离比例变化
				float friction = params.baseFriction + params.frictionRange * prox;

				// 弹簧常数和自由长度随距离比例变化
				float k = params.baseSpringConstant - params.springConstantNegativeRange * prox;
				float freeLength = params.baseSpringFreeLength + params.springFreeLengthRange * prox;

				// 计算拉伸量，并应用非线性修正（sqrt）
				float stretch = dist - freeLength;
				stretch = (float) (Math.sqrt(Math.abs(stretch * params.springStretchMult)) * Math.signum(stretch));

				float forceMag = k * Math.abs(stretch);
				if (stretch < 0) forceMag = 0; // 只拉不推（单向弹簧）

				// 摩擦力消耗力的大小
				float forceMagReduction = Math.min(Math.abs(forceMag), friction);
				forceMag -= forceMagReduction;
				friction -= forceMagReduction;

				// 计算弹簧力加速度
				Vector2f force = new Vector2f(dirToDest);
				force.scale(forceMag * Math.signum(stretch));

				Vector2f acc = new Vector2f(force);
				acc.scale(amount);
				Vector2f.add(p.vel, acc, p.vel);

				// 剩余摩擦力：沿速度反方向减速
				if (friction > 0) {
					float relSpeed = Vector2f.sub(aVel, p.vel, new Vector2f()).length();
					if (relSpeed > params.minSpeedForFriction) {
						Vector2f frictionDec = Misc.getUnitVectorAtDegreeAngle(Misc.getAngleInDegrees(p.vel));
						frictionDec.negate();
						frictionDec.scale(Math.min(friction, p.vel.length()) * amount);
						Vector2f.add(p.vel, frictionDec, p.vel);
					}
				}

				// 横向摩擦：抑制轨道运动
				float lateralSpeed = Math.abs(Vector2f.dot(p.vel, perp));
				if (lateralSpeed > 0) {// && lateralSpeed > params.minSpeedForFriction) {
					Vector2f frictionDec = new Vector2f(perp);
					if (Vector2f.dot(frictionDec, p.vel) > 0) {
						frictionDec.negate();
					}
					float lateralFactor = params.lateralFrictionFactor;
					lateralFactor += Math.min(Math.abs(attachedTo.getAngularVelocity()), 100f) * params.lateralFrictionTurnRateFactor;
					float lateralFriction = lateralSpeed * lateralFactor;
					frictionDec.scale(Math.min(lateralFriction, p.vel.length()) * amount);
					Vector2f.add(p.vel, frictionDec, p.vel);
				}

				// 限速
				float speed = p.vel.length();
				if (speed > maxSpeed) {
					p.vel.scale(maxSpeed / speed);
				}
			}

			// 更新成员自身状态（位置、角度、生命、闪烁等）
			p.advance(amount, params);

			// 如果需要全部消散，则强制所有成员开始淡出
			if (despawnAll) {
				if (!p.fader.isFadingOut() && !p.fader.isFadedOut()) {
					p.fader.setDurationOut(params.minDespawnTime +
							(params.maxDespawnTime - params.minDespawnTime) * (float) Math.random());
					p.fader.fadeOut();
				}
			}

			// 如果成员已完全淡出，加入待移除列表
			if (p.fader.isFadedOut()) {
				remove.add(p);
			}
		}

		// 更新最大距离
		maxDistFromCenterToFragment = (float) Math.sqrt(maxDistSq);

		// 批量移除淡出的成员
		members.removeAll(remove);

		// 如果正在全部消散且尚未播放消散音效，则播放并标记
		if (despawnAll) {
			if (!despawning) {
				if (params.despawnSound != null) {
					Global.getSoundPlayer().playSound(params.despawnSound, 1f, 1f, entity.getLocation(), aVel);
					despawning = true;
				}
			}
		}

		// 如果特效已过期，则从全局映射中移除（注释：实际移除在isExpired中完成）
		if (isExpired()) {
//            getShipMap().remove(attachedTo);
//            getFlockingMap().remove(params.flockingClass, this);
//            getExchangeMap().remove(params.memberExchangeClass, this);
		} else if (!despawnAll && !despawning){
			// 与附近的碎片群交换成员
			exchangeWithNearbySwarms(amount);
		}

		// 重生和数量维持逻辑（仅在未全部消散时）
		if (!despawnAll) {
			respawnChecker.advance(amount * params.memberRespawnRate);
			if (respawnChecker.intervalElapsed() && params.withRespawn) {
				int num = getNumMembersToMaintain();
				if (members.size() < num) {
					int add = Math.min(params.numToRespawn, num - members.size());
					addMembers(add);

					// 如果配置了偏移重滚比例，则随机重滚部分成员的偏移
					if (params.offsetRerollFractionOnMemberRespawn > 0f) {
						int reroll = Math.round(params.offsetRerollFractionOnMemberRespawn * members.size());
						if (reroll < 1) reroll = 1;
						WeightedRandomPicker<VIII_SwarmMember> picker = getPicker(true, false);
						for (int i = 0; i < reroll; i++) {
							VIII_SwarmMember pick = picker.pickAndRemove();
							if (pick == null) break;
							pick.rollOffset(params, attachedTo);
						}
					}
				} else if (members.size() > num && params.removeMembersAboveMaintainLevel) {
					despawnMembers(1);  // 超出维持数，消散一个成员
				} else if (params.maxNumMembersToAlwaysRemoveAbove >= 0 &&
						members.size() > params.maxNumMembersToAlwaysRemoveAbove) {
					int extra = members.size() - params.maxNumMembersToAlwaysRemoveAbove;
					int numRemove = (int) Math.min(extra * 0.1f, 5f);
					if (numRemove < 1) numRemove = 1;
					despawnMembers(numRemove);
				}
			}

			// 闪烁逻辑
			flashChecker.advance(amount * params.flashFrequency);
			params.preFlashDelay -= amount;
			if (params.preFlashDelay < 0) params.preFlashDelay = 0;
			if (flashChecker.intervalElapsed() && params.preFlashDelay <= 0) {
				if (params.flashProbability > 0) {
					WeightedRandomPicker<VIII_SwarmMember> notFlashing = new WeightedRandomPicker<>();
					for (VIII_SwarmMember p : members) {
						if (p.flash == null) {
							notFlashing.add(p);
						}
					}
					for (int i = 0; i < params.numToFlash; i++) {
						if ((float) Math.random() < params.flashProbability) {
							VIII_SwarmMember pick = notFlashing.pickAndRemove();
							if (pick != null) pick.flash();
						}
					}
				}
			}
		}

		sinceExchange += amount;
		// 以下是被注释的代码（与投射物命中后的尾迹处理有关，未使用）
//        if (proj.didDamage()) {
//            if (!resetTrailSpeed) {
//                for (VIII_ParticleData p : particles) {
//                    Vector2f.add(p.vel, projVel, p.vel);
//                }
//                projVel.scale(0f);
//                resetTrailSpeed = true;
//            }
//            for (VIII_ParticleData p : particles) {
//                float dist = p.offset.length();
//                p.vel.scale(Math.min(1f, dist / 100f));
//            }
//        }
	}

	/**
	 * 与附近的其他翻涌群交换成员（如果配置了交换类）
	 * @param amount 时间增量（秒）
	 */
	public void exchangeWithNearbySwarms(float amount) {
		if (params.memberExchangeClass == null || params.memberExchangeRange <= 0) return;

		transferChecker.advance(amount * params.memberExchangeRate);
		if (!transferChecker.intervalElapsed()) return;

		WeightedRandomPicker<VIII_RoilingSwarmEffect> swarmPicker = new WeightedRandomPicker<>();

		// 收集同一交换类且距离在范围内的其他碎片群
		for (VIII_RoilingSwarmEffect other : getExchangeMap().getList(params.memberExchangeClass)) {
			if (other == this || other.getEntity() == null || other.despawning) continue;
			if (other.attachedTo == null || attachedTo == null) continue;
			if (other.attachedTo.getOwner() != attachedTo.getOwner()) continue; // 不同阵营不交换
			if (other.params.memberExchangeClass == null ||
					!other.params.memberExchangeClass.equals(params.memberExchangeClass)) {
				continue;
			}
			float dist = Misc.getDistance(entity.getLocation(), other.getEntity().getLocation());
			if (dist > params.memberExchangeRange) continue;

			swarmPicker.add(other);
		}

		VIII_RoilingSwarmEffect other = swarmPicker.pick();
		if (other == null) return;

		// 随机确定交换数量（在最小和最大之间）
		int num = params.minMembersToExchange +
				Misc.random.nextInt(params.maxMembersToExchange - params.minMembersToExchange + 1);

		WeightedRandomPicker<VIII_SwarmMember> picker = getPicker(true, true);
		WeightedRandomPicker<VIII_SwarmMember> pickerOther = other.getPicker(true, true);

		num = Math.min(num, picker.getItems().size());
		num = Math.min(num, pickerOther.getItems().size());

		// 执行交换
		for (int i = 0; i < num; i++) {
			VIII_SwarmMember pick = picker.pickAndRemove();
			VIII_SwarmMember otherPick = pickerOther.pickAndRemove();
			if (pick == null || otherPick == null) break;

			removeMember(pick);
			other.addMember(pick);
			pick.rollOffset(other.params, other.attachedTo);

			other.removeMember(otherPick);
			addMember(otherPick);
			otherPick.rollOffset(params, attachedTo);

			sinceExchange = 0f;
		}
	}

	/**
	 * 判断是否应该全部消散（附着实体已死亡或不存在）
	 * @return true表示应消散
	 */
	public boolean shouldDespawnAll() {
		if (forceDespawn) return true;

		// 被注释的随机强制消散代码（未启用）
//        if ((float) Math.random() > 0.9995f && !params.generateOffsetAroundAttachedEntityOval) {
//            forceDespawn = true;
//            return true;
//        }

		if (attachedTo instanceof ShipAPI ship) {
            return !Global.getCombatEngine().isShipAlive(ship);
		}
		if (attachedTo instanceof MissileAPI missile) {
            return !Global.getCombatEngine().isMissileAlive(missile);
		}

		return attachedTo.isExpired() || !Global.getCombatEngine().isEntityInPlay(attachedTo);
	}

	/**
	 * 判断特效是否已过期（全部消散且成员为空）
	 * @return true表示已过期
	 */
	public boolean isExpired() {
		boolean expired = shouldDespawnAll() && members.isEmpty();
		if (expired) {
			// 从全局映射中移除自己
			getShipMap().remove(attachedTo);
			getFlockingMap().remove(params.flockingClass, this);
			getExchangeMap().remove(params.memberExchangeClass, this);
		}
		return expired;
	}

	// ==================== 渲染方法 ====================

	/**
	 * 渲染特效（覆盖父类方法）。
	 * 根据当前渲染图层绘制个体成员的基础精灵与闪光光晕。
	 *
	 * @param layer    当前渲染的图层（如 FIGHTERS_LAYER, ABOVE_PARTICLES_LOWER 等）
	 * @param viewport 视口信息，包含透明度倍率等
	 */
	public void render(CombatEngineLayers layer, ViewportAPI viewport) {
		// 调试开关（若为 true 则直接跳过所有渲染，便于快速排查问题）
		//if (true) return;

		// 从参数中获取当前特效的基础颜色与视口透明度倍率
		Color color = params.color;
		float alphaMult = viewport.getAlphaMult();
		if (alphaMult <= 0f) return; // 视口完全透明，无需渲染

		alphaMult *= params.alphaMult; // 叠加特效自身的全局透明度

		// =========================
		// 图层1：基础碎片精灵
		// 在 FIGHTERS_LAYER 渲染每个成员的示意图标
		// =========================
		if (layer == CombatEngineLayers.FIGHTERS_LAYER) {
			// （被注释的代码块：当画面缩放倍率极高时，改用点精灵渲染以提升性能与视觉效果）
//            float zoom = viewport.getViewMult();
//            if (zoom >= 3f) {
//                GL11.glDisable(GL11.GL_TEXTURE_2D);
//                if (!members.isEmpty()) {
//                    Color c = members.get(0).sprite.getAverageBrightColor();
//                    c = Misc.interpolateColor(c, members.get(0).sprite.getAverageColor(), 0.9f);
//                    GL11.glEnable(GL11.GL_POINT_SMOOTH);
//                    GL11.glPointSize(params.baseSpriteSize / zoom * 0.5f);
//                    GL11.glBegin(GL11.GL_POINTS);
//                    for (VIII_SwarmMember p : members) {
//                        float b = p.fader.getBrightness();
//                        Misc.setColor(c, alphaMult * b);
//                        GL11.glVertex2f(p.loc.x, p.loc.y);
//                    }
//                    GL11.glEnd();
//                }
//            } else {

			// 正常渲染路径：绑定第一个成员的纹理（假设所有成员使用相同纹理）
			if (!members.isEmpty()) {
				members.get(0).sprite.bindTexture();
			}

			// 遍历所有个体成员，绘制基准精灵
			for (VIII_SwarmMember p : members) {
				// 计算当前成员显示大小：基础尺寸 × 个体缩放 × 当前亮度系数
				float size = params.baseSpriteSize;
				size *= p.scale * p.fader.getBrightness();

				float b = p.fader.getBrightness(); // 当前亮度（渐入渐出效果）

				// 设置精灵的角度与大小，并应用全局颜色与透明度
				p.sprite.setAngle(p.angle);
				p.sprite.setSize(size, size);
				p.sprite.setAlphaMult(alphaMult * b * params.alphaMultBase);
				p.sprite.setColor(color);
				p.sprite.renderAtCenterNoBind(p.loc.x, p.loc.y);

				// 若允许闪烁光晕，且未启用独立的核心光晕（flashCoreRadiusMult <= 0），
				// 则在基准精灵所在位置以加法混合再绘制一层发光核心
				float glow = getGlowForMember(p); // 获取当前成员的闪烁强度（0~1）
				if (glow > 0 && params.flashCoreRadiusMult <= 0f) {
					p.sprite.setAlphaMult(alphaMult * b * glow * params.alphaMultFlash);
					p.sprite.setColor(params.flashCoreColor);      // 使用闪光核心颜色
					p.sprite.setAdditiveBlend();                   // 切换为加法混合模式
					p.sprite.renderAtCenter(p.loc.x, p.loc.y);
					p.sprite.setNormalBlend();                     // 恢复普通混合
				}
			}
//            } // 对应被注释的 else 分支结束
		}

		// =========================
		// 图层2：闪光光晕
		// 根据配置可能在 FIGHTERS_LAYER 或 ABOVE_PARTICLES_LOWER 渲染
		// 此处会额外绘制扩散光晕与核心光晕
		// =========================
		if ((layer == CombatEngineLayers.ABOVE_PARTICLES_LOWER && !params.renderFlashOnSameLayer) ||
				(layer == CombatEngineLayers.FIGHTERS_LAYER && params.renderFlashOnSameLayer)) {

			// 加载用于光晕的圆形纹理（“threat_swarm_glow”）
			SpriteAPI glowSprite = Global.getSettings().getSprite("misc", "VIII_threat_swarm_glow");
			glowSprite.setAdditiveBlend(); // 全层采用加法混合，产生发光叠加效果

			for (VIII_SwarmMember p : members) {
				float glow = getGlowForMember(p); // 闪烁强度
				if (glow > 0f) {
					// ---- 扩散光晕（边缘色） ----
					float size = params.flashRadius * (0.5f + 0.5f * glow) * 2f;
					size *= p.scale * p.fader.getBrightness(); // 跟随成员缩放与亮度

					// （被注释的代码：根据成员到中心的距离动态调整光晕颜色，未启用）
//                    float f = p.offset.length() / 150f;
//                    if (f > 1f) f = 1f;
//                    f = 1 - f * 0.5f;
//                    Color color2 = Misc.interpolateColor(params.flashFringeColor, Misc.setBrightness(
//                                    new Color(7, 163, 169), 255), f);

					float b = p.fader.getBrightness();
					if (b > 0 && size > 0) {
						glowSprite.setSize(size, size);
						glowSprite.setAlphaMult(alphaMult * b * glow * 0.5f * params.alphaMultFlash);
						glowSprite.setColor(params.flashFringeColor); // 边缘光晕颜色
						glowSprite.renderAtCenter(p.loc.x, p.loc.y);
					}

					// ---- 核心光晕（核心色） ----
					// 大小为基准精灵尺寸 × 缩放 × 2 × 核心半径倍率
					float memberSize = params.baseSpriteSize;
					memberSize *= p.scale;
					memberSize *= 2f;
					memberSize *= params.flashCoreRadiusMult;
					if (b > 0 && memberSize > 0) {
						glowSprite.setSize(memberSize, memberSize);
						glowSprite.setAlphaMult(alphaMult * b * p.fader.getBrightness() * glow * 0.5f * params.alphaMultFlash);
						glowSprite.setColor(params.flashCoreColor); // 核心光晕颜色
						glowSprite.renderAtCenter(p.loc.x, p.loc.y);
					}
				}
			}
		}
	}

	// ==================== Getter/Setter ====================

	public VIII_RoilingSwarmParams getParams() {
		return params;
	}

	public List<VIII_SwarmMember> getMembers() {
		return members;
	}

	public CombatEntityAPI getAttachedTo() {
		return attachedTo;
	}

	public boolean isDespawning() {
		return despawning;
	}

	public boolean isForceDespawn() {
		return forceDespawn;
	}

	public void setForceDespawn(boolean forceDespawn) {
		this.forceDespawn = forceDespawn;
	}
}