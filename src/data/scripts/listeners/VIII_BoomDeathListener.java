package data.scripts.listeners;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.listeners.AdvanceableListener;
import data.scripts.shipsystems.VIII_BoomSelfDestruct;

/**
 * 挂在 VIII_BOOM 上的"阵亡补爆"监听器。
 * <p>
 * 需求：爆破单元被摧毁时，如果自爆系统（{@link VIII_BoomSelfDestruct}）的爆炸还没触发过，
 * 就补触发一次同样的爆炸（橙色爆片 + 同样的伤害/扩散波）。
 * <p>
 * 为什么用 {@link AdvanceableListener}：引擎在 {@code Ship.advance} 里每帧推进监听器
 * （反编译见 com.fs.starfarer.combat.entities.Ship 第 1830-1831 行，且在残骸分支之前），
 * 所以舰船被打爆变成残骸之后监听器仍会继续被调用，能稳定捕捉到"刚阵亡"的那一帧；
 * 若改用船插的 {@code advanceInCombat}，一旦进入残骸流程就不再可靠。
 * <p>
 * 去重：真正的去重标记是 {@link VIII_BoomSelfDestruct#DETONATED_KEY}
 * （"VIII_boom_blast_detonated"），{@link VIII_BoomSelfDestruct#detonate(ShipAPI, CombatEngineAPI)}
 * 内部会检查，所以"自爆系统先炸 → 随后自毁"不会再炸第二次。
 * <p>
 * 由 {@link data.hullmods.VIII_BoomAIHullmod} 在舰船创建/入战时装上（同类型只挂一个）。
 */
public class VIII_BoomDeathListener implements AdvanceableListener {

    /** 监听的目标舰船 */
    private final ShipAPI ship;

    /** 本监听器是否已经处理过（处理完就摘掉自己，避免每帧空转） */
    private boolean triggered = false;

    public VIII_BoomDeathListener(ShipAPI ship) {
        this.ship = ship;
    }

    /**
     * 每帧检查舰船是否还活着：一旦阵亡/变成残骸，就补一次爆炸并摘掉监听器。
     */
    @Override
    public void advance(float amount) {
        if (triggered || ship == null) {
            return;
        }
        // 还活着就继续等
        if (ship.isAlive() && !ship.isHulk()) {
            return;
        }
        triggered = true;
        ship.removeListener(this);

        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) {
            return;
        }
        // 主菜单背景战斗里不补爆：那里的爆炸会反复触发，造成明显卡顿
        if (!VIII_BoomSelfDestruct.isRealBattle(engine)) {
            return;
        }
        // detonate 内部会检查"是否已经炸过"，所以这里不用再判一次
        VIII_BoomSelfDestruct.detonate(ship, engine);
    }
}
