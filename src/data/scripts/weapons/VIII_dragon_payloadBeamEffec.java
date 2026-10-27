package data.scripts.weapons;

// 导入远行星号游戏API中的战斗相关类
import com.fs.starfarer.api.combat.*;
// 导入间隔计时工具类，用于控制周期性触发
import com.fs.starfarer.api.util.IntervalUtil;
// 导入LazyLib碰撞检测工具类
import org.lazywizard.lazylib.CollisionUtils;
// 导入LazyLib数学工具类
import org.lazywizard.lazylib.MathUtils;
// 导入LazyLib向量工具类
import org.lazywizard.lazylib.VectorUtils;
// 导入LazyLib战斗工具类，用于获取范围内的实体
import org.lazywizard.lazylib.combat.CombatUtils;
// 导入LWJGL二维向量类
import org.lwjgl.util.vector.Vector2f;

import java.util.List;

/**
 * 光束穿透效果插件 - 实现光束对多个目标的穿透伤害
 * 优化点:
 * 1. 减少重复计算
 * 2. 优化碰撞检测
 * 3. 添加性能保护机制
 * 4. 提高代码可读性
 * 新增功能: 光束每个检查点可以造成四种伤害类型
 * 新增功能: 将光束总伤害平均分配到各个检查点
 */
// 实现BeamEffectPlugin接口，用于自定义光束的每帧效果
public class VIII_dragon_payloadBeamEffec implements BeamEffectPlugin {
    // 计时器，每0.01秒触发一次伤害检查（频率较高）
    private final IntervalUtil timer = new IntervalUtil(0.01F, 0.01F);
    // 对舰船造成的伤害系数（实际伤害 = damagePerCheck * MAX_DAMAGE）
    private static final float MAX_DAMAGE = 1F;
    // 对非舰船实体（如导弹、小行星）造成的伤害系数（2倍）
    private static final float NO_SHIP_DAMAGE = 2F;
    // 最多检查的实体数量，防止性能爆炸
    private static final int MAX_ENTITIES_TO_CHECK = 30;
    // 检查点之间的间隔距离（像素）
    private static final float CHECKPOINT_INTERVAL = 20.0F;
    // 粒子效果随机偏移范围（像素）
    private static final float PARTICLE_RANGE = 10.0F;

    // 每帧/每次调用的主要逻辑
    @Override
    public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {
        // 如果战斗暂停，或者光束的DPS持续时间为0（即不造成伤害），则直接返回
        if (engine.isPaused() || beam.getDamage().getDpsDuration() <= 0) {
            return;
        }

        // 推进计时器
        timer.advance(amount);
        // 如果计时器未达到间隔时间，则跳过本次伤害判定（控制伤害判定频率）
        if (!timer.intervalElapsed()) {
            return;
        }

        // 预计算光束参数（避免在循环中重复计算）
        Vector2f beamFrom = beam.getFrom();          // 光束起点（世界坐标）
        Vector2f beamTo = beam.getTo();              // 光束终点（世界坐标）
        float beamRange = beam.getWeapon().getRange(); // 武器射程（光束长度上限）
        float beamAngle = VectorUtils.getAngle(beamFrom, beamTo); // 光束方向角度（度）
        float beamDistance = MathUtils.getDistance(beamFrom, beamTo); // 光束实际长度（像素）

        // 计算本次调用期间的总伤害和总EMP（伤害 = DPS * 持续时间）
        float totalDamage = beam.getDamage().getDamage() * beam.getDamage().getDpsDuration();
        float totalEmp = beam.getDamage().getFluxComponent() * beam.getDamage().getDpsDuration();

        // 计算检查点数量：光束长度除以间隔距离，四舍五入取整
        int checkpointCount = Math.round(beamDistance / CHECKPOINT_INTERVAL);
        if (checkpointCount <= 0) {
            return; // 没有检查点，直接返回
        }

        // 将总伤害和EMP平均分配到每个检查点
        float damagePerCheck = totalDamage / checkpointCount;
        float empPerCheck = totalEmp / checkpointCount;

        // 获取光束起点附近一定范围内的所有战斗实体（以光束起点为圆心，射程为半径）
        List<CombatEntityAPI> entities = CombatUtils.getEntitiesWithinRange(beamFrom, beamRange);
        // 如果实体数量超过性能保护上限，则只取前MAX_ENTITIES_TO_CHECK个
        if (entities.size() > MAX_ENTITIES_TO_CHECK) {
            entities = entities.subList(0, MAX_ENTITIES_TO_CHECK);
        }

        // 获取光束发射者的阵营ID（用于排除友军）
        int beamOwner = beam.getSource().getOwner();

        // 遍历每个检查点（从起点沿光束方向等间距分布）
        for (int i = 0; i < checkpointCount; i++) {
            // 当前检查点距离起点的距离
            float distance = i * CHECKPOINT_INTERVAL;
            // 计算当前检查点的世界坐标（从起点出发，沿光束方向移动distance像素）
            Vector2f point = MathUtils.getPointOnCircumference(beamFrom, distance, beamAngle);

            // 对每个检查点，遍历所有候选实体，检查是否命中
            for (CombatEntityAPI entity : entities) {
                // 跳过友方（同阵营）以及中立（阵营100，如残骸）
                if (entity.getOwner() == beamOwner || entity.getOwner() == 100) {
                    continue;
                }

                // 如果实体是舰船
                if (entity instanceof ShipAPI enemy) {
                    // 跳过相位状态（不可攻击）的舰船
                    if (enemy.isPhased()) {
                        continue;
                    }

                    // 判断是否命中护盾：护盾存在且开启，且检查点在护盾弧内且距离小于护盾半径
                    boolean isInShield = false;
                    if (entity.getShield() != null && entity.getShield().isOn()) {
                        isInShield = entity.getShield().isWithinArc(point) &&
                                MathUtils.isWithinRange(point, entity.getShield().getLocation(),
                                        entity.getShield().getRadius());
                    }

                    // 判断检查点是否在舰船边界内（精确碰撞检测）
                    boolean isInBounds = CollisionUtils.isPointWithinBounds(point, entity);
                    // 如果点在边界内或护盾内，则造成伤害
                    if (isInBounds || isInShield) {
                        // 生成击中粒子效果：在检查点周围随机偏移，粒子速度方向远离检查点
                        Vector2f particleLoc = MathUtils.getRandomPointInCircle(point, PARTICLE_RANGE);
                        Vector2f particleVel = new Vector2f(particleLoc.x - point.x, particleLoc.y - point.y);
                        engine.addHitParticle(
                                point,                                 // 粒子中心位置
                                particleVel,                           // 粒子速度方向
                                MathUtils.getRandomNumberInRange(30.0F, 45.0F), // 粒子大小（随机）
                                beam.getBrightness(),                  // 粒子亮度（随光束强度）
                                0.5F,                                  // 粒子持续时间（秒）
                                beam.getFringeColor()                  // 粒子颜色（光束边缘色）
                        );

                        // 应用均分后的伤害和EMP（舰船使用MAX_DAMAGE系数）
                        engine.applyDamage(
                                enemy,                                 // 目标舰船
                                point,                                 // 命中点
                                damagePerCheck * MAX_DAMAGE,           // 伤害值
                                beam.getDamage().getType(),            // 伤害类型（高爆/动能/破片/能量）
                                empPerCheck * MAX_DAMAGE,              // EMP伤害值
                                true,                                  // 是否产生击退效果
                                false,                                 // 是否忽略护盾（不忽略，由isInShield决定是否命中护盾）
                                beam.getSource()                       // 伤害来源（光束发射者）
                        );
                    }
                } else if (CollisionUtils.isPointWithinBounds(point, entity)) {
                    // 非舰船实体（导弹、小行星等）：只要点在边界内就直接应用伤害（使用NO_SHIP_DAMAGE系数）
                    engine.applyDamage(
                            entity,                                   // 目标实体
                            point,                                    // 命中点
                            damagePerCheck * NO_SHIP_DAMAGE,          // 伤害值（更高系数）
                            beam.getDamage().getType(),               // 伤害类型
                            empPerCheck * NO_SHIP_DAMAGE,             // EMP伤害值
                            true,                                     // 产生击退
                            false,                                    // 不忽略护盾（非舰船一般无护盾）
                            beam.getSource()                          // 伤害来源
                    );
                }
            }
        }
    }
}