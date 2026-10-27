package data.scripts.shipsystems;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;

/**
 * VIII系列机动推进器系统脚本（重构版）
 *
 * 新效果：
 *   - 舰船机动性 ×2（加速度、减速度、转向加速度、最大转向速率均翻倍）
 *   - 最大航速 +65
 *   - 武器射速 +50%（实弹/能量/导弹）
 *   - 武器幅能消耗 -50%（实弹/能量/导弹）
 */
public class VIII_ManeuveringJetsStats extends BaseShipSystemScript {

	@Override
	public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
		// 退出阶段：移除全部加成
		if (state == State.OUT) {
			unmodifyAll(stats, id);
			return;
		}

		// ===== 机动性 ×2 =====
		// 加速度 ×2
		stats.getAcceleration().modifyMult(id, 3f);
		// 减速度 ×2
		stats.getDeceleration().modifyMult(id, 3f);
		// 转向加速度 ×2
		stats.getTurnAcceleration().modifyMult(id, 3f);
		// 最大转向速率 ×2
		stats.getMaxTurnRate().modifyMult(id, 3f);

		// ===== 最大航速 +65 =====
		stats.getMaxSpeed().modifyFlat(id, 65f);

		// ===== 武器射速 +50% =====
		stats.getBallisticRoFMult().modifyMult(id, 1.5f);
		stats.getEnergyRoFMult().modifyMult(id, 1.5f);
		stats.getMissileRoFMult().modifyMult(id, 1.5f);

		// ===== 武器幅能消耗 -50% =====
		stats.getEnergyWeaponFluxCostMod().modifyMult(id, 0.5f);
		stats.getBallisticWeaponFluxCostMod().modifyMult(id, 0.5f);
		stats.getMissileWeaponFluxCostMod().modifyMult(id, 0.5f);
	}

	@Override
	public void unapply(MutableShipStatsAPI stats, String id) {
		unmodifyAll(stats, id);
	}

	/**
	 * 移除所有本系统添加的属性修正
	 */
	private void unmodifyAll(MutableShipStatsAPI stats, String id) {
		stats.getAcceleration().unmodify(id);
		stats.getDeceleration().unmodify(id);
		stats.getTurnAcceleration().unmodify(id);
		stats.getMaxTurnRate().unmodify(id);
		stats.getMaxSpeed().unmodify(id);

		stats.getBallisticRoFMult().unmodify(id);
		stats.getEnergyRoFMult().unmodify(id);
		stats.getMissileRoFMult().unmodify(id);

		stats.getEnergyWeaponFluxCostMod().unmodify(id);
		stats.getBallisticWeaponFluxCostMod().unmodify(id);
		stats.getMissileWeaponFluxCostMod().unmodify(id);
	}

	@Override
	public StatusData getStatusData(int index, State state, float effectLevel) {
		if (index == 0) {
			return new StatusData("机动性提高 最大航速 +65", false);
		} else if (index == 1) {
			return new StatusData("武器射速 +50% 武器幅能 -50%", false);
		}
		return null;
	}
}