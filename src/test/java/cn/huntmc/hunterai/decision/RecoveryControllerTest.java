package cn.huntmc.hunterai.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class RecoveryControllerTest {
  @Test
  void acceptedNavigationWithRealProgressClearsPriorFailures() {
    assertEquals(0, RecoveryController.nextFailedAttempts(2, true, true));
  }

  @Test
  void acceptedNavigationWithoutProgressDoesNotHidePriorFailure() {
    assertEquals(2, RecoveryController.nextFailedAttempts(2, true, false));
  }

  @Test
  void rejectedNavigationAddsARecoveryFailure() {
    assertEquals(3, RecoveryController.nextFailedAttempts(2, false, false));
  }

  @Test
  void acceptedNavigationWithoutRealProgressHasAHardTimeout() {
    org.junit.jupiter.api.Assertions.assertFalse(RecoveryController.noProgressTimedOut(100L, 115L));
    org.junit.jupiter.api.Assertions.assertTrue(RecoveryController.noProgressTimedOut(100L, 116L));
  }

  @Test
  void tacticalDisengageChangesDirectionAfterEachFailedEndpoint() {
    Vec3 position = Vec3.ZERO;
    Vec3 away = new Vec3(1.0D, 0.0D, 0.0D);
    Vec3 side = new Vec3(0.0D, 0.0D, 1.0D);

    Vec3 first = RecoveryController.tacticalDisengageTarget(position, away, side, 0);
    Vec3 second = RecoveryController.tacticalDisengageTarget(position, away, side, 1);
    Vec3 third = RecoveryController.tacticalDisengageTarget(position, away, side, 2);

    assertNotEquals(first, second);
    assertNotEquals(second, third);
    assertNotEquals(first, third);
  }
}
