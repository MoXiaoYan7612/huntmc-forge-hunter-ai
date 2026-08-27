package cn.huntmc.hunterai.entity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class HunterMatchAffiliationTest {
  @Test
  void onlyMatchingNonBlankMatchTeamsAreAllied() {
    UUID match = UUID.randomUUID();

    assertTrue(HunterMatchAffiliation.sameTeam(match, "team1", match, "team1"));
    assertFalse(HunterMatchAffiliation.sameTeam(match, "team1", match, "team2"));
    assertFalse(HunterMatchAffiliation.sameTeam(match, "", match, ""));
    assertFalse(HunterMatchAffiliation.sameTeam(match, "team1", UUID.randomUUID(), "team1"));
  }
}
