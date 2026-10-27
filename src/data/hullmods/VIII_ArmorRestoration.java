package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;
import org.lazywizard.lazylib.VectorUtils;
import org.lazywizard.lazylib.combat.DefenseUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.util.vector.Vector2f;

import java.awt.*;

import static data.scripts.VIII_tools.createShaderProgram;


/**
 * VIII 基础设计框架船体插件。
 * 提供以下效果：
 * 1. 降低受到的 EMP、护盾、船体伤害。
 * 2. 添加装甲缓慢再生机制：战斗中有装甲损伤后，若5秒内未再受伤害，开始每秒恢复最大装甲值的1.5%，
 *    若舰船带有 "kk" 内插，则恢复速度加倍。
 * 3. 添加被击中时的视觉特效：在受击点渲染一个圆形护盾光晕，使用自定义着色器混合舰船纹理和特效纹理。
 */
public class VIII_ArmorRestoration extends BaseHullMod {
    // 装甲再生速率：每秒恢复最大装甲值的百分比
    private static final float REGEN_PER_SEC_PERCENT = 2f;
    // 装甲再生所需的无伤时间（秒）
    private static final float DISRUPTION_TIME = 5f;

    /**
     * 在舰船创建前应用效果，修改基础属性。
     */
    @Override
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id){
//        // 降低受到的EMP伤害至75%
//        stats.getEmpDamageTakenMult().modifyMult(id, 0.75f);
//        // 降低护盾受到的伤害至85%
//        stats.getShieldDamageTakenMult().modifyMult(id,0.85f);
//        // 降低船体受到的伤害至95%
//        stats.getHullDamageTakenMult().modifyMult(id,0.95f);
        stats.getArmorBonus().modifyMult(id, 1.12F);
        stats.getCombatEngineRepairTimeMult().modifyMult(id, 0.7F);//修引擎时间
        stats.getCombatWeaponRepairTimeMult().modifyMult(id, 0.7F);//修武器时间
    }
    @Override
    public boolean shouldAddDescriptionToTooltip(ShipAPI.HullSize hullSize, ShipAPI ship, boolean isForModSpec) {
        return false; // 不自动添加描述，使用自定义描述
    }

    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, ShipAPI.HullSize hullSize, ShipAPI ship, float width, boolean isForModSpec) {
        float pad = 3.0F;
        float opad = 10.0F;
        Color h = Misc.getHighlightColor();
        Color bad = Misc.getNegativeHighlightColor();

        // 基础说明
        tooltip.addPara("为了在河外星系可能的，最恶劣环境生产下来，这些舰船内置了一系列受到管制的激进技术。该舰的装甲和结构融入了一种简单而高效的活性纳米致动器，配合舰体内置的纳米锻炉，使其不但获得更强大的抗击力，同时还能不间断的修复结构完整性。", opad);
        tooltip.addSectionHeading("基础效果", Alignment.MID, pad);
        // 整备时间和重新装填时间惩罚
        tooltip.addPara("提高 %s 装甲值.\n在战斗中修复失效的武器和引擎时间减少了 %s .\n在停止受到伤害{%s}后,装甲会以每秒{%s}的比例进行自我修复.", pad,Misc.getTextColor(),h,"12%","30%","5秒","2%");
    }

    /**
     * 舰船创建后添加监听器。
     */
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        // 添加受击渲染监听器（用于生成视觉特效）
//        if(!ship.hasListenerOfClass(VIII_render_listener.class)){
//            ship.addListener(new VIII_render_listener(ship));
//        }

        // 添加装甲再生重置监听器（受到伤害时重置计时器）
        if(!ship.hasListenerOfClass(VIII_ARRListener.class)) {
            ship.addListener(new VIII_ARRListener());
        }
    }

    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        if(ship.getVariant().hasHullMod("VIII_Coordinate")) return;
        // 如果舰船没有装甲损伤，则直接返回
        if (!DefenseUtils.hasArmorDamage(ship)) return;
        // 如果是残骸或正在散热，不进行再生
        if (ship.isHulk() || ship.getFluxTracker().isVenting()) return;

        // 检查是否拥有“灌溉机”插件（无视冷却）
        boolean hasWatering = ship.getVariant().hasHullMod("VIII_WateringMachine");
        if (!hasWatering) {
            // 获取累计无伤计时器（存储在动态统计中）
            float timer = ship.getMutableStats().getDynamic().getStat("viii_arr_timer").getModifiedValue() + amount;
            ship.getMutableStats().getDynamic().getStat("viii_arr_timer").modifyFlat("tracker", timer);
            // 如果累计时间未达到要求，不进行再生
            if (timer < DISRUPTION_TIME) return;
        }

        // 获取装甲网格信息
        ArmorGridAPI armorGrid = ship.getArmorGrid();
        float[][] grid = armorGrid.getGrid();
        float max = armorGrid.getMaxArmorInCell(); // 每个装甲格的最大值
        // 如果舰船过载，再生速率减半
        float statusMult = ship.getFluxTracker().isOverloaded() ? 0.5f : 1f;

        // 计算每帧修复量：最大装甲值 × 再生速率百分比 × 时间增量 × 状态倍率
        float repairAmount;
        if (hasWatering) {
            // 若有 "VIII_WateringMachine" 内插，修复速率翻倍（除以50而不是100）
            repairAmount = max * (REGEN_PER_SEC_PERCENT / 50f) * amount * statusMult;
        } else {
            repairAmount = max * (REGEN_PER_SEC_PERCENT / 100f) * amount * statusMult;
        }

        // 遍历所有装甲格，将低于最大值的格子增加修复量
        for (int x = 0; x < grid.length; x++) {
            for (int y = 0; y < grid[x].length; y++) {
                if (grid[x][y] < max) {
                    float newValue = Math.min(grid[x][y] + repairAmount, max);
                    armorGrid.setArmorValue(x, y, newValue);
                }
            }
        }
        // 同步舰船的装甲状态（更新可视化）
        ship.syncWithArmorGridState();
    }

    /**
     * 装甲再生重置监听器。
     * 当舰船受到伤害（非护盾）时，重置无伤计时器。
     */
    private static class VIII_ARRListener implements DamageTakenModifier {
        @Override
        public String modifyDamageTaken(Object param,
                                        CombatEntityAPI target,
                                        DamageAPI damage,
                                        Vector2f point,
                                        boolean shieldHit) {
            // 如果是护盾命中或目标不是舰船，则忽略
            if (shieldHit || !(target instanceof ShipAPI ship)) return null;
            /*if(ship.getVariant().hasHullMod("VIII_WateringMachine")) {
                return null;
            }else {*/
                // 重置计时器（移除 modifier）
            ship.getMutableStats().getDynamic().getStat("viii_arr_timer")
                        .unmodify("tracker");
            //}
            return null;
        }
    }

    /**
     * 受击渲染监听器。
     * 当舰船受到伤害（无论是否护盾）时，创建一个自定义渲染插件来显示护盾受击效果。
     */
    public static class VIII_render_listener implements DamageTakenModifier {
        ShipAPI ship;
        int programID; // 自定义着色器程序ID

        public VIII_render_listener(ShipAPI ship){
            // 从顶点和片段着色器源码创建着色器程序
            programID = createShaderProgram(vert, frag2);
            this.ship = ship;
        }

        @Override
        public String modifyDamageTaken(Object param, CombatEntityAPI target, DamageAPI damage, Vector2f point, boolean shieldHit) {
            // 计算受击点相对于舰船中心的偏移（世界坐标）
            Vector2f hitpoint = new Vector2f();
            Vector2f.sub(point, ship.getLocation(), hitpoint);
            // 创建并添加渲染插件
            BaseCombatLayeredRenderingPlugin plugin = new VIII_closeshield_plugin(ship, hitpoint, ship.getFacing(),
                    0.2f, 0.1f, 1f, programID);
            Global.getCombatEngine().addLayeredRenderingPlugin(plugin);
            return null;
        }
    }

    // 顶点着色器源码：简单的传递纹理坐标
    public static String vert =
            """
            #version 110
            
            varying vec2 Vbc_fargUV;
            void main() {
                gl_Position = ftransform();
                Vbc_fargUV = gl_MultiTexCoord0.xy;
            }""";

    // 片段着色器源码：混合特效纹理和舰船纹理，根据受击点产生圆形渐变
    public static String frag2 =
            """
            #version 110
            uniform sampler2D Vbc_texture1;      // 特效纹理（护盾光晕）
            uniform sampler2D Vbc_textureship;    // 舰船纹理
            uniform vec4 Vbc_texturecolor;        // 特效颜色（这里为青色）
            uniform vec2 Vbc_state;                // 状态（未使用）
            uniform vec2 Vbc_scale;                 // 纹理缩放（用于特效纹理）
            
            uniform float Vbc_maxrange;             // 圆形渐变的最大半径（uv空间）
            uniform float Vbc_innerrange;           // 圆形渐变的内半径
            uniform vec2 Vbc_point;                  // 受击点的uv坐标
            uniform float Vbc_timelevel;             // 时间因子（用于淡出）
            
            varying vec2 Vbc_fargUV;                 // 当前片元的uv坐标
            
            void main() {
                // 对特效纹理进行缩放采样（使纹理重复？）
                vec2 scaledTexCoord = Vbc_fargUV * Vbc_scale;
                vec4 v1 = texture2D(Vbc_texture1, scaledTexCoord);
                // 采样舰船纹理（使用原始uv）
                vec4 v2 = texture2D(Vbc_textureship, Vbc_fargUV);
                // 结果颜色：取v1的rgb（但用了xyx，即r,g,r），alpha为v1.a * Vbc_state.x * v2.w
                // 注意：v2.w 是舰船纹理的alpha，可能用于控制特效只在舰船不透明部分显示
                vec4 result = vec4(v1.xyx, v1.w * Vbc_state.x * v2.w);
            
                // 如果alpha不为0，将结果颜色与指定的颜色混合（混合因子0.6）
                if(result.w != 0.0){
                    result = mix(vec4(result.xyz, result.w), vec4(Vbc_texturecolor.xyz, result.w), 0.6);
                }
                // 颜色增强： (c-0.5)*1.9+0.5 相当于对比度增强
                result.xyz = vec3((result.xyz - 0.5) * 1.9 + 0.5);
                // 乘以时间因子淡出
                result.w = result.w * Vbc_timelevel;
            
                // 计算当前片元uv到受击点uv的距离
                float lenth = length(Vbc_fargUV - Vbc_point);
                // 根据距离计算alpha衰减：平滑从内半径到最大半径的过渡
                float alpha = smoothstep(Vbc_innerrange, Vbc_maxrange, lenth);
                result.w = result.w * (1.0 - alpha);
                gl_FragColor = result;
            }""";

    /**
     * 自定义渲染插件，用于在受击点绘制一个圆形护盾光晕。
     * 它会在舰船之上绘制一个四边形，应用自定义着色器，根据受击点位置生成渐变效果。
     */
    public static class VIII_closeshield_plugin extends BaseCombatLayeredRenderingPlugin implements DamageTakenModifier {
        public ShipAPI ship;
        public Vector2f hitpoint;      // 受击点相对于舰船中心的偏移（世界坐标）
        public CombatEngineAPI engine;
        float face;                    // 舰船朝向（未使用？）
        public boolean shuold_expired = false; // 是否应过期（移除）
        Vector2f scale = new Vector2f();       // 特效纹理的缩放因子
        Color color = new Color(0, 165, 230, 255);  // 特效颜色（青色）
        Vector2f size;                          // 渲染四边形的半尺寸（像素）
        float maxrange;                          // 圆形渐变的最大半径（uv空间）
        float innerrange;                        // 圆形渐变的内半径
        float time;                              // 剩余存活时间
        float time_interv;                        // 初始时间（用于计算时间比例）
        Vector2f spriteoffset = new Vector2f();   // 舰船纹理中心到实际渲染原点的偏移

        int programID;                            // 着色器程序ID
        // 着色器uniform变量位置
        int Vbc_texture1_location;
        int Vbc_textureship_location;
        int Vbc_texturecolor_location;
        int Vbc_state_location;
        int Vbc_scale_location;
        int sprite_textrue = Global.getSettings().getSprite("fx","VIII_shieldTexture").getTextureId(); // 特效纹理
        int sprite_ship;                          // 舰船纹理ID
        int Vbc_maxrange;
        int Vbc_innerrange;
        int Vbc_point;
        int Vbc_time;

        public VIII_closeshield_plugin(ShipAPI ship, Vector2f point, float face, float maxrange, float innerrange, float time, int programID) {
            this.programID = programID;
            this.hitpoint = point;
            this.ship = ship;
            this.face = face;
            this.layer = CombatEngineLayers.ABOVE_SHIPS_LAYER; // 在舰船图层之上渲染
            this.engine = Global.getCombatEngine();
            this.maxrange = maxrange;
            this.innerrange = innerrange;
            this.time = time;
            time_interv = time;

            // 获取着色器uniform位置
            Vbc_texture1_location = GL20.glGetUniformLocation(programID, "Vbc_texture1");
            Vbc_textureship_location = GL20.glGetUniformLocation(programID, "Vbc_textureship");
            Vbc_texturecolor_location = GL20.glGetUniformLocation(programID, "Vbc_texturecolor");
            Vbc_state_location = GL20.glGetUniformLocation(programID, "Vbc_state");
            Vbc_scale_location = GL20.glGetUniformLocation(programID, "Vbc_scale");

            Vbc_maxrange = GL20.glGetUniformLocation(programID, "Vbc_maxrange");
            Vbc_innerrange = GL20.glGetUniformLocation(programID, "Vbc_innerrange");
            Vbc_point = GL20.glGetUniformLocation(programID, "Vbc_point");
            Vbc_time = GL20.glGetUniformLocation(programID, "Vbc_timelevel");

            Global.getLogger(this.getClass()).info("state_location :" + Vbc_state_location);

            // 计算纹理缩放因子：使特效纹理的像素密度适应舰船纹理尺寸
            // 这里意图是找到大于等于舰船纹理宽度的最小2的幂，除以64得到缩放因子
            int i = 0;
            do { i += 1; } while ((1 << i) < ship.getSpriteAPI().getWidth()); // 原代码使用 (2 ^ i) 实际上是位运算？应为 1<<i
            scale.x = (1 << i) / 64f;
            i = 0;
            do { i += 1; } while ((1 << i) < ship.getSpriteAPI().getHeight());
            scale.y = (1 << i) / 64f;

            // 渲染四边形的半尺寸 = 舰船纹理尺寸的一半
            size = new Vector2f(ship.getSpriteAPI().getWidth() * 0.5f, ship.getSpriteAPI().getHeight() * 0.5f);
            // 舰船纹理中心坐标（相对于纹理左上角？通常getCenterX/Y返回的是纹理中心相对于左下角？）
            Vector2f shiplocation = new Vector2f(ship.getSpriteAPI().getCenterX(), ship.getSpriteAPI().getCenterY());
            // 计算偏移量：从纹理左下角到纹理中心的偏移，用于后续将四边形中心对齐到舰船中心
            Vector2f.sub(size, shiplocation, spriteoffset);

            sprite_ship = ship.getSpriteAPI().getTextureId();
        }

        /**
         * 判断是否应该暂停更新（游戏暂停或舰船不存在）。
         */
        public boolean shouldPause() {
            if (engine == null) return true;
            if (engine.isPaused()) return true;
            return !engine.isEntityInPlay(ship);
        }

        public float getRenderRadius() { return 10000f; }
        public boolean isExpired() { return shuold_expired; }

        @Override
        public String modifyDamageTaken(Object param, CombatEntityAPI target, DamageAPI damage, Vector2f point, boolean shieldHit) {
            return null; // 此监听器只用于渲染，不修改伤害
        }

        public void advance(float amount) {
            if (shouldPause()) return;
            if (time > 0.0) {
                time -= amount;
            } else time = 0f;
            if (time == 0) { shuold_expired = true; }
        }

        @Override
        public void render(CombatEngineLayers layer, ViewportAPI viewport) {
            if (programID != 0 && ship != null) { // 注意：原代码有注释 //&& layer == CombatEngineLayers.ABOVE_SHIPS_LAYER
                Vector2f location = ship.getLocation(); // 舰船世界坐标
                float facing = ship.getFacing() - 90f;   // 舰船朝向调整（可能因为OpenGL坐标系与游戏坐标系差异）

                // 激活着色器程序
                GL20.glUseProgram(programID);

                // 设置纹理单元0：特效纹理
                GL20.glUniform1i(Vbc_texture1_location, 0);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, sprite_textrue);

                // 设置纹理单元1：舰船纹理
                GL20.glUniform1i(Vbc_textureship_location, 1);
                GL13.glActiveTexture(GL13.GL_TEXTURE1);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, sprite_ship);

                // 设置其他uniform
                GL20.glUniform2f(Vbc_state_location, 1f, 1f);
                GL20.glUniform2f(Vbc_scale_location, scale.x, scale.y);
                GL20.glUniform4f(Vbc_texturecolor_location,
                        color.getRed() / 255f,
                        color.getGreen() / 255f,
                        color.getBlue() / 255f,
                        color.getAlpha() / 255f);
                GL20.glUniform1f(Vbc_innerrange, innerrange);
                GL20.glUniform1f(Vbc_maxrange, maxrange);
                GL20.glUniform1f(Vbc_time, time / time_interv); // 归一化时间因子

                // 计算受击点在舰船纹理上的UV坐标
                Vector2f newrenderlocation = new Vector2f();
                Vector2f.add(location, spriteoffset, newrenderlocation); // 渲染四边形中心的世界坐标

                // 将受击点（相对于舰船中心）转换为相对于渲染原点的坐标，并考虑舰船旋转
                Vector2f rl = new Vector2f();
                // 旋转受击点：注意原代码使用 90f - ship.getFacing()，可能是为了匹配纹理坐标轴
                VectorUtils.rotateAroundPivot(hitpoint, new Vector2f(), 90f - ship.getFacing(), rl);

                // 获取纹理尺寸信息
                float f1 = ship.getSpriteAPI().getTextureWidth() / 2f;   // 纹理半宽
                float f2 = ship.getSpriteAPI().getTextureHeight() / 2f;  // 纹理半高
                // 实际像素尺寸与纹理像素的比例（用于坐标转换）
                float actwidth = ship.getSpriteAPI().getWidth() / ship.getSpriteAPI().getTextureWidth();
                float actheight = ship.getSpriteAPI().getHeight() / ship.getSpriteAPI().getTextureHeight();

                // 计算受击点在纹理坐标系中的UV坐标（以纹理中心为原点？）
                // 公式： f1 - (spriteoffset.x)/actwidth + (rl.x)/actwidth
                // 其中 spriteoffset 是从纹理中心到左下角的偏移，rl 是旋转后的受击点偏移（世界坐标）
                // 除以 actwidth 转换为纹理像素单位
                f1 = f1 - (spriteoffset.x) / actwidth + (rl.x) / actwidth;
                f2 = f2 - (spriteoffset.y) / actheight + (rl.y) / actwidth; // 注意这里第二个用了 actwidth？可能是笔误，应为 actheight

                GL20.glUniform2f(Vbc_point, f1, f2); // 传递给着色器

                // 开始渲染四边形
                GL11.glPushMatrix();

                // 移动到舰船世界坐标
                GL11.glTranslatef(location.x, location.y, 0f);
                // 旋转使四边形与舰船朝向一致（注意旋转方向）
                GL11.glRotatef(facing, 0f, 0f, 1f);
                // 平移使得纹理中心对齐到舰船中心
                GL11.glTranslatef(spriteoffset.x, spriteoffset.y, 0f);

                GL11.glEnable(GL11.GL_BLEND);
                GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_CONSTANT_ALPHA); // 使用常量alpha混合？可能有问题

                // 绘制一个四边形，覆盖整个舰船纹理区域
                GL11.glBegin(GL11.GL_QUADS);
                GL11.glTexCoord2f(0f, 0f);                     // 左下
                GL11.glVertex2f(-size.x, -size.y);

                GL11.glTexCoord2f(ship.getSpriteAPI().getTextureWidth(), 0f); // 右下
                GL11.glVertex2f(size.x, -size.y);

                GL11.glTexCoord2f(ship.getSpriteAPI().getTextureWidth(), ship.getSpriteAPI().getTextureHeight()); // 右上
                GL11.glVertex2f(size.x, size.y);

                GL11.glTexCoord2f(0f, ship.getSpriteAPI().getTextureHeight()); // 左上
                GL11.glVertex2f(-size.x, size.y);
                GL11.glEnd();

                GL11.glPopMatrix();

                // 清理纹理绑定和着色器
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
                GL20.glUseProgram(0);

            } else {
                Global.getLogger(this.getClass()).info("error3_1");
                shuold_expired = true;
            }
        }
    }
}