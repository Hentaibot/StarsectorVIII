package data.scripts.weapons.VIII_XLretreating;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.OnFireEffectPlugin;
import com.fs.starfarer.api.combat.OnHitEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import org.apache.log4j.Logger;
import org.lwjgl.util.vector.Vector2f;

/**
 * 重力炮效果插件，同时实现开火、命中、每帧更新三个接口。
 * 主要功能：
 * 1. 在武器开火时，将投射物注册到全局渲染器 VIII_SingularityRenderer 中，并为其配置像素半径。
 * 2. 添加一个分层渲染插件 VIII_GravityCannonChargeGlow 来表现充能光晕，并将其附着到投射物上。
 * 3. 如果投射物是导弹，则将其设置为地雷模式（静止，引爆范围等）。
 * 4. 每帧更新时进行初始化记录（但未实现实际逻辑，可能用于调试或预留）。
 * 5. 命中效果目前为空，可能由其他插件处理。
 */
public class VIII_GravityCannonEffect implements OnFireEffectPlugin, OnHitEffectPlugin, EveryFrameWeaponEffectPlugin {

    /** 日志记录器，用于输出调试信息 */
    private static final Logger LOG = Global.getLogger(VIII_GravityCannonEffect.class);

    /** 当前武器对象，在 advance 中初始化记录 */
    private WeaponAPI weapon;

    /** 标记是否已完成初始化（用于日志输出一次） */
    private boolean initialized = false;

    /** 充能光晕实体（可能用于视觉特效，但目前未使用） */
    protected CombatEntityAPI chargeGlowEntity;

    /** 充能光晕插件实例，用于在 onFire 中添加 */
    protected VIII_GravityCannonChargeGlow chargeGlowPlugin;

    /**
     * 每帧更新方法，由 EveryFrameWeaponEffectPlugin 接口定义。
     * 当前实现仅用于记录初始化信息，并检查舰船状态（但无实际操作）。
     * @param amount 时间增量（秒）
     * @param engine 战斗引擎
     * @param weapon 当前武器
     */
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine != null && !engine.isPaused()) {
            if (!this.initialized) {
                this.weapon = weapon;
                this.initialized = true;
                LOG.info("[MOCI_SINGULARITY] effect initialized for weapon id=" + weapon.getId());
            }

            ShipAPI ship = weapon.getShip();
            // 以下代码原意可能是：如果舰船无效或已死，则执行某些操作，但目前为空
            if (ship != null && ship.isAlive()) {
                ship.isHulk();
            }// 可能用于清理资源，但未实现
        }
    }

    /**
     * 命中效果，由 OnHitEffectPlugin 接口定义。
     * 当前为空实现，可能由其他机制处理（如投射物自身的爆炸）。
     * @param projectile 造成伤害的弹丸
     * @param target 被击中的实体
     * @param point 命中点
     * @param shieldHit 是否命中护盾
     * @param damageResult 伤害结果
     * @param engine 战斗引擎
     */
    public void onHit(DamagingProjectileAPI projectile, CombatEntityAPI target, Vector2f point, boolean shieldHit,
                      ApplyDamageResultAPI damageResult, CombatEngineAPI engine) {
        // 空实现
    }

    /**
     * 开火效果，由 OnFireEffectPlugin 接口定义。
     * 在武器开火时调用，负责：
     * 1. 将投射物注册到全局渲染器 VIII_SingularityRenderer 中，并配置像素半径（视觉特效）。
     * 2. 添加一个分层渲染插件 VIII_GravityCannonChargeGlow 来表现充能光晕，并附着到投射物上。
     * 3. 如果投射物是导弹，将其设置为地雷模式（静止、不造成友军伤害、设置引爆范围、立即就绪）。
     * @param projectile 发射出的投射物
     * @param weapon 发射武器
     * @param engine 战斗引擎
     */
    public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        // 获取或创建全局的奇异渲染器（单例模式）
        VIII_SingularityRenderer renderer = VIII_SingularityRenderer.getOrCreate();
        if (renderer != null) {
            // 如果投射物尚未被渲染器跟踪，则开始跟踪
            if (!renderer.isTracked(projectile)) {
                renderer.trackProjectile(projectile);
            }
            // 配置投射物的像素半径（10.0 基本半径，2.0 可能是变化量或乘数）
            renderer.configurePixelRadius(projectile, 10.0F, 2.0F);
            LOG.info("[MOCI_SINGULARITY] projectile fired & tracked id=" + projectile.getProjectileSpecId());
        }



        // 创建充能光晕插件实例，并添加到引擎的分层渲染中
        VIII_GravityCannonChargeGlow glow = new VIII_GravityCannonChargeGlow(weapon);
        engine.addLayeredRenderingPlugin(glow);
        glow.attachToProjectile(projectile); // 将光晕附着到投射物上，使其跟随移动

        // 如果投射物是导弹，则将其设置为地雷模式
        if (projectile instanceof MissileAPI missile) {
            missile.setMine(true);                      // 标记为地雷（静止）
            missile.setNoMineFFConcerns(true);          // 不关心友军误伤（即不会伤害友军）
            missile.setMineExplosionRange((float) VIII_GravityCannonChargeGlow.MAX_ARC_RANGE + 50.0F); // 设置引爆范围（基于光晕最大半径）
            missile.setMinePrimed(true);                 // 使地雷立即就绪（可引爆）
            missile.setUntilMineExplosion(0.0F);         // 地雷引爆前等待时间设为0（即立即准备就绪）
        }
    }
}