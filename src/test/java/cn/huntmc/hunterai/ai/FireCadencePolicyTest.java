package cn.huntmc.hunterai.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class FireCadencePolicyTest {
  private final FireCadencePolicy policy = new FireCadencePolicy();

  @Test
  void rapidCadenceUsesTheCurrentWeaponsPhysicalRateAsItsLowerBound() {
    FireCadencePolicy.Selection selection =
        policy.select(500, winningExchange(), aggressiveHunter(), 1, new Random(7L));

    assertEquals("rapid", selection.mode());
    assertEquals(3L, selection.weaponMinimumIntervalTicks());
    assertTrue(selection.intervalTicks() >= 3L);
    assertTrue(selection.intervalTicks() <= 4L);
  }

  @Test
  void laterShotsAddAtMostTwoTicksOfHumanVariance() {
    FireCadencePolicy.Selection rapid =
        policy.select(500, winningExchange(), aggressiveHunter(), 1, new Random(11L));
    FireCadencePolicy.Selection controlled =
        policy.select(500, distantLowAmmoExchange(), patientHunter(), 4, new Random(11L));

    assertEquals("measured", controlled.mode());
    assertEquals("bounded-human-variance", controlled.reason());
    assertEquals(rapid.weaponMinimumIntervalTicks(), controlled.weaponMinimumIntervalTicks());
    assertTrue(controlled.intervalTicks() >= controlled.weaponMinimumIntervalTicks());
    assertTrue(controlled.intervalTicks() <= controlled.weaponMinimumIntervalTicks() + 2L);
  }

  @Test
  void followUpDoesNotAddASecondLongCooldownToA150RpmPistol() {
    FireCadencePolicy.Selection selection =
        policy.select(150, 8L, distantLowAmmoExchange(), patientHunter(), 2, new Random(11L));

    assertEquals("rapid", selection.mode());
    assertTrue(selection.intervalTicks() >= 8L);
    assertTrue(selection.intervalTicks() <= 9L);
  }

  @Test
  void slowWeaponStillCannotBeScheduledAboveItsRealCooldownFloor() {
    FireCadencePolicy.Selection selection =
        policy.select(75, winningExchange(), aggressiveHunter(), 1, new Random(3L));

    assertEquals(16L, selection.weaponMinimumIntervalTicks());
    assertTrue(selection.intervalTicks() >= 16L);
  }

  @Test
  void actualTaczCooldownFloorTakesPrecedenceOverTheRoundedRpmDisplayValue() {
    FireCadencePolicy.Selection selection =
        policy.select(500, 5L, winningExchange(), aggressiveHunter(), 1, new Random(5L));

    assertEquals(5L, selection.weaponMinimumIntervalTicks());
    assertTrue(selection.intervalTicks() >= 5L);
  }

  private static CombatEvaluation winningExchange() {
    return new CombatEvaluation(0.80D, 0.20D, 0.20D, 0.90D, 0.90D, false, "test");
  }

  private static CombatEvaluation distantLowAmmoExchange() {
    return new CombatEvaluation(0.50D, 0.30D, 0.30D, 0.30D, 0.45D, false, "test");
  }

  private static HunterPersonality aggressiveHunter() {
    return new HunterPersonality(0.85D, 0.25D, 0.75D, 0.5D, 0.5D, 0.5D, 0.5D, 0.5D, 0.5D, 0.8D);
  }

  private static HunterPersonality patientHunter() {
    return new HunterPersonality(0.35D, 0.80D, 0.35D, 0.5D, 0.5D, 0.5D, 0.5D, 0.5D, 0.5D, 0.8D);
  }
}
