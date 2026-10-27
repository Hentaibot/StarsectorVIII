package data.scripts.shipsystems;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.*;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.impl.combat.BaseShipSystemScript;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.plugins.ShipSystemStatsScript;
import org.lwjgl.util.vector.Vector2f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 激光雷达阵列舰船系统脚本（终极增强版）。
 *
 * <p>新增功能：</p>
 * <ul>
 *   <li><b>视觉电弧特效：</b>系统激活期间，每隔 0.05 秒从随机非 SYSTEM 炮塔槽位向一个
 *        ID 前缀为 "EMP" 的 SYSTEM 槽位生成一道纯视觉电弧（无伤害），
 *        电弧参数参考重型裁决者命中特效，呈现华丽闪电效果。</li>
 * </ul>
 */
public class VIII_LidarArrayStats extends BaseShipSystemScript {

    // 雷达启动音效
    public static String LIDAR_WINDUP = "lidar_windup";
    // 武器辉光颜色（激活时）
    public static Color WEAPON_GLOW = new Color(0, 122, 255, 255);

    // ========== 属性加成常量 ==========
    public static float RANGE_BONUS = 40.0F;
    public static float PASSIVE_RANGE_BONUS = 20.0F;
    public static float ROF_BONUS = 2.0F;
    public static float RECOIL_BONUS = 75.0F;
    public static float PROJECTILE_SPEED_BONUS = 50.0F;

    // 雷达天线数据
    protected List<LidarDishData> dishData = new ArrayList<>();

    protected boolean needsUnapply = false;
    protected boolean playedWindup = false;
    protected boolean inited = false;

    private boolean outResetDone = false;

    // 幅能转移相关
    private float prevTotalFlux = 0f;
    private boolean fluxInit = false;
    private static final float HARD_FLUX_TRANSFER_RATE = 1.0f;

    // ---- 新增电弧系统 ----
    private float arcTimer = 0f;
    private static final float ARC_INTERVAL = 0.18f;

    // 槽位相对坐标缓存
    private final List<Vector2f> turretSlotOffsets = new ArrayList<>();   // 非系统炮塔槽位
    private final List<Vector2f> empSystemSlotOffsets = new ArrayList<>(); // 前缀 EMP 的系统槽位

    private static final Random RAND = new Random();

    // 电弧视觉参数（参考 VIII_heavy_adjudicatorOnHitEffect）
    private static final Color ARC_FRINGE = new Color(52, 93, 255, 255);
    private static final Color ARC_CORE = Color.WHITE;

    public VIII_LidarArrayStats() {
    }

    /**
     * 初始化雷达天线数据，并缓存所有槽位坐标。
     */
    public void init(ShipAPI ship) {
        if (this.inited) return;
        this.inited = true;
        this.needsUnapply = true;

        // 收集 lidar 装饰武器
        List<WeaponAPI> lidars = new ArrayList<>();
        for (WeaponAPI w : ship.getAllWeapons()) {
            if (w.isDecorative() && w.getSpec().hasTag("lidar")) {
                lidars.add(w);
            }
        }
        lidars.sort((o1, o2) -> Float.compare(o1.getSlot().getLocation().x, o2.getSlot().getLocation().x));

        float count = lidars.size();
        float index = 0f;
        for (WeaponAPI w : lidars) {
            w.setSuspendAutomaticTurning(true);
            LidarDishData data = new LidarDishData();
            data.w = w;
            data.angle = 0.0F;
            data.phase = index / count;
            data.count = count;
            this.dishData.add(data);
            index++;
        }

        // 收集电弧起点/终点槽位（仅需执行一次）
        ShipHullSpecAPI hullSpec = ship.getHullSpec();
        for (WeaponSlotAPI slot : hullSpec.getAllWeaponSlotsCopy()) {
            if (slot.isSystemSlot()) {
                if (slot.getId() != null && slot.getId().startsWith("EMP")) {
                    empSystemSlotOffsets.add(new Vector2f(slot.getLocation()));
                }
            } else if (slot.isTurret()) {
                turretSlotOffsets.add(new Vector2f(slot.getLocation()));
            }
        }
    }

    /**
     * 更新雷达的固定朝向（不旋转）。
     */
    public void updateDishOrientations() {
        for (LidarDishData data : this.dishData) {
            float facing = data.angle + data.w.getArcFacing() + data.w.getShip().getFacing();
            data.w.setFacing(facing);
            data.w.updateBeamFromPoints();
        }
    }

    @Override
    public void apply(MutableShipStatsAPI stats, String id, State state, float effectLevel) {
        ShipAPI ship = (ShipAPI) stats.getEntity();
        if (ship != null && !ship.isHulk()) {
            this.init(ship);

            boolean active = (state == State.IN || state == State.ACTIVE || state == State.OUT);

            if (active) {
                this.modify(id, stats, effectLevel);
                this.needsUnapply = true;
            } else if (this.needsUnapply) {
                this.unmodify(id, stats);
                for (WeaponAPI w : ship.getAllWeapons()) {
                    if (!w.getSlot().isSystemSlot() && !w.isDecorative()
                            && w.getSlot().isHardpoint()
                            && (w.getType() == WeaponType.BALLISTIC || w.getType() == WeaponType.ENERGY)) {
                        w.setGlowAmount(0.0F, null);
                    }
                }
                this.needsUnapply = false;
            }

            this.updateDishOrientations();

            // OUT 冷却重置
            if (state == State.OUT && !outResetDone) {
                for (WeaponAPI w : ship.getAllWeapons()) {
                    if (!w.isDecorative() && !w.getSlot().isSystemSlot() && w.getType() != WeaponType.MISSILE) {
                        w.setRemainingCooldownTo(0f);
                    }
                }
                outResetDone = true;
            }
            if (!active) {
                outResetDone = false;
                fluxInit = false;
                arcTimer = 0f;   // 系统关闭时重置电弧计时器
            }

            // 幅能转移
            if (active) {
                transferAllFluxWhileActive(ship);
            }

            // 电弧特效 (每 0.05 秒一组)
            if (active) {
                spawnArcIfNeeded(ship, Global.getCombatEngine().getElapsedInLastFrame());
            }

            if (active) {
                // 武器开火控制
                for (WeaponAPI w : ship.getAllWeapons()) {
                    if (w.getSlot().isSystemSlot() || w.getType() == WeaponType.MISSILE) continue;
                    if (state == State.IN) {
                        if (!w.isDecorative() || !w.getSpec().hasTag("lidar")) {
                            w.setForceNoFireOneFrame(true);
                        }
                    } else {
                        if (w.isDecorative() || !w.getSlot().isHardpoint()
                                || (w.getType() != WeaponType.BALLISTIC && w.getType() != WeaponType.ENERGY)) {
                            w.setForceNoFireOneFrame(true);
                        }
                    }
                }

                // 辉光
                for (WeaponAPI w : ship.getAllWeapons()) {
                    if (!w.isDecorative() && w.getSlot().isHardpoint()
                            && (w.getType() == WeaponType.BALLISTIC || w.getType() == WeaponType.ENERGY)) {
                        w.setGlowAmount(effectLevel, WEAPON_GLOW);
                    }
                }

                // 雷达开火
                float fireThreshold = 0.0769f + 0.02f;
                if (state == State.IN && effectLevel >= fireThreshold) {
                    for (LidarDishData data : this.dishData) {
                        if (data.w.isDecorative() && data.w.getSpec().hasTag("lidar")) {
                            data.w.setForceFireOneFrame(true);
                        }
                    }
                }

                // 音效
                if ((state == State.IN && effectLevel > 0.67f || state == State.ACTIVE) && !this.playedWindup) {
                    Global.getSoundPlayer().playSound(LIDAR_WINDUP, 1.0f, 1.0f, ship.getLocation(), ship.getVelocity());
                    this.playedWindup = true;
                }
            }
        } else {
            if (this.needsUnapply) {
                this.unmodify(id, stats);
                if (ship != null) {
                    for (WeaponAPI w : ship.getAllWeapons()) {
                        if (!w.isDecorative() && w.getSlot().isHardpoint()
                                && (w.getType() == WeaponType.BALLISTIC || w.getType() == WeaponType.ENERGY)) {
                            w.setGlowAmount(0.0F, null);
                        }
                    }
                }
                this.needsUnapply = false;
            }
        }
    }

    /**
     * 每隔 0.05 秒从随机炮塔向 EMP 系统槽位生成一道纯视觉电弧。
     */
    /**
     * 根据需要生成一条视觉电弧，连接随机选择的非系统炮塔和EMP系统槽位。
     *
     * 该方法每帧调用，由计时器控制电弧生成频率（{@link #ARC_INTERVAL}）。
     * 电弧纯为视觉效果，不受战斗伤害机制影响。
     *
     * @param ship 电弧所依附的舰船，用于坐标变换
     * @param dt   上一帧经过的时间（秒）
     */
    private void spawnArcIfNeeded(ShipAPI ship, float dt) {
        // 若炮塔或EMP槽位偏移量集合为空，则无法生成电弧，直接返回
        if (turretSlotOffsets.isEmpty() || empSystemSlotOffsets.isEmpty()) return;

        // 累加计时器
        arcTimer += dt;
        // 若未达到生成间隔，则跳过本次生成
        if (arcTimer < ARC_INTERVAL) return;

        // 重置计时器，保留超出部分以避免连续丢帧造成积累延迟
        arcTimer -= ARC_INTERVAL;

        // 获取战斗引擎实例
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) return;

        // ---- 随机选取起点（普通炮塔）与终点（EMP系统槽位） ----
        Vector2f startOffset = turretSlotOffsets.get(RAND.nextInt(turretSlotOffsets.size()));
        Vector2f endOffset   = empSystemSlotOffsets.get(RAND.nextInt(empSystemSlotOffsets.size()));

        // 将舰船局部的槽位偏移量转换为世界坐标系位置
        Vector2f start = transformOffsetToWorld(ship, startOffset);
        Vector2f end   = transformOffsetToWorld(ship, endOffset);

        // ---- 配置电弧参数 ----
        // 参数模仿“重型裁决者”的视觉效果，用于产生粗壮、不规则的电弧
        EmpArcEntityAPI.EmpArcParams params = new EmpArcEntityAPI.EmpArcParams();
        params.segmentLengthMult = 8f;          // 每段长度倍率（分段更长）
        params.zigZagReductionFactor = 0.15f;   // 锯齿减弱因子（让锯齿更小）
        params.fadeOutDist = 50f;               // 末尾淡出距离
        params.minFadeOutMult = 10f;            // 最小淡出倍率（确保淡出明显）
        params.flickerRateMult = 0.3f;          // 闪烁频率倍率
        // 强制覆盖电弧移动时长，避免过快闪烁（保证视觉平稳）
        params.movementDurOverride = Math.max(0.2f, 0.3f);

        // ---- 生成视觉电弧 ----
        // 将电弧锚定在舰船上（start 和 end 均绑定 ship），使其跟随舰船移动
        EmpArcEntityAPI mainArc = engine.spawnEmpArcVisual(
                start, ship,        // 起点（世界坐标）及绑定的实体
                end,   ship,        // 终点及绑定的实体
                15f,                // 电弧厚度（像素）
                ARC_FRINGE,         // 边缘颜色
                ARC_CORE,           // 核心颜色
                params
        );

        // 禁用起点默认的光晕渲染，让视觉效果更干净
        mainArc.setRenderGlowAtStart(false);
        // 启用单段闪烁模式，使整条电弧同步闪烁而非分段独立
        mainArc.setSingleFlickerMode(true);
        mainArc.setWarping(1f);
    }

    /**
     * 将船体坐标系下的相对偏移转换为世界坐标（考虑舰船位置与朝向）。
     */
    private Vector2f transformOffsetToWorld(ShipAPI ship, Vector2f offset) {
        float cos = (float) Math.cos(Math.toRadians(ship.getFacing()));
        float sin = (float) Math.sin(Math.toRadians(ship.getFacing()));
        Vector2f loc = ship.getLocation();
        return new Vector2f(
                loc.x + offset.x * cos - offset.y * sin,
                loc.y + offset.x * sin + offset.y * cos
        );
    }

    // ========== 其余方法保持不变 ==========
    private void transferAllFluxWhileActive(ShipAPI ship) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) return;
        FluxTrackerAPI flux = ship.getFluxTracker();
        float currTotal = flux.getCurrFlux();
        if (!fluxInit) {
            prevTotalFlux = currTotal;
            fluxInit = true;
            return;
        }
        float dt = engine.getElapsedInLastFrame();
        if (dt <= 0f) dt = 0.016f;
        ShipAPI target = ship.getShipTarget();
        boolean targetValid = target != null && target.isAlive() && target.getOwner() != ship.getOwner();
        List<ShipAPI> enemies = null;
        if (!targetValid) {
            enemies = new ArrayList<>();
            for (ShipAPI other : engine.getShips()) {
                if (other == ship) continue;
                if (!other.isAlive()) continue;
                if (other.getOwner() == ship.getOwner()) continue;
                if (!engine.isAwareOf(ship.getOwner(), other)) continue;
                enemies.add(other);
            }
        }
        float delta = currTotal - prevTotalFlux;
        if (delta > 0f) {
            if (targetValid) {
                target.getFluxTracker().increaseFlux(delta, true);
                flux.decreaseFlux(delta);
            } else if (enemies != null && !enemies.isEmpty()) {
                float each = delta / enemies.size();
                for (ShipAPI enemy : enemies) {
                    enemy.getFluxTracker().increaseFlux(each, true);
                }
                flux.decreaseFlux(delta);
            }
        }
        float currentHard = flux.getHardFlux();
        if (currentHard > 0f) {
            float maxTransfer = currentHard * dt * HARD_FLUX_TRANSFER_RATE;
            float toTransfer = Math.min(currentHard, maxTransfer);
            flux.setHardFlux(currentHard - toTransfer);
            flux.decreaseFlux(toTransfer);
            if (targetValid) {
                target.getFluxTracker().increaseFlux(toTransfer, true);
            } else if (enemies != null && !enemies.isEmpty()) {
                float each = toTransfer / enemies.size();
                for (ShipAPI enemy : enemies) {
                    enemy.getFluxTracker().increaseFlux(each, true);
                }
            }
        }
        prevTotalFlux = flux.getCurrFlux();
    }

    protected void modify(String id, MutableShipStatsAPI stats, float effectLevel) {
        float rofMult = 1.0f + ROF_BONUS * effectLevel;
        float recoilMult = 1.0f - 0.01f * RECOIL_BONUS;
        stats.getBallisticWeaponRangeBonus().modifyPercent(id, RANGE_BONUS);
        stats.getEnergyWeaponRangeBonus().modifyPercent(id, RANGE_BONUS);
        stats.getBeamWeaponRangeBonus().modifyPercent(id, RANGE_BONUS);
        stats.getBallisticRoFMult().modifyMult(id, rofMult);
        stats.getEnergyRoFMult().modifyMult(id, rofMult);
        stats.getMaxRecoilMult().modifyMult(id, recoilMult);
        stats.getRecoilPerShotMult().modifyMult(id, recoilMult);
        stats.getRecoilDecayMult().modifyMult(id, recoilMult);
        stats.getBallisticProjectileSpeedMult().modifyPercent(id, PROJECTILE_SPEED_BONUS);
        stats.getEnergyProjectileSpeedMult().modifyPercent(id, PROJECTILE_SPEED_BONUS);
    }

    protected void unmodify(String id, MutableShipStatsAPI stats) {
        stats.getBallisticWeaponRangeBonus().modifyPercent(id, PASSIVE_RANGE_BONUS);
        stats.getEnergyWeaponRangeBonus().modifyPercent(id, PASSIVE_RANGE_BONUS);
        stats.getBeamWeaponRangeBonus().modifyPercent(id, PASSIVE_RANGE_BONUS);
        stats.getBallisticRoFMult().modifyPercent(id, PASSIVE_RANGE_BONUS);
        stats.getEnergyRoFMult().modifyPercent(id, PASSIVE_RANGE_BONUS);
        stats.getMaxRecoilMult().unmodifyMult(id);
        stats.getRecoilPerShotMult().unmodifyMult(id);
        stats.getRecoilDecayMult().unmodifyMult(id);
        stats.getBallisticProjectileSpeedMult().unmodifyPercent(id);
        stats.getEnergyProjectileSpeedMult().unmodifyPercent(id);
        this.playedWindup = false;
    }

    @Override
    public void unapply(MutableShipStatsAPI stats, String id) {
    }

    @Override
    public String getDisplayNameOverride(State state, float effectLevel) {
        return (state == State.IDLE || state == State.COOLDOWN) ? "激光定位系统 - 半功率" : null;
    }

    public static class LidarDishData {
        public WeaponAPI w;
        public float angle;
        public float phase;
        public float count;
    }
}