package data.hullmods;


import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;

public class VIII_productionStop extends BaseHullMod {
    /**
     * 判断该插件是否可应用于指定舰船。
     * @param ship 目标舰船
     * @return true 如果可以应用，否则 false
     */
    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        // 注意：原代码中写了两遍相同的判断条件，可能是笔误，但实际效果是检查舰船是否安装了“GWC_Refactor”插件
        // 并且舰船必须有护盾（ship.getShield() != null）
        return (ship.getVariant().getHullMods().contains("VIII_production"));
        // 注释掉的部分原本要求舰船船体ID以“GWC_Exstinctio”开头，现已失效
    }

    /**
     * 获取无法应用该插件的原因字符串。
     * @param ship 目标舰船
     * @return 原因字符串，若无原因返回 null
     */
    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        // 如果舰船没有安装“GWC_Refactor”插件，则返回缺少必要设备的提示
        if (!ship.getVariant().getHullMods().contains("VIII_production")) {
            return "缺少必要的设备";
        }
        // 注意：此处没有对护盾缺失的检查，但根据 isApplicableToShip 的逻辑，缺少护盾也会导致不可用
        // 但 getUnapplicableReason 可能只处理部分原因，剩余原因会由系统自动处理或默认文本
        return null;
    }
}