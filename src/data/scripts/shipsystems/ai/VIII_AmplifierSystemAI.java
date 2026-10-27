// 包路径：远行星号战斗系统AI实现包
package data.scripts.shipsystems.ai;

// 导入战斗接口
import com.fs.starfarer.api.combat.*;
// 导入LWJGL二维向量类（虽然本AI未直接使用，但接口需要）
import org.lwjgl.util.vector.Vector2f;

/**
 * 放大器系统AI脚本（对应VIII_AmplifierSystem）
 * 负责在合适时机自动启用“放大器”系统。该系统效果：激活时碎片群规模×2、重生速率×2、所有武器射程×2。
 * AI决策逻辑：
 * 1. 系统必须可用（冷却完毕且未激活）。
 * 2. 满足以下所有条件时激活：
 *    - 存在敌方舰船（至少有非友方舰船在战斗中）；
 *    - 舰船当前幅能水平低于阈值（70%），确保有足够资源承受可能的幅能消耗；
 *    - 舰船当前有攻击目标（target不为null）或处于战斗状态（幅能>10%表示正在交战）。
 * 3. 避免在撤退或缺弹时激活，但本系统不涉及弹药。
 */
public class VIII_AmplifierSystemAI implements ShipSystemAIScript {

    // 幅能阈值：超过此值则不激活系统，防止因幅能过高导致风险
    private static final float FLUX_THRESHOLD = 0.7f;

    // 最小幅能阈值：当幅能低于此值且没有目标时，认为未进入战斗，不激活
    private static final float MIN_FLUX_FOR_COMBAT = 0.1f;

    // 舰船对象，在init中保存
    private ShipAPI ship;

    // 系统对象，在init中保存
    private ShipSystemAPI system;

    // 战斗引擎对象，在init中保存，用于查询战场信息
    private CombatEngineAPI engine;

    /**
     * 初始化AI，由游戏引擎在创建时调用
     * @param ship    所属舰船
     * @param system  关联的系统
     * @param flags   AI标志集合
     * @param engine  战斗引擎
     */
    @Override
    public void init(ShipAPI ship, ShipSystemAPI system, ShipwideAIFlags flags, CombatEngineAPI engine) {
        this.ship = ship;
        this.system = system;
        this.engine = engine;
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
        // 如果系统正在冷却中或已经激活，不做任何事
        if (system.isCoolingDown() || system.isActive()) {
            return;
        }

        // 获取舰船当前状态
        float fluxLevel = ship.getFluxLevel();

        // 幅能条件：不能太高以免危险，但也不能太低（<10%且无目标表示未交战）
        if (fluxLevel > FLUX_THRESHOLD) {
            return;
        }
        // 如果幅能极低且没有明确目标，认为未进入战斗，不激活
        if (fluxLevel < MIN_FLUX_FOR_COMBAT && target == null) {
            return;
        }

        // 检查是否有敌方舰船在战斗中（排除已经残骸或被移除的）
        boolean enemyPresent = false;
        for (ShipAPI other : engine.getShips()) {
            // 忽略残骸、已移除或同一阵营的舰船
            if (other.isHulk() || other.isExpired() || other.getOwner() == ship.getOwner()) {
                continue;
            }
            // 找到一个非友方舰船即可
            enemyPresent = true;
            break;
        }

        // 如果没有敌人，不激活系统
        if (!enemyPresent) {
            return;
        }

        // 所有条件满足，激活系统以增强碎片群和武器射程
        ship.useSystem();
        // 也可使用：ship.giveCommand(ShipCommand.USE_SYSTEM, null, 0);
    }
}