package cn.huntmc.hunterai.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.huntmc.hunterai.profile.HunterProfile;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class CombatControllerPolicyTest {
  private static final Vec3 THREAT = new Vec3(4.0D, 64.0D, 0.0D);

  @Test
  void directDamageBearingLastsLongEnoughToFinishAHumanTurn() {
    assertTrue(CombatController.freshDamageBearing(THREAT, 100L, 100L));
    assertTrue(CombatController.freshDamageBearing(THREAT, 100L, 102L));
    assertTrue(CombatController.freshDamageBearing(THREAT, 100L, 116L));
    assertFalse(CombatController.freshDamageBearing(THREAT, 100L, 117L));
  }

  @Test
  void invalidOrFutureDamageDoesNotPreemptCombat() {
    assertFalse(CombatController.freshDamageBearing(null, 100L, 100L));
    assertFalse(CombatController.freshDamageBearing(THREAT, Long.MIN_VALUE, 100L));
    assertFalse(CombatController.freshDamageBearing(THREAT, 101L, 100L));
  }

  @Test
  void boundedClosingDoesNotNavigateToAnElevatedTargetsExactPosition() {
    Vec3 origin = new Vec3(0.0D, 64.0D, 0.0D);
    Vec3 elevatedThreat = new Vec3(50.0D, 92.0D, 0.0D);
    Vec3 closing = CombatController.boundedClosingTarget(origin, elevatedThreat, 8.0D);

    assertEquals(64.0D, closing.y, 0.0001D);
    assertTrue(closing.x >= 3.0D && closing.x <= 10.0D);
    assertTrue(closing.distanceToSqr(elevatedThreat) > 1.0D);
  }

  @Test
  void visiblePlayerAimAndPriorExchangeCountAsCombatPressure() {
    assertTrue(CombatController.hasImmediateCombatPressure(false, true, 0));
    assertTrue(CombatController.hasImmediateCombatPressure(false, false, 1));
    assertTrue(CombatController.hasImmediateCombatPressure(true, false, 0));
    assertFalse(CombatController.hasImmediateCombatPressure(false, false, 0));
  }

  @Test
  void damageDoesNotForceMediumRangeRifleIntoHipFire() {
    assertTrue(CombatController.shouldUseAds(HunterProfile.RIFLE, 12.0D, false));
    assertTrue(CombatController.shouldUseAds(HunterProfile.SHOTGUN, 10.0D, false));
    assertFalse(CombatController.shouldUseAds(HunterProfile.RIFLE, 3.5D, false));
    assertFalse(CombatController.shouldUseAds(HunterProfile.RIFLE, 12.0D, true));
  }

  @Test
  void mediumAndLongRangeUseTheProvenThreeTickProjectileLead() {
    assertEquals(1.0D / 3.0D, CombatController.leadTicks(HunterProfile.SHOTGUN, 6.0D), 0.0001D);
    assertEquals(20.0D / 18.0D, CombatController.leadTicks(HunterProfile.REVOLVER, 20.0D), 0.0001D);
    assertEquals(40.0D / 18.0D, CombatController.leadTicks(HunterProfile.RIFLE, 40.0D), 0.0001D);
    assertEquals(3.0D, CombatController.leadTicks(HunterProfile.RIFLE, 60.0D), 0.0001D);
    assertEquals(3.0D, CombatController.leadTicks(null, 80.0D), 0.0001D);
  }

  @Test
  void bodyShotsRequireTighterAlignmentAsDistanceIncreases() {
    assertEquals(3.25D, CombatController.fireAimThreshold(HunterProfile.SHOTGUN, 6.0D, false));
    assertEquals(3.50D, CombatController.fireAimThreshold(HunterProfile.RIFLE, 6.0D, false));
    assertEquals(2.25D, CombatController.fireAimThreshold(HunterProfile.RIFLE, 18.0D, true));
    assertEquals(1.75D, CombatController.fireAimThreshold(HunterProfile.REVOLVER, 32.0D, true));
    assertEquals(1.25D, CombatController.fireAimThreshold(HunterProfile.RIFLE, 64.0D, true));
    assertTrue(
        CombatController.fireAimThreshold(HunterProfile.RIFLE, 64.0D, true)
            < CombatController.fireAimThreshold(HunterProfile.RIFLE, 18.0D, true));
  }

  @Test
  void closeAimIsBodyOnlyWhileMediumRangeRevolverBehaviorRemainsAvailable() {
    UUID hunter = UUID.fromString("57cb8b41-ddf7-4aba-b80a-5fb2cc1da763");
    UUID target = UUID.fromString("09ab4045-b9b0-447f-b8b1-4e74e3f175d6");
    assertEquals(
        0.74D,
        CombatController.weaponAdjustedAimHeight(
            HunterProfile.SHOTGUN, 6.0D, 0.88D, hunter, target),
        0.0001D);
    assertTrue(
        CombatController.weaponAdjustedAimHeight(
                HunterProfile.REVOLVER, 20.0D, 0.70D, hunter, target)
            >= 0.70D);
  }

  @Test
  void angularAimOffsetPreservesRangeAndLeavesZeroOffsetUnchanged() {
    Vec3 eye = new Vec3(0.0D, 1.6D, 0.0D);
    Vec3 target = new Vec3(0.0D, 1.2D, 8.0D);
    Vec3 unchanged = CombatController.angularlyOffsetAimPoint(eye, target, 0.0D, 0.0D);
    Vec3 offset = CombatController.angularlyOffsetAimPoint(eye, target, 2.0D, -1.0D);
    assertEquals(target.x, unchanged.x, 0.0001D);
    assertEquals(target.y, unchanged.y, 0.0001D);
    assertEquals(target.z, unchanged.z, 0.0001D);
    assertEquals(eye.distanceTo(target), eye.distanceTo(offset), 0.0001D);
    assertTrue(offset.distanceToSqr(target) > 0.001D);
  }

  @Test
  void healthyCloseHunterFinishesARecentExchangeBeforeRelocating() {
    assertTrue(CombatController.shouldMaintainFinishPressure(
        110L, 120L, 6.0D, 14.0D, 0.80D, 5, false, 1));
    assertFalse(CombatController.shouldMaintainFinishPressure(
        121L, 120L, 6.0D, 14.0D, 0.80D, 5, false, 1));
    assertFalse(CombatController.shouldMaintainFinishPressure(
        110L, 120L, 6.0D, 14.0D, 0.30D, 5, false, 1));
    assertFalse(CombatController.shouldMaintainFinishPressure(
        110L, 120L, 6.0D, 14.0D, 0.80D, 0, false, 1));
  }

  @Test
  void preAimChangesMovementButCannotSuppressImmediateFireOpportunity() {
    CombatController.CombatDecision ordinary = CombatController.decideCombat(
        new CombatController.CombatSituation(false, true, false, false, true, false, false, 6L, false));
    CombatController.CombatDecision preAimed = CombatController.decideCombat(
        new CombatController.CombatSituation(false, true, false, false, true, false, false, 6L, true));

    assertTrue(ordinary.immediateFireOpportunity());
    assertTrue(preAimed.immediateFireOpportunity());
    assertFalse(preAimed.coverMovementUrgent());
    assertFalse(preAimed.repositionMovementUrgent());
  }

  @Test
  void finishWindowSurvivesCoverPreferenceUntilPhysicalConstraintAppears() {
    assertTrue(CombatController.protectedFinishWindow(110L, 120L, 0.60D, 2, false, 1));
    assertTrue(CombatController.decideCombat(
        new CombatController.CombatSituation(false, false, true, false, true, false, false, 10L, true))
        .immediateFireOpportunity());
    assertFalse(CombatController.protectedFinishWindow(110L, 120L, 0.60D, 0, false, 1));
    assertFalse(CombatController.protectedFinishWindow(110L, 120L, 0.60D, 2, true, 1));
  }

  @Test
  void alignedMediumRangeOpportunityCannotBeWithheldForCoverOrPreAim() {
    CombatController.CombatDecision decision = CombatController.decideCombat(
        new CombatController.CombatSituation(false, false, false, true, true, false, false, 8L, true));

    assertTrue(decision.immediateFireOpportunity());
    assertFalse(decision.coverMovementUrgent());
    assertFalse(decision.repositionMovementUrgent());
  }

  @Test
  void ordinaryMovementDoesNotCancelAnIntentionalRepositionState() {
    CombatController.CombatDecision decision = CombatController.decideCombat(
        new CombatController.CombatSituation(false, false, false, false, true, false, false, 2L, false));

    assertFalse(decision.immediateFireOpportunity());
    assertTrue(decision.repositionMovementUrgent());
  }

  @Test
  void playerAndHunterUseTheSameFirstTriggerClock() {
    UUID hunter = UUID.fromString("57cb8b41-ddf7-4aba-b80a-5fb2cc1da763");
    UUID target = UUID.fromString("09ab4045-b9b0-447f-b8b1-4e74e3f175d6");

    long playerTarget = FirstTriggerGate.delayTicks(18.0D, false, hunter, target, 1L);
    long hunterTarget = FirstTriggerGate.delayTicks(18.0D, false, hunter, target, 1L);
    assertEquals(playerTarget, hunterTarget);
  }

  @Test
  void rangeSelectionUsesOnlyAFirstTriggerPauseOrPostBurstPhysicalRecoveryWindow() {
    var ready = new cn.huntmc.hunterai.combat.HunterWeaponAdapter.WeaponReadiness(
        0L,
        true,
        cn.huntmc.hunterai.combat.HunterWeaponAdapter.ManualActionState.READY);
    var coolingDown = new cn.huntmc.hunterai.combat.HunterWeaponAdapter.WeaponReadiness(
        2L,
        true,
        cn.huntmc.hunterai.combat.HunterWeaponAdapter.ManualActionState.READY);

    assertTrue(CombatController.shouldSelectRangeAdvisory(
        false, 0, false, 5, false, 100L, 104L, 100L, ready, false));
    assertFalse(CombatController.shouldSelectRangeAdvisory(
        false, 0, false, 5, false, 100L, 100L, 100L, ready, false));
    assertTrue(CombatController.shouldSelectRangeAdvisory(
        false, 3, false, 5, false, 100L, 100L, 103L, coolingDown, false));
    assertFalse(CombatController.shouldSelectRangeAdvisory(
        false, 3, false, 5, false, 100L, 100L, 103L, coolingDown, true));
    assertFalse(CombatController.shouldSelectRangeAdvisory(
        false, 3, true, 5, false, 100L, 100L, 103L, coolingDown, false));
  }

  @Test
  void distantShotgunUsesAStableBoundedInterceptInsteadOfTargetFeet() {
    Vec3 origin = Vec3.ZERO;
    Vec3 target = new Vec3(72.0D, 0.0D, 0.0D);
    UUID hunter = UUID.fromString("57cb8b41-ddf7-4aba-b80a-5fb2cc1da763");
    Vec3 intercept = CombatController.segmentedClosingTarget(
        origin, target, HunterProfile.SHOTGUN, hunter, 100L);

    assertTrue(intercept.distanceTo(origin) >= 6.0D);
    assertTrue(intercept.distanceTo(origin) <= 10.0001D);
    assertTrue(intercept.distanceToSqr(target) > 1.0D);
    assertEquals(intercept, CombatController.segmentedClosingTarget(
        origin, target, HunterProfile.SHOTGUN, hunter, 100L));
  }
}
