package data.scripts.weapons;

import java.awt.Color;

import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.ProximityExplosionEffect;
import com.fs.starfarer.api.impl.combat.NegativeExplosionVisual.NEParams;
import com.fs.starfarer.api.impl.combat.RiftCascadeMineExplosion;
// 注意：原来的 RiftLightningEffect 导入未被使用，已删除（见下文说明）

/**
 * 裂隙闪电雷的爆炸效果类。
 * <p>
 * 由 VIII_lightning_minelayer_mine.proj 中的 behaviorSpec.onExplosionEffect 反射调用，
 * 因此：1) 必须有 public 无参构造器；2) 类名大小写必须与字符串完全一致。
 * <p>
 * 本类只负责"视觉表现"，不含任何伤害结算逻辑。伤害需要在
 * .proj 的 explosionSpec 中定义，或在本类的 onExplosion 里自行结算。
 */
public class VIII_LightningMineExplosion implements ProximityExplosionEffect {

    // 裂隙颜色：R=27, G=19, B=255, A=255（蓝紫色，完全不透明）
    // 如需与 .proj 的 glowColor(255,50,50,255 红色) 保持一致，请改成同一个色值
    private static final Color RIFT_COLOR = new Color(27, 19, 255, 255);

    // 裂隙半径（世界单位）
    private static final float RIFT_RADIUS = 75f;

    /**
     * 引信触发时被调用。
     *
     * @param explosion        爆炸生成的投射物对象（携带位置、归属等信息，常用于挂载视觉效果）
     * @param originalProjectile 原始的投射物（即那颗水雷）。部分实现中它与 explosion 是同一对象，
     *                           不要假定两者一定不同
     */
    @Override
    public void onExplosion(DamagingProjectileAPI explosion, DamagingProjectileAPI originalProjectile) {
        // 调试用输出已注释：原文本 "EXPLOSION" 意为 "爆炸"
        //System.out.println("爆炸");

        // 生成一份"标准裂隙"参数模板：传入颜色与半径
        NEParams p = RiftCascadeMineExplosion.createStandardRiftParams(RIFT_COLOR, RIFT_RADIUS);

        // 淡出时长（秒）：越大，裂隙残留越久。原值为 0.25f，此处拉长为 1 秒
        p.fadeOut = 1f;

        // 命中辉光尺寸倍率：原值为 0.5f
        p.hitGlowSizeMult = 0.6f;

        // 若启用"反向变暗"效果（把负爆炸从发光改为压暗画面），需补上
        // import com.fs.starfarer.api.impl.combat.NSProjEffect; 否则无法解析下面的常量
        //p.invertForDarkening = NSProjEffect.STANDARD_RIFT_COLOR;

        // 裂隙环带厚度：原值为 25f，此处加厚到 50f
        // 注意：厚度(50) 明显大于半径(20)，视觉上容易糊成一团，建议按半径的 0.4~0.8 倍调节
        p.thickness = 50f;

        // 以下为被注释掉的原始参数，备查：
        //p.hitGlowSizeMult = 0.5f;   // 命中辉光尺寸倍率
        //p.thickness = 25f;          // 环带厚度
        //p.fadeOut = 0.25f;          // 淡出时长

        // 依据 explosion 的位置与归属，生成标准裂隙实体（纯视觉，不含伤害）
        RiftCascadeMineExplosion.spawnStandardRift(explosion, p);
    }
}