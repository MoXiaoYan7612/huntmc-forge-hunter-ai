package cn.huntmc.hunterai.coordinator;

/** Pure lane selection kept separate from action priorities so it can be regression tested. */
final class BehaviorLanePolicy {
  private BehaviorLanePolicy() {}

  static BehaviorLane select(
      boolean visibleContact,
      boolean freshDirectDamage,
      boolean combatSearchActive,
      boolean objectiveInteraction,
      boolean tacticalOperation) {
    if (visibleContact || freshDirectDamage) return BehaviorLane.ACTIVE_COMBAT;
    if (combatSearchActive) return BehaviorLane.COMBAT_SEARCH;
    if (objectiveInteraction) return BehaviorLane.OBJECTIVE_INTERACTION;
    if (tacticalOperation) return BehaviorLane.TACTICAL_OPERATION;
    return BehaviorLane.STRATEGIC_TRANSIT;
  }
}
