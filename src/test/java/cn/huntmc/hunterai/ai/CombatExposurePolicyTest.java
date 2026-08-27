package cn.huntmc.hunterai.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class CombatExposurePolicyTest {
  @Test
  void exposurePolicyOnlyDescribesMovementPreferenceAfterDamage() {
    assertEquals(
        CombatExposurePolicy.Decision.RELOCATE_WITH_SUPPRESSION,
        CombatExposurePolicy.decide(1.0D, true, true, 0));
    assertEquals(
        CombatExposurePolicy.Decision.RELOCATE_WITHOUT_FIRE,
        CombatExposurePolicy.decide(1.0D, true, true, 2));
    assertFalse(CombatController.decideCombat(
        new CombatController.CombatSituation(false, false, true, true, true, false, false, 12L, false))
        .coverMovementUrgent());
  }

  @Test
  void undamagedExposedHunterUsesCoverWhileNoLegalShotExists() {
    assertEquals(
        CombatExposurePolicy.Decision.RELOCATE_WITHOUT_FIRE,
        CombatExposurePolicy.decide(0.90D, true, false, 0));
  }

  @Test
  void policyDoesNotInventCoverWhenNoneIsReachable() {
    assertEquals(
        CombatExposurePolicy.Decision.HOLD_POSITION,
        CombatExposurePolicy.decide(1.0D, false, true, 4));
  }
}
