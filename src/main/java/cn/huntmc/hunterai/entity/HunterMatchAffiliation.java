package cn.huntmc.hunterai.entity;

import java.util.UUID;

/** Match-seat affiliation rule shared by Hunter perception and friendly-fire protection. */
final class HunterMatchAffiliation {
  private HunterMatchAffiliation() {}

  static boolean sameTeam(
      UUID firstMatchId, String firstTeamId, UUID secondMatchId, String secondTeamId) {
    return firstMatchId != null
        && firstMatchId.equals(secondMatchId)
        && firstTeamId != null
        && !firstTeamId.isBlank()
        && firstTeamId.equals(secondTeamId);
  }
}
