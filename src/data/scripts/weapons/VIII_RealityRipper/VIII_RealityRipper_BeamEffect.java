package data.scripts.weapons.VIII_RealityRipper;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.impl.combat.NegativeExplosionVisual;
import com.fs.starfarer.api.impl.combat.RiftCascadeMineExplosion;
import com.fs.starfarer.api.loading.DamagingExplosionSpec;
import org.boxutil.manager.CombatRenderingManager;
import org.boxutil.units.standard.attribute.NodeData;
import org.boxutil.units.standard.entity.SegmentEntity;
import org.boxutil.util.CurveUtil;
import org.boxutil.util.CurveUtil.DealtController;
import org.lazywizard.lazylib.MathUtils;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * VIII_RealityRipper 光束武器特效插件。
 * 当光束命中目标时，每 0.01 秒在命中点产生一次爆炸，并生成两条曲线光束：
 * 1. 从武器到命中点（主光束），造成常规伤害和 EMP。
 * 2. 从命中点到爆炸半径内随机敌舰的武器/引擎位置（次光束），仅造成 EMP 伤害。
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
    private static final float MAIN_BEAM_DAMAGE = 20f;          // 主曲线光束伤害倍数（相对于武器基础伤害）
    private static final float MAIN_BEAM_START_WIDTH = 30f;           // 起点宽度（像素）
    private static final float MAIN_BEAM_END_WIDTH = 10f;             // 终点宽度（像素）
    private static final float MAIN_BEAM_FADE_IN = 0.1f;              // 淡入时间（秒）
    private static final float MAIN_BEAM_FULL = 0.2f;                 // 保持时间（秒）
    private static final float MAIN_BEAM_FADE_OUT = 0.1f;             // 淡出时间（秒）
    private static final Color MAIN_START_COLOR = new Color(255, 0, 213, 150);
    private static final Color MAIN_START_EMISSIVE = new Color(28, 0, 255, 150);
    private static final Color MAIN_END_COLOR = new Color(28, 0, 255, 150);
    private static final Color MAIN_END_EMISSIVE = new Color(255, 0, 213, 150);

    // ===================== 二次曲线光束参数（仅 EMP） =====================
    private static final int SECONDARY_BEAM = 3;                      // 二次光束数量
    private static final float SECONDARY_BEAM_EMP = 500f;             // 二次光束 EMP 伤害值
    private static final float SECONDARY_BEAM_START_WIDTH = 10f;      // 起点宽度（像素）
    private static final float SECONDARY_BEAM_END_WIDTH = 8f;         // 终点宽度（像素）
    private static final float SECONDARY_BEAM_FADE_IN = 0.05f;        // 淡入时间（秒）
    private static final float SECONDARY_BEAM_FULL = 0.15f;           // 保持时间（秒）
    private static final float SECONDARY_BEAM_FADE_OUT = 0.2f;        // 淡出时间（秒）
    private static final Color SECONDARY_START_COLOR = new Color(255, 0, 213, 200);
    private static final Color SECONDARY_START_EMISSIVE = new Color(28, 0, 255, 255);
    private static final Color SECONDARY_END_COLOR = new Color(255, 0, 213, 200);
    private static final Color SECONDARY_END_EMISSIVE = new Color(28, 0, 255, 255);

    // 通用纹理参数
    private static final String DIFFUSE_TEXTURE = "graphics/fx/beam_rough2_fringe.png";
    private static final String EMISSIVE_TEXTURE = "graphics/fx/beam_rough2_fringe.png";
    private static final float TEXTURE_SPEED = -512f;

    // 击中主颜色（用于裂隙特效）
    private static final Color HIT_COLOR = new Color(28, 0, 255, 255);

    // 随机数生成器
    private static final Random RANDOM = new Random();

    // 计时器
    private float sinceLastExplosion = 0f;

    @Override
    public void advance(float amount, CombatEngineAPI engine, BeamAPI beam) {
        if (engine.isPaused()) return;

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

                // ----- 新功能：对爆炸半径内所有敌方舰船施加硬幅能增加 -----
                applyHardFluxToEnemiesInRadius(engine, source, point);

                // ----- 生成主曲线光束（武器 → 命中点）-----
                spawnMainCurveBeam(engine, beam);

                // ----- 生成二次曲线光束（命中点 → 随机敌舰武器/引擎），数量为3道 -----
                spawnSecondaryCurveBeams(engine, beam, point);
            }
        }
        beam.getDamage().setForceHardFlux(true);
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

    /**
     * 生成从光束起点到终点的主曲线光束。
     */
    private void spawnMainCurveBeam(CombatEngineAPI engine, BeamAPI beam) {
        ShipAPI source = beam.getSource();
        WeaponAPI weapon = beam.getWeapon();
        Vector2f start = beam.getFrom();
        Vector2f end = beam.getTo();


        // 计算方向角
        float angle = VectorUtils.getAngle(start, end);
        float rad = (float) Math.toRadians(angle);
        float dirX = (float) Math.cos(rad);
        float dirY = (float) Math.sin(rad);
        float perpX = -dirY; // 垂直方向

        // 创建节点
        NodeData startNode = new NodeData(start);
        NodeData endNode = new NodeData(end);

        // 起点切线：随机垂直方向
        float range = weapon.getRange();
        float tangentLength = MathUtils.getRandomNumberInRange(range * 0.02f, range * 0.15f);
        float sign = RANDOM.nextBoolean() ? 1f : -1f;
        startNode.setTangentRight(perpX * tangentLength * sign, dirX * tangentLength * sign);

        // 创建伤害控制器（主光束使用混合伤害控制器）
        DealtController controller = new MainBeamDealt(engine, source, MAIN_BEAM_DAMAGE, beam.getDamageTarget());

        // 生成曲线光束
        var curveResult = CurveUtil.spawnCurveBeam(engine, null, startNode, endNode, range * 2f, controller);
        SegmentEntity segment = curveResult.one;
        if (segment == null) return;

        // 设置节点颜色和宽度
        var nodes = segment.getNodes();
        if (nodes != null && nodes.size() >= 2) {
            NodeData first = nodes.get(0);
            NodeData last = nodes.get(nodes.size() - 1);
            first.setColor(MAIN_START_COLOR);
            first.setEmissiveColor(MAIN_START_EMISSIVE);
            first.setWidth(MAIN_BEAM_START_WIDTH);
            last.setColor(MAIN_END_COLOR);
            last.setEmissiveColor(MAIN_END_EMISSIVE);
            last.setWidth(MAIN_BEAM_END_WIDTH);
        }

        // 材质和纹理
        segment.getMaterialData().setDiffuse(Global.getSettings().getSprite(DIFFUSE_TEXTURE));
        segment.getMaterialData().setEmissive(Global.getSettings().getSprite(EMISSIVE_TEXTURE));
        segment.setAdditiveBlend();
        segment.setInterpolation((short) 32);
        segment.setTexturePixels(256f);
        segment.setTextureSpeed(TEXTURE_SPEED);

        // 两端淡出
        float length = CurveUtil.getCurveLength(startNode, endNode, 8);
        float smoothFactor = Math.max(length - 4f, 0f) / length;
        segment.setFillStartAlpha(0f);
        segment.setFillStartFactor(smoothFactor);
        segment.setFillEndAlpha(0f);
        segment.setFillEndFactor(smoothFactor);

        segment.setGlobalTimer(MAIN_BEAM_FADE_IN, MAIN_BEAM_FULL, MAIN_BEAM_FADE_OUT);
        segment.setNodeRefreshAllFromCurrentIndex();
        segment.submitNodes();
        segment.setLayer(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER);
        CombatRenderingManager.addEntity(segment);
    }

    /**
     * 生成指定数量的二次曲线光束，每条从命中点连接到爆炸半径内随机敌舰的武器/引擎位置。
     *
     * @param engine   战斗引擎
     * @param beam     当前光束
     * @param startPos 起点（命中点）
     */
    private void spawnSecondaryCurveBeams(CombatEngineAPI engine, BeamAPI beam, Vector2f startPos) {
        for (int i = 0; i < SECONDARY_BEAM; i++) {
            spawnSingleSecondaryCurveBeam(engine, beam, startPos);
        }
    }

    /**
     * 生成一条二次曲线光束。
     */
    private void spawnSingleSecondaryCurveBeam(CombatEngineAPI engine, BeamAPI beam, Vector2f startPos) {
        ShipAPI source = beam.getSource();

        // 收集爆炸半径内的敌方舰船
        List<ShipAPI> validTargets = new ArrayList<>();
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
        if (!weapons.isEmpty()) {
            WeaponAPI randomWeapon = weapons.get(RANDOM.nextInt(weapons.size()));
            endPos = randomWeapon.getLocation();
        } else {
            endPos = targetShip.getLocation(); // 后备
        }

        // 计算方向角
        float angle = VectorUtils.getAngle(startPos, endPos);
        float rad = (float) Math.toRadians(angle);
        float dirX = (float) Math.cos(rad);
        float dirY = (float) Math.sin(rad);
        float perpX = -dirY; // 垂直方向

        // 创建节点
        NodeData startNode = new NodeData(startPos);
        NodeData endNode = new NodeData(endPos);

        // 起点切线：随机垂直方向，长度基于爆炸半径
        float tangentLength = MathUtils.getRandomNumberInRange(EXPLOSION_RADIUS * 0.02f, EXPLOSION_RADIUS * 0.1f);
        float sign = RANDOM.nextBoolean() ? 1f : -1f;
        startNode.setTangentRight(perpX * tangentLength * sign, dirX * tangentLength * sign);

        // 创建仅 EMP 伤害控制器
        DealtController controller = new EMPOnlyBeamDealt(engine, source, SECONDARY_BEAM_EMP, beam.getDamageTarget());

        // 生成曲线光束
        var curveResult = CurveUtil.spawnCurveBeam(engine, null, startNode, endNode, EXPLOSION_RADIUS * 2f, controller);
        SegmentEntity segment = curveResult.one;
        if (segment == null) return;

        // 设置节点颜色和宽度
        var nodes = segment.getNodes();
        if (nodes != null && nodes.size() >= 2) {
            NodeData first = nodes.get(0);
            NodeData last = nodes.get(nodes.size() - 1);
            first.setColor(SECONDARY_START_COLOR);
            first.setEmissiveColor(SECONDARY_START_EMISSIVE);
            first.setWidth(SECONDARY_BEAM_START_WIDTH);
            last.setColor(SECONDARY_END_COLOR);
            last.setEmissiveColor(SECONDARY_END_EMISSIVE);
            last.setWidth(SECONDARY_BEAM_END_WIDTH);
        }

        // 材质和纹理（复用主光束纹理）
        segment.getMaterialData().setDiffuse(Global.getSettings().getSprite(DIFFUSE_TEXTURE));
        segment.getMaterialData().setEmissive(Global.getSettings().getSprite(EMISSIVE_TEXTURE));
        segment.setAdditiveBlend();
        segment.setInterpolation((short) 32);
        segment.setTexturePixels(256f);
        segment.setTextureSpeed(TEXTURE_SPEED);

        // 两端淡出
        float length = CurveUtil.getCurveLength(startNode, endNode, 8);
        float smoothFactor = Math.max(length - 4f, 0f) / length;
        segment.setFillStartAlpha(0f);
        segment.setFillStartFactor(smoothFactor);
        segment.setFillEndAlpha(0f);
        segment.setFillEndFactor(smoothFactor);

        segment.setGlobalTimer(SECONDARY_BEAM_FADE_IN, SECONDARY_BEAM_FULL, SECONDARY_BEAM_FADE_OUT);
        segment.setNodeRefreshAllFromCurrentIndex();
        segment.submitNodes();
        segment.setLayer(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER);
        CombatRenderingManager.addEntity(segment);
    }

    // ===================== 伤害控制器 =====================

    /**
     * 主曲线光束伤害控制器：应用常规伤害 + EMP（使用传入的 damage 和固定 EMP）
     */
    private record MainBeamDealt(CombatEngineAPI engine, ShipAPI source, float damage,
                                 CombatEntityAPI excludeTarget) implements DealtController {
        @Override
        public void applyEffect(CombatEntityAPI target, Vector2f point, float beamT, boolean isShieldHit) {
            // 应用伤害（常规 + EMP）
            engine.applyDamage(target, point, damage, DamageType.ENERGY, 300f, false, true, source);
        }

        @Override
        public boolean isIgnore(CombatEntityAPI target) {
            if (target == excludeTarget) return true;
            if (target == source) return true;
            if (target.getCollisionClass() == CollisionClass.NONE) return true;
            if (target.getOwner() >= 100) return true;

            if (target instanceof ShipAPI s) {
                if (s.isPhased()) return true;
                if (!s.isHulk() && !s.isTargetable()) return true;
            }

            boolean isFighter = target instanceof ShipAPI && ((ShipAPI) target).isFighter();
            boolean parentIsSource = target instanceof ShipAPI && ((ShipAPI) target).getParentStation() == source;
            return (target instanceof MissileAPI || isFighter || parentIsSource) && target.getOwner() == source.getOwner();
        }

        @Override
        public boolean isPierceShield(ShipAPI target) {
            return false;
        }

        @Override
        public boolean isPierce(CombatEntityAPI target, Vector2f point, float beamT, boolean isShieldHit) {
            return target instanceof CombatAsteroidAPI || target instanceof MissileAPI;
        }
    }

    /**
     * 仅 EMP 伤害控制器：仅应用 EMP 伤害，常规伤害为 0
     */
    private record EMPOnlyBeamDealt(CombatEngineAPI engine, ShipAPI source, float empDamage,
                                    CombatEntityAPI excludeTarget) implements DealtController {
        @Override
        public void applyEffect(CombatEntityAPI target, Vector2f point, float beamT, boolean isShieldHit) {
            // 仅 EMP 伤害，常规伤害设为 0
            engine.applyDamage(target, point, 0f, DamageType.ENERGY, empDamage, true, true, source);
        }

        @Override
        public boolean isIgnore(CombatEntityAPI target) {
            // 同主控制器逻辑
            if (target == source) return true;
            if (target.getCollisionClass() == CollisionClass.NONE) return true;
            if (target.getOwner() >= 100) return true;

            if (target instanceof ShipAPI s) {
                if (s.isPhased()) return true;
                if (!s.isHulk() && !s.isTargetable()) return true;
            }

            boolean isFighter = target instanceof ShipAPI && ((ShipAPI) target).isFighter();
            boolean parentIsSource = target instanceof ShipAPI && ((ShipAPI) target).getParentStation() == source;
            return (target instanceof MissileAPI || isFighter || parentIsSource) && target.getOwner() == source.getOwner();
        }

        @Override
        public boolean isPierceShield(ShipAPI target) {
            return true;
        }

        @Override
        public boolean isPierce(CombatEntityAPI target, Vector2f point, float beamT, boolean isShieldHit) {
            return target instanceof CombatAsteroidAPI || target instanceof MissileAPI || target instanceof ShipAPI;
        }
    }

    // ===================== 裂隙特效 =====================
    public static NegativeExplosionVisual.NEParams createStandardRiftParams(float radius) {
        NegativeExplosionVisual.NEParams p = new NegativeExplosionVisual.NEParams();
        p.numRiftsToSpawn = 2;
        p.fadeIn = 0.1f;
        p.fadeOut = 0.5f;
        p.spawnHitGlowAt = 0f;
        p.hitGlowSizeMult = 0.75f;
        p.radius = radius;
        p.thickness = radius * 1.25f;
        p.noiseMag = 1f;
        p.noiseMult = 1f;
        p.noisePeriod = 0.1f;
        p.withHitGlow = true;
        p.withNegativeParticles = false;
        p.color = new Color(HIT_COLOR.getRed(), HIT_COLOR.getGreen(), HIT_COLOR.getBlue(), 255);
        p.underglow = new Color(
                Math.max(0, HIT_COLOR.getRed() - 50),
                Math.max(0, HIT_COLOR.getGreen() - 50),
                Math.max(0, HIT_COLOR.getBlue() - 50),
                0
        );
        p.blackColor = new Color(0, 0, 0, 255);
        p.invertForDarkening = null;
        p.additiveBlend = false;
        return p;
    }
}