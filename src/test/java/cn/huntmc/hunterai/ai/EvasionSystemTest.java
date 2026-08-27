package cn.huntmc.hunterai.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class EvasionSystemTest {
  @Test
  void createsShortBurstThenRespectsItsOwnCooldown() {
    EvasionSystem system = new EvasionSystem();
    EvasionSystem.Step first = system.tick(100L, 0.80D, true, new Random(44L));

    assertTrue(first.active());
    assertTrue(first.started());
    assertTrue(system.tick(101L, 0.80D, true, new Random(99L)).active());
    assertFalse(system.tick(first.activeUntil() + 1L, 0.10D, true, new Random(2L)).active());
    assertFalse(system.tick(first.activeUntil() + 1L, 0.90D, true, new Random(3L)).active());
  }

  @Test
  void everyMovingDodgeHasEnoughTravelToClearAPlayerAimLaneWithoutOvercommitting() {
    for (long seed = 0L; seed < 64L; seed++) {
      EvasionSystem.Step step =
          new EvasionSystem().tick(100L, 1.0D, false, true, new Random(seed));
      assertTrue(step.distance() >= 2.20D && step.distance() <= 3.00D);
      assertTrue(step.speed() >= 0.86D && step.speed() <= 0.92D);
      assertTrue(step.action().moves());
    }
  }

  @Test
  void cancelsAnUnreachableBurstBeforeItsNormalDurationEnds() {
    EvasionSystem system = new EvasionSystem();
    EvasionSystem.Step first = system.tick(100L, 0.80D, true, new Random(7L));

    system.cancel(101L);

    assertFalse(system.tick(102L, 0.80D, true, new Random(8L)).active());
  }

  @Test
  void openGroundAndPlayerPreAimUseBoundedLaneBreakingBursts() {
    for (long seed = 0L; seed < 32L; seed++) {
      EvasionSystem open = new EvasionSystem();
      EvasionSystem.Step openStep = open.tick(100L, 0.90D, false, new Random(seed));
      assertTrue(openStep.active());
      assertTrue(openStep.activeUntil() - 100L >= 6L);
      assertTrue(openStep.activeUntil() - 100L <= 9L);
      assertTrue(openStep.distance() >= 1.75D && openStep.distance() <= 2.45D);
      assertTrue(openStep.speed() >= 0.81D && openStep.speed() <= 0.89D);
      assertNotEquals(EvasionSystem.Action.CROUCH_DROP, openStep.action());

      EvasionSystem preAim = new EvasionSystem();
      EvasionSystem.Step preAimStep = preAim.tick(100L, 0.90D, false, true, new Random(seed));
      assertTrue(preAimStep.activeUntil() - 100L >= 7L);
      assertTrue(preAimStep.activeUntil() - 100L <= 10L);
      assertTrue(preAimStep.distance() >= 2.20D && preAimStep.distance() <= 3.00D);
      assertTrue(preAimStep.speed() >= 0.86D && preAimStep.speed() <= 0.92D);
      assertNotEquals(EvasionSystem.Action.CROUCH_DROP, preAimStep.action());
    }
  }

  @Test
  void coveredFightUsesACompactDodgeInsteadOfCrossingTheWholeRoom() {
    for (long seed = 0L; seed < 16L; seed++) {
      EvasionSystem.Step step = new EvasionSystem().tick(100L, 0.90D, true, new Random(seed));
      assertTrue(step.activeUntil() - 100L >= 4L);
      assertTrue(step.activeUntil() - 100L <= 7L);
      assertTrue(step.distance() >= 1.35D && step.distance() <= 1.90D);
      assertTrue(step.speed() >= 0.77D && step.speed() <= 0.84D);
    }
  }
}
