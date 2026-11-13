package data.scripts.weapons.VIII_RealityRipper;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.impl.combat.NegativeExplosionVisual;
import com.fs.starfarer.api.impl.combat.RiftCascadeMineExplosion;
import com.fs.starfarer.api.loading.DamagingExplosionSpec;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * VIII_RealityRipper 光束武器特效插件。
 * 当光束命中目标时，每 0.01 秒在命中点产生一次爆炸，并生成二次电弧：
 * 从命中点射向爆炸半径内随机敌舰的随机武器位置（次电弧），仅造成 EMP 伤害。
 * 爆炸半径 2000 像素，伤害 20，EMP 伤害 500，爆炸效果完全透明（仅造成伤害）。
 */
public class VIII_RealityRipper_BeamEffect implements BeamEffectPlugin {

    // ===================== 爆炸参数 =====================
    private static final float EXPLOSION_RADIUS = 2000f;
    private static final float EXPLOSION_DAMAGE = 20f;
    private static final float EXPLOSION_EMP = 500f;
    private static final float EXPLOSION_DURATION = 0.1f;
    private static final float INTERVAL = 0.01f;

    // ===================== 主曲线光束参数 =====================
//    private static final float MAIN_BEAM_DAMAGE = 20f;          // 主曲线光束伤害倍数（相对于武器基础伤害）
//    private static final float MAIN_BEAM_START_WIDTH = 30f;           // 起点宽度（像素）
//    private static final float MAIN_BEAM_END_WIDTH = 10f;             // 终点宽度（像素）
//    private static final float MAIN_BEAM_FADE_IN = 0.1f;              // 淡入时间（秒）
//    private static final float MAIN_BEAM_FULL = 0.2f;                 // 保持时间（秒）
//    private static final float MAIN_BEAM_FADE_OUT = 0.1f;             // 淡出时间（秒）
//    private static final Color MAIN_START_COLOR = new Color(255, 0, 213, 150);
//    private static final Color MAIN_START_EMISSIVE = new Color(28, 0, 255, 150);
//    private static final Color MAIN_END_COLOR = new Color(28, 0, 255, 150);
//    private static final Color MAIN_END_EMISSIVE = new Color(255, 0, 213, 150);

    // ===================== 二次电弧参数（仅 EMP） =====================
    private static final int SECONDARY_BEAM = 3;                       // 二次电弧数量
    private static final float SECONDARY_BEAM_EMP = 500f;              // 二次电弧 EMP 伤害值
    private static final float SECONDARY_ARC_THICKNESS = 10f;          // 电弧粗细
    // 以下参数与 VIII_RealityRipper_Effect.spawnChargeArcs 保持一致
    private static final float SECONDARY_ARC_SEGMENT_LENGTH_MULT = 8f;    // 分段长度倍率
    private static final float SECONDARY_ARC_ZIGZAG_REDUCTION = 0.15f;    // 锯齿削减
    private static final float SECONDARY_ARC_FADE_OUT_DIST = 50f;         // 端点淡出距离
    private static final float SECONDARY_ARC_MIN_FADE_OUT_MULT = 10f;     // 端点淡出倍率
    private static final float SECONDARY_ARC_FLICKER_RATE_MULT = 0.3f;    // 闪烁频率倍率
    private static final float SECONDARY_ARC_MOVEMENT_DUR = 0.3f;         // 抖动时长
    private static final float SECONDARY_ARC_WARPING = 1f;                // 扭曲强度
    // 颜色沿用原二次曲线光束，不做修改：外缘品红、内芯蓝紫
    private static final Color SECONDARY_ARC_FRINGE_COLOR = new Color(255, 0, 213, 200);
    private static final Color SECONDARY_ARC_CORE_COLOR = new Color(28, 0, 255, 255);

    // ===================== 光束粒子参数（开火时沿光束漂移）=====================
    private static final Color BEAM_PARTICLE_COLOR_A = new Color(255, 0, 213, 255);   // 粒子颜色 A：品红
    private static final Color BEAM_PARTICLE_COLOR_B = new Color(28, 0, 255, 255);    // 粒子颜色 B：蓝紫
    private static final float BEAM_PARTICLE_COLOR_MIX = 0.35f;                       // 颜色混合比：A 占 35%、B 占 65%，调整直接改粒子色调
    private static final float BEAM_PARTICLE_DENSITY = 3f;                            // 密度系数：越大粒子越密（粒子数 = 光束宽 × 0.5 × 光束长 × 帧时长 × 密度 × 蓄能度）
    private static final float BEAM_PARTICLE_CHANCE = 0.7f;                           // 每帧生成概率（0~1）：越小越稀疏，1 表示每帧都生成
    private static final float BEAM_PARTICLE_SIZE_MIN = 10f;                           // 粒子最小尺寸（像素）
    private static final float BEAM_PARTICLE_SIZE_MAX = 15f;                          // 粒子最大尺寸（像素）
    private static final float BEAM_PARTICLE_DURATION_MIN = 0.4f;                     // 粒子最短存活时间（秒）
    private static final float BEAM_PARTICLE_DURATION_MAX = 0.9f;                     // 粒子最长存活时间（秒）
    private static final float BEAM_PARTICLE_INERTIA = 0.5f;                          // 沿舰船速度的惯性比例（0~1）：越大粒子越跟随舰船移动
    private static final float BEAM_PARTICLE_DRIFT = 50f;                             // 随机漂移速度（像素/秒）：使粒子向两侧散开，值越大越蓬松
    private static final float BEAM_PARTICLE_SPREAD_MULT = 1f;                        // 生成散布半径倍数：半径 = 光束宽度 × 该值（1 即与光束同宽）
    private static final int BEAM_PARTICLE_MAX_PER_FRAME = 150;                        // 单帧生成上限：防止光束过长时粒子爆量掉帧

    // 随机数生成器
    private static final Random RANDOM = new Random();

    // 计时器
    private float sinceLastExplosion = 0f;

    @Override
    public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {
        if (engine.isPaused()) return;

        // 记录本帧光束终点，武器进入冷却时用该位置生成奇点
        WeaponAPI beamWeapon = beam.getWeapon();
        if (beamWeapon != null && beamWeapon.getShip() != null) {
            beamWeapon.getShip().setCustomData(VIII_RealityRipper_Effect.KEY_LAST_BEAM_END,
                    new Vector2f(beam.getTo()));
        }

        if (beam.didDamageThisFrame()) {
            sinceLastExplosion += amount;
            if (sinceLastExplosion >= INTERVAL) {
                sinceLastExplosion = 0f;

                Vector2f point = beam.getTo();
                ShipAPI source = beam.getSource();

                // ----- 生成透明爆炸（纯伤害）-----
                DamagingExplosionSpec spec = new DamagingExplosionSpec(
                        EXPLOSION_DURATION,
                        EXPLOSION_RADIUS,
                        EXPLOSION_RADIUS,
                        EXPLOSION_DAMAGE,
                        EXPLOSION_DAMAGE,
                        CollisionClass.MISSILE_NO_FF,
                        CollisionClass.MISSILE_NO_FF,
                        0f, 0f, 0f, 0,
                        new Color(0, 0, 0, 0),
                        new Color(0, 0, 0, 0)
                );
                spec.setMaxEMPDamage(EXPLOSION_EMP);
                spec.setMinEMPDamage(EXPLOSION_EMP);
                spec.setUseDetailedExplosion(false);
                spec.setSoundSetId("rift_lightning_explosion");
                spec.setSoundVolume(0.5f);
                spec.setShowGraphic(false);
                spec.setDamageType(DamageType.ENERGY);

                DamagingProjectileAPI explosion = engine.spawnDamagingExplosion(spec, source, point);

                // ----- 生成裂隙特效（视觉）-----
                NegativeExplosionVisual.NEParams p = createStandardRiftParams(70f);
                RiftCascadeMineExplosion.spawnStandardRift(explosion, p);

                // ----- 黑洞贴图特效：使用 VIII_blackhole2，持续命中时不断续命 -----
                VIII_RealityRipper_Blackhole.onBeamHit(engine, beam.getWeapon(), point);

                // ----- 新功能：对爆炸半径内所有敌方舰船施加硬幅能增加 -----
                applyHardFluxToEnemiesInRadius(engine, source, point);

                // ----- 生成主曲线光束（武器 → 命中点）-----
                //spawnMainCurveBeam(engine, beam);

                // ----- 生成二次曲线光束（命中点 → 随机敌舰武器/引擎），数量为3道 -----
                spawnSecondaryArcs(engine, beam, point);
            }
        }
        beam.getDamage().setForceHardFlux(true);

        // 开火期间沿光束持续生成漂移粒子，模仿 RX_0_1_arm_right_weapon_magnum 的等离子流
        generateBeamParticles(engine, beam, amount);
    }

    /**
     * 沿光束生成向两侧漂移的粒子，形成持续的等离子流。
     * <p>参数取自 RX_0_1_arm_right_weapon_magnum 的实现，颜色换成本 mod 的品红/蓝紫。</p>
     * <p>粒子由 {@link VIII_RealityRipper_BeamParticles} 统一绘制在
     * {@link com.fs.starfarer.api.combat.CombatEngineLayers#ABOVE_SHIPS_LAYER}，也就是黑洞贴图的下一层。</p>
     */
    private void generateBeamParticles(CombatEngineAPI engine, BeamAPI beam, float amount) {
        if (beam.getBrightness() <= 0.1f) return;
        WeaponAPI weapon = beam.getWeapon();
        if (weapon == null || !weapon.isFiring()) return;
        Vector2f from = beam.getFrom();
        Vector2f to = beam.getTo();
        if (from == null || to == null) return;

        float width = Math.max(1f, beam.getWidth());
        float length = MathUtils.getDistance(from, to);
        if (length <= 1f) return;

        float chargeLevel = Math.max(weapon.getChargeLevel(), 0.5f);
        float count = width * BEAM_PARTICLE_SPREAD_MULT * length * amount * BEAM_PARTICLE_DENSITY * chargeLevel;
        if (RANDOM.nextFloat() > BEAM_PARTICLE_CHANCE) return;

        int spawn = (int) Math.min(count, BEAM_PARTICLE_MAX_PER_FRAME);
        if (spawn <= 0) {
            // count 不足 1 时按剩余概率决定是否补一颗
            if (RANDOM.nextFloat() > count) return;
            spawn = 1;
        }

        ShipAPI ship = beam.getSource();
        Vector2f shipVelocity = ship == null ? new Vector2f() : ship.getVelocity();
        Color color = blendColors(BEAM_PARTICLE_COLOR_A, BEAM_PARTICLE_COLOR_B, BEAM_PARTICLE_COLOR_MIX);
        ViewportAPI viewport = engine.getViewport();

        for (int i = 0; i < spawn; i++) {
            Vector2f point = MathUtils.getRandomPointOnLine(from, to);
            point = MathUtils.getRandomPointInCircle(point, width * BEAM_PARTICLE_SPREAD_MULT);
            if (viewport != null && !viewport.isNearViewport(point, 30f)) continue;

            Vector2f velocity = new Vector2f(shipVelocity.x * BEAM_PARTICLE_INERTIA,
                    shipVelocity.y * BEAM_PARTICLE_INERTIA);
            velocity = MathUtils.getRandomPointInCircle(velocity, BEAM_PARTICLE_DRIFT);
            float size = MathUtils.getRandomNumberInRange(BEAM_PARTICLE_SIZE_MIN, BEAM_PARTICLE_SIZE_MAX);
            float duration = MathUtils.getRandomNumberInRange(BEAM_PARTICLE_DURATION_MIN,
                    BEAM_PARTICLE_DURATION_MAX);
            VIII_RealityRipper_BeamParticles.spawn(engine, point, velocity, size, chargeLevel, duration, color);
        }
    }

    /**
     * 按比例混合两个颜色（ratio 为 color1 的占比）。
     */
    private static Color blendColors(Color color1, Color color2, float ratio) {
        float inv = 1f - ratio;
        int r = (int) (color1.getRed() * ratio + color2.getRed() * inv);
        int g = (int) (color1.getGreen() * ratio + color2.getGreen() * inv);
        int b = (int) (color1.getBlue() * ratio + color2.getBlue() * inv);
        int a = (int) (color1.getAlpha() * ratio + color2.getAlpha() * inv);
        return new Color(r, g, b, a);
    }

    /**
     * 对爆炸半径内的所有敌方舰船和战斗机增加其最大幅能容量 3% 的硬幅能。
     * @param engine 战斗引擎
     * @param source 伤害来源（发射舰）
     * @param center 爆炸中心点
     */
    private void applyHardFluxToEnemiesInRadius(CombatEngineAPI engine, ShipAPI source, Vector2f center) {
        for (ShipAPI ship : engine.getShips()) {
            if (ship == source) continue; // 忽略自身
            if (ship.getOwner() == source.getOwner()) continue; // 忽略友军
            if (!ship.isAlive() || ship.isHulk()) continue; // 忽略已摧毁/残骸
            if (ship.isPhased()) continue; // 忽略相位状态
            float dist = MathUtils.getDistance(center, ship.getLocation());
            if (dist <= EXPLOSION_RADIUS) {
                FluxTrackerAPI flux = ship.getFluxTracker();
                if (flux != null) {
                    float fluxToAdd = ship.getMaxFlux() * 0.01f; // 最大幅能的1%
                    flux.increaseFlux(fluxToAdd, true); // 增加硬幅能
                }
            }
        }
    }

//    /**
//     * 生成从光束起点到终点的主曲线光束。
//     */
//    private void spawnMainCurveBeam(CombatEngineAPI engine, BeamAPI beam) {
//        ShipAPI source = beam.getSource();
//        WeaponAPI weapon = beam.getWeapon();
//        Vector2f start = beam.getFrom();
//        Vector2f end = beam.getTo();
//
//
//        // 计算方向角
//        float angle = VectorUtils.getAngle(start, end);
//        float rad = (float) Math.toRadians(angle);
//        float dirX = (float) Math.cos(rad);
//        float dirY = (float) Math.sin(rad);
//        float perpX = -dirY; // 垂直方向
//
//        // 创建节点
//        NodeData startNode = new NodeData(start);
//        NodeData endNode = new NodeData(end);
//
//        // 起点切线：随机垂直方向
//        float range = weapon.getRange();
//        float tangentLength = MathUtils.getRandomNumberInRange(range * 0.02f, range * 0.15f);
//        float sign = RANDOM.nextBoolean() ? 1f : -1f;
//        startNode.setTangentRight(perpX * tangentLength * sign, dirX * tangentLength * sign);
//
//        // 创建伤害控制器（主光束使用混合伤害控制器）
//        DealtController controller = new MainBeamDealt(engine, source, MAIN_BEAM_DAMAGE, beam.getDamageTarget());
//
//        // 生成曲线光束
//        var curveResult = CurveUtil.spawnCurveBeam(engine, null, startNode, endNode, range * 2f, controller);
//        SegmentEntity segment = curveResult.one;
//        if (segment == null) return;
//
//        // 设置节点颜色和宽度
//        var nodes = segment.getNodes();
//        if (nodes != null && nodes.size() >= 2) {
//            NodeData first = nodes.get(0);
//            NodeData last = nodes.get(nodes.size() - 1);
//            first.setColor(MAIN_START_COLOR);
//            first.setEmissiveColor(MAIN_START_EMISSIVE);
//            first.setWidth(MAIN_BEAM_START_WIDTH);
//            last.setColor(MAIN_END_COLOR);
//            last.setEmissiveColor(MAIN_END_EMISSIVE);
//            last.setWidth(MAIN_BEAM_END_WIDTH);
//        }
//
//        // 材质和纹理
//        segment.getMaterialData().setDiffuse(Global.getSettings().getSprite(DIFFUSE_TEXTURE));
//        segment.getMaterialData().setEmissive(Global.getSettings().getSprite(EMISSIVE_TEXTURE));
//        segment.setAdditiveBlend();
//        segment.setInterpolation((short) 32);
//        segment.setTexturePixels(256f);
//        segment.setTextureSpeed(TEXTURE_SPEED);
//
//        // 两端淡出
//        float length = CurveUtil.getCurveLength(startNode, endNode, 8);
//        float smoothFactor = Math.max(length - 4f, 0f) / length;
//        segment.setFillStartAlpha(0f);
//        segment.setFillStartFactor(smoothFactor);
//        segment.setFillEndAlpha(0f);
//        segment.setFillEndFactor(smoothFactor);
//
//        segment.setGlobalTimer(MAIN_BEAM_FADE_IN, MAIN_BEAM_FULL, MAIN_BEAM_FADE_OUT);
//        segment.setNodeRefreshAllFromCurrentIndex();
//        segment.submitNodes();
//        segment.setLayer(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER);
//        CombatRenderingManager.addEntity(segment);
//    }

    /**
     * 生成指定数量的二次电弧，每条从命中点射向爆炸半径内随机敌舰的随机武器位置。
     *
     * @param engine   战斗引擎
     * @param beam     当前光束
     * @param startPos 起点（命中点）
     */
    private void spawnSecondaryArcs(CombatEngineAPI engine, BeamAPI beam, Vector2f startPos) {
        for (int i = 0; i < SECONDARY_BEAM; i++) {
            spawnSingleSecondaryArc(engine, beam, startPos);
        }
    }

    /**
     * 生成一条二次电弧。
     *
     * <p>电弧参数与 {@code VIII_RealityRipper_Effect.spawnChargeArcs} 完全一致，
     * 渲染层设为 {@link CombatEngineLayers#ABOVE_SHIPS_LAYER}，与光束粒子同层
     * （位于黑洞贴图的下一层）。伤害语义保持原二次曲线光束的做法：常规伤害 0、
     * 仅 {@code 500} EMP，穿盾、计为软幅能。</p>
     */
    private void spawnSingleSecondaryArc(CombatEngineAPI engine, BeamAPI beam, Vector2f startPos) {
        ShipAPI source = beam.getSource();
        if (source == null) return;

        // 收集爆炸半径内的敌方舰船
        List<ShipAPI> validTargets = new ArrayList<ShipAPI>();
        for (ShipAPI ship : engine.getShips()) {
            if (ship == source) continue; // 忽略发射舰
            if (ship.getOwner() == source.getOwner()) continue; // 忽略友军
            if (!ship.isAlive() || ship.isHulk()) continue; // 忽略已摧毁/残骸
            if (ship.isPhased()) continue; // 忽略相位状态
            float dist = MathUtils.getDistance(startPos, ship.getLocation());
            if (dist <= EXPLOSION_RADIUS) {
                validTargets.add(ship);
            }
        }

        if (validTargets.isEmpty()) return; // 无目标，跳过

        // 随机选择一个目标舰船
        ShipAPI targetShip = validTargets.get(RANDOM.nextInt(validTargets.size()));

        // 确定终点：优先随机选取一个武器的位置，若无武器则用舰船中心
        Vector2f endPos;
        List<WeaponAPI> weapons = targetShip.getAllWeapons();
        if (weapons != null && !weapons.isEmpty()) {
            WeaponAPI randomWeapon = weapons.get(RANDOM.nextInt(weapons.size()));
            endPos = randomWeapon.getLocation();
        } else {
            endPos = targetShip.getLocation(); // 后备
        }
        if (endPos == null) return;

        // 电弧参数：与 spawnChargeArcs 一致
        EmpArcEntityAPI.EmpArcParams params = new EmpArcEntityAPI.EmpArcParams();
        params.segmentLengthMult = SECONDARY_ARC_SEGMENT_LENGTH_MULT;
        params.zigZagReductionFactor = SECONDARY_ARC_ZIGZAG_REDUCTION;
        params.fadeOutDist = SECONDARY_ARC_FADE_OUT_DIST;
        params.minFadeOutMult = SECONDARY_ARC_MIN_FADE_OUT_MULT;
        params.flickerRateMult = SECONDARY_ARC_FLICKER_RATE_MULT;
        params.movementDurOverride = Math.max(0.2f, SECONDARY_ARC_MOVEMENT_DUR);

        EmpArcEntityAPI arc = engine.spawnEmpArcVisual(startPos, source, endPos, targetShip,
                SECONDARY_ARC_THICKNESS, SECONDARY_ARC_FRINGE_COLOR, SECONDARY_ARC_CORE_COLOR, params);
        if (arc == null) return;

        arc.setRenderGlowAtStart(false);        // 起点不画光晕，避免命中点堆成一团
        arc.setSingleFlickerMode(true);         // 整条电弧同步闪烁
        arc.setWarping(SECONDARY_ARC_WARPING);
        arc.setLayer(CombatEngineLayers.ABOVE_SHIPS_LAYER); // 与光束粒子同层

        // 仅 EMP 伤害（与原二次曲线光束一致：0 常规伤害、穿盾、软幅能）
        engine.applyDamage(targetShip, endPos, 0f, DamageType.ENERGY, SECONDARY_BEAM_EMP, true, true, source);
    }

    // ===================== 裂隙特效 =====================
    public static NegativeExplosionVisual.NEParams createStandardRiftParams(float radius) {
        NegativeExplosionVisual.NEParams p = new NegativeExplosionVisual.NEParams();
        p.numRiftsToSpawn = 2;
        p.fadeIn = 0.1f;
        p.fadeOut = 0.5f;
        p.spawnHitGlowAt = 0f;
        p.hitGlowSizeMult = 0f;
        p.radius = radius;
        p.thickness = radius * 1.25f;
        p.noiseMag = 1f;
        p.noiseMult = 1f;
        p.noisePeriod = 0.1f;
        p.withHitGlow = false;
        p.withNegativeParticles = false;
        p.color = new Color(0, 0, 0, 0);
        p.underglow = new Color(
                0,
                0,
                0,
                0
        );
        p.blackColor = new Color(0, 0, 0, 255);
        p.invertForDarkening = null;
        p.additiveBlend = false;
        return p;
    }
}