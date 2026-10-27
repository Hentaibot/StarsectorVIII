package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.OnFireEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import org.lwjgl.util.vector.Vector2f;

import java.util.List;

/**
 * 碎甲 MIRV 的开火效果。
 *
 * 原实现在 onFire 回调里直接"生成舰船 + 移除投射物"，这会在引擎正遍历投射物列表的过程中
 * 修改实体集合，存在迭代冲突风险。现在改为：
 *   1) onFire 只创建一个内部类实例（即每帧战斗插件），登记到战斗引擎；
 *   2) 真正的生成 / 移除动作延迟到下一帧由插件的 advance 执行，执行完毕后自我注销。
 *
 * 该内部类继承 BaseEveryFrameCombatPlugin —— 引擎中"每帧执行一次"的标准基类，
 * 通过 CombatEngineAPI.addPlugin() 注册，用 removePlugin() 注销。
 */
public class VIII_Fragmentation_MIRVonFireEffect implements OnFireEffectPlugin {

    /**
     * 开火回调：仅登记一个延迟执行的插件，不做任何实体增删。
     *
     * @param projectile 本次发射的投射物
     * @param weapon     开火的武器
     * @param engine     战斗引擎
     */
    @Override
    public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        // 为本次开火创建专属的一次性插件
        SpawnSwarmAction action = new SpawnSwarmAction(projectile, weapon);

        // 使用 Global.getCombatEngine() 注册到战斗引擎
        CombatEngineAPI liveEngine = Global.getCombatEngine();
        if (liveEngine != null) {
            liveEngine.addPlugin(action);
        } else if (engine != null) {
            // 兜底：极端情况下 Global 尚未就绪时，使用回调传入的引擎
            engine.addPlugin(action);
        }
    }

    /**
     * 内部类：负责在下一帧生成攻击群、移除原始投射物，然后自我注销。
     */
    private static class SpawnSwarmAction extends BaseEveryFrameCombatPlugin {

        /** 触发本次生成的投射物 */
        private final DamagingProjectileAPI projectile;
        /** 发射该投射物的武器（可为 null） */
        private final WeaponAPI weapon;
        /** 开火瞬间母舰的速度（开火回调时就取好，后续母舰移动不影响本次计算） */
        private final Vector2f parentVelocity;
        /** 防止被重复执行 */
        private boolean executed = false;

        /**
         * 构造方法：在开火回调中立即捕获所有需要的快照数据。
         *
         * @param projectile 本次发射的投射物
         * @param weapon     开火的武器
         */
        SpawnSwarmAction(DamagingProjectileAPI projectile, WeaponAPI weapon) {
            this.projectile = projectile;
            this.weapon = weapon;

            // 记录母舰开火瞬间的速度：投射物销毁后 weapon 可能不再有效，因此必须提前快照
            ShipAPI sourceShip = (weapon != null) ? weapon.getShip() : null;
            this.parentVelocity = (sourceShip != null)
                    ? new Vector2f(sourceShip.getVelocity())
                    : new Vector2f();
        }

        /**
         * 每帧调用：执行一次生成 + 移除，然后自我注销。
         *
         * @param amount 帧时间增量
         * @param events 输入事件列表（本插件不使用）
         */
        @Override
        public void advance(float amount, List<InputEventAPI> events) {
            if (executed) return;
            executed = true;

            CombatEngineAPI engine = Global.getCombatEngine();
            if (engine == null) return;

            try {
                // 投射物可能在这期间已被移除，先做一次存在性校验
                if (projectile != null && engine.isEntityInPlay(projectile)) {
                    spawnShip(projectile, parentVelocity, engine);
                }
            } finally {
                // 无论生成成功与否，都尝试移除原始投射物
                if (projectile != null && engine.isEntityInPlay(projectile)) {
                    engine.removeEntity(projectile);
                }

                // 自我注销：一次性动作，执行完就移除本插件
                engine.removePlugin(this);
            }
        }

        /**
         * 在投射物位置生成攻击群战机，并赋予"投射物速度 + 母舰速度"的初速。
         *
         * @param projectile     投射物（提供生成位置、朝向、阵营与速度）
         * @param parentVelocity 母舰开火瞬间的速度
         * @param engine         战斗引擎
         */
        private void spawnShip(DamagingProjectileAPI projectile, Vector2f parentVelocity, CombatEngineAPI engine) {
            // 获取对应阵营的舰队管理器
            CombatFleetManagerAPI fleetManager = engine.getFleetManager(projectile.getOwner());

            // 保存当前部署消息状态
            boolean wasSuppressed = fleetManager.isSuppressDeploymentMessages();

            // 禁止"XX 已部署"之类的提示消息
            fleetManager.setSuppressDeploymentMessages(true);

            try {
                // 在投射物位置生成指定的战机联队（使用第三参数为朝向的重载）
                ShipAPI ship = fleetManager.spawnShipOrWing(
                        "VIII_attack_swarm_wing",
                        projectile.getLocation(),
                        projectile.getFacing());

                // 设置碰撞类型为战斗机
                ship.setCollisionClass(CollisionClass.FIGHTER);

                // 计算初始速度（投射物速度 + 母舰速度）
                Vector2f vel = new Vector2f(projectile.getVelocity());
                Vector2f.add(vel, parentVelocity, vel);
                ship.getVelocity().set(vel);
            } finally {
                // 恢复原始部署消息状态
                fleetManager.setSuppressDeploymentMessages(wasSuppressed);
            }
        }
    }
}