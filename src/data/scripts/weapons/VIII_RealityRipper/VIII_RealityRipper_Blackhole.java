package data.scripts.weapons.VIII_RealityRipper;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import org.lwjgl.util.vector.Vector2f;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

/**
 * VIII_RealityRipper 光束命中点的黑洞贴图特效。
 *
 * <p>行为：</p>
 * <ul>
 *     <li>光束每命中一次就把同一个特效实例刷新到新的命中点，并重置它的淡出计时；</li>
 *     <li>只要命中持续，特效就一直存在（生命周期被不断延长）；</li>
 *     <li>停止命中后按裂隙的寿命淡出：0.6 秒后自动移除；</li>
 *     <li>贴图使用 settings.json 中注册的 {@code fx/VIII_blackhole2}，并按固定角速度顺时针旋转；</li>
 *     <li>渲染层为 {@link CombatEngineLayers#ABOVE_SHIPS_AND_MISSILES_LAYER}，低于裂隙所在的
 *     {@link CombatEngineLayers#ABOVE_PARTICLES_LOWER} 与 {@link CombatEngineLayers#ABOVE_PARTICLES}。</li>
 * </ul>
 */
public class VIII_RealityRipper_Blackhole extends BaseCombatLayeredRenderingPlugin {

    /** settings.json 中 graphics.fx 下的贴图 id。 */
    public static final String SPRITE_CATEGORY = "fx";
    public static final String SPRITE_ID = "VIII_blackhole2";

    /** 贴图显示直径（世界单位，贴图为正方形 512x512）。 */
    private static final float BASE_SIZE = 1536F;
    /** 顺时针旋转角速度（度/秒）。 */
    private static final float ROTATION_SPEED = 60.0F;
    /** 命中刷新窗口：距最后一次命中不超过该时间时视为“持续命中”。 */
    private static final float HOLD_TIME = 0.12F;
    /** 停止命中后的淡出时间，与裂隙（NegativeExplosionVisual）的寿命一致。 */
    private static final float FADE_OUT_TIME = 0.6F;
    /** 首次出现时的极短淡入，避免硬切。 */
    private static final float FADE_IN_TIME = 0.08F;

    /** 每个光源（武器）对应一个实例，避免同一次持续命中反复创建插件。 */
    private static final Map<Object, VIII_RealityRipper_Blackhole> INSTANCES =
            new HashMap<Object, VIII_RealityRipper_Blackhole>();

    private final CombatEngineAPI engine;
    private final Object key;
    private final SpriteAPI sprite;
    private final Vector2f location = new Vector2f();

    private float angle;
    private float sinceLastHit = FADE_OUT_TIME;
    private float fadeOutTimer = FADE_OUT_TIME;
    private float fadeInTimer;
    private float alpha;
    private boolean expired;

    private VIII_RealityRipper_Blackhole(CombatEngineAPI engine, Object key, Vector2f point) {
        super(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER);
        this.engine = engine;
        this.key = key;
        this.sprite = Global.getSettings().getSprite(SPRITE_CATEGORY, SPRITE_ID);
        this.location.set(point);
    }

    /**
     * 光束命中时调用：创建或刷新对应光源的黑洞特效。
     *
     * @param engine 战斗引擎
     * @param key    光源标识（通常传 {@code beam.getWeapon()}）
     * @param point  命中点（通常传 {@code beam.getTo()}）
     */
    public static void onBeamHit(CombatEngineAPI engine, Object key, Vector2f point) {
        if (engine == null || key == null || point == null) {
            return;
        }
        VIII_RealityRipper_Blackhole fx = INSTANCES.get(key);
        if (fx == null || fx.expired) {
            fx = new VIII_RealityRipper_Blackhole(engine, key, point);
            INSTANCES.put(key, fx);
            engine.addLayeredRenderingPlugin(fx);
        }
        fx.refresh(point);
    }

    private void refresh(Vector2f point) {
        location.set(point);
        sinceLastHit = 0.0F;
        fadeOutTimer = 0.0F;
    }

    @Override
    public void advance(float amount) {
        if (expired || engine.isPaused()) {
            return;
        }

        angle -= ROTATION_SPEED * amount;
        if (angle <= -360.0F) {
            angle += 360.0F;
        }

        sinceLastHit += amount;
        if (sinceLastHit < HOLD_TIME) {
            fadeInTimer += amount;
            alpha = Math.min(1.0F, fadeInTimer / FADE_IN_TIME);
            fadeOutTimer = 0.0F;
        } else {
            fadeOutTimer += amount;
            alpha = Math.max(0.0F, 1.0F - fadeOutTimer / FADE_OUT_TIME);
        }

        if (fadeOutTimer >= FADE_OUT_TIME) {
            expired = true;
            INSTANCES.remove(key);
        }
    }

    @Override
    public void render(CombatEngineLayers layer, ViewportAPI viewport) {
        if (expired || alpha <= 0.0F || sprite == null) {
            return;
        }
        if (layer != CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER) {
            return;
        }

        // 该层（ABOVE_SHIPS_AND_MISSILES_LAYER）的投影已经是世界坐标，
        // 直接传世界坐标即可；转成屏幕坐标反而会产生相机偏移。
        sprite.setSize(BASE_SIZE, BASE_SIZE);
        sprite.setAngle(angle);
        sprite.setNormalBlend();
        sprite.setAlphaMult(alpha * viewport.getAlphaMult());
        sprite.renderAtCenter(location.x, location.y);
    }

    @Override
    public EnumSet<CombatEngineLayers> getActiveLayers() {
        return EnumSet.of(CombatEngineLayers.ABOVE_SHIPS_AND_MISSILES_LAYER);
    }

    @Override
    public float getRenderRadius() {
        return BASE_SIZE;
    }

    @Override
    public boolean isExpired() {
        return expired;
    }

    @Override
    public void cleanup() {
        INSTANCES.remove(key);
    }
}
