package cn.huntmc.hunterai.combat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.huntmc.hunterai.profile.HunterProfile;
import org.junit.jupiter.api.Test;

class HunterWeaponAdapterRangeTest {
  @Test
  void manualActionCyclesAsSoonAsPhysicalCooldownEnds() {
    assertFalse(HunterWeaponAdapter.shouldCycleManualAction(
        true, false, 4, false, false, 1L));
    assertTrue(HunterWeaponAdapter.shouldCycleManualAction(
        true, false, 4, false, false, 0L));
  }

  @Test
  void manualActionMaintenanceDoesNotInterruptInvalidWeaponStates() {
    assertFalse(HunterWeaponAdapter.shouldCycleManualAction(
        false, false, 4, false, false, 0L));
    assertFalse(HunterWeaponAdapter.shouldCycleManualAction(
        true, true, 4, false, false, 0L));
    assertFalse(HunterWeaponAdapter.shouldCycleManualAction(
        true, false, 0, false, false, 0L));
    assertFalse(HunterWeaponAdapter.shouldCycleManualAction(
        true, false, 4, true, false, 0L));
    assertFalse(HunterWeaponAdapter.shouldCycleManualAction(
        true, false, 4, false, true, 0L));
  }

  @Test
  void rifleBeatsShotgunAtLongRange() {
    assertTrue(
        HunterWeaponAdapter.rangeFitScore(HunterProfile.RIFLE, 60.0D)
            > HunterWeaponAdapter.rangeFitScore(HunterProfile.SHOTGUN, 60.0D));
  }

  @Test
  void shotgunBeatsRifleInsideItsCloseRangeAdvantage() {
    assertTrue(
        HunterWeaponAdapter.rangeFitScore(HunterProfile.SHOTGUN, 10.0D)
            > HunterWeaponAdapter.rangeFitScore(HunterProfile.RIFLE, 10.0D));
  }

  @Test
  void scoreMarginAndCooldownOnlyPreventThrashingBetweenUsableWeapons() {
    assertFalse(HunterWeaponAdapter.shouldSwitchForRange(
        true, true, 0.70D, true, 0.79D, false));
    assertTrue(HunterWeaponAdapter.shouldSwitchForRange(
        true, false, 0.20D, true, 0.24D, false));
    assertTrue(HunterWeaponAdapter.shouldSwitchForRange(
        true, false, 0.20D, true, 0.80D, true));
  }

  @Test
  void unusableCandidateNeverCausesRangeSwitch() {
    assertFalse(HunterWeaponAdapter.shouldSwitchForRange(
        true, false, 0.20D, false, 0.90D, false));
    assertFalse(HunterWeaponAdapter.shouldSwitchForRange(
        false, false, -1.0D, false, 0.90D, false));
  }
}
