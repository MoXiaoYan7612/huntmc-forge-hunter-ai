package cn.huntmc.hunterai.director;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GodDirectorTest {
  @Test
  void eightHuntersInTwoPersonTeamsOnlyAssignOneTeamToTheObjective() {
    UUID match = UUID.fromString("12345678-1234-5678-1234-567812345678");
    Map<String, Integer> teams = new LinkedHashMap<>();
    teams.put("team-a", 2);
    teams.put("team-b", 2);
    teams.put("team-c", 2);
    teams.put("team-d", 2);

    Set<String> selected = GodDirector.selectObjectiveTeams(match, teams, 8);

    assertEquals(1, selected.size());
    assertEquals(selected, GodDirector.selectObjectiveTeams(match, teams, 8));
  }

  @Test
  void ungroupedHuntersStillLimitObjectiveParticipantsToOneQuarter() {
    UUID match = UUID.fromString("87654321-4321-8765-4321-876543218765");
    Map<String, Integer> soloTeams = new LinkedHashMap<>();
    for (int index = 0; index < 8; index++) soloTeams.put("solo-" + index, 1);

    assertEquals(2, GodDirector.selectObjectiveTeams(match, soloTeams, 8).size());
  }

  @Test
  void supportLanesIncludeInterceptionAmbushObservationAndDelay() {
    assertEquals(GodDirector.Role.INTERCEPTOR, GodDirector.supportRole(0));
    assertEquals(GodDirector.Role.AMBUSHER, GodDirector.supportRole(1));
    assertEquals(GodDirector.Role.OBSERVER, GodDirector.supportRole(2));
    assertEquals(GodDirector.Role.DELAYED_CONTESTER, GodDirector.supportRole(3));
    assertTrue(GodDirector.supportRole(4) == GodDirector.Role.INTERCEPTOR);
  }

  @Test
  void objectiveReleaseUsesAStableTwentyToThirtySecondReconWindow() {
    UUID match = UUID.fromString("11111111-2222-3333-4444-555555555555");
    long delay = GodDirector.objectiveReleaseDelay(match);

    assertTrue(delay >= 400L);
    assertTrue(delay <= 600L);
    assertEquals(delay, GodDirector.objectiveReleaseDelay(match));
  }
}
