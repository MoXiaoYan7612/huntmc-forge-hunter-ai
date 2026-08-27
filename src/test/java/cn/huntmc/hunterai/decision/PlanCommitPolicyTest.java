package cn.huntmc.hunterai.decision;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlanCommitPolicyTest {
  @Test
  void normalSwitchRequiresPointTwelveImprovementButForcedEventsBypassIt() {
    assertFalse(PlanCommitPolicy.shouldReplace(0.70D, 0.819D, false));
    assertTrue(PlanCommitPolicy.shouldReplace(0.70D, 0.82D, false));
    assertTrue(PlanCommitPolicy.shouldReplace(0.70D, 0.20D, true));
  }
}
