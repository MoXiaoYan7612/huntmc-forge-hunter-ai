package cn.huntmc.hunterai.coordinator;

/**
 * The single primary owner of a Hunter tick.  Modules inside a lane may still submit requests,
 * but strategic transit, objective interaction and combat search can no longer drive the entity
 * at the same time.
 */
enum BehaviorLane {
  ACTIVE_COMBAT,
  COMBAT_SEARCH,
  OBJECTIVE_INTERACTION,
  TACTICAL_OPERATION,
  STRATEGIC_TRANSIT
}
