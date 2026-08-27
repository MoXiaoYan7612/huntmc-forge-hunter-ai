package cn.huntmc.hunterai.action;

public enum InteractionCommand {
  NONE(""),
  START_BANISH("huntmc_hunter_ai:start_banish"),
  CLAIM_BOUNTY("huntmc_hunter_ai:claim_bounty"),
  REVIVE("huntmc_hunter_ai:revive"),
  EXTRACT("huntmc_hunter_ai:extract"),
  /** Local actuator command; it never crosses the Bukkit match bridge. */
  RECOVER_HEALTH("");

  private final String actionId;

  InteractionCommand(String actionId) {
    this.actionId = actionId;
  }

  public String actionId() {
    return actionId;
  }
}
