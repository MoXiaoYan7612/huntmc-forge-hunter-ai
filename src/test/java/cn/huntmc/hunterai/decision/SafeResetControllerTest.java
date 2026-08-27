package cn.huntmc.hunterai.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class SafeResetControllerTest {
  @Test
  void preservesBreakContactThenProgressesThroughCoverRecoveryAndReassessment() {
    assertEquals(
        SafeResetPhase.BREAK_CONTACT,
        next(SafeResetPhase.BREAK_CONTACT, true, false, true, false, false, false));
    assertEquals(
        SafeResetPhase.REACH_IMMEDIATE_COVER,
        next(SafeResetPhase.BREAK_CONTACT, false, false, true, false, false, false));
    assertEquals(
        SafeResetPhase.MOVE_TO_DEEP_COVER,
        next(SafeResetPhase.REACH_IMMEDIATE_COVER, false, true, true, false, false, false));
    assertEquals(
        SafeResetPhase.RECOVER,
        next(SafeResetPhase.MOVE_TO_DEEP_COVER, false, true, true, true, false, false));
    assertEquals(
        SafeResetPhase.REASSESS,
        next(SafeResetPhase.RECOVER, false, true, true, true, true, false));
  }

  @Test
  void timeoutAlwaysMovesToReassessment() {
    assertEquals(
        SafeResetPhase.REASSESS,
        next(SafeResetPhase.REACH_IMMEDIATE_COVER, false, false, true, false, false, true));
  }

  @Test
  void movementFailuresAdvanceBoundedRecoverySequence() {
    assertEquals(1, SafeResetController.nextRecoveryAttempt(0));
    assertEquals(5, SafeResetController.nextRecoveryAttempt(5));
    org.junit.jupiter.api.Assertions.assertTrue(
        SafeResetController.isMovementFailure("path-unavailable"));
    org.junit.jupiter.api.Assertions.assertTrue(
        SafeResetController.isMovementFailure("route-stalled"));
    org.junit.jupiter.api.Assertions.assertTrue(
        SafeResetController.isMovementFailure("degenerate-path"));
    org.junit.jupiter.api.Assertions.assertTrue(
        SafeResetController.isMovementFailure("validated-route-missing"));
    org.junit.jupiter.api.Assertions.assertFalse(
        SafeResetController.isMovementFailure("following"));
  }

  @Test
  void recoveryWithoutThreatAdvancesTowardStrategicAnchor() {
    Vec3 direction = SafeResetController.recoveryDirection(
        Vec3.ZERO, null, new Vec3(20.0D, 0.0D, 0.0D), new UUID(1L, 2L));

    assertEquals(1.0D, direction.x, 1.0E-9D);
    assertEquals(0.0D, direction.z, 1.0E-9D);
  }

  @Test
  void hostileThreatStillTakesPriorityOverStrategicAnchor() {
    Vec3 direction = SafeResetController.recoveryDirection(
        Vec3.ZERO, new Vec3(10.0D, 0.0D, 0.0D), new Vec3(20.0D, 0.0D, 0.0D),
        new UUID(1L, 2L));

    assertEquals(-1.0D, direction.x, 1.0E-9D);
    assertEquals(0.0D, direction.z, 1.0E-9D);
  }

  private static SafeResetPhase next(
      SafeResetPhase current,
      boolean firstTick,
      boolean immediateReached,
      boolean hasDeepCover,
      boolean deepReached,
      boolean recovered,
      boolean timedOut) {
    return SafeResetController.nextPhase(
        current,
        firstTick,
        immediateReached,
        hasDeepCover,
        deepReached,
        recovered,
        timedOut);
  }
}
