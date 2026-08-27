package cn.huntmc.hunterai.decision;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.huntmc.hunterai.ai.HunterIntent;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HunterPlanTest {
  @Test
  void commitmentEndsAtItsBoundaryAndInvalidationOverridesIt() {
    HunterPlan plan = new HunterPlan(
        UUID.randomUUID(), StrategicGoal.TRANSIT_TO_BOSS, HunterIntent.PUSH, null,
        PlanPhase.APPROACH, List.of(), 140L, 300L, PlanValidity.VALID,
        FallbackPlan.inactive(), 0.7D, "test");

    assertTrue(plan.committed(139L));
    assertFalse(plan.committed(140L));
    assertFalse(plan.invalidated("route-failed").committed(120L));
  }

  @Test
  void activatesOnlyBranchesDeclaredByThePlan() {
    ContingencyBranch branch =
        new ContingencyBranch("visible-enemy", HunterIntent.REPOSITION, PlanPhase.ENGAGE);
    HunterPlan plan = new HunterPlan(
        UUID.randomUUID(), StrategicGoal.TRANSIT_TO_BOSS, HunterIntent.PUSH, null,
        PlanPhase.APPROACH, List.of(branch), 140L, 300L, PlanValidity.VALID,
        FallbackPlan.inactive(), 0.7D, "test");

    HunterPlan activated = plan.activate(branch, "contingency:visible-enemy");

    assertEquals(HunterIntent.REPOSITION, activated.intent());
    assertEquals(PlanPhase.ENGAGE, activated.phase());
    assertEquals("contingency:visible-enemy", activated.reason());
    assertThrows(
        IllegalArgumentException.class,
        () -> plan.activate(
            new ContingencyBranch("route-failure", HunterIntent.DISENGAGE, PlanPhase.ESCAPE),
            "not-declared"));
  }
}
