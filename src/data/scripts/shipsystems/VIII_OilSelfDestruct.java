package data.scripts.shipsystems;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.CombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.util.Misc;
import java.awt.Color;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.lwjgl.util.vector.Vector2f;

/**
 * VIII_oil 油罐模块的自爆战术系统（参考 Interstellar Imperium 的 II_Explode，
 * 但完全不播放任何音效）。
 * <p>
 * 行为：
 * - 前摇（IN）期间船体持续抖动、状态栏出现起爆提示，给玩家和 AI 一点预警；
 * - 进入全功率（ACTIVE）的瞬间点爆：爆心先放中心闪光，再开一个持续
 *   {@link #WAVE_DURATION} 秒的扩散波——爆片在 2 秒内缓慢向外扩散到
 *   {@link #BLAST_RADIUS} 的最大范围，伤害半径跟着爆片一起线性变大；
 * - 伤害在波前扫到目标时才结算，越晚被扫到（离爆心越远）伤害越低，
 *   所有目标拿到的伤害总和被 {@link #BLAST_DAMAGE} 卡死：起爆瞬间统计范围内
 *   的敌人数量 n，每个目标的预算就是 {@code BLAST_DAMAGE / n}，实际伤害再乘
 *   {@code 1 - 距离/半径}，因此全体合计永远不会超过 BLAST_DAMAGE；
 * - 只炸敌人（同 owner 的舰船跳过），并且不使用任何 SoundAPI 调用。
 * <p>
 * 2 秒扩散波由一个 {@link CombatLayeredRenderingPlugin} 驱动：它挂在战斗引擎的
 * 实体列表里逐帧 advance，{@link CombatLayeredRenderingPlugin#isExpired()} 返回
 * true 后由引擎自动清理，不需要手动 remove。
 * <p>
 * 什么时候放由 {@link data.scripts.shipsystems.ai.VIII_OilSelfDestructAI} 决定：
 * 只有油罐已脱离母舰、且最近的敌舰进入触发距离时才会按下去。
 */
public class VIII_OilSelfDestruct extends BaseShipSystemScript {

    /** 爆炸影响半径（像素） */
    public static final float BLAST_RADIUS = 2000.0F;

    /** 整场爆炸的总伤害上限：所有目标加起来也不会超过这个值 */
    public static final float BLAST_DAMAGE = 50000.0F;

    /** 爆心处的最大 EMP 伤害（按目标各自的距离衰减） */
    public static final float BLAST_EMP = 100000.0F;

    /** 爆片从爆心扩散到 {@link #BLAST_RADIUS} 所需的时间（秒） */
    public static final float WAVE_DURATION = 3.0F;

    /** 环形爆片的数量（参考 LE_Explode 里那圈 spawnExplosion） */
    private static final int BURST_COUNT = 100;

    /** 初始爆片的外抛速度：正好在 WAVE_DURATION 秒内飞完 BLAST_RADIUS */
    private static final float BURST_SPEED = BLAST_RADIUS / WAVE_DURATION;

    /** 爆片尺寸范围（像素） */
    private static final float BURST_MIN_SIZE = 70.0F;
    private static final float BURST_MAX_SIZE = 170.0F;

    /** 扩散波前沿每隔多久补一批小爆片（秒） */
    private static final float FRONT_INTERVAL = 0.045F;

    /** 扩散波前沿每批补几片 */
    private static final int FRONT_COUNT = 3;

    /**
     * 去重标记：挂在油罐的 customData 上，保证同一艘油罐只炸一次
     * （自爆系统和"阵亡补爆"共用这个标记）。
     */
    public static final String DETONATED_KEY = "VIII_oil_blast_detonated";

    /** 引擎里"无主/残骸"的 owner 值：站点解体时子模块会被改成它（CombatFleetManager:1010/1018） */
    private static final int NEUTRAL_OWNER = 100;

    /** 引爆前的抖动颜色 */
    private static final Color JITTER_COLOR = new Color(85, 0, 255, 60);

    /** 视觉爆炸颜色 */
    private static final Color EXPLOSION_COLOR = new Color(94, 0, 255, 255);

    /** 引爆只执行一次 */
    private boolean exploded = false;

    /**
     * 系统每帧回调。
     *
     * @param stats       舰船属性，用 getEntity() 取舰船
     * @param id          系统 id
     * @param state       系统状态
     * @param effectLevel 当前效果强度 0~1
     */
    @Override
    public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
        ShipAPI ship = (ShipAPI) stats.getEntity();
        if (ship == null) {
            return;
        }
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null || engine.isPaused()) {
            return;
        }
        // 前摇 / 后摇期间抖动，作为"要炸了"的视觉预警
        if (effectLevel > 0.0F) {
            ship.setJitter(ship, JITTER_COLOR, effectLevel, 5, effectLevel * 5.0F, 10.0F + effectLevel * 20.0F);
        }
        // 进入全功率的瞬间点爆
        if (state == State.ACTIVE && !exploded) {
            exploded = true;
            explode(ship, engine);
        }
    }

    /**
     * 点爆：走统一的 {@link #detonate(ShipAPI, CombatEngineAPI)} 入口，
     * 然后对自身造成致命伤害让油罐解体。
     */
    private void explode(ShipAPI ship, CombatEngineAPI engine) {
        detonate(ship, engine);

        // 自毁：高爆伤害直接打在自己身上，绕过护盾
        engine.applyDamage(ship, ship.getLocation(), ship.getMaxHitpoints() * 10.0F + 1000.0F,
                DamageType.HIGH_EXPLOSIVE, 0.0F, true, false, ship);
    }

    /**
     * 统一的引爆入口：自爆系统（ACTIVE 时）和"模块阵亡补爆"都走这里。
     * <p>
     * 起爆瞬间统计范围内的敌人、放中心闪光和初始爆片、挂上 2 秒扩散波；
     * 用 {@link #DETONATED_KEY} 去重，保证同一艘油罐只炸一次——
     * 系统已经炸过的话，阵亡时不会再补一次。
     */
    public static void detonate(ShipAPI ship, CombatEngineAPI engine) {
        if (ship == null || engine == null) {
            return;
        }
        if (hasDetonated(ship)) {
            return;
        }
        ship.setCustomData(DETONATED_KEY, Boolean.TRUE);

        Vector2f loc = new Vector2f(ship.getLocation());
        Vector2f vel = new Vector2f(ship.getVelocity());

        // 先按起爆瞬间的范围统计敌人：伤害预算按人数平分，保证总和不超过 BLAST_DAMAGE
        List<ShipAPI> targets = collectTargets(ship, loc, engine);

        // 中心闪光 + 一圈初始爆片
        spawnExplosionEffect(engine, loc, vel);

        // 2 秒扩散波：爆片边扩散边结算伤害，结束后自动过期
        engine.addLayeredRenderingPlugin(new OilBlastWave(loc, vel, ship, targets));
    }

    /** 这艘油罐是否已经炸过（自爆系统或阵亡补爆任意一次都算） */
    public static boolean hasDetonated(ShipAPI ship) {
        if (ship == null || ship.getCustomData() == null) {
            return false;
        }
        return Boolean.TRUE.equals(ship.getCustomData().get(DETONATED_KEY));
    }

    /**
     * 收集起爆瞬间处在最大范围内的敌舰（友方跳过、残骸跳过）。
     * <p>
     * 敌我判定必须用 {@link #getFriendlyOwner(ShipAPI)}，不能用 {@code getOwner()}：
     * 油罐被打爆后引擎会把它（以及母舰站点）的 owner 改成 100
     * （反编译见 CombatFleetManager 第 1010、1018 行对站点与子模块的
     * {@code setOwner(100)}），此时 100 ≠ 玩家 0，会把母舰和友军当成敌人一起炸。
     */
    private static List<ShipAPI> collectTargets(ShipAPI ship, Vector2f loc, CombatEngineAPI engine) {
        List<ShipAPI> targets = new ArrayList<ShipAPI>();
        int friendlyOwner = getFriendlyOwner(ship);
        if (friendlyOwner < 0) {
            // 阵营判不出来时宁可不炸，也不能把所有人当敌人
            return targets;
        }
        List<ShipAPI> ships = engine.getShips();
        for (int i = 0; i < ships.size(); i++) {
            ShipAPI target = ships.get(i);
            if (target == null || target == ship || !target.isAlive() || target.isHulk()) {
                continue;
            }
            // 友方（含母舰、友军、友方战机/模块）一律跳过
            if (target.getOwner() == friendlyOwner) {
                continue;
            }
            // 100 = 无主/残骸阵营，不属于任何一方，也不打
            if (target.getOwner() == NEUTRAL_OWNER) {
                continue;
            }
            if (Misc.getDistance(loc, target.getLocation()) > BLAST_RADIUS) {
                continue;
            }
            targets.add(target);
        }
        return targets;
    }

    /**
     * 取这艘油罐真正所属阵营的 owner，用来判别敌我。
     * <p>
     * 不能直接用 {@code ship.getOwner()}：舰船阵亡、站点解体时引擎会把 owner 改成 100
     * （反编译见 CombatFleetManager 第 1010、1018 行对站点与子模块的
     * {@code setOwner(100)}），此时 100 ≠ 玩家 0，会把母舰和友军当成敌人一起炸。
     * 所以按可信度依次取：母舰的 {@code getOriginalOwner()}（第一次 setOwner 时记录、
     * 之后不再变，见 com.fs.starfarer.combat.entities.Ship 第 1342-1346 行）→
     * 母舰当前 owner → 油罐自己的 originalOwner → 油罐当前 owner。
     */
    private static int getFriendlyOwner(ShipAPI ship) {
        ShipAPI parent = ship.getParentStation();
        if (parent != null) {
            if (isValidOwner(parent.getOriginalOwner())) {
                return parent.getOriginalOwner();
            }
            if (isValidOwner(parent.getOwner())) {
                return parent.getOwner();
            }
        }
        if (isValidOwner(ship.getOriginalOwner())) {
            return ship.getOriginalOwner();
        }
        if (isValidOwner(ship.getOwner())) {
            return ship.getOwner();
        }
        return -1;
    }

    /** 0/1 才是真正的交战双方；-1（未设置）与 100（无主）都不算 */
    private static boolean isValidOwner(int owner) {
        return owner == 0 || owner == 1;
    }

    /**
     * 参考 Luddic Enhancement 的 LE_Explode（其 explode 里那圈 spawnExplosion）做的爆炸特效：
     * 沿 360 度均匀抛出一圈半透明爆片，外抛速度刚好在 {@link #WAVE_DURATION} 秒内
     * 飞到最大范围，再在中心叠两层大闪光撑起体量。颜色全部取自本类的
     * {@link #EXPLOSION_COLOR}，且 spawnExplosion 本身不播声音，符合"不包含音效"的要求。
     *
     * @param engine 战斗引擎
     * @param loc    爆心
     * @param vel    爆心所在的舰船速度（让爆片继承一部分母体动量）
     */
    private static void spawnExplosionEffect(CombatEngineAPI engine, Vector2f loc, Vector2f vel) {
        float angleStep = 360.0F / BURST_COUNT;
        for (int i = 0; i < BURST_COUNT; i++) {
            // 每个爆片加一点角度抖动，避免看起来像机械的同心圆环
            float angle = angleStep * (float) i + (float) Math.random() * 10.0F;
            float radians = (float) Math.toRadians(angle);
            float speed = BURST_SPEED * (0.85F + (float) Math.random() * 0.35F);
            Vector2f burstVel = new Vector2f(
                    vel.x + (float) Math.cos(radians) * speed,
                    vel.y + (float) Math.sin(radians) * speed);
            float size = BURST_MIN_SIZE + (float) Math.random() * (BURST_MAX_SIZE - BURST_MIN_SIZE);
            // 存活到接近 2 秒，才能看到爆片一路扩散到最大范围
            float duration = 1.4F + (float) Math.random() * 0.5F;
            // 半透明爆片，alpha 随机让层次更自然
            int alpha = 45 + (int) (Math.random() * 45.0);
            engine.spawnExplosion(loc, burstVel, withAlpha(EXPLOSION_COLOR, alpha), size, duration);
        }
        // 中心两层大闪光：内层小而实、外层大而淡
        engine.spawnExplosion(loc, vel, EXPLOSION_COLOR, 260.0F, 1.2F);
        engine.spawnExplosion(loc, vel, withAlpha(EXPLOSION_COLOR, 180), 430.0F, 1.6F);
    }

    /** 复制一个颜色并替换 alpha，用来做半透明的爆片 */
    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    /**
     * 状态栏文字：只给一条起爆提示。
     */
    @Override
    public StatusData getStatusData(int index, State state, float effectLevel) {
        if (index == 0) {
            return new StatusData("起爆倒计时", false);
        }
        return null;
    }

    /**
     * 系统结束时复位，保证下一轮（如果有）还能重新引爆。
     */
    @Override
    public void unapply(MutableShipStatsAPI stats, String id) {
        exploded = false;
    }

    /**
     * 2 秒扩散波：爆片前沿线性外扩到 {@link #BLAST_RADIUS}，波前扫到目标时才结算伤害。
     * <p>
     * 作为 {@link CombatLayeredRenderingPlugin} 挂在引擎实体列表上逐帧 advance，
     * {@link #isExpired()} 返回 true 后引擎自动清理。
     */
    public static class OilBlastWave implements CombatLayeredRenderingPlugin {

        private final Vector2f center;
        private final Vector2f originVelocity;
        private final ShipAPI source;
        private final List<ShipAPI> targets;
        private final boolean[] hit;
        private final float perTargetBudget;

        private float elapsed = 0.0F;
        private float frontTimer = 0.0F;
        private boolean expired = false;

        public OilBlastWave(Vector2f center, Vector2f originVelocity, ShipAPI source, List<ShipAPI> targets) {
            this.center = new Vector2f(center);
            this.originVelocity = new Vector2f(originVelocity);
            this.source = source;
            this.targets = new ArrayList<ShipAPI>(targets);
            this.hit = new boolean[this.targets.size()];
            if (this.targets.isEmpty()) {
                this.perTargetBudget = 0.0F;
            } else {
                this.perTargetBudget = BLAST_DAMAGE / (float) this.targets.size();
            }
        }

        @Override
        public void init(CombatEntityAPI entity) {
        }

        @Override
        public void cleanup() {
        }

        @Override
        public boolean isExpired() {
            return expired;
        }

        @Override
        public void advance(float amount) {
            if (expired) {
                return;
            }
            CombatEngineAPI engine = Global.getCombatEngine();
            if (engine == null || engine.isPaused()) {
                return;
            }
            elapsed += amount;
            float progress = elapsed / WAVE_DURATION;
            if (progress > 1.0F) {
                progress = 1.0F;
            }
            float radius = BLAST_RADIUS * progress;

            applyWaveDamage(engine, radius);
            spawnFrontParticles(engine, radius, progress, amount);

            if (elapsed >= WAVE_DURATION) {
                expired = true;
            }
        }

        /**
         * 波前扫到谁就给谁结算一次伤害：越远的目标越晚被扫到，伤害越低；
         * 每个目标最多拿 {@code BLAST_DAMAGE / n}，全体合计不超过 BLAST_DAMAGE。
         */
        private void applyWaveDamage(CombatEngineAPI engine, float radius) {
            for (int i = 0; i < targets.size(); i++) {
                if (hit[i]) {
                    continue;
                }
                ShipAPI target = targets.get(i);
                if (target == null || !target.isAlive() || target.isHulk()) {
                    hit[i] = true;
                    continue;
                }
                float dist = Misc.getDistance(center, target.getLocation());
                if (dist > BLAST_RADIUS) {
                    // 已经飞出最大范围，吃不到了
                    hit[i] = true;
                    continue;
                }
                if (dist > radius) {
                    continue;
                }
                hit[i] = true;
                float factor = 1.0F - dist / BLAST_RADIUS;
                if (factor <= 0.0F) {
                    continue;
                }
                engine.applyDamage(target, target.getLocation(), perTargetBudget * factor,
                        DamageType.HIGH_EXPLOSIVE, BLAST_EMP * factor, true, false, source);
            }
        }

        /**
         * 在当前的波前位置补一批小爆片，让扩散过程看得见；越往外扩散 alpha 越低。
         */
        private void spawnFrontParticles(CombatEngineAPI engine, float radius, float progress, float amount) {
            if (progress >= 1.0F) {
                return;
            }
            frontTimer += amount;
            if (frontTimer < FRONT_INTERVAL) {
                return;
            }
            frontTimer -= FRONT_INTERVAL;

            float baseAngle = (float) Math.random() * 360.0F;
            int alpha = (int) (130.0F * (1.0F - progress)) + 25;
            float speed = BURST_SPEED * 0.35F;
            for (int i = 0; i < FRONT_COUNT; i++) {
                float angle = baseAngle + (float) i * (360.0F / FRONT_COUNT) + (float) Math.random() * 12.0F;
                float radians = (float) Math.toRadians(angle);
                float r = radius * (0.92F + (float) Math.random() * 0.16F);
                Vector2f point = new Vector2f(
                        center.x + (float) Math.cos(radians) * r,
                        center.y + (float) Math.sin(radians) * r);
                Vector2f burstVel = new Vector2f(
                        originVelocity.x + (float) Math.cos(radians) * speed,
                        originVelocity.y + (float) Math.sin(radians) * speed);
                float size = 60.0F + (float) Math.random() * 70.0F;
                float duration = 0.45F + (float) Math.random() * 0.35F;
                engine.spawnExplosion(point, burstVel, withAlpha(EXPLOSION_COLOR, alpha), size, duration);
            }
        }

        @Override
        public EnumSet<CombatEngineLayers> getActiveLayers() {
            // 纯逻辑，不自己绘制（视觉全部交给 spawnExplosion）
            return EnumSet.noneOf(CombatEngineLayers.class);
        }

        @Override
        public float getRenderRadius() {
            return 0.0F;
        }

        @Override
        public void render(CombatEngineLayers layer, ViewportAPI viewport) {
        }
    }
}
