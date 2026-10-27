package data.hullmods;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CargoStackAPI;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 武器切换系统：用于在特定舰船上切换手臂武器的船体插件。
 * 该类管理多种模式（如光束剑、光束枪等）的轮替，负责武器槽替换和仓库清理。
 * 每个具体的插件ID对应一个配置（hullModComfig），定义了该模式的行为。
 */
public class VIII_WeaponSwitch2 extends BaseHullMod {

    /**
     * 静态配置映射：键为插件ID，值为对应的配置对象。
     * 这里定义了多个武器切换模式，每个模式对应一个可安装的船体插件。
     * 这些插件通常通过 VIII_WeaponSwitch 管理器进行自动轮替。
     */
    public static final Map<String, hullModComfig> HULL_MOD_CONFIGS = new HashMap<>();

    static {
        // ========== 模式组1：昆仑舰（VIII_KunLun）的左臂武器 ==========
        // 模式1-1：左臂裂谷级联光束剑（VIII_XLriftcascade_L_Mode）
        hullModComfig config = new hullModComfig("VIII_XLriftcascade_L");
        HULL_MOD_CONFIGS.put("VIII_XLriftcascade_L", config);
        config.hulls.add("VIII_KunLun");                      // 仅限昆仑舰
        config.replaceSlots.put("XL_L", "VIII_XLriftcascade"); // 槽位 XL_L 安装裂谷级联武器
        config.nextHullMod = "VIII_XLvpdriver_L";        // 下一个模式是左臂V型驱动枪
        config.sourceHullMod = "VIII_XLriftcascade_L";   // 源插件ID（自身）
        config.displaySortOrder = 3000;                       // 显示顺序
        config.displayCategoryIndex = 3;                      // 分类：舰船系统
        config.managedWeaponPrefixes.add("VIII_XLriftcascade"); // 管理前缀，用于清理仓库

        // 模式1-2：左臂V型驱动枪（VIII_XLvpdriver_L_Mode）
        config = new hullModComfig("VIII_XLvpdriver_L");
        HULL_MOD_CONFIGS.put("VIII_XLvpdriver_L", config);
        config.hulls.add("VIII_KunLun");
        config.replaceSlots.put("XL_L", "VIII_XLvpdriver");
        config.nextHullMod = "VIII_XLretreating_L";      // 下一个模式是左臂退行炮
        config.sourceHullMod = "VIII_XLriftcascade_L";
        config.displaySortOrder = 3100;
        config.displayCategoryIndex = 3;
        config.managedWeaponPrefixes.add("VIII_XLvpdriver");

        // 模式1-3：左臂退行炮（VIII_XLretreating_L_Mode）
        config = new hullModComfig("VIII_XLretreating_L");
        HULL_MOD_CONFIGS.put("VIII_XLretreating_L", config);
        config.hulls.add("VIII_KunLun");
        config.replaceSlots.put("XL_L", "VIII_XLretreating");
        config.nextHullMod = "VIII_XLriftcascade_L";     // 下一个模式回到裂谷级联（形成循环）
        config.sourceHullMod = "VIII_XLriftcascade_L";
        config.displaySortOrder = 3200;
        config.displayCategoryIndex = 3;
        config.managedWeaponPrefixes.add("VIII_XLretreating");

        // 模式2-1：右臂裂谷级联光束剑（VIII_XLriftcascade_L_Mode）
        config = new hullModComfig("VIII_XLriftcascade_R");
        HULL_MOD_CONFIGS.put("VIII_XLriftcascade_R", config);
        config.hulls.add("VIII_KunLun");                      // 仅限昆仑舰
        config.replaceSlots.put("XL_R", "VIII_XLriftcascade"); // 槽位 XL_L 安装裂谷级联武器
        config.nextHullMod = "VIII_XLvpdriver_R";        // 下一个模式是左臂V型驱动枪
        config.sourceHullMod = "VIII_XLriftcascade_R";   // 源插件ID（自身）
        config.displaySortOrder = 3300;                       // 显示顺序
        config.displayCategoryIndex = 3;                      // 分类：舰船系统
        config.managedWeaponPrefixes.add("VIII_XLriftcascade"); // 管理前缀，用于清理仓库

        // 模式2-2：右臂V型驱动枪（VIII_XLvpdriver_L_Mode）
        config = new hullModComfig("VIII_XLvpdriver_R");
        HULL_MOD_CONFIGS.put("VIII_XLvpdriver_R", config);
        config.hulls.add("VIII_KunLun");
        config.replaceSlots.put("XL_R", "VIII_XLvpdriver");
        config.nextHullMod = "VIII_XLretreating_R";      // 下一个模式是左臂退行炮
        config.sourceHullMod = "VIII_XLriftcascade_R";
        config.displaySortOrder = 3400;
        config.displayCategoryIndex = 3;
        config.managedWeaponPrefixes.add("VIII_XLvpdriver");

        // 模式3-3：右臂退行炮（VIII_XLretreating_L_Mode）
        config = new hullModComfig("VIII_XLretreating_R");
        HULL_MOD_CONFIGS.put("VIII_XLretreating_R", config);
        config.hulls.add("VIII_KunLun");
        config.replaceSlots.put("XL_R", "VIII_XLretreating");
        config.nextHullMod = "VIII_XLriftcascade_R";     // 下一个模式回到裂谷级联（形成循环）
        config.sourceHullMod = "VIII_XLriftcascade_R";
        config.displaySortOrder = 3500;
        config.displayCategoryIndex = 3;
        config.managedWeaponPrefixes.add("VIII_XLretreating");

        // ========== 模式组2：Xolotl舰（VIII_xolotl）的主武器1 ==========
        // 模式2-1：主炮1 乙太停止器B型（VIII_euthystopos_b_Mode1）
        config = new hullModComfig("VIII_euthystopos_b_Mode1");
        HULL_MOD_CONFIGS.put("VIII_euthystopos_b_Mode1", config);
        config.hulls.add("VIII_xolotl");                       // 仅限Xolotl舰
        config.replaceSlots.put("MAINGUN_1", "VIII_euthystopos_b"); // 槽位MAINGUN_1安装B型
        config.nextHullMod = "VIII_euthystopos_e_Mode1";       // 下一个模式是E型
        config.sourceHullMod = "VIII_euthystopos_b_Mode1";
        config.displaySortOrder = 3600;
        config.displayCategoryIndex = 2;
        config.managedWeaponPrefixes.add("VIII_xolotl_MainGun1"); // 管理主炮1相关的武器前缀

        // 模式2-2：主炮1 乙太停止器E型（VIII_euthystopos_e_Mode1）
        config = new hullModComfig("VIII_euthystopos_e_Mode1");
        HULL_MOD_CONFIGS.put("VIII_euthystopos_e_Mode1", config);
        config.hulls.add("VIII_xolotl");
        config.replaceSlots.put("MAINGUN_1", "VIII_euthystopos_e");
        config.nextHullMod = "VIII_euthystopos_b_Mode1";       // 下一个模式回到B型
        config.sourceHullMod = "VIII_euthystopos_b_Mode1";
        config.displaySortOrder = 3700;
        config.displayCategoryIndex = 2;
        config.managedWeaponPrefixes.add("VIII_xolotl_MainGun1");

        // ========== 模式组3：Xolotl舰的主武器2 ==========
        // 模式3-1：主炮2 乙太停止器B型（VIII_euthystopos_b_Mode2）
        config = new hullModComfig("VIII_euthystopos_b_Mode2");
        HULL_MOD_CONFIGS.put("VIII_euthystopos_b_Mode2", config);
        config.hulls.add("VIII_xolotl");
        config.replaceSlots.put("MAINGUN_2", "VIII_euthystopos_b");
        config.nextHullMod = "VIII_euthystopos_e_Mode2";
        config.sourceHullMod = "VIII_euthystopos_b_Mode2";
        config.displaySortOrder = 3800;
        config.displayCategoryIndex = 2;
        config.managedWeaponPrefixes.add("VIII_xolotl_MainGun2");

        // 模式3-2：主炮2 乙太停止器E型（VIII_euthystopos_e_Mode2）
        config = new hullModComfig("VIII_euthystopos_e_Mode2");
        HULL_MOD_CONFIGS.put("VIII_euthystopos_e_Mode2", config);
        config.hulls.add("VIII_xolotl");
        config.replaceSlots.put("MAINGUN_2", "VIII_euthystopos_e");
        config.nextHullMod = "VIII_euthystopos_b_Mode2";
        config.sourceHullMod = "VIII_euthystopos_b_Mode2";
        config.displaySortOrder = 3900;
        config.displayCategoryIndex = 2;
        config.managedWeaponPrefixes.add("VIII_xolotl_MainGun2");
    }

    /**
     * 在舰船创建后应用插件效果。
     * 根据当前插件的ID从配置映射中获取对应的配置，并调用其 applyEffectsAfterShipCreation 方法。
     */
    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        // 新增：如果当前插件是永久插件，则转换为普通插件
        String modId = spec.getId();
        if (HULL_MOD_CONFIGS.containsKey(modId)) {
            HULL_MOD_CONFIGS.get(modId).applyEffectsAfterShipCreation(ship, id);
        }
    }

    /**
     * 获取插件在列表中的显示顺序。
     * 如果当前插件有配置，则使用配置中的排序值，否则调用父类方法。
     */
    @Override
    public int getDisplaySortOrder() {
        if (HULL_MOD_CONFIGS.containsKey(spec.getId())) {
            return HULL_MOD_CONFIGS.get(spec.getId()).displaySortOrder;
        }
        return super.getDisplaySortOrder();
    }

    /**
     * 获取插件显示的分类索引（用于UI分组）。
     */
    @Override
    public int getDisplayCategoryIndex() {
        if (HULL_MOD_CONFIGS.containsKey(spec.getId())) {
            return HULL_MOD_CONFIGS.get(spec.getId()).displayCategoryIndex;
        }
        return super.getDisplayCategoryIndex();
    }

    /**
     * 内部配置类，存储每个武器切换模式的详细参数和行为。
     * 每个模式对应一个具体的插件ID，包含允许安装的船体、武器槽替换映射、
     * 轮替关系、显示属性以及需要清理的武器前缀。
     */
    public static class hullModComfig {
        // 冲突插件集合（暂未使用，但可扩展为禁止与其他插件共存）
        private final Set<String> conflictHullMods = new HashSet<>();
        // 允许安装的船体ID集合（空集合表示无限制）
        private final Set<String> hulls = new HashSet<>();
        // 下一个插件的ID（用于轮替，形成循环链）
        private String nextHullMod = null;
        // 源插件ID（用于轮替关系，通常是一组轮替插件中第一个的ID）
        private String sourceHullMod = null;
        // 武器槽替换映射：槽位ID -> 武器ID
        private final Map<String, String> replaceSlots = new HashMap<>();
        // 当前配置对应的插件ID（主要用于日志）
        private final String modId;

        // 显示控制属性
        private int displaySortOrder = 2000; // 默认显示顺序（值越小越靠前）
        private int displayCategoryIndex = 2; // 默认分类（2表示舰船系统类）

        // 管理的武器ID前缀集合：用于清理玩家仓库中不再需要的武器
        private final Set<String> managedWeaponPrefixes = new HashSet<>();

        private hullModComfig(String modId) {
            this.modId = modId;
        }

        public String getNextHullmod() {
            return nextHullMod;
        }

        public String getSourceHullMod() {
            return sourceHullMod;
        }

        /**
         * 在舰船创建后应用效果：执行武器槽替换和武器清理。
         */
        public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
            applySlotReplace(ship);
            cleanupManagedWeapons(ship);
        }

        /**
         * 执行武器槽替换：将指定槽位的武器替换为配置中的武器。
         * 如果目标武器不存在或无效，则保留原武器并记录错误。
         */
        public void applySlotReplace(ShipAPI ship) {
            ShipVariantAPI variant = ship.getVariant();
            for (String slotID : replaceSlots.keySet()) {
                if (variant.getSlot(slotID) == null) {
                    Global.getLogger(this.getClass()).warn("武器切换系统：槽位 " + slotID + " 不存在，跳过替换");
                    continue;
                }
                String weaponId = replaceSlots.get(slotID);
                if (weaponId == null || weaponId.isEmpty()) {
                    Global.getLogger(this.getClass()).warn("武器切换系统：武器ID为空，槽位 " + slotID + " 将被清空");
                    variant.clearSlot(slotID);
                    continue;
                }
                if (Global.getSettings().getWeaponSpec(weaponId) == null) {
                    Global.getLogger(this.getClass()).error("武器切换系统：武器ID " + weaponId + " 无效，无法安装到槽位 " + slotID + "，保留原武器");
                    // 不清空槽位，保留原武器
                    continue;
                }
                // 武器有效，执行替换
                variant.clearSlot(slotID);
                variant.addWeapon(slotID, weaponId);
                Global.getLogger(this.getClass()).info("武器切换系统：槽位 " + slotID + " 已安装武器 " + weaponId);
            }
        }

        /**
         * 清理玩家仓库中由本插件管理的武器。
         * 仅在舰船属于玩家（originalOwner < 0）时执行，防止清理AI舰船仓库。
         * 遍历玩家舰队货物，移除所有武器ID以 managedWeaponPrefixes 中任一前缀开头的武器堆叠。
         * 这样做是为了防止玩家通过拆卸插件保留旧武器，导致重复获得。
         */
        private void cleanupManagedWeapons(ShipAPI ship) {
            if (ship.getOriginalOwner() < 0) {  // 仅处理玩家舰船（originalOwner为-1表示玩家）
                // 确保玩家舰队和货物存在
                if (Global.getSector() != null
                        && Global.getSector().getPlayerFleet() != null
                        && Global.getSector().getPlayerFleet().getCargo() != null) {

                    // 遍历货物中的所有堆叠（使用副本避免并发修改）
                    for (CargoStackAPI stack : Global.getSector().getPlayerFleet().getCargo().getStacksCopy()) {
                        if (stack.isWeaponStack()) {
                            String weaponId = stack.getWeaponSpecIfWeapon().getWeaponId();
                            // 检查武器ID是否以任一管理前缀开头
                            for (String prefix : managedWeaponPrefixes) {
                                if (weaponId.startsWith(prefix)) {
                                    // 移除该堆叠
                                    Global.getSector().getPlayerFleet().getCargo().removeStack(stack);
                                    Global.getLogger(this.getClass()).info(
                                            "武器切换系统：清理玩家仓库中的轮替武器 " + weaponId +
                                                    " (配置: " + modId + ")");
                                    break; // 找到匹配后跳出内层循环
                                }
                            }
                        }
                    }
                }
            }
        }

        /**
         * 判断该配置是否适用于指定的舰船变体。
         * 检查船体ID是否在允许列表内（如果列表非空），且没有冲突插件。
         * 目前冲突插件逻辑未实际使用，但可扩展。
         * @param variant 舰船变体
         * @return 如果适用则返回true
         */
        public boolean isApplicableToShip(ShipVariantAPI variant) {
            String hullID = variant.getHullSpec().getBaseHullId();
            if (!hulls.isEmpty() && !hulls.contains(hullID)) {
                return false;
            }
            for (String mod : variant.getHullMods()) {
                if (conflictHullMods.contains(mod)) {
                    return false;
                }
            }
            return true;
        }
    }
}