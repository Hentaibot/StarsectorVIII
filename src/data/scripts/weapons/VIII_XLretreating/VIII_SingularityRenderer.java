package data.scripts.weapons.VIII_XLretreating;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseCombatLayeredRenderingPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import java.awt.Color;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.util.vector.Vector2f;

/**
 * 奇点渲染器插件：负责绘制黑洞/奇点的引力透镜效果，并管理其生命周期（成长、合并、蒸发、坍缩）。
 * 继承自BaseCombatLayeredRenderingPlugin，在战斗场景的特定图层进行渲染。
 */
public final class VIII_SingularityRenderer extends BaseCombatLayeredRenderingPlugin {
    // 在引擎自定义数据中存储此渲染器实例的键
    public static final String RENDER_KEY = "VIII_SingularityRenderer";
    private static final Logger LOG = Global.getLogger(VIII_SingularityRenderer.class); // 日志记录器

    private final CombatEngineAPI engine = Global.getCombatEngine(); // 当前战斗引擎
    // 由弹丸追踪的奇点数据，键为弹丸对象
    private final Map<DamagingProjectileAPI, SingularityData> active = new HashMap();
    // 由任意对象令牌手动追踪的奇点数据（例如用于非弹丸的奇点）
    private final Map<Object, SingularityData> manual = new HashMap();

    // 着色器程序ID
    private int program = 0;
    // 着色器uniform变量位置数组
    private int[] uniform = null;
    // 屏幕纹理ID，用于捕获当前屏幕内容作为引力透镜的输入
    private int screenTexId = 0;
    // 屏幕纹理的宽度和高度
    private int screenTexW = 0;
    private int screenTexH = 0;

    // 各种常量参数（见下文注释）
    private static final float DEFAULT_EVENT_HORIZON_RADIUS = 180.0F;          // 默认事件视界半径（世界单位）
    private static final float DEFAULT_LENS_STRENGTH = 50.0F;                  // 默认透镜强度（未使用？）
    private static final float DEFAULT_LENS_SIZE_MULT = 2.8F;                  // 默认透镜尺寸乘数（未使用？）
    private static final float DEFAULT_DARK_ALPHA = 1.0F;                      // 默认黑暗区域透明度（未使用？）
    private static final float CAPTURE_MARGIN = 4.0F;                          // 捕获边距（未使用？）
    private static final float MASS_GROWTH_PER_KILL = 0.35F;                   // 每次击杀增加的质量倍数（未使用？）
    private static final float MASS_GROWTH_PER_SECOND = 0.05F;                 // 每秒质量增长倍数（未使用？）
    private static final float MAX_MASS_MULT = 3.5F;                           // 最大质量倍数
    private static final float ASSIMILATION_BASE_FRAC_PER_SEC = 0.25F;         // 基础同化速率（每秒）
    private static final float ASSIMILATION_SQRT_MULT = 0.65F;                 // 同化速率平方根系数
    private static final float ASSIMILATION_MAX_FRAC = 0.95F;                  // 最大同化比例
    private static final float DEFAULT_BASE_RADIUS_PX = 10.0F;                 // 默认基础半径（像素）
    private static final float DEFAULT_GROWTH_PX_PER_SEC = 2.0F;               // 默认每秒增长像素
    private static final float EINSTEIN_SCALE = 0.03F;                         // 爱因斯坦尺度（未使用？）
    private static final float MASS_NORMALIZER = 10000.0F;                     // 质量归一化因子（将质量除以该值得到mHat）
    private static final float RS_PIXELS_PER_MHAT = 40.0F;                     // 每单位mHat对应的史瓦西半径像素数
    private static final float SHADOW_MULT = 2.6F;                             // 阴影乘数（用于计算rsPhysicalPx）
    private static final float THETA_E_COEFF = 90.0F;                          // 爱因斯坦环角度系数
    private static final float RMAX_COEFF = 3.5F;                              // 最大半径系数
    private static final float COLLAPSE_THRESHOLD_PX = 100.0F;                 // 触发坍缩的半径阈值（像素）
    private static final float COLLAPSE_SHRINK_PX_PER_SEC = 140.0F;            // 坍缩后收缩速度（像素/秒）
    private static final float TRAVEL_MASS_PER_WORLD = 2.0F;                   // 每移动单位距离增加的质量（用于弹丸移动质量累积）
    private static final float RADIUS_SMOOTH_LERP = 5.0F;                      // 半径平滑插值时间常数（未使用？）
    private static final float NO_COLLAPSE_MAX_RS_PX = 250.0F;                 // 禁用坍缩时最大半径限制
    private static boolean COLLAPSE_ENABLED = false;                           // 是否启用坍缩（可通过setter修改）
    private static final float MERGE_DISTANCE_PX = 20.0F;                      // 奇点合并的像素距离阈值
    private static final float SUPER_NOVA_MASS_THRESHOLD = 50000.0F;           // 触发超新星爆炸的质量阈值
    private float elapsed = 0.0F;                                              // 累计经过时间（用于日志间隔控制）
    private static final float LOG_INTERVAL = 0.5F;                            // 日志输出间隔（秒）

    /**
     * 获取或创建全局唯一的奇点渲染器实例。
     * 如果引擎中已存在，则返回现有实例；否则创建新实例并添加到引擎。
     */
    public static VIII_SingularityRenderer getOrCreate() {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) {
            return null;
        } else {
            Object existing = engine.getCustomData().get("VIII_SingularityRenderer");
            if (existing instanceof VIII_SingularityRenderer) {
                return (VIII_SingularityRenderer)existing;
            } else {
                VIII_SingularityRenderer renderer = new VIII_SingularityRenderer();
                engine.addLayeredRenderingPlugin(renderer);
                engine.getCustomData().put("VIII_SingularityRenderer", renderer);
                return renderer;
            }
        }
    }

    /**
     * 私有构造方法，设置渲染图层并初始化着色器。
     */
    private VIII_SingularityRenderer() {
        this.layer = CombatEngineLayers.JUST_BELOW_WIDGETS; // 在部件下方渲染
        this.initShader(); // 初始化着色器程序
    }

    /**
     * 使用默认参数追踪一个弹丸（奇点）。
     * @param proj 弹丸对象
     */
    public void trackProjectile(DamagingProjectileAPI proj) {
        this.trackProjectile(proj, 180.0F, 50.0F, new Color(255, 180, 80, 255));
        if (proj != null) {
            LOG.info("[MOCI_SINGULARITY] trackProjectile id=" + proj.getProjectileSpecId());
        } else {
            LOG.warn("[MOCI_SINGULARITY] trackProjectile null projectile");
        }
    }

    /**
     * 使用自定义参数追踪一个弹丸。
     * @param proj 弹丸
     * @param eventHorizonRadius 事件视界半径（世界单位，但实际未使用？）
     * @param lensStrength 透镜强度（未使用？）
     * @param accretionColor 吸积盘颜色（未使用？）
     */
    public void trackProjectile(DamagingProjectileAPI proj, float eventHorizonRadius, float lensStrength, Color accretionColor) {
        if (proj != null) {
            if (!this.active.containsKey(proj)) {
                this.active.put(proj, new SingularityData(proj, eventHorizonRadius, lensStrength, accretionColor));
            }
        }
    }

    /**
     * 使用一个令牌手动追踪一个奇点（不依赖弹丸）。
     * @param token 任意对象作为标识
     * @param initialCenter 初始世界坐标中心
     */
    public void trackAt(Object token, Vector2f initialCenter) {
        if (token != null && initialCenter != null) {
            if (!this.manual.containsKey(token)) {
                SingularityData s = new SingularityData((DamagingProjectileAPI)null, 180.0F, 50.0F, new Color(255, 180, 80, 255));
                s.token = token;
                s.manualCenter.set(initialCenter);
                this.manual.put(token, s);
            }
        }
    }

    /**
     * 更新手动追踪奇点的中心位置。
     * @param token 令牌
     * @param center 新中心
     */
    public void updateCenter(Object token, Vector2f center) {
        SingularityData s = (SingularityData)this.manual.get(token);
        if (s != null && center != null) {
            s.manualCenter.set(center);
        }
    }

    /**
     * 每帧更新所有奇点的状态（质量、半径、蒸发、坍缩、合并等）。
     * @param amount 时间增量（秒）
     */
    public void advance(float amount) {
        // 仅在引擎存在且未暂停时更新
        if (this.engine != null && !this.engine.isPaused()) {
            this.elapsed += amount; // 累计时间

            // 遍历所有活跃的弹丸奇点，检查是否需要清理（弹丸消失、蒸发完成等）
            Iterator<Map.Entry<DamagingProjectileAPI, SingularityData>> it = this.active.entrySet().iterator();
            while(it.hasNext()) {
                Map.Entry<DamagingProjectileAPI, SingularityData> e = (Map.Entry)it.next();
                DamagingProjectileAPI p = (DamagingProjectileAPI)e.getKey();
                SingularityData s = (SingularityData)e.getValue();

                // 判断是否允许生命周期清理：未在坍缩、未坍缩完成，或者坍缩完成后持有时间结束且平滑半径接近0
                boolean allowLifecycleCleanup = !s.collapsing && (!s.collapsed || s.afterCollapseHold <= 0.0F && s.smoothedRsPx <= 0.5F);
                // 判断弹丸是否结束（淡出、过期或无亮度）
                boolean projEnded = p != null && (p.isFading() || p.isExpired() || p.getBrightness() <= 0.0F);
                // 如果弹丸结束且奇点未蒸发、未坍缩、未坍缩完成，则进入蒸发阶段
                if (projEnded && !s.evaporating && !s.collapsing && !s.collapsed) {
                    s.evaporating = true;
                    s.evaporateProgress = 0.0F;
                    if (p != null) {
                        s.frozenCenter.set(p.getLocation()); // 冻结当前位置
                        s.freezeCenter = true;
                    }
                    LOG.info("[MOCI_SINGULARITY] enter evaporation phase");
                }

                // 判断蒸发是否完成：进度>=0.999 或 平滑半径<=0.5
                boolean evapDone = s.evaporating && (s.evaporateProgress >= 0.999F || s.smoothedRsPx <= 0.5F);
                // 清理条件：弹丸为null、已造成伤害、蒸发完成，或者允许生命周期清理且弹丸结束但未蒸发
                boolean shouldCleanup = p == null || p.didDamage() || evapDone || allowLifecycleCleanup && projEnded && !s.evaporating;
                if (shouldCleanup) {
                    // 删除该奇点关联的纹理
                    if (s.texId != 0) {
                        try {
                            GL11.glDeleteTextures(s.texId);
                        } catch (Exception var17) {
                        }
                    }
                    // 对受影响的飞船应用速度阻尼
                    this.applyVelocityDampingToAffectedShips(s);
                    // 记录清理原因
                    String reason = p == null ? "null" : (p.didDamage() ? "didDamage" : (allowLifecycleCleanup && p.isFading() ? "isFading" : (allowLifecycleCleanup && p.isExpired() ? "isExpired" : (allowLifecycleCleanup && p.getBrightness() <= 0.0F ? "noBrightness" : "unknown"))));
                    String pid = p != null ? p.getProjectileSpecId() : "null";
                    LOG.info("[MOCI_SINGULARITY] cleanup projectile id=" + pid + " reason=" + reason + " texId=" + s.texId + " collapsing=" + s.collapsing + " collapsed=" + s.collapsed + " rsPx=" + s.smoothedRsPx);
                    it.remove(); // 从活跃映射中移除
                }
            }

            // 对每个活跃的弹丸奇点进行状态更新
            for(SingularityData s : this.active.values()) {
                // 质量倍数随时间增长，上限MAX_MASS_MULT
                s.massMult = Math.min(3.5F, s.massMult + 0.05F * amount);
                // 当前半径按照增长速率增加（像素）
                s.currentRadiusPx += s.growthPxPerSec * amount;

                try {
                    // 如果未冻结中心且弹丸存在，计算移动距离并累积到massCache（旅行质量）
                    if (!s.freezeCenter && s.projectile != null) {
                        Vector2f cur = s.projectile.getLocation();
                        if (!s.lastCenterInit) {
                            s.lastCenterWorld.set(cur);
                            s.lastCenterInit = true;
                        } else {
                            float dx = cur.x - s.lastCenterWorld.x;
                            float dy = cur.y - s.lastCenterWorld.y;
                            float dist = (float)Math.sqrt((double)(dx * dx + dy * dy));
                            if (dist > 0.0F) {
                                s.lastCenterWorld.set(cur);
                                s.massCache += dist * 2.0F; // 每移动1单位距离增加2质量
                            }
                        }
                    }
                } catch (Throwable var16) {
                }

                // 计算归一化质量 mHat = massGrowth / 10000
                float mHatGrowth = Math.max(0.0F, s.massGrowth) / 10000.0F;
                // 同化速率：基于mHatGrowth的平方根计算，限制在0~0.95之间
                float rate = 0.25F * (1.0F + 0.65F * (float)Math.sqrt((double)Math.max(0.0F, mHatGrowth)));
                rate = Math.min(0.95F, Math.max(0.0F, rate));
                // 将massCache中的质量按速率转移到massGrowth
                float moved = Math.min(s.massCache, s.massCache * rate * amount);
                if (moved > 0.0F) {
                    s.massCache -= moved;
                    s.massGrowth += moved;
                }

                // 重新计算mHatGrowth用于平滑插值因子
                mHatGrowth = 1.0F - (float)Math.exp((double)(-amount / Math.max(0.001F, 5.0F)));
                rate = Math.max(0.0F, s.massGrowth) / 10000.0F;
                moved = Math.min(rate, 25.0F);
                // 根据质量计算物理半径（像素）：SHADOW_MULT * RS_PIXELS_PER_MHAT * mHat（但限制moved最大25）
                float rsPhysicalPx = 2.6F * 40.0F * moved;

                // 当前半径乘以质量倍数得到增长半径
                float growthPx = s.currentRadiusPx * s.massMult;
                float targetPx; // 目标平滑半径

                if (s.collapsed) {
                    // 已坍缩：目标半径为10像素（极小）
                    targetPx = 10.0F;
                } else if (s.collapsing) {
                    // 坍缩中：目标半径保持当前平滑半径（？实际下面有插值逻辑）
                    targetPx = s.smoothedRsPx;
                } else if (s.evaporating) {
                    // 蒸发中：进度增加，质量减少，半径平滑减小
                    s.evaporateProgress = Math.min(1.0F, s.evaporateProgress + amount * 0.6F);
                    s.massGrowth = Math.max(0.0F, s.massGrowth * (1.0F - 0.6F * amount));
                    s.massCache = Math.max(0.0F, s.massCache * (1.0F - 0.6F * amount));
                    float targetEvap = Math.max(0.0F, s.smoothedRsPx * (1.0F - 0.7F * amount));
                    targetPx = Math.min(s.smoothedRsPx, targetEvap);
                } else {
                    // 正常状态：目标半径为增长半径和物理半径的最大值
                    targetPx = Math.max(growthPx, rsPhysicalPx);
                    if (!COLLAPSE_ENABLED) {
                        targetPx = Math.min(targetPx, 250.0F); // 未启用坍缩时，限制最大250像素
                    }
                }

                // 使用指数平滑更新平滑半径
                s.smoothedRsPx += (targetPx - s.smoothedRsPx) * mHatGrowth;

                // 检查是否触发坍缩（启用坍缩、未坍缩、未坍缩中、平滑半径达到阈值）
                if (COLLAPSE_ENABLED && !s.collapsed && !s.collapsing && s.smoothedRsPx >= 100.0F) {
                    s.collapsing = true;
                    s.collapseStartRsPx = s.smoothedRsPx;
                    s.collapseProgress = 0.0F;
                    if (s.projectile != null) {
                        s.frozenCenter.set(s.projectile.getLocation());
                        s.freezeCenter = true;
                    }
                    LOG.info("[MOCI_SINGULARITY] collapse start (projectile) rsPx=" + s.smoothedRsPx);
                }

                // 坍缩过程中的处理
                if (s.collapsing && !s.collapsed) {
                    float target = 10.0F; // 最终目标半径
                    // 向目标平滑移动，插值速度更快（mHatGrowth*2.5）
                    s.smoothedRsPx += (target - s.smoothedRsPx) * Math.min(1.0F, mHatGrowth * 2.5F);
                    float span = Math.max(1.0F, s.collapseStartRsPx - 10.0F); // 坍缩跨度
                    s.collapseProgress = Math.min(1.0F, 1.0F - (s.smoothedRsPx - 10.0F) / span); // 计算进度（0~1）
                    int step = (int)Math.floor((double)(s.collapseProgress * 5.0F)); // 将进度分为5步用于日志
                    if (step > s.collapseStepLogged) {
                        s.collapseStepLogged = step;
                        LOG.info("[MOCI_SINGULARITY] collapse progress (projectile) step=" + step + "/5 rsPx=" + s.smoothedRsPx + " progress=" + s.collapseProgress);
                    }
                    // 如果接近目标，则完成坍缩
                    if (s.smoothedRsPx <= 10.5F) {
                        s.smoothedRsPx = 10.0F;
                        s.collapseProgress = 1.0F;
                        s.collapsing = false;
                        s.collapsed = true;
                        s.afterCollapseHold = 0.5F; // 坍缩后保持时间
                        LOG.info("[MOCI_SINGULARITY] collapse completed (projectile)");
                    }
                }

                // 如果已坍缩，半径逐渐缩小至0（收缩）
                if (s.collapsed) {
                    if (s.smoothedRsPx > 0.0F) {
                        s.smoothedRsPx = Math.max(0.0F, s.smoothedRsPx - 140.0F * amount);
                    }
                    if (s.afterCollapseHold > 0.0F) {
                        s.afterCollapseHold -= amount;
                    }
                    // 此处原代码有if(p != null && s.afterCollapseHold <= 0.0F)但无操作，可能遗留
                    DamagingProjectileAPI p = s.projectile;
                    if (p != null && s.afterCollapseHold <= 0.0F) {
                    }
                }

                // 每隔LOG_INTERVAL输出调试日志
                if (this.elapsed - s.lastLogAt >= 0.5F) {
                    s.lastLogAt = this.elapsed;
                    rate = Math.max(0.0F, s.massGrowth) / 10000.0F;
                    rsPhysicalPx = 2.6F * 40.0F * Math.min(rate, 25.0F);
                    LOG.debug("[MOCI_SINGULARITY] diag P rsSmooth=" + s.smoothedRsPx + " rsPhys=" + rsPhysicalPx + " mGrowth=" + s.massGrowth + " mCache=" + s.massCache + " mHat=" + rate + " collapsing=" + s.collapsing + " collapsed=" + s.collapsed);
                }
            }

            // 检查并合并相近的奇点（仅当未蒸发/坍缩/坍缩完成）
            List<SingularityData> list = new ArrayList(this.active.values());
            for(int i = 0; i < list.size(); ++i) {
                SingularityData a = (SingularityData)list.get(i);
                if (!a.evaporating && !a.collapsing && !a.collapsed) {
                    Vector2f ca = a.freezeCenter ? a.frozenCenter : (a.projectile != null ? a.projectile.getLocation() : null);
                    if (ca != null) {
                        for(int j = i + 1; j < list.size(); ++j) {
                            SingularityData b = (SingularityData)list.get(j);
                            if (!b.evaporating && !b.collapsing && !b.collapsed) {
                                Vector2f cb = b.freezeCenter ? b.frozenCenter : (b.projectile != null ? b.projectile.getLocation() : null);
                                if (cb != null) {
                                    float dx = ca.x - cb.x;
                                    float dy = ca.y - cb.y;
                                    float dist = (float)Math.sqrt((double)(dx * dx + dy * dy));
                                    // 将世界距离转换为像素距离（基于屏幕分辨率）
                                    float pixelsPerWorldUnit = (float)this.screenTexW / Math.max(1.0F, Global.getCombatEngine().getViewport().getVisibleWidth());
                                    float dpix = dist * pixelsPerWorldUnit;
                                    if (dpix <= 20.0F) { // 像素距离小于阈值
                                        float totalGrowth = a.massGrowth + b.massGrowth;
                                        if (totalGrowth >= 50000.0F) {
                                            this.triggerSuperExplosion(a, b, ca); // 质量过大触发超新星爆炸
                                        } else {
                                            this.mergeSingularities(a, b, ca, cb); // 否则合并
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 再次遍历，清理已经坍缩或蒸发完成的奇点（删除纹理并移除）
            Iterator<Map.Entry<DamagingProjectileAPI, SingularityData>> it2 = this.active.entrySet().iterator();
            while(it2.hasNext()) {
                Map.Entry<DamagingProjectileAPI, SingularityData> en = (Map.Entry)it2.next();
                SingularityData s = (SingularityData)en.getValue();
                boolean collapseClean = s.collapsed && s.afterCollapseHold <= 0.0F && s.smoothedRsPx <= 0.5F;
                boolean evapClean = s.evaporating && (s.evaporateProgress >= 0.999F || s.smoothedRsPx <= 0.5F);
                if (collapseClean || evapClean) {
                    if (s.texId != 0) {
                        try {
                            GL11.glDeleteTextures(s.texId);
                            LOG.info("[MOCI_SINGULARITY] deleted singularity texId=" + s.texId);
                        } catch (Exception var15) {
                        }
                    }
                    this.applyVelocityDampingToAffectedShips(s);
                    it2.remove();
                    LOG.info("[MOCI_SINGULARITY] untracked after collapse (visuals cleaned)");
                }
            }

            // 对手动追踪的奇点进行类似的状态更新（与上面类似，但中心来自manualCenter）
            for(SingularityData s : this.manual.values()) {
                s.massMult = Math.min(3.5F, s.massMult + 0.05F * amount);
                s.currentRadiusPx += s.growthPxPerSec * amount;
                float mHatGrowth = Math.max(0.0F, s.massGrowth) / 10000.0F;
                float rate = 0.25F * (1.0F + 0.65F * (float)Math.sqrt((double)Math.max(0.0F, mHatGrowth)));
                rate = Math.min(0.95F, Math.max(0.0F, rate));
                float moved = Math.min(s.massCache, s.massCache * rate * amount);
                if (moved > 0.0F) {
                    s.massCache -= moved;
                    s.massGrowth += moved;
                }

                mHatGrowth = 1.0F - (float)Math.exp((double)(-amount / Math.max(0.001F, 5.0F)));
                rate = Math.max(0.0F, s.massGrowth) / 10000.0F;
                moved = Math.min(rate, 25.0F);
                float rsPhysicalPx = 2.6F * 40.0F * moved;
                float growthPx = !s.collapsing && !s.collapsed ? s.currentRadiusPx * s.massMult : s.smoothedRsPx;
                float targetPx = s.collapsed ? 10.0F : Math.max(growthPx, rsPhysicalPx);
                if (!COLLAPSE_ENABLED) {
                    targetPx = Math.min(targetPx, 250.0F);
                }

                s.smoothedRsPx += (targetPx - s.smoothedRsPx) * mHatGrowth;

                if (COLLAPSE_ENABLED && !s.collapsed && !s.collapsing && s.smoothedRsPx >= 100.0F) {
                    s.collapsing = true;
                    s.collapseStartRsPx = s.smoothedRsPx;
                    s.collapseProgress = 0.0F;
                    LOG.info("[MOCI_SINGULARITY] collapse start (manual) rsPx=" + s.smoothedRsPx);
                }

                if (s.collapsing && !s.collapsed) {
                    float target = 10.0F;
                    s.smoothedRsPx += (target - s.smoothedRsPx) * Math.min(1.0F, mHatGrowth * 2.5F);
                    float span = Math.max(1.0F, s.collapseStartRsPx - 10.0F);
                    s.collapseProgress = Math.min(1.0F, 1.0F - (s.smoothedRsPx - 10.0F) / span);
                    int step = (int)Math.floor((double)(s.collapseProgress * 5.0F));
                    if (step > s.collapseStepLogged) {
                        s.collapseStepLogged = step;
                        LOG.info("[MOCI_SINGULARITY] collapse progress (manual) step=" + step + "/5 rsPx=" + s.smoothedRsPx + " progress=" + s.collapseProgress);
                    }

                    if (s.smoothedRsPx <= 10.5F) {
                        s.smoothedRsPx = 10.0F;
                        s.collapseProgress = 1.0F;
                        s.collapsing = false;
                        s.collapsed = true;
                        LOG.info("[MOCI_SINGULARITY] collapse completed (manual)");
                    }
                }

                if (s.collapsed && s.smoothedRsPx > 0.0F) {
                    s.smoothedRsPx = Math.max(0.0F, s.smoothedRsPx - 140.0F * amount);
                }

                if (this.elapsed - s.lastLogAt >= 0.5F) {
                    s.lastLogAt = this.elapsed;
                    rate = Math.max(0.0F, s.massGrowth) / 10000.0F;
                    rsPhysicalPx = 2.6F * 40.0F * Math.min(rate, 25.0F);
                    LOG.debug("[MOCI_SINGULARITY] diag M rsSmooth=" + s.smoothedRsPx + " rsPhys=" + rsPhysicalPx + " mGrowth=" + s.massGrowth + " mCache=" + s.massCache + " mHat=" + rate + " collapsing=" + s.collapsing + " collapsed=" + s.collapsed);
                }
            }
        }
    }

    /**
     * 渲染奇点的引力透镜效果。
     * 该操作在JUST_BELOW_WIDGETS图层执行，捕获屏幕内容，然后使用着色器对每个奇点进行扭曲。
     * @param layer 当前渲染的图层
     * @param viewport 视口信息
     */
    public void render(CombatEngineLayers layer, ViewportAPI viewport) {
        if (layer == CombatEngineLayers.JUST_BELOW_WIDGETS) {
            if (!this.active.isEmpty() || !this.manual.isEmpty()) {
                // 如果正在显示命令界面或部署对话框，跳过渲染
                if (this.engine != null && this.engine.getCombatUI() != null && this.engine.getCombatUI().isShowingCommandUI()) {
                    LOG.debug("[MOCI_SINGULARITY] skipping render: command UI showing");
                } else if (this.engine != null && this.engine.getCombatUI() != null && this.engine.getCombatUI().isShowingDeploymentDialog()) {
                    LOG.debug("[MOCI_SINGULARITY] skipping render: deployment dialog showing");
                } else {
                    float screenW = Global.getSettings().getScreenWidth();
                    float screenH = Global.getSettings().getScreenHeight();
                    // 获取当前视口位置和尺寸
                    IntBuffer vpBuf = BufferUtils.createIntBuffer(16);
                    GL11.glGetInteger(2978, vpBuf);
                    int vpX = vpBuf.get(0);
                    int vpY = vpBuf.get(1);
                    int vpW = vpBuf.get(2);
                    int vpH = vpBuf.get(3);

                    // 确保屏幕纹理已创建并匹配当前视口大小
                    this.ensureScreenTexture(vpW, vpH);

                    // 将当前屏幕内容复制到屏幕纹理中
                    GL11.glBindTexture(3553, this.screenTexId);
                    GL11.glCopyTexSubImage2D(3553, 0, 0, 0, vpX, vpY, vpW, vpH);

                    ViewportAPI vp = Global.getCombatEngine().getViewport();

                    // 遍历所有弹丸奇点
                    for(SingularityData s : this.active.values()) {
                        Vector2f world = s.freezeCenter ? s.frozenCenter : s.projectile.getLocation();
                        // 以下两行可能用于转换坐标，但返回值未使用
                        vp.convertWorldXtoScreenX(world.x);
                        vp.convertWorldYtoScreenY(world.y);

                        // 计算像素与世界单位的比例
                        float pixelsPerWorldUnit = (float)this.screenTexW / vp.getVisibleWidth();
                        float mHat = Math.max(0.0F, s.massGrowth) / 10000.0F;
                        float mHatRender = Math.min(mHat, 25.0F); // 限制最大25
                        float rsPx = s.smoothedRsPx; // 事件视界半径（像素）
                        // 计算爱因斯坦环半径（像素）
                        float thetaEPx = 90.0F * (float)Math.sqrt((double)Math.max(0.0F, mHatRender));
                        thetaEPx = Math.max(rsPx * 0.9F, thetaEPx); // 至少为事件视界的0.9倍
                        float rMaxPx;
                        if (!s.collapsing && !s.collapsed) {
                            // 正常状态：最大半径为事件视界的1.2倍或3.5倍爱因斯坦环半径，取较大值
                            rMaxPx = Math.max(rsPx * 1.2F, 3.5F * thetaEPx);
                        } else {
                            rMaxPx = rsPx; // 坍缩中或已坍缩时，最大半径等于事件视界
                        }

                        // 限制半径不超过屏幕尺寸的45%和49%
                        float screenMinPx = (float)Math.min(this.screenTexW, this.screenTexH);
                        rsPx = Math.min(rsPx, screenMinPx * 0.45F);
                        rMaxPx = Math.min(rMaxPx, screenMinPx * 0.49F);
                        thetaEPx = Math.max(rsPx * 0.9F, Math.min(thetaEPx, rMaxPx * 0.95F)); // 再次调整

                        // 如果透镜环太窄，记录调试信息
                        if (rMaxPx - rsPx < 6.0F) {
                            LOG.debug("[MOCI_SINGULARITY] lens band too thin; rsPx=" + rsPx + " rMaxPx=" + rMaxPx + " thetaE=" + thetaEPx);
                        }

                        // 如果着色器程序有效，则进行渲染
                        if (this.program != 0 && this.uniform != null) {
                            GL20.glUseProgram(this.program); // 激活着色器
                            GL11.glEnable(3042); // 启用混合
                            GL11.glBlendFunc(770, 771); // 设置混合函数（源alpha，1-源alpha）
                            GL11.glBindTexture(3553, this.screenTexId); // 绑定屏幕纹理

                            // 设置uniform变量
                            GL20.glUniform1i(this.uniform[0], 0); // srcTex 纹理单元0
                            // 计算中心在纹理坐标中的位置（0~1）
                            float centerUx = (world.x - vp.getLLX()) / vp.getVisibleWidth();
                            float centerUy = (world.y - vp.getLLY()) / vp.getVisibleHeight();
                            GL20.glUniform2f(this.uniform[1], centerUx, centerUy); // center
                            GL20.glUniform3f(this.uniform[2], rsPx, rMaxPx, thetaEPx); // rs_rlens_strength（三个值）
                            GL20.glUniform2f(this.uniform[3], (float)this.screenTexW, (float)this.screenTexH); // texSize
                            float fade = 1.0F;
                            if (s.evaporating) {
                                fade = Math.max(0.0F, 1.0F - s.evaporateProgress); // 蒸发时淡出
                            }
                            GL20.glUniform1f(this.uniform[4], 1.0F * fade); // darkAlpha

                            // 可选的magStrength uniform，设为0
                            int magLocation = GL20.glGetUniformLocation(this.program, "magStrength");
                            if (magLocation >= 0) {
                                GL20.glUniform1f(magLocation, 0.0F);
                            }

                            // 设置horizonColor：坍缩完成时白色，坍缩中灰色渐变，否则黑色
                            int hc = GL20.glGetUniformLocation(this.program, "horizonColor");
                            if (hc >= 0) {
                                if (s.collapsed) {
                                    GL20.glUniform3f(hc, 1.0F, 1.0F, 1.0F);
                                } else if (s.collapsing) {
                                    float t = Math.min(1.0F, Math.max(0.0F, s.collapseProgress));
                                    GL20.glUniform3f(hc, t, t, t);
                                } else {
                                    GL20.glUniform3f(hc, 0.0F, 0.0F, 0.0F);
                                }
                            }

                            // 设置投影矩阵为正交矩阵，覆盖整个屏幕纹理区域
                            GL11.glMatrixMode(5889); // GL_PROJECTION
                            GL11.glPushMatrix();
                            GL11.glLoadIdentity();
                            GL11.glOrtho((double)0.0F, (double)this.screenTexW, (double)0.0F, (double)this.screenTexH, (double)-1.0F, (double)1.0F);
                            GL11.glMatrixMode(5888); // GL_MODELVIEW
                            GL11.glPushMatrix();
                            GL11.glLoadIdentity();
                            GL11.glEnable(3553); // 启用纹理

                            // 绘制一个覆盖全屏的四边形，触发片段着色器对每个像素进行处理
                            GL11.glBegin(7); // GL_QUADS
                            GL11.glTexCoord2f(0.0F, 0.0F);
                            GL11.glVertex2f(0.0F, 0.0F);
                            GL11.glTexCoord2f(0.0F, 1.0F);
                            GL11.glVertex2f(0.0F, (float)this.screenTexH);
                            GL11.glTexCoord2f(1.0F, 1.0F);
                            GL11.glVertex2f((float)this.screenTexW, (float)this.screenTexH);
                            GL11.glTexCoord2f(1.0F, 0.0F);
                            GL11.glVertex2f((float)this.screenTexW, 0.0F);
                            GL11.glEnd();

                            // 恢复矩阵
                            GL11.glMatrixMode(5888);
                            GL11.glPopMatrix();
                            GL11.glMatrixMode(5889);
                            GL11.glPopMatrix();

                            GL20.glUseProgram(0); // 停用着色器
                        } else {
                            LOG.warn("[MOCI_SINGULARITY] shader program missing; program=" + this.program + " uniformNull=" + (this.uniform == null));
                        }
                    }

                    // 对每个手动追踪的奇点执行相同的渲染逻辑（代码与上面几乎相同）
                    for(SingularityData s : this.manual.values()) {
                        Vector2f world = s.freezeCenter ? s.frozenCenter : s.manualCenter;
                        vp.convertWorldXtoScreenX(world.x);
                        vp.convertWorldYtoScreenY(world.y);
                        float pixelsPerWorldUnit = (float)this.screenTexW / vp.getVisibleWidth();
                        float mHat = Math.max(0.0F, s.massGrowth) / 10000.0F;
                        float mHatRender = Math.min(mHat, 25.0F);
                        float rsPx = s.smoothedRsPx;
                        float thetaEPx = 90.0F * (float)Math.sqrt((double)Math.max(0.0F, mHatRender));
                        thetaEPx = Math.max(rsPx * 0.9F, thetaEPx);
                        float rMaxPx;
                        if (!s.collapsing && !s.collapsed) {
                            rMaxPx = Math.max(rsPx * 1.2F, 3.5F * thetaEPx);
                        } else {
                            rMaxPx = rsPx;
                        }
                        float screenMinPx = (float)Math.min(this.screenTexW, this.screenTexH);
                        rsPx = Math.min(rsPx, screenMinPx * 0.45F);
                        rMaxPx = Math.min(rMaxPx, screenMinPx * 0.49F);
                        thetaEPx = Math.max(rsPx * 0.9F, Math.min(thetaEPx, rMaxPx * 0.95F));
                        if (rMaxPx - rsPx < 6.0F) {
                            LOG.debug("[MOCI_SINGULARITY] lens band too thin (manual); rsPx=" + rsPx + " rMaxPx=" + rMaxPx + " thetaE=" + thetaEPx);
                        }
                        if (this.program != 0 && this.uniform != null) {
                            GL20.glUseProgram(this.program);
                            GL11.glEnable(3042);
                            GL11.glBlendFunc(770, 771);
                            GL11.glBindTexture(3553, this.screenTexId);
                            GL20.glUniform1i(this.uniform[0], 0);
                            float centerUx = (world.x - vp.getLLX()) / vp.getVisibleWidth();
                            float centerUy = (world.y - vp.getLLY()) / vp.getVisibleHeight();
                            GL20.glUniform2f(this.uniform[1], centerUx, centerUy);
                            GL20.glUniform3f(this.uniform[2], rsPx, rMaxPx, thetaEPx);
                            GL20.glUniform2f(this.uniform[3], (float)this.screenTexW, (float)this.screenTexH);
                            float fadeM = 1.0F;
                            if (s.evaporating) {
                                fadeM = Math.max(0.0F, 1.0F - s.evaporateProgress);
                            }
                            GL20.glUniform1f(this.uniform[4], 1.0F * fadeM);
                            int magLocation = GL20.glGetUniformLocation(this.program, "magStrength");
                            if (magLocation >= 0) {
                                GL20.glUniform1f(magLocation, 0.0F);
                            }
                            int hc = GL20.glGetUniformLocation(this.program, "horizonColor");
                            if (hc >= 0) {
                                if (s.collapsed) {
                                    GL20.glUniform3f(hc, 1.0F, 1.0F, 1.0F);
                                } else if (s.collapsing) {
                                    float t = Math.min(1.0F, Math.max(0.0F, s.collapseProgress));
                                    GL20.glUniform3f(hc, t, t, t);
                                } else {
                                    GL20.glUniform3f(hc, 0.0F, 0.0F, 0.0F);
                                }
                            }
                            GL11.glMatrixMode(5889);
                            GL11.glPushMatrix();
                            GL11.glLoadIdentity();
                            GL11.glOrtho((double)0.0F, (double)this.screenTexW, (double)0.0F, (double)this.screenTexH, (double)-1.0F, (double)1.0F);
                            GL11.glMatrixMode(5888);
                            GL11.glPushMatrix();
                            GL11.glLoadIdentity();
                            GL11.glEnable(3553);
                            GL11.glBegin(7);
                            GL11.glTexCoord2f(0.0F, 0.0F);
                            GL11.glVertex2f(0.0F, 0.0F);
                            GL11.glTexCoord2f(0.0F, 1.0F);
                            GL11.glVertex2f(0.0F, (float)this.screenTexH);
                            GL11.glTexCoord2f(1.0F, 1.0F);
                            GL11.glVertex2f((float)this.screenTexW, (float)this.screenTexH);
                            GL11.glTexCoord2f(1.0F, 0.0F);
                            GL11.glVertex2f((float)this.screenTexW, 0.0F);
                            GL11.glEnd();
                            GL11.glMatrixMode(5888);
                            GL11.glPopMatrix();
                            GL11.glMatrixMode(5889);
                            GL11.glPopMatrix();
                            GL20.glUseProgram(0);
                        }
                    }
                }
            }
        }
    }

    /**
     * 返回此插件活跃的渲染图层集合。
     */
    public EnumSet<CombatEngineLayers> getActiveLayers() {
        return EnumSet.of(CombatEngineLayers.JUST_BELOW_WIDGETS);
    }

    /**
     * 插件是否已过期（始终返回false，表示一直存在）。
     */
    public boolean isExpired() {
        return false;
    }

    /**
     * 返回渲染半径（极大值，确保始终渲染）。
     */
    public float getRenderRadius() {
        return 1000000.0F;
    }

    /**
     * 初始化插件（空实现）。
     */
    public void init(CombatEntityAPI entity) {
    }

    /**
     * 清理资源：删除所有纹理和着色器程序。
     */
    public void cleanup() {
        for(SingularityData s : this.active.values()) {
            if (s.texId != 0) {
                try {
                    GL11.glDeleteTextures(s.texId);
                } catch (Exception var7) {
                }
            }
        }
        for(SingularityData s : this.manual.values()) {
            if (s.texId != 0) {
                try {
                    GL11.glDeleteTextures(s.texId);
                } catch (Exception var6) {
                }
            }
        }
        this.active.clear();
        this.manual.clear();
        if (this.screenTexId != 0) {
            try {
                GL11.glDeleteTextures(this.screenTexId);
                LOG.info("[MOCI_SINGULARITY] deleted screenTex id=" + this.screenTexId);
            } catch (Exception ex) {
                LOG.error("[MOCI_SINGULARITY] failed to delete screenTex", ex);
            }
            this.screenTexId = 0;
            this.screenTexW = 0;
            this.screenTexH = 0;
        }
        if (this.program != 0) {
            try {
                GL20.glDeleteProgram(this.program);
                LOG.info("[MOCI_SINGULARITY] deleted shader program id=" + this.program);
            } catch (Exception ex) {
                LOG.error("[MOCI_SINGULARITY] failed to delete shader program", ex);
            }
            this.program = 0;
            this.uniform = null;
        }
    }

    /**
     * 初始化着色器程序，从文件加载并获取uniform位置。
     */
    private void initShader() {
        try {
            VIII_ShaderManager mgr = VIII_ShaderManager.getInstance();
            this.program = mgr.createShader("data/shaders/Moci_SingularityLens.vert", "data/shaders/Moci_SingularityLens.frag", "Moci_SingularityLens");
            if (this.program != 0) {
                GL20.glUseProgram(this.program);
                this.uniform = new int[]{
                        GL20.glGetUniformLocation(this.program, "srcTex"),
                        GL20.glGetUniformLocation(this.program, "center"),
                        GL20.glGetUniformLocation(this.program, "rs_rlens_strength"),
                        GL20.glGetUniformLocation(this.program, "texSize"),
                        GL20.glGetUniformLocation(this.program, "darkAlpha")
                };
                GL20.glUseProgram(0);
            }
        } catch (Exception var2) {
            this.program = 0;
            this.uniform = null;
        }
    }

    /**
     * 确保奇点数据有自己的纹理（未使用？但保留创建逻辑）。
     * @param s 奇点数据
     * @param w 宽度
     * @param h 高度
     */
    private void ensureTexture(SingularityData s, int w, int h) {
        if (s.texId == 0 || s.texW != w || s.texH != h) {
            if (s.texId != 0) {
                try {
                    GL11.glDeleteTextures(s.texId);
                } catch (Exception var5) {
                }
                s.texId = 0;
                s.texW = 0;
                s.texH = 0;
            }
            int id = GL11.glGenTextures();
            GL11.glBindTexture(3553, id);
            GL11.glTexParameteri(3553, 10241, 9729); // GL_TEXTURE_MIN_FILTER = GL_LINEAR
            GL11.glTexParameteri(3553, 10240, 9729); // GL_TEXTURE_MAG_FILTER = GL_LINEAR
            GL11.glTexParameteri(3553, 10242, 33071); // GL_TEXTURE_WRAP_S = GL_CLAMP_TO_EDGE
            GL11.glTexParameteri(3553, 10243, 33071); // GL_TEXTURE_WRAP_T = GL_CLAMP_TO_EDGE
            GL11.glTexImage2D(3553, 0, 32856, w, h, 0, 6408, 5121, (ByteBuffer)null); // 内部格式RGBA16? 实际可能RGBA
            s.texId = id;
            s.texW = w;
            s.texH = h;
        }
    }

    /**
     * 确保屏幕纹理存在且尺寸匹配。
     * @param w 视口宽度
     * @param h 视口高度
     */
    private void ensureScreenTexture(int w, int h) {
        if (this.screenTexId == 0 || this.screenTexW != w || this.screenTexH != h) {
            if (this.screenTexId != 0) {
                try {
                    GL11.glDeleteTextures(this.screenTexId);
                } catch (Exception var4) {
                }
                this.screenTexId = 0;
                this.screenTexW = 0;
                this.screenTexH = 0;
            }
            int id = GL11.glGenTextures();
            GL11.glBindTexture(3553, id);
            GL11.glTexParameteri(3553, 10241, 9729);
            GL11.glTexParameteri(3553, 10240, 9729);
            GL11.glTexParameteri(3553, 10242, 33071);
            GL11.glTexParameteri(3553, 10243, 33071);
            GL11.glTexImage2D(3553, 0, 32856, w, h, 0, 6408, 5121, (ByteBuffer)null);
            this.screenTexId = id;
            this.screenTexW = w;
            this.screenTexH = h;
            LOG.info("[MOCI_SINGULARITY] inits screenTex " + w + "x" + h + " id=" + id);
        }
    }

    /**
     * 设置是否启用坍缩功能。
     */
    public static void setCollapseEnabled(boolean enabled) {
        COLLAPSE_ENABLED = enabled;
    }

    /**
     * 返回坍缩是否启用。
     */
    public static boolean isCollapseEnabled() {
        return COLLAPSE_ENABLED;
    }

    /**
     * 合并两个奇点。
     * @param a 奇点A
     * @param b 奇点B
     * @param ca 奇点A的中心
     * @param cb 奇点B的中心
     */
    private void mergeSingularities(SingularityData a, SingularityData b, Vector2f ca, Vector2f cb) {
        float mA = Math.max(0.0F, a.massGrowth + a.massCache);
        float mB = Math.max(0.0F, b.massGrowth + b.massCache);
        float mT = Math.max(0.001F, mA + mB);
        // 质量加权平均中心
        float cx = (ca.x * mA + cb.x * mB) / mT;
        float cy = (ca.y * mA + cb.y * mB) / mT;
        Vector2f c = new Vector2f(cx, cy);
        // 合并质量
        a.massGrowth = Math.max(0.0F, a.massGrowth) + Math.max(0.0F, b.massGrowth);
        a.massCache = Math.max(0.0F, a.massCache) + Math.max(0.0F, b.massCache);
        // 取较大半径
        a.currentRadiusPx = Math.max(a.currentRadiusPx, b.currentRadiusPx);
        a.smoothedRsPx = Math.max(a.smoothedRsPx, b.smoothedRsPx);
        a.freezeCenter = true;
        a.frozenCenter.set(c); // A的中心冻结到合并中心
        // B标记为蒸发，即将消失
        b.evaporating = true;
        b.evaporateProgress = 0.0F;
        b.freezeCenter = true;
        b.frozenCenter.set(cb);
        LOG.info("[MOCI_SINGULARITY] merge singularities totalA=" + mA + " totalB=" + mB + " -> " + mT + " (growth=" + a.massGrowth + ", cache=" + a.massCache + ")");
    }

    /**
     * 触发超新星爆炸效果。
     * @param a 奇点A
     * @param b 奇点B
     * @param c 爆炸中心
     */
    private void triggerSuperExplosion(SingularityData a, SingularityData b, Vector2f c) {
        try {
            CombatEngineAPI eng = Global.getCombatEngine();
            // 生成爆炸和粒子效果
            eng.spawnExplosion(c, new Vector2f(0.0F, 0.0F), new Color(255, 240, 200, 255), 600.0F, 2.5F);
            eng.addSmoothParticle(c, new Vector2f(0.0F, 0.0F), 1200.0F, 1.0F, 2.0F, Color.white);
        } catch (Throwable var5) {
        }
        // 两个奇点都进入蒸发状态
        a.evaporating = true;
        a.evaporateProgress = 0.0F;
        a.freezeCenter = true;
        a.frozenCenter.set(c);
        b.evaporating = true;
        b.evaporateProgress = 0.0F;
        b.freezeCenter = true;
        b.frozenCenter.set(c);
        LOG.info("[MOCI_SINGULARITY] SUPER NOVA triggered");
    }

    /**
     * 为指定的弹丸奇点增加质量（massCache增加）。
     * @param proj 弹丸
     * @param delta 质量增量
     */
    public void addMass(DamagingProjectileAPI proj, float delta) {
        if (proj != null) {
            SingularityData s = (SingularityData)this.active.get(proj);
            if (s != null) {
                s.massCache += Math.max(0.0F, delta);
                s.massMult = Math.max(1.0F, Math.min(3.5F, s.massMult + delta * 5.0E-4F));
            }
        }
    }

    /**
     * 为手动追踪的奇点增加质量。
     * @param token 令牌
     * @param delta 质量增量
     */
    public void addMass(Object token, float delta) {
        SingularityData s = (SingularityData)this.manual.get(token);
        if (s != null) {
            s.massCache += Math.max(0.0F, delta);
            s.massMult = Math.max(1.0F, Math.min(3.5F, s.massMult + delta * 5.0E-4F));
        }
    }

    /**
     * 获取指定弹丸的事件视界半径（世界单位）。
     * @param proj 弹丸
     * @return 半径（世界单位）
     */
    public float getEventHorizonRadiusWorld(DamagingProjectileAPI proj) {
        SingularityData s = (SingularityData)this.active.get(proj);
        if (s == null) {
            return 180.0F;
        } else {
            ViewportAPI vp = Global.getCombatEngine().getViewport();
            float pixelsPerWorldUnit = (float)this.screenTexW / Math.max(1.0F, vp.getVisibleWidth());
            float rsPx = Math.max(2.0F, s.smoothedRsPx);
            return rsPx / Math.max(0.001F, pixelsPerWorldUnit);
        }
    }

    /**
     * 获取指定弹丸的中心位置（世界坐标）。
     * @param proj 弹丸
     * @return 中心向量，可能为null
     */
    public Vector2f getCenterWorld(DamagingProjectileAPI proj) {
        SingularityData s = (SingularityData)this.active.get(proj);
        if (s == null) {
            return null;
        } else if (s.freezeCenter) {
            return new Vector2f(s.frozenCenter);
        } else {
            return s.projectile != null ? new Vector2f(s.projectile.getLocation()) : null;
        }
    }

    /**
     * 检查弹丸是否被追踪。
     */
    public boolean isTracked(DamagingProjectileAPI proj) {
        return this.active.containsKey(proj);
    }

    /**
     * 获取手动追踪奇点的事件视界半径（世界单位）。
     */
    public float getEventHorizonRadiusWorld(Object token) {
        SingularityData s = (SingularityData)this.manual.get(token);
        if (s == null) {
            return 180.0F;
        } else {
            ViewportAPI vp = Global.getCombatEngine().getViewport();
            float pixelsPerWorldUnit = (float)this.screenTexW / Math.max(1.0F, vp.getVisibleWidth());
            float rsPx = Math.max(2.0F, s.smoothedRsPx);
            return rsPx / Math.max(0.001F, pixelsPerWorldUnit);
        }
    }

    /**
     * 为弹丸奇点配置像素半径基础值和增长率。
     * @param proj 弹丸
     * @param basePx 基础半径（像素）
     * @param growthPxPerSec 每秒增长像素
     */
    public void configurePixelRadius(DamagingProjectileAPI proj, float basePx, float growthPxPerSec) {
        SingularityData s = (SingularityData)this.active.get(proj);
        if (s != null) {
            s.baseRadiusPx = Math.max(1.0F, basePx);
            s.growthPxPerSec = Math.max(0.0F, growthPxPerSec);
            s.currentRadiusPx = s.baseRadiusPx;
        }
    }

    /**
     * 为手动追踪奇点配置像素半径基础值和增长率。
     */
    public void configurePixelRadius(Object token, float basePx, float growthPxPerSec) {
        SingularityData s = (SingularityData)this.manual.get(token);
        if (s != null) {
            s.baseRadiusPx = Math.max(1.0F, basePx);
            s.growthPxPerSec = Math.max(0.0F, growthPxPerSec);
            s.currentRadiusPx = s.baseRadiusPx;
        }
    }

    /**
     * 获取弹丸奇点的质量值（massGrowth）。
     */
    public float getMassValue(DamagingProjectileAPI proj) {
        SingularityData s = (SingularityData)this.active.get(proj);
        return s == null ? 0.0F : Math.max(0.0F, s.massGrowth);
    }

    /**
     * 获取手动追踪奇点的质量值。
     */
    public float getMassValue(Object token) {
        SingularityData s = (SingularityData)this.manual.get(token);
        return s == null ? 0.0F : Math.max(0.0F, s.massGrowth);
    }

    /**
     * 检查弹丸奇点是否已坍缩完成。
     */
    public boolean isCollapsed(DamagingProjectileAPI proj) {
        SingularityData s = (SingularityData)this.active.get(proj);
        return s != null && s.collapsed;
    }

    /**
     * 检查弹丸奇点是否正在坍缩。
     */
    public boolean isCollapsing(DamagingProjectileAPI proj) {
        SingularityData s = (SingularityData)this.active.get(proj);
        return s != null && s.collapsing;
    }

    /**
     * 检查弹丸奇点是否正在蒸发。
     */
    public boolean isEvaporating(DamagingProjectileAPI proj) {
        SingularityData s = (SingularityData)this.active.get(proj);
        return s != null && s.evaporating;
    }

    /**
     * 获取弹丸奇点的坍缩进度（0~1）。
     */
    public float getCollapseProgress(DamagingProjectileAPI proj) {
        SingularityData s = (SingularityData)this.active.get(proj);
        return s == null ? 0.0F : s.collapseProgress;
    }

    /**
     * 检查手动追踪奇点是否已坍缩完成。
     */
    public boolean isCollapsed(Object token) {
        SingularityData s = (SingularityData)this.manual.get(token);
        return s != null && s.collapsed;
    }

    /**
     * 检查手动追踪奇点是否正在坍缩。
     */
    public boolean isCollapsing(Object token) {
        SingularityData s = (SingularityData)this.manual.get(token);
        return s != null && s.collapsing;
    }

    /**
     * 检查手动追踪奇点是否正在蒸发。
     */
    public boolean isEvaporating(Object token) {
        SingularityData s = (SingularityData)this.manual.get(token);
        return s != null && s.evaporating;
    }

    /**
     * 获取手动追踪奇点的坍缩进度。
     */
    public float getCollapseProgress(Object token) {
        SingularityData s = (SingularityData)this.manual.get(token);
        return s == null ? 0.0F : s.collapseProgress;
    }

    /**
     * 停止追踪指定令牌的奇点，并删除其纹理。
     */
    public void stopTracking(Object token) {
        SingularityData s = (SingularityData)this.manual.remove(token);
        if (s != null && s.texId != 0) {
            try {
                GL11.glDeleteTextures(s.texId);
            } catch (Exception var4) {
            }
        }
    }

    /**
     * 记录受奇点影响的飞船（用于后续速度阻尼）。
     * @param proj 弹丸
     * @param ship 飞船
     */
    public void recordAffectedShip(DamagingProjectileAPI proj, ShipAPI ship) {
        if (proj != null && ship != null) {
            SingularityData s = (SingularityData)this.active.get(proj);
            if (s != null) {
                s.affectedShips.add(ship);
            }
        }
    }

    /**
     * 对受影响的飞船应用速度阻尼（如果速度超过最大速度一定比例，添加监听器逐渐减速）。
     * @param s 奇点数据
     */
    private void applyVelocityDampingToAffectedShips(SingularityData s) {
        if (s != null && !s.affectedShips.isEmpty()) {
            int dampedCount = 0;
            for(ShipAPI ship : s.affectedShips) {
                if (ship != null && ship.isAlive() && !ship.isHulk()) {
                    Vector2f vel = ship.getVelocity();
                    float currentSpeed = vel.length();
                    float maxSpeed = ship.getMaxSpeed();
                    if (currentSpeed > maxSpeed * 1.1F) { // 如果速度超过最大速度的1.1倍
                        boolean hasListener = false;
                        for(Object listener : ship.getListeners(VelocityDampingListener.class)) {
                            if (listener instanceof VelocityDampingListener) {
                                ((VelocityDampingListener)listener).refresh(); // 刷新已有监听器
                                hasListener = true;
                                break;
                            }
                        }
                        if (!hasListener) {
                            ship.addListener(new VelocityDampingListener(ship)); // 添加新监听器
                            ++dampedCount;
                        }
                    }
                }
            }
            if (dampedCount > 0) {
                LOG.info("[MOCI_SINGULARITY] applied progressive velocity damping to " + dampedCount + " ships");
            }
            s.affectedShips.clear(); // 清空集合
        }
    }

    /**
     * 内部类：存储单个奇点的所有状态数据。
     */
    private static final class SingularityData {
        DamagingProjectileAPI projectile; // 关联的弹丸（可能为null）
        Object token;                      // 手动追踪的令牌
        float eventHorizonRadius;           // 事件视界半径（世界单位，初始传入但未使用）
        float lensStrength;                 // 透镜强度（初始传入但未使用）
        int texId;                           // 纹理ID（未使用？）
        int texW;                            // 纹理宽度
        int texH;                            // 纹理高度
        float massMult;                      // 质量倍数（用于计算增长半径）
        float massCache;                      // 缓存质量（尚未被吸收的质量）
        float massGrowth;                     // 已吸收的质量（用于物理半径计算）
        float baseRadiusPx;                   // 基础半径（像素）
        float growthPxPerSec;                  // 每秒增长像素
        float currentRadiusPx;                 // 当前半径（像素，随时间增长）
        float smoothedRsPx;                    // 平滑后的事件视界半径（像素，用于渲染）
        boolean collapsed;                      // 是否已坍缩完成
        boolean collapsing;                     // 是否正在坍缩
        float collapseProgress;                  // 坍缩进度（0~1）
        float collapseStartRsPx;                 // 坍缩开始时的半径
        Vector2f manualCenter = new Vector2f();  // 手动追踪的中心
        float lastLogAt = -999.0F;                // 上次日志记录时间
        int collapseStepLogged = -1;              // 已记录的坍缩步数
        boolean freezeCenter;                     // 是否冻结中心（用于弹丸结束后固定位置）
        final Vector2f frozenCenter = new Vector2f(); // 冻结的中心
        float afterCollapseHold = 0.0F;            // 坍缩完成后保持时间（秒）
        boolean lastCenterInit;                    // 上一次中心是否已初始化（用于移动距离计算）
        final Vector2f lastCenterWorld = new Vector2f(); // 上一帧的世界坐标中心
        boolean evaporating;                        // 是否正在蒸发
        float evaporateProgress;                    // 蒸发进度（0~1）
        final Set<ShipAPI> affectedShips = new HashSet(); // 受影响的飞船集合（用于速度阻尼）

        /**
         * 构造方法。
         * @param p 弹丸
         * @param r 事件视界半径（未使用）
         * @param lens 透镜强度（未使用）
         * @param diskColor 吸积盘颜色（未使用）
         */
        SingularityData(DamagingProjectileAPI p, float r, float lens, Color diskColor) {
            this.projectile = p;
            this.token = null;
            this.eventHorizonRadius = r;
            this.lensStrength = lens;
            this.texId = 0;
            this.texW = 0;
            this.texH = 0;
            this.massMult = 1.0F;
            this.massCache = 0.0F;
            this.massGrowth = 0.0F;
            this.baseRadiusPx = 10.0F;
            this.growthPxPerSec = 2.0F;
            this.currentRadiusPx = this.baseRadiusPx;
            this.smoothedRsPx = 1.0F;
            this.collapsed = false;
            this.collapsing = false;
            this.collapseProgress = 0.0F;
            this.collapseStartRsPx = this.currentRadiusPx;
            this.freezeCenter = false;
            this.afterCollapseHold = 0.0F;
            this.lastCenterInit = false;
            this.evaporating = false;
            this.evaporateProgress = 0.0F;
        }
    }

    /**
     * 速度阻尼监听器：使飞船速度逐渐降低到最大速度附近。
     */
    private static class VelocityDampingListener implements AdvanceableListener {
        private final ShipAPI ship;
        private float duration;                      // 剩余持续时间
        private static final float TOTAL_DURATION = 3.0F; // 总持续时间
        private static final float DAMPING_RATE = 0.85F;   // 阻尼系数（指数衰减）

        public VelocityDampingListener(ShipAPI ship) {
            this.ship = ship;
            this.duration = 3.0F;
        }

        /**
         * 刷新持续时间（当再次触发时重置计时）。
         */
        public void refresh() {
            this.duration = 3.0F;
        }

        /**
         * 每帧更新：减少速度超出的部分。
         * @param amount 时间增量
         */
        public void advance(float amount) {
            if (this.ship != null && this.ship.isAlive() && !this.ship.isHulk()) {
                this.duration -= amount;
                Vector2f vel = this.ship.getVelocity();
                float currentSpeed = vel.length();
                float maxSpeed = this.ship.getMaxSpeed();
                float excessSpeed = currentSpeed - maxSpeed;
                if (excessSpeed > 1.0F) {
                    // 超出部分按指数衰减
                    float dampingFactor = (float)Math.pow((double)0.85F, (double)amount);
                    float newExcessSpeed = excessSpeed * dampingFactor;
                    float targetSpeed = maxSpeed + newExcessSpeed;
                    if (currentSpeed > 0.001F) {
                        float scale = targetSpeed / currentSpeed;
                        vel.scale(scale); // 缩放速度向量
                    }
                    if (this.duration <= 0.0F) {
                        this.ship.removeListener(this); // 持续时间结束则移除自身
                    }
                } else {
                    this.ship.removeListener(this); // 速度已正常则移除
                }
            } else {
                this.ship.removeListener(this);
            }
        }
    }
}