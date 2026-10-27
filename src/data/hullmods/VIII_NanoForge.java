package data.hullmods;

import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.BoundsAPI.SegmentAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.util.IntervalUtil;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.WeightedRandomPicker;

import data.scripts.VIII_threat.VIII_RoilingSwarmEffect;
import data.scripts.VIII_threat.VIII_SwarmLauncherEffect;
import data.scripts.VIII_threat.VIII_VoltaicDischargeOnFireEffect;
import data.scripts.shipsystems.VIII_ConstructionSwarmSystemScript;
import data.scripts.shipsystems.VIII_ConstructionSwarmSystemScript.VIII_SwarmConstructionData;

import org.lwjgl.util.vector.Vector2f;

/**
 * 战地纳米锻炉（VIII_eclipse 专属，内置隐藏船插）。
 * <p>
 * 【机制】
 * 开战瞬间先发射 2 个 VIII_FYP 建造群，之后每 {@link #PRODUCTION_INTERVAL} 秒
 * 消耗 {@link #FRAGMENT_COST} 个碎片再发射 1 个建造群：
 * <ul>
 * <li>场上己方 VIII_FYP 不足 {@link #FYP_LIMIT} 艘（含正在建造的）→ 继续造 VIII_FYP；</li>
 * <li>已经达到上限 → 改为造 VIII_BOOM。</li>
 * </ul>
 * 不消耗 CR（战备值）。
 * <p>
 * 【建造流程：不是就地打印，而是完整的"发射建造群 → 飞行 → 就地组装"】
 * 直接沿用威胁派系（VIII_threat）的原版流水线：
 * <ol>
 * <li>从本舰的 SYSTEM 武器槽发射一艘建造群战机（{@link VIII_SwarmLauncherEffect#CONSTRUCTION_SWARM_WING}），
 *     并给它挂 {@link VIII_SwarmConstructionData}，写明要造的变体、飞行时间、建造时间；</li>
 * <li>战机自带的 {@link data.scripts.VIII_threat.VIII_ThreatSwarmAI} 会操控它飞行，
 *     飞满 {@link #TRAVEL_TIME} 秒后创建
 *     {@link data.scripts.VIII_threat.VIII_ThreatShipConstructionScript}；</li>
 * <li>该脚本负责淡入、护甲增长、抖动、负星云粒子、环路音效，建造完成后生成目标舰，
 *     并把建造群战机本身撞毁（不会伤到母舰）；</li>
 * <li>碎片从母舰的 {@link VIII_RoilingSwarmEffect} 转移给建造群，同时消散 20 个成员
 *     （就是"消耗 20 碎片"）。</li>
 * </ol>
 * <p>
 * 【为什么内置且不可安装】
 * hull_mods.csv 里 hidden=TRUE、hiddenEverywhere=TRUE，只有 VIII_Eclipse.ship 的
 * builtInMods 里带它；改造界面里不会出现，其它舰船也装不上。
 */
public class VIII_NanoForge extends BaseHullMod {

    /** 唯一生效的舰体 id */
    public static final String HULL_ID = "VIII_eclipse";

    /** 先生产的舰船变体 id（浮游单元） */
    public static final String VARIANT_FYP = "VIII_FYP";
    /** 浮游单元达到上限后改产的舰船变体 id（爆破单元） */
    public static final String VARIANT_BOOM = "VIII_BOOM";

    /** 每批的生产间隔（秒） */
    public static final float PRODUCTION_INTERVAL = 20.0F;
    /** 每生产一艘消耗的碎片数量 */
    public static final int FRAGMENT_COST = 20;
    /** 场上允许同时存在的己方 VIII_FYP 数量上限（含正在建造的） */
    public static final int FYP_LIMIT = 2;

    /** 建造群从发射到抵达建造位置的时间（秒） */
    public static final float TRAVEL_TIME = 3.0F;
    /** 建造群到位后的组装时间（秒）——护卫舰用原版的快速建造时长 */
    public static final float CONSTRUCTION_TIME = 2.0F;

    /** 是否已经发射过开战第一批 */
    private static final String INITIAL_KEY = "VIII_nano_forge_initial_launched";
    /** 计时器存在舰船 customData 里的 key */
    private static final String TIMER_KEY = "VIII_nano_forge_timer";
    /** 玩家舰状态条 key */
    private static final String STATUS_KEY = "VIII_nano_forge_status";
    /** 状态条图标 */
    private static final String STATUS_ICON = "graphics/icons/hullsys/high_energy_focus.png";
    /** 建造群减速修正的来源 id */
    private static final String SWARM_SPEED_SOURCE = "VIII_nano_forge_swarm_speed";

    /**
     * 每帧推进生产计时。船插自带的 advanceInCombat 对每艘装了本插的舰船每帧调用一次，
     * 不需要额外挂监听器。
     */
    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        if (ship == null || ship.getHullSpec() == null) {
            return;
        }
        if (!HULL_ID.equals(ship.getHullSpec().getHullId())) {
            return;
        }
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null || engine.isPaused()) {
            return;
        }
        if (!ship.isAlive() || ship.isHulk()) {
            return;
        }

        IntervalUtil timer = getTimer(ship);

        // 开战第一批：不等待计时器，直接连续发射 2 个 VIII_FYP 建造群
        if (!Boolean.TRUE.equals(ship.getCustomData().get(INITIAL_KEY))) {
            int launched = 0;
            for (int i = 0; i < FYP_LIMIT; i++) {
                if (!launchConstructionSwarm(ship, engine, VARIANT_FYP)) {
                    break;
                }
                launched++;
            }
            if (launched > 0) {
                ship.setCustomData(INITIAL_KEY, Boolean.TRUE);
            }
            showStatus(ship, engine, timer, getFragments(ship));
            return;
        }

        timer.advance(amount);

        int fragments = getFragments(ship);
        showStatus(ship, engine, timer, fragments);

        if (!timer.intervalElapsed()) {
            return;
        }
        if (fragments < FRAGMENT_COST) {
            // 碎片不够：这一批跳过，等下一个周期
            return;
        }

        String variantId = countFypAndUnderConstruction(ship, engine) < FYP_LIMIT
                ? VARIANT_FYP : VARIANT_BOOM;
        launchConstructionSwarm(ship, engine, variantId);
    }

    /**
     * 发射一艘建造群战机，并把它配置成"飞过去造一艘 {@code variantId}"。
     * <p>
     * 逻辑照抄 {@link VIII_ConstructionSwarmSystemScript} 的 launchSwarm，
     * 去掉 CR 消耗与"按战况自动选变体"的部分（这里由锻炉决定造什么）。
     *
     * @return 成功发射返回 true
     */
    private boolean launchConstructionSwarm(ShipAPI ship, CombatEngineAPI engine, String variantId) {
        VIII_RoilingSwarmEffect sourceSwarm = VIII_RoilingSwarmEffect.getSwarmFor(ship);
        if (sourceSwarm == null || sourceSwarm.getNumActiveMembers() < FRAGMENT_COST) {
            return false;
        }

        WeightedRandomPicker<WeaponSlotAPI> slots = new WeightedRandomPicker<WeaponSlotAPI>();
        for (WeaponSlotAPI slot : ship.getHullSpec().getAllWeaponSlotsCopy()) {
            if (slot.isSystemSlot()) {
                slots.add(slot);
            }
        }
        if (slots.isEmpty()) {
            return false;
        }

        WeaponSlotAPI slot = slots.pick();
        Vector2f loc = slot.computePosition(ship);
        float facing = slot.computeMidArcAngle(ship);

        CombatFleetManagerAPI manager = engine.getFleetManager(ship.getOriginalOwner());
        if (manager == null) {
            return false;
        }
        boolean wasSuppressed = manager.isSuppressDeploymentMessages();
        manager.setSuppressDeploymentMessages(true);
        ShipAPI fighter = manager.spawnShipOrWing(
                VIII_SwarmLauncherEffect.CONSTRUCTION_SWARM_WING, loc, facing, 0.0F, null);
        manager.setSuppressDeploymentMessages(wasSuppressed);
        if (fighter == null) {
            return false;
        }
        if (fighter.getWing() != null) {
            fighter.getWing().setSourceShip(ship);
        }

        // 建造群移动缓慢，并沿着发射槽的方向飞出去
        fighter.getMutableStats().getMaxSpeed().modifyMult(
                SWARM_SPEED_SOURCE, VIII_ConstructionSwarmSystemScript.CONSTRUCTION_SWARM_SPEED_MULT);
        Vector2f takeoffVel = Misc.getUnitVectorAtDegreeAngle(facing);
        takeoffVel.scale(fighter.getMaxSpeed());

        // 建造群本体不可见、无爆炸 / 残骸，装甲网格清空（避免被部件伤害打断）
        fighter.setDoNotRender(true);
        fighter.setExplosionScale(0.0F);
        fighter.setHulkChanceOverride(0.0F);
        fighter.setImpactVolumeMult(VIII_SwarmLauncherEffect.IMPACT_VOLUME_MULT);
        fighter.getArmorGrid().clearComponentMap();
        Vector2f.add(fighter.getVelocity(), takeoffVel, fighter.getVelocity());

        // 给建造群挂自己的碎片群（用来承载"在建数据"和视觉上的碎片）
        VIII_RoilingSwarmEffect swarm = VIII_FragmentSwarmHullmod.createSwarmFor(fighter);
        if (swarm == null) {
            return false;
        }
        swarm.params.flashFringeColor = VIII_VoltaicDischargeOnFireEffect.EMP_FRINGE_COLOR;
        VIII_RoilingSwarmEffect.getFlockingMap().remove(swarm.params.flockingClass, swarm);
        swarm.params.flockingClass = VIII_FragmentSwarmHullmod.CONSTRUCTION_SWARM_FLOCKING_CLASS;
        VIII_RoilingSwarmEffect.getFlockingMap().add(swarm.params.flockingClass, swarm);

        // 护卫舰尺寸：碰撞边界放大 2 倍，避免建造群钻进舰体里
        float collisionMult = 2.0F;
        for (SegmentAPI segment : fighter.getExactBounds().getOrigSegments()) {
            segment.getP1().scale(collisionMult);
            segment.getP2().scale(collisionMult);
            segment.set(segment.getP1().x, segment.getP1().y, segment.getP2().x, segment.getP2().y);
        }
        fighter.setCollisionRadius(fighter.getCollisionRadius() * collisionMult);

        // 碎片群成员数 = 本次消耗的碎片数（视觉上也跟着变大）
        swarm.params.initialMembers = FRAGMENT_COST;
        swarm.params.baseMembersToMaintain = FRAGMENT_COST;

        // 把"要造什么、飞多久、造多久"塞进建造群
        VIII_SwarmConstructionData data = new VIII_SwarmConstructionData();
        data.variantId = variantId;
        data.constructionTime = CONSTRUCTION_TIME;
        data.preConstructionTravelTime = TRAVEL_TIME;
        swarm.custom1 = data;

        // 从母舰的碎片群里转移 20 个碎片给建造群 = 消耗 20 碎片
        int transfer = Math.min(FRAGMENT_COST, sourceSwarm.getNumActiveMembers());
        if (transfer > 0) {
            Vector2f transferPoint = new Vector2f(takeoffVel);
            transferPoint.scale(0.5F);
            Vector2f.add(transferPoint, fighter.getLocation(), transferPoint);
            sourceSwarm.transferMembersTo(swarm, transfer, transferPoint, 100.0F);
        }
        int add = FRAGMENT_COST - transfer;
        if (add > 0) {
            swarm.addMembers(add);
        }
        return true;
    }

    /** 取（或懒创建）本舰的生产计时器 */
    private IntervalUtil getTimer(ShipAPI ship) {
        Object curr = ship.getCustomData().get(TIMER_KEY);
        if (curr instanceof IntervalUtil) {
            return (IntervalUtil) curr;
        }
        IntervalUtil timer = new IntervalUtil(PRODUCTION_INTERVAL, PRODUCTION_INTERVAL);
        ship.setCustomData(TIMER_KEY, timer);
        return timer;
    }

    /** 本舰当前可用碎片数（没有碎片群则视为 0） */
    private int getFragments(ShipAPI ship) {
        VIII_RoilingSwarmEffect swarm = VIII_RoilingSwarmEffect.getSwarmFor(ship);
        return swarm == null ? 0 : swarm.getNumActiveMembers();
    }

    /**
     * 统计场上己方的 VIII_FYP 数量，包含"正在建造"的：
     * 已经成形的按 hullId 判断，还在建造群里的按 {@link VIII_SwarmConstructionData#variantId} 判断。
     */
    private int countFypAndUnderConstruction(ShipAPI source, CombatEngineAPI engine) {
        int owner = source.getOriginalOwner();
        int count = 0;
        List<ShipAPI> ships = engine.getShips();
        for (int i = 0; i < ships.size(); i++) {
            ShipAPI curr = ships.get(i);
            if (curr == null || curr.getHullSpec() == null) {
                continue;
            }
            if (curr.getOwner() != owner) {
                continue;
            }
            if (curr.isHulk() || !curr.isAlive()) {
                continue;
            }
            if (curr.isFighter()) {
                // 建造群：读它身上挂的建造数据
                VIII_RoilingSwarmEffect swarm = VIII_RoilingSwarmEffect.getSwarmFor(curr);
                if (swarm != null && swarm.custom1 instanceof VIII_SwarmConstructionData) {
                    VIII_SwarmConstructionData data = (VIII_SwarmConstructionData) swarm.custom1;
                    if (VARIANT_FYP.equals(data.variantId)) {
                        count++;
                    }
                }
                continue;
            }
            if (VARIANT_FYP.equals(curr.getHullSpec().getHullId())) {
                count++;
            }
        }
        return count;
    }

    /**
     * 只在玩家亲自指挥本舰时，把锻炉状态挂到左下角状态栏，
     * 显示下一批次的倒计时和当前碎片数。
     */
    private void showStatus(ShipAPI ship, CombatEngineAPI engine, IntervalUtil timer, int fragments) {
        if (engine.getPlayerShip() != ship) {
            return;
        }
        if (fragments < FRAGMENT_COST) {
            engine.maintainStatusForPlayerShip(STATUS_KEY, STATUS_ICON, "战地纳米锻炉",
                    "碎片不足（" + fragments + "/" + FRAGMENT_COST + "）", true);
            return;
        }
        float remain = timer.getIntervalDuration() - timer.getElapsed();
        if (remain < 0.0F) {
            remain = 0.0F;
        }
        String text = "下一批次 " + String.format("%.1f", Float.valueOf(remain))
                + " 秒（碎片 " + fragments + "）";
        engine.maintainStatusForPlayerShip(STATUS_KEY, STATUS_ICON, "战地纳米锻炉", text, false);
    }

    /**
     * 隐藏船插：不在改造界面显示（真正的隐藏靠 CSV 的 hidden/hiddenEverywhere）。
     */
    @Override
    public boolean shouldAddDescriptionToTooltip(HullSize hullSize, ShipAPI ship, boolean isForModSpec) {
        return false;
    }

    /**
     * 兜底：除 VIII_eclipse 以外的舰船即使被强行加上本插也不生效。
     */
    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        if (ship == null || ship.getHullSpec() == null) {
            return false;
        }
        return HULL_ID.equals(ship.getHullSpec().getHullId());
    }
}
