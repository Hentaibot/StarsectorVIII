// 包路径：远行星号游戏API实现中的战斗威胁包
package data.scripts.shipsystems;

// 导入全局游戏实例类
import com.fs.starfarer.api.Global;
// 导入战斗相关接口：舰船、战斗引擎、可变属性等
import com.fs.starfarer.api.combat.*;
// 导入EMP电弧参数内部类
import com.fs.starfarer.api.combat.EmpArcEntityAPI.EmpArcParams;
// 导入舰船尺寸枚举
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
// 导入舰船系统状态枚举
import com.fs.starfarer.api.combat.ShipSystemAPI.SystemState;
// 导入舰船AI标志中的具体标志常量
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;
// 导入武器尺寸枚举
import com.fs.starfarer.api.combat.WeaponAPI.WeaponSize;
// 导入基础舰船系统脚本抽象类
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
// 导入输入事件接口
import com.fs.starfarer.api.input.InputEventAPI;
// 导入武器槽位接口
import com.fs.starfarer.api.loading.WeaponSlotAPI;
// 导入杂项工具类
import com.fs.starfarer.api.util.Misc;
// 导入查找舰船过滤器接口
import com.fs.starfarer.api.util.Misc.FindShipFilter;
// 导入LWJGL二维向量类
import data.scripts.VIII_threat.VIII_ThreatCombatStrategyAI;
import data.scripts.VIII_threat.VIII_ThreatShipConstructionScript;
import data.scripts.VIII_threat.VIII_ThreatSwarmAI;
import data.scripts.VIII_threat.VIII_VoltaicDischargeOnFireEffect;
import org.lwjgl.util.vector.Vector2f;

// 导入AWT颜色类
import java.awt.*;
// 导入Java标准库中的列表类
import java.util.ArrayList;
import java.util.List;

/**
 * 能量鞭笞系统脚本
 * 继承自BaseShipSystemScript，实现能量鞭笞（Energy Lash）系统的核心逻辑：
 * 发射EMP电弧攻击敌方或充能友方，并根据目标类型调整冷却时间等。
 */
public class VIII_EnergyLashSystemScript extends BaseShipSystemScript {
    
    // ==================== 系统参数常量 ====================
    /** 最大鞭笞范围（像素） */
    public static float MAX_LASH_RANGE = 2000f;
    
    /** 对敌方造成的直接伤害（当前为0，可能仅EMP） */
    public static float DAMAGE = 0;
    /** 对敌方造成的EMP伤害（幅能伤害） */
    public static float EMP_DAMAGE = 1500;
    
    /** 最小冷却时间（秒） */
    public static float MIN_COOLDOWN = 2f;
    /** 最大冷却时间（秒） */
    public static float MAX_COOLDOWN = 10f;
    /** 冷却时间与部署点的倍率关系（每点部署点增加0.33秒冷却） */
    public static float COOLDOWN_DP_MULT = 0.33f;
    
    /** 命中敌方后的最小冷却时间（秒） */
    public static float MIN_HIT_ENEMY_COOLDOWN = 2f;
    /** 命中敌方后的最大冷却时间（秒） */
    public static float MAX_HIT_ENEMY_COOLDOWN = 5f;
    /** 命中相位敌方的冷却时间倍率（2倍） */
    public static float HIT_PHASE_ENEMY_COOLDOWN_MULT = 2f;
    
    /** 对友方攻击群（Swarm）的鞭笞超时时间（秒），避免重复充能同一群 */
    public static float SWARM_TIMEOUT = 10f;
    
    /** 相位敌方被击中后的过载持续时间（秒） */
    public static float PHASE_OVERLOAD_DUR = 1f;
    
    // ==================== 内部辅助类：延迟执行战斗插件 ====================
    /**
     * 延迟执行动作的战斗插件（用于多电弧依次发射）
     */
    public static class DelayedCombatActionPlugin extends BaseEveryFrameCombatPlugin {
        float elapsed = 0f;   // 已过去时间
        float delay;          // 延迟时间（秒）
        Runnable r;           // 要执行的动作
        
        public DelayedCombatActionPlugin(float delay, Runnable r) {
            this.delay = delay;
            this.r = r;
        }
        
        @Override
        public void advance(float amount, List<InputEventAPI> events) {
            if (Global.getCombatEngine().isPaused()) return;  // 战斗暂停时不计时
            
            elapsed += amount;
            if (elapsed < delay) return;                     // 未到延迟时间
            
            r.run();                                          // 执行动作
            
            CombatEngineAPI engine = Global.getCombatEngine();
            engine.removePlugin(this);                        // 执行后移除自身
        }
    }
    
    // ==================== 成员变量 ====================
    /** 主要发射槽位（中型系统槽） */
    protected WeaponSlotAPI mainSlot;
    /** 所有系统槽位列表（用于多电弧发射） */
    protected List<WeaponSlotAPI> slots;
    /** 是否准备好发射（防止重复发射） */
    protected boolean readyToFire = true;
    /** 自上次对攻击群鞭笞后经过的时间（秒） */
    protected float sinceSwarmTargeted = SWARM_TIMEOUT;
    /** 待设置的冷却时间（在系统冷却阶段开始时应用） */
    protected float cooldownToSet = -1f;
    
    /**
     * 查找舰船上的所有系统槽位，并记录中型槽为主槽
     * @param ship 目标舰船
     */
    protected void findSlots(ShipAPI ship) {
        if (slots != null) return;   // 已找到则直接返回
        slots = new ArrayList<>();
        // 遍历舰船所有武器槽位
        for (WeaponSlotAPI slot : ship.getHullSpec().getAllWeaponSlotsCopy()) {
            if (slot.isSystemSlot()) {                    // 只处理系统槽
                slots.add(slot);
                if (slot.getSlotSize() == WeaponSize.MEDIUM) {
                    mainSlot = slot;                     // 中型槽作为主发射槽
                }
            }
        }
    }
    
    /**
     * 应用系统效果（由父类在系统激活、持续、关闭时调用）
     * @param stats       舰船可变属性统计
     * @param id          效果ID
     * @param state       系统状态（IDLE/ACTIVE/OUT/COOLDOWN）
     * @param jitterLevel 效果强度（0~1）
     */
    public void apply(MutableShipStatsAPI stats, String id, State state, float jitterLevel) {
        ShipAPI ship;
        // 获取属性统计关联的舰船实体
        if (stats.getEntity() instanceof ShipAPI) {
            ship = (ShipAPI) stats.getEntity();
        } else {
            return;   // 非舰船实体无法处理
        }
        
        // 更新自上次攻击群鞭笞后的计时器（每帧增加经过时间）
        sinceSwarmTargeted += Global.getCombatEngine().getElapsedInLastFrame();
        
        // 如果系统处于冷却或空闲状态，且有待设置的冷却时间，则应用该冷却时间
        if ((state == State.COOLDOWN || state == State.IDLE) && cooldownToSet >= 0f) {
            ship.getSystem().setCooldown(cooldownToSet);
            ship.getSystem().setCooldownRemaining(cooldownToSet);
            cooldownToSet = -1f;   // 清除待设置标记
        }
        
        // 当系统空闲、冷却或效果强度为0时，重置发射准备标志
        if (state == State.IDLE || state == State.COOLDOWN || jitterLevel <= 0f) {
            readyToFire = true;
        }
        
        // 系统激活（IN）或关闭（OUT）阶段，设置舰船抖动视觉效果
        if (state == State.IN || state == State.OUT) {
            // 抖动强度随效果强度变化

            float maxRangeBonus = 150f;
            // 抖动范围奖励：在激活阶段逐渐减小，关闭阶段为0
            float jitterRangeBonus = (1f - jitterLevel * jitterLevel) * maxRangeBonus;
            
            // 计算亮度：激活阶段先增后减，关闭阶段单调递减
            if (state == State.OUT) {
                jitterRangeBonus = 0f;
            }
            // 设置舰船抖动（颜色固定为青灰色）
            ship.setJitter(this, new Color(130,155,145,255), jitterLevel, 5, 0f, 3f + jitterRangeBonus);
        }
        
        // 当效果强度达到1（系统完全激活）且可以发射时，执行鞭笞
        if (jitterLevel == 1 && readyToFire) {
            // 寻找有效目标（敌方或友方）
            ShipAPI target = findTarget(ship);
            readyToFire = false;   // 防止重复发射
            if (target != null) {
                CombatEngineAPI engine = Global.getCombatEngine();
                findSlots(ship);   // 确保槽位列表已初始化
                
                Vector2f slotLoc = mainSlot.computePosition(ship);   // 主发射槽世界坐标
                
                // 创建EMP电弧参数对象
                EmpArcParams params = new EmpArcParams();
                params.segmentLengthMult = 8f;          // 分段长度倍率
                params.zigZagReductionFactor = 0.15f;   // 之字形衰减因子
                params.fadeOutDist = 500f;              // 淡出距离
                params.minFadeOutMult = 2f;             // 最小淡出倍率
                params.flickerRateMult = 0.7f;          // 闪烁率倍率
                
                // 情况1：目标是友方（同阵营）
                if (ship.getOwner() == target.getOwner()) {
                    params.flickerRateMult = 0.3f;       // 降低闪烁率
                    
                    // 确定电弧颜色：普通友方用EMP边缘色，攻击群用相位边缘色
                    Color color = VIII_VoltaicDischargeOnFireEffect.EMP_FRINGE_COLOR;
                    if (VIII_ThreatSwarmAI.isAttackSwarm(target)) {
                        color = VIII_VoltaicDischargeOnFireEffect.PHASE_FRINGE_COLOR;
                    }
                    float emp = 0;   // 对友方不造成EMP伤害
                    float dam = 0;   // 对友方不造成直接伤害
                    // 生成穿透护盾的EMP电弧（视觉效果，实际无伤害）
                    EmpArcEntityAPI arc = engine.spawnEmpArcPierceShields(ship, slotLoc, ship, target,
                            DamageType.ENERGY, 
                            dam,
                            emp, 
                            100000f, // 最大范围（极大值）
                            "energy_lash_friendly_impact", // 命中音效ID
                            100f,    // 电弧厚度
                            color,
                            new Color(255,255,255,255),
                            params
                            );
                    arc.setTargetToShipCenter(slotLoc, target);   // 电弧瞄准目标中心
                    arc.setCoreWidthOverride(50f);                // 覆盖核心宽度50像素
                    arc.setSingleFlickerMode(true);               // 单次闪烁模式
                    // 播放发射音效
                    Global.getSoundPlayer().playSound("energy_lash_fire", 1f, 1f, ship.getLocation(), ship.getVelocity());
                } 
                // 情况2：目标是敌方
                else {
                    params.flickerRateMult = 0.4f;   // 闪烁率0.4
                    
                    int numArcs = slots.size();      // 电弧数量等于系统槽位数量
                    float emp = EMP_DAMAGE;          // EMP伤害值
                    float dam = DAMAGE;              // 直接伤害值（通常为0）
                    
                    // 依次发射多个电弧（每个槽位一个）
                    for (int i = 0; i < numArcs; i++) {
                        float delay = 0.03f * i;     // 每个电弧延迟0.03秒，形成连射效果
                        int index = i;
                        // 定义发射动作的Runnable
                        Runnable r = () -> {
                            // 获取当前槽位的位置
                            Vector2f slotLoc1 = slots.get(index).computePosition(ship);
                            // 根据目标是否相位状态选择颜色
                            Color color = VIII_VoltaicDischargeOnFireEffect.EMP_FRINGE_COLOR;
                            Color core = new Color(255,255,255,255);
                            if (target.isPhased()) {
                                color = VIII_VoltaicDischargeOnFireEffect.PHASE_FRINGE_COLOR;
                                core = VIII_VoltaicDischargeOnFireEffect.PHASE_CORE_COLOR;
                            }
                            // 生成EMP电弧（造成伤害）
                            EmpArcEntityAPI arc = engine.spawnEmpArc(ship, slotLoc1, ship, target,
                                    DamageType.ENERGY,
                                    dam,
                                    emp,
                                    100000f,   // 最大范围
                                    "energy_lash_enemy_impact", // 命中音效ID
                                    60f,       // 电弧厚度
                                    color,
                                    core,
                                    params
                                    );
                            arc.setCoreWidthOverride(40f);       // 核心宽度40像素
                            arc.setSingleFlickerMode(true);      // 单次闪烁模式
                        };
                        // 延迟执行或立即执行
                        if (delay <= 0f) {
                            r.run();
                        } else {
                            Global.getCombatEngine().addPlugin(new DelayedCombatActionPlugin(delay, r));
                        }
                        // 播放发射音效（每个电弧都播放，可能重叠，但原版如此）
                        Global.getSoundPlayer().playSound("energy_lash_fire_at_enemy", 1f, 1f, ship.getLocation(), ship.getVelocity());
                    }
                }
                
                // 应用鞭笞命中后的效果（充能/过载等）
                applyEffectToTarget(ship, target);
            }
        }
    }

    /**
     * 应用鞭笞命中目标后的具体效果
     * @param ship   发射鞭笞的舰船
     * @param target 被命中的目标
     */
    protected void applyEffectToTarget(ShipAPI ship, ShipAPI target) {
        boolean isSwarm = VIII_ThreatSwarmAI.isAttackSwarm(target);
        if (!isSwarm) {
            if (target == null || target.getSystem() == null || target.isHulk()) return;
        }
        if (ship == null || ship.getSystem() == null || ship.isHulk()) return;

        // 情况1：命中友方
        if (ship.getOwner() == target.getOwner()) {
            // 施加 3 秒 6 倍时间流速增加（不可叠加，命中刷新持续时间）
            if (target.getSystem() != null) {
                final String stateKey = "VIII_EnergyLash_time_boost_state";
                final String modIdKey = stateKey + "_modId";

                // 1. 移除当前可能存在的旧效果
                Object existing = target.getCustomData().get(stateKey);
                if (existing instanceof BaseEveryFrameCombatPlugin oldPlugin) {
                    Global.getCombatEngine().removePlugin(oldPlugin);
                    // 移除旧的属性修改
                    String oldModId = (String) target.getCustomData().get(modIdKey);
                    if (oldModId != null) {
                        target.getMutableStats().getTimeMult().unmodify(oldModId);
                    }
                }

                // 2. 应用新的时间流速修改
                final String timeMultId = "VIII_EnergyLash_time_boost_" + System.nanoTime();
                target.getMutableStats().getTimeMult().modifyMult(timeMultId, 6f);

                // 3. 创建新的延迟插件，3 秒后自动移除
                BaseEveryFrameCombatPlugin newPlugin = new BaseEveryFrameCombatPlugin() {
                    float elapsed = 0f;

                    @Override
                    public void advance(float amount, List<InputEventAPI> events) {
                        if (Global.getCombatEngine().isPaused()) return;

                        elapsed += amount;
                        if (elapsed >= 3f || !target.isAlive()) {
                            target.getMutableStats().getTimeMult().unmodify(timeMultId);
                            Global.getCombatEngine().removePlugin(this);
                            // 清理存储的数据
                            target.getCustomData().remove(stateKey);
                            target.getCustomData().remove(modIdKey);
                        }
                    }
                };

                // 4. 记录新状态
                target.getCustomData().put(stateKey, newPlugin);
                target.getCustomData().put(modIdKey, timeMultId);
                Global.getCombatEngine().addPlugin(newPlugin);
            }
            // 如果友方是攻击群，则激活其相位模式
            else if (isSwarm) {
                VIII_VoltaicDischargeOnFireEffect.setSwarmPhaseMode(target);
                sinceSwarmTargeted = 0f;
            }

            // 计算本系统的冷却时间（同原逻辑）
            float cooldown = target.getHullSpec().getSuppliesToRecover();
            cooldown = MIN_COOLDOWN + cooldown * COOLDOWN_DP_MULT;
            if (cooldown > MAX_COOLDOWN) cooldown = MAX_COOLDOWN;
            if (target.isFighter()) cooldown = MIN_COOLDOWN;
            cooldownToSet = cooldown;
        }
        // 情况2：命中敌方（保持原逻辑不变）
        else {
            boolean hitPhase = false;
            if (target.isPhased()) {
                target.setOverloadColor(VIII_VoltaicDischargeOnFireEffect.EMP_FRINGE_COLOR_BRIGHT);
                target.getFluxTracker().beginOverloadWithTotalBaseDuration(PHASE_OVERLOAD_DUR);
                if (target.getFluxTracker().showFloaty() ||
                        ship == Global.getCombatEngine().getPlayerShip() ||
                        target == Global.getCombatEngine().getPlayerShip()) {
                    target.getFluxTracker().playOverloadSound();
                    target.getFluxTracker().showOverloadFloatyIfNeeded("相位场干扰！",
                            VIII_VoltaicDischargeOnFireEffect.EMP_FRINGE_COLOR, 4f, true);
                }
                Global.getCombatEngine().addPlugin(new BaseEveryFrameCombatPlugin() {
                    @Override
                    public void advance(float amount, List<InputEventAPI> events) {
                        if (!target.getFluxTracker().isOverloadedOrVenting()) {
                            target.resetOverloadColor();
                            Global.getCombatEngine().removePlugin(this);
                        }
                    }
                });
                hitPhase = true;
            }
            float cooldown = MIN_HIT_ENEMY_COOLDOWN +
                    (MAX_HIT_ENEMY_COOLDOWN - MIN_HIT_ENEMY_COOLDOWN) * (float) Math.random();
            if (hitPhase) {
                cooldown *= HIT_PHASE_ENEMY_COOLDOWN_MULT;
            }
            if (cooldown > MAX_COOLDOWN) cooldown = MAX_COOLDOWN;
            cooldownToSet = cooldown;
        }
    }
    
    /**
     * 系统关闭时撤销效果（父类方法，无需实现）
     */
    public void unapply(MutableShipStatsAPI stats, String id) {
    }
    
    /**
     * 获取状态栏显示的数据（未使用）
     */
    public StatusData getStatusData(int index, State state, float effectLevel) {
        return null;
    }
    
    /**
     * 获取系统在UI上显示的提示文字（鼠标悬停时）
     * @param system 系统对象
     * @param ship   舰船
     * @return 提示文字
     */
    @Override
    public String getInfoText(ShipSystemAPI system, ShipAPI ship) {
        if (system.isOutOfAmmo()) return null;
        if (system.getState() != SystemState.IDLE) return null;
        
        ShipAPI target = findTarget(ship);
        if (target != null && target != ship) {
            return "就绪";                     // 有有效目标，系统可用
        }
        if (ship.getShipTarget() != null) {
            return "超出范围";              // 有目标但超出范围
        }
        return "没有目标";                     // 无有效目标
    }
    
    /**
     * 判断目标是否在鞭笞范围内
     * @param ship   发射舰船
     * @param target 目标舰船
     * @return true=在范围内
     */
    public boolean isInRange(ShipAPI ship, ShipAPI target) {
        float range = getRange(ship);
        float dist = Misc.getDistance(ship.getLocation(), target.getLocation());
        float radSum = ship.getCollisionRadius() + target.getCollisionRadius();
        return dist <= range + radSum;
    }
    
    /**
     * 判断某个舰船是否可以作为有效的鞭笞目标
     * @param ship   发射舰船
     * @param other  待判断的舰船
     * @return true=有效
     */
    public boolean isValidLashTarget(ShipAPI ship, ShipAPI other) {
        if (other == null) return false;
        // 新增：督军不能成为鞭笞目标
        if (VIII_ThreatCombatStrategyAI.isOverseer(other)) return false;
        if (other.isHulk() || other.getOwner() == 100) return false;   // 残骸或中立
        if (other.isShuttlePod()) return false;                         // 穿梭舱忽略
        if (other.hasTag(VIII_ThreatShipConstructionScript.SHIP_UNDER_CONSTRUCTION)) return false; // 正在建造中的船忽略
        // 如果是友方战机且为攻击群，且距离上次充能已超过超时时间，则允许
        if (other.isFighter() && other.getOwner() == ship.getOwner()) {
            return VIII_ThreatSwarmAI.isAttackSwarm(other) && sinceSwarmTargeted > SWARM_TIMEOUT;
        }
        
        if (other.isFighter()) return false;    // 其他友方战机忽略
//        if (other.getOwner() == ship.getOwner()) {
//            // 友方非战机：必须拥有系统且系统实现了EnergyLashActivatedSystem，且不在冷却/激活/过载中
//            if (other.getSystem() == null) return false;
//            if (!(other.getSystem().getScript() instanceof VIII_EnergyLashActivatedSystem)) return false;
//            if (other.getSystem().getCooldownRemaining() > 0) return false;
//            if (other.getSystem().isActive()) return false;
//            if (other.getFluxTracker().isOverloadedOrVenting()) return false;
//        }
        return true;
    }
    
    /**
     * 寻找当前最佳鞭笞目标
     * @param ship 发射舰船
     * @return 目标舰船，如果无有效目标则返回null
     */
    protected ShipAPI findTarget(ShipAPI ship) {
        float range = getRange(ship);
        boolean player = ship == Global.getCombatEngine().getPlayerShip();
        ShipAPI target = ship.getShipTarget();   // 获取当前AI或玩家指定的目标
        
        // 额外范围奖励（如果AI有CUSTOM1标志，增加500范围）
        float extraRange = 0f;
        if (ship.getShipAI() != null && ship.getAIFlags().hasFlag(AIFlags.CUSTOM1)){
            target = (ShipAPI) ship.getAIFlags().getCustom(AIFlags.CUSTOM1);
            extraRange += 500f;
        }
        
        // 如果已有目标，检查是否超出范围（包括额外范围）
        if (target != null) {
            float dist = Misc.getDistance(ship.getLocation(), target.getLocation());
            float radSum = ship.getCollisionRadius() + target.getCollisionRadius();
            if (dist > range + radSum + extraRange) target = null;
        } else {
            // 没有目标，则尝试根据条件查找
            FindShipFilter filter = s -> isValidLashTarget(ship, s);
            
            // 如果当前目标是空或友方，根据玩家鼠标或AI机动目标查找
            if (player) {
                // 玩家：寻找鼠标位置最近的符合条件的目标
                target = Misc.findClosestShipTo(ship, ship.getMouseTarget(), HullSize.FIGHTER, range, true, false, filter);
            } else {
                // AI：使用机动目标（MANEUVER_TARGET）
                Object test = ship.getAIFlags().getCustom(AIFlags.MANEUVER_TARGET);
                if (test instanceof ShipAPI) {
                    target = (ShipAPI) test;
                    float dist = Misc.getDistance(ship.getLocation(), target.getLocation());
                    float radSum = ship.getCollisionRadius() + target.getCollisionRadius();
                    if (dist > range + radSum) target = null;   // 超出范围则清空
                }
            }
            // 如果仍无目标，则寻找舰船位置附近最近的有效目标
            if (target == null) {
                target = Misc.findClosestShipTo(ship, ship.getLocation(), HullSize.FIGHTER, range, true, false, filter);
            }
        }
        return target;
    }
    
    /**
     * 判断系统是否可用（UI调用）
     * @param system 系统对象
     * @param ship   舰船
     * @return true=可用
     */
    @Override
    public boolean isUsable(ShipSystemAPI system, ShipAPI ship) {
        ShipAPI target = findTarget(ship);
        return target != null && target != ship;
    }
    
    /**
     * 获取鞭笞系统的有效范围（考虑舰船的系统射程加成）
     * @param ship 舰船
     * @return 范围（像素）
     */
    public static float getRange(ShipAPI ship) {
        if (ship == null) return MAX_LASH_RANGE;
        return ship.getMutableStats().getSystemRangeBonus().computeEffective(MAX_LASH_RANGE);
    }
}