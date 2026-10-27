package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.BeamEffectPlugin;
import com.fs.starfarer.api.combat.BoundsAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.EmpArcEntityAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.loading.MissileSpecAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List; // 新增导入
import org.lwjgl.util.vector.Vector2f;

import static com.fs.starfarer.api.impl.combat.RiftCascadeMineExplosion.SIZE_MULT_KEY;

/**
 * 裂谷级联光束效果插件（修改版）。
 * 当光束命中目标后，会在目标表面左右交替生成固定数量的地雷，
 * 地雷大小根据生成顺序变化，并伴有电弧视觉效果。
 */
public class VIII_XLriftcascadeBeamEffect implements BeamEffectPlugin {

    // ========== 静态配置参数 ==========
    /** 负粒子颜色（用于视觉特效） */
    public static Color NEGATIVE_SOURCE_COLOR = new Color(200, 255, 200, 25);
    /** 地雷武器ID */
    public static String RIFTCASCADE_MINELAYER = "riftcascade_minelayer";
    /** 地雷之间的间隔距离（沿目标表面弧长） */
    public static float SPAWN_SPACING = 175.0F;
    /** 地雷生成间隔时间（秒） */
    public static float SPAWN_INTERVAL = 0.05F;
    /** 固定地雷数量（不再动态计算） */
    public static int FIXED_MINE_COUNT = 10; // 固定生成10个地雷

    // ========== 实例状态变量 ==========
    /** 左侧电弧起点（用于左向环绕） */
    protected Vector2f arcFromLeft = null;
    /** 右侧电弧起点（用于右向环绕） */
    protected Vector2f arcFromRight = null;
    /** 左侧已生成地雷数量 */
    protected int spawnedLeft = 0;
    /** 右侧已生成地雷数量 */
    protected int spawnedRight = 0;
    /** 需要生成的总地雷数量（固定为 FIXED_MINE_COUNT） */
    protected int totalToSpawn = 0;
    /** 标记当前轮到左侧生成（true）还是右侧生成（false） */
    protected boolean leftTurn = true;
    /** 距离下一次生成地雷的剩余时间 */
    protected float untilNextSpawn = 0.0F;
    /** 标记是否已完成所有地雷的生成 */
    protected boolean doneSpawningMines = false;
    /** 已生成地雷总数（spawnedLeft + spawnedRight） */
    protected float spawned = 0.0F;
    /** 上一个生成的地雷位置（用于可能的位置调整，当前未使用） */
    protected Vector2f prevMineLoc = null;
    /** 定时器，用于周期性生成负粒子效果（每0.05秒一次） */
    protected IntervalUtil tracker = new IntervalUtil(0.05F, 0.05F);

    @Override
    public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {
        // 每帧更新：生成负粒子效果（根据定时器）
        tracker.advance(amount);
//        if (tracker.intervalElapsed()) {
//            spawnNegativeParticles(engine, beam);
//        }

        // 只有当光束亮度足够（即光束正在有效发射）时才处理地雷生成逻辑
        if (!(beam.getBrightness() < 1.0F)) {
            if (!doneSpawningMines) {
                // 如果光束有目标（即命中了某实体）
                if (beam.getDamageTarget() != null) {
                    // 首次命中时初始化
                    if (totalToSpawn <= 0) {
                        totalToSpawn = FIXED_MINE_COUNT;                 // 设置固定数量
                        arcFromLeft = new Vector2f(beam.getRayEndPrevFrame());  // 左右起点均从命中点开始
                        arcFromRight = new Vector2f(beam.getRayEndPrevFrame());
                        untilNextSpawn = 0.0F; // 立即开始生成
                        leftTurn = true;        // 从左侧开始
                        spawnedLeft = 0;
                        spawnedRight = 0;
                    }

                    untilNextSpawn -= amount;                             // 减少等待时间
                    // 循环处理：可能一次性生成多个地雷（如果 untilNextSpawn 减到负值）
                    while (untilNextSpawn <= 0.0F && !doneSpawningMines) {
                        boolean isLeft = leftTurn;
                        Vector2f startPoint = isLeft ? arcFromLeft : arcFromRight;
                        float dir = isLeft ? 1.0F : -1.0F;

                        // 计算下一个电弧落点
                        Vector2f nextLoc;
                        if (spawned == 0) {
                            // 第一个地雷直接使用光束终点
                            nextLoc = new Vector2f(beam.getRayEndPrevFrame());
                        } else {
                            nextLoc = getNextArcLoc(beam, dir, startPoint);
                        }

                        // 生成EMP电弧视觉效果（从起点到下一个点）
                        float thickness = beam.getWidth();
                        float dist = Misc.getDistance(startPoint, nextLoc);
                        if (dist < SPAWN_SPACING * 2.0F) {
                            EmpArcEntityAPI arc = engine.spawnEmpArcVisual(
                                    startPoint, null, nextLoc, null,
                                    thickness, beam.getFringeColor(), Color.white);
                            arc.setCoreWidthOverride(Math.max(20.0F, thickness * 0.67F));
                        }

                        // 生成地雷
                        spawnMine(beam.getSource(), nextLoc);

                        // 更新对应方向的起点
                        if (isLeft) {
                            arcFromLeft = nextLoc;
                            spawnedLeft++;
                        } else {
                            arcFromRight = nextLoc;
                            spawnedRight++;
                        }
                        spawned = spawnedLeft + spawnedRight;

                        leftTurn = !leftTurn;

                        if (spawned >= totalToSpawn) {
                            doneSpawningMines = true;
                        }

                        untilNextSpawn += SPAWN_INTERVAL;
                    }
                }
            }
        }
    }

    /**
     * 计算下一个电弧落点，给定起始点和方向。
     * 该点位于目标表面（或护盾表面），通过绕目标中心旋转一定角度得到。
     * @param beam 光束对象
     * @param dir 方向：1 表示顺时针（左），-1 表示逆时针（右）
     * @param from 起始点（世界坐标）
     * @return 下一个点的世界坐标
     */
    public Vector2f getNextArcLoc(BeamAPI beam, float dir, Vector2f from) {
        CombatEntityAPI target = beam.getDamageTarget();

        // 如果目标是舰船且是模块（有母舰）
        if (target instanceof ShipAPI ship) {
            ShipAPI station = ship.getParentStation();
            if (station != null) {
                List<ShipAPI> modules = station.getChildModulesCopy();
                if (modules != null && !modules.isEmpty()) {
                    // 过滤出非残骸模块
                    List<ShipAPI> aliveModules = new ArrayList<>();
                    for (ShipAPI mod : modules) {
                        if (!mod.isHulk()) {
                            aliveModules.add(mod);
                        }
                    }

                    // 判断母舰是否属于友方（与光束发射者同阵营）
                    int sourceOwner = beam.getSource().getOwner();
                    if (station.getOwner() == sourceOwner) {
                        // 友方母舰：寻找最近的敌方单位
                        CombatEngineAPI engine = Global.getCombatEngine();
                        ShipAPI nearestEnemy = null;
                        float minDist = Float.MAX_VALUE;
                        Vector2f currentLoc = ship.getLocation(); // 当前模块位置作为参考点

                        // 遍历所有舰船（包括模块）
                        for (ShipAPI otherShip : engine.getShips()) {
                            if (otherShip.isHulk()) continue;               // 排除残骸
                            if (otherShip.getOwner() == sourceOwner) continue; // 排除友方
                            if (otherShip.getOwner() >= 100) continue;      // 排除中立
                            float dist = Misc.getDistance(currentLoc, otherShip.getLocation());
                            if (dist < minDist) {
                                minDist = dist;
                                nearestEnemy = otherShip;
                            }
                        }

                        // 如果找到了最近的敌方，返回其中心位置
                        if (nearestEnemy != null) {
                            return new Vector2f(nearestEnemy.getLocation());
                        }
                        // 没有敌方时，回退到原逻辑的随机模块选择
                    }

                    // 原逻辑：随机选择一个非残骸模块
                    if (!aliveModules.isEmpty()) {
                        int idx = (int) (Math.random() * aliveModules.size());
                        return new Vector2f(aliveModules.get(idx).getLocation());
                    } else {
                        // 所有模块都是残骸，返回母舰中心
                        return new Vector2f(station.getLocation());
                    }
                } else {
                    // 没有子模块，使用母舰中心
                    return new Vector2f(station.getLocation());
                }
            }
        }

        // 以下为原逻辑（非模块目标或没有母舰的情况）
        Vector2f targetLoc = target.getLocation();
        float targetRadius = target.getCollisionRadius();

        boolean hitShield = target.getShield() != null && target.getShield().isWithinArc(beam.getRayEndPrevFrame());
        float perSpawn = SPAWN_SPACING;
        if (hitShield) {
            perSpawn *= 0.67F;
        }

        float prevAngle = Misc.getAngleInDegrees(targetLoc, from);
        float anglePerSegment = 360.0F * perSpawn / (6.28F * targetRadius);
        if (anglePerSegment > 90.0F) {
            anglePerSegment = 90.0F;
        }

        float angle = prevAngle + anglePerSegment * dir;
        Vector2f arcTo = Misc.getUnitVectorAtDegreeAngle(angle);
        arcTo.scale(targetRadius);
        Vector2f.add(targetLoc, arcTo, arcTo);

        float actualRadius = Global.getSettings().getTargetingRadius(arcTo, target, hitShield);

        actualRadius += 30.0F + 50.0F * (float) Math.random();

        arcTo = Misc.getUnitVectorAtDegreeAngle(angle);
        arcTo.scale(actualRadius);
        Vector2f.add(targetLoc, arcTo, arcTo);

        if (target instanceof ShipAPI ship && !hitShield) {
            BoundsAPI bounds = ship.getExactBounds();
            if (bounds != null) {
                Vector2f best = null;
                float bestDist = Float.MAX_VALUE;
                for (BoundsAPI.SegmentAPI segment : bounds.getSegments()) {
                    float test = Misc.getDistance(segment.getP1(), arcTo);
                    if (test < bestDist) {
                        bestDist = test;
                        best = segment.getP1();
                    }
                }
                if (best != null) {
                    Object o = Global.getSettings().getWeaponSpec(RIFTCASCADE_MINELAYER).getProjectileSpec();
                    if (o instanceof MissileSpecAPI spec) {
                        float explosionRadius = (float) spec.getBehaviorJSON()
                                .optJSONObject("explosionSpec").optDouble("coreRadius", 100.0F);
                        float sizeMult = getSizeMult();
                        explosionRadius *= sizeMult;
                        Vector2f dirVec = Misc.getUnitVectorAtDegreeAngle(Misc.getAngleInDegrees(best, arcTo));
                        dirVec.scale(explosionRadius * 0.3F);
                        Vector2f.add(best, dirVec, dirVec);
                        arcTo = dirVec;
                    }
                }
            }
        }

        return arcTo;
    }

    /**
     * 根据地雷生成顺序计算当前地雷的大小倍数。
     * 越早生成的地雷越大，越晚越小，但公式可能导致倍数在 3.0 到 3.5 之间（可能是个bug）。
     * @return 大小倍数
     */
    public float getSizeMult() {
        // 原意图：sizeMult = 1.0F - spawned / (totalToSpawn - 1)  范围 0~1
        // 然后 sizeMult = 3F + (1.0F - sizeMult) * 0.5F  结果在 3.0 ~ 3.5 之间
        // 可能原意是 0.75F + (1.0F - sizeMult) * 0.5F 之类的，但此处保留原始代码
        float sizeMult = 1.0F - spawned / (float) Math.max(1, totalToSpawn - 1);
        sizeMult = 3F + (1.0F - sizeMult) * 0.5F;
        return sizeMult;
    }

    /**
     * 在指定位置生成一个地雷（Rift Cascade Mine）。
     * @param source  发射源舰船
     * @param mineLoc 地雷生成位置（世界坐标）
     */
    public void spawnMine(ShipAPI source, Vector2f mineLoc) {
        CombatEngineAPI engine = Global.getCombatEngine();
        // 生成地雷投射体
        MissileAPI mine = (MissileAPI) engine.spawnProjectile(
                source, null, RIFTCASCADE_MINELAYER,
                mineLoc, (float) Math.random() * 360.0F, null);
        float sizeMult = getSizeMult();
        mine.setCustomData(SIZE_MULT_KEY, sizeMult);                     // 存储大小倍数，供爆炸时使用
        if (source != null) {
            // 应用源舰船的伤害修正（如技能、船体插件）
            Global.getCombatEngine().applyDamageModifiersToSpawnedProjectileWithNullWeapon(
                    source, WeaponType.ENERGY, false, mine.getDamage());
        }
        mine.getDamage().getModifier().modifyMult("mine_sizeMult", sizeMult);
        float fadeInTime = 0.05F;
        mine.getVelocity().scale(0.0F);                                   // 初始速度为0
        mine.fadeOutThenIn(fadeInTime);                                   // 淡入淡出效果
        float liveTime = 0.0F;
        mine.setFlightTime(mine.getMaxFlightTime() - liveTime);          // 设置剩余飞行时间（满时间）
        mine.addDamagedAlready(source);                                   // 防止伤害源自身
        mine.setNoMineFFConcerns(true);                                   // 不触发友军误伤
        prevMineLoc = mineLoc;                                            // 记录当前位置（未使用但保留）
    }

    /**
     * 生成负粒子效果（在光束周围产生一些漂浮粒子）。
     * @param engine 战斗引擎
     * @param beam   光束对象
     */
    public void spawnNegativeParticles(CombatEngineAPI engine, BeamAPI beam) {
        float length = beam.getLengthPrevFrame();
        if (length <= 10.0F) return; // 光束太短时不生成

        Vector2f from = beam.getFrom();
        Vector2f to = beam.getRayEndPrevFrame();
        ShipAPI ship = beam.getSource();
        float angle = Misc.getAngleInDegrees(from, to);
        // 计算方向向量但未使用（可能原意用于粒子方向，但此处未使用）
        Misc.getUnitVectorAtDegreeAngle(angle);
        Color color = NEGATIVE_SOURCE_COLOR;
        float sizeMult = 1.0F;
        sizeMult = 0.67F; // 固定为0.67，覆盖了之前的赋值

        for (int i = 0; i < 3; ++i) {
            float rampUp = 0.25F + 0.25F * (float) Math.random(); // 渐变上升时间比例
            float dur = 1.0F + (float) Math.random();               // 持续时间
            float size = 200.0F + 50.0F * (float) Math.random();   // 粒子大小
            size *= sizeMult;
            // 在武器位置周围随机偏移生成粒子
            Vector2f loc = Misc.getPointAtRadius(beam.getWeapon().getLocation(), size * 0.33F);
            engine.addNegativeParticle(loc, ship.getVelocity(), size, rampUp / dur, dur, color);
        }
    }
}