// 声明此类所在的包路径，属于 MagicLib 子系统中的示例包
package data.scripts.shipsystems.subsystems;

// 导入舰船接口，用于获取舰船相关属性和状态
import com.fs.starfarer.api.combat.ShipAPI;
// 导入 JetBrains 的非空注解，用于标记方法返回值不会为 null
import org.jetbrains.annotations.NotNull;
// 导入 MagicLib 的无人机子系统基类，提供无人机管理的核心功能
import org.magiclib.subsystems.drones.MagicDroneSubsystem;

/**
 * 生成一架点防御（PD）无人机。
 * 该子系统没有可用的激活按键，也不会占用按键索引。
 * （通常用于被动提供护航无人机，不需要玩家手动操控）
 */
public class VIII_RoundShieldDroneSubsystem extends MagicDroneSubsystem {

    /**
     * 构造函数，创建该子系统实例并绑定到指定舰船
     * @param ship 安装此子系统的舰船
     */
    public VIII_RoundShieldDroneSubsystem(ShipAPI ship) {
        // 调用父类构造函数，完成如 stats、aiData 等字段的初始化
        super(ship);
    }

    /**
     * 此子系统是否允许分配按键
     * 返回 false 表示它不会出现在 UI 的激活栏中，也无法手动触发
     */
    @Override
    public boolean canAssignKey() {
        return false;
    }

    /**
     * 获取子系统激活后的基础持续时间（秒）
     * 返回 0 表示无人机是常驻的，不会在一定时间后自动消失
     */
    @Override
    public float getBaseActiveDuration() {
        return 0;
    }

    /**
     * 获取子系统使用后的基础冷却时间（秒）
     * 由于此子系统不可激活，冷却时间设为 0 无意义
     */
    @Override
    public float getBaseCooldownDuration() {
        return 0;
    }

    /**
     * AI 判断是否应该激活子系统的逻辑
     * 此处直接委托给 canActivate()，由于 canActivate() 恒返回 false，AI 永远不会尝试激活
     */
    @Override
    public boolean shouldActivateAI(float amount) {
        return canActivate();
    }

    /**
     * 每层充能的基础充能时间（秒）
     * 当无人机损失后，需要 10 秒来补充一个充能（即重新部署一架无人机）
     */
    @Override
    public float getBaseChargeRechargeDuration() {
        return 15f;
    }

    /**
     * 判断子系统当前是否可以被激活
     * 硬编码返回 false，意味着无论条件如何都无法激活（该子系统完全被动运作）
     */
    @Override
    public boolean canActivate() {
        return false;
    }

    /**
     * 返回在 UI 上显示的子系统名称
     */
    @Override
    public String getDisplayText() {
        return "'圆盾'防御云";
    }

    /**
     * 返回当前状态文本（如“就绪”、“冷却中”等）
     * 由于不需要任何状态提示，直接返回空字符串
     */
    @Override
    public String getStateText() {
        return "";
    }

    /**
     * 计算并返回 UI 进度条的填充比例 (0~1)
     * 此处用于显示无人机补充充能的进度
     */
    @Override
    public float getBarFill() {
        float fill = 0f;
        // 当已部署的无人机数量未满时，显示充能进度
        if (charges < calcMaxCharges()) {
            fill = chargeInterval.getElapsed() / chargeInterval.getIntervalDuration();
        }
        return fill;
    }

    /**
     * 获取最大充能层数
     * 这里设置为 2，表示最多可拥有 2 层充能，对应 2 架无人机
     */
    @Override
    public int getMaxCharges() {
        return 2;
    }

    /**
     * 获取同时存在的最大无人机数量
     */
    @Override
    public int getMaxDeployedDrones() {
        return 1;
    }

    /**
     * 激活此子系统时是否会消耗充能层数
     * 因为子系统不可激活，设为 false
     */
    @Override
    public boolean usesChargesOnActivate() {
        return false;
    }

    /**
     * 返回无人机的变体ID，决定生成哪种无人机
     * 此处指定为 "drone_pd_example"，对应数据文件中的一个 PD 无人机定义
     */
    @Override
    public @NotNull String getDroneVariant() {
        return "VIII_pd_swarm_wing";
    }

}