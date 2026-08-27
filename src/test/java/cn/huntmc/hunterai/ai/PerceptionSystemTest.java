package cn.huntmc.hunterai.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class PerceptionSystemTest {
  @Test
  void directAttackerAssistIsBoundedAndUuidSpecific() {
    java.util.UUID attacker = java.util.UUID.randomUUID();
    assertTrue(PerceptionSystem.isDirectThreatCandidate(attacker, attacker, 120L, 120L));
    assertFalse(PerceptionSystem.isDirectThreatCandidate(attacker, attacker, 121L, 120L));
    assertFalse(PerceptionSystem.isDirectThreatCandidate(
        java.util.UUID.randomUUID(), attacker, 120L, 120L));
  }

  @Test
  void headDirectionAcceptsTargetsInFrontAndRejectsTargetsToTheSide() {
    Vec3 headForward = new Vec3(0.0D, 0.0D, 1.0D);

    assertTrue(PerceptionSystem.inFieldOfView(headForward, new Vec3(0.0D, 0.0D, 4.0D)));
    assertFalse(PerceptionSystem.inFieldOfView(headForward, new Vec3(4.0D, 0.0D, 0.0D)));
  }

  @Test
  void peripheralVisionCanAcquireAThreatBeforeTheDedicatedScanReachesIt() {
    Vec3 headForward = new Vec3(0.0D, 0.0D, 1.0D);

    assertTrue(PerceptionSystem.inPeripheralFieldOfView(headForward, new Vec3(4.0D, 0.0D, 1.0D)));
    assertFalse(PerceptionSystem.inPeripheralFieldOfView(headForward, new Vec3(0.0D, 0.0D, -4.0D)));
  }

  @Test
  void facingSweepExpandsOnlyToTheDirectionTheHunterIsActuallyChecking() {
    Vec3 headForward = new Vec3(0.0D, 0.0D, 1.0D);

    assertTrue(
        PerceptionSystem.inHorizontalFacingSweepFieldOfView(headForward, new Vec3(4.0D, 0.0D, 0.0D)));
    assertFalse(
        PerceptionSystem.inHorizontalFacingSweepFieldOfView(
            headForward, new Vec3(0.0D, 0.0D, -4.0D)));
  }

  @Test
  void horizontalAttentionDoesNotRejectTargetsOnHighOrLowGround() {
    Vec3 headForward = new Vec3(0.0D, 0.0D, 1.0D);

    assertTrue(
        PerceptionSystem.inHorizontalFieldOfView(headForward, new Vec3(0.0D, 14.0D, 4.0D)));
    assertTrue(
        PerceptionSystem.inHorizontalFieldOfView(headForward, new Vec3(0.0D, -14.0D, 4.0D)));
    assertFalse(
        PerceptionSystem.inHorizontalFieldOfView(headForward, new Vec3(4.0D, 14.0D, 0.0D)));
  }

  @Test
  void difficultVisualConditionsRequireLongerConfirmation() {
    int clearNearTarget = PerceptionSystem.requiredAcquisitionTicks(6.0D, false, 0.0D, 15, false);
    int darkCrouchedPartialTarget =
        PerceptionSystem.requiredAcquisitionTicks(42.0D, true, 0.22D, 2, true);

    assertEquals(1, clearNearTarget);
    assertEquals(10, darkCrouchedPartialTarget);
    assertTrue(darkCrouchedPartialTarget > clearNearTarget);
  }

  @Test
  void transparentDecorationsDoNotBecomeSolidVisualWalls() {
    assertFalse(PerceptionSystem.blocksVision(false, false, false));
    assertTrue(PerceptionSystem.blocksVision(false, false, true));
    assertTrue(PerceptionSystem.blocksVision(true, false, false));
    assertTrue(PerceptionSystem.blocksVision(false, true, false));
  }

  @Test
  void playerPreferenceDoesNotHideAMuchCloserSecondThreat() {
    double distantPlayer = PerceptionSystem.targetAttentionCost(true, 42.0D, false);
    double closeHunter = PerceptionSystem.targetAttentionCost(false, 8.0D, false);
    double similarlyDistantHunter = PerceptionSystem.targetAttentionCost(false, 44.0D, false);

    assertTrue(closeHunter < distantPlayer);
    assertTrue(distantPlayer < similarlyDistantHunter);
  }
}
