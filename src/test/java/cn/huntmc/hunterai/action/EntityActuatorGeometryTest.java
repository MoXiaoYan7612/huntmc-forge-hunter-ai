package cn.huntmc.hunterai.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.huntmc.hunterai.navigation.ValidatedRoute;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class EntityActuatorGeometryTest {
  @Test
  void lineOfFireDistanceUsesTheBoundedSegment() {
    Vec3 start = Vec3.ZERO;
    Vec3 end = new Vec3(10.0D, 0.0D, 0.0D);

    assertEquals(1.0D, EntityActuator.distanceToSegmentSquared(new Vec3(5.0D, 1.0D, 0.0D), start, end), 0.0001D);
    assertEquals(4.0D, EntityActuator.distanceToSegmentSquared(new Vec3(12.0D, 0.0D, 0.0D), start, end), 0.0001D);
  }

  @Test
  void partialNativePathExecutesToItsReachableEnd() {
    Vec3 requested = new Vec3(30.0D, 64.0D, 30.0D);
    Vec3 reachableEnd = new Vec3(18.0D, 64.0D, 24.0D);

    assertEquals(reachableEnd, EntityActuator.executionTarget(requested, reachableEnd, false));
    assertEquals(requested, EntityActuator.executionTarget(requested, reachableEnd, true));
    assertEquals(requested, EntityActuator.executionTarget(requested, null, false));

    ValidatedRoute partial =
        new ValidatedRoute(
            UUID.randomUUID(),
            requested,
            List.of(reachableEnd),
            reachableEnd,
            true,
            18.0D,
            0.0D,
            0.0D,
            "");
    assertFalse(partial.reachesRequestedTarget());
    assertEquals(requested, partial.requestedTarget());
  }

  @Test
  void tinyPartialPathDoesNotMasqueradeAsUsefulProgress() {
    Vec3 current = Vec3.ZERO;
    Vec3 target = new Vec3(30.0D, 0.0D, 0.0D);

    assertFalse(EntityActuator.partialPathMakesProgress(
        current, new Vec3(1.0D, 0.0D, 0.0D), target));
    assertTrue(EntityActuator.partialPathMakesProgress(
        current, new Vec3(8.0D, 0.0D, 0.0D), target));
  }

  @Test
  void aPathEndingAtTheCurrentCellCannotMasqueradeAsVerticalProgress() {
    Vec3 current = new Vec3(150.6D, -46.0D, -710.5D);

    assertFalse(EntityActuator.pathEndOffersExecutableProgress(
        current, new Vec3(150.0D, -46.0D, -711.0D)));
    assertTrue(EntityActuator.pathEndOffersExecutableProgress(
        current, new Vec3(154.0D, -47.0D, -711.0D)));
  }

  @Test
  void movementArrivalAcceptsEdgeOfNodeButNotAnUnreachedFloor() {
    Vec3 current = new Vec3(102.8D, -56.0D, -603.2D);

    assertTrue(EntityActuator.movementTargetReached(
        current, new Vec3(102.5D, -56.0D, -604.0D)));
    assertFalse(EntityActuator.movementTargetReached(
        current, new Vec3(102.5D, -54.0D, -604.0D)));
  }

  @Test
  void localFallbackIsLimitedToNearbyUnvalidatedTacticalMovement() {
    Vec3 current = new Vec3(164.5D, -52.2D, -668.5D);
    Vec3 nearby = new Vec3(167.5D, -52.5D, -669.0D);

    assertTrue(EntityActuator.canUseLocalTacticalFallback(
        "combat-search", null, current, nearby));
    assertTrue(EntityActuator.canUseLocalTacticalFallback(
        "health-recovery", null, current, nearby));
    assertFalse(EntityActuator.canUseLocalTacticalFallback(
        "strategic-plan", null, current, nearby));
    assertFalse(EntityActuator.canUseLocalTacticalFallback(
        "combat-search", UUID.randomUUID(), current, nearby));
    assertFalse(EntityActuator.canUseLocalTacticalFallback(
        "combat-search", null, current, nearby.add(0.0D, 2.0D, 0.0D)));
    assertFalse(EntityActuator.canUseLocalTacticalFallback(
        "combat-search", null, current, nearby.add(8.0D, 0.0D, 0.0D)));
  }

  @Test
  void blockCenteredPathEndCountsAsTheRequestedDestination() {
    Vec3 requested = new Vec3(30.8D, 64.0D, 30.8D);
    Vec3 blockCenteredEnd = new Vec3(30.0D, 64.0D, 30.0D);
    ValidatedRoute route =
        new ValidatedRoute(
            UUID.randomUUID(),
            requested,
            List.of(blockCenteredEnd),
            blockCenteredEnd,
            true,
            8.0D,
            0.0D,
            0.0D,
            "");

    assertTrue(route.reachesRequestedTarget());
  }

  @Test
  void onlyCombatAttentionOwnersPersistAcrossVanillaBodyRotation() {
    assertTrue(EntityActuator.retainsCombatView("combat-controller"));
    assertTrue(EntityActuator.retainsCombatView("damage-reaction"));
    assertFalse(EntityActuator.retainsCombatView("vigilance"));
    assertFalse(EntityActuator.retainsCombatView(null));
  }

  @Test
  void sprintNavigationIncludesThePlayerSprintAcceleration() {
    assertEquals(0.95D, EntityActuator.calibratedSpeed(1.0D, false), 0.0001D);
    assertEquals(1.22D, EntityActuator.calibratedSpeed(1.0D, true), 0.0001D);
    assertEquals(1.24D, EntityActuator.calibratedSpeed(1.03D, true), 0.0001D);
  }

  @Test
  void combatViewUsesTheRestoredOldVersionTrackingBaselineWithoutSnapTurning() {
    assertEquals(16.0F, EntityActuator.combatYawStep("combat-controller", true), 0.0001F);
    assertEquals(9.0F, EntityActuator.combatYawStep("combat-controller", false), 0.0001F);
    assertEquals(12.0F, EntityActuator.combatPitchStep("combat-controller", true), 0.0001F);
    assertEquals(7.0F, EntityActuator.combatPitchStep("combat-controller", false), 0.0001F);
    assertEquals(10.0F, EntityActuator.combatYawStep("combat-controller", true, 6.0D), 0.0001F);
    assertEquals(8.0F, EntityActuator.combatYawStep("combat-controller", false, 6.0D), 0.0001F);
    assertEquals(7.0F, EntityActuator.combatPitchStep("combat-controller", true, 6.0D), 0.0001F);
    assertEquals(16.0F, EntityActuator.combatYawStep("combat-controller", true, 14.0D), 0.0001F);
    assertEquals(14.0F, EntityActuator.combatYawStep("damage-reaction", false), 0.0001F);
    assertEquals(14.0F, EntityActuator.combatYawStep("damage-reaction", false, 3.0D), 0.0001F);
  }

  @Test
  void viewYawIsAlwaysNormalizedAndUsesTheShortestTurn() {
    float recovered = EntityActuator.normalizedYawStep(-7648.53F, 170.0F, 9.0F);
    assertTrue(recovered >= -180.0F && recovered < 180.0F);
    assertEquals(-180.0F, EntityActuator.normalizedYawStep(179.0F, -179.0F, 1.0F), 0.001F);
  }

  @Test
  void acceptedPathRequiresRealDisplacementBeforeReportingProgress() {
    Vec3 origin = Vec3.ZERO;
    Vec3 target = new Vec3(10.0D, 0.0D, 0.0D);

    assertEquals(
        MovementProgressState.PATH_ACCEPTED,
        EntityActuator.classifyMovementProgress(
            origin, new Vec3(0.34D, 0.0D, 0.0D), 10.0D, 9.66D, 100L, 111L));
    assertEquals(
        MovementProgressState.PROGRESSING,
        EntityActuator.classifyMovementProgress(
            origin,
            new Vec3(0.35D, 0.0D, 0.0D),
            origin.distanceTo(target),
            new Vec3(0.35D, 0.0D, 0.0D).distanceTo(target),
            100L,
            111L));
  }

  @Test
  void acceptedPathBecomesStalledAfterTwelveTicksWithoutProgress() {
    Vec3 origin = Vec3.ZERO;

    assertEquals(
        MovementProgressState.PATH_ACCEPTED,
        EntityActuator.classifyMovementProgress(
            origin, new Vec3(0.10D, 0.0D, 0.0D), 10.0D, 9.90D, 100L, 111L));
    assertEquals(
        MovementProgressState.STALLED,
        EntityActuator.classifyMovementProgress(
            origin, new Vec3(0.10D, 0.0D, 0.0D), 10.0D, 9.90D, 100L, 112L));
  }

  @Test
  void lateralMovementCannotMasqueradeAsRouteProgress() {
    Vec3 origin = Vec3.ZERO;
    Vec3 lateral = new Vec3(0.0D, 0.0D, 0.50D);
    Vec3 target = new Vec3(10.0D, 0.0D, 0.0D);

    assertEquals(
        MovementProgressState.STALLED,
        EntityActuator.classifyMovementProgress(
            origin,
            lateral,
            origin.distanceTo(target),
            lateral.distanceTo(target),
            100L,
            112L));
  }
}
