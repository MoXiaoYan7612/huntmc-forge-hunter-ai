package cn.huntmc.hunterai.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BehaviorLanePolicyTest {
  @Test
  void combatAndSearchPreemptObjectiveAndStrategicWork() {
    assertEquals(
        BehaviorLane.ACTIVE_COMBAT,
        BehaviorLanePolicy.select(true, false, true, true, true));
    assertEquals(
        BehaviorLane.ACTIVE_COMBAT,
        BehaviorLanePolicy.select(false, true, true, true, true));
    assertEquals(
        BehaviorLane.COMBAT_SEARCH,
        BehaviorLanePolicy.select(false, false, true, true, true));
  }

  @Test
  void objectiveInteractionOwnsTheTickBeforeNormalTactics() {
    assertEquals(
        BehaviorLane.OBJECTIVE_INTERACTION,
        BehaviorLanePolicy.select(false, false, false, true, true));
    assertEquals(
        BehaviorLane.TACTICAL_OPERATION,
        BehaviorLanePolicy.select(false, false, false, false, true));
    assertEquals(
        BehaviorLane.STRATEGIC_TRANSIT,
        BehaviorLanePolicy.select(false, false, false, false, false));
  }
}
