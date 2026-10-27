package data.scripts.weapons;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEngineLayers;
import com.fs.starfarer.api.combat.EveryFrameWeaponEffectPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;

import data.render.Moci_EngineRender;

public class MechLEngineAnimationController implements EveryFrameWeaponEffectPlugin {
    private static final Map<String, int[]> SEQUENCE = new HashMap<>();
    private boolean initialized = false;
    private String legDirection; // 腿部方向
    private WeaponAPI wingWeapon = null; // 关联的翼部武器
    private WeaponAPI shoulderWeapon = null; // 关联的肩部武器

    // 引擎控制参数
    private Color engineColor = new Color(43, 149, 255, 195); // 默认颜色，作为备用

    public static float ENGINE_LENGTH_MULT = 2.0f;  // 尾焰长度乘数
    public static float ENGINE_WIDTH_MULT = 4.0f;    // 尾焰宽度乘数
    public static float GLOW_SIZE_MULT = 1.0f;  // 光亮尺寸乘数

    private static int maxFrames = 1;
    static {
        SEQUENCE.put("LEG_S", new int[]{0, maxFrames});
        SEQUENCE.put("LEG_SL", new int[]{0, maxFrames});
        SEQUENCE.put("LEG_SR", new int[]{0, maxFrames});
        SEQUENCE.put("LEG_A", new int[]{0, maxFrames});
        SEQUENCE.put("LEG_D", new int[]{0, maxFrames});
        SEQUENCE.put("LEG_LW", new int[]{0, maxFrames});
        SEQUENCE.put("LEG_RW", new int[]{0, maxFrames});
    }

    String currentSequence = null;
    int current = 0;
    float timer = 0;

    @Override
    public void advance(float amount, CombatEngineAPI engine, WeaponAPI weapon) {
        if (engine == null || engine.isPaused() || weapon.getAnimation()==null) {
            return;
        }

        ShipAPI ship = weapon.getShip();
        // 初始化时根据武器槽位ID确定腿部方向和关联翼部
        if (!initialized) {
            String slotId = weapon.getSlot().getId();
            legDirection = slotId; // 直接使用完整槽位ID

            // 如果是翼部引擎，查找对应的翼部武器
            if (slotId.equals("LEG_LW") || slotId.equals("LEG_RW")) {
                String wingSlotId = slotId.equals("LEG_LW") ? "WING_L" : "WING_R";
                for (WeaponAPI w : weapon.getShip().getAllWeapons()) {
                    if (w.getSlot().getId().equals(wingSlotId)) {
                        wingWeapon = w;
                        break;
                    }
                }

                if (ship.getEngineController() != null && ship.getEngineController().getShipEngines() != null) {
                    // 不再寻找最近的引擎，而是直接使用武器自身作为引擎位置
                    ShipEngineControllerAPI.ShipEngineAPI dummyEngine = null;

                    // 创建一个虚拟引擎槽
                    for (ShipEngineControllerAPI.ShipEngineAPI engineSlot : ship.getEngineController().getShipEngines()) {
                        if (engineSlot != null) {
                            dummyEngine = engineSlot;
                            break; // 随便取一个引擎作为模板
                        }
                    }

                    SpriteAPI glowSprite = Global.getSettings().getSprite("fx", "engineflame32-orig");
                    SpriteAPI flameSprite = Global.getSettings().getSprite("fx", "engineglow32_crystal");
                    SpriteAPI cricleSprite = Global.getSettings().getSprite("fx", "MBSlowSprite");

                    // 确保找到有效引擎
                    if (dummyEngine != null) {
                        Moci_EngineRender render = new Moci_EngineRender(
                                weapon.getShip(),
                                dummyEngine,
                                weapon,
                                glowSprite,
                                flameSprite,
                                cricleSprite,
                                CombatEngineLayers.UNDER_SHIPS_LAYER,
                                CombatEngineLayers.UNDER_SHIPS_LAYER,
                                true
                        );
                        // 设置光亮参数
                        render.setGlowSizeMult(GLOW_SIZE_MULT);

                        // 从引擎获取颜色，如果有的话
                        Color engineColorToUse = engineColor; // 默认使用预设颜色
                        if (dummyEngine.getEngineColor() != null) {
                            // 使用引擎原始颜色，但保留透明度
                            engineColorToUse = new Color(
                                    dummyEngine.getEngineColor().getRed(),
                                    dummyEngine.getEngineColor().getGreen(),
                                    dummyEngine.getEngineColor().getBlue(),
                                    engineColor.getAlpha()
                            );
                        }

                        // 设置引擎颜色
                        render.setColorOverride(engineColorToUse);

                        // 设置尾焰尺寸参数
                        render.setLengthMult(ENGINE_LENGTH_MULT);
                        render.setWidthMult(ENGINE_WIDTH_MULT);

                        engine.addLayeredRenderingPlugin(render);
                    }
                }
            }
            // 如果是左右引擎，查找对应的肩膀武器
            if (slotId.equals("LEG_A") || slotId.equals("LEG_D")) {
                String wingSlotId = slotId.equals("LEG_A") ? "SHOULDER_L" : "SHOULDER_R";
                for (WeaponAPI w : weapon.getShip().getAllWeapons()) {
                    if (w.getSlot().getId().equals(wingSlotId)) {
                        shoulderWeapon = w;
                        break;
                    }
                }
            }

            if (weapon.getAnimation() != null) {
                maxFrames = weapon.getAnimation().getNumFrames() - 1;
                SEQUENCE.put(legDirection, new int[]{0, maxFrames});
            }
            initialized = true;
        }

        if (!ship.isAlive()) {
            weapon.getAnimation().pause();
            return;
        }

        // 如果是翼部引擎，跟随翼部武器角度
        if (wingWeapon != null) {
            weapon.setCurrAngle(wingWeapon.getCurrAngle());
        }
        // 如果是左右引擎，跟随翼部武器角度
        if (shoulderWeapon != null) {
            weapon.setCurrAngle(shoulderWeapon.getCurrAngle());
        }

        // 处理动画序列
        if(currentSequence == null) {
            weapon.getAnimation().pause();
        }

        if (currentSequence != null) {
            int[] sq = SEQUENCE.get(currentSequence);
            boolean shouldPlay = switch (legDirection) {
                case "LEG_S" ->
                        ship.getEngineController().isDecelerating() || ship.getEngineController().isAcceleratingBackwards();
                case "LEG_SL" ->
                        ship.getEngineController().isDecelerating() ||
                                ship.getEngineController().isAcceleratingBackwards() ||
                                ship.getEngineController().isTurningLeft();
                case "LEG_SR" ->
                        ship.getEngineController().isDecelerating() ||
                                ship.getEngineController().isAcceleratingBackwards() ||
                                ship.getEngineController().isTurningRight();
                case "LEG_D" ->
                        ship.getEngineController().isStrafingLeft() || ship.getEngineController().isTurningLeft();
                case "LEG_A" ->
                        ship.getEngineController().isStrafingRight() || ship.getEngineController().isTurningRight();
                // 修改：翼部引擎现在也响应转向和平移动作
                case  "LEG_RW"->
                        ship.getEngineController().isAccelerating() ||
                                ship.getEngineController().isTurningLeft() ||
                                ship.getEngineController().isStrafingLeft();
                case "LEG_LW" ->
                        ship.getEngineController().isAccelerating() ||
                                ship.getEngineController().isTurningRight() ||
                                ship.getEngineController().isStrafingRight();
                default -> false;
            };

            timer += amount * weapon.getAnimation().getFrameRate();
            while (timer >= 1) {
                timer -= 1;
                if (shouldPlay) {
                    current = Math.min(current + 1, sq[1]);
                } else {
                    // 直接回到第0帧
                    current = 0;
                    currentSequence = null;
                    timer = 0;
                    weapon.getAnimation().pause();
                    break;
                }
            }
        }
        else {
            // 根据腿部方向设置当前序列
            if (legDirection.equals("LEG_S") &&
                    (ship.getEngineController().isDecelerating() || ship.getEngineController().isAcceleratingBackwards())) {
                currentSequence = legDirection;
            }
            // 修改：翼部引擎现在也响应转向和平移动作
            else if (legDirection.equals("LEG_LW") &&
                    (ship.getEngineController().isAccelerating() ||
                            ship.getEngineController().isTurningLeft() ||
                            ship.getEngineController().isStrafingLeft())) {
                currentSequence = legDirection;
            }
            else if (legDirection.equals("LEG_RW") &&
                    (ship.getEngineController().isAccelerating() ||
                            ship.getEngineController().isTurningRight() ||
                            ship.getEngineController().isStrafingRight())) {
                currentSequence = legDirection;
            }
            else if (legDirection.equals("LEG_A") &&
                    (ship.getEngineController().isStrafingRight() || ship.getEngineController().isTurningRight())) {
                currentSequence = legDirection;
            }
            else if (legDirection.equals("LEG_D") &&
                    (ship.getEngineController().isStrafingLeft() || ship.getEngineController().isTurningLeft())) {
                currentSequence = legDirection;
            }

            if(currentSequence != null){
                int[] sq = SEQUENCE.get(currentSequence);
                current = sq[0];
                timer = 0;
                weapon.getAnimation().play();
            }
        }
        weapon.getAnimation().setFrame(current);
    }
}