package data.scripts.weapons.VIII_RealityRipper;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.impl.combat.RiftCascadeMineExplosion;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import org.dark.shaders.distortion.DistortionShader;
import org.dark.shaders.distortion.RippleDistortion;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * 缩退炮的「冷却奇点」：光束开火结束、武器进入冷却时，在最后一次光束终点留下一个奇点。
 *
 * <p>生命周期：</p>
 * <ol>
 *     <li>充能期（{@link #CHARGE_TIME} 秒）：三个四角星芒光晕缓慢旋转、脉动，模拟奇点吸积；</li>
 *     <li>爆发：以奇点为中心喷出紫色烟雾，3 秒内扩散到 {@link #BURST_MAX_RADIUS} 半径；</li>
 *     <li>扩散完成后检测该半径内所有敌舰，在每艘敌舰附近随机布下 3~4 枚
 *         {@code riftcascade_minelayer} 裂隙地雷；</li>
 *     <li>每枚地雷爆炸时，额外向 1000 范围内的敌舰随机武器 / 引擎位置射出 3~7 道
 *         穿盾、1000 点纯 EMP 伤害的电弧（参数与配色沿用开火前摇的电荷电弧）。</li>
 * </ol>
 *
 * <p>渲染层为 {@link CombatEngineLayers#ABOVE_SHIPS_AND_MISSILES_LAYER}，
 * 与黑洞贴图同层，位于光束粒子之上、裂隙之下。</p>
 */
public class VIII_RealityRipper_SingularityRift extends BaseCombatLayeredRenderingPlugin {

    /** 贴图分类 */
    private static final String SPRITE_CATEGORY = "fx";
    /** 贴图 id（settings.json 的 graphics.fx 中注册） */
    private static final String SPRITE_ID = "VIII_singularity_star";

    /** 充能时长（秒）：从冷却开始到爆发 */
    private static final float CHARGE_TIME = 7.5F;
    /** 充能期光晕淡入时长（秒） */
    private static final float CHARGE_FADE_IN = 1.2F;
    /** 三层光晕的基础尺寸（世界单位） */
    private static final float[] FLARE_SIZES = {300.0F, 210.0F, 140.0F};
    /** 三层光晕的初始角度（度） */
    private static final float[] FLARE_ANGLES = {0.0F, 60.0F, 120.0F};
    /** 三层光晕的自转角速度（度/秒，正为顺时针） */
    private static final float[] FLARE_SPINS = {18.0F, -26.0F, 34.0F};
    /** 脉动幅度（相对尺寸） */
    private static final float PULSE_AMOUNT = 0.08F;
    /** 脉动角速度（弧度/秒） */
    private static final float PULSE_SPEED = 2.4F;
    /** 充能期每帧最多生成的吸积粒子数 */
    private static final int ACCRETION_PER_FRAME = 2;

    /** 爆发扩散时长（秒） */
    private static final float BURST_DURATION = 3.0F;
    /** 爆发最大半径（世界单位） */
    private static final float BURST_MAX_RADIUS = 3000.0F;
    /** 光晕在爆发后的淡出时长（秒） */
    private static final float BURST_FLARE_FADE = 0.9F;
    /** 爆发烟雾每帧生成数 */
    private static final int BURST_PARTICLES_PER_FRAME = 6;

    /** 地雷尚未全部爆炸时，每帧补充的余烬烟雾数量（维持云团不散） */
    private static final int LINGER_PARTICLES_PER_FRAME = 2;
    /** 所有地雷爆炸后，烟雾淡出时长（秒） */
    private static final float SMOKE_FADE_OUT = 1.2F;
    /** 地雷全部爆炸后烟雾继续保持的时长（秒）——改「多久之后才开始淡出」只改这里 */
    private static final float SMOKE_HOLD_AFTER_MINES = 5.0F;
    /** 爆发闪光颜色 */
    private static final Color BURST_FLASH_COLOR = new Color(255, 245, 255, 255);
    /** 爆发烟雾起始颜色（紫） */
    private static final Color BURST_SMOKE_COLOR = new Color(99, 34, 174, 255);
    /** 爆发烟雾末段颜色（深紫、完全透明） */
    private static final Color BURST_SMOKE_END_COLOR = new Color(86, 24, 178, 0);

    /** 每艘敌舰附近生成的地雷数量下限 */
    private static final int MINE_MIN = 3;
    /** 每艘敌舰附近生成的地雷数量上限（含） */
    private static final int MINE_MAX = 4;
    /** 地雷距舰体的最近距离 */
    private static final float MINE_OFFSET_MIN = 70.0F;
    /** 地雷距舰体的最远距离 */
    private static final float MINE_OFFSET_MAX = 190.0F;
    /** 地雷引爆前保留的时间（相对最大飞行时间的提前量，0.4 = 原 0.8 秒的一半） */
    private static final float MINE_FUSE_LEAD = 0.4F;

    /** 地雷爆炸后每枚反弹的电弧数量下限 */
    private static final int ARC_MIN = 3;
    /** 地雷爆炸后每枚反弹的电弧数量上限（含） */
    private static final int ARC_MAX = 7;
    /** 电弧索敌半径 */
    private static final float ARC_RANGE = 1000.0F;
    /** 电弧纯 EMP 伤害 */
    private static final float ARC_EMP_DAMAGE = 1000.0F;
    /** 电弧厚度 */
    private static final float ARC_THICKNESS = 8.0F;
    /** 电弧颜色（外圈 / 内芯），沿用开火前摇的紫蓝色系 */
    private static final Color[][] ARC_COLORS = {
            {new Color(196, 92, 255, 255), new Color(255, 236, 255, 255)},
            {new Color(160, 64, 255, 255), new Color(240, 220, 255, 255)},
            {new Color(212, 120, 255, 255), new Color(255, 245, 255, 255)},
            {new Color(144, 48, 255, 255), new Color(232, 208, 255, 255)},
            {new Color(178, 76, 255, 255), new Color(246, 228, 255, 255)}
    };

    private CombatEngineAPI engine;
    private final ShipAPI source;
    private final Vector2f center;
    private SpriteAPI sprite;

    /** 充能已进行的时间 */
    private float timer;
    /** 爆发已进行的时间 */
    private float burstTimer;
    /** 是否已经进入爆发阶段 */
    private boolean bursting;
    /** 是否已经在爆发结束后布设过地雷 */
    private boolean minesSpawned;
    /** 插件是否已结束 */
    private boolean expired;
    /** 是否已经开始烟雾淡出 */
    private boolean fading;
    /** 烟雾淡出已进行的时间 */
    private float smokeFadeTimer;
    private float smokeHoldTimer;

    /** 追踪中的地雷（含最后一次观测到的位置） */
    private final List<TrackedMine> mines = new ArrayList<TrackedMine>();

    private final Random random = new Random();
    private final IntervalUtil rippleTimer = new IntervalUtil(0.30F, 0.55F);

    private VIII_RealityRipper_SingularityRift(ShipAPI source, Vector2f center) {
        super(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER);
        this.engine = Global.getCombatEngine();
        this.source = source;
        this.center = new Vector2f(center);
    }

    /**
     * 在指定位置生成一个冷却奇点。
     *
     * @param engine 战斗引擎
     * @param source 发射舰船（用于判定敌我，可为 null）
     * @param center 奇点位置（通常是光束终点）
     */
    public static void spawn(CombatEngineAPI engine, ShipAPI source, Vector2f center) {
        if (engine == null || center == null) return;
        VIII_RealityRipper_SingularityRift rift = new VIII_RealityRipper_SingularityRift(source, center);
        rift.engine = engine;
        engine.addLayeredRenderingPlugin(rift);
    }

    @Override
    public void advance(float amount) {
        if (engine == null) engine = Global.getCombatEngine();
        if (engine == null || engine.isPaused() || expired) return;

        // ---------- 充能期 ----------
        if (!bursting) {
            timer += amount;
            spawnAccretionParticles();
            if (timer >= CHARGE_TIME) startBurst();
        }

        // ---------- 爆发期 ----------
        if (bursting) {
            burstTimer += amount;
            if (!minesSpawned) {
                // 扩散阶段：紫色烟雾 + 冲击波在 BURST_DURATION 秒内铺到最大半径
                updateBurst();
                if (burstTimer >= BURST_DURATION) {
                    minesSpawned = true;
                    spawnMinesNearEnemies();
                    spawnArcsFromRift();
                }
            } else if (!mines.isEmpty()) {
                // 地雷尚未全部爆炸：持续补充余烬烟雾，云团保持不散
                spawnLingerSmoke();
            } else if (!fading && smokeHoldTimer < SMOKE_HOLD_AFTER_MINES) {
                // 地雷全部爆炸后，烟雾再保持 SMOKE_HOLD_AFTER_MINES 秒（期间继续补充余烬）
                smokeHoldTimer += amount;
                spawnLingerSmoke();
            } else {
                // 保持时间结束：停止补充，进入淡出
                if (!fading) {
                    fading = true;
                    smokeFadeTimer = 0.0F;
                }
                smokeFadeTimer += amount;
                if (smokeFadeTimer >= SMOKE_FADE_OUT) {
                    expired = true;
                }
            }
        }

        updateMines();
    }

    @Override
    public void render(CombatEngineLayers layer, com.fs.starfarer.api.combat.ViewportAPI viewport) {
        if (layer != CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER) return;
        if (sprite == null) {
            sprite = Global.getSettings().getSprite(SPRITE_CATEGORY, SPRITE_ID);
        }
        if (sprite == null) return;

        float alpha = resolveFlareAlpha();
        if (alpha <= 0.01F) return;

        sprite.setAdditiveBlend();
        sprite.setAlphaMult(alpha * viewport.getAlphaMult());
        for (int i = 0; i < FLARE_SIZES.length; i++) {
            float pulse = 1.0F + PULSE_AMOUNT * (float) Math.sin(timer * PULSE_SPEED + i * 1.7F);
            float size = FLARE_SIZES[i] * pulse;
            sprite.setSize(size, size);
            sprite.setAngle(FLARE_ANGLES[i] + FLARE_SPINS[i] * timer);
            sprite.renderAtCenter(center.x, center.y);
        }
    }

    @Override
    public EnumSet<CombatEngineLayers> getActiveLayers() {
        return EnumSet.of(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER);
    }

    @Override
    public float getRenderRadius() {
        return 6000.0F;
    }

    @Override
    public boolean isExpired() {
        return expired;
    }

    @Override
    public void cleanup() {
        mines.clear();
    }

    // ==================================================================
    // 视觉
    // ==================================================================

    /** 光晕当前透明度：充能期淡入，爆发后淡出。 */
    private float resolveFlareAlpha() {
        if (!bursting) {
            return Math.min(1.0F, timer / CHARGE_FADE_IN);
        }
        return Math.max(0.0F, 1.0F - burstTimer / BURST_FLARE_FADE);
    }

    /** 充能期向中心汇聚的紫色吸积粒子。 */
    private void spawnAccretionParticles() {
        for (int i = 0; i < ACCRETION_PER_FRAME; i++) {
            float angle = random.nextFloat() * 360.0F;
            float distance = 180.0F + random.nextFloat() * 320.0F;
            Vector2f from = MathUtilsOnCircle(center, distance, angle);
            Vector2f velocity = new Vector2f(center.x - from.x, center.y - from.y);
            float length = (float) Math.sqrt(velocity.x * velocity.x + velocity.y * velocity.y);
            if (length <= 0.01F) continue;
            velocity.scale((140.0F + random.nextFloat() * 120.0F) / length);
            float size = 3.0F + random.nextFloat() * 4.0F;
            float duration = 0.35F + random.nextFloat() * 0.35F;
            engine.addHitParticle(from, velocity, size, 1.0F, duration,
                    new Color(178, 96, 255, 210));
        }
    }

    /** 进入爆发阶段。 */
    private void startBurst() {
        bursting = true;
        burstTimer = 0.0F;
        engine.spawnExplosion(center, new Vector2f(), BURST_FLASH_COLOR, 480.0F, 0.7F);

        RippleDistortion shock = new RippleDistortion(new Vector2f(center), new Vector2f());
        shock.setSize(220.0F);
        shock.setMaxSize(BURST_MAX_RADIUS * 0.55F);
        shock.setIntensity(0.45F);
        shock.setMaxIntensity(0.45F);
        shock.setLifetime(BURST_DURATION);
        shock.fadeOutSize(BURST_DURATION * 0.85F);
        shock.fadeOutIntensity(BURST_DURATION * 0.85F);
        DistortionShader.addDistortion(shock);
    }

    /** 爆发期：喷出紫色烟雾，扩散半径随时间增大。 */
    private void updateBurst() {
        float progress = Math.min(1.0F, burstTimer / BURST_DURATION);
        float radius = BURST_MAX_RADIUS * (float) Math.sqrt(progress);

        for (int i = 0; i < BURST_PARTICLES_PER_FRAME; i++) {
            float angle = random.nextFloat() * 360.0F;
            float distance = radius * (0.15F + random.nextFloat() * 0.85F);
            Vector2f location = MathUtilsOnCircle(center, distance, angle);

            float velocityAngle = angle + random.nextFloat() * 40.0F - 20.0F;
            float speed = 30.0F + random.nextFloat() * 120.0F;
            Vector2f velocity = new Vector2f(
                    (float) Math.cos(Math.toRadians(velocityAngle)) * speed,
                    (float) Math.sin(Math.toRadians(velocityAngle)) * speed);

            float size = 140.0F + random.nextFloat() * 220.0F;
            float endSizeMult = 1.6F + random.nextFloat() * 1.6F;
            float duration = 1.2F + random.nextFloat() * 1.4F;
            engine.addNebulaParticle(location, velocity, size, endSizeMult,
                    0.18F, 0.55F, duration, blendColor(BURST_SMOKE_COLOR, BURST_SMOKE_END_COLOR,
                            Math.min(1.0F, progress)));
        }

        // 阶段性冲击波，让扩散更有层次
        rippleTimer.advance(0.016F);
        if (rippleTimer.intervalElapsed()) {
            RippleDistortion ripple = new RippleDistortion(new Vector2f(center), new Vector2f());
            ripple.setSize(Math.max(160.0F, radius * 0.35F));
            ripple.setMaxSize(Math.max(320.0F, radius));
            ripple.setIntensity(0.28F);
            ripple.setMaxIntensity(0.28F);
            ripple.setLifetime(1.1F);
            ripple.fadeOutSize(0.9F);
            ripple.fadeOutIntensity(0.9F);
            DistortionShader.addDistortion(ripple);
        }
    }

    // ==================================================================
    // 地雷
    // ==================================================================

    /** 爆发扩散完成后，在 3000 范围内每艘敌舰附近布设裂隙地雷。 */
    private void spawnMinesNearEnemies() {
        List<ShipAPI> targets = new ArrayList<ShipAPI>();
        for (ShipAPI ship : engine.getShips()) {
            if (!isValidEnemy(ship)) continue;
            if (Misc.getDistance(center, ship.getLocation()) > BURST_MAX_RADIUS) continue;
            targets.add(ship);
        }

        for (ShipAPI target : targets) {
            int count = MINE_MIN + random.nextInt(MINE_MAX - MINE_MIN + 1);
            for (int i = 0; i < count; i++) {
                spawnMine(target);
            }
        }
    }

    /** 在目标舰附近生成一枚裂隙地雷。 */
    private void spawnMine(ShipAPI target) {
        float angle = random.nextFloat() * 360.0F;
        float distance = MINE_OFFSET_MIN + random.nextFloat() * (MINE_OFFSET_MAX - MINE_OFFSET_MIN);
        Vector2f location = MathUtilsOnCircle(target.getLocation(), distance, angle);

        CombatEntityAPI spawned = engine.spawnProjectile(source, null, "riftcascade_minelayer",
                location, random.nextFloat() * 360.0F, new Vector2f());
        if (!(spawned instanceof MissileAPI mine)) return;

        float sizeMult = 0.85F + random.nextFloat() * 0.3F;
        mine.setCustomData(RiftCascadeMineExplosion.SIZE_MULT_KEY, sizeMult);
        mine.getDamage().getModifier().modifyMult("VIII_rift_mine", sizeMult);
        mine.setNoMineFFConcerns(true);
        mine.getVelocity().scale(0.0F);
        mine.fadeOutThenIn(0.05F);

        // 提前接近最大飞行时间，让地雷在布设后不久进入起爆倒计时
        float fuse = Math.max(0.0F, mine.getMaxFlightTime() - MINE_FUSE_LEAD);
        mine.setFlightTime(fuse);

        mines.add(new TrackedMine(mine, new Vector2f(location)));
    }

    /** 每帧检查地雷是否已经爆炸，爆炸的立刻补上电弧。 */
    private void updateMines() {
        Iterator<TrackedMine> iterator = mines.iterator();
        while (iterator.hasNext()) {
            TrackedMine tracked = iterator.next();
            MissileAPI mine = tracked.mine;

            if (mine != null && engine.isEntityInPlay(mine) && !mine.isExpired()) {
                tracked.lastLocation.set(mine.getLocation());
                continue;
            }

            iterator.remove();
            spawnArcsAt(tracked.lastLocation);
        }
    }

    /** 奇点本体在爆发完成时也会向外甩出一圈电弧。 */
    private void spawnArcsFromRift() {
        spawnArcsAt(center);
    }

    /**
     * 从指定位置向 1000 范围内敌舰的随机武器 / 引擎位置射出电弧，
     * 每道电弧造成一次穿盾的纯 EMP 伤害。
     *
     * @param from 电弧起点（地雷爆炸位置）
     */
    private void spawnArcsAt(Vector2f from) {
        List<ShipAPI> targets = new ArrayList<ShipAPI>();
        for (ShipAPI ship : engine.getShips()) {
            if (!isValidEnemy(ship)) continue;
            if (Misc.getDistance(from, ship.getLocation()) > ARC_RANGE) continue;
            targets.add(ship);
        }
        if (targets.isEmpty()) return;

        int count = ARC_MIN + random.nextInt(ARC_MAX - ARC_MIN + 1);
        for (int i = 0; i < count; i++) {
            ShipAPI target = targets.get(random.nextInt(targets.size()));
            Vector2f point = randomTargetPoint(target);
            if (point == null) point = new Vector2f(target.getLocation());

            Color[] colors = ARC_COLORS[random.nextInt(ARC_COLORS.length)];
            com.fs.starfarer.api.combat.EmpArcEntityAPI.EmpArcParams params =
                    new com.fs.starfarer.api.combat.EmpArcEntityAPI.EmpArcParams();
            params.segmentLengthMult = 8.0F;
            params.zigZagReductionFactor = 0.15F;
            params.fadeOutDist = 50.0F;
            params.minFadeOutMult = 10.0F;
            params.flickerRateMult = 0.3F;
            params.movementDurOverride = 0.3F;

            com.fs.starfarer.api.combat.EmpArcEntityAPI arc = engine.spawnEmpArcVisual(
                    new Vector2f(from), null, new Vector2f(point), null,
                    ARC_THICKNESS, colors[0], colors[1], params);
            if (arc != null) {
                arc.setRenderGlowAtStart(false);
                arc.setSingleFlickerMode(true);
                arc.setWarping(1.0F);
            }

            engine.applyDamage(target, new Vector2f(point), 1.0F, DamageType.ENERGY,
                    ARC_EMP_DAMAGE, true, false, source, true);
        }
    }

    /** 在敌舰上随机挑一个武器或引擎位置。 */
    private Vector2f randomTargetPoint(ShipAPI target) {
        List<Vector2f> candidates = new ArrayList<Vector2f>();

        for (WeaponAPI weapon : target.getAllWeapons()) {
            if (weapon.isDecorative()) continue;
            candidates.add(new Vector2f(weapon.getLocation()));
        }

        ShipEngineControllerAPI engines = target.getEngineController();
        if (engines != null) {
            for (ShipEngineControllerAPI.ShipEngineAPI engineSlot : engines.getShipEngines()) {
                candidates.add(new Vector2f(engineSlot.getLocation()));
            }
        }

        if (candidates.isEmpty()) return null;
        return candidates.get(random.nextInt(candidates.size()));
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 敌我判定：非我方、存活、非残骸、非战机。 */
    private boolean isValidEnemy(ShipAPI ship) {
        if (ship == null || ship == source) return false;
        if (!ship.isAlive() || ship.isHulk()) return false;
        if (ship.isFighter()) return false;
        if (source != null && ship.getOwner() == source.getOwner()) return false;
        return true;
    }

    /** 地雷尚未全部爆炸时补充的余烬烟雾：让紫色云团一直维持到最后一枚地雷起爆。 */
    private void spawnLingerSmoke() {
        for (int i = 0; i < LINGER_PARTICLES_PER_FRAME; i++) {
            float angle = random.nextFloat() * 360.0F;
            float distance = BURST_MAX_RADIUS * (0.15F + random.nextFloat() * 0.85F);
            Vector2f location = MathUtilsOnCircle(center, distance, angle);

            float velocityAngle = random.nextFloat() * 360.0F;
            float speed = 10.0F + random.nextFloat() * 45.0F;
            Vector2f velocity = new Vector2f(
                    (float) Math.cos(Math.toRadians(velocityAngle)) * speed,
                    (float) Math.sin(Math.toRadians(velocityAngle)) * speed);

            float size = 160.0F + random.nextFloat() * 240.0F;
            float duration = 1.0F + random.nextFloat() * 1.2F;
            engine.addNebulaParticle(location, velocity, size, 1.4F + random.nextFloat(),
                    0.20F, 0.55F, duration, BURST_SMOKE_COLOR);
        }
    }

    private static Vector2f MathUtilsOnCircle(Vector2f center, float radius, float angleDegrees) {
        float radians = (float) Math.toRadians(angleDegrees);
        return new Vector2f(
                center.x + (float) Math.cos(radians) * radius,
                center.y + (float) Math.sin(radians) * radius);
    }

    private static Color blendColor(Color from, Color to, float t) {
        float clamped = Math.max(0.0F, Math.min(1.0F, t));
        return new Color(
                (int) (from.getRed() + (to.getRed() - from.getRed()) * clamped),
                (int) (from.getGreen() + (to.getGreen() - from.getGreen()) * clamped),
                (int) (from.getBlue() + (to.getBlue() - from.getBlue()) * clamped),
                (int) (from.getAlpha() + (to.getAlpha() - from.getAlpha()) * clamped));
    }

    /** 地雷追踪数据。 */
    private static final class TrackedMine {
        private final MissileAPI mine;
        private final Vector2f lastLocation;

        private TrackedMine(MissileAPI mine, Vector2f lastLocation) {
            this.mine = mine;
            this.lastLocation = lastLocation;
        }
    }
}
