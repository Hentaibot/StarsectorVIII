// 包路径：远行星号游戏API实现中的战斗威胁包
package data.scripts.shipsystems;

// 导入可变舰船属性统计接口
import com.fs.starfarer.api.combat.*;
// 导入舰船接口
// 导入舰船命令枚举
// 导入舰船AI标志中的具体标志常量
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;
// 导入武器接口
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;

import java.awt.*;

/**
 * 提取协议系统脚本
 * 继承自基础能量鞭笞激活系统，实现“提取协议”系统：激活时大幅提升机动性，结束时强制排辐能并补充弹药
 */
public class VIII_ExtractionProtocolSystemScript extends BaseShipSystemScript {

    // 速度加成常量（每秒增加100像素/秒）
    public static float SPEED_BONUS = 100f;

    // 标记是否已经执行过排辐能（防止重复）
    protected boolean vented = false;


    /**
     * 应用系统效果的具体实现（由父类apply调用）
     * @param ship        舰船对象
     * @param stats       可变舰船属性统计
     * @param id          效果ID
     * @param state       系统状态（IN/ACTIVE/OUT）
     * @param effectLevel 效果强度（0~1）
     */
    protected void apply(ShipAPI ship, MutableShipStatsAPI stats, String id, State state, float effectLevel) {
        // 修改最大速度：增加 SPEED_BONUS * 效果强度
        stats.getMaxSpeed().modifyFlat(id, SPEED_BONUS * effectLevel);
        // 修改加速度：增加 2 * SPEED_BONUS * 效果强度
        stats.getAcceleration().modifyFlat(id, 2f * SPEED_BONUS * effectLevel);
        // 修改减速度：增加 2 * SPEED_BONUS * 效果强度
        stats.getDeceleration().modifyFlat(id, 2f * SPEED_BONUS * effectLevel);

        // 被注释掉的代码：幅能消散倍率修改（未启用）
        //stats.getFluxDissipation().modifyMult(id, 1f + (FLUX_DISSIPATION_MULT - 1f) * effectLevel);

        // 被注释掉的代码：引擎颜色渐变（未启用）
        //ship.getEngineController().fadeToOtherColor(this, color, new Color(0,0,0,0), effectLevel, 0.67f);
        // 延伸引擎火焰长度：参数分别为系统对象、额外长度、额外宽度、亮度倍率
        ship.getEngineController().extendFlame(ship.getSystem(), 0.01f * effectLevel, 0f * effectLevel, effectLevel);

        // 如果效果强度为0（系统未激活），则直接返回
        if (effectLevel <= 0f) return;

        // 设置AI标志：要求舰船后退，持续1秒
        ship.getAIFlags().setFlag(AIFlags.BACK_OFF, 1f);
        // 设置AI标志：禁止排辐能（因为系统会在结束时强制排辐）
        ship.getAIFlags().setFlag(AIFlags.DO_NOT_VENT, 1f);
        // 设置AI标志：后退的最小距离为10000像素（极大值，意味着尽可能远地后退）
        ship.getAIFlags().setFlag(AIFlags.BACK_OFF_MIN_RANGE, 10000f);
        // 取消AI标志：允许后退（取消禁止后退的标志）
        ship.getAIFlags().unsetFlag(AIFlags.DO_NOT_BACK_OFF);

        // 如果系统处于激活阶段（IN或ACTIVE）
        if (state == State.IN || state == State.ACTIVE) {
            vented = false;                                      // 重置排辐能标记
            makeAllGroupsAutofireOneFrame(ship);                // 强制所有武器组在一帧内切换到自动开火
        }
        // 如果系统处于关闭阶段（OUT）
        if (state == State.OUT) {
            // 如果尚未执行排辐能操作
            if (!vented) {
                // 命令舰船执行排辐能（强制散热）
                ship.giveCommand(ShipCommand.VENT_FLUX, null, 0);

                // 遍历舰船所有武器
                for (WeaponAPI w : ship.getAllWeapons()) {
                    if (w.isDecorative()) continue;             // 跳过装饰性武器（无实际功能）
                    if (w.getSlot().isSystemSlot()) continue;   // 跳过系统槽位的武器（内置系统）
                    // 如果武器使用弹药且当前弹药小于最大弹药
                    if (w.usesAmmo() && w.getAmmo() < w.getMaxAmmo()) {
                        w.setAmmo(w.getMaxAmmo());              // 将弹药补满
                    }
                }
                vented = true;                                   // 标记已执行排辐能，避免重复
            }
        }

        // 调用父类方法设置标准抖动效果（继承自BaseEnergyLashActivatedSystem）
        setStandardJitter(ship, state, effectLevel);
    }
    /**
     * 设置标准抖动效果（用于系统激活时的视觉反馈）
     * @param ship        舰船
     * @param state       系统状态
     * @param effectLevel 效果强度
     */
    protected void setStandardJitter(ShipAPI ship, State state, float effectLevel) {
        // 如果舰船是残骸，不设置抖动
        if (ship.isHulk()) return;

        // 计算抖动强度：基础0.5，加上效果强度的50%
        float jitterLevel = effectLevel;
        jitterLevel = 0.5f + 0.5f * jitterLevel;
        // 如果是系统关闭（OUT）阶段，抖动强度平方衰减
        if (state == State.OUT) {
            jitterLevel *= jitterLevel;
        }
        // 设置舰船抖动（参数：源对象、颜色、强度、次数、偏移范围、持续时间）
        ship.setJitter(this, new Color(130,155,145,255), jitterLevel, 1, 0f, 4f);
        // 禁用护盾抖动（只抖动舰体，不抖动护盾）
        ship.setJitterShields(false);
        // 启用圆形抖动模式
        ship.setCircularJitter(true);
    }

    /**
     * 获取系统在UI状态栏显示的文本（每帧调用，index决定显示哪一行）
     * @param index       行索引（0为第一行，1为第二行）
     * @param state       系统状态
     * @param effectLevel 效果强度
     * @return StatusData对象（包含文本和是否为关键信息），如果为null则不显示
     */
    public StatusData getStatusData(int index, State state, float effectLevel) {
        if (effectLevel <= 0f) return null;

        if (index == 0) {
            // 第一行显示：机动性提升（非关键信息）
            return new StatusData("机动性提高", false);
        } else if (index == 1) {
            // 第二行显示：具体速度加成数值
            return new StatusData("+" + (int)SPEED_BONUS + " 最高航速", false);
        }
        return null;
    }
    /**
     * 强制所有武器组在一帧内切换到自动开火模式
     * 用于系统激活时确保火力最大化
     * @param ship 舰船
     */
    protected void makeAllGroupsAutofireOneFrame(ShipAPI ship) {
        // 遍历舰船所有武器组的副本（避免并发修改）
        for (WeaponGroupAPI g : ship.getWeaponGroupsCopy()) {
            // 如果当前武器组未开启自动开火，则切换为自动开火
            if (!g.isAutofiring()) {
                g.toggleOn();  // 开启自动开火
            }
        }
        // 重置当前选中的武器组（让AI重新选择）
        ship.resetSelectedGroup();
        // 阻止以下命令在本帧生效（避免玩家输入干扰）
        ship.blockCommandForOneFrame(ShipCommand.TOGGLE_AUTOFIRE);  // 阻止切换自动开火命令
        ship.blockCommandForOneFrame(ShipCommand.SELECT_GROUP);     // 阻止选择武器组命令
        ship.blockCommandForOneFrame(ShipCommand.FIRE);             // 阻止手动开火命令

        // 获取AI标志中的机动目标（可能为舰船）
        Object test = ship.getAIFlags().getCustom(AIFlags.MANEUVER_TARGET);
        // 如果目标是一艘舰船，则设置舰船的当前攻击目标为该舰船
        if (test instanceof ShipAPI target) {
            ship.setShipTarget(target);
        }
    }


}