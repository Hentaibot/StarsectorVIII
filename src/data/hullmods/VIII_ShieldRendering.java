package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import java.awt.Color;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.lazywizard.lazylib.VectorUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.util.vector.Vector2f;

/**
 * MSR 六边形护盾渲染器。
 *
 * <p>主舰和所有存活模块的精灵先写入模板缓冲，再只在模板轮廓内绘制六边形网格。
 * 网格从舰船贴图中心向外扩张，越靠近中心越透明；另有周期性的颜色扫过带。
 * 少量碎片粒子同样被模板裁剪在舰体轮廓内，模块被摧毁后不再参与绘制。</p>
 */
public class VIII_ShieldRendering extends BaseCombatLayeredRenderingPlugin {

    public static final String RENDER_KEY = "VIII_ShieldRendering";
    private static final int MAX_PARTICLES = VIII_ParticlePool.MAX_PARTICLES;
    private static final Color PARTICLE_COLOR = new Color(148, 26, 44, 255);
    private static final float EMIT_RADIUS_MULT = 0.44f;
    private static final Color HEX_SWEEP_COLOR = new Color(255, 0, 39,255);
    private static final Color HULL_LINE = new Color(148, 26, 44, 255);
    private static final Color HIT_COLOR = new Color(255, 0, 0, 255);
    private static final int MAX_HITS = 32;
    private static final float BEAM_HIT_COOLDOWN = 0.12f;

    /** 受击六边形亮度倍率，原版命中光效很强时可以继续调高。 */
    public static float HIT_BRIGHTNESS_MULT = 2.0f;

    /** 碎片生成间隔，单位秒；默认 0.1 秒约等于每秒 10 个。 */
    public static float PARTICLE_INTERVAL = 0.5f;

    /** 单次伤害不超过该值时，只高亮中心一格六边形。 */
    public static float HIT_DAMAGE_ONE_RING = 200f;

    /** 单次伤害超过一格阈值且不超过该值时，高亮中心与第 1、2 圈。 */
    public static float HIT_DAMAGE_TWO_RINGS = 750f;

    /** 单次伤害超过两圈阈值时，高亮中心与第 1、2、3 圈；超过该值才产生涟漪。 */
    public static float HIT_DAMAGE_THREE_RINGS = 1250f;

    /** 超过三圈阈值后，每额外这么多伤害多扩散一圈涟漪。 */
    public static float HIT_DAMAGE_EXTRA_RING = 500f;

    /** 涟漪最多额外扩散的圈数。 */
    public static int HIT_MAX_EXTRA_RINGS = 5;

    /** 受击亮度达到满值所需的伤害；越小越容易亮到最高。 */
    public static float HIT_BRIGHTNESS_DAMAGE = 1f;

    /**
     * 船体六边形单元半径，单位为世界坐标。
     *
     * <p>数值越大，六边形越大、数量越少；小于 4 时按 4 处理。</p>
     */
    public static float HEX_CELL_RADIUS = 12f;

    /**
     * 护盾开启后，六边形从舰船贴图中心扩张到全舰所需时间，单位为秒。
     */
    public static float HEX_EXPAND_TIME = 3f;

    /**
     * 护盾关闭后，六边形从最大范围收拢到贴图中心所需时间，单位为秒。
     */
    public static float HEX_COLLAPSE_TIME = 1f;

    /**
     * 六边形颜色扫过舰体的间隔，单位为秒。
     */
    public static float HEX_SWEEP_INTERVAL = 10f;

    /**
     * 颜色扫过带的高度，单位为世界坐标；宽度自动覆盖舰体横向范围。
     */
    public static float HEX_SWEEP_HEIGHT = 90f;

    /**
     * 六边形整体不透明度倍率，1 为当前基准值，0 为完全透明。
     */
    public static float HEX_ALPHA = 0.4f;

    /**
     * 贴图中心处的六边形 alpha（不透明度）。
     *
     * <p>0 表示中心完全透明，1 表示中心与边缘一样不透明。</p>
     */
    public static float HEX_CENTER_ALPHA = 0.05f;

    /**
     * 从贴图中心到边缘的 alpha 渐变范围。
     *
     * <p>0.3 表示前 30% 半径完成从中心 alpha 到边缘 alpha 的过渡；
     * 1 表示整个半径都在渐变。</p>
     */
    public static float HEX_FADE_START = 0.35f;



    private static VIII_ShieldRendering active;

    private VIII_ParticlePool pool;
    private final VIII_ParticlePool.Batch batch = new VIII_ParticlePool.Batch();
    private final Random random = new Random();
    private final Map<ShipAPI, Float> collapseStart = new HashMap<ShipAPI, Float>();
    private final Set<ShipAPI> shieldWasOn = new HashSet<ShipAPI>();
    private final List<Hit> hits = new ArrayList<Hit>();
    private final Map<ShipAPI, Float> lastBeamHit = new HashMap<ShipAPI, Float>();

    /**
     * 一次受击高亮。命中点以贴图中心为原点的局部坐标保存，舰船转向时高亮仍贴合舰体。
     */
    private static class Hit {
        ShipAPI root;
        float localX;
        float localY;
        float damage;
        boolean beam;
        float start;
        float life;
        int maxRing;
        int extraRings;
        float brightness;
        boolean ripple;
    }

    /**
     * 获取当前战斗的渲染器；不存在时创建并注册。
     */
    public static VIII_ShieldRendering getInstance() {
        if (Global.getCombatEngine() == null) {
            return null;
        }

        Object existing = Global.getCombatEngine().getCustomData().get(RENDER_KEY);
        if (existing instanceof VIII_ShieldRendering) {
            active = (VIII_ShieldRendering) existing;
            return active;
        }

        VIII_ShieldRendering renderer = new VIII_ShieldRendering();
        Global.getCombatEngine().addLayeredRenderingPlugin(renderer);
        Global.getCombatEngine().getCustomData().put(RENDER_KEY, renderer);
        active = renderer;
        return renderer;
    }

    public static VIII_ParticlePool getPool() {
        VIII_ShieldRendering renderer = getInstance();
        if (renderer == null) {
            return null;
        }
        if (renderer.pool == null) {
            renderer.pool = new VIII_ParticlePool(MAX_PARTICLES);
        }
        return renderer.pool;
    }

    /**
     * 主体监听器每帧调用，为主舰和所有存活模块发射粒子。
     */
    public static void emitFor(ShipAPI root, int count) {
        if (root == null || count <= 0) {
            return;
        }
        if (root.getShield() == null || !root.getShield().isOn()) {
            return;
        }
        if (!VIII_ShieldPriority.canApply(root, VIII_ShieldPriority.LAYER_MSR)) {
            return;
        }

        VIII_ShieldRendering renderer = getInstance();
        if (renderer == null) {
            return;
        }
        if (getPool() == null) {
            return;
        }

        int amount = Math.min(count, 8);
        renderer.emitOnShip(root, root, amount);
        for (ShipAPI module : root.getChildModulesCopy()) {
            if (isRenderableModule(module)) {
                renderer.emitOnShip(root, module, amount);
            }
        }
    }

    /**
     * 记录一次 MSR 护盾受击。伤害 0~500 只亮中心一格，
     * 500~3000 亮两圈，3000~10000 亮三圈；超过 10000 再追加收束涟漪。
     * 光束伤害最多只亮两圈，且不追加涟漪。
     */
    public static void addHit(ShipAPI root, Vector2f point, float damage, boolean beam) {
        if (root == null || point == null || damage <= 0f) {
            return;
        }
        VIII_ShieldRendering renderer = getInstance();
        if (renderer == null) {
            return;
        }
        renderer.addHitInternal(root, point, damage, beam);
    }

    private void addHitInternal(ShipAPI root, Vector2f point, float damage, boolean beam) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) {
            return;
        }

        float now = engine.getTotalElapsedTime(false);
        if (beam) {
            Float last = lastBeamHit.get(root);
            if (last != null && now - last < BEAM_HIT_COOLDOWN) {
                return;
            }
            lastBeamHit.put(root, now);
        }

        float oneRingDamage = Math.max(1f, HIT_DAMAGE_ONE_RING);
        float twoRingDamage = Math.max(oneRingDamage, HIT_DAMAGE_TWO_RINGS);
        float threeRingDamage = Math.max(twoRingDamage, HIT_DAMAGE_THREE_RINGS);
        float extraRingDamage = Math.max(1f, HIT_DAMAGE_EXTRA_RING);
        int maxExtraRings = Math.max(1, HIT_MAX_EXTRA_RINGS);

        int maxRing;
        if (damage <= oneRingDamage) {
            maxRing = 0;
        } else if (damage <= twoRingDamage) {
            maxRing = 2;
        } else {
            maxRing = 3;
        }
        if (beam) {
            maxRing = Math.min(2, maxRing);
        }

        boolean ripple = damage > threeRingDamage && !beam;
        int extraRings = 0;
        if (ripple) {
            extraRings = 1 + (int) ((damage - threeRingDamage) / extraRingDamage);
            if (extraRings < 1) {
                extraRings = 1;
            }
            if (extraRings > maxExtraRings) {
                extraRings = maxExtraRings;
            }
        }

        float brightness = Math.max(0.01f,
                Math.min(1f, damage / Math.max(1f, HIT_BRIGHTNESS_DAMAGE) * HIT_BRIGHTNESS_MULT));
        Vector2f center = getTextureCenter(root);
        float facing = (float) Math.toRadians(root.getFacing());
        float cos = (float) Math.cos(facing);
        float sin = (float) Math.sin(facing);
        float dx = point.x - center.x;
        float dy = point.y - center.y;

        Hit hit = new Hit();
        hit.root = root;
        hit.localX = dx * cos + dy * sin;
        hit.localY = -dx * sin + dy * cos;
        hit.damage = damage;
        hit.beam = beam;
        hit.start = now;
        hit.life = ripple ? 1.05f : 0.45f;
        hit.maxRing = maxRing;
        hit.extraRings = extraRings;
        hit.brightness = brightness;
        hit.ripple = ripple;
        hits.add(hit);

        while (hits.size() > MAX_HITS) {
            hits.remove(0);
        }
    }

    private static boolean isRenderableModule(ShipAPI module) {
        return module != null && module.isAlive() && !module.isHulk();
    }

    private void emitOnShip(ShipAPI root, ShipAPI target, int count) {
        if (root == null || target == null || !target.isAlive() || target.isHulk()) {
            return;
        }

        SpriteAPI sprite = target.getSpriteAPI();
        float width = sprite != null ? Math.max(20f, sprite.getWidth()) : Math.max(20f, target.getCollisionRadius() * 2f);
        float height = sprite != null ? Math.max(20f, sprite.getHeight()) : Math.max(20f, target.getCollisionRadius() * 2f);
        float rx = Math.max(4f, width * EMIT_RADIUS_MULT);
        float ry = Math.max(4f, height * EMIT_RADIUS_MULT);
        Vector2f targetCenter = getTextureCenter(target);
        Vector2f rootCenter = getTextureCenter(root);

        float cell = Math.max(4f, HEX_CELL_RADIUS);
        float sqrt3 = 1.7320508f;
        float rootFacing = (float) Math.toRadians(root.getFacing());
        float rootCos = (float) Math.cos(rootFacing);
        float rootSin = (float) Math.sin(rootFacing);
        float targetFacing = (float) Math.toRadians(target.getFacing());
        float targetCos = (float) Math.cos(targetFacing);
        float targetSin = (float) Math.sin(targetFacing);

        for (int i = 0; i < count; i++) {
            float angle = random.nextFloat() * 6.2831855f;
            float radius = (float) Math.sqrt(random.nextFloat());
            float localX = (float) Math.cos(angle) * rx * radius;
            float localY = (float) Math.sin(angle) * ry * radius;

            float worldX = targetCenter.x + localX * targetCos - localY * targetSin;
            float worldY = targetCenter.y + localX * targetSin + localY * targetCos;

            // 将随机点吸附到最近的主舰六边形中心，粒子只从实际绘制出的六边形位置生成。
            float dx = worldX - rootCenter.x;
            float dy = worldY - rootCenter.y;
            float rootX = dx * rootCos + dy * rootSin;
            float rootY = -dx * rootSin + dy * rootCos;
            int q = Math.round((2f / 3f * rootX) / cell);
            int s = Math.round((-1f / 3f * rootX + sqrt3 / 3f * rootY) / cell);

            float hexX = 1.5f * cell * q;
            float hexY = sqrt3 * cell * (s + q * 0.5f);
            float positionX = rootCenter.x + hexX * rootCos - hexY * rootSin;
            float positionY = rootCenter.y + hexX * rootSin + hexY * rootCos;

            Vector2f position = new Vector2f(positionX, positionY);
            Vector2f velocity = new Vector2f((positionX - targetCenter.x) * 0.35f,
                    (positionY - targetCenter.y) * 0.35f);

            float size = cell * (0.65f + random.nextFloat() * 0.55f);
            float life = 0.8f + random.nextFloat() * 0.7f;
            float rotation = random.nextFloat() * 360f;
            float angular = (random.nextFloat() - 0.5f) * 180f;

            pool.spawnIfRoom(6, position, velocity,
                    rotation, angular, new Vector2f(size, size),
                    0.7f, 0.7f, life, PARTICLE_COLOR);
        }
    }

    @Override
    public void init(com.fs.starfarer.api.combat.CombatEntityAPI entity) {
    }

    @Override
    public void cleanup() {
        if (pool != null) {
            pool.clear();
            pool = null;
        }
        collapseStart.clear();
        shieldWasOn.clear();
        hits.clear();
        lastBeamHit.clear();
        active = null;
    }

    @Override
    public boolean isExpired() {
        return super.isExpired();
    }

    @Override
    public void advance(float amount) {
        updateCollapseStates();
    }

    @Override
    public EnumSet<CombatEngineLayers> getActiveLayers() {
        return EnumSet.of(CombatEngineLayers.ABOVE_SHIPS_LAYER,
                CombatEngineLayers.JUST_BELOW_WIDGETS);
    }

    @Override
    public float getRenderRadius() {
        return 1000000f;
    }

    @Override
    public void render(CombatEngineLayers layer, ViewportAPI viewport) {
        if (viewport == null) {
            return;
        }
        boolean hitLayer = layer == CombatEngineLayers.JUST_BELOW_WIDGETS;
        if (!hitLayer && layer != CombatEngineLayers.ABOVE_SHIPS_LAYER) {
            return;
        }

        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) {
            return;
        }
        updateCollapseStates();
        if (pool == null) {
            pool = new VIII_ParticlePool(MAX_PARTICLES);
        }

        if (!engine.isPaused()) {
            pool.update(engine.getElapsedInLastFrame());
        }

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glColorMask(true, true, true, true);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(viewport.getLLX(), viewport.getLLX() + viewport.getVisibleWidth(),
                viewport.getLLY(), viewport.getLLY() + viewport.getVisibleHeight(),
                -2000f, 2000f);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();

        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL20.glUseProgram(0);

        // 每艘舰独立一轮模板写入：如果先把所有舰船的轮廓并集写进同一张模板，
        // 相邻舰船的六边形网格会画进彼此的舰体轮廓里，重叠处被混合两次而出现叠加。
        if (hitLayer) {
            // 受击高亮与涟漪单独放在 JUST_BELOW_WIDGETS，盖住原版命中光效。
            for (ShipAPI ship : engine.getShips()) {
                if (shouldRender(ship)) {
                    beginStencilPass();
                    renderShipAndModulesToStencil(ship);
                    endStencilPass();
                    drawHitEffects(ship);
                }
            }
        } else {
            for (ShipAPI ship : engine.getShips()) {
                if (shouldRender(ship)) {
                    beginStencilPass();
                    renderShipAndModulesToStencil(ship);
                    endStencilPass();
                    drawHullHex(ship);
                }
            }

            if (pool.activeCount() > 0) {
                drawParticles(engine, viewport);
            }
        }

        GL11.glDisable(GL11.GL_STENCIL_TEST);

        GL20.glUseProgram(previousProgram);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopAttrib();
    }

    /**
     * 开始为单艘舰船写模板：清空模板缓冲、关闭颜色写入、把不透明像素写成 1。
     *
     * <p>每艘舰船一轮独立的模板写入，确保六边形网格只画在该舰自己的轮廓里。</p>
     */
    private void beginStencilPass() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        GL11.glStencilMask(0xFF);
        GL11.glClearStencil(0);
        GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
        GL11.glColorMask(false, false, false, false);
        GL11.glStencilFunc(GL11.GL_ALWAYS, 1, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glAlphaFunc(GL11.GL_GREATER, 0.05f);
    }

    /**
     * 结束模板写入：恢复颜色写入，只保留模板值等于 1 的像素参与后续绘制。
     */
    private void endStencilPass() {
        GL11.glColorMask(true, true, true, true);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glStencilFunc(GL11.GL_EQUAL, 1, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);

        // 舰船精灵写入模板时会改写混合状态，这里必须重新开启，否则六边形 alpha 会被忽略。
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
    }

    /**
     * 碎片粒子只在所有力场舰船的轮廓并集内绘制一次。
     *
     * <p>粒子如果跟着逐舰的模板循环绘制，落在两舰重叠区域里的粒子会被重复叠加，
     * 所以这里单独再做一次并集模板，只画一遍。</p>
     */
    private void drawParticles(CombatEngineAPI engine, ViewportAPI viewport) {
        beginStencilPass();
        for (ShipAPI ship : engine.getShips()) {
            if (shouldRender(ship)) {
                renderShipAndModulesToStencil(ship);
            }
        }
        endStencilPass();

        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
        batch.reset();
        pool.draw(batch, viewport);
        batch.drawTriangles();
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    private void drawHullHex(ShipAPI root) {
        if (root == null) {
            return;
        }

        float reveal = getHexReveal(root);
        if (reveal <= 0f) {
            return;
        }

        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);

        Vector2f center = getTextureCenter(root);
        float cx = center.x;
        float cy = center.y;
        float radius = getHullHexRadius(root);
        float cell = Math.max(4f, HEX_CELL_RADIUS);

        SpriteAPI sprite = root.getSpriteAPI();
        float halfLength = sprite != null
                ? Math.max(sprite.getWidth(), sprite.getHeight()) * 0.5f
                : radius;

        float sweepPhase = -1f;
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine != null && HEX_SWEEP_INTERVAL > 0f) {
            float interval = Math.max(0.01f, HEX_SWEEP_INTERVAL);
            float now = engine.getTotalElapsedTime(false);
            sweepPhase = (now % interval) / interval;
        }

        GL11.glLineWidth(1.0f);
        drawHexGrid(cx, cy, radius, cell, root.getFacing(), HULL_LINE,
                reveal, halfLength, sweepPhase);
    }

    private void drawHitEffects(ShipAPI root) {
        if (root == null || hits.isEmpty()) {
            return;
        }

        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) {
            return;
        }

        float now = engine.getTotalElapsedTime(false);
        Iterator<Hit> cleanup = hits.iterator();
        while (cleanup.hasNext()) {
            Hit hit = cleanup.next();
            if (now - hit.start >= hit.life) {
                cleanup.remove();
            }
        }
        if (hits.isEmpty()) {
            return;
        }

        Vector2f center = getTextureCenter(root);
        float cell = Math.max(4f, HEX_CELL_RADIUS);
        float sqrt3 = 1.7320508f;
        float facing = (float) Math.toRadians(root.getFacing());
        float cos = (float) Math.cos(facing);
        float sin = (float) Math.sin(facing);

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);

        Iterator<Hit> iterator = hits.iterator();
        while (iterator.hasNext()) {
            Hit hit = iterator.next();
            if (hit.root != root) {
                continue;
            }

            float age = now - hit.start;
            if (age < 0f || age >= hit.life) {
                continue;
            }

            float initialFade = hit.ripple
                    ? Math.max(0f, 1f - age / 0.55f)
                    : Math.max(0f, 1f - age / hit.life);
            float initialAlpha = 1.0f * hit.brightness * initialFade;

            float qf = (2f / 3f * hit.localX) / cell;
            float rf = (-1f / 3f * hit.localX + sqrt3 / 3f * hit.localY) / cell;
            int q0 = Math.round(qf);
            int r0 = Math.round(rf);

            float rippleT = -1f;
            float front = 0f;
            if (hit.ripple && age > 0.12f) {
                float span = Math.max(0.01f, hit.life - 0.12f);
                rippleT = Math.min(1f, (age - 0.12f) / span);
                front = hit.maxRing + rippleT * hit.extraRings;
            }

            int range = hit.maxRing + (hit.ripple ? hit.extraRings + 2 : 1);
            for (int q = q0 - range; q <= q0 + range; q++) {
                for (int s = r0 - range; s <= r0 + range; s++) {
                    int dist = hexDistance(q, s, q0, r0);
                    boolean initial = dist <= hit.maxRing;
                    boolean band = false;
                    float bandT = 0f;
                    if (rippleT >= 0f) {
                        float diff = Math.abs(dist - front);
                        if (diff <= 0.75f) {
                            band = true;
                            bandT = 1f - diff / 0.75f;
                        }
                    }
                    if (!initial && !band) {
                        continue;
                    }

                    float alpha;
                    if (initial) {
                        alpha = initialAlpha;
                    } else {
                        alpha = 1.0f * hit.brightness * (1f - rippleT) * bandT;
                    }
                    if (alpha <= 0.01f) {
                        continue;
                    }

                    float localX = 1.5f * cell * q;
                    float localY = sqrt3 * cell * (s + q * 0.5f);
                    float cx = center.x + localX * cos - localY * sin;
                    float cy = center.y + localX * sin + localY * cos;
                    drawHitCell(cx, cy, cell, cos, sin, alpha);
                }
            }

            if (rippleT >= 0f) {
                float ringAlpha = 1.0f * hit.brightness * (1f - rippleT) * 0.8f;
                if (ringAlpha > 0.01f) {
                    GL11.glLineWidth(2.2f);
                    GL11.glColor4f(HIT_COLOR.getRed() / 255f, HIT_COLOR.getGreen() / 255f,
                            HIT_COLOR.getBlue() / 255f, ringAlpha);
                    drawCircleLine(center.x + hit.localX * cos - hit.localY * sin,
                            center.y + hit.localX * sin + hit.localY * cos,
                            front * 1.5f * cell, 64);
                }
            }
        }

        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glLineWidth(1f);
    }

    private void drawHitCell(float cx, float cy, float cell, float cos, float sin, float alpha) {
        float r = HIT_COLOR.getRed() / 255f;
        float g = HIT_COLOR.getGreen() / 255f;
        float b = HIT_COLOR.getBlue() / 255f;

        GL11.glColor4f(r, g, b, alpha);
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glVertex2f(cx, cy);
        for (int i = 0; i <= 6; i++) {
            float a = (float) (Math.PI / 3.0 * i);
            float vx = cell * (float) Math.cos(a);
            float vy = cell * (float) Math.sin(a);
            GL11.glVertex2f(cx + vx * cos - vy * sin, cy + vx * sin + vy * cos);
        }
        GL11.glEnd();

        GL11.glColor4f(r, g, b, alpha);
        GL11.glLineWidth(2.6f);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        for (int i = 0; i < 6; i++) {
            float a = (float) (Math.PI / 3.0 * i);
            float vx = cell * (float) Math.cos(a);
            float vy = cell * (float) Math.sin(a);
            GL11.glVertex2f(cx + vx * cos - vy * sin, cy + vx * sin + vy * cos);
        }
        GL11.glEnd();
    }

    private void drawCircleLine(float cx, float cy, float radius, int segments) {
        if (radius <= 0f || segments < 8) {
            return;
        }
        GL11.glBegin(GL11.GL_LINE_LOOP);
        for (int i = 0; i < segments; i++) {
            double angle = Math.PI * 2.0 * i / segments;
            GL11.glVertex2f(cx + (float) Math.cos(angle) * radius,
                    cy + (float) Math.sin(angle) * radius);
        }
        GL11.glEnd();
    }

    private static int hexDistance(int q1, int r1, int q2, int r2) {
        int dq = q1 - q2;
        int dr = r1 - r2;
        return (Math.abs(dq) + Math.abs(dr) + Math.abs(dq + dr)) / 2;
    }

    private float getHexReveal(ShipAPI root) {
        float collapse = getCollapseReveal(root);
        if (collapse >= 0f) {
            return collapse;
        }

        VIII_Shield.VIII_ShieldScript script = VIII_Shield.VIII_ShieldScript.getInstance(root);
        if (script == null) {
            return 0f;
        }

        float duration = Math.max(0.01f, HEX_EXPAND_TIME);
        return Math.min(1f, script.getSpreadLevel() * (4f / duration));
    }

    /**
     * 护盾关闭后，从 1 平滑收拢到 0；没有收拢动画时返回 -1。
     */
    private float getCollapseReveal(ShipAPI root) {
        Float start = collapseStart.get(root);
        if (start == null) {
            return -1f;
        }

        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) {
            return 0f;
        }

        float duration = Math.max(0.01f, HEX_COLLAPSE_TIME);
        float elapsed = engine.getTotalElapsedTime(false) - start.floatValue();
        if (elapsed >= duration) {
            collapseStart.remove(root);
            shieldWasOn.remove(root);
            return 0f;
        }
        if (elapsed <= 0f) {
            return 1f;
        }

        float t = elapsed / duration;
        return 1f - t * t;
    }

    /**
     * 记录护盾从开启变为关闭的瞬间，并清理已结束或已死亡的收拢状态。
     */
    private void updateCollapseStates() {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) {
            return;
        }

        float now = engine.getTotalElapsedTime(false);
        float duration = Math.max(0.01f, HEX_COLLAPSE_TIME);

        for (ShipAPI ship : engine.getShips()) {
            if (ship == null || ship.getParentStation() != null) {
                continue;
            }

            if (!VIII_Shield.VIII_ShieldScript.hasShield(ship)
                    || !ship.isAlive() || ship.isHulk()) {
                collapseStart.remove(ship);
                shieldWasOn.remove(ship);
                continue;
            }

            boolean on = ship.getShield() != null && ship.getShield().isOn();
            if (on) {
                shieldWasOn.add(ship);
                collapseStart.remove(ship);
                continue;
            }

            if (!shieldWasOn.contains(ship)) {
                continue;
            }

            Float start = collapseStart.get(ship);
            if (start == null) {
                collapseStart.put(ship, Float.valueOf(now));
            } else if (now - start.floatValue() >= duration) {
                collapseStart.remove(ship);
                shieldWasOn.remove(ship);
            }
        }
    }

    private void drawHexGrid(float cx, float cy, float radius, float cell,
            float facingDegrees, Color color, float reveal, float halfLength,
            float sweepPhase) {
        float sqrt3 = 1.7320508f;
        float facing = (float) Math.toRadians(facingDegrees);
        float cosFacing = (float) Math.cos(facing);
        float sinFacing = (float) Math.sin(facing);
        int range = (int) (radius / (1.5f * cell)) + 2;
        float revealRadius = radius * Math.max(0f, Math.min(1f, reveal));

        float baseR = color.getRed() / 255f;
        float baseG = color.getGreen() / 255f;
        float baseB = color.getBlue() / 255f;
        float baseA = color.getAlpha() / 255f;
        float sweepR = HEX_SWEEP_COLOR.getRed() / 255f;
        float sweepG = HEX_SWEEP_COLOR.getGreen() / 255f;
        float sweepB = HEX_SWEEP_COLOR.getBlue() / 255f;

        float sweepHeight = Math.max(0f, HEX_SWEEP_HEIGHT);
        float bandCenter = 0f;
        if (sweepPhase >= 0f && sweepHeight > 0f) {
            bandCenter = halfLength + sweepHeight * 0.5f
                    - sweepPhase * (2f * halfLength + sweepHeight);
        }

        GL11.glBegin(GL11.GL_LINES);

        for (int q = -range; q <= range; q++) {
            for (int s = -range; s <= range; s++) {
                float localX = 1.5f * cell * q;
                float localY = sqrt3 * cell * (s + q * 0.5f);
                float distance = (float) Math.sqrt(localX * localX + localY * localY);
                if (distance > revealRadius) {
                    continue;
                }

                float hx = cx + localX * cosFacing - localY * sinFacing;
                float hy = cy + localX * sinFacing + localY * cosFacing;

                float radial = Math.min(1f, distance / Math.max(1f, revealRadius));
                float centerAlpha = Math.max(0f, Math.min(1f, HEX_CENTER_ALPHA));
                float fadeRange = Math.max(0.01f, Math.min(1f, HEX_FADE_START));
                float globalAlpha = Math.max(0f, Math.min(1f, HEX_ALPHA));
                float fade = Math.min(1f, radial / fadeRange);
                float alphaFactor = centerAlpha + (1f - centerAlpha) * fade * fade;
                float alpha = baseA * alphaFactor * globalAlpha;
                float r = baseR;
                float g = baseG;
                float b = baseB;

                if (sweepPhase >= 0f && sweepHeight > 0f) {
                    float delta = Math.abs(localX - bandCenter);
                    float halfBand = sweepHeight * 0.5f;
                    if (delta < halfBand) {
                        float t = 1f - delta / halfBand;
                        float sweep = t * t * (3f - 2f * t);
                        r += (sweepR - r) * sweep * 0.85f;
                        g += (sweepG - g) * sweep * 0.85f;
                        b += (sweepB - b) * sweep * 0.85f;
                        alpha = Math.min(1f,
                                alpha + sweep * (0.08f + 0.20f * radial) * globalAlpha);
                    }
                }

                GL11.glColor4f(r, g, b, alpha);

                for (int i = 0; i < 6; i++) {
                    float a1 = (float) (Math.PI / 3.0 * i);
                    float a2 = (float) (Math.PI / 3.0 * (i + 1));
                    float c1 = (float) Math.cos(a1);
                    float s1 = (float) Math.sin(a1);
                    float c2 = (float) Math.cos(a2);
                    float s2 = (float) Math.sin(a2);

                    float x1 = hx + cell * (c1 * cosFacing - s1 * sinFacing);
                    float y1 = hy + cell * (c1 * sinFacing + s1 * cosFacing);
                    float x2 = hx + cell * (c2 * cosFacing - s2 * sinFacing);
                    float y2 = hy + cell * (c2 * sinFacing + s2 * cosFacing);

                    GL11.glVertex2f(x1, y1);
                    GL11.glVertex2f(x2, y2);
                }
            }
        }

        GL11.glEnd();
    }

    /**
     * 取舰船贴图的几何中心，而不是舰船锚点。
     *
     * <p>精灵的锚点对齐 {@code ship.getLocation()}，纹理中心相对锚点的偏移为
     * {@code (width/2 - centerX, height/2 - centerY)}，再按精灵角度旋转。</p>
     */
    private Vector2f getTextureCenter(ShipAPI ship) {
        float x = ship.getLocation().x;
        float y = ship.getLocation().y;

        SpriteAPI sprite = ship.getSpriteAPI();
        if (sprite == null) {
            return new Vector2f(x, y);
        }

        float localX = sprite.getWidth() * 0.5f - sprite.getCenterX();
        float localY = sprite.getHeight() * 0.5f - sprite.getCenterY();
        float angle = (float) Math.toRadians(sprite.getAngle());
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        return new Vector2f(x + localX * cos - localY * sin,
                y + localX * sin + localY * cos);
    }

    private float getHullHexRadius(ShipAPI root) {
        float radius = Math.max(80f, root.getCollisionRadius() * 2.0f);
        SpriteAPI sprite = root.getSpriteAPI();
        if (sprite != null) {
            radius = Math.max(radius, Math.max(sprite.getWidth(), sprite.getHeight()) * 0.62f);
        }

        for (ShipAPI module : root.getChildModulesCopy()) {
            if (!isRenderableModule(module)) {
                continue;
            }
            SpriteAPI moduleSprite = module.getSpriteAPI();
            float moduleSize = moduleSprite != null
                    ? Math.max(moduleSprite.getWidth(), moduleSprite.getHeight()) * 0.5f
                    : module.getCollisionRadius();
            float distance = Vector2f.sub(module.getLocation(), root.getLocation(), null).length();
            radius = Math.max(radius, distance + moduleSize * 0.9f);
        }

        return radius;
    }

    private void setColor(Color color, float alphaMult) {
        GL11.glColor4f(color.getRed() / 255f, color.getGreen() / 255f,
                color.getBlue() / 255f, color.getAlpha() / 255f * alphaMult);
    }

    private boolean shouldRender(ShipAPI ship) {
        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            return false;
        }
        if (ship.getParentStation() != null) {
            return false;
        }
        if (!VIII_Shield.VIII_ShieldScript.hasShield(ship)) {
            return false;
        }
        if (ship.getShield() == null || !ship.getShield().isOn()) {
            return getCollapseReveal(ship) > 0f;
        }
        return VIII_ShieldPriority.canApply(ship, VIII_ShieldPriority.LAYER_MSR);
    }

    private void renderShipAndModulesToStencil(ShipAPI ship) {
        if (ship == null || !ship.isAlive() || ship.isHulk()) {
            return;
        }

        SpriteAPI sprite = ship.getSpriteAPI();
        if (sprite != null) {
            sprite.renderAtCenter(ship.getLocation().x, ship.getLocation().y);
        }

        for (ShipAPI module : ship.getChildModulesCopy()) {
            if (isRenderableModule(module)) {
                renderShipAndModulesToStencil(module);
            }
        }
    }
}
