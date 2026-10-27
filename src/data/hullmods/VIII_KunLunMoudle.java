package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 昆仑舰模块化船体插件。
 * 该插件内置在昆仑舰及其子模块中，负责协调母舰与子模块之间的行为同步、引擎性能计算、以及不兼容插件移除。
 * 主要功能：
 * - 子模块跟随母舰移动（加速、转向、后退等指令同步）。
 * - 引擎熄火状态同步（母舰熄火时子模块也熄火）。
 * - 时间流速同步（使子模块的时间倍率与母舰一致）。
 * - 目标锁定同步（将母舰的锁定目标传递给子模块，尤其是航母的舰载机目标）。
 * - 战斗机召回指令同步。
 * - 根据所有工作引擎（包括子模块）动态调整母舰的加速度、转向等性能。
 * - 移除子模块上不允许安装的插件，并添加不兼容警告插件。
 */
public class VIII_KunLunMoudle extends BaseHullMod {

    /** 用于控制不兼容警告插件显示时间的计数器 */
    private float check = 0.0F;

    /** 不兼容警告插件的ID */
    private static final String ERROR = "IncompatibleHullmodWarning";

    /** 被禁用的全能设计插件（OMNI）集合 */
    private static final Set<String> BLOCKED_OMNI = new HashSet<>();

    /** 被禁用的其他插件集合（对所有阵营有效） */
    private static final Set<String> BLOCKED_OTHER = new HashSet<>();

    /** 被禁用的其他插件集合（仅对玩家有效） */
    private static final Set<String> BLOCKED_OTHER_PLAYER_ONLY = new HashSet<>();

    /** 默认的母舰原始质量（用于计算每个引擎的推力） */
    private static final float DEFAULT_ORIGINAL_MASS = 9000.0F;

    /** 默认的原始引擎数量（用于计算每个引擎的推力） */
    private static final int DEFAULT_ORIGINAL_ENGINES = 7;

    // ========== 子模块同步方法 ==========

    /**
     * 将母舰的指令同步到子模块。
     * 包括移动指令（加速、转向、后退等）、引擎熄火状态、时间倍率、目标锁定和战斗机召回。
     * @param child  子模块舰船
     * @param parent 母舰舰船
     */
    private static void advanceChild(ShipAPI child, ShipAPI parent) {
        ShipEngineControllerAPI ec = parent.getEngineController();
        if (ec == null) return;

        // 母舰存活时，将其移动指令传递给子模块
        if (parent.isAlive()) {
            if (ec.isAccelerating()) child.giveCommand(ShipCommand.ACCELERATE, null, 0);
            if (ec.isAcceleratingBackwards()) child.giveCommand(ShipCommand.ACCELERATE_BACKWARDS, null, 0);
            if (ec.isDecelerating()) child.giveCommand(ShipCommand.DECELERATE, null, 0);
            if (ec.isStrafingLeft()) child.giveCommand(ShipCommand.STRAFE_LEFT, null, 0);
            if (ec.isStrafingRight()) child.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0);
            if (ec.isTurningLeft()) child.giveCommand(ShipCommand.TURN_LEFT, null, 0);
            if (ec.isTurningRight()) child.giveCommand(ShipCommand.TURN_RIGHT, null, 0);
        }

        // 引擎熄火同步：如果母舰引擎熄火而子模块尚未熄火，则强制子模块熄火
        ShipEngineControllerAPI cec = child.getEngineController();
        if (cec != null && (ec.isFlamingOut() || ec.isFlamedOut()) && !cec.isFlamingOut() && !cec.isFlamedOut()) {
            cec.forceFlameout(true);
        }

        // 如果子模块有机库（航母模块），同步召回战斗机指令和战斗机目标
        if (child.hasLaunchBays()) {
            // 召回指令同步
            if (child.isPullBackFighters() ^ parent.isPullBackFighters()) {
                child.giveCommand(ShipCommand.PULL_BACK_FIGHTERS, null, 0);
            }

            // 战斗机目标同步
            if (child.getAIFlags() != null) {
                Object target;
                if (Global.getCombatEngine().getPlayerShip() == parent || parent.getAIFlags() == null) {
                    target = parent.getShipTarget(); // 玩家控制或母舰无AI时，使用母舰直接目标
                } else {
                    target = parent.getAIFlags().getCustom(AIFlags.CARRIER_FIGHTER_TARGET); // 使用AI指定的战斗机目标
                }
                if (target != null) {
                    child.getAIFlags().setFlag(AIFlags.CARRIER_FIGHTER_TARGET, 1.0F, target);
                }
            }
        }

        // 时间流速同步：使子模块的时间倍率与母舰一致
        MutableShipStatsAPI parentStats = parent.getMutableStats();
        MutableShipStatsAPI childStats = child.getMutableStats();
        if (parentStats != null && childStats != null) {
            float parentTimeMult = parentStats.getTimeMult().getModifiedValue();
            if (parent.isAlive()) {
                childStats.getTimeMult().modifyMult("VIII_KunLunMoudle_TimeSync", parentTimeMult);
            }
        }

        // 目标锁定同步：如果母舰有锁定目标，子模块也锁定同一目标
        if (parent.getShipTarget() != null) {
            child.setShipTarget(parent.getShipTarget());
        }

        // 以下代码与上面重复，可能是合并时的冗余，但保留原样
        if (child.hasLaunchBays()) {
            if (child.isPullBackFighters() ^ parent.isPullBackFighters()) {
                child.giveCommand(ShipCommand.PULL_BACK_FIGHTERS, null, 0);
            }
            if (child.getAIFlags() != null) {
                if (((Global.getCombatEngine().getPlayerShip() == parent) || (parent.getAIFlags() == null))
                        && (parent.getShipTarget() != null)) {
                    child.getAIFlags().setFlag(AIFlags.CARRIER_FIGHTER_TARGET, 1f, parent.getShipTarget());
                } else if ((parent.getAIFlags() != null)
                        && parent.getAIFlags().hasFlag(AIFlags.CARRIER_FIGHTER_TARGET)
                        && (parent.getAIFlags().getCustom(AIFlags.CARRIER_FIGHTER_TARGET) != null)) {
                    child.getAIFlags().setFlag(AIFlags.CARRIER_FIGHTER_TARGET, 1f,
                            parent.getAIFlags().getCustom(AIFlags.CARRIER_FIGHTER_TARGET));
                }
            }
        }
    }

//    // ========== 母舰引擎性能计算 ==========
//
//    /**
//     * 根据所有子模块和母舰自身的工作引擎，动态调整母舰的加速度、转向等性能。
//     * 基本原理：计算所有工作引擎的总推力，除以母舰总质量，得到引擎性能倍率，然后应用给母舰的移动属性。
//     * @param parent   母舰舰船
//     * @param children 子模块列表
//     */
//    private static void advanceParent(ShipAPI parent, List<ShipAPI> children) {
//        ShipEngineControllerAPI ec = parent.getEngineController();
//        if (ec == null) return;
//
//        String hullId = parent.getHullSpec().getBaseHullId();
//        float thrustPerEngine = calculateThrustPerEngine(hullId); // 计算每个引擎的基础推力
//        float totalWorkingEngines = ec.getShipEngines().size();   // 母舰自身引擎数量
//
//        // 遍历所有子模块，累加它们的工作引擎贡献
//        for (ShipAPI child : children) {
//            if (child.getParentStation() == parent && child.getStationSlot() != null && child.isAlive()) {
//                ShipEngineControllerAPI cec = child.getEngineController();
//                if (cec == null) continue;
//
//                float contribution = 0.0F;
//                for (ShipEngineControllerAPI.ShipEngineAPI ce : cec.getShipEngines()) {
//                    if (ce.isActive() && !ce.isDisabled() && !ce.isPermanentlyDisabled() && !ce.isSystemActivated()) {
//                        contribution += ce.getContribution(); // 每个引擎的贡献度（通常为1）
//                    }
//                }
//                totalWorkingEngines += cec.getShipEngines().size() * contribution; // 子模块引擎数量 * 贡献度
//            }
//        }
//
//        // 计算总推力 = 引擎数量 * 每引擎推力
//        float thrust = totalWorkingEngines * thrustPerEngine;
//        // 引擎性能 = 总推力 / 总质量（母舰+子模块质量）
//        float enginePerformance = thrust / Math.max(1.0F, parent.getMassWithModules());
//
//        // 应用性能倍率到母舰的移动属性
//        MutableShipStatsAPI stats = parent.getMutableStats();
//        if (stats != null) {
//            stats.getAcceleration().modifyMult("VIII_KunLunMoudle", enginePerformance);
//            stats.getDeceleration().modifyMult("VIII_KunLunMoudle", enginePerformance);
//            stats.getTurnAcceleration().modifyMult("VIII_KunLunMoudle", enginePerformance);
//            stats.getMaxTurnRate().modifyMult("VIII_KunLunMoudle", enginePerformance);
//            stats.getMaxSpeed().modifyMult("VIII_KunLunMoudle", enginePerformance);
//            stats.getZeroFluxSpeedBoost().modifyMult("VIII_KunLunMoudle", enginePerformance);
//        }
//    }

    /**
     * 根据母舰船体ID计算每个引擎的基础推力。
     * 对于昆仑舰，使用默认质量和引擎数计算。
     * @param hullId 母舰船体ID
     * @return 每个引擎的推力值
     */
    private static float calculateThrustPerEngine(String hullId) {
        if (hullId.equals("VIII_KunLun")) {
            return DEFAULT_ORIGINAL_MASS / DEFAULT_ORIGINAL_ENGINES; // 原始质量 / 原始引擎数
        }
        return 9000.0F; // 默认值，可根据实际值调整
    }

    // ========== 父类方法重写 ==========

    /**
     * 每帧在战斗中调用，负责同步子模块和计算母舰性能。
     * @param ship   当前舰船（可能是母舰或子模块）
     * @param amount 时间增量
     */
    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        // 如果当前舰船是子模块（有母舰），则同步子模块
        ShipAPI parent = ship.getParentStation();
        if (parent != null) advanceChild(ship, parent);

        // 如果当前舰船有子模块，则计算母舰性能
//        List<ShipAPI> children = ship.getChildModulesCopy();
//        if (children != null && !children.isEmpty()) {
//            advanceParent(ship, children);
//        }
    }

    /**
     * 在舰船创建后应用插件效果。
     * 主要进行不兼容插件检查，移除被禁止的插件并添加警告插件。
     * @param ship 舰船对象
     * @param id   插件ID（此实例中未使用）
     */
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        ship.getMutableStats(); // 可能用于触发某些初始化，返回值未使用

        // 如果 check > 0，则递减，当 check 变为 1 时移除警告插件
        if (check > 0.0F && --check < 1.0F) {
            ship.getVariant().removeMod(ERROR);
        }

        String hullId = ship.getHullSpec().getBaseHullId();
        // 如果是子模块，则进行不兼容插件检查
        if (isModule(hullId)) {
            checkAndRemoveBlockedMods(ship, BLOCKED_OMNI);
            checkAndRemoveBlockedMods(ship, BLOCKED_OTHER);
            checkAndRemoveBlockedMods(ship, BLOCKED_OTHER_PLAYER_ONLY);
        }
    }

    // ========== 辅助方法 ==========

    /**
     * 判断给定的船体ID是否为昆仑舰的子模块。
     * @param hullId 船体ID
     * @return true 如果是子模块
     */
    private boolean isModule(String hullId) {
        return hullId.equals("VIII_KunLun_LeftengineMoudle") ||
                hullId.equals("VIII_KunLun_RightengineMoudle") ||
                hullId.equals("VIII_KunLun_weaponLplatfrom") ||
                hullId.equals("VIII_KunLun_weaponRplatfrom") ||
                hullId.equals("VIII_Kunlun_Flank_Lplatfrom") ||
                hullId.equals("VIII_Kunlun_Flank_Rplatfrom") ;
    }

    /**
     * 检查并移除子模块上的被禁用插件。
     * 如果发现被禁用插件，移除之，并添加不兼容警告插件，同时设置 check 计数器为 3，使警告显示一段时间。
     * @param ship        舰船对象
     * @param blockedMods 被禁用的插件ID集合
     */
    private void checkAndRemoveBlockedMods(ShipAPI ship, @NotNull Set<String> blockedMods) {
        for (String mod : blockedMods) {
            if (ship.getVariant().hasHullMod(mod)) {
                ship.getVariant().removeMod(mod);
                ship.getVariant().addMod(ERROR);
                check = 3.0F; // 设置计数器，使警告插件显示3帧
            }
        }
    }

    // ========== 静态初始化块：填充禁用插件集合 ==========
    static {
        // 被禁用的全能设计插件（可能特指某些插件）
        BLOCKED_OMNI.add("high_scatter_amp");

        // 被禁用的其他插件（对所有阵营）
        BLOCKED_OTHER.add("shield_shunt");
        BLOCKED_OTHER.add("unstable_injector");
        BLOCKED_OTHER.add("safetyoverrides");
        BLOCKED_OTHER.add("recovery_shuttles");
        BLOCKED_OTHER.add("additional_berthing");
        BLOCKED_OTHER.add("augmentedengines");
        BLOCKED_OTHER.add("auxiliary_fuel_tanks");
        BLOCKED_OTHER.add("efficiency_overhaul");
        BLOCKED_OTHER.add("expanded_cargo_holds");
        BLOCKED_OTHER.add("hiressensors");
        BLOCKED_OTHER.add("militarized_subsystems");

        // 被禁用的其他插件（仅对玩家有效）
        BLOCKED_OTHER_PLAYER_ONLY.add("converted_hangar");
        BLOCKED_OTHER_PLAYER_ONLY.add("TSC_converted_hangar");
    }
}