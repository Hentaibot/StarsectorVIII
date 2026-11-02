package data.scripts.weapons.VIII_RealityRipper;

import com.fs.starfarer.api.combat.*;
import data.scripts.util.VIII_BoxBasedUtil;
import org.boxutil.manager.CombatRenderingManager;
import org.boxutil.units.standard.entity.FlareEntity;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * VIII_RealityRipper 武器特效插件。
 * 负责管理武器动画、开火时的光晕效果以及冷却期间的粒子火花。
 * 光晕在武器充能超过80%且不在冷却时持续生成（内圈白、外圈蓝），并逐帧跟随枪口位置与朝向。
 * 冷却期间的粒子分为两阶段：
 *   - 第一阶段（冷却时间的 20%~100%）：生成粒子 7~10
 *   - 第二阶段（冷却时间的 30%~100%）：生成粒子 1~6
 * 粒子位置基于武器本地偏移并随武器朝向旋转，实现跟随效果。
 */
public class VIII_RealityRipper_Effect implements EveryFrameWeaponEffectPlugin {

    // ========================= 动画阶段比例 =========================
    // 动画阶段占冷却时间的比例（相对于总冷却时间）
    private static final float HOLD_RATIO = 0.2f;   // 开火结束后前 20% 冷却时间保持第20帧
    private static final float ANIM_RATIO = 0.2f;   // 接下来的 20% 冷却时间播放从21到41帧的动画
    // 剩余 60% 冷却时间保持第0帧（暂停）

    // 粒子生成延迟比例：开火结束后经过冷却时间的 30% 才开始生成第一批粒子
    private static final float PARTICLE_START_RATIO = 0.3f;

    // ========================= 光晕参数 =========================
    // 光晕朝向相对于舰船的固定偏移（度）
    static final float Facing = 90f;

    // ========================= 粒子效果参数 =========================
    // 所有粒子参数均基于武器本地坐标系，distX/distY 为本地偏移，velAngleOffset 为速度方向相对于武器角度的偏移

    // 粒子1（原fire2）参数
    private static final float PARTICLE1_DIST_X = -58f;                // 本地X方向偏移（像素）
    private static final float PARTICLE1_DIST_Y = 62f;                 // 本地Y方向偏移（像素）
    private static final float PARTICLE1_VEL_ANGLE_OFFSET = 50f;       // 速度方向相对于武器角度的偏移（度）

    // 粒子2（原fire1）参数
    private static final float PARTICLE2_DIST_X = -58f;
    private static final float PARTICLE2_DIST_Y = -62f;
    private static final float PARTICLE2_VEL_ANGLE_OFFSET = -50f;

    // 粒子3
    private static final float PARTICLE3_DIST_X = -62f;
    private static final float PARTICLE3_DIST_Y = 64f;
    private static final float PARTICLE3_VEL_ANGLE_OFFSET = 65f;

    // 粒子4
    private static final float PARTICLE4_DIST_X = -62f;
    private static final float PARTICLE4_DIST_Y = -64f;
    private static final float PARTICLE4_VEL_ANGLE_OFFSET = -65f;

    // 粒子5
    private static final float PARTICLE5_DIST_X = -65f;
    private static final float PARTICLE5_DIST_Y = 65f;
    private static final float PARTICLE5_VEL_ANGLE_OFFSET = 80f;

    // 粒子6
    private static final float PARTICLE6_DIST_X = -65f;
    private static final float PARTICLE6_DIST_Y = -65f;
    private static final float PARTICLE6_VEL_ANGLE_OFFSET = -80f;

    // 粒子7（第一组远距离粒子）
    private static final float PARTICLE7_DIST_X = -162f;
    private static final float PARTICLE7_DIST_Y = 64f;
    private static final float PARTICLE7_VEL_ANGLE_OFFSET = 65f;

    // 粒子8
    private static final float PARTICLE8_DIST_X = -162f;
    private static final float PARTICLE8_DIST_Y = -64f;
    private static final float PARTICLE8_VEL_ANGLE_OFFSET = -65f;

    // 粒子9
    private static final float PARTICLE9_DIST_X = -162f;
    private static final float PARTICLE9_DIST_Y = 64f;
    private static final float PARTICLE9_VEL_ANGLE_OFFSET = 120f;

    // 粒子10
    private static final float PARTICLE10_DIST_X = -162f;
    private static final float PARTICLE10_DIST_Y = -64f;
    private static final float PARTICLE10_VEL_ANGLE_OFFSET = -120f;

    // 速度随机范围参数
    private static final float VELOCITY_RANDOM_BASE_MIN = 10f;          // 基础速度随机最小值
    private static final float VELOCITY_RANDOM_BASE_MAX = 80f;          // 基础速度随机最大值
    private static final float VELOCITY_RANDOM_ADD_MIN = -20f;          // 额外随机增量最小值
    private static final float VELOCITY_RANDOM_ADD_MAX = 50f;           // 额外随机增量最大值

    // 粒子通用属性
    private static final float PARTICLE_SIZE = 5f;                      // 粒子大小（像素）
    private static final float PARTICLE_BRIGHTNESS = 1f;                // 粒子亮度
    private static final float PARTICLE_DURATION = 1f;                  // 粒子持续时间（秒）

    // ========================= 持续光晕参数 =========================
    /** 光晕淡入时间（秒） */
    private static final float FLARE_FADE_IN = 0.15f;
    /** 光晕保持时间（秒） */
    private static final float FLARE_FULL = 0.3f;
    /** 光晕淡出时间（秒） */
    private static final float FLARE_FADE_OUT = 0.1f;
    /** 单个光晕总生命周期（秒），到点后主动移除实体 */
    private static final float FLARE_TOTAL_LIFE = FLARE_FADE_IN + FLARE_FULL + FLARE_FADE_OUT;
    /** 持续生成的间隔（秒）：每隔这么久生成一组光晕 */
    private static final float FLARE_SPAWN_INTERVAL = 0.08f;
    /** 开始生成光晕所需的最低充能等级 */
    private static final float FLARE_CHARGE_THRESHOLD = 0.8f;
    /** 场上同时存在的光晕数量上限（兜底，正常不会触发） */
    private static final int MAX_ACTIVE_FLARES = 18;

    /** 蓝色光晕基础尺寸（世界单位） */
    private static final float BLUE_FLARE_WIDTH = 3000f;
    private static final float BLUE_FLARE_HEIGHT = 100f;
    /**
     * 正圆小型光晕的直径（世界单位）。
     * 这个值是留给手动调整的：改大就更明显，改小就更含蓄。
     */
    private static final float ROUND_FLARE_SIZE = 250f;

    /** 光晕辉光强度（越大边缘越亮、衰减越远） */
    private static final float FLARE_GLOW_POWER = 0.3f;
    /** 光晕噪点强度（0 表示完全光滑） */
    private static final float FLARE_NOISE_POWER = 0.1f;

    /** 光晕内圈颜色：纯白 */
    private static final Color FLARE_CORE_COLOR = new Color(255, 255, 255, 255);
    /** 光晕外圈颜色：蓝色 */
    private static final Color FLARE_FRINGE_COLOR = new Color(0, 0, 255, 205);

    // ========================= 光晕实体引用 =========================
    // 持续生成的光晕实体列表，每帧更新位置与朝向
    private final List<ActiveFlare> activeFlares = new ArrayList<ActiveFlare>();
    // 距离下一次生成的时间累计
    private float flareSpawnTimer = 0f;

    // ========================= 公共方法 =========================

    /**
     * 每帧调用，更新武器特效。
     *
     * @param amount 时间增量（秒）
     * @param engine 战斗引擎
     * @param weapon 当前武器
     */
    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 游戏暂停或武器无动画时跳过
        if (engine.isPaused() || weapon.getAnimation() == null) return;

        float chargeLevel = weapon.getChargeLevel();                // 当前充能等级 (0~1)
        float cooldownRemaining = weapon.getCooldownRemaining();   // 剩余冷却时间
        float totalCooldown = weapon.getCooldown();                 // 总冷却时间

        // ==================== 开火光晕处理 ====================
        // 持续生成光晕并持续更新位置与朝向；武器进入冷却后停止生成
        updateFlares(amount, weapon, chargeLevel, cooldownRemaining);

        // ==================== 武器动画帧管理 ====================
        if (cooldownRemaining > 0 && totalCooldown > 0) {
            float elapsedCooldown = totalCooldown - cooldownRemaining; // 已经过冷却时间
            float phase1End = totalCooldown * HOLD_RATIO;               // 第一阶段结束时间（保持第20帧）
            float phase2End = totalCooldown * (HOLD_RATIO + ANIM_RATIO); // 第二阶段结束时间（动画结束）

            if (elapsedCooldown <= phase1End) {
                // 冷却前 HOLD_RATIO 比例的时间：保持第20帧（炮口准备状态）
                weapon.getAnimation().setFrame(20);
            } else if (elapsedCooldown <= phase2End) {
                // 接下来的 ANIM_RATIO 比例时间：播放21~41帧动画（能量释放动画）
                float progress = (elapsedCooldown - phase1End) / (totalCooldown * ANIM_RATIO);
                progress = Math.min(1.0f, Math.max(0.0f, progress));
                int frame = 21 + (int) (progress * 21); // 21到41帧
                weapon.getAnimation().setFrame(frame);
            } else {
                // 剩余时间保持第0帧（静止状态）
                weapon.getAnimation().setFrame(0);
            }
        } else {
            // 不在冷却时，根据充能等级设置帧数（0~20）
            int frame = (int) (chargeLevel * 20);
            if (frame > 20) frame = 20;
            weapon.getAnimation().setFrame(frame);
        }

        // ==================== 冷却期间的粒子效果 ====================
        if (cooldownRemaining > 0 && totalCooldown > 0) {
            float elapsedCooldown = totalCooldown - cooldownRemaining; // 已经过冷却时间
            float weaponAngle = weapon.getCurrAngle();                 // 武器当前角度
            Vector2f ww = weapon.getLocation();                        // 武器世界坐标

            // 第一组粒子（1~6）：在冷却时间的后70%生成（即经过前30%后开始）
            if (elapsedCooldown > totalCooldown * PARTICLE_START_RATIO) {
                spawnParticle(engine, weaponAngle, ww,
                        PARTICLE1_DIST_X, PARTICLE1_DIST_Y,
                        PARTICLE1_VEL_ANGLE_OFFSET);
                spawnParticle(engine, weaponAngle, ww,
                        PARTICLE2_DIST_X, PARTICLE2_DIST_Y,
                        PARTICLE2_VEL_ANGLE_OFFSET);
                spawnParticle(engine, weaponAngle, ww,
                        PARTICLE3_DIST_X, PARTICLE3_DIST_Y,
                        PARTICLE3_VEL_ANGLE_OFFSET);
                spawnParticle(engine, weaponAngle, ww,
                        PARTICLE4_DIST_X, PARTICLE4_DIST_Y,
                        PARTICLE4_VEL_ANGLE_OFFSET);
                spawnParticle(engine, weaponAngle, ww,
                        PARTICLE5_DIST_X, PARTICLE5_DIST_Y,
                        PARTICLE5_VEL_ANGLE_OFFSET);
                spawnParticle(engine, weaponAngle, ww,
                        PARTICLE6_DIST_X, PARTICLE6_DIST_Y,
                        PARTICLE6_VEL_ANGLE_OFFSET);
            }

            // 第二组粒子（7~10）：在冷却时间的后80%生成（即经过前20%后开始），持续到冷却结束
            // 注意：该条件独立于上一组，因此两组粒子在时间上会有重叠（0.3~1.0 同时存在）
            if (elapsedCooldown > totalCooldown * ANIM_RATIO) {
                spawnParticle(engine, weaponAngle, ww,
                        PARTICLE7_DIST_X, PARTICLE7_DIST_Y,
                        PARTICLE7_VEL_ANGLE_OFFSET);
                spawnParticle(engine, weaponAngle, ww,
                        PARTICLE8_DIST_X, PARTICLE8_DIST_Y,
                        PARTICLE8_VEL_ANGLE_OFFSET);
                spawnParticle(engine, weaponAngle, ww,
                        PARTICLE9_DIST_X, PARTICLE9_DIST_Y,
                        PARTICLE9_VEL_ANGLE_OFFSET);
                spawnParticle(engine, weaponAngle, ww,
                        PARTICLE10_DIST_X, PARTICLE10_DIST_Y,
                        PARTICLE10_VEL_ANGLE_OFFSET);
            }
        }
    }

    /**
     * 持续生成并更新开火光晕。
     * 每帧把所有仍存活的光晕移动到当前枪口位置，并同步朝向（舰船朝向 + Facing），
     * 所以光晕始终贴着枪口、并随舰船转向一起转。
     * 武器进入冷却或充能不足时不再生成新光晕，已有光晕按 0.15 / 0.3 / 0.1 的生命周期自然淡出。
     *
     * @param amount            时间增量（秒）
     * @param weapon            当前武器
     * @param chargeLevel       当前充能等级
     * @param cooldownRemaining 剩余冷却时间
     */
    private void updateFlares(float amount, WeaponAPI weapon, float chargeLevel, float cooldownRemaining) {
        ShipAPI ship = weapon.getShip();
        float facing = (ship == null) ? 0f : ship.getFacing() + Facing;
        Vector2f location = weapon.getLocation();

        // 先更新所有存活光晕：位置与朝向持续跟随枪口 / 舰船
        Iterator<ActiveFlare> iter = activeFlares.iterator();
        while (iter.hasNext()) {
            ActiveFlare flare = iter.next();
            flare.age += amount;
            if (flare.entity == null || flare.entity.hasDelete() || flare.age >= FLARE_TOTAL_LIFE) {
                // 生命周期结束：主动移除，避免实体在空中累积
                if (flare.entity != null && !flare.entity.hasDelete()) {
                    CombatRenderingManager.removeEntity(flare.entity);
                }
                iter.remove();
                continue;
            }
            flare.entity.setLocation(location);
            flare.entity.setFacingScale(facing, 1f, 1f);
        }

        // 冷却中（或充能不足）不再生成新光晕
        if (cooldownRemaining > 0f || chargeLevel <= FLARE_CHARGE_THRESHOLD) {
            flareSpawnTimer = 0f;
            return;
        }

        // 持续生成：按固定间隔成组生成（蓝色光晕 + 正圆小光晕）
        flareSpawnTimer += amount;
        while (flareSpawnTimer >= FLARE_SPAWN_INTERVAL) {
            flareSpawnTimer -= FLARE_SPAWN_INTERVAL;
            spawnFlare(location, facing, randomScale(BLUE_FLARE_WIDTH, BLUE_FLARE_HEIGHT), false);
            float round = ROUND_FLARE_SIZE * randomMultiplier();
            spawnFlare(location, facing, new Vector2f(round, round), true);
        }

        // 兜底：万一底层没有回收，强制丢弃最旧的实体，防止越积越多
        while (activeFlares.size() > MAX_ACTIVE_FLARES) {
            ActiveFlare oldest = activeFlares.remove(0);
            if (oldest.entity != null && !oldest.entity.hasDelete()) {
                CombatRenderingManager.removeEntity(oldest.entity);
            }
        }
    }

    /**
     * 生成一个跟着枪口走的光晕实体，并记录到 activeFlares 里参与逐帧更新。
     * 实体由 {@link VIII_BoxBasedUtil#createSharpFlare} 创建（不自动入队），
     * 这里手动 addEntity，方便后续逐帧更新位置与朝向、到点后再移除。
     *
     * @param location 生成位置（世界坐标）
     * @param facing   朝向（度）
     * @param scale    尺寸（世界单位）
     * @param round    是否强制为正圆
     */
    private void spawnFlare(Vector2f location, float facing, Vector2f scale, boolean round) {
        FlareEntity flare = VIII_BoxBasedUtil.createSharpFlare(location, facing, scale.x, scale.y,
                FLARE_CORE_COLOR, FLARE_FRINGE_COLOR,
                FLARE_FADE_IN, FLARE_FULL, FLARE_FADE_OUT,
                FLARE_GLOW_POWER, FLARE_NOISE_POWER);
        if (flare == null) return;
        if (round) {
            // aspect=1 关掉 autoAspect 的纵向压缩；discRatio=2 把内圈还原成正圆
            flare.setAspect(1.0f);
            flare.setDiscRatio(2.0f);
        }
        CombatRenderingManager.addEntity(flare);
        activeFlares.add(new ActiveFlare(flare));
    }

    /** 随机尺寸倍率，避免所有光晕完全重合、看起来死板。 */
    private static float randomMultiplier() {
        return 0.85f + (float) Math.random() * 0.3f;
    }

    /** 按基础宽高生成带随机抖动的尺寸。 */
    private static Vector2f randomScale(float baseWidth, float baseHeight) {
        float mult = randomMultiplier();
        return new Vector2f(baseWidth * mult, baseHeight * mult);
    }

    /** 一个仍在生命周期内的光晕实体及其存活时间。 */
    private static final class ActiveFlare {
        private final FlareEntity entity;
        private float age;

        private ActiveFlare(FlareEntity entity) {
            this.entity = entity;
        }
    }

    /**
     * 生成单个粒子（火花）。
     * 位置基于武器本地偏移 (distX, distY) 经武器朝向旋转得到，实现粒子跟随武器移动和旋转。
     * 速度方向基于武器角度叠加 velAngleOffset，并带有随机大小。
     *
     * @param engine         战斗引擎
     * @param weaponAngle    武器当前角度（度）
     * @param weaponLoc      武器世界坐标
     * @param distX          本地 X 方向偏移（像素）
     * @param distY          本地 Y 方向偏移（像素）
     * @param velAngleOffset 速度方向相对于武器角度的偏移（度）
     */
    private void spawnParticle(CombatEngineAPI engine, float weaponAngle, Vector2f weaponLoc,
                               float distX, float distY,
                               float velAngleOffset) {
        // 将武器角度转换为弧度，计算旋转矩阵
        float rad = (float) Math.toRadians(weaponAngle);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);

        // 将本地偏移 (distX, distY) 旋转到世界坐标系
        float worldOffsetX = distX * cos - distY * sin;
        float worldOffsetY = distX * sin + distY * cos;

        // 粒子世界位置 = 武器位置 + 旋转后的偏移
        Vector2f pos = new Vector2f(weaponLoc.x + worldOffsetX, weaponLoc.y + worldOffsetY);

        // 计算速度方向（世界角度）
        float velRad = (float) Math.toRadians(weaponAngle + velAngleOffset);
        float velDirX = (float) Math.cos(velRad);
        float velDirY = (float) Math.sin(velRad);

        // 随机速度大小（基于基础范围 + 额外随机增量）
        float o = (float) Math.random() * (VELOCITY_RANDOM_BASE_MAX - VELOCITY_RANDOM_BASE_MIN) + VELOCITY_RANDOM_BASE_MIN;
        float a = o + (float) Math.random() * (VELOCITY_RANDOM_ADD_MAX - VELOCITY_RANDOM_ADD_MIN) + VELOCITY_RANDOM_ADD_MIN;
        float b = o + (float) Math.random() * (VELOCITY_RANDOM_ADD_MAX - VELOCITY_RANDOM_ADD_MIN) + VELOCITY_RANDOM_ADD_MIN;

        // 合成速度向量
        Vector2f velocity = new Vector2f(velDirX * a, velDirY * b);

        // 添加粒子到引擎（hit粒子，不产生伤害，仅视觉效果）
        engine.addHitParticle(pos, velocity, PARTICLE_SIZE, PARTICLE_BRIGHTNESS, PARTICLE_DURATION,
                new Color(25, 100, 155, 255)); // 蓝色调
    }
}