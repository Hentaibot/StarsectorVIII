package data.hullmods;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CoreUITabId;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import data.hullmods.VIII_WeaponSwitch2.hullModComfig;
import java.util.*;
import static data.hullmods.VIII_WeaponSwitch2.HULL_MOD_CONFIGS;

/**
 * 轮替插件管理器插件（内置不可见）。
 * 作用：在舰船创建时自动安装预设的初始源插件，并在玩家手动移除某个轮替插件后，
 * 根据配置自动安装下一个轮替插件（或回退到源插件）。
 */
public class VIII_WeaponSwitch extends BaseHullMod {

    /**
     * 记录每个舰船变体上已安装的插件集合，用于检测插件是否被移除。
     * 键：舰船变体对象；值：该变体上已安装的插件ID集合（快照）。
     */
    private static final Map<ShipVariantAPI, Set<String>> modRecorder = new HashMap<>();

    @Override
    public void applyEffectsBeforeShipCreation(ShipAPI.HullSize hullSize, MutableShipStatsAPI stats, String id) {
        ShipVariantAPI variant = stats.getVariant();
        if (variant != null) {
            // 用于存储本次检测到的被移除的插件ID
            Set<String> removedHullMod = new HashSet<>();

            // 如果该变体已有记录（说明之前保存过快照）
            if (modRecorder.containsKey(variant)) {
                Global.getLogger(this.getClass()).info("检查被移除的插件");
                // 遍历记录中的插件，检查当前变体是否还拥有
                for (String modId : modRecorder.get(variant)) {
                    if (!variant.hasHullMod(modId)) {
                        removedHullMod.add(modId);
                        Global.getLogger(this.getClass()).info("被移除的插件：" + modId);
                    }
                }
            } else {
                // 首次处理该变体：需要安装初始的源插件
                Global.getLogger(this.getClass()).info("首次安装");
                // 收集所有配置中定义的源插件ID（每个源插件只处理一次，避免重复）
                Set<String> sourceHullMod = new HashSet<>();
                for (VIII_WeaponSwitch2.hullModComfig config : HULL_MOD_CONFIGS.values()) {
                    // 如果该配置有源插件ID，且该源插件尚未被加入集合，且该配置适用于当前变体
                    if (!sourceHullMod.contains(config.getSourceHullMod())) {
                        if (config.getSourceHullMod() != null && config.isApplicableToShip(variant)) {
                            sourceHullMod.add(config.getSourceHullMod());
                            Global.getLogger(this.getClass()).info("SourceMod：" + config.getSourceHullMod());
                        }
                    }
                }
                // 遍历变体当前已安装的插件，如果这些插件本身也是配置中的轮替插件，
                // 则从源插件集合中移除对应的源插件（因为已安装的插件替代了源插件）
                for (String modId : variant.getHullMods()) {
                    if (HULL_MOD_CONFIGS.containsKey(modId)) {
                        hullModComfig config = HULL_MOD_CONFIGS.get(modId);
                        sourceHullMod.remove(config.getSourceHullMod());
                        Global.getLogger(this.getClass()).info("已安装的：" + modId + " 移除" + sourceHullMod);
                    }
                }
                // 安装剩余的源插件
                if (!sourceHullMod.isEmpty()) {
                    for (String modId : sourceHullMod) {
                        Global.getLogger(this.getClass()).info("安装插件：" + modId);
                        variant.addMod(modId);
                    }
                }
            }

            // 处理被移除的插件：查找下一个应该安装的插件
            if (!removedHullMod.isEmpty()) {
                Global.getLogger(this.getClass()).info("遍历被移除mod的下一个mod：");
                for (String modId : removedHullMod) {
                    String addId = null;
                    String sourceId = null;
                    int i = 0;
                    // 循环查找，直到找到一个可安装的插件或陷入死循环
                    while (addId == null && HULL_MOD_CONFIGS.containsKey(modId)) {
                        i++;
                        hullModComfig config = HULL_MOD_CONFIGS.get(modId);
                        addId = config.getNextHullmod();          // 获取配置中定义的下一个插件
                        if (sourceId == null) {
                            sourceId = config.getSourceHullMod(); // 记录源插件，作为最后备选
                            Global.getLogger(this.getClass()).info("sourceid：" + sourceId);
                        }
                        // 如果下一个插件不适用于当前变体，则继续沿着链查找
                        if (!config.isApplicableToShip(variant)) {
                            addId = null;
                            modId = config.getNextHullmod();      // 将modId更新为下一个，继续循环
                        }
                        // 防止死循环（最多查找999次）
                        if (i > 10) {
                            Global.getLogger(this.getClass()).info("尝试寻找继承插件id的时候陷入死循环，跳出，所有插件都不适用于当前舰船 插件id：" + modId);
                            break;
                        }
                    }
                    // 如果找到了可安装的下一个插件，则安装它
                    if (addId != null && !addId.isEmpty()) {
                        variant.addMod(addId);
                        Global.getLogger(this.getClass()).info("安装插件：" + addId);
                    } else if (sourceId != null && !sourceId.isEmpty()) {
                        // 否则回退安装源插件
                        variant.addMod(sourceId);
                        Global.getLogger(this.getClass()).info("安装插件：" + sourceId);
                    }
                }
            }
        }
    }

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        // 当舰船生成后，保存当前变体的插件快照到记录器中，用于后续检测移除
        ShipVariantAPI variant = ship.getVariant();
        modRecorder.put(variant, new HashSet<>(variant.getHullMods()));
    }

    @Override
    public void advanceInCampaign(FleetMemberAPI member, float amount) {
        // 如果处于装配界面，则不执行清理（避免在玩家装配时清空记录）
        if (isInRefit()) {
            return;
        }
        // 离开装配界面后，清空所有记录，因为下次进入战斗或重新生成舰船时会重新记录
        modRecorder.clear();
    }

    /**
     * 判断当前是否处于装配界面。
     * 返回true表示在装配界面，此时不进行记录清理。
     * 注意：该方法逻辑可能存在一定问题（例如对战斗和模拟的判断），但基本符合需求。
     */
    public static boolean isInRefit() {
        if (Global.getSector().getCampaignUI().getCurrentCoreTab() == CoreUITabId.REFIT) {
            return true;
        }
        if (Global.getCurrentState() == GameState.COMBAT) {
            return false; // 战斗状态肯定不是装配
        }
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) {
            return false; // 引擎不存在
        }
        if (engine.isSimulation()) {
            return false; // 模拟战不算装配
        }
        return engine.getCombatUI() == null; // 根据有无UI判断（可能有误，但保留原样）
    }

}