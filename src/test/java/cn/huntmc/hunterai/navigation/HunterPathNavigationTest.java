package cn.huntmc.hunterai.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HunterPathNavigationTest {
  @Test
  void ladderNodesAdvanceOnlyAfterTheHunterActuallyReachesTheRung() {
    assertFalse(HunterPathNavigation.reachedLadderWaypoint(0.10D, 0.45D, 0.45D));
    assertTrue(HunterPathNavigation.reachedLadderWaypoint(0.10D, 0.20D, 0.45D));
    assertFalse(HunterPathNavigation.reachedLadderWaypoint(0.50D, 0.10D, 0.45D));
  }

  @Test
  void climbDirectionDoesNotInventVerticalMovementForALevelExit() {
    assertEquals(
        HunterPathNavigation.ClimbDirection.UP, HunterPathNavigation.climbDirection(64.2D, 65.0D));
    assertEquals(
        HunterPathNavigation.ClimbDirection.DOWN, HunterPathNavigation.climbDirection(64.8D, 64.0D));
    assertEquals(
        HunterPathNavigation.ClimbDirection.NONE, HunterPathNavigation.climbDirection(64.0D, 64.03D));
  }
}
