package data.scripts.weapons;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.WeaponAPI;

public class Xolotl_missileAnimEffect implements EveryFrameWeaponEffectPlugin {
    // 当前动画帧
    int current = 0;
    // 充能动画序列的帧范围 [起始帧, 结束帧] (0到7帧)
    int[] chargeUpSequence = new int[]{0, 7};
    // 发射后/冷却动画序列的帧范围 [起始帧, 结束帧] (8到12帧)
    int[] chargeDownSequence = new int[]{8, 12};
    // 动画帧计时器，用于累积时间以驱动序列动画
    float timer = 0;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        // 基础检查：确保游戏和武器状态正常，且飞船存活
        if (engine == null || engine.isPaused() || weapon.getAnimation() == null || !weapon.getShip().isAlive()) {
            return;
        }

        // 默认暂停动画，脚本将手动控制每一帧的播放
        weapon.getAnimation().pause();

        // 判断武器是否处于"正在主动充能"状态
        // 条件：充能等级大于0，并且武器当前不处于冷却状态
        boolean chargeUp = weapon.getChargeLevel() > 0 && (weapon.getCooldownRemaining() == 0 || weapon.isInBurst());

        if (chargeUp) {
            // --- 武器正在主动充能 ---
            float chargeLevel = weapon.getChargeLevel();
            // 充能动画的目标结束帧 (即chargeUpSequence的第11帧)
            int to = chargeUpSequence[1];
            // 根据当前充能百分比，直接计算并设置对应的充能动画帧 (0-11帧)
            current = (int) (chargeLevel * to);
            // 重置动画计时器，因为充能动画是直接映射的，不是序列播放
            timer = 0;
        } else {
            // --- 武器不处于主动充能状态 (可能在发射后冷却，或从充能中断开始反向播放，或已空闲) ---
            if (current > 0) { // 如果当前帧不是0 (即不是完全空闲状态)
                // 累积时间，用于驱动发射后动画或反向充能动画的帧更新
                timer += amount * weapon.getAnimation().getFrameRate();

                // 当累积的时间足够播放一帧或多帧时
                while (timer >= 1) {
                    // 检查动画是否已播放完毕并应停止
                    // 条件1: 当前帧已经是发射后动画的最后一帧 (chargeDownSequence[1]，即17帧)
                    // 条件2: 当前帧已经是0帧 (空闲帧)
                    if (current == chargeDownSequence[1] || current == 0) {
                        current = 0; // 设置为最终的空闲帧
                        break;       // 结束动画播放
                    }

                    timer--; // 消耗一帧的时间

                    // 判断是播放发射后动画还是反向充能动画
                    if (current >= chargeUpSequence[1]) { // 如果当前帧大于等于充能满的帧 (11帧)
                        // 这意味着之前已满充能或正在播放发射后动画，继续播放发射后动画 (12帧到17帧)
                        current++;
                    } else {
                        // 当前帧小于11帧，意味着充能未满就被中断，需要反向播放充能动画至0帧
                        current--;
                    }
                }
            }
        }
        // 设置计算出的当前动画帧
        weapon.getAnimation().setFrame(current);
    }
}