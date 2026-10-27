package data.scripts.util;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * 通用工具类
 * 提供各种实用方法
 */
public class VIII_Util {

	/**
	 * 按每秒次数触发
	 * 用于控制粒子生成频率等
	 *
	 * @param times 每秒触发次数
	 * @param amount 时间增量（通常是engine.getElapsedInLastFrame()）
	 * @return 是否触发
	 */
	public static boolean timesPerSec(float times, float amount) {
		return Math.random() < amount * times;
	}

	/**
	 * 获取范围内的所有弹幕和导弹
	 *
	 * @param location 中心位置
	 * @param range 范围
	 * @return 弹幕列表
	 */
	public static List<DamagingProjectileAPI> getProjectilesAndMissilesWithinRange(Vector2f location, float range) {
		List<DamagingProjectileAPI> result = new ArrayList<>();

		// 获取所有弹幕
		for (DamagingProjectileAPI proj : Global.getCombatEngine().getProjectiles()) {
			if (proj.getLocation() == null) continue;
			float distance = Vector2f.sub(proj.getLocation(), location, null).length();
			if (distance <= range) {
				result.add(proj);
			}
		}

		// 获取所有导弹
		for (MissileAPI missile : Global.getCombatEngine().getMissiles()) {
			if (missile.getLocation() == null) continue;
			float distance = Vector2f.sub(missile.getLocation(), location, null).length();
			if (distance <= range) {
				result.add(missile);
			}
		}

		return result;
	}

	/**
	 * 获取范围内的敌方弹幕和导弹
	 *
	 * @param location 中心位置
	 * @param range 范围
	 * @param owner 己方阵营
	 * @return 敌方弹幕列表
	 */
	public static List<DamagingProjectileAPI> getEnemyProjectilesAndMissilesWithinRange(Vector2f location, float range, int owner) {
		List<DamagingProjectileAPI> result = new ArrayList<>();

		for (DamagingProjectileAPI proj : getProjectilesAndMissilesWithinRange(location, range)) {
			if (proj.getOwner() != owner) {
				result.add(proj);
			}
		}

		return result;
	}

	/**
	 * 添加光源（使用GraphicsLib）
	 * 如果GraphicsLib不可用，则不执行任何操作
	 *
	 * @param location 光源位置
	 * @param size 光源大小
	 * @param intensity 光源强度
	 * @param duration 持续时间
	 * @param color 光源颜色
	 */
	public static void addLight(Vector2f location, float size, float intensity, float duration, Color color) {
		// 检查是否有GraphicsLib
		try {
			Class.forName("org.dark.shaders.light.LightShader");
			// 如果有GraphicsLib，使用反射调用
			Class<?> lightShaderClass = Class.forName("org.dark.shaders.light.LightShader");
			Class<?> standardLightClass = Class.forName("org.dark.shaders.light.StandardLight");

			Object light = standardLightClass.getConstructor(
					Vector2f.class, Vector2f.class, Vector2f.class, CombatEntityAPI.class
			).newInstance(location, new Vector2f(), new Vector2f(), null);

			standardLightClass.getMethod("setSize", float.class).invoke(light, size);
			standardLightClass.getMethod("setIntensity", float.class).invoke(light, intensity);
			standardLightClass.getMethod("setLifetime", float.class).invoke(light, duration);
			standardLightClass.getMethod("setColor", Color.class).invoke(light, color);
			standardLightClass.getMethod("fadeIn", float.class).invoke(light, 0.1f);
			standardLightClass.getMethod("fadeOut", float.class).invoke(light, duration * 0.5f);

			lightShaderClass.getMethod("addLight", standardLightClass).invoke(null, light);
		} catch (Exception e) {
			// GraphicsLib不可用，忽略
		}
	}

	/**
	 * 显示浮动文本
	 *
	 * @param anchor 锚点实体
	 * @param at 显示位置
	 * @param text 文本内容
	 */
	public static void showText(CombatEntityAPI anchor, Vector2f at, String text) {
		Global.getCombatEngine().addFloatingText(at, text, 25f, Color.RED, anchor, 1f, 1f);
	}

	/**
	 * 显示浮动数字
	 *
	 * @param anchor 锚点实体
	 * @param at 显示位置
	 * @param amount 数字
	 */
	public static void showText(CombatEntityAPI anchor, Vector2f at, float amount) {
		showText(anchor, at, "" + amount);
	}

	// ==================== Grid遍历工具方法 ====================

	/**
	 * 使用Grid遍历获取范围内的导弹
	 * 性能优化：使用空间索引，只检查指定区域内的导弹
	 *
	 * @param center 中心位置
	 * @param radius 检查半径
	 * @return 范围内的导弹列表
	 */
	public static List<MissileAPI> getMissilesInRange(Vector2f center, float radius) {
		List<MissileAPI> result = new ArrayList<>();
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null) return result;

		float checkSize = radius * 2f;
		java.util.Iterator<Object> iter = engine.getMissileGrid().getCheckIterator(
				center, checkSize, checkSize
		);

		while (iter.hasNext()) {
			Object obj = iter.next();
			if (!(obj instanceof MissileAPI)) continue;

			MissileAPI missile = (MissileAPI) obj;
			if (missile.isExpired() || !engine.isEntityInPlay(missile)) continue;

			float dist = Vector2f.sub(missile.getLocation(), center, null).length();
			if (dist <= radius) {
				result.add(missile);
			}
		}

		return result;
	}

	/**
	 * 使用Grid遍历获取范围内的敌方导弹
	 *
	 * @param center 中心位置
	 * @param radius 检查半径
	 * @param owner 己方阵营
	 * @return 范围内的敌方导弹列表
	 */
	public static List<MissileAPI> getEnemyMissilesInRange(Vector2f center, float radius, int owner) {
		List<MissileAPI> result = new ArrayList<>();
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null) return result;

		float checkSize = radius * 2f;
		java.util.Iterator<Object> iter = engine.getMissileGrid().getCheckIterator(
				center, checkSize, checkSize
		);

		while (iter.hasNext()) {
			Object obj = iter.next();
			if (!(obj instanceof MissileAPI)) continue;

			MissileAPI missile = (MissileAPI) obj;
			if (missile.getOwner() == owner) continue;
			if (missile.isExpired() || !engine.isEntityInPlay(missile)) continue;

			float dist = Vector2f.sub(missile.getLocation(), center, null).length();
			if (dist <= radius) {
				result.add(missile);
			}
		}

		return result;
	}

	/**
	 * 使用Grid遍历获取范围内的所有弹丸（包括导弹）
	 *
	 * @param center 中心位置
	 * @param radius 检查半径
	 * @return 范围内的弹丸列表
	 */
	public static List<DamagingProjectileAPI> getAllProjectilesInRange(Vector2f center, float radius) {
		List<DamagingProjectileAPI> result = new ArrayList<>();
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null) return result;

		float checkSize = radius * 2f;
		java.util.Iterator<Object> iter = engine.getAllObjectGrid().getCheckIterator(
				center, checkSize, checkSize
		);

		while (iter.hasNext()) {
			Object obj = iter.next();
			if (!(obj instanceof DamagingProjectileAPI)) continue;

			DamagingProjectileAPI proj = (DamagingProjectileAPI) obj;
			if (proj.isExpired() || !engine.isEntityInPlay(proj)) continue;

			float dist = Vector2f.sub(proj.getLocation(), center, null).length();
			if (dist <= radius) {
				result.add(proj);
			}
		}

		return result;
	}

	/**
	 * 使用Grid遍历获取范围内的敌方弹丸（包括导弹）
	 *
	 * @param center 中心位置
	 * @param radius 检查半径
	 * @param owner 己方阵营
	 * @return 范围内的敌方弹丸列表
	 */
	public static List<DamagingProjectileAPI> getEnemyProjectilesInRange(Vector2f center, float radius, int owner) {
		List<DamagingProjectileAPI> result = new ArrayList<>();
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null) return result;

		float checkSize = radius * 2f;
		java.util.Iterator<Object> iter = engine.getAllObjectGrid().getCheckIterator(
				center, checkSize, checkSize
		);

		while (iter.hasNext()) {
			Object obj = iter.next();
			if (!(obj instanceof DamagingProjectileAPI)) continue;

			DamagingProjectileAPI proj = (DamagingProjectileAPI) obj;
			if (proj.getOwner() == owner) continue;
			if (proj.isExpired() || !engine.isEntityInPlay(proj)) continue;

			float dist = Vector2f.sub(proj.getLocation(), center, null).length();
			if (dist <= radius) {
				result.add(proj);
			}
		}

		return result;
	}

	/**
	 * 使用Grid遍历获取范围内的舰船
	 *
	 * @param center 中心位置
	 * @param radius 检查半径
	 * @return 范围内的舰船列表
	 */
	public static List<ShipAPI> getShipsInRange(Vector2f center, float radius) {
		List<ShipAPI> result = new ArrayList<>();
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null) return result;

		float checkSize = radius * 2f;
		java.util.Iterator<Object> iter = engine.getShipGrid().getCheckIterator(
				center, checkSize, checkSize
		);

		while (iter.hasNext()) {
			Object obj = iter.next();
			if (!(obj instanceof ShipAPI)) continue;

			ShipAPI ship = (ShipAPI) obj;
			if (ship.isHulk() || !engine.isEntityInPlay(ship)) continue;

			float dist = Vector2f.sub(ship.getLocation(), center, null).length();
			if (dist <= radius) {
				result.add(ship);
			}
		}

		return result;
	}

	/**
	 * 使用Grid遍历获取范围内的敌方舰船
	 *
	 * @param center 中心位置
	 * @param radius 检查半径
	 * @param owner 己方阵营
	 * @return 范围内的敌方舰船列表
	 */
	public static List<ShipAPI> getEnemyShipsInRange(Vector2f center, float radius, int owner) {
		List<ShipAPI> result = new ArrayList<>();
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null) return result;

		float checkSize = radius * 2f;
		java.util.Iterator<Object> iter = engine.getShipGrid().getCheckIterator(
				center, checkSize, checkSize
		);

		while (iter.hasNext()) {
			Object obj = iter.next();
			if (!(obj instanceof ShipAPI)) continue;

			ShipAPI ship = (ShipAPI) obj;
			if (ship.getOwner() == owner) continue;
			if (ship.isHulk() || !engine.isEntityInPlay(ship)) continue;

			float dist = Vector2f.sub(ship.getLocation(), center, null).length();
			if (dist <= radius) {
				result.add(ship);
			}
		}

		return result;
	}

	/**
	 * 使用Grid遍历获取范围内的小行星
	 *
	 * @param center 中心位置
	 * @param radius 检查半径
	 * @return 范围内的小行星列表
	 */
	public static List<CombatEntityAPI> getAsteroidsInRange(Vector2f center, float radius) {
		List<CombatEntityAPI> result = new ArrayList<>();
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null) return result;

		float checkSize = radius * 2f;
		java.util.Iterator<Object> iter = engine.getAsteroidGrid().getCheckIterator(
				center, checkSize, checkSize
		);

		while (iter.hasNext()) {
			Object obj = iter.next();
			if (!(obj instanceof CombatEntityAPI)) continue;

			CombatEntityAPI asteroid = (CombatEntityAPI) obj;
			if (!engine.isEntityInPlay(asteroid)) continue;

			float dist = Vector2f.sub(asteroid.getLocation(), center, null).length();
			if (dist <= radius) {
				result.add(asteroid);
			}
		}

		return result;
	}

	/**
	 * 使用Grid遍历获取矩形区域内的导弹
	 * 适用于光束、光剑等线性武器的检测
	 *
	 * @param center 中心位置
	 * @param width 宽度
	 * @param height 高度
	 * @return 区域内的导弹列表
	 */
	public static List<MissileAPI> getMissilesInRect(Vector2f center, float width, float height) {
		List<MissileAPI> result = new ArrayList<>();
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null) return result;

		java.util.Iterator<Object> iter = engine.getMissileGrid().getCheckIterator(
				center, width, height
		);

		while (iter.hasNext()) {
			Object obj = iter.next();
			if (!(obj instanceof MissileAPI)) continue;

			MissileAPI missile = (MissileAPI) obj;
			if (missile.isExpired() || !engine.isEntityInPlay(missile)) continue;

			result.add(missile);
		}

		return result;
	}

	/**
	 * 使用Grid遍历获取矩形区域内的所有弹丸
	 * 适用于光束、光剑等线性武器的检测
	 *
	 * @param center 中心位置
	 * @param width 宽度
	 * @param height 高度
	 * @return 区域内的弹丸列表
	 */
	public static List<DamagingProjectileAPI> getAllProjectilesInRect(Vector2f center, float width, float height) {
		List<DamagingProjectileAPI> result = new ArrayList<>();
		CombatEngineAPI engine = Global.getCombatEngine();
		if (engine == null) return result;

		java.util.Iterator<Object> iter = engine.getAllObjectGrid().getCheckIterator(
				center, width, height
		);

		while (iter.hasNext()) {
			Object obj = iter.next();
			if (!(obj instanceof DamagingProjectileAPI)) continue;

			DamagingProjectileAPI proj = (DamagingProjectileAPI) obj;
			if (proj.isExpired() || !engine.isEntityInPlay(proj)) continue;

			result.add(proj);
		}

		return result;
	}
}
