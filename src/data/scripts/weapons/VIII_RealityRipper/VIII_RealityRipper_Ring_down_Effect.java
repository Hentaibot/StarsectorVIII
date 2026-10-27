package data.scripts.weapons.VIII_RealityRipper;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

public class VIII_RealityRipper_Ring_down_Effect implements EveryFrameWeaponEffectPlugin {

    /** 累计经过的时间（秒），用于控制动画循环 */
    private float elapsed = 0.0F;

    /**
     * 每帧调用一次，用于更新武器动画。
     * 动画共9帧，每1.5秒循环一次。
     * 游戏暂停、舰船残骸或散热时动画暂停（时间不累积，当前帧不变）。
     * 过载时正常播放。
     * @param amount 时间增量（秒）
     * @param engine 战斗引擎
     * @param weapon 所作用的武器
     */
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 游戏暂停时不更新动画（保持当前帧）
        if (engine.isPaused()) return;

        ShipAPI ship = weapon.getShip();

        // 舰船残骸或散热时，动画暂停（时间不累积，当前帧不变）
        if (ship.isHulk() || ship.getFluxTracker().isVenting()) {
            return;
        }

        // 正常播放动画（过载时也正常播放，无需特殊处理）
        elapsed += amount;

        float cycleTime = 0.4f;          // 循环周期（秒）
        int totalFrames = 9;             // 总帧数

        // 循环归零
        if (elapsed >= cycleTime) {
            elapsed -= cycleTime;
        }

        // 计算当前帧：用时间比例乘以总帧数，确保均匀分布
        int frame = (int) (elapsed / cycleTime * totalFrames);
        if (frame >= totalFrames) frame = totalFrames - 1; // 边界保护
        weapon.getAnimation().setFrame(frame);
    }
}