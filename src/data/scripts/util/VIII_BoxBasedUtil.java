package data.scripts.util;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import org.boxutil.base.BaseControlData;
import org.boxutil.base.SimpleParticleControlData;
import org.boxutil.base.api.RenderDataAPI;
import org.boxutil.base.api.InstanceDataAPI;
import org.boxutil.define.BoxEnum;
import org.boxutil.manager.CombatRenderingManager;
import org.boxutil.units.standard.entity.FlareEntity;
import org.boxutil.units.standard.entity.SpriteEntity;
import org.boxutil.util.CommonUtil;
import org.lazywizard.lazylib.MathUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * BoxUtil 工具类
 */
public class VIII_BoxBasedUtil {

    public static final String KEY_PREFIX = VIII_BoxBasedUtil.class.getSimpleName();
    public static final Random RANDOM = new Random();
    public static final Color EMPTY = new Color(0, 0, 0, 0);
    public static final Vector2f ZERO = new Vector2f();

    public static final int DEFAULT_MAX_PARTICLES = 4000;
    public static final int DEFAULT_MAX_DURATION = 100;

    /**
     * 添加单个粒子
     */
    public static SpriteEntity addSingleParticle(String key, Vector2f loc, Vector2f vel, float size, float brightness, 
                                                  float in, float full, float out, Color color, String sprite, 
                                                  float facing, float turnRate, float growth) {

        if (key == null) {
            key = KEY_PREFIX + sprite;
        } else {
            key = KEY_PREFIX + key + sprite;
        }

        CombatEngineAPI engine = Global.getCombatEngine();
        if (!engine.getCustomData().containsKey(key)) {

            SpriteEntity spriteEntity = new SpriteEntity(sprite);
            spriteEntity.getMaterialData().setColor(Color.WHITE);
            spriteEntity.getMaterialData().setAlphaToEmissive(0f);
            spriteEntity.setLayer(CombatEngineLayers.ABOVE_PARTICLES_LOWER);
            spriteEntity.setAdditiveBlend();
            SimpleParticleControlData data = new SimpleParticleControlData(DEFAULT_MAX_PARTICLES, DEFAULT_MAX_DURATION, -9999f, false);
            spriteEntity.setControlData(data);
            CombatRenderingManager.addEntity(spriteEntity);

            engine.getCustomData().put(key, spriteEntity);
        }

        SpriteEntity spriteEntity = (SpriteEntity) engine.getCustomData().get(key);
        SimpleParticleControlData data = (SimpleParticleControlData) spriteEntity.getControlData();

        color = scaleAlpha(color, brightness);
        size *= 0.5f;
        data.addParticle(loc, facing, turnRate, vel, new Vector2f(size, size), new Vector2f(growth, growth), color, EMPTY, in, full, out);

        return spriteEntity;
    }

    /**
     * 添加星云平滑粒子
     */
    public static SpriteEntity addNebulaSmoothParticle(Vector2f loc, Vector2f vel, float size, float endSizeMult, 
                                                        float rampUpFraction, float fullBrightnessFraction, 
                                                        float totalDuration, Color color) {

        float in = Math.max(totalDuration * rampUpFraction, 0f);
        float out = Math.max(totalDuration * (1f - rampUpFraction), 0f);

        SpriteEntity spriteEntity = addSingleParticle(null, loc, vel, size, fullBrightnessFraction, in, 0f, out, color, 
            "graphics/fx/cleaner_clouds00.png", RANDOM.nextFloat() * 360f, 0f, 
            endSizeMultToInstanceGrowth(size, endSizeMult, totalDuration));
        spriteEntity.setTileSize(2, 2);
        spriteEntity.setRandomTile(true);
        spriteEntity.setRandomTileEachInstance(true);

        return spriteEntity;
    }

    /**
     * 计算实例增长率
     */
    public static float endSizeMultToInstanceGrowth(float scale, float endSizeMult, float totalDuration) {
        return scale * (endSizeMult - 1f) / totalDuration;
    }

    /**
     * 缩放颜色透明度
     */
    private static Color scaleAlpha(Color color, float mult) {
        if (mult >= 1f) return color;
        if (mult <= 0f) return new Color(color.getRed(), color.getGreen(), color.getBlue(), 0);
        int alpha = (int) (color.getAlpha() * mult);
        if (alpha > 255) alpha = 255;
        if (alpha < 0) alpha = 0;
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    /**
     * 创建通用矩形VAO
     */
    public static final byte[] BASIC_VERTICES = new byte[]{-128, -128, 127, -128, -128, 127, 127, 127};

    public static int[] createUniversalRectVAO() {

        int vaoId = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vaoId);

        // vbo create and done
        int vboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vboId);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, CommonUtil.createByteBuffer(BASIC_VERTICES), GL15.GL_STATIC_DRAW);

        // vao config
        // 2 number for a point
        byte size = 2;
        // layout (location = 0) is 2 number of byte, which is a point
        GL20.glVertexAttribPointer(0, size, GL11.GL_BYTE, true, 2, 0);
        GL20.glEnableVertexAttribArray(0);

        // after build
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);

        return new int[]{vaoId, vboId};
    }

    /**
     * 在地图上添加一个锐利的光晕（Flare）特效。
     *
     * @param location   光晕中心位置
     * @param angle      光晕的旋转角度（度）
     * @param width      光晕宽度
     * @param height     光晕高度
     * @param coreColor  核心颜色
     * @param fringeColor 边缘颜色
     * @param in         淡入时间
     * @param full       全亮持续时间
     * @param out        淡出时间
     * @param glowPower  辉光强度
     * @param noisePower 噪点强度
     */
    public static void addSharpFlare(Vector2f location, float angle, float width, float height,
                                     Color coreColor, Color fringeColor, float in, float full, float out,
                                     float glowPower, float noisePower) {
        // 创建光晕实体
        FlareEntity flareEntity = new FlareEntity();
        flareEntity.setLocation(location);               // 位置
        flareEntity.setSize(width, height);              // 尺寸

        // 设置朝向缩放，第一个参数为角度，后两个为 x/y 缩放（这里均为 1）
        flareEntity.setFacingScale(MathUtils.clampAngle(angle), 1f, 1f);

        flareEntity.setCoreColor(coreColor);             // 核心颜色
        flareEntity.setFringeColor(fringeColor);         // 边缘颜色
        flareEntity.setAdditiveBlend();                  // 使用加法混合

        flareEntity.setSmoothDisc();                     // 平滑圆盘
        flareEntity.autoAspect();                        // 自动宽高比
        flareEntity.setNoisePower(noisePower);           // 噪点强度
        flareEntity.setGlowPower(glowPower);             // 辉光强度
        flareEntity.setGlobalTimer(in, full, out);       // 设置淡入、全亮、淡出时间

        // 设置渲染图层（粒子层上方稍低处）
        flareEntity.setLayer(CombatEngineLayers.ABOVE_PARTICLES_LOWER);

        // 将光晕实体添加到战斗渲染管理器中
        CombatRenderingManager.addEntity(flareEntity);
    }

    public static void addSharpFlare_noFlick(Vector2f location, float angle, float width, float height,
                                     Color coreColor, Color fringeColor, float in, float full, float out,
                                     float glowPower, float noisePower) {
        // 创建光晕实体
        FlareEntity flareEntity = new FlareEntity();
        flareEntity.setLocation(location);               // 位置
        flareEntity.setSize(width, height);              // 尺寸
        flareEntity.setFlick(false);                     // 不启用闪烁

        // 设置朝向缩放，第一个参数为角度，后两个为 x/y 缩放（这里均为 1）
        flareEntity.setFacingScale(MathUtils.clampAngle(angle), 1f, 1f);

        flareEntity.setCoreColor(coreColor);             // 核心颜色
        flareEntity.setFringeColor(fringeColor);         // 边缘颜色
        flareEntity.setAdditiveBlend();                  // 使用加法混合

        flareEntity.setSmoothDisc();                     // 平滑圆盘
        flareEntity.autoAspect();                        // 自动宽高比
        flareEntity.setNoisePower(noisePower);           // 噪点强度
        flareEntity.setGlowPower(glowPower);             // 辉光强度
        flareEntity.setGlobalTimer(in, full, out);       // 设置淡入、全亮、淡出时间

        // 设置渲染图层（粒子层上方稍低处）
        flareEntity.setLayer(CombatEngineLayers.ABOVE_PARTICLES_LOWER);

        // 将光晕实体添加到战斗渲染管理器中
        CombatRenderingManager.addEntity(flareEntity);
    }

    /**
     * 添加精灵到目标实体
     * 
     * @param sprite 精灵图
     * @param target 目标实体
     * @param in 淡入时间
     * @param full 保持时间
     * @param out 淡出时间
     * @param layer 渲染层级
     * @return SpriteEntity实体
     */
    public static SpriteEntity addSpriteToTarget(
            SpriteAPI sprite, CombatEntityAPI target, 
            float in, float full, float out, 
            CombatEngineLayers layer) {

        SpriteEntity spriteEntity = new SpriteEntity(sprite);
        spriteEntity.setControlData(new EntityBasedControlData(target));
        spriteEntity.setLayer(layer);
        spriteEntity.setGlobalTimer(in, full, out);
        
        CombatRenderingManager.addEntity(spriteEntity);

        return spriteEntity;
    }

    /**
     * 添加基础实例数据
     * 
     * @param spriteEntity 精灵实体
     * @param in 淡入时间
     * @param full 保持时间
     * @param out 淡出时间
     * @param facing 朝向
     * @param turnRate 旋转速度
     * @param growth 增长速度
     * @return Instance2Data实例
     */
    public static org.boxutil.units.standard.attribute.Instance2Data addBasicInstanceData(
            SpriteEntity spriteEntity, 
            float in, float full, float out, 
            float facing, float turnRate, float growth) {

        List<InstanceDataAPI> particleList = new ArrayList<>();
        org.boxutil.units.standard.attribute.Instance2Data data = 
                new org.boxutil.units.standard.attribute.Instance2Data();
        data.setFacing(facing);
        data.setTurnRate(turnRate);
        data.setScale(1, 1);
        data.setScaleRate(growth, growth);
        data.setTimer(in, full, out);
        particleList.add(data);

        spriteEntity.setInstanceData(particleList);
        spriteEntity.setInstanceDataRefreshAllFromCurrentIndex();
        spriteEntity.submitInstanceData();
        spriteEntity.setRenderingCount(1);
        spriteEntity.setAlwaysRefreshInstanceData(true);

        return data;
    }

    /**
     * 添加空间扭曲效果
     * 
     * @param location 位置
     * @param range 范围
     * @param intensity 强度
     * @param in 淡入时间
     * @param full 保持时间
     * @param out 淡出时间
     * @param fadeOut 淡出时间（重复参数，保持兼容性）
     */
    public static void addDistortion(Vector2f location, float range, 
                                     float intensity, float in, float full, 
                                     float out, float fadeOut) {
        // 使用原版API添加扭曲效果
        Global.getCombatEngine().addSwirlyNebulaParticle(
            location, 
            ZERO, 
            range, 
            2f, // 持续时间倍数
            0.5f, // 增长速度
            0.5f, // 旋转速度
            in + full + out, 
            new Color(100, 100, 100, (int)(intensity * 255)), 
            true
        );
    }

    /**
     * 创建爆发实例数据
     * 用于创建向外爆发的粒子效果
     * 
     * @param count 粒子数量
     * @param range 范围
     * @param rangeRandom 范围随机值（未使用）
     * @param angle 角度
     * @param speed 速度
     * @param size 大小
     * @param sizeMult 大小倍数
     * @param colors 颜色数组
     * @param in 淡入时间
     * @param out 淡出时间
     * @return 实例数据列表
     */
    public static List<InstanceDataAPI> createBurstOutInstanceData(
            int count, float[] range, Object rangeRandom, 
            float[] angle, float[] speed, float[] size, 
            float[] sizeMult, Color[] colors, 
            float in, float out) {

        List<InstanceDataAPI> result = new ArrayList<>();
        
        for (int i = 0; i < count; i++) {
            org.boxutil.units.standard.attribute.Instance2Data data = 
                    new org.boxutil.units.standard.attribute.Instance2Data();
            
            // 随机角度
            float particleAngle = (angle != null && angle.length > 0) 
                    ? angle[0] + RANDOM.nextFloat() * (angle.length > 1 ? angle[1] : 360f)
                    : RANDOM.nextFloat() * 360f;
            
            // 随机大小
            float particleSize = (size != null && size.length > 0)
                    ? size[0] + RANDOM.nextFloat() * (size.length > 1 ? (size[1] - size[0]) : 0f)
                    : 1f;
            
            // 随机大小倍数
            float particleSizeMult = (sizeMult != null && sizeMult.length > 0)
                    ? sizeMult[0] + RANDOM.nextFloat() * (sizeMult.length > 1 ? (sizeMult[1] - sizeMult[0]) : 0f)
                    : 1f;
            
            data.setFacing(particleAngle);
            data.setScale(particleSize, particleSize);
            data.setScaleRate(particleSize * (particleSizeMult - 1f) / (in + out), 
                            particleSize * (particleSizeMult - 1f) / (in + out));
            data.setTimer(in, 0f, out);
            
            // 随机颜色
            if (colors != null && colors.length > 0) {
                Color color = colors[RANDOM.nextInt(colors.length)];
                data.setColor(color);
            }
            
            result.add(data);
        }
        
        return result;
    }

    /**
     * 添加星云粒子
     * 
     * @param instanceData 实例数据列表
     * @param location 位置
     * @param size 大小
     * @param brightness 亮度
     * @param in 淡入时间
     * @param out 淡出时间
     * @param color 颜色
     * @return SpriteEntity实体
     */
    public static SpriteEntity addNebulaParticle(
            List<InstanceDataAPI> instanceData, 
            Vector2f location, float size, float brightness, 
            float in, float out, Color color) {

        SpriteEntity spriteEntity = new SpriteEntity("graphics/fx/cleaner_clouds00.png");
        spriteEntity.getMaterialData().setColor(color);
        spriteEntity.getMaterialData().setColorAlpha(brightness);
        spriteEntity.getMaterialData().setAlphaToEmissive(0f);

        spriteEntity.setLocation(location);
        spriteEntity.setBaseSizePerTiles(size * 0.5f, size * 0.5f);
        spriteEntity.setAdditiveBlend();
        spriteEntity.setTileSize(2, 2);
        spriteEntity.setRandomTile(true);
        spriteEntity.setRandomTileEachInstance(true);

        spriteEntity.setInstanceData(instanceData);
        spriteEntity.setInstanceDataRefreshAllFromCurrentIndex();
        spriteEntity.submitInstanceData();
        spriteEntity.setRenderingCount(instanceData.size());
        spriteEntity.setAlwaysRefreshInstanceData(true);

        spriteEntity.setLayer(CombatEngineLayers.ABOVE_PARTICLES_LOWER);
        spriteEntity.setGlobalTimer(in, 0f, out);
        
        CombatRenderingManager.addEntity(spriteEntity);

        return spriteEntity;
    }

    /**
     * 将渐变转换为计时器
     * 
     * @param in 淡入时间
     * @param out 淡出时间
     * @return 时间数组 [in, out]
     */
    public static float[] rampToTimer(float in, float out) {
        return new float[]{in, out};
    }

    /**
     * SDF纹理缓存
     * 避免重复生成相同的SDF纹理
     */
    public static Map<String, int[]> SAVED_SDF = new HashMap<>();

    /**
     * 生成SDF（Signed Distance Field）纹理
     * 
     * SDF纹理用于高质量的边缘渲染和发光效果
     * 
     * @param sprite 源精灵
     * @param stroke 描边宽度
     * @return int数组 [纹理ID, 宽度, 高度]
     */
    public static int[] genSDF(SpriteAPI sprite, int stroke) {
        // 检查缓存
        String key = sprite.getTextureId() + "_" + stroke;
        if (SAVED_SDF.containsKey(key)) {
            return SAVED_SDF.get(key);
        }

        // 生成SDF纹理
        float strokeDiv = 1f / stroke;
        int localWidth = (int) sprite.getWidth();
        int localHeight = (int) sprite.getHeight();
        
        int[] sdfs = org.boxutil.util.ShaderUtil.genSDF(
            sprite.getTextureId(), 
            GL11.GL_ALPHA, 
            localWidth, 
            localHeight, 
            stroke, 
            stroke, 
            0.5f, 
            org.boxutil.util.CalculateUtil.getExponentPOTMin(Math.max(localWidth, localHeight)), 
            strokeDiv, 
            strokeDiv
        );

        // 缓存结果
        SAVED_SDF.put(key, sdfs);

        // 设置纹理参数
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, sdfs[0]);
        GL11.glTexParameteri(
            GL11.GL_TEXTURE_2D, 
            GL11.GL_TEXTURE_WRAP_S, 
            GL12.GL_CLAMP_TO_EDGE
        );
        GL11.glTexParameteri(
            GL11.GL_TEXTURE_2D, 
            GL11.GL_TEXTURE_WRAP_T, 
            GL12.GL_CLAMP_TO_EDGE
        );
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        return sdfs;
    }

    /**
     * 添加平滑粒子（简化版本）
     * 
     * @param loc 位置
     * @param vel 速度
     * @param size 大小
     * @param brightness 亮度
     * @param duration 持续时间
     * @param color 颜色
     * @return SpriteEntity实体
     */
    public static SpriteEntity addSmoothParticle(Vector2f loc, Vector2f vel, float size, 
                                                  float brightness, float duration, Color color) {
        return addSingleParticle(null, loc, vel, size, brightness, 0f, 0f, duration, 
                                color, "graphics/fx/hit_glow.png", 0f, 0f, 0f);
    }

    /**
     * 实体基础控制数据类
     * 用于将渲染实体绑定到游戏实体（如舰船、导弹等）
     */
    public static class EntityBasedControlData extends BaseControlData {

        /** 绑定的游戏实体 */
        public final CombatEntityAPI entity;
        
        /** 是否跟随实体朝向 */
        public boolean withFacing = true;

        /**
         * 构造函数
         * 
         * @param entity 要绑定的游戏实体
         */
        public EntityBasedControlData(CombatEntityAPI entity) {
            this.entity = entity;
        }

        /**
         * 每帧更新控制逻辑
         * 将渲染实体的位置和朝向同步到游戏实体
         */
        @Override
        public void controlAdvance(RenderDataAPI renderEntity, float amount) {
            if (entity != null) {
                // 同步位置
                renderEntity.setLocation(entity.getLocation());
                // 同步朝向（如果启用）
                if (withFacing) {
                    renderEntity.setFacingScale(
                        MathUtils.clampAngle(entity.getFacing()), 1f, 1f
                    );
                }

                // 检查实体是否仍然有效
                CombatEngineAPI engine = Global.getCombatEngine();
                if (engine == null || !isEntityValid(engine, entity)) {
                    // 如果实体无效，开始淡出
                    float[] timer = renderEntity.getGlobalTimer();
                    if (timer[0] > 1) {
                        timer[0] = 1;
                    }
                }
            }
        }

        /**
         * 检查实体是否有效
         * 
         * @param engine 战斗引擎
         * @param entity 要检查的实体
         * @return 实体是否有效
         */
        public static boolean isEntityValid(CombatEngineAPI engine, CombatEntityAPI entity) {
            // 检查是否在场
            if (!engine.isEntityInPlay(entity)) return false;
            // 检查是否过期
            if (entity.isExpired()) return false;

            // 舰船特殊检查
            if (entity instanceof ShipAPI) {
                ShipAPI ship = (ShipAPI) entity;
                if (!ship.isAlive()) return false;
            }
            
            // 弹幕特殊检查
            if (entity instanceof DamagingProjectileAPI) {
                DamagingProjectileAPI projectile = (DamagingProjectileAPI) entity;
                if (projectile.isFading()) return false;
                
                // 导弹特殊检查
                if (projectile instanceof MissileAPI) {
                    MissileAPI missile = (MissileAPI) projectile;
                    if (missile.didDamage()) return false;
                }
            }

            return true;
        }

        @Override
        public boolean controlAlphaBasedTimer(RenderDataAPI renderEntity) {
            return false;
        }
    }
}
