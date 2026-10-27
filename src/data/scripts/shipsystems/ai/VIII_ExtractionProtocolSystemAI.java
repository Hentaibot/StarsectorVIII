// 包路径：远行星号战斗系统AI实现包
package data.scripts.shipsystems.ai;

// 导入战斗接口
import com.fs.starfarer.api.combat.*;
import org.lwjgl.util.vector.Vector2f;

/**
 * 提取协议系统AI脚本
 * 负责在合适时机自动启用“提取协议”系统。该系统的效果是：激活时极大提升机动性并强制武器自动开火，
 * 结束时强制排辐能并补满所有武器弹药。AI会根据幅能水平、弹药余量以及结构完整度来判断是否需要激活系统。
 * 决策逻辑：
 * 1. 系统必须可用（冷却完毕且未激活）。
 * 2. 满足以下任一条件时激活：
 *    - 幅能超过75%（舰船即将过载）；
 *    - 幅能超过50%并且至少有一组武器的弹药即将耗尽（弹药不足当前最大值的一半）；
 *    - 结构值低于30%（严重受损）。
 */
public class VIII_ExtractionProtocolSystemAI implements ShipSystemAIScript {

    // 幅能阈值：当幅能超过此值时触发激活（0.75表示75%）
    private static final float FLUX_THRESHOLD_MAIN = 0.75f;

    // 幅能次阈值：当幅能超过此值且弹药不足时触发激活（0.5表示50%）
    private static final float FLUX_THRESHOLD_SECONDARY = 0.5f;

    // 弹药不足阈值：武器当前弹药低于最大弹药的一半时视为“弹药不足”
    private static final float AMMO_RATIO_THRESHOLD = 0.5f;

    // 结构阈值：舰船结构值低于此比例时触发激活（0.3表示30%）
    private static final float HULL_THRESHOLD = 0.3f;

    // 舰船对象，在init中初始化
    private ShipAPI ship;

    // 系统对象，在init中初始化
    private ShipSystemAPI system;

    /**
     * 初始化AI，由游戏引擎在创建时调用
     * @param ship  所属舰船
     * @param system 关联的系统
     * @param flags AI标志集合
     * @param engine 战斗引擎
     */
    @Override
    public void init(ShipAPI ship, ShipSystemAPI system, ShipwideAIFlags flags, CombatEngineAPI engine) {
        this.ship = ship;
        this.system = system;
    }


    /**
     * 每帧调用一次，用于决策是否激活系统
     * @param amount             帧时间（秒）
     * @param missileDangerDir   导弹危险方向向量（长度表示危险程度）
     * @param collisionDangerDir 碰撞危险方向向量
     * @param target             当前攻击目标（可能为null）
     */
    @Override
    public void advance(float amount, Vector2f missileDangerDir, Vector2f collisionDangerDir, ShipAPI target) {
        // 如果系统正在冷却中或已经激活，则不做任何事
        if (system.isCoolingDown() || system.isActive()) {
            return;
        }

        // 获取当前舰船状态
        // 幅能等级（0 ~ 1）
        float fluxLevel = ship.getFluxLevel();
        // 结构值等级（0 ~ 1），1表示满结构
        float hullLevel = ship.getHullLevel();

        // 检查幅能条件：幅能超过主阈值
        boolean fluxHigh = fluxLevel >= FLUX_THRESHOLD_MAIN;

        // 检查幅能 + 弹药条件：幅能超过次阈值且存在弹药不足的武器
        boolean fluxMediumAndAmmoLow = false;
        if (fluxLevel >= FLUX_THRESHOLD_SECONDARY) {
            // 遍历所有武器，检查是否有弹药不足的武器
            for (WeaponAPI w : ship.getAllWeapons()) {
                // 跳过装饰性武器和系统槽武器
                if (w.isDecorative() || w.getSlot().isSystemSlot()) continue;
                // 如果武器使用弹药，且当前弹药低于最大弹药的一半
                if (w.usesAmmo() && w.getAmmo() < w.getMaxAmmo() * AMMO_RATIO_THRESHOLD) {
                    fluxMediumAndAmmoLow = true;
                    break; // 找到一项即可
                }
            }
        }

        // 结构条件：值低于阈值
        boolean hullLow = hullLevel <= HULL_THRESHOLD;

        // 如果满足任一条件，激活系统
        if (fluxHigh || fluxMediumAndAmmoLow || hullLow) {
            // 使用系统命令激活“提取协议”
            ship.useSystem();
            // 也可以使用：ship.giveCommand(ShipCommand.USE_SYSTEM, null, 0);
        }

        // 注意：系统激活后，VIII_ExtractionProtocolSystemScript会接管后续的AI标志设置，
        // 包括强制后退、禁止排辐等，因此这里无需额外处理。
    }
}