package data.scripts.weapons.VIII_RealityRipper;

import com.fs.starfarer.api.combat.*;
import org.boxutil.manager.CombatRenderingManager;
import org.boxutil.units.standard.attribute.Instance2Data;
import org.boxutil.units.standard.entity.FlareEntity;
import org.lwjgl.util.vector.Vector2f;
import org.lwjgl.util.vector.Vector4f;

import java.awt.Color;

import static org.boxutil.util.RenderingUtil.addCombatFlareField;

/**
 * VIII_RealityRipper 武器特效插件。
 * 负责管理武器动画、开火时的光晕效果以及冷却期间的粒子火花。
 * 光晕在武器充能超过80%且不在冷却时出现，并随武器移动。
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

    // ========================= 光晕实体引用 =========================
    // 用于复用光晕实体，避免每帧重复创建
    private FlareEntity blueFlare = null;    // 蓝色光晕
    private FlareEntity whiteFlare = null;   // 白色光晕

    // ========================= 公共方法 =========================

    /**
     * 创建单个光晕实体。
     * 此方法存在已知问题：创建了一个未初始化的 FlareEntity 并添加到渲染管理器，但实际返回的是 addCombatFlareField 生成的实体。
     * 为保持兼容性暂不修改，但可能导致多余实体。
     *
     * @param location    光晕位置（世界坐标）
     * @param facing      光晕朝向（度）
     * @param coreColor   核心颜色
     * @param fringeColor 边缘颜色
     * @param fadeIn      淡入时间（秒）
     * @param full        保持时间（秒）
     * @param fadeOut     淡出时间（秒）
     * @return 通过 addCombatFlareField 生成的光晕实体
     */
    public static FlareEntity createSingleFlare(Vector2f location, float facing,
                                                Color coreColor, Color fringeColor,
                                                float fadeIn, float full, float fadeOut) {
        // 创建一个空的光晕实体（未设置属性）
        FlareEntity entity = new FlareEntity();
        // 定义光晕的大小范围（四个分量：最小宽度、最大宽度、最小高度、最大高度）
        Vector4f sizeRange = new Vector4f(300f, 30f, 256f, 10f);

        // 创建一个实例数据对象（此处未实际使用，可能为预留）
        Instance2Data data = new Instance2Data();
        data.setScale(300f, 30f);
        // 将空实体添加到渲染管理器（可能导致无效实体）
        CombatRenderingManager.addEntity(entity);

        // 调用 BoxUtil 的工具方法生成光晕场，并返回其中的一个实体
        return addCombatFlareField(
                location,                     // 光晕位置
                1,                            // 数量（单个）
                facing,                       // 朝向
                0f,                           // 角度范围（0表示固定朝向）
                null,                         // 散布范围（无）
                null,                         // 朝向范围（固定）
                sizeRange,                    // 大小范围
                fringeColor,                   // 边缘颜色
                null,                          // 边缘颜色变化（固定）
                coreColor,                     // 核心颜色
                null,                          // 核心颜色变化（固定）
                true,                          // 启用闪烁
                false,                         // 非同步闪烁
                fadeIn, full, fadeOut,          // 生命周期
                0f,                            // 时间偏移范围
                true,                          // 加法混合
                CombatEngineLayers.ABOVE_PARTICLES // 渲染层级
        ).one; // 返回生成的第一个光晕实体
    }

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
        // 充能 > 80% 且不在冷却时生成/更新光晕
        if (chargeLevel > 0.8f && cooldownRemaining <= 0f) {
            float chargeTime = weapon.getSpec().getChargeTime() * 0.2f; // 充能时间的20%作为淡入时间
            float burstRemaining = weapon.getBurstFireTimeRemaining(); // 剩余连发时间（保持时间）
            float fadeOut = totalCooldown * 0.1f;                       // 总冷却时间的10%作为淡出时间

            float shipFacing = 0f;
            ShipAPI ship = weapon.getShip();
            if (ship != null) {
                shipFacing = ship.getFacing() + Facing; // 光晕朝向 = 舰船朝向 + 固定偏移
            }

            Vector2f weaponLoc = weapon.getLocation();

            // 蓝色光晕（核心）
            if (blueFlare != null && !blueFlare.hasDelete()) {
                // 复用已有光晕，更新位置、朝向和缩放
                blueFlare.setStateVanilla(weaponLoc, shipFacing, new Vector2f(9f, 7f));
            } else {
                blueFlare = createSingleFlare(weaponLoc, shipFacing,
                        new Color(0, 0, 255, 255),   // 核心纯蓝不透明
                        new Color(0, 0, 255, 128),   // 边缘半透明蓝
                        chargeTime, burstRemaining, fadeOut);
                blueFlare.setSmoothDisc();            // 设置为平滑圆盘形状
                blueFlare.setFlickerSyncCode(1);      // 闪烁同步码
                blueFlare.setSyncFlick(true);         // 启用同步闪烁
            }

            // 白色光晕（外层）
            if (whiteFlare != null && !whiteFlare.hasDelete()) {
                whiteFlare.setStateVanilla(weaponLoc, shipFacing, new Vector2f(4f, 2f));
            } else {
                whiteFlare = createSingleFlare(weaponLoc, shipFacing,
                        new Color(255, 255, 255, 255), // 核心纯白不透明
                        new Color(255, 255, 255, 128), // 边缘半透明白
                        chargeTime, burstRemaining, fadeOut);
                whiteFlare.setSmoothDisc();
                whiteFlare.setFlickerSyncCode(1);
                whiteFlare.setSyncFlick(true);
            }
        } else {
            // 不满足光晕条件时清空引用，光晕将自然淡出并自动删除
            blueFlare = null;
            whiteFlare = null;
        }

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