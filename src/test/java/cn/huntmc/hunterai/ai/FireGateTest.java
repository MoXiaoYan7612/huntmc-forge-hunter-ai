package cn.huntmc.hunterai.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.huntmc.hunterai.combat.HunterWeaponAdapter;
import org.junit.jupiter.api.Test;

class FireGateTest {
  private static final HunterWeaponAdapter.WeaponReadiness READY =
      new HunterWeaponAdapter.WeaponReadiness(
          0L, true, HunterWeaponAdapter.ManualActionState.READY);

  @Test
  void legalAlignedShotFiresWithoutAnyRangeInputOrRangeLimit() {
    for (double ignoredRangeMultiplier : new double[] {1.5D, 2.0D, 2.5D}) {
      assertEquals(FireGate.Status.FIRE, FireGate.evaluate(readyInput()).status());
    }
  }

  @Test
  void strategyAndMovementCannotEnterTheFirePermissionContract() {
    assertEquals(FireGate.Status.FIRE, FireGate.evaluate(readyInput()).status());
    assertTrue(FireGate.Input.class.getRecordComponents().length > 0);
    for (var component : FireGate.Input.class.getRecordComponents()) {
      assertTrue(!component.getName().contains("cover")
          && !component.getName().contains("reposition")
          && !component.getName().contains("retreat")
          && !component.getName().contains("range"));
    }
  }

  @Test
  void firstTriggerAndAimStabilityAreTheOnlyHumanTimingGates() {
    FireGate.Decision delayed = FireGate.evaluate(input(
        true, true, false, 5, false, READY, true,
        100L, 101L, 0L, false, 0.0D, 3.5D, 3L, 3L));
    assertEquals(FireGate.BlockReason.FIRST_TRIGGER_DELAY, delayed.reason());

    FireGate.Decision stabilizing = FireGate.evaluate(input(
        true, true, false, 5, false, READY, true,
        100L, 100L, 0L, false, 0.0D, 3.5D, 2L, 3L));
    assertEquals(FireGate.Status.READY, stabilizing.status());
    assertEquals(FireGate.BlockReason.AIM_STABILIZING, stabilizing.reason());
  }

  @Test
  void nativeCooldownBoltReloadAdsAndFriendlyLineRemainAbsoluteBlocks() {
    assertBlocked(FireGate.BlockReason.FRIENDLY_LINE, input(
        true, true, true, 5, false, READY, true,
        100L, 100L, 0L, false, 0.0D, 3.5D, 3L, 3L));
    assertBlocked(FireGate.BlockReason.RELOADING, input(
        true, true, false, 5, true, READY, true,
        100L, 100L, 0L, false, 0.0D, 3.5D, 3L, 3L));
    assertBlocked(FireGate.BlockReason.RELOADING, withReadiness(
        new HunterWeaponAdapter.WeaponReadiness(
            0L, true, HunterWeaponAdapter.ManualActionState.RELOADING)));
    assertBlocked(FireGate.BlockReason.NATIVE_COOLDOWN, withReadiness(
        new HunterWeaponAdapter.WeaponReadiness(
            2L, true, HunterWeaponAdapter.ManualActionState.READY)));
    assertBlocked(FireGate.BlockReason.NEED_BOLT, withReadiness(
        new HunterWeaponAdapter.WeaponReadiness(
            0L, true, HunterWeaponAdapter.ManualActionState.NEED_BOLT)));
    assertBlocked(FireGate.BlockReason.IS_BOLTING, withReadiness(
        new HunterWeaponAdapter.WeaponReadiness(
            0L, true, HunterWeaponAdapter.ManualActionState.BOLTING)));
    assertBlocked(FireGate.BlockReason.ADS_TRANSITION, withReadiness(
        new HunterWeaponAdapter.WeaponReadiness(
            0L, false, HunterWeaponAdapter.ManualActionState.READY)));
  }

  @Test
  void lossOfLosInvalidTargetAndAimErrorStillBlock() {
    assertBlocked(FireGate.BlockReason.NO_LEGAL_VISUAL, input(
        false, true, false, 5, false, READY, true,
        100L, 100L, 0L, false, 0.0D, 3.5D, 3L, 3L));
    assertBlocked(FireGate.BlockReason.TARGET_INVALID, input(
        true, false, false, 5, false, READY, true,
        100L, 100L, 0L, false, 0.0D, 3.5D, 3L, 3L));
    assertBlocked(FireGate.BlockReason.AIM_ALIGNING, input(
        true, true, false, 5, false, READY, true,
        100L, 100L, 0L, false, 3.6D, 3.5D, 3L, 3L));
  }

  @Test
  void onlyResourceFailuresReleaseTheWeaponChannelToRecovery() {
    assertTrue(FireGate.evaluate(input(
        true, true, false, 0, false, READY, true,
        100L, 100L, 0L, false, 0.0D, 3.5D, 3L, 3L))
        .resourceRecoveryOwnsWeapon());
    assertTrue(FireGate.evaluate(input(
        true, true, false, 5, true, READY, true,
        100L, 100L, 0L, false, 0.0D, 3.5D, 3L, 3L))
        .resourceRecoveryOwnsWeapon());
    assertTrue(FireGate.evaluate(withReadiness(
        new HunterWeaponAdapter.WeaponReadiness(
            0L, false, HunterWeaponAdapter.ManualActionState.UNAVAILABLE)))
        .resourceRecoveryOwnsWeapon());
    assertTrue(!FireGate.evaluate(withReadiness(
        new HunterWeaponAdapter.WeaponReadiness(
            2L, true, HunterWeaponAdapter.ManualActionState.READY)))
        .resourceRecoveryOwnsWeapon());
  }

  private static FireGate.Input readyInput() {
    return input(
        true, true, false, 5, false, READY, true,
        100L, 100L, 0L, false, 0.0D, 3.5D, 3L, 3L);
  }

  private static FireGate.Input withReadiness(HunterWeaponAdapter.WeaponReadiness readiness) {
    return input(
        true, true, false, 5, false, readiness, true,
        100L, 100L, 0L, false, 0.0D, 3.5D, 3L, 3L);
  }

  private static FireGate.Input input(
      boolean legalVisual,
      boolean hostileAlive,
      boolean friendlyLineBlocked,
      int ammunition,
      boolean reloading,
      HunterWeaponAdapter.WeaponReadiness readiness,
      boolean ads,
      long now,
      long firstTriggerEligibleAt,
      long nextCadenceAt,
      boolean ambushFocusHold,
      double aimError,
      double aimThreshold,
      long stableTicks,
      long requiredStableTicks) {
    return new FireGate.Input(
        legalVisual,
        hostileAlive,
        friendlyLineBlocked,
        ammunition,
        reloading,
        readiness,
        ads,
        now,
        firstTriggerEligibleAt,
        nextCadenceAt,
        ambushFocusHold,
        aimError,
        aimThreshold,
        stableTicks,
        requiredStableTicks);
  }

  private static void assertBlocked(FireGate.BlockReason reason, FireGate.Input input) {
    FireGate.Decision decision = FireGate.evaluate(input);
    assertEquals(FireGate.Status.BLOCKED, decision.status());
    assertEquals(reason, decision.reason());
  }
}
