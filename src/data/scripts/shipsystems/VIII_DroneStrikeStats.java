package data.scripts.shipsystems;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.EmpArcEntityAPI;
import com.fs.starfarer.api.combat.FighterLaunchBayAPI;
import com.fs.starfarer.api.combat.FighterWingAPI;
import com.fs.starfarer.api.combat.GuidedMissileAI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipSystemAPI.SystemState;
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.impl.combat.DroneStrikeStatsAIInfoProvider;
import com.fs.starfarer.api.plugins.ShipSystemStatsScript;
import com.fs.starfarer.api.util.Misc;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.lwjgl.util.vector.Vector2f;

/**
 * 无人机打击舰船系统脚本。
 * 该系统激活时，将当前舰船搭载的无人机（来自所有航母甲板）转化为导弹，
 * 向目标发射，并伴随华丽的电弧视觉效果。
 * 具体行为：
 * 1. 激活时，获取所有甲板上的无人机。
 * 2. 根据目标选择策略确定目标（优先使用舰船语音指定的目标）。
 * 3. 将每架无人机转换为对应导弹实体（使用指定武器ID），
 *    并设置目标、电磁脉冲抗性、最大射程/飞行时间等属性。
 * 4. 原无人机从编队中移除，并附加一个渲染脚本 VIII_DroneMissileScript，
 *    将无人机同步到导弹位置，使其看起来像无人机本身变成了导弹。
 * 5. 当导弹命中目标或失效时，脚本会处理无人机的销毁和爆炸特效。
 */
public class VIII_DroneStrikeStats extends BaseShipSystemScript implements DroneStrikeStatsAIInfoProvider {

    // 用于发射导弹的模拟武器（由全局引擎创建）
    protected WeaponAPI weapon;

    // 是否已经发射过（防止重复触发）
    protected boolean fired = false;

    // 强制指定的下一个目标（可通过外界设置）
    protected ShipAPI forceNextTarget = null;

    // 自定义数据键：标识暴雪群所属舰船
    public static final String BLIZZARD_OWNER_KEY = "VIII_blizzard_owner";
    // 自定义数据键：标识该战机是暴雪群成员
    public static final String BLIZZARD_TAG_KEY = "VIII_blizzard_swarm";
    // 自定义数据键：标识该战机已经被转换为导弹（不再可用）
    private static final String CONVERTED_KEY = "VIII_drone_converted";

    public VIII_DroneStrikeStats() {
    }

    /**
     * 配置使用的导弹武器ID。
     * 子类可以覆盖此方法以使用不同导弹。
     * @return 武器ID字符串
     */
    protected String getWeaponId() {
        return "terminator_missile";
    }

    /**
     * 配置要发射的无人机数量。
     * 默认只有1架。
     * @return 发射数量
     */
    protected int getNumToFire() {
        return 1;
    }

    /**
     * 系统每帧更新时调用。
     *
     * @param stats       舰船属性
     * @param id          系统实例标识
     * @param state       系统状态（IN/ACTIVE/OUT/COOLDOWN/IDLE）
     * @param effectLevel 效果等级（0~1）
     */
    public void apply(MutableShipStatsAPI stats, String id, ShipSystemStatsScript.State state, float effectLevel) {
        ShipAPI ship;
        // 确保实体是舰船
        if (stats.getEntity() instanceof ShipAPI) {
            ship = (ShipAPI) stats.getEntity();

            // 若模拟武器尚未创建，则创建一个虚拟武器（仅用于提供弹道参数）
            if (this.weapon == null) {
                this.weapon = Global.getCombatEngine().createFakeWeapon(ship, this.getWeaponId());
            }

            // 获取当前所有无人机，设置爆炸效果参数（视觉缩放、速度覆盖、颜色覆盖）
            for (ShipAPI drone : this.getDrones(ship)) {
                drone.setExplosionScale(0.67F);
                drone.setExplosionVelocityOverride(new Vector2f());
                drone.setExplosionFlashColorOverride(new Color(109, 166, 255, 255));
            }

            // 当效果等级大于0且尚未发射时，触发将无人机转换为导弹
            if (effectLevel > 0.0F && !this.fired) {
                if (!this.getDrones(ship).isEmpty()) {
                    ShipAPI target = this.findTarget(ship);
                    this.convertDrones(ship, target);
                }
            } else if (state == State.IDLE) {
                // 系统空闲时重置发射标记，允许下次再发射
                this.fired = false;
            }
        }
    }

    /**
     * 将无人机转换为导弹并发射。
     *
     * @param ship   所有者舰船
     * @param target 目标舰船（可能为null，表示无目标）
     */
    public void convertDrones(ShipAPI ship, final ShipAPI target) {
        CombatEngineAPI engine = Global.getCombatEngine();
        this.fired = true; // 标记已发射
        this.forceNextTarget = null; // 清除强制目标设置

        int num = 0; // 已发射数量计数器
        List<ShipAPI> drones = this.getDrones(ship); // 获取所有无人机

        // 若存在目标，则按无人机与目标的距离排序，优先发射较近的无人机
        if (target != null) {
            drones.sort((o1, o2) -> {
                float d1 = Misc.getDistance(o1.getLocation(), target.getLocation());
                float d2 = Misc.getDistance(o2.getLocation(), target.getLocation());
                return (int) Math.signum(d1 - d2);
            });
        } else {
            // 无目标时随机洗牌（随机顺序发射）
            Collections.shuffle(drones);
        }

        // 遍历无人机
        for (ShipAPI drone : drones) {
            // 只转换前 getNumToFire() 架无人机
            if (num < this.getNumToFire()) {
                // 在无人机位置生成导弹，朝向与速度与无人机当前一致
                MissileAPI missile = (MissileAPI) engine.spawnProjectile(
                        ship,
                        this.weapon,
                        this.getWeaponId(),
                        new Vector2f(drone.getLocation()),
                        drone.getFacing(),
                        new Vector2f(drone.getVelocity())
                );

                // 如果导弹AI实现了GuidedMissileAI，则设置追踪目标
                if (target != null && missile.getAI() instanceof GuidedMissileAI ai) {
                    ai.setTarget(target);
                }

                // 高电磁脉冲抗性，防止被EMP瘫痪
                missile.setEmpResistance(10000);

                // 调整导弹最大射程和飞行时间，使其与系统射程加成匹配
                float base = missile.getMaxRange();
                float max = this.getMaxRange(ship);
                missile.setMaxRange(max);
                // 按射程比例缩放飞行时间，保证导弹能到达目标
                missile.setMaxFlightTime(missile.getMaxFlightTime() * max / base);

                // 将无人机从原编队移除
                if (drone.getWing() != null) {
                    drone.getWing().removeMember(drone);
                }
                drone.setWing(null);
                // 设置爆炸闪光颜色（与之前相同）
                drone.setExplosionFlashColorOverride(new Color(109, 166, 255, 255));
                // **新增** 标记该无人机已被转换为导弹，后续 getDrones 将忽略它
                drone.setCustomData(CONVERTED_KEY, true);

                // 添加渲染脚本，使无人机视觉效果同步跟随导弹
                engine.addLayeredRenderingPlugin(new VIII_DroneMissileScript(drone, missile));

                EmpArcEntityAPI.EmpArcParams mainArcParams = new EmpArcEntityAPI.EmpArcParams();
                mainArcParams.segmentLengthMult = 8f; // 闪电分段长度倍率
                mainArcParams.zigZagReductionFactor = 0.15f; // 闪电曲折度
                mainArcParams.fadeOutDist = 50f; // 闪电淡出距离
                mainArcParams.minFadeOutMult = 10f; // 最小淡出倍率
                mainArcParams.flickerRateMult = 0.3f; // 闪电闪烁频率倍率
                // 闪电移动时长（按距离计算，确保速度固定）
                mainArcParams.movementDurOverride = Math.max(0.05f,0.1f);

                // 创建从舰船位置到导弹位置的电弧（视觉特效：无人机发射时的能量连接）
                float thickness = 26.0F;
                float coreWidthMult = 0.67F;
                EmpArcEntityAPI arc = engine.spawnEmpArcVisual(
                        ship.getLocation(), ship,
                        missile.getLocation(), missile,
                        thickness,
                        new Color(28, 0, 255, 255), // 电弧边缘颜色
                        Color.white,                    // 核心颜色
                        mainArcParams
                );
                arc.setCoreWidthOverride(thickness * coreWidthMult); // 设置核心宽度
                arc.setRenderGlowAtStart(false); // 不渲染起点光晕
                arc.setSingleFlickerMode(true); // 单段闪烁模式

            } else if (drone.getShipAI() != null) {
                // 对于未被转换的剩余无人机，取消其当前机动指令（保留作为普通无人机）
                drone.getShipAI().cancelCurrentManeuver();
            }

            ++num; // 无论是否转换，计数器都增加，用于限制发射数量
        }
    }

    /**
     * 系统移除时调用（本实现无操作，因为所有修正都在apply中直接设置而非持久化）
     */
    public void unapply(MutableShipStatsAPI stats, String id) {
    }

    /**
     * 寻找当前目标。
     * 目标选择逻辑：
     * 1. 若存在强制目标（forceNextTarget）且存活，则使用它。
     * 2. 对于AI（非玩家）舰船：
     *    - 若AI标志中指定了机动目标，且距离合理，则使用它。
     *    - 否则找到鼠标位置附近最近的驱逐舰级（FRIGATE）或更大敌方舰船。
     * 3. 对于玩家舰船：
     *    - 若已指定目标，则直接使用；
     *    - 否则找鼠标位置最近的战斗机，若附近有大型舰船则转为攻击大型船；
     *    - 若仍找不到，则找射程内最近的大型舰船。
     *
     * @param ship 所有者舰船
     * @return 目标舰船，若无则返回null
     */
    protected ShipAPI findTarget(ShipAPI ship) {
        // 如果没有无人机，直接无目标
        if (this.getDrones(ship).isEmpty()) {
            return null;
        }
        // 强制目标优先
        if (this.forceNextTarget != null && this.forceNextTarget.isAlive()) {
            return this.forceNextTarget;
        }

        float range = this.getMaxRange(ship); // 系统射程
        boolean player = ship == Global.getCombatEngine().getPlayerShip(); // 是否为玩家
        ShipAPI target = ship.getShipTarget(); // 舰船当前锁定的目标（通常通过键盘/鼠标指定）

        // 非玩家舰船的目标逻辑
        if (!player) {
            // 尝试从AI标志中获取机动目标
            Object test = ship.getAIFlags().getCustom(AIFlags.MANEUVER_TARGET);
            if (test instanceof ShipAPI) {
                target = (ShipAPI) test;
                float dist = Misc.getDistance(ship.getLocation(), target.getLocation());
                float radSum = ship.getCollisionRadius() + target.getCollisionRadius();
                // 若目标太远则忽略
                if (dist > range + radSum) {
                    target = null;
                }
            }
            // 若没有目标，则在鼠标位置附近寻找最近的驱逐舰（FRIGATE）或更大舰船，且必须在射程内
            if (target == null) {
                target = Misc.findClosestShipEnemyOf(ship, ship.getMouseTarget(), HullSize.FRIGATE, range, true);
            }
            return target;
        }
        // 玩家舰船：优先使用手动目标
        else if (target != null) {
            return target;
        } else {
            // 尝试找鼠标位置附近的战斗机（FIGHTER）
            target = Misc.findClosestShipEnemyOf(ship, ship.getMouseTarget(), HullSize.FIGHTER, Float.MAX_VALUE, true);
            // 如果找到战斗机，且附近有大型舰船，则将目标转为大型舰船（避免浪费无人攻击战斗机）
            if (target != null && target.isFighter()) {
                ShipAPI nearbyShip = Misc.findClosestShipEnemyOf(ship, target.getLocation(), HullSize.FRIGATE, 100.0F, false);
                if (nearbyShip != null) {
                    target = nearbyShip;
                }
            }
            // 仍然找不到目标，则找射程内最近的大型敌舰
            if (target == null) {
                target = Misc.findClosestShipEnemyOf(ship, ship.getLocation(), HullSize.FIGHTER, range, true);
            }
            return target;
        }
    }

    /**
     * 状态栏数据（本系统无特殊状态显示）
     */
    public ShipSystemStatsScript.StatusData getStatusData(int index, ShipSystemStatsScript.State state, float effectLevel) {
        return null;
    }

    /**
     * 获取舰船当前所有无人机（来自所有仍有编队的起飞甲板）。
     * @param ship 所有者舰船
     * @return 无人机列表
     */
    /**
     * 核心修改：现在返回属于本舰且存活的所有暴雪攻击群成员。
     */
    @Override
    public List<ShipAPI> getDrones(ShipAPI ship) {
        List<ShipAPI> result = new ArrayList<>();
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) return result;
        for (ShipAPI other : engine.getShips()) {
            // 只关心战斗机
            if (!other.isFighter()) continue;
            // 必须是存活且非残骸
            if (!other.isAlive() || other.isHulk()) continue;
            // 检查自定义数据：是否属于当前舰船且是暴雪群
            Object owner = other.getCustomData().get(BLIZZARD_OWNER_KEY);
            if (owner != ship) continue;  // 必须是同一个母舰实例
            // **新增** 排除已被转换为导弹的无人机
            if (other.getCustomData().containsKey(CONVERTED_KEY)) continue;
            Object isBlizzard = other.getCustomData().get(BLIZZARD_TAG_KEY);
            if (!(isBlizzard instanceof Boolean && (Boolean) isBlizzard)) continue;
            result.add(other);
        }
        return result;
    }

    /**
     * 提供给UI显示的提示文本（根据系统状态返回不同提示）。
     *
     * @param system 系统API
     * @param ship   舰船
     * @return 提示字符串，null表示无提示
     */
    public String getInfoText(ShipSystemAPI system, ShipAPI ship) {
        // 弹药耗尽或无空闲时无提示
        if (system.isOutOfAmmo()) {
            return null;
        }
        if (system.getState() != SystemState.IDLE) {
            return null;
        }
        // 没有无人机时显示“没有无人机”
        if (this.getDrones(ship).isEmpty()) {
            return "没有集群";
        }
        float range = this.getMaxRange(ship);
        ShipAPI target = this.findTarget(ship);
        if (target == null) {
            // 若有鼠标点选位置但超出范围，则显示“超出范围”
            if (ship.getMouseTarget() != null) {
                float dist = Misc.getDistance(ship.getLocation(), ship.getMouseTarget());
                float radSum = ship.getCollisionRadius();
                if (dist + radSum > range) {
                    return "超出范围";
                }
            }
            return "没有目标";
        } else {
            // 有目标：判断是否在射程内
            float dist = Misc.getDistance(ship.getLocation(), target.getLocation());
            float radSum = ship.getCollisionRadius() + target.getCollisionRadius();
            return dist > range + radSum ? "超出范围" : "就绪";
        }
    }

    /**
     * 判断系统是否可用。
     * 即使系统处于CD中，只要还有无人机就让系统可用（可以提前取消CD？根据代码是返回true）
     * 实际逻辑：如果系统状态不是IDLE，则可用（允许充能中继续触发？）
     * 否则要求拥有无人机。
     *
     * @param system 系统API
     * @param ship   舰船
     * @return 是否可用
     */
    public boolean isUsable(ShipSystemAPI system, ShipAPI ship) {
        if (ship != null && ship.getSystem() != null && ship.getSystem().getState() != SystemState.IDLE) {
            // 系统未空闲时视为可用（可能是为了允许系统在充能时依然调用）
            return true;
        } else {
            // 空闲时必须拥有无人机才可用
            if (ship != null) {
                return !this.getDrones(ship).isEmpty();
            }
        }
        return false;
    }

    /**
     * 获取系统最大射程（考虑舰船系统射程加成）。
     * @param ship 所有者舰船
     * @return 有效射程
     */
    public float getMaxRange(ShipAPI ship) {
        // 确保武器存在
        if (this.weapon == null) {
            this.weapon = Global.getCombatEngine().createFakeWeapon(ship, this.getWeaponId());
        }
        // 应用舰船的系统射程加成，计算最终有效射程
        return ship.getMutableStats().getSystemRangeBonus().computeEffective(this.weapon.getRange());
    }

    /** 无人机能否作为点防御使用（AI决策用），这里返回true表示可以使用 */
    public boolean dronesUsefulAsPD() {
        return true;
    }

    /** 无人机打击是否对战斗机有效，这里返回false（系统可能更偏好打大船） */
    public boolean droneStrikeUsefulVsFighters() {
        return false;
    }

    /** 最大无人机数量（用于AI估算） */
    public int getMaxDrones() {
        return 2;
    }

    /** 获取导弹飞行速度（用于AI预判） */
    public float getMissileSpeed() {
        return this.weapon.getProjectileSpeed();
    }

    /** 外部设置强制目标 */
    public void setForceNextTarget(ShipAPI forceNextTarget) {
        this.forceNextTarget = forceNextTarget;
    }

    /** 获取强制目标 */
    public ShipAPI getForceNextTarget() {
        return this.forceNextTarget;
    }

    /**
     * 内部渲染脚本：负责将无人机跟随导弹移动，并在导弹命中/失效时销毁无人机并触发爆炸。
     * 该类实现了BaseCombatLayeredRenderingPlugin，每帧在渲染层中更新。
     */
    public static class VIII_DroneMissileScript extends BaseCombatLayeredRenderingPlugin {
        /** 原来的无人机实体 */
        protected ShipAPI drone;
        /** 转换后的导弹实体 */
        protected MissileAPI missile;
        /** 是否已完成（用于判断插件过期） */
        protected boolean done;

        /**
         * 构造脚本。
         * @param drone   无人机（将在模拟中消失）
         * @param missile 对应导弹
         */
        public VIII_DroneMissileScript(ShipAPI drone, MissileAPI missile) {
            this.drone = drone;
            this.missile = missile;
            missile.setNoFlameoutOnFizzling(true); // 防止哑火熄火
        }

        /**
         * 每帧更新：将无人机位置/朝向/速度同步到导弹，并检查导弹状态。
         */
        public void advance(float amount) {
            super.advance(amount);
            if (!this.done) {
                CombatEngineAPI engine = Global.getCombatEngine();

                // 让导弹具有全面的ECCM能力，避免被干扰
                this.missile.setEccmChanceOverride(1.0F);
                // 设置导弹的阵营为无人机原所有者（保持正确的敌我关系）
                this.missile.setOwner(this.drone.getOriginalOwner());

                // 将无人机实体完全同步到导弹当前状态，仿佛无人机就是导弹的外壳
                this.drone.getLocation().set(this.missile.getLocation());
                this.drone.getVelocity().set(this.missile.getVelocity());
                this.drone.setCollisionClass(CollisionClass.FIGHTER); // 保持战斗机碰撞类，使其可以被击中
                this.drone.setFacing(this.missile.getFacing());

                // 透明化无人机引擎效果（隐去原引擎），因为导弹会自带尾迹
                this.drone.getEngineController().fadeToOtherColor(this,
                        new Color(0, 0, 0, 0),
                        new Color(0, 0, 0, 0),
                        1.0F, 1.0F);

                // 计算飞行进度，用于控制抖动强度（越靠近目标抖动越大）
                float dist = Misc.getDistance(this.missile.getLocation(), this.missile.getStart());
                float jitterFraction = dist / this.missile.getMaxRange();
                jitterFraction = Math.max(jitterFraction, this.missile.getFlightTime() / this.missile.getMaxFlightTime());
                this.missile.setSpriteAlphaOverride(0.0F); // 隐藏导弹本身的精灵（完全由无人机代替）
                float jitterMax = 1.0F + 10.0F * jitterFraction; // 抖动幅度随时间增长
                this.drone.setJitter(this,
                        new Color(255, 100, 50, (int) (25.0F + 50.0F * jitterFraction)), // 抖动颜色
                        1.0F, // 抖动强度
                        10,   // 频率
                        1.0F,
                        jitterMax);

                // 检查无人机是否已被摧毁（单独被击毁的情况）
                boolean droneDestroyed = this.drone.isHulk() || this.drone.getHitpoints() <= 0.0F;

                // 条件1：导弹未失效，导弹未被摧毁（除非已经击中目标），无人机也未损毁
                if (!this.missile.isFizzling()
                        && (!(this.missile.getHitpoints() <= 0.0F) || this.missile.didDamage())
                        && !droneDestroyed) {

                    // 若导弹已成功命中目标，则销毁无人机并产生爆炸
                    if (this.missile.didDamage()) {
                        // 停止运动
                        this.drone.getVelocity().set(0.0F, 0.0F);
                        this.missile.getVelocity().set(0.0F, 0.0F);
                        // 在无人机位置附近随机点施加巨大伤害，使无人机瞬间“毁灭”以模拟爆炸
                        Vector2f damageFrom = new Vector2f(this.drone.getLocation());
                        damageFrom = Misc.getPointWithinRadius(damageFrom, 20.0F);
                        engine.applyDamage(this.drone, damageFrom, 1000000.0F, DamageType.ENERGY,
                                0.0F, true, false, this.drone, false);
                        this.missile.interruptContrail(); // 中断导弹尾迹（避免残留）
                        engine.removeEntity(this.drone);   // 移除无人机实体
                        engine.removeEntity(this.missile); // 移除导弹
                        this.done = true;
                    }
                } else {
                    // 条件2：导弹失效或无人机被摧毁，则销毁并播放爆炸
                    this.drone.getVelocity().set(0.0F, 0.0F);
                    this.missile.getVelocity().set(0.0F, 0.0F);
                    if (!droneDestroyed) {
                        // 无人机未被损坏时强制摧毁（模拟导弹失效时无人机也跟着爆炸）
                        Vector2f damageFrom = new Vector2f(this.drone.getLocation());
                        damageFrom = Misc.getPointWithinRadius(damageFrom, 20.0F);
                        engine.applyDamage(this.drone, damageFrom, 1000000.0F, DamageType.ENERGY,
                                0.0F, true, false, this.drone, false);
                    }
                    this.missile.interruptContrail();
                    engine.removeEntity(this.drone);
                    engine.removeEntity(this.missile);
                    this.missile.explode(); // 触发爆炸特效
                    this.done = true;
                }
            }
        }

        /** 当done为true时，插件过期，引擎会将其移除 */
        public boolean isExpired() {
            return this.done;
        }
    }
}