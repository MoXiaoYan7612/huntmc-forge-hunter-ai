package cn.huntmc.hunterai.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CombatEvaluationTest {
  @Test
  void lowHealthDoesNotAutomaticallyMakeEscapeFavorableWhenCoverIsPoor() {
    CombatEvaluation combat =
        CombatEvaluation.assess(
            new SelfModel.Snapshot(0.22D, 0.55D, 0.90D, false, false, true),
            0.15D,
            1.0D,
            10.0D,
            20.0D,
            45.0D,
            0.20D,
            false);

    assertFalse(combat.escapeFavorable());
    assertTrue(combat.dodgeUrgency() > 0.48D);
  }

  @Test
  void coveredLowAmmoPositionCanMakeBreakingContactFavorable() {
    CombatEvaluation combat =
        CombatEvaluation.assess(
            new SelfModel.Snapshot(0.20D, 0.80D, 0.10D, true, false, true),
            1.0D,
            0.0D,
            35.0D,
            18.0D,
            45.0D,
            0.70D,
            true);

    assertTrue(combat.escapeFavorable());
  }
}
