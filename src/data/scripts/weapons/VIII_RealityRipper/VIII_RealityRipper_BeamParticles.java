package data.scripts.weapons.VIII_RealityRipper;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;

/**
 * 光束粒子的独立渲染器。
 * <p>引擎自带的 {@code addSmoothParticle} 画在 {@link CombatEngineLayers#ABOVE_PARTICLES_LOWER} 一带，
 * 会盖在 {@link VIII_RealityRipper_Blackhole}（{@link CombatEngineLayers#ABOVE_SHIPS_AND_MISSILES_LAYER}）上面，
 * 所以这里改用自定义分层渲染：把粒子画在 {@link CombatEngineLayers#ABOVE_SHIPS_LAYER}，
 * 也就是黑洞贴图的下一层。</p>
 *
 * <p>粒子用一次贴图绑定 + 立即模式四边形批量绘制，避免逐个精灵绘制带来的开销。</p>
 */
public class VIII_RealityRipper_BeamParticles extends BaseCombatLayeredRenderingPlugin {

    private static final String KEY = "VIII_RealityRipper_BeamParticles";

    private static final String SPRITE_CATEGORY = "fx";
    /** 粒子贴图：本 mod 自带的柔和圆形 alpha 渐变（已在 settings.json 的 graphics.fx 下注册）。 */
    private static final String SPRITE_ID = "VIII_particle_glow";

    /** 存活粒子上限，防止长时间开火时列表无限增长。 */
    private static final int MAX_ALIVE = 4000;

    private static class Particle {
        final Vector2f loc = new Vector2f();
        final Vector2f vel = new Vector2f();
        float size;
        float brightness;
        float life;
        float maxLife;
        float red;
        float green;
        float blue;
        float alpha;
    }

    private final List<Particle> particles = new ArrayList<Particle>();

    private VIII_RealityRipper_BeamParticles() {
        super(CombatEngineLayers.ABOVE_SHIPS_LAYER);
    }

    /**
     * 生成一颗光束粒子。
     *
     * @param engine     战斗引擎
     * @param location   生成位置（世界坐标）
     * @param velocity   初速度（像素/秒）
     * @param size       尺寸（像素）
     * @param brightness 亮度（0~1）
     * @param duration   存活时间（秒）
     * @param color      颜色（alpha 会被亮度与剩余寿命缩放）
     */
    public static void spawn(CombatEngineAPI engine, Vector2f location, Vector2f velocity,
                             float size, float brightness, float duration, Color color) {
        if (engine == null || location == null || color == null) return;
        VIII_RealityRipper_BeamParticles renderer = getOrCreate(engine);
        if (renderer.particles.size() >= MAX_ALIVE) return;

        Particle p = new Particle();
        p.loc.set(location);
        if (velocity != null) p.vel.set(velocity);
        p.size = size;
        p.brightness = brightness;
        p.maxLife = Math.max(0.05f, duration);
        p.red = color.getRed() / 255f;
        p.green = color.getGreen() / 255f;
        p.blue = color.getBlue() / 255f;
        p.alpha = color.getAlpha() / 255f;
        renderer.particles.add(p);
    }

    private static VIII_RealityRipper_BeamParticles getOrCreate(CombatEngineAPI engine) {
        Object existing = engine.getCustomData().get(KEY);
        if (existing instanceof VIII_RealityRipper_BeamParticles) {
            return (VIII_RealityRipper_BeamParticles) existing;
        }
        VIII_RealityRipper_BeamParticles renderer = new VIII_RealityRipper_BeamParticles();
        engine.addLayeredRenderingPlugin(renderer);
        engine.getCustomData().put(KEY, renderer);
        return renderer;
    }

    @Override
    public void advance(float amount) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null || engine.isPaused()) return;

        for (Iterator<Particle> it = particles.iterator(); it.hasNext(); ) {
            Particle p = it.next();
            p.life += amount;
            if (p.life >= p.maxLife) {
                it.remove();
                continue;
            }
            p.loc.x += p.vel.x * amount;
            p.loc.y += p.vel.y * amount;
        }
    }

    @Override
    public EnumSet<CombatEngineLayers> getActiveLayers() {
        return EnumSet.of(CombatEngineLayers.ABOVE_SHIPS_LAYER);
    }

    @Override
    public boolean isExpired() {
        return false;
    }

    @Override
    public float getRenderRadius() {
        return 20000f;
    }

    @Override
    public void render(CombatEngineLayers layer, ViewportAPI viewport) {
        if (layer != CombatEngineLayers.ABOVE_SHIPS_LAYER) return;
        if (particles.isEmpty()) return;

        SpriteAPI sprite = Global.getSettings().getSprite(SPRITE_CATEGORY, SPRITE_ID);
        if (sprite == null) return;

        float viewAlpha = viewport == null ? 1f : viewport.getAlphaMult();
        if (viewAlpha <= 0.01f) return;

        float texW = Math.max(1f, sprite.getTextureWidth());
        float texH = Math.max(1f, sprite.getTextureHeight());
        float u0 = sprite.getTexX() / texW;
        float v0 = sprite.getTexY() / texH;
        float u1 = (sprite.getTexX() + sprite.getTexWidth()) / texW;
        float v1 = (sprite.getTexY() + sprite.getTexHeight()) / texH;

        sprite.bindTexture();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);

        GL11.glBegin(GL11.GL_QUADS);
        for (Particle p : particles) {
            float remaining = 1f - p.life / p.maxLife;
            float alpha = p.alpha * p.brightness * remaining * viewAlpha;
            if (alpha <= 0.003f) continue;
            GL11.glColor4f(p.red, p.green, p.blue, alpha);

            float half = p.size * 0.5f;
            float x = p.loc.x;
            float y = p.loc.y;
            GL11.glTexCoord2f(u0, v0);
            GL11.glVertex2f(x - half, y - half);
            GL11.glTexCoord2f(u0, v1);
            GL11.glVertex2f(x - half, y + half);
            GL11.glTexCoord2f(u1, v1);
            GL11.glVertex2f(x + half, y + half);
            GL11.glTexCoord2f(u1, v0);
            GL11.glVertex2f(x + half, y - half);
        }
        GL11.glEnd();

        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glDisable(GL11.GL_BLEND);
    }

    @Override
    public void cleanup() {
        particles.clear();
    }
}
