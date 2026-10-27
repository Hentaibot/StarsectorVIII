package data.scripts.weapons;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import data.scripts.shipsystems.VIII_OilSelfDestruct;

/**
 * 挂在 VIII_oil 模块上的"阵亡补爆"监听器。
 * <p>
 * 需求：油罐被摧毁时，如果自爆系统（{@link VIII_OilSelfDestruct}）的爆炸还没触发过，
 * 就补触发一次同样的爆炸。
 * <p>
 * 为什么用 {@link AdvanceableListener}：引擎在 {@code Ship.advance} 里每帧推进监听器
 * （反编译见 com.fs.starfarer.combat.entities.Ship 第 1830-1831 行，且在残骸分支之前），
 * 所以舰船被打爆变成残骸之后监听器仍会继续被调用，能稳定捕捉到"刚阵亡"的那一帧。
 * 若改用船插 {@code advanceInCombat}，一旦进入残骸流程就不再可靠。
 * <p>
 * 去重：真正的去重标记在 {@link VIII_OilSelfDestruct#DETONATED_KEY} 上，
 * {@link VIII_OilSelfDestruct#detonate(ShipAPI, CombatEngineAPI)} 内部会检查，
 * 所以"自爆系统先炸 → 随后自毁"不会炸第二次。
 * <p>
 * 由 {@link VIII_FireOilEffect} 在每帧检查母舰子模块时挂上（同类型监听器只挂一个）。
 */
public class VIII_OilDeathListener implements AdvanceableListener {

    /** 监听的目标模块 */
    private final ShipAPI module;

    /** 本监听器是否已经处理过（处理完就摘掉自己，避免每帧空转） */
    private boolean triggered = false;

    public VIII_OilDeathListener(ShipAPI module) {
        this.module = module;
    }

    /**
     * 每帧检查模块是否还活着：一旦阵亡/变成残骸，就补一次爆炸并摘掉监听器。
     */
    @Override
    public void advance(float amount) {
        if (triggered || module == null) {
            return;
        }
        // 还活着就继续等
        if (module.isAlive() && !module.isHulk()) {
            return;
        }
        triggered = true;
        module.removeListener(this);

        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) {
            return;
        }
        // detonate 内部会检查"是否已经炸过"，所以这里不用再判一次
        VIII_OilSelfDestruct.detonate(module, engine);
    }
}
