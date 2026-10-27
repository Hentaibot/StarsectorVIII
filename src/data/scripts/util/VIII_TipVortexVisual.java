// 包路径：数据/脚本/工具包
package data.scripts.util;

// 导入远行星号游戏API核心类
import com.fs.starfarer.api.Global;                              // 全局游戏实例
import com.fs.starfarer.api.combat.*;                            // 战斗相关接口
import com.fs.starfarer.api.graphics.SpriteAPI;                  // 精灵图API
// 导入BoxUtil框架中的着色器和工具类
import org.boxutil.base.BaseShaderData;                          // 着色器数据基类
import org.boxutil.manager.ShaderCore;                           // 着色器核心管理器
import org.boxutil.units.builtin.shader.BUtil_ShaderProgram;     // BoxUtil着色器程序
import org.boxutil.util.CommonUtil;                              // 通用工具类
import org.boxutil.util.ShaderUtil;                              // 着色器工具类
import org.boxutil.util.TransformUtil;                           // 变换工具类
// 导入LazyLib数学工具
import org.lazywizard.lazylib.MathUtils;                         // 数学工具
// 导入LWJGL OpenGL相关类和数学类
import org.lwjgl.opengl.*;                                       // OpenGL常量和方法
import org.lwjgl.util.vector.Matrix4f;                           // 4x4矩阵
import org.lwjgl.util.vector.Vector2f;                           // 二维向量

import java.awt.Color;                                           // AWT颜色类
import java.util.EnumSet;                                        // 枚举集合

// 静态导入自定义工具方法（创建通用矩形VAO）
import static data.scripts.util.VIII_BoxBasedUtil.createUniversalRectVAO;

/**
 * 着色器渲染类，来自于会长 @AnyIDElse
 * 让我们感谢猫猫会长的魔法，会长赛高！
 * 该插件用于在舰船尾部绘制尾迹涡流特效，使用着色器渲染多个纹理层（中心、底层、尖端），
 * 支持动态调整颜色、尺寸、偏移和透明度，根据舰船状态淡入淡出。
 */
public class VIII_TipVortexVisual extends BaseCombatLayeredRenderingPlugin {

    // 零向量常量（用于音效等）
    public static final Vector2f ZERO = new Vector2f();

    // 附着实体（舰船）的引用
    private final ShipAPI anchor;
    // 中心层纹理
    private final SpriteAPI centerTexture;
    // 底层纹理
    private final SpriteAPI underTexture;
    // 尖端纹理
    private final SpriteAPI tipTexture;

    // 各层颜色
    private Color centerColor;   // 中心层颜色
    private Color underColor;    // 底层颜色
    private Color tipColor;      // 尖端颜色
    private Color topColor;      // 顶层颜色（实际用于高光）

    // 基础宽度和长度（基于舰船碰撞半径计算）
    private final float basicWidth;
    private final float basicLength;
    // 宽度倍率、长度倍率、偏移量、整体缩放倍率
    private float widthMult = 1f;
    private float lengthMult = 1f;
    private float offset = 0f;
    private float sizeMult = 1f;        // 整体缩放（用于根据速度等动态调整）

    // 有效性标志（当附着实体消失或死亡后变为false）
    private boolean valid = true;
    // 存活等级（舰船死亡后逐渐降为0，用于淡出）
    private float aliveLevel = 1f;
    // 效果强度（0~1，由外部设置，用于淡入淡出）
    private float effectLevel = 0f;

    /**
     * 构造函数
     * @param anchor         附着舰船
     * @param centerTexture  中心纹理
     * @param underTexture   底层纹理
     * @param tipTexture     尖端纹理
     */
    public VIII_TipVortexVisual(ShipAPI anchor, SpriteAPI centerTexture, SpriteAPI underTexture, SpriteAPI tipTexture) {
        this.anchor = anchor;
        this.centerTexture = centerTexture;
        this.underTexture = underTexture;
        this.tipTexture = tipTexture;
        // 基础宽度 = 舰船碰撞半径 + 10像素
        this.basicWidth = anchor.getCollisionRadius() + 10f;
        // 基础长度 = 舰船碰撞半径 * 0.5 + 2像素
        this.basicLength = anchor.getCollisionRadius() * 0.5f + 2f;
        // 获取第一个引擎（用于默认颜色，但后续会覆盖）
        ShipEngineControllerAPI.ShipEngineAPI firstEngine = anchor.getEngineController().getShipEngines().get(0);

        // 初始化颜色：如果舰船有引擎控制器且非空，则使用引擎颜色和尾迹颜色，否则默认白色
        if (anchor.getEngineController() != null && !anchor.getEngineController().getShipEngines().isEmpty()) {
            this.tipColor = anchor.getVentCoreColor();      // 尖端颜色使用排气核心色（可能未使用）
            this.topColor = anchor.getVentFringeColor();    // 顶层颜色使用排气边缘色
        } else {
            this.centerColor = Color.WHITE;
            this.underColor = Color.WHITE;
        }
        // 强制覆盖中心色和底层色为第一个引擎的颜色和尾迹颜色（这样所有层颜色都基于引擎）
        this.centerColor = firstEngine.getEngineColor();
        this.underColor = firstEngine.getContrailColor();
    }

    // ==================== Getter / Setter ====================
    public Color getCenterColor() { return centerColor; }
    public void setCenterColor(Color centerColor) { this.centerColor = centerColor; }

    public Color getUnderColor() { return underColor; }
    public void setUnderColor(Color underColor) { this.underColor = underColor; }

    public Color getTipColor() { return tipColor; }
    public void setTipColor(Color tipColor) { this.tipColor = tipColor; }

    public Color getTopColor() { return topColor; }
    public void setTopColor(Color topColor) { this.topColor = topColor; }

    public float getWidthMult() { return widthMult; }
    public void setWidthMult(float widthMult) { this.widthMult = widthMult; }

    public float getLengthMult() { return lengthMult; }
    public void setLengthMult(float lengthMult) { this.lengthMult = lengthMult; }

    public float getOffset() { return offset; }
    public void setOffset(float offset) { this.offset = offset; }

    public float getBasicWidth() { return basicWidth; }
    public float getBasicLength() { return basicLength; }

    // 渲染半径（用于视口剔除），取基础宽度 * 整体缩放 * 5倍，确保特效始终可见
    @Override
    public float getRenderRadius() {
        return basicWidth * sizeMult * 5f;
    }

    // 指定该特效渲染的图层：舰船下方层和粒子层下方（使其位于舰船之下，又高于一些粒子）
    @Override
    public EnumSet<CombatEngineLayers> getActiveLayers() {
        return EnumSet.of(CombatEngineLayers.BELOW_SHIPS_LAYER, CombatEngineLayers.ABOVE_PARTICLES_LOWER);
    }

    public float getEffectLevel() { return effectLevel; }
    public void setEffectLevel(float effectLevel) { this.effectLevel = effectLevel; }

    public float getSizeMult() { return sizeMult; }
    public void setSizeMult(float sizeMult) { this.sizeMult = sizeMult; }

    public void setValid(boolean valid) { this.valid = valid; }
    public boolean isValid() { return valid; }

    /**
     * 每帧更新逻辑：更新插件实体的位置，处理舰船死亡时的淡出，以及有效性判断
     * @param amount 时间增量（秒）
     */
    @Override
    public void advance(float amount) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine.isPaused()) return;                      // 战斗暂停时不更新
        if (!engine.isEntityInPlay(anchor)) {               // 如果附着实体已不在战斗中（被移除），标记无效
            valid = false;
            return;
        }

        // 将渲染插件实体的位置同步到舰船位置
        entity.getLocation().set(anchor.getLocation());

        // 如果舰船已死亡，则逐渐降低存活等级（淡出）
        if (!anchor.isAlive()) {
            aliveLevel -= amount * 3f;                      // 每秒下降300%，即约0.33秒完全淡出
            if (aliveLevel <= 0f) {
                aliveLevel = 0f;
                valid = false;                              // 淡出完成后标记无效，等待被清理
            }
            return;
        }
    }

    /**
     * 初始化插件，创建着色器程序和VAO/VBO
     * @param entity 该特效对应的战斗实体（由引擎自动创建）
     */
    @Override
    public void init(CombatEntityAPI entity) {
        super.init(entity);
        advance(0f);  // 立即执行一次更新，确保初始位置同步

        // 创建着色器程序：从指定路径加载顶点和片段着色器
        int programId = ShaderUtil.createShaderVFFormPath(this.getClass().getName(),
                "data/shaders/Moci_tip_vortex.vert",
                "data/shaders/Moci_tip_vortex.frag");
        // 包装为BoxUtil的着色器程序
        program = new BUtil_ShaderProgram(programId);
        // 记录着色器中的uniform位置（用于后续设置）
        program.location = new int[]{
                program.getUniformIndex("modelMatrix"),    // 模型矩阵
                program.getUniformIndex("size"),           // 尺寸（长度、宽度）
                program.getUniformIndex("time"),           // 时间（用于动画）
                program.getUniformIndex("uv"),             // UV坐标（此处未使用）
                program.getUniformIndex("tex"),            // 纹理单元
                program.getUniformIndex("colorStart"),     // 起始颜色
                program.getUniformIndex("colorEnd")        // 结束颜色
        };
        // 获取UBO（Uniform Buffer Object）绑定位置，用于共享全局矩阵数据（如投影矩阵）
        program.uboLocation = new int[]{
                program.getUBOIndex("BUtilGlobalData", ShaderCore.getMatrixUBOBinding())
        };

        // 创建一个通用的矩形VAO（顶点数组对象），返回VAO ID和VBO ID
        int[] idV = createUniversalRectVAO();
        vaoId = idV[0];
        vboId = idV[1];
    }

    /**
     * 清理资源：删除VAO、VBO和着色器程序
     */
    @Override
    public void cleanup() {
        if (vaoId > 0) {
            GL30.glDeleteVertexArrays(vaoId);   // 删除VAO
            vaoId = 0;
        }
        if (vboId > 0) {
            GL15.glDeleteBuffers(vaoId);        // 注意：此处应为vboId，但代码误写为vaoId，可能是个bug
            vboId = 0;
        }
        if (program != null) {
            program.delete();                    // 删除着色器程序
            program = null;
        }
    }

    /**
     * 判断插件是否已过期（应被移除）
     * @return true表示应移除
     */
    @Override
    public boolean isExpired() {
        return !valid;
    }

    /**
     * 渲染特效（根据图层调用不同的纹理层）
     * @param layer    当前渲染的图层
     * @param viewport 视口
     */
    @Override
    public void render(CombatEngineLayers layer, ViewportAPI viewport) {
        // 计算最终透明度：视口透明度 * 舰船透明度 * 存活等级 * 效果强度，再平方（使淡入淡出更平滑）
        float alphaMult = viewport.getAlphaMult() * anchor.getAlphaMult() * aliveLevel * effectLevel;
        if (alphaMult <= 0f) return;                // 透明度为0时不渲染

        alphaMult *= alphaMult;                     // 平方，使淡入淡出曲线更陡

        // 计算当前渲染尺寸：基础尺寸 * 倍率 * 整体缩放
        float width = basicWidth * widthMult * sizeMult;
        float length = basicLength * lengthMult * sizeMult;

        CombatEngineAPI engine = Global.getCombatEngine();
        // 根据图层决定渲染哪一层纹理
        if (layer == CombatEngineLayers.BELOW_SHIPS_LAYER) {
            // 底层：使用 underTexture，颜色为 topColor 到 underColor 渐变，尺寸较大，透明度较低
            renderVortex(engine, anchor, underTexture, topColor, underColor,
                    2f * length, 4f * width, offset + length * 0.8f, alphaMult * 0.75f);
        } else if (layer == CombatEngineLayers.ABOVE_PARTICLES_LOWER) {
            // 粒子层之上：先渲染中心层（centerTexture），再渲染尖端层（tipTexture）
            renderVortex(engine, anchor, centerTexture, topColor, centerColor,
                    3f * length, 2.25f * width, offset + length * 0.25f, alphaMult);
            renderVortex(engine, anchor, tipTexture, topColor, tipColor,
                    5f * length, 2f * width, offset - length * 0.8f, alphaMult);
        }
    }

    // 着色器相关资源
    private int vaoId = -99;            // 顶点数组对象ID
    private int vboId = -99;            // 顶点缓冲对象ID
    private BaseShaderData program = null; // 着色器程序
    private Matrix4f matrix = new Matrix4f(); // 模型矩阵

    /**
     * 渲染一个涡流层（实际调用OpenGL绘制）
     * @param engine      战斗引擎
     * @param anchor      附着舰船
     * @param texture     要渲染的纹理
     * @param startColor  起始颜色（靠近舰船处）
     * @param endColor    结束颜色（远离舰船处）
     * @param length      长度（像素）
     * @param width       宽度（像素）
     * @param offset      偏移量（从舰船中心沿朝向的距离，正为前方，负为后方）
     * @param alphaMult   最终透明度倍率
     */
    public void renderVortex(CombatEngineAPI engine, ShipAPI anchor, SpriteAPI texture,
                             Color startColor, Color endColor, float length, float width,
                             float offset, float alphaMult) {
        // 确保着色器程序和VAO/VBO已初始化
        if (program != null && vaoId > 0 && vboId > 0) {
            // 计算涡流的位置：从舰船中心沿舰船朝向移动 offset 像素
            float angle = anchor.getFacing();
            Vector2f location = MathUtils.getPoint(anchor.getLocation(), offset, angle);

            // 启用混合模式（加法混合：源Alpha与目标颜色相加，产生发光效果）
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);

            // 绑定VAO
            GL30.glBindVertexArray(vaoId);
            // 激活着色器程序
            program.active();

            // 创建模型矩阵并传递给着色器
            TransformUtil.createModelMatrixVanilla(location, MathUtils.clampAngle(angle), matrix);
            GL20.glUniformMatrix4(program.location[0], false, CommonUtil.createFloatBuffer(matrix));

            // 传递长度和宽度（vec2）
            GL20.glUniform2f(program.location[1], length, width);

            // 传递总经过时间（用于纹理滚动等动画）
            GL20.glUniform1f(program.location[2], engine.getTotalElapsedTime(false));

            // 注意：shader中的uv uniform实际上没有被使用，这里传递1.0作为占位符
            GL20.glUniform2f(program.location[3], 1.0f, 1.0f);

            // 激活纹理单元0并绑定纹理
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture.getTextureId());
            GL20.glUniform1i(program.location[4], 0);

            // 传递起始颜色和结束颜色（RGBA分量归一化到0~1，乘以透明度倍率）
            GL20.glUniform4f(program.location[5],
                    startColor.getRed() / 255f, startColor.getGreen() / 255f,
                    startColor.getBlue() / 255f, alphaMult * startColor.getAlpha() / 255f);
            GL20.glUniform4f(program.location[6],
                    endColor.getRed() / 255f, endColor.getGreen() / 255f,
                    endColor.getBlue() / 255f, alphaMult * endColor.getAlpha() / 255f);

            // 绘制三角形带（4个顶点形成一个矩形）
            GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);

            // 关闭着色器程序并解绑VAO
            program.close();
            GL30.glBindVertexArray(0);
        }
    }
}