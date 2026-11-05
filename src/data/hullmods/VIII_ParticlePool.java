package data.hullmods;

import com.fs.starfarer.api.combat.ViewportAPI;
import java.awt.Color;
import java.nio.FloatBuffer;
import java.util.Random;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

/**
 * MSR 粒子护盾使用的定容粒子池。
 *
 * <p>粒子只保存位置、速度、旋转、尺寸和颜色，绘制时统一展开成三角形提交。
 * 粒子的可见范围由渲染器的模板缓冲裁剪到舰船贴图，池本身不处理舰船逻辑。</p>
 */
public class VIII_ParticlePool {

    public static final int MAX_PARTICLES = 2000;
    private static final int[] SIDES = {3, 4, 6};

    private final Particle[] particles;
    private int count;
    private final Random random = new Random();

    public VIII_ParticlePool(int capacity) {
        int cap = Math.max(1, Math.min(MAX_PARTICLES, capacity));
        particles = new Particle[cap];
    }

    public int activeCount() {
        return count;
    }

    public void clear() {
        for (int i = 0; i < count; i++) {
            particles[i] = null;
        }
        count = 0;
    }

    public void spawnIfRoom(int sides, Vector2f position, Vector2f velocity,
                            float rotation, float angularVelocity,
                            Vector2f halfSize, float velocityStopTime,
                            float angularStopTime, float life, Color color) {
        if (count >= particles.length || position == null || halfSize == null || color == null) {
            return;
        }

        Particle p = new Particle();
        p.sides = sides;
        p.x = position.x;
        p.y = position.y;
        p.vx = velocity == null ? 0f : velocity.x;
        p.vy = velocity == null ? 0f : velocity.y;
        p.rot = rotation;
        p.angVel = angularVelocity;
        p.phase = random.nextFloat() * 6.2831855f;
        p.sx = halfSize.x;
        p.sy = halfSize.y;
        p.vStopT = Math.max(0.0001f, velocityStopTime);
        p.wStopT = Math.max(0.0001f, angularStopTime);
        p.life = Math.max(0.05f, life);
        p.age = 0f;
        p.tMove = 0f;
        p.r = color.getRed() / 255f;
        p.g = color.getGreen() / 255f;
        p.b = color.getBlue() / 255f;
        p.a = color.getAlpha() / 255f;
        particles[count++] = p;
    }

    public void update(float dt) {
        if (dt <= 0f || count == 0) {
            return;
        }

        for (int i = 0; i < count; i++) {
            Particle p = particles[i];
            p.age += dt;
            if (p.age >= p.life) {
                removeAt(i);
                i--;
                continue;
            }

            p.tMove += dt;

            float vt = Math.min(1f, p.tMove / p.vStopT);
            float vk = 1f - vt;
            vk *= vk;
            p.x += p.vx * vk * dt;
            p.y += p.vy * vk * dt;

            float wt = Math.min(1f, p.tMove / p.wStopT);
            float wk = 1f - wt;
            wk *= wk;
            p.rot += p.angVel * wk * dt;
        }
    }

    private void removeAt(int index) {
        int last = count - 1;
        particles[index] = particles[last];
        particles[last] = null;
        count = last;
    }

    public void draw(Batch batch, ViewportAPI viewport) {
        if (count == 0 || viewport == null) {
            return;
        }

        float left = viewport.getLLX() - 32f;
        float right = viewport.getLLX() + viewport.getVisibleWidth() + 32f;
        float bottom = viewport.getLLY() - 32f;
        float top = viewport.getLLY() + viewport.getVisibleHeight() + 32f;

        for (int i = 0; i < count; i++) {
            Particle p = particles[i];
            if (p.x < left || p.x > right || p.y < bottom || p.y > top) {
                continue;
            }

            float fade = 1f - p.age / p.life;
            if (fade <= 0f) {
                continue;
            }

            batch.emitPolygon(p.x, p.y, p.sx, p.sy, p.rot, p.sides,
                    p.r, p.g, p.b, p.a * fade, p.phase);
        }
    }

    public static int randomSides(Random random) {
        return SIDES[random.nextInt(SIDES.length)];
    }

    private static class Particle {
        int sides;
        float x;
        float y;
        float vx;
        float vy;
        float rot;
        float angVel;
        float phase;
        float sx;
        float sy;
        float vStopT;
        float wStopT;
        float life;
        float age;
        float tMove;
        float r;
        float g;
        float b;
        float a;
    }

    /**
     * 把粒子多边形展开为三角形，并一次性提交。
     *
     * <p>顶点格式为 x、y、r、g、b、a，颜色逐顶点提交，避免每个粒子单独切换状态。</p>
     */
    public static class Batch {

        private static final int MAX_VERTICES = MAX_PARTICLES * 18;
        private final FloatBuffer buffer = BufferUtils.createFloatBuffer(MAX_VERTICES * 6);
        private int count;

        public void reset() {
            buffer.clear();
            count = 0;
        }

        public void emitPolygon(float cx, float cy, float sx, float sy,
                                float rotation, int sides, float r, float g,
                                float b, float a, float phase) {
            if (sides < 3) {
                sides = 3;
            }

            int needed = sides * 3;
            if (count + needed > MAX_VERTICES) {
                return;
            }

            float rotationRad = rotation * 0.017453292f;
            float cos = (float) Math.cos(rotationRad);
            float sin = (float) Math.sin(rotationRad);
            float step = 6.2831855f / sides;

            float px = 0f;
            float py = 0f;
            for (int i = 0; i <= sides; i++) {
                float angle = phase + step * i;
                float lx = (float) Math.cos(angle) * sx;
                float ly = (float) Math.sin(angle) * sy;
                float wx = cx + lx * cos - ly * sin;
                float wy = cy + lx * sin + ly * cos;
                if (i > 0) {
                    vertex(cx, cy, r, g, b, a);
                    vertex(px, py, r, g, b, a);
                    vertex(wx, wy, r, g, b, a);
                }
                px = wx;
                py = wy;
            }
        }

        private void vertex(float x, float y, float r, float g, float b, float a) {
            buffer.put(x);
            buffer.put(y);
            buffer.put(r);
            buffer.put(g);
            buffer.put(b);
            buffer.put(a);
            count++;
        }

        public void drawTriangles() {
            if (count <= 0) {
                return;
            }

            buffer.flip();
            GL11.glEnableClientState(GL11.GL_VERTEX_ARRAY);
            GL11.glEnableClientState(GL11.GL_COLOR_ARRAY);
            try {
                // 顶点与颜色交错存储，步长为 6 个 float。
                buffer.position(0);
                GL11.glVertexPointer(2, 24, buffer);
                buffer.position(2);
                GL11.glColorPointer(4, 24, buffer);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, count);
            } finally {
                GL11.glDisableClientState(GL11.GL_COLOR_ARRAY);
                GL11.glDisableClientState(GL11.GL_VERTEX_ARRAY);
            }
        }
    }
}
