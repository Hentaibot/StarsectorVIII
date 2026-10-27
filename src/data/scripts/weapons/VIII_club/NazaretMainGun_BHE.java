package data.scripts.weapons.VIII_club;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.loading.DamagingExplosionSpec;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.util.vector.Vector2f;

import java.awt.*;
import java.util.List;

/**
 * 主炮命中效果插件（NazaretMainGun_BHE）。
 * 当弹丸命中目标时，根据发射舰船的当前幅能值计算伤害，并在短暂延迟后生成爆炸和EMP电弧。
 */
public class NazaretMainGun_BHE implements OnHitEffectPlugin {

    // ========== 静态常量配置 ==========
    /** 爆炸持续时间（秒） */
    private static final float damageDuration = 0.25f;
    /** 爆炸碰撞类型（不对友军造成伤害） */
    private static final CollisionClass collisionClass = CollisionClass.MISSILE_NO_FF;
    /** 对战斗机时的碰撞类型 */
    private static final CollisionClass collisionClassFighter = CollisionClass.MISSILE_NO_FF;
    /** 爆炸粒子最小大小 */
    private static final float particleSizeMin = 0;
    /** 爆炸粒子大小随机范围 */
    private static final float particleSizeRange = 0;
    /** 爆炸粒子持续时间（秒） */
    private static final float particleDuration = 0;
    /** 爆炸粒子数量 */
    private static final int particleCount = 0;
    /** 爆炸粒子颜色（红色） */
    public static final Color color = new Color(255, 108, 54, 0);
    /** 爆炸颜色（设为null，使用默认） */
    private static final Color expColor = new Color(255, 108, 54, 0);

    /** 伤害与当前幅能的比例：1表示取100%当前幅能值作为伤害基数 */
    private static final float DAMAGE_PERCENT = 0.1f;

    /** 核心爆炸半径（全伤害区域） */
    private static final float coreRadius = 150;
    /** 边缘爆炸半径（伤害逐渐递减） */
    private static final float radius = 300;

    /** EMP电弧的正常半径 */
    private static final float NORMAL_ARC_RADIUS = 100000f;
    /** EMP伤害值（固定2000，但会乘以 DAMAGE_PERCENT 和幅能水平） */
    private static final float EMP_DAMAGE = 2000;
    /** EMP电弧的颜色（红褐色） */
    private static final Color ARC_COLOR = new Color(255, 108, 54, 0);

    // ========== 辅助方法 ==========

    /**
     * 将绝对位置转换为相对于目标实体的偏移量。
     * 计算方式：先将绝对位置减去实体位置，然后旋转到实体局部坐标系（减去实体的朝向）。
     * @param entity 目标实体
     * @param loc 绝对位置
     * @return 相对于实体的偏移向量（在实体局部坐标系中）
     */
    private static Vector2f LocationToOffset(CombatEntityAPI entity, Vector2f loc) {
        Vector2f offset = new Vector2f(loc);
        Vector2f.sub(offset, entity.getLocation(), offset);          // offset = loc - entity.location
        VectorUtils.rotate(offset, -entity.getFacing(), offset);    // 旋转 -facing 度，转换到实体局部坐标系
        return offset;
    }

    /**
     * 将相对于目标实体的偏移量转换为绝对位置。
     * @param entity 目标实体
     * @param offset 局部偏移
     * @return 绝对位置坐标
     */
    private static Vector2f OffsetToLocation(CombatEntityAPI entity, Vector2f offset) {
        Vector2f loc = new Vector2f(offset);
        VectorUtils.rotate(loc, entity.getFacing(), loc);           // 旋转到世界坐标系
        Vector2f.add(loc, entity.getLocation(), loc);               // 加上实体位置
        return loc;
    }

    // ========== 接口实现 ==========

    /**
     * 弹丸命中目标时的回调方法。
     * @param projectile 造成伤害的弹丸
     * @param target 被命中的实体
     * @param point 命中点世界坐标
     * @param shieldHit 是否命中护盾
     * @param damageResult 伤害结果对象
     * @param engine 战斗引擎
     */
    @Override
    public void onHit(DamagingProjectileAPI projectile, CombatEntityAPI target,
                      Vector2f point, boolean shieldHit,
                      ApplyDamageResultAPI damageResult, CombatEngineAPI engine) {
        float delay = 1; // 爆炸延迟1秒

        ShipAPI sourceShip = projectile.getSource(); // 发射舰船
        float fluxLevel;
        float fluxValue = 0f;

        if (sourceShip != null) {
            fluxValue = sourceShip.getFluxTracker().getCurrFlux() * DAMAGE_PERCENT; // 计算伤害基数
        }

        // 添加一个自定义插件，负责在延迟后生成爆炸和EMP
        Global.getCombatEngine().addPlugin(new explosionSpawner(target, LocationToOffset(target, point), fluxValue, delay, sourceShip));
    }

    // ========== 内部类：爆炸生成器 ==========

    /**
     * 内部类 explosionSpawner，继承自 BaseEveryFrameCombatPlugin。
     * 负责在指定延迟后生成爆炸和EMP电弧。
     */
    private static class explosionSpawner extends BaseEveryFrameCombatPlugin {
        /** 被击中的目标实体 */
        final CombatEntityAPI target;
        /** 命中点相对于目标实体的偏移（局部坐标） */
        final Vector2f offset;
        /** 爆炸伤害值（基于发射舰船的幅能计算） */
        final float damage;
        /** 延迟时间（秒） */
        final float delay;
        /** 计时器，累计经过的时间 */
        float timer = 0;
        /** 发射舰船（伤害来源） */
        final ShipAPI source;
        /** 发射舰船的幅能水平（0~1），用于计算EMP伤害 */
        float fluxLevel;

        /** 构造函数，初始化各字段 */
        private explosionSpawner(CombatEntityAPI target, Vector2f offset, float damage, float delay, ShipAPI source) {
            this.target = target;
            this.offset = offset;
            this.damage = damage;
            this.delay = delay;
            this.source = source;
            this.fluxLevel = (source != null) ? source.getFluxLevel() : 0f;
        }

        /** 标记是否已完成爆炸生成 */
        boolean done = false;

        /**
         * 每帧更新方法，用于计时和触发爆炸。
         * @param amount 时间增量（秒）
         * @param events 输入事件列表（未使用）
         */
        @Override
        public void advance(float amount, List<InputEventAPI> events) {
            // 计算EMP伤害：EMP_DAMAGE * DAMAGE_PERCENT * fluxLevel
            float emp = EMP_DAMAGE * DAMAGE_PERCENT * fluxLevel;

            if (done) {
                // 如果已完成，移除自身
                Global.getCombatEngine().removePlugin(this);
                return;
            }
            timer += amount;
            if (timer >= delay) {
                // 延迟时间到，生成爆炸
                DamagingExplosionSpec spec = new DamagingExplosionSpec(
                        damageDuration,          // 爆炸持续时间
                        radius,                   // 爆炸半径（边缘）
                        coreRadius,               // 核心半径（全伤害）
                        damage,                   // 最大伤害
                        0,                        // 最小伤害（未使用，此处设为0）
                        collisionClass,           // 碰撞类型
                        collisionClassFighter,    // 对战机碰撞类型
                        particleSizeMin,          // 粒子最小大小
                        particleSizeRange,        // 粒子大小随机范围
                        particleDuration,         // 粒子持续时间
                        particleCount,            // 粒子数量
                        color,                     // 粒子颜色
                        expColor                   // 爆炸颜色（null）
                );
                // 在绝对位置生成爆炸（将偏移转换回世界坐标）
                Global.getCombatEngine().spawnDamagingExplosion(spec, source, OffsetToLocation(target, offset));
                spec.setSoundSetId("VIII_club_HE_boom"); // 设置爆炸音效
                // 如果来源存在且目标是舰船，则生成EMP电弧（穿透护盾）
                if (source != null && target instanceof ShipAPI) {
                    Global.getCombatEngine().spawnEmpArcPierceShields(
                            source,                // 伤害来源
                            OffsetToLocation(target, offset),                // 起点偏移？此处可能有问题：应为起点世界坐标，但传入的是局部偏移
                            target,                // 起点附着实体（应为null？但这里用了target，可能导致电弧起点在target上）
                            target,                // 目标实体
                            DamageType.ENERGY,     // 伤害类型
                            200F,                  // 伤害值（固定200）
                            emp,                   // EMP伤害
                            NORMAL_ARC_RADIUS,                // 最大电弧范围
                            "tachyon_lance_emp_impact", // 音效ID
                            10f,                  // 电弧粗细
                            ARC_COLOR,             // 边缘颜色
                            Color.RED               // 核心颜色
                    );
                }
                done = true; // 标记完成，下一帧会移除插件
            }
        }
    }
}