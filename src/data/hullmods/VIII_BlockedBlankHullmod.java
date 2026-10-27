package data.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

//这个是冲突的船插的警告用的船插 只是占位的罢了
public class VIII_BlockedBlankHullmod extends BaseHullMod
{
    //这个似乎不要也行
    @Override
    public String getDescriptionParam(int index, HullSize hullSize)
    {
        if (index == 0)
        {
            return "坏耶!";
        }
        return null;
    }
}
