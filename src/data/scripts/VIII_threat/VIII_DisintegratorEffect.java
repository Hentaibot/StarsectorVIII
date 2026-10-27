package data.scripts.VIII_threat;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.util.FaderUtil;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import org.lwjgl.opengl.GL14;
import org.lwjgl.util.vector.Vector2f;

import java.awt.*;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * 瓦解者效果 —— 命中后生成一个持续产生粒子特效并持续对装甲造成伤害的层渲染效果。
 * 同时实现了 OnHitEffectPlugin（命中时触发）和 BaseCombatLayeredRenderingPlugin（自定义渲染）。
 */
public class VIII_DisintegratorEffect extends BaseCombatLayeredRenderingPlugin implements OnHitEffectPlugin {

    // 总伤害分段次数，即总 tick 数（11次）
    public static int NUM_TICKS = 11;
    // 总伤害值（与 NUM_TICKS 配合，每 tick 造成约 TOTAL_DAMAGE / NUM_TICKS 的伤害）
    public static float TOTAL_DAMAGE = 1000;

    public VIII_DisintegratorEffect() {
        // 无参构造，供 OnHitEffect 回调使用
    }

    /**
     * 可被子类覆盖的总伤害值
     */
    protected float getTotalDamage() {
        return TOTAL_DAMAGE;
    }

    /**
     * 可被子类覆盖的伤害 tick 次数
     */
    protected int getNumTicks() {
        return NUM_TICKS;
    }

    /**
     * 是否允许对船体结构造成伤害（默认 false，只伤害装甲）
     */
    protected boolean canDamageHull() {
        return false;
    }

    /**
     * 命中时回调，创建该效果实例并添加到战斗引擎的层渲染插件中。
     */
    public void onHit(DamagingProjectileAPI projectile, CombatEntityAPI target, Vector2f point,
                      boolean shieldHit, ApplyDamageResultAPI damageResult, CombatEngineAPI engine) {
        if (shieldHit) return;                     // 击中护盾不触发
        if (projectile.isFading()) return;        // 弹丸已开始消失则不触发
        if (!(target instanceof ShipAPI)) return; // 仅对舰船生效

        // 计算命中点相对于目标中心的局部偏移（逆旋转后存储）
        Vector2f offset = Vector2f.sub(point, target.getLocation(), new Vector2f());
        offset = Misc.rotateAroundOrigin(offset, -target.getFacing());

        // 创建该效果的完整实例（传入弹丸、目标、偏移量）
        VIII_DisintegratorEffect effect = new VIII_DisintegratorEffect(projectile, (ShipAPI) target, offset);
        // 将该效果作为层渲染插件添加到引擎中，返回的实体用来定位整个效果的坐标
        CombatEntityAPI e = engine.addLayeredRenderingPlugin(effect);
        // 将实体位置设为弹丸当前位置（后续 advance 中会动态更新到目标身上）
        e.getLocation().set(projectile.getLocation());
    }

    /**
     * 内部类：单颗粒子数据，控制每个光点的显示、移动、缩放、淡入淡出。
     */
    public static class VIII_ParticleData {
        public SpriteAPI sprite;          // 使用的精灵贴图
        public Vector2f offset = new Vector2f(); // 相对于效果中心的偏移
        public Vector2f vel = new Vector2f();    // 移动速度
        public float scale = 1f;                 // 当前缩放比例
        public float scaleIncreaseRate = 1f;     // 缩放变化速率
        public float turnDir = 1f;               // 旋转方向与速度（度/秒）
        public float angle = 1f;                 // 当前旋转角度

        public float maxDur;                     // 最大存在时长
        public FaderUtil fader;                  // 淡入淡出控制器
        public float elapsed = 0f;               // 已存在时间
        public float baseSize;                   // 基础大小

        public Color color = new Color(100,150,255,35); // 默认颜色（浅蓝半透明）

        /**
         * @param baseSize     基础大小
         * @param maxDur       最大存活时间（秒）
         * @param endSizeMult  最终大小倍率（>1 变大，<1 变小）
         */
        public VIII_ParticleData(float baseSize, float maxDur, float endSizeMult) {
            // 使用星空粒子纹理
            sprite = Global.getSettings().getSprite("misc", "nebula_particles");
            // 随机选择纹理中的一小块（4x4 网格）
            float i = Misc.random.nextInt(4);
            float j = Misc.random.nextInt(4);
            sprite.setTexWidth(0.25f);
            sprite.setTexHeight(0.25f);
            sprite.setTexX(i * 0.25f);
            sprite.setTexY(j * 0.25f);
            sprite.setAdditiveBlend(); // 叠加混合，产生发光效果

            angle = (float) Math.random() * 360f; // 随机初始角度

            this.maxDur = maxDur;
            // 计算每秒钟的缩放变化率
            scaleIncreaseRate = endSizeMult / maxDur;
            if (endSizeMult < 1f) {
                scaleIncreaseRate = -1f * endSizeMult;
            }
            scale = 1f;

            this.baseSize = baseSize;
            // 随机旋转方向与速度
            turnDir = Math.signum((float) Math.random() - 0.5f) * 20f * (float) Math.random();

            // 随机漂移方向
            float driftDir = (float) Math.random() * 360f;
            vel = Misc.getUnitVectorAtDegreeAngle(driftDir);
            // 移动速度基于基础大小和最大时长，并添加随机性
            vel.scale(0.25f * baseSize / maxDur * (1f + (float) Math.random() * 1f));

            fader = new FaderUtil(0f, 0.5f, 0.5f); // 淡入淡出总时长0.5秒
            fader.forceOut();  // 初始为完全透明
            fader.fadeIn();    // 开始淡入
        }

        /**
         * 每帧更新：缩放、位移、旋转、淡入淡出。
         */
        public void advance(float amount) {
            scale += scaleIncreaseRate * amount;

            offset.x += vel.x * amount;
            offset.y += vel.y * amount;

            angle += turnDir * amount;

            elapsed += amount;
            // 最后0.6秒（0.5+0.1容差）开始淡出
            if (maxDur - elapsed <= fader.getDurationOut() + 0.1f) {
                fader.fadeOut();
            }
            fader.advance(amount);
        }
    }

    protected List<VIII_ParticleData> particles = new ArrayList<VIII_ParticleData>(); // 当前所有活跃粒子
    protected DamagingProjectileAPI proj; // 触发效果的弹丸
    protected ShipAPI target;             // 目标舰船
    protected Vector2f offset;           // 命中点相对于目标中心的局部偏移
    protected int ticks = 0;             // 已执行的伤害 tick 次数
    protected IntervalUtil interval;     // 伤害间隔计时器
    protected FaderUtil fader = new FaderUtil(1f, 0.5f, 0.5f); // 整体效果的淡入淡出（用于音量等）

    /**
     * 全参数构造，创建针对特定目标的效果实例。
     */
    public VIII_DisintegratorEffect(DamagingProjectileAPI proj, ShipAPI target, Vector2f offset) {
        this.proj = proj;
        this.target = target;
        this.offset = offset;

        // 每 0.8~1 秒触发一次伤害 tick
        interval = new IntervalUtil(0.8f, 1f);
        interval.forceIntervalElapsed(); // 第一次立即触发
    }

    /**
     * 渲染半径，用于判断视口裁剪。
     */
    public float getRenderRadius() {
        return 500f;
    }

    // 指定渲染层：指示器下方（避免遮挡重要 UI）
    protected EnumSet<CombatEngineLayers> layers = EnumSet.of(CombatEngineLayers.BELOW_INDICATORS_LAYER);

    @Override
    public EnumSet<CombatEngineLayers> getActiveLayers() {
        return layers;
    }

    /**
     * 初始化，保留父类行为。
     */
    public void init(CombatEntityAPI entity) {
        super.init(entity);
    }

    /**
     * 每帧逻辑：更新效果位置、粒子生命周期、伤害计时、循环音效。
     */
    public void advance(float amount) {
        if (Global.getCombatEngine().isPaused()) return;

        // 将存储的局部偏移转为世界坐标
        Vector2f loc = new Vector2f(offset);
        loc = Misc.rotateAroundOrigin(loc, target.getFacing());
        Vector2f.add(target.getLocation(), loc, loc);
        entity.getLocation().set(loc); // 更新效果的实体位置

        // 清理已过期的粒子
        List<VIII_ParticleData> remove = new ArrayList<VIII_ParticleData>();
        for (VIII_ParticleData p : particles) {
            p.advance(amount);
            if (p.elapsed >= p.maxDur) {
                remove.add(p);
            }
        }
        particles.removeAll(remove);

        // 整体效果淡出控制（用于音量）
        float volume = 1f;
        if (ticks >= getNumTicks() || !target.isAlive() || !Global.getCombatEngine().isEntityInPlay(target)) {
            fader.fadeOut();
            fader.advance(amount);
            volume = fader.getBrightness();
        }
        // 持续播放循环音效，音量和位置跟随效果
        Global.getSoundPlayer().playLoop(getSoundLoopId(), target, 1f, volume, loc, target.getVelocity());

        // 按间隔触发一次伤害 tick
        interval.advance(amount);
        if (interval.intervalElapsed() && ticks < getNumTicks()) {
            dealDamage();
            ticks++;
        }
    }

    /**
     * 循环音效 ID（可被子类覆盖）
     */
    protected String getSoundLoopId() {
        return "disintegrator_loop";
    }

    /**
     * 每次 tick 生成的粒子数量（可被子类覆盖）
     */
    protected int getNumParticlesPerTick() {
        return 3;
    }

    /**
     * 添加一个默认参数的粒子。
     */
    protected void addParticle() {
        VIII_ParticleData p = new VIII_ParticleData(30f, 3f + (float) Math.random() * 2f, 2f);
        particles.add(p);
        p.offset = Misc.getPointWithinRadius(p.offset, 20f);
    }

    /**
     * 伤害生效后的回调（子类可重写以实现额外效果）
     */
    protected void damageDealt(Vector2f loc, float hullDamage, float armorDamage) {
    }

    /**
     * 执行一次伤害 tick：在命中点周围 5x5 装甲格区域造成装甲伤害，
     * 并且如果允许（canDamageHull）则对船体结构造成溢出伤害。
     */
    protected void dealDamage() {
        CombatEngineAPI engine = Global.getCombatEngine();

        // 生成本次 tick 的粒子特效
        int num = getNumParticlesPerTick();
        for (int i = 0; i < num; i++) {
            addParticle();
        }

        Vector2f point = new Vector2f(entity.getLocation());

        ArmorGridAPI grid = target.getArmorGrid();
        int[] cell = grid.getCellAtLocation(point);
        if (cell == null) return; // 无法定位到装甲格则跳过

        int gridWidth = grid.getGrid().length;
        int gridHeight = grid.getGrid()[0].length;

        // 伤害倍率（考虑源与目标的伤害加成）
        float damageTypeMult = getDamageTypeMult(proj.getSource(), target);

        float damagePerTick = (float) getTotalDamage() / (float) getNumTicks();
        float damageDealt = 0f;
        float hullDamage = 0f;

        // 遍历中心及其周围 5x5 区域（但排除四角）
        for (int i = -2; i <= 2; i++) {
            for (int j = -2; j <= 2; j++) {
                if ((i == 2 || i == -2) && (j == 2 || j == -2)) continue; // 跳过角落

                int cx = cell[0] + i;
                int cy = cell[1] + j;

                if (cx < 0 || cx >= gridWidth || cy < 0 || cy >= gridHeight) continue;

                // 不同位置的伤害倍率：中心及相邻一格 1/15，最外层 1/30
                float damMult = 1/30f;
                if (i == 0 && j == 0) {
                    damMult = 1/15f;
                } else if (i <= 1 && i >= -1 && j <= 1 && j >= -1) {
                    damMult = 1/15f;
                } else {
                    damMult = 1/30f;
                }

                float armorInCell = grid.getArmorValue(cx, cy);
                float damage = damagePerTick * damMult * damageTypeMult;

                // 如果伤害超过单元格剩余装甲且允许伤害船体，则溢出部分转为船体伤害
                if (damage > armorInCell && canDamageHull()) {
                    hullDamage += damage - armorInCell;
                }
                damage = Math.min(damage, armorInCell);
                if (damage <= 0) continue;

                target.getArmorGrid().setArmorValue(cx, cy, Math.max(0, armorInCell - damage));
                damageDealt += damage;
            }
        }

        // 显示装甲伤害浮动数字
        if (damageDealt > 0) {
            if (Misc.shouldShowDamageFloaty(proj.getSource(), target)) {
                engine.addFloatingDamageText(point, damageDealt, 0f, Misc.FLOATY_ARMOR_DAMAGE_COLOR, target, proj.getSource());
            }
            target.syncWithArmorGridState(); // 刷新装甲视觉状态
        }

        // 应用船体伤害
        if (hullDamage > 1f) {
            float showHullDamage = Math.min(hullDamage, target.getHitpoints());
            if (showHullDamage >= 0) {
                target.setHitpoints(target.getHitpoints() - hullDamage);
                // 如果伤害导致船体结构 ≤ 0，则摧毁目标
                if (target.getHitpoints() <= 0f && !target.isHulk()) {
                    target.setSpawnDebris(false); // 禁止产生残骸
                    engine.applyDamage(target, point, 100f, DamageType.ENERGY, 0f, true, false, proj.getSource(), false);
                }
                if (Misc.shouldShowDamageFloaty(proj.getSource(), target)) {
                    Vector2f p2 = new Vector2f(point);
                    p2.y += 20f; // 偏移避免重叠
                    engine.addFloatingDamageText(p2, hullDamage, 0f, Misc.FLOATY_HULL_DAMAGE_COLOR, target, proj.getSource());
                }
            }
        }

        damageDealt(point, hullDamage, damageDealt);
    }

    /**
     * 效果是否已完全结束：无粒子、且已完成所有 tick 或目标不可用。
     */
    public boolean isExpired() {
        return particles.isEmpty() &&
                (ticks >= getNumTicks() || !target.isAlive() || !Global.getCombatEngine().isEntityInPlay(target));
    }

    /**
     * 自定义渲染：将每个粒子的精灵绘制到指定视口。
     * 使用反向减法混合模式，产生特殊的能量溶解视觉效果。
     */
    public void render(CombatEngineLayers layer, ViewportAPI viewport) {
        float x = entity.getLocation().x;
        float y = entity.getLocation().y;

        float b = viewport.getAlphaMult();

        // 设置混合方程式为反向减法（使粒子表现为“侵蚀”效果）
        GL14.glBlendEquation(GL14.GL_FUNC_REVERSE_SUBTRACT);

        for (VIII_ParticleData p : particles) {
            float size = p.baseSize * p.scale;

            Vector2f loc = new Vector2f(x + p.offset.x, y + p.offset.y);

            float alphaMult = 1f;

            p.sprite.setAngle(p.angle);
            p.sprite.setSize(size, size);
            p.sprite.setAlphaMult(b * alphaMult * p.fader.getBrightness());
            p.sprite.setColor(p.color);
            p.sprite.renderAtCenter(loc.x, loc.y);
        }

        // 恢复默认加法混合
        GL14.glBlendEquation(GL14.GL_FUNC_ADD);
    }

    /**
     * 计算源对目标装甲伤害的最终倍率。
     * 考虑目标装甲承受倍率、源对不同舰船尺寸的伤害加成。
     */
    public static float getDamageTypeMult(ShipAPI source, ShipAPI target) {
        if (source == null || target == null) return 1f;

        float damageTypeMult = target.getMutableStats().getArmorDamageTakenMult().getModifiedValue();
        switch (target.getHullSize()) {
            case CAPITAL_SHIP:
                damageTypeMult *= source.getMutableStats().getDamageToCapital().getModifiedValue();
                break;
            case CRUISER:
                damageTypeMult *= source.getMutableStats().getDamageToCruisers().getModifiedValue();
                break;
            case DESTROYER:
                damageTypeMult *= source.getMutableStats().getDamageToDestroyers().getModifiedValue();
                break;
            case FRIGATE:
                damageTypeMult *= source.getMutableStats().getDamageToFrigates().getModifiedValue();
                break;
            case FIGHTER:
                damageTypeMult *= source.getMutableStats().getDamageToFighters().getModifiedValue();
                break;
        }
        return damageTypeMult;
    }

    public Vector2f getOffset() {
        return offset;
    }

    public void setOffset(Vector2f offset) {
        this.offset = offset;
    }
}