package cn.huntmc.hunterai.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class TacticalPlanTest {
  @Test
  void progressesFromApproachToObservationThenEngagement() {
    TacticalPlan plan = new TacticalPlan();
    plan.begin(HunterIntent.OVERWATCH, new Vec3(4.0D, 64.0D, 4.0D), 10L, "test");

    assertEquals(TacticalPlan.Stage.APPROACH, plan.update(false, false, false, 11L));
    assertEquals(TacticalPlan.Stage.SETTLE, plan.update(true, false, false, 12L));
    assertEquals(TacticalPlan.Stage.OBSERVE, plan.update(true, false, false, 21L));
    assertEquals(TacticalPlan.Stage.ENGAGE, plan.update(true, true, false, 22L));
  }

  @Test
  void confirmedInRangeTargetEngagesBeforeTheHunterReachesItsPlannedPosition() {
    TacticalPlan plan = new TacticalPlan();
    plan.begin(HunterIntent.FLANK, new Vec3(12.0D, 64.0D, 12.0D), 10L, "test");

    assertEquals(TacticalPlan.Stage.ENGAGE, plan.update(false, true, false, 11L));
  }
}
