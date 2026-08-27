package cn.huntmc.hunterai.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class SightFocusControllerTest {
  private static final UUID HUNTER = UUID.fromString("00000000-0000-0000-0000-000000000101");
  private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000202");

  @Test
  void concealedUndiscoveredTargetBuildsFocusThenCommits() {
    SightFocusController controller = new SightFocusController();
    SightFocusController.Decision first = controller.tick(
        HUNTER, PLAYER, 24.0D, 2.0D, true, false, false, false, 100L);
    assertEquals(SightFocusController.State.FOCUSING, first.state());
    assertTrue(first.holdFire());

    SightFocusController.Decision last = first;
    for (long now = 101L; now <= 140L; now++)
      last = controller.tick(HUNTER, PLAYER, 24.0D, 2.0D, true, false, false, false, now);
    assertEquals(SightFocusController.State.COMMITTED, last.state());
    assertFalse(last.holdFire());
    assertEquals(1.0D, last.quality(), 0.0001D);
  }

  @Test
  void playerAimOrDamageImmediatelyEndsTheWaitingGate() {
    SightFocusController controller = new SightFocusController();
    controller.tick(HUNTER, PLAYER, 24.0D, 2.0D, true, false, false, false, 100L);
    SightFocusController.Decision discovered = controller.tick(
        HUNTER, PLAYER, 24.0D, 2.0D, true, false, true, false, 101L);
    assertEquals(SightFocusController.State.DISCOVERED, discovered.state());
    assertTrue(discovered.discovered());
    assertFalse(discovered.holdFire());

    SightFocusController.Decision damaged = controller.tick(
        HUNTER, PLAYER, 24.0D, 2.0D, true, false, false, true, 102L);
    assertEquals("direct-damage", damaged.reason());
  }

  @Test
  void discoveredContactNeverReturnsToAmbushWaiting() {
    SightFocusController controller = new SightFocusController();
    controller.tick(HUNTER, PLAYER, 24.0D, 2.0D, true, false, false, false, 100L);
    controller.tick(HUNTER, PLAYER, 24.0D, 2.0D, true, false, true, false, 101L);

    SightFocusController.Decision afterPlayerLooksAway = controller.tick(
        HUNTER, PLAYER, 24.0D, 2.0D, true, false, false, false, 102L);

    assertEquals(SightFocusController.State.DISCOVERED, afterPlayerLooksAway.state());
    assertTrue(afterPlayerLooksAway.discovered());
    assertFalse(afterPlayerLooksAway.holdFire());
  }

  @Test
  void briefOcclusionClearsAimBonusButRetainsDiscovery() {
    SightFocusController controller = new SightFocusController();
    controller.tick(HUNTER, PLAYER, 24.0D, 2.0D, true, false, false, false, 100L);
    controller.tick(HUNTER, PLAYER, 24.0D, 2.0D, true, false, true, false, 101L);

    controller.loseVisual();
    SightFocusController.Decision reacquired = controller.tick(
        HUNTER, PLAYER, 24.0D, 2.0D, true, false, false, false, 103L);

    assertEquals(SightFocusController.State.DISCOVERED, reacquired.state());
    assertEquals(0.0D, reacquired.quality(), 0.0001D);
    assertFalse(reacquired.holdFire());
  }

  @Test
  void closeOrExposedTargetsNeverCreateAnAmbushDelay() {
    SightFocusController controller = new SightFocusController();
    assertFalse(controller.tick(
        HUNTER, PLAYER, 6.0D, 1.0D, true, false, false, false, 1L).holdFire());
    assertFalse(controller.tick(
        HUNTER, PLAYER, 24.0D, 1.0D, false, false, false, false, 2L).holdFire());
  }

  @Test
  void fullFocusRaisesOnlyABoundedSubsetTowardTheHead() {
    boolean foundFocusedContact = false;
    for (int index = 1; index <= 1000; index++) {
      UUID target = new UUID(0L, index);
      double height = SightFocusController.focusedAimHeight(HUNTER, target, 0.70D, 1.0D);
      assertTrue(height <= 0.88D);
      if (height > 0.70D) foundFocusedContact = true;
    }
    assertTrue(foundFocusedContact);
    assertEquals(0.70D, SightFocusController.focusedAimHeight(HUNTER, PLAYER, 0.70D, 0.0D));
  }
}
