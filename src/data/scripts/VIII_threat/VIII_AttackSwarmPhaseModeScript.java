// 包路径：远行星号游戏API实现中的战斗威胁包
package data.scripts.VIII_threat;

// 导入Java标准库中的列表接口

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.util.Misc;

import java.awt.*;
import java.util.List;

/**
 * 攻击群相位模式脚本
 * 当舰船进入相位模式时，修改围绕舰船的“翻涌群”特效参数，并补充特定武器的弹药
 */
public class VIII_AttackSwarmPhaseModeScript extends BaseEveryFrameCombatPlugin {

    // 相位模式的持续时间（秒），默认为20秒
    public static float DURATION = 20f;

    // 剩余时间（秒）
    protected float timeLeft;
    // 应用相位模式的舰船
    protected ShipAPI ship;
    // 原始特效颜色（备份，用于恢复）
    protected Color origColor = VIII_VoltaicDischargeOnFireEffect.EMP_FRINGE_COLOR;
    // 原始特效半径（备份）
    protected float origRadius = 120f;
    // 原始闪烁频率（备份）
    protected float origFlashFrequency = 1f;
    // 原始闪烁速率倍率（备份）
    protected float origFlashRateMult = 1f;
    // 光晕淡出延迟时间（秒），在相位模式结束后延迟关闭特效
    protected float glowFadeDelay = 2f;

    /**
     * 构造函数，使用默认持续时间
     * @param ship 要应用相位模式的舰船
     */
    public VIII_AttackSwarmPhaseModeScript(ShipAPI ship) {
        this(ship, DURATION);
    }

    /**
     * 构造函数，指定持续时间
     * @param ship 要应用相位模式的舰船
     * @param dur  持续时间（秒）
     */
    public VIII_AttackSwarmPhaseModeScript(ShipAPI ship, float dur) {
        this.ship = ship;
        this.timeLeft = dur;

        // 开启相位模式（修改特效参数，补充弹药）
        turnOnPhaseMode();

        // 将本插件添加到战斗引擎，使其每帧执行advance方法
        Global.getCombatEngine().addPlugin(this);
    }

    /**
     * 开启相位模式：修改翻涌群特效参数，并为特定标签的武器补充弹药
     */
    public void turnOnPhaseMode() {
        // 获取该舰船关联的翻涌群特效
        VIII_RoilingSwarmEffect swarm = VIII_RoilingSwarmEffect.getSwarmFor(ship);
        if (swarm == null) return;  // 没有翻涌群则直接返回

        // 备份原始特效参数，以便关闭时恢复
        origColor = swarm.params.flashFringeColor;
        origRadius = swarm.params.flashRadius;
        origFlashFrequency = swarm.params.flashFrequency;
        origFlashRateMult = swarm.params.flashRateMult;

        // 设置相位模式下的特效颜色（使用VoltaicDischargeOnFireEffect类中定义的相位边缘色，透明度60）
        swarm.params.flashFringeColor = Misc.setAlpha(VIII_VoltaicDischargeOnFireEffect.PHASE_FRINGE_COLOR, 60);
        // 设置相位模式下的特效半径增大到180
        swarm.params.flashRadius = 180f;
        // 添加相位模式标签到特效参数中，用于其他逻辑识别
        swarm.params.tags.add(VIII_VoltaicDischargeOnFireEffect.SWARM_TAG_PHASE_MODE);

        // 遍历舰船的所有武器
        for (WeaponAPI w : ship.getAllWeapons()) {
            // 如果武器使用弹药，且其规格包含FRAGMENT_GLOW标签（碎片光效类武器）
            if (w.usesAmmo() && w.getSpec().hasTag(Tags.FRAGMENT_GLOW)) {
                // 将弹药设置为1000（实际是无限弹药的效果）
                w.setAmmo(1000);
                // 注释掉的代码：w.setMaxAmmo(Integer.MAX_VALUE);
            }
            // 如果武器规格包含OVERSEER_CHARGE标签（督军充能类），或者舰船是战斗机且包含OVERSEER_CHARGE_FIGHTER标签
            if (w.getSpec().hasTag(Tags.OVERSEER_CHARGE) ||
                    (ship.isFighter() && w.getSpec().hasTag(Tags.OVERSEER_CHARGE_FIGHTER))) {
                // 将该武器的弹药补充到最大值
                w.setAmmo(w.getMaxAmmo());
            }
        }
    }

    /**
     * 关闭相位模式：恢复原始特效参数，并重置特定武器的弹药
     */
    public void turnOffPhaseMode() {
        // 获取舰船关联的翻涌群特效
        VIII_RoilingSwarmEffect swarm = VIII_RoilingSwarmEffect.getSwarmFor(ship);
        if (swarm == null) return;

        // 恢复原始特效参数
        swarm.params.flashFringeColor = origColor;
        swarm.params.flashRadius = origRadius;
        swarm.params.flashFrequency = origFlashFrequency;
        swarm.params.flashRateMult = origFlashRateMult;
        // 移除相位模式标签
        swarm.params.tags.remove(VIII_VoltaicDischargeOnFireEffect.SWARM_TAG_PHASE_MODE);

        // 遍历舰船所有武器
        for (WeaponAPI w : ship.getAllWeapons()) {
            // 如果武器使用弹药且包含FRAGMENT_GLOW标签
            if (w.usesAmmo() && w.getSpec().hasTag(Tags.FRAGMENT_GLOW)) {
                // 将弹药重置为最大弹药量（恢复正常）
                w.setAmmo(w.getMaxAmmo());
            }
        }
    }

    /**
     * 抑制光晕效果：将闪烁频率设为0，并清空特定武器的弹药
     */
    public void suppressGlow() {
        // 获取舰船关联的翻涌群特效
        VIII_RoilingSwarmEffect swarm = VIII_RoilingSwarmEffect.getSwarmFor(ship);
        if (swarm != null) {
            // 将闪烁频率设为0，停止闪烁
            swarm.params.flashFrequency = 0f;
            swarm.params.flashRateMult = 1f;
        }
        // 遍历舰船所有武器
        for (WeaponAPI w : ship.getAllWeapons()) {
            // 如果武器使用弹药且包含FRAGMENT_GLOW标签
            if (w.usesAmmo() && w.getSpec().hasTag(Tags.FRAGMENT_GLOW)) {
                // 将弹药设置为0，彻底禁止发射
                w.setAmmo(0);
            }
        }
    }

    // 每帧更新方法（由战斗引擎调用）
    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        // 如果战斗暂停，则不进行任何更新
        if (Global.getCombatEngine().isPaused()) return;

        // 减少剩余时间
        timeLeft -= amount;

        // 如果相位模式持续时间已结束
        if (timeLeft <= 0f) {
            // 减少光晕淡出延迟时间
            glowFadeDelay -= amount;
            // 抑制光晕效果（停止闪烁，清空弹药）
            suppressGlow();

            // 如果淡出延迟也已结束
            if (glowFadeDelay <= 0f) {
                // 完全关闭相位模式（恢复特效和弹药）
                turnOffPhaseMode();
                // 获取战斗引擎实例
                CombatEngineAPI engine = Global.getCombatEngine();
                // 从引擎中移除本插件，停止更新
                engine.removePlugin(this);
            }
        }
    }
}