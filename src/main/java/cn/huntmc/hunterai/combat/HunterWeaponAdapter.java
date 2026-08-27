package cn.huntmc.hunterai.combat;

import cn.huntmc.hunterai.config.HunterAiConfig;
import cn.huntmc.hunterai.entity.HunterEntity;
import cn.huntmc.hunterai.profile.HunterProfile;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ShootResult;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.gun.FireMode;
import com.tacz.guns.init.ModItems;
import com.tacz.guns.resource.pojo.data.gun.Bolt;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/** Direct TaCZ 1.1.8-hotfix integration for a non-player LivingEntity. */
public final class HunterWeaponAdapter {
  private static final long RANGE_SWITCH_COOLDOWN_TICKS = 20L;
  private static final double RANGE_SWITCH_SCORE_MARGIN = 0.12D;
  private ResourceLocation equippedGunId;
  private List<ResourceLocation> cachedLoadoutIds = List.of();
  private final List<ItemStack> cachedLoadoutStacks = new ArrayList<>();
  private int selectedLoadoutIndex;
  private boolean unavailable;
  private boolean aiming;
  private String lastAction = "not-equipped";
  private String lastShootResult = "not-fired";
  private String lastShootDiagnostic = "nativeShoot=not-invoked";
  private long lastRangeSwitchAt = Long.MIN_VALUE;

  public boolean ensureEquipped(HunterEntity hunter) {
    syncAssignedLoadout(hunter);
    ResourceLocation gunId = selectedGunId(hunter);
    if (gunId != null
        && gunId.equals(equippedGunId)
        && gunId.equals(gunId(hunter.getMainHandItem()))) return !unavailable;
    if (gunId == null) {
      hunter.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
      equippedGunId = null;
      unavailable = true;
      remember("equip failed: assigned HuntMC loadout has no remaining weapon");
      return false;
    }
    if (TimelessAPI.getCommonGunIndex(gunId).isEmpty()) {
      unavailable = true;
      remember("equip failed: configured TaCZ gun is unavailable");
      return false;
    }
    ItemStack stack = selectedStack(gunId);
    if (stack.isEmpty()) {
      unavailable = true;
      remember("equip failed: TaCZ gun item API is unavailable");
      return false;
    }
    return equip(hunter, gunId, stack, "equipped ");
  }

  public boolean tryFire(HunterEntity hunter) {
    if (unavailable || !ensureEquipped(hunter)) {
      lastShootResult = "not-equipped";
      return false;
    }
    IGunOperator operator = IGunOperator.fromLivingEntity(hunter);
    // Firing must not silently force ADS. HunterCoordinator chooses aimed shots only from
    // stable positions; close-range and evasive shots remain legal hip fire.
    ShootResult result = operator.shoot(() -> hunter.getXRot(), () -> hunter.getYRot());
    lastShootResult = result.name();
    lastShootDiagnostic =
        "nativeShoot=" + result.name()
            + ";gun=" + currentGunId(hunter)
            + ";entityId=" + hunter.getId()
            + ";gameTime=" + hunter.level().getGameTime();
    if (result == ShootResult.NEED_BOLT) {
      operator.bolt();
      remember("shoot " + result.name() + "; bolt requested");
    } else if (result == ShootResult.NO_AMMO) {
      EmptyWeaponAction recovery = recoverEmptyWeapon(hunter);
      remember(
          "shoot "
              + result.name()
              + (recovery == EmptyWeaponAction.SWITCHED
                  ? "; switched to " + equippedGunId
                  : recovery == EmptyWeaponAction.RELOAD_REQUESTED
                      ? "; reload requested"
                      : "; no recovery available"));
    } else {
      remember("shoot " + result.name());
    }
    return result == ShootResult.SUCCESS;
  }

  public EmptyWeaponAction recoverEmptyWeapon(HunterEntity hunter) {
    IGun gun = IGun.getIGunOrNull(hunter.getMainHandItem());
    if (gun == null) {
      remember("reload skipped: main hand is not a TaCZ gun");
      return EmptyWeaponAction.NONE;
    }
    if (gun.getCurrentAmmoCount(hunter.getMainHandItem()) > 0) return EmptyWeaponAction.NONE;
    if (switchToLoadedAlternative(hunter)) return EmptyWeaponAction.SWITCHED;
    IGunOperator operator = IGunOperator.fromLivingEntity(hunter);
    if (!operator.getSynReloadState().getStateType().isReloading()) {
      operator.reload();
      remember("reload requested: magazine empty");
    }
    return EmptyWeaponAction.RELOAD_REQUESTED;
  }

  /**
   * Selects the loaded assigned weapon whose useful range best matches the current legal visual.
   * The actuator is the only caller allowed to apply the returned switch; combat policy merely
   * requests the command. A score margin and cooldown keep a target near a range boundary from
   * making the Hunter swap weapons every tick.
   */
  public RangeSelection selectForDistance(HunterEntity hunter, double distance) {
    if (!Double.isFinite(distance) || distance < 0.0D)
      return new RangeSelection(RangeSelectionStatus.NO_LOADED_ALTERNATIVE,
          hunter.profile(), currentGunId(hunter), 0.0D,
          "invalid-distance");
    if (!ensureEquipped(hunter))
      return new RangeSelection(RangeSelectionStatus.EMPTY, hunter.profile(), null, 0.0D,
          "weapon-unavailable");

    syncAssignedLoadout(hunter);
    ResourceLocation currentId = currentGunId(hunter);
    HunterProfile currentProfile = HunterProfile.fromGunId(currentId);
    ItemStack currentStack = hunter.getMainHandItem();
    IGun currentGun = IGun.getIGunOrNull(currentStack);
    boolean currentLoaded = currentGun != null && currentGun.getCurrentAmmoCount(currentStack) > 0;
    double currentScore = currentLoaded ? rangeFitScore(currentProfile, distance) : -1.0D;

    if (cachedLoadoutIds.size() < 2) {
      remember("range selection retained " + currentId + ": single weapon");
      return new RangeSelection(
          currentLoaded
              ? RangeSelectionStatus.NO_LOADED_ALTERNATIVE
              : isReloading(hunter) ? RangeSelectionStatus.RELOADING : RangeSelectionStatus.EMPTY,
          currentProfile, currentId, Math.max(0.0D, currentScore),
          "single-weapon");
    }

    int bestIndex = Math.max(0, cachedLoadoutIds.indexOf(currentId));
    ResourceLocation bestId = currentId;
    HunterProfile bestProfile = currentProfile;
    double bestScore = currentScore;
    boolean foundLoaded = currentLoaded;
    for (int index = 0; index < cachedLoadoutIds.size(); index++) {
      ResourceLocation candidateId = cachedLoadoutIds.get(index);
      ItemStack candidate = cachedLoadoutStacks.get(index);
      if (candidate.isEmpty()) {
        candidate = createGunStack(candidateId);
        cachedLoadoutStacks.set(index, candidate);
      }
      IGun candidateGun = IGun.getIGunOrNull(candidate);
      if (candidateGun == null || candidateGun.getCurrentAmmoCount(candidate) <= 0) continue;
      HunterProfile candidateProfile = HunterProfile.fromGunId(candidateId);
      double candidateScore = rangeFitScore(candidateProfile, distance);
      if (!foundLoaded || candidateScore > bestScore + 1.0E-6D) {
        bestIndex = index;
        bestId = candidateId;
        bestProfile = candidateProfile;
        bestScore = candidateScore;
        foundLoaded = true;
      }
    }

    if (!foundLoaded) {
      remember("range selection retained " + currentId + ": no-loaded-alternative");
      return new RangeSelection(
          isReloading(hunter) ? RangeSelectionStatus.RELOADING : RangeSelectionStatus.EMPTY,
          currentProfile, currentId, Math.max(0.0D, currentScore), "no-loaded-alternative");
    }

    boolean different = bestId != null && !bestId.equals(currentId);
    long now = hunter.level().getGameTime();
    boolean coolingDown = lastRangeSwitchAt != Long.MIN_VALUE
        && now >= lastRangeSwitchAt
        && now - lastRangeSwitchAt < RANGE_SWITCH_COOLDOWN_TICKS;
    boolean switchAllowed = shouldSwitchForRange(
        currentLoaded, currentLoaded, currentScore, foundLoaded, bestScore, coolingDown);
    if (!different || !switchAllowed) {
      String selectionReason = coolingDown && different
          ? "switch-cooldown"
          : different ? "insufficient-range-advantage" : "current-best-range";
      remember("range selection retained " + currentId + ": " + selectionReason);
      return new RangeSelection(
          currentLoaded ? RangeSelectionStatus.CURRENT_READY : RangeSelectionStatus.EMPTY,
          currentProfile, currentId, Math.max(0.0D, currentScore),
          selectionReason);
    }

    saveEquippedStack(hunter);
    selectedLoadoutIndex = bestIndex;
    ItemStack selected = cachedLoadoutStacks.get(bestIndex);
    if (!equip(hunter, bestId, selected, "range-switched to "))
      return new RangeSelection(
          currentLoaded ? RangeSelectionStatus.CURRENT_READY : RangeSelectionStatus.EMPTY,
          currentProfile, currentId,
          Math.max(0.0D, currentScore), "range-switch-failed");
    lastRangeSwitchAt = now;
    return new RangeSelection(
        RangeSelectionStatus.SWITCHED_BETTER,
        bestProfile, bestId, Math.max(0.0D, bestScore), "better-range-envelope");
  }

  /** Pure weapon-range utility used by production selection and deterministic tests. */
  public static double rangeFitScore(HunterProfile profile, double distance) {
    if (profile == null || !Double.isFinite(distance) || distance < 0.0D) return 0.0D;
    double maximum = Math.max(1.0D, profile.maximumRange());
    double base = Math.max(0.0D, 1.0D - Math.abs(distance - profile.preferredRange()) / maximum);
    if (distance <= maximum) return base;
    double overRange = (distance - maximum) / Math.max(1.0D, maximum * 0.75D);
    return base * Math.max(0.0D, 1.0D - overRange) * 0.55D;
  }

  public static boolean shouldSwitchForRange(
      boolean currentLoaded,
      boolean currentUsable,
      double currentScore,
      boolean candidateUsable,
      double candidateScore,
      boolean coolingDown) {
    if (!candidateUsable) return false;
    if (candidateUsable && !currentUsable) return true;
    if (coolingDown) return false;
    return !currentLoaded
        || candidateScore >= currentScore + RANGE_SWITCH_SCORE_MARGIN;
  }

  public boolean isReloading(HunterEntity hunter) {
    return IGunOperator.fromLivingEntity(hunter).getSynReloadState().getStateType().isReloading();
  }

  /**
   * TaCZ exposes the authoritative server-side cooldown through the living gun operator. The
   * value is milliseconds, so callers can schedule against the actual remaining lock rather than
   * only the gun's nominal RPM.
   */
  public long shootCooldownRemainingTicks(HunterEntity hunter) {
    long remainingMillis = Math.max(0L, IGunOperator.fromLivingEntity(hunter).getSynShootCoolDown());
    return (remainingMillis + 49L) / 50L;
  }

  /** Authoritative TaCZ readiness snapshot captured on the server thread before combat policy. */
  public WeaponReadiness readiness(HunterEntity hunter) {
    // Snapshot collection must stay read-only. EntityActuator owns equipment mutation and will
    // equip an assigned gun when it applies the frame; a missing gun is unavailable for this tick.
    ItemStack stack = hunter.getMainHandItem();
    IGun gun = IGun.getIGunOrNull(stack);
    if (gun == null) return new WeaponReadiness(0L, false, ManualActionState.UNAVAILABLE);
    IGunOperator operator = IGunOperator.fromLivingEntity(hunter);
    boolean reloading = operator.getSynReloadState().getStateType().isReloading();
    boolean bolting = operator.getSynIsBolting();
    boolean manualAction = TimelessAPI.getCommonGunIndex(gun.getGunId(stack))
        .map(index -> index.getGunData().getBolt() == Bolt.MANUAL_ACTION)
        .orElse(false);
    ManualActionState manualState = reloading
        ? ManualActionState.RELOADING
        : bolting
            ? ManualActionState.BOLTING
            : manualAction && !gun.hasBulletInBarrel(stack) && gun.getCurrentAmmoCount(stack) > 0
                ? ManualActionState.NEED_BOLT
                : ManualActionState.READY;
    return new WeaponReadiness(
        shootCooldownRemainingTicks(hunter), isAimReady(hunter), manualState);
  }

  /**
   * Starts a manual-action cycle as soon as TaCZ's physical shot cooldown permits it.
   *
   * <p>A player presses the bolt action while recovering from the shot. Previously a Hunter did
   * not call {@link IGunOperator#bolt()} until its AI cadence timer requested the next shot, so the
   * cadence delay and the complete bolt animation ran serially. This maintenance action overlaps
   * them without bypassing either TaCZ cooldown or animation.
   */
  public boolean cycleManualActionIfReady(HunterEntity hunter) {
    ItemStack stack = hunter.getMainHandItem();
    IGun gun = IGun.getIGunOrNull(stack);
    if (gun == null) return false;
    IGunOperator operator = IGunOperator.fromLivingEntity(hunter);
    boolean manualAction = TimelessAPI.getCommonGunIndex(gun.getGunId(stack))
        .map(index -> index.getGunData().getBolt() == Bolt.MANUAL_ACTION)
        .orElse(false);
    if (!shouldCycleManualAction(
        manualAction,
        gun.hasBulletInBarrel(stack),
        gun.getCurrentAmmoCount(stack),
        operator.getSynReloadState().getStateType().isReloading(),
        operator.getSynIsBolting(),
        shootCooldownRemainingTicks(hunter))) return false;
    operator.bolt();
    boolean started = operator.getSynIsBolting();
    if (started) remember("manual action started after shot cooldown");
    return started;
  }

  /** Pure guard shared with regression tests; the TaCZ operator remains the final authority. */
  public static boolean shouldCycleManualAction(
      boolean manualAction,
      boolean bulletInBarrel,
      int ammunition,
      boolean reloading,
      boolean bolting,
      long shootCooldownRemainingTicks) {
    return manualAction
        && !bulletInBarrel
        && ammunition > 0
        && !reloading
        && !bolting
        && shootCooldownRemainingTicks <= 0L;
  }

  public void lowerWeapon(HunterEntity hunter) {
    if (!unavailable && aiming) {
      IGunOperator.fromLivingEntity(hunter).aim(false);
      aiming = false;
      remember("weapon lowered");
    }
  }

  public void raiseWeapon(HunterEntity hunter) {
    if (!unavailable && ensureEquipped(hunter) && !aiming) {
      IGunOperator.fromLivingEntity(hunter).aim(true);
      aiming = true;
      remember("weapon aimed");
    }
  }

  /** True only after TaCZ has synchronised a meaningful portion of the weapon's ADS transition. */
  public boolean isAimReady(HunterEntity hunter) {
    IGunOperator operator = IGunOperator.fromLivingEntity(hunter);
    return operator.getSynIsAiming() && operator.getSynAimingProgress() >= 0.72F;
  }

  public String lastAction() {
    return lastAction;
  }

  public String lastShootResult() {
    return lastShootResult;
  }

  /** Diagnoses TaCZ's authoritative shoot path without replaying or replacing its sound event. */
  public String lastShootDiagnostic() {
    return lastShootDiagnostic;
  }

  public String debugStatus(HunterEntity hunter) {
    if (unavailable) {
      return "unavailable=true lastShoot=" + lastShootResult + " lastAction=" + lastAction;
    }
    IGun gun = IGun.getIGunOrNull(hunter.getMainHandItem());
    int ammo = gun == null ? -1 : gun.getCurrentAmmoCount(hunter.getMainHandItem());
    return "ammo="
        + ammo
        + " gun="
        + currentGunId(hunter)
        + " loadoutIndex="
        + selectedLoadoutIndex
        + " aim="
        + aiming
        + " reload="
        + isReloading(hunter)
        + " lastShoot="
        + lastShootResult
        + " shootDiagnostic="
        + lastShootDiagnostic
        + " lastAction="
        + lastAction;
  }

  /** Ammunition is part of tactical decision making as well as TaCZ's actual reload behavior. */
  public AmmoState ammoState(HunterEntity hunter) {
    IGun gun = IGun.getIGunOrNull(hunter.getMainHandItem());
    int ammunition = gun == null ? 0 : Math.max(0, gun.getCurrentAmmoCount(hunter.getMainHandItem()));
    ResourceLocation gunId = currentGunId(hunter);
    int capacity =
        gunId == null
            ? 1
            : TimelessAPI.getCommonGunIndex(gunId)
                .map(index -> Math.max(1, index.getGunData().getAmmoAmount()))
                .orElse(1);
    return new AmmoState(ammunition, capacity, isReloading(hunter));
  }

  /** Returns the TaCZ server cooldown for the current fire mode, attachment cache, and heat state. */
  public FireRate fireRate(HunterEntity hunter) {
    ItemStack gunStack = hunter.getMainHandItem();
    IGun gun = IGun.getIGunOrNull(gunStack);
    if (gun == null) return new FireRate(0, 0L, false);
    FireMode fireMode = gun.getFireMode(gunStack);
    return TimelessAPI.getCommonGunIndex(gun.getGunId(gunStack))
        .map(
            index -> {
              long cooldownMillis =
                  fireMode == FireMode.BURST
                      ? Math.max(1L, (long) (index.getGunData().getBurstData().getMinInterval() * 1_000.0F))
                      : Math.max(1L, index.getGunData().getShootInterval(hunter, fireMode, gunStack));
              int effectiveRpm = Math.max(1, (int) Math.round(60_000.0D / cooldownMillis));
              long minimumIntervalTicks = Math.max(1L, (cooldownMillis + 49L) / 50L);
              return new FireRate(effectiveRpm, minimumIntervalTicks, true);
            })
        .orElse(new FireRate(0, 0L, false));
  }

  public record AmmoState(int ammunition, int capacity, boolean reloading) {
    public double readiness() {
      if (reloading) return 0.10D;
      if (ammunition <= 0) return 0.0D;
      return Math.max(0.35D, Math.min(1.0D, ammunition / (double) Math.max(1, capacity)));
    }
  }

  public record FireRate(int roundsPerMinute, long minimumIntervalTicks, boolean available) {}

  public record RangeSelection(
      RangeSelectionStatus status,
      HunterProfile profile,
      ResourceLocation gunId,
      double score,
      String reason) {
    public boolean switched() {
      return status == RangeSelectionStatus.SWITCHED_BETTER;
    }

    public boolean readyToFire() {
      return status == RangeSelectionStatus.CURRENT_READY
          || status == RangeSelectionStatus.SWITCHED_BETTER
          || status == RangeSelectionStatus.NO_LOADED_ALTERNATIVE && gunId != null;
    }
  }

  public enum RangeSelectionStatus {
    CURRENT_READY,
    SWITCHED_BETTER,
    NO_LOADED_ALTERNATIVE,
    RELOADING,
    EMPTY
  }

  public record WeaponReadiness(
      long nativeCooldownTicks, boolean adsReady, ManualActionState manualActionState) {}

  public enum ManualActionState {
    READY,
    NEED_BOLT,
    BOLTING,
    RELOADING,
    UNAVAILABLE
  }

  public enum EmptyWeaponAction {
    NONE,
    SWITCHED,
    RELOAD_REQUESTED
  }

  private void remember(String action) {
    lastAction = action;
  }

  private ResourceLocation selectedGunId(HunterEntity hunter) {
    if (hunter.matchLoadoutAssigned()) {
      if (cachedLoadoutIds.isEmpty()) return null;
      selectedLoadoutIndex = Math.max(0, Math.min(selectedLoadoutIndex, cachedLoadoutIds.size() - 1));
      return cachedLoadoutIds.get(selectedLoadoutIndex);
    }
    return HunterAiConfig.gunId(hunter.profile());
  }

  private ResourceLocation currentGunId(HunterEntity hunter) {
    IGun gun = IGun.getIGunOrNull(hunter.getMainHandItem());
    return gun == null ? selectedGunId(hunter) : gun.getGunId(hunter.getMainHandItem());
  }

  private void syncAssignedLoadout(HunterEntity hunter) {
    if (!hunter.matchLoadoutAssigned()) {
      cachedLoadoutIds = List.of();
      cachedLoadoutStacks.clear();
      selectedLoadoutIndex = 0;
      return;
    }
    List<ResourceLocation> desired = hunter.matchWeaponIds();
    if (desired.equals(cachedLoadoutIds)) return;
    saveEquippedStack(hunter);
    List<ResourceLocation> previousIds = cachedLoadoutIds;
    List<ItemStack> previousStacks = new ArrayList<>(cachedLoadoutStacks);
    cachedLoadoutIds = List.copyOf(desired);
    cachedLoadoutStacks.clear();
    for (ResourceLocation desiredId : desired) {
      int previousIndex = previousIds.indexOf(desiredId);
      cachedLoadoutStacks.add(
          previousIndex >= 0 && previousIndex < previousStacks.size()
              ? previousStacks.get(previousIndex)
              : ItemStack.EMPTY);
    }
    int retainedIndex = equippedGunId == null ? -1 : desired.indexOf(equippedGunId);
    selectedLoadoutIndex = retainedIndex >= 0 ? retainedIndex : 0;
  }

  private ItemStack selectedStack(ResourceLocation gunId) {
    if (cachedLoadoutIds.isEmpty()) return createGunStack(gunId);
    int index = cachedLoadoutIds.indexOf(gunId);
    if (index < 0) return ItemStack.EMPTY;
    ItemStack stack = cachedLoadoutStacks.get(index);
    if (!stack.isEmpty()) return stack;
    stack = createGunStack(gunId);
    cachedLoadoutStacks.set(index, stack);
    return stack;
  }

  private boolean switchToLoadedAlternative(HunterEntity hunter) {
    syncAssignedLoadout(hunter);
    if (cachedLoadoutIds.size() < 2) return false;
    saveEquippedStack(hunter);
    for (int offset = 1; offset < cachedLoadoutIds.size(); offset++) {
      int candidateIndex = (selectedLoadoutIndex + offset) % cachedLoadoutIds.size();
      ResourceLocation candidateId = cachedLoadoutIds.get(candidateIndex);
      ItemStack candidate = cachedLoadoutStacks.get(candidateIndex);
      if (candidate.isEmpty()) {
        candidate = createGunStack(candidateId);
        cachedLoadoutStacks.set(candidateIndex, candidate);
      }
      IGun candidateGun = IGun.getIGunOrNull(candidate);
      if (candidateGun == null || candidateGun.getCurrentAmmoCount(candidate) <= 0) continue;
      selectedLoadoutIndex = candidateIndex;
      return equip(hunter, candidateId, candidate, "switched to ");
    }
    return false;
  }

  private void saveEquippedStack(HunterEntity hunter) {
    if (equippedGunId == null || cachedLoadoutIds.isEmpty()) return;
    int index = cachedLoadoutIds.indexOf(equippedGunId);
    if (index < 0 || index >= cachedLoadoutStacks.size()) return;
    ItemStack held = hunter.getMainHandItem();
    if (equippedGunId.equals(gunId(held))) cachedLoadoutStacks.set(index, held);
  }

  private boolean equip(
      HunterEntity hunter, ResourceLocation gunId, ItemStack stack, String actionPrefix) {
    IGunOperator operator = IGunOperator.fromLivingEntity(hunter);
    if (IGun.getIGunOrNull(hunter.getMainHandItem()) != null) {
      operator.cancelReload();
      operator.aim(false);
    }
    hunter.setItemSlot(EquipmentSlot.MAINHAND, stack);
    operator.initialData();
    operator.draw(hunter::getMainHandItem);
    equippedGunId = gunId;
    unavailable = false;
    aiming = false;
    if (hunter.matchLoadoutAssigned()) hunter.setProfile(HunterProfile.fromGunId(gunId));
    remember(actionPrefix + gunId);
    return true;
  }

  private static ItemStack createGunStack(ResourceLocation gunId) {
    ItemStack stack = new ItemStack(ModItems.MODERN_KINETIC_GUN.get());
    IGun gun = IGun.getIGunOrNull(stack);
    if (gun == null) return ItemStack.EMPTY;
    gun.setGunId(stack, gunId);
    TimelessAPI.getCommonGunIndex(gunId)
        .ifPresent(
            index -> {
              gun.setCurrentAmmoCount(stack, index.getGunData().getAmmoAmount());
              gun.setBulletInBarrel(stack, true);
            });
    return stack;
  }

  private static ResourceLocation gunId(ItemStack stack) {
    IGun gun = IGun.getIGunOrNull(stack);
    return gun == null ? null : gun.getGunId(stack);
  }
}
