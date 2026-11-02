package data.scripts.weapons.VIII_XLretreating;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.OnFireEffectPlugin;
import com.fs.starfarer.api.combat.OnHitEffectPlugin;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import org.apache.log4j.Logger;
import org.lwjgl.util.vector.Vector2f;

/**
 * 缩退炮（重力炮）武器插件，同时实现开火、命中、每帧更新三个接口。
 * <p>
 * 现在的职责被刻意缩小了：
 * <ol>
 *   <li><b>开火</b>：给刚发射的弹体挂上 {@link VIII_XLretreatingFlightEffect}，
 *       由它负责飞行期光晕与命中判定；</li>
 *   <li><b>飞行期</b>：不再注册 {@link VIII_SingularityRenderer}，也不再实例化
 *       {@link VIII_GravityCannonChargeGlow}，所以弹体飞行途中不会产生任何引力、伤害或黑洞；</li>
 *   <li><b>命中</b>：弹体 collisionClass 为 NONE，引擎不会回调 here，
 *       真正的命中由飞行效果插件自行判定后触发黑洞。</li>
 * </ol>
 */
public class VIII_GravityCannonEffect implements OnFireEffectPlugin, OnHitEffectPlugin, EveryFrameWeaponEffectPlugin {

    /** 日志记录器，用于输出调试信息 */
    private static final Logger LOG = Global.getLogger(VIII_GravityCannonEffect.class);

    /** 当前武器对象，在 advance 中初始化记录 */
    private WeaponAPI weapon;

    /** 标记是否已完成初始化（用于日志输出一次） */
    private boolean initialized = false;

    /**
     * 每帧更新：当前只做一次初始化日志，实际效果都在飞行效果插件里。
     */
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused() || initialized) {
            return;
        }
        this.weapon = weapon;
        this.initialized = true;
        LOG.info("[MOCI_SINGULARITY] effect initialized for weapon id=" + weapon.getId());
    }

    /**
     * 命中效果：引擎不会调用（collisionClass 为 NONE），保留空实现只是为了兼容旧配置。
     */
    public void onHit(DamagingProjectileAPI projectile, CombatEntityAPI target, Vector2f point, boolean shieldHit,
                      ApplyDamageResultAPI damageResult, CombatEngineAPI engine) {
        // 命中由 VIII_XLretreatingFlightEffect 自行判定
    }

    /**
     * 开火效果：给弹体挂上飞行效果插件。
     * 注意不再把弹体设成地雷（setMine），否则弹体会停在原地不会飞行。
     */
    public void onFire(DamagingProjectileAPI projectile, WeaponAPI weapon, CombatEngineAPI engine) {
        if (engine == null || !(projectile instanceof MissileAPI)) {
            return;
        }
        MissileAPI missile = (MissileAPI) projectile;
        if (missile.isMine()) {
            // 兼容旧逻辑留下的地雷标记
            missile.setMine(false);
        }
        engine.addPlugin(new VIII_XLretreatingFlightEffect(missile, engine));
    }

    public WeaponAPI getWeapon() {
        return weapon;
    }
}
