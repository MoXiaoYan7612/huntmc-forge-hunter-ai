package cn.huntmc.hunterai.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HunterReplayAnalyzerTest {
  @TempDir Path session;

  @Test
  void reconstructsGlobalSequenceAndFiltersByDecisionPlanGoalAndType() throws Exception {
    Files.writeString(
        session.resolve("events-0001.ndjson"),
        event(
            1,
            10,
            "PLAN",
            "decision-a",
            "plan-a",
            "route-a",
            "{\"goal\":\"TRANSIT_TO_BOSS\",\"intent\":\"PUSH\",\"target\":\"(8.0,64.0,0.0)\",\"routeId\":\"route-a\"}"));
    Files.writeString(
        session.resolve("frames-0001.ndjson"),
        event(
                2,
                10,
                "ACTION_FRAME",
                "decision-a",
                "plan-a",
                "route-a",
                "{\"movement\":\"SPRINT\",\"weapon\":\"HOLD\",\"look\":\"(8.0,65.0,0.0)\",\"owners\":\"{MOVEMENT=strategic-plan}\"}")
            + event(
                3,
                11,
                "OUTCOME",
                "decision-a",
                "plan-a",
                "route-a",
                "{\"position\":\"(1.0,64.0,0.0)\",\"objectiveDistance\":\"7.0\",\"objectiveProgress\":\"1.0\",\"healthDelta\":\"0.0\"}"));

    HunterReplayAnalyzer.Analysis analysis = HunterReplayAnalyzer.analyze(session);
    HunterReplayAnalyzer.ReplayFilter filter =
        new HunterReplayAnalyzer.ReplayFilter(
            "hunter-a",
            10L,
            10L,
            "decision-a",
            "plan-a",
            null,
            "ACTION_FRAME",
            null);
    HunterReplayAnalyzer.ReplayQuery query = HunterReplayAnalyzer.query(session, filter);

    assertEquals(3L, analysis.eventCount());
    assertEquals(0L, analysis.sequenceGaps());
    assertEquals(1, analysis.planCount());
    assertEquals(1, analysis.executedPlanCount());
    assertEquals(1.0D, analysis.planExecutionRate());
    assertEquals(1, query.timeline().size());
    assertEquals("ACTION_FRAME", query.timeline().get(0).type());
    assertTrue(Files.isRegularFile(session.resolve("session-summary.md")));
    assertTrue(Files.isRegularFile(session.resolve("defects.json")));
  }

  @Test
  void reportsOneDefectForOneContinuousFallbackEpisode() throws Exception {
    String payload = "{\"level\":\"SAFE_RESET\",\"phase\":\"REACH_IMMEDIATE_COVER\"}";
    Files.writeString(
        session.resolve("events-0001.ndjson"),
        event(1, 0, "FALLBACK", "decision-a", "plan-a", "route-a", payload)
            + event(2, 181, "FALLBACK", "decision-a", "plan-a", "route-a", payload)
            + event(3, 182, "FALLBACK", "decision-a", "plan-a", "route-a", payload));

    HunterReplayAnalyzer.Analysis analysis = HunterReplayAnalyzer.analyze(session);

    assertEquals(
        1L,
        analysis.defects().stream().filter(defect -> "FALLBACK_LOOP".equals(defect.type())).count());
  }

  @Test
  void safeResetPlansWithoutFallbackEventsDoNotOverflowIntoFalseReentry() throws Exception {
    String first = "{\"goal\":\"SAFE_RESET\",\"planId\":\"safe-a\"}";
    String second = "{\"goal\":\"SAFE_RESET\",\"planId\":\"safe-b\"}";
    Files.writeString(
        session.resolve("events-0001.ndjson"),
        event(1, 10, "PLAN", "decision-a", "safe-a", "route-a", first)
            + event(2, 20, "PLAN", "decision-b", "safe-b", "route-b", second));

    HunterReplayAnalyzer.Analysis analysis = HunterReplayAnalyzer.analyze(session);

    assertEquals(
        0L,
        analysis.defects().stream()
            .filter(defect -> "FALLBACK_REENTRY".equals(defect.type()))
            .count());
  }

  @Test
  void usesMonotonicSessionTickWhenWorldGameTimeMovesBackward() throws Exception {
    String payload = "{\"level\":\"SAFE_RESET\",\"phase\":\"REACH_IMMEDIATE_COVER\"}";
    Files.writeString(
        session.resolve("events-0001.ndjson"),
        eventWithSession(1, 0, 1200, "FALLBACK", payload)
            + eventWithSession(2, 181, 4, "FALLBACK", payload));

    HunterReplayAnalyzer.Analysis analysis = HunterReplayAnalyzer.analyze(session);

    assertEquals(
        1L,
        analysis.defects().stream().filter(defect -> "FALLBACK_LOOP".equals(defect.type())).count());
  }

  @Test
  void recognizesThePersistentCombatControllerAsTheLegalViewOwner() throws Exception {
    Files.writeString(
        session.resolve("events-0001.ndjson"),
        event(1, 10, "PERCEPTION", "decision-a", "plan-a", "route-a", "{\"visible\":\"target-a\"}"));
    Files.writeString(
        session.resolve("frames-0001.ndjson"),
        event(
            2,
            10,
            "ACTION_FRAME",
            "decision-a",
            "plan-a",
            "route-a",
            "{\"movement\":\"HOLD\",\"weapon\":\"ADS\",\"look\":\"(8.0,65.0,0.0)\",\"owners\":\"{VIEW=combat-controller, WEAPON=combat-controller}\"}"));

    HunterReplayAnalyzer.Analysis analysis = HunterReplayAnalyzer.analyze(session);

    assertEquals(
        0L,
        analysis.defects().stream().filter(defect -> "COMBAT_AIM_OVERRIDDEN".equals(defect.type())).count());
  }

  @Test
  void nativeCooldownAndManualActionDoNotBecomeSeenNotFiredDefects() throws Exception {
    StringBuilder events = new StringBuilder();
    long sequence = 1L;
    events.append(event(sequence++, 0, "PERCEPTION", "decision-a", "plan-a", "route-a",
        "{\"visible\":\"target-a\"}"));
    String[] physicalResults = {"NATIVE_COOLDOWN", "NEED_BOLT", "IS_BOLTING", "ADS_TRANSITION"};
    for (int tick = 1; tick <= 24; tick++) {
      events.append(event(sequence++, tick, "ACTION_FRAME", "decision-a", "plan-a", "route-a",
          "{\"weapon\":\"ADS_FIRE\",\"owners\":\"{VIEW=combat-controller, WEAPON=combat-controller}\"}"));
      events.append(event(sequence++, tick, "ACTUATOR_RESULT", "decision-a", "plan-a", "route-a",
          "{\"weapon\":\"" + physicalResults[(tick - 1) % physicalResults.length] + "\"}"));
    }
    Files.writeString(session.resolve("events-0001.ndjson"), events);

    HunterReplayAnalyzer.Analysis analysis = HunterReplayAnalyzer.analyze(session);

    assertEquals(0L, analysis.defects().stream()
        .filter(defect -> "SEEN_NOT_FIRED".equals(defect.type())).count());
  }

  @Test
  void sameTickSuccessfulShotDoesNotBecomeSeenNotFiredDefect() throws Exception {
    Files.writeString(
        session.resolve("events-0001.ndjson"),
        event(1, 10, "PERCEPTION", "decision-a", "plan-a", "route-a",
                "{\"visible\":\"target-a\"}")
            + event(2, 10, "ACTION_FRAME", "decision-a", "plan-a", "route-a",
                "{\"weapon\":\"ADS_FIRE\","
                    + "\"owners\":\"{VIEW=combat-controller, WEAPON=combat-controller}\"}")
            + event(3, 10, "ACTUATOR_RESULT", "decision-a", "plan-a", "route-a",
                "{\"weapon\":\"fired-ads:test\"}"));

    HunterReplayAnalyzer.Analysis analysis = HunterReplayAnalyzer.analyze(session);

    assertEquals(0L, analysis.defects().stream()
        .filter(defect -> "SEEN_NOT_FIRED".equals(defect.type())).count());
  }

  @Test
  void reportsFollowupMissingTwoTicksAfterNativeBlockClears() throws Exception {
    StringBuilder events = new StringBuilder();
    long sequence = 1L;
    events.append(event(sequence++, 0, "PERCEPTION", "decision-a", "plan-a", "route-a",
        "{\"visible\":\"target-a\"}"));
    events.append(event(sequence++, 1, "ACTUATOR_RESULT", "decision-a", "plan-a", "route-a",
        "{\"weapon\":\"fired-ads:test\"}"));
    events.append(event(sequence++, 2, "ACTUATOR_RESULT", "decision-a", "plan-a", "route-a",
        "{\"weapon\":\"NATIVE_COOLDOWN\"}"));
    for (int tick = 3; tick <= 6; tick++) {
      events.append(event(sequence++, tick, "ACTION_FRAME", "decision-a", "plan-a", "route-a",
          "{\"weapon\":\"ADS\",\"owners\":\"{VIEW=combat-controller, WEAPON=combat-controller}\"}"));
      events.append(event(sequence++, tick, "ACTUATOR_RESULT", "decision-a", "plan-a", "route-a",
          "{\"weapon\":\"ads\"}"));
    }
    Files.writeString(session.resolve("events-0001.ndjson"), events);

    HunterReplayAnalyzer.Analysis analysis = HunterReplayAnalyzer.analyze(session);

    assertEquals(1L, analysis.defects().stream()
        .filter(defect -> "SEEN_NOT_FIRED".equals(defect.type()))
        .filter(defect -> defect.description().contains("physical block cleared"))
        .count());
  }

  @Test
  void acceptsMinecraftAbsoluteYawAndDamageReactionAsCombatViewOwnership() throws Exception {
    Files.writeString(
        session.resolve("events-0001.ndjson"),
        event(1, 10, "INPUT", "decision-a", "plan-a", "route-a", "{\"yaw\":\"350.0\"}")
            + event(2, 10, "PERCEPTION", "decision-a", "plan-a", "route-a", "{\"visible\":\"target-a\"}"));
    Files.writeString(
        session.resolve("frames-0001.ndjson"),
        event(
            3,
            10,
            "ACTION_FRAME",
            "decision-a",
            "plan-a",
            "route-a",
            "{\"movement\":\"EVADE\",\"weapon\":\"ADS\",\"owners\":\"{VIEW=damage-reaction, WEAPON=combat-controller}\"}"));

    HunterReplayAnalyzer.Analysis analysis = HunterReplayAnalyzer.analyze(session);

    assertEquals(0L, analysis.defects().stream()
        .filter(defect -> "UNBOUNDED_ROTATION".equals(defect.type())
            || "COMBAT_AIM_OVERRIDDEN".equals(defect.type()))
        .count());
  }

  @Test
  void reportsAssignedHunterTickGaps() throws Exception {
    Files.writeString(
        session.resolve("frames-0001.ndjson"),
        event(1, 10, "INPUT", "decision-a", "plan-a", "route-a",
                "{\"position\":\"(0.0,64.0,0.0)\",\"assigned\":\"true\"}")
            + event(2, 14, "INPUT", "decision-a", "plan-a", "route-a",
                "{\"position\":\"(1.0,64.0,0.0)\",\"assigned\":\"true\"}"));

    HunterReplayAnalyzer.Analysis analysis = HunterReplayAnalyzer.analyze(session);

    assertEquals(
        1L,
        analysis.defects().stream().filter(defect -> "AI_TICK_GAP".equals(defect.type())).count());
  }

  @Test
  void reportsAimErrorThatDoesNotConverge() throws Exception {
    Files.writeString(
        session.resolve("events-0001.ndjson"),
        event(1, 10, "PERCEPTION", "decision-a", "plan-a", "route-a", "{\"visible\":\"target-a\"}")
            + event(2, 10, "COMBAT", "decision-a", "plan-a", "route-a",
                "{\"state\":\"TRACK\",\"aimError\":\"14.04\",\"reason\":\"new-legal-visual\"}")
            + event(3, 30, "COMBAT", "decision-a", "plan-a", "route-a",
                "{\"state\":\"TRACK\",\"aimError\":\"14.04\",\"reason\":\"new-legal-visual\"}"));

    HunterReplayAnalyzer.Analysis analysis = HunterReplayAnalyzer.analyze(session);

    assertEquals(
        1L,
        analysis.defects().stream()
            .filter(defect -> "AIM_NOT_CONVERGING".equals(defect.type()))
            .count());
  }

  @Test
  void doesNotReportAlreadyConvergedAimAsStagnant() throws Exception {
    Files.writeString(
        session.resolve("events-0001.ndjson"),
        event(1, 10, "PERCEPTION", "decision-a", "plan-a", "route-a", "{\"visible\":\"target-a\"}")
            + event(2, 10, "COMBAT", "decision-a", "plan-a", "route-a",
                "{\"state\":\"TRACK\",\"aimError\":\"0.03\",\"reason\":\"fire-gate-AIM_STABILIZING\"}")
            + event(3, 30, "COMBAT", "decision-a", "plan-a", "route-a",
                "{\"state\":\"TRACK\",\"aimError\":\"0.03\",\"reason\":\"fire-gate-AIM_STABILIZING\"}"));

    HunterReplayAnalyzer.Analysis analysis = HunterReplayAnalyzer.analyze(session);

    assertEquals(
        0L,
        analysis.defects().stream()
            .filter(defect -> "AIM_NOT_CONVERGING".equals(defect.type()))
            .count());
  }

  @Test
  void reportsStrategicPlanChurnWithinOneSecond() throws Exception {
    StringBuilder events = new StringBuilder();
    for (int index = 0; index < 10; index++) {
      events.append(
          event(
              index + 1L,
              100L + index,
              "PLAN",
              "decision-" + index,
              "plan-" + index,
              "route-" + index,
              "{\"goal\":\"EXTRACT\",\"intent\":\"PUSH\",\"target\":\"(10.0,64.0,10.0)\"}"));
    }
    Files.writeString(session.resolve("events-0001.ndjson"), events);

    HunterReplayAnalyzer.Analysis analysis = HunterReplayAnalyzer.analyze(session);

    assertEquals(
        1L,
        analysis.defects().stream().filter(defect -> "PLAN_CHURN".equals(defect.type())).count());
  }

  @Test
  void arrivalEndsTheMovementEpisodeBeforeStationaryInputFrames() throws Exception {
    StringBuilder frames = new StringBuilder();
    frames.append(event(
        1, 10, "ACTION_FRAME", "decision-a", "plan-a", "route-a",
        "{\"movement\":\"SPRINT\",\"weapon\":\"HOLD\",\"owners\":\"{MOVEMENT=strategic-plan}\"}"));
    frames.append(event(
        2, 10, "ACTUATOR_RESULT", "decision-a", "plan-a", "route-a",
        "{\"movementResult\":\"arrived\"}"));
    for (int index = 0; index < 12; index++) {
      frames.append(event(
          3L + index,
          11L + index,
          "INPUT",
          "decision-a",
          "plan-a",
          "route-a",
          "{\"position\":\"(10.0,64.0,10.0)\",\"assigned\":\"false\"}"));
    }
    Files.writeString(session.resolve("frames-0001.ndjson"), frames);

    HunterReplayAnalyzer.Analysis analysis = HunterReplayAnalyzer.analyze(session);

    assertEquals(
        0L,
        analysis.defects().stream().filter(defect -> "PATH_LOOP".equals(defect.type())).count());
    assertEquals(
        0L,
        analysis.defects().stream().filter(defect -> "MOVEMENT_STALL".equals(defect.type())).count());
  }

  @Test
  void blockedLocalCombatDodgeDoesNotPolluteStrategicPathRecovery() throws Exception {
    Files.writeString(
        session.resolve("frames-0001.ndjson"),
        event(
            1,
            10,
            "ACTUATOR_RESULT",
            "decision-a",
            "plan-a",
            "route-a",
            "{\"movementResult\":\"local-evade-blocked\",\"failureTarget\":\"(1.0,64.0,0.0)\"}"));

    HunterReplayAnalyzer.Analysis analysis = HunterReplayAnalyzer.analyze(session);

    assertEquals(
        0L,
        analysis.defects().stream()
            .filter(defect -> "PATH_EXECUTION_FAILURE".equals(defect.type()))
            .count());
  }

  @Test
  void aggregatesRepeatedFailuresInOneRegionIntoOneEpisode() throws Exception {
    StringBuilder frames = new StringBuilder();
    long sequence = 1L;
    for (int index = 0; index < 3; index++) {
      long tick = 10L + index * 40L;
      frames.append(event(sequence++, tick, "ACTUATOR_RESULT", "decision-a", "plan-a", "route-a",
          "{\"movementResult\":\"navigation-rejected\","
              + "\"failureTarget\":\"(9.0,64.0,9.0)\"}"));
      frames.append(event(sequence++, tick + 1L, "ACTUATOR_RESULT", "decision-a", "plan-a", "route-a",
          "{\"movementResult\":\"following\"}"));
    }
    Files.writeString(session.resolve("frames-0001.ndjson"), frames);

    HunterReplayAnalyzer.Analysis analysis = HunterReplayAnalyzer.analyze(session);
    var failures = analysis.defects().stream()
        .filter(defect -> "PATH_EXECUTION_FAILURE".equals(defect.type()))
        .toList();

    assertEquals(1, failures.size());
    assertEquals(3, failures.get(0).evidenceSequences().size());
    assertTrue(failures.get(0).description().contains("occurrences=3"));
  }

  @Test
  void reportsBossInteractionRangeWithoutBanishAttempt() throws Exception {
    StringBuilder frames = new StringBuilder();
    for (int index = 0; index < 20; index++) {
      frames.append(event(
          index + 1L,
          100L + index,
          "INPUT",
          "decision-a",
          "plan-a",
          "route-a",
          "{\"position\":\"(4.2,64.0,0.0)\",\"objective\":\"(0.0,64.0,0.0)\","
              + "\"bounty\":\"UNBANISHED\",\"bossInteractionRange\":\"5.0\","
              + "\"assigned\":\"true\"}"));
    }
    Files.writeString(session.resolve("frames-0001.ndjson"), frames);

    HunterReplayAnalyzer.Analysis analysis = HunterReplayAnalyzer.analyze(session);

    assertEquals(
        1L,
        analysis.defects().stream()
            .filter(defect -> "OBJECTIVE_INTERACTION_NOT_ATTEMPTED".equals(defect.type()))
            .count());
  }

  @Test
  void reportsInterceptPushThatHoldsAfterArrivalForTooLong() throws Exception {
    StringBuilder frames = new StringBuilder();
    frames.append(event(
        1, 100, "PLAN", "decision-a", "plan-a", "route-a",
        "{\"goal\":\"INTERCEPT_APPROACH\",\"intent\":\"PUSH\","
            + "\"target\":\"(10.0,64.0,10.0)\"}"));
    for (int index = 0; index <= 80; index++) {
      frames.append(event(
          index + 2L,
          101L + index,
          "ACTION_FRAME",
          "decision-a",
          "plan-a",
          "route-a",
          "{\"movement\":\"HOLD\",\"weapon\":\"HOLD\","
              + "\"owners\":\"{MOVEMENT=strategic-plan}\"}"));
    }
    Files.writeString(session.resolve("frames-0001.ndjson"), frames);

    HunterReplayAnalyzer.Analysis analysis = HunterReplayAnalyzer.analyze(session);

    assertEquals(1L, analysis.defects().stream()
        .filter(defect -> "INTERCEPT_HOLD_STALL".equals(defect.type())).count());
  }

  @Test
  void legalAcquisitionLostToRealFovChangeIsNotAConfirmationDefect() throws Exception {
    Files.writeString(
        session.resolve("events-0001.ndjson"),
        event(1, 100, "PERCEPTION", "decision-a", "plan-a", "route-a",
            "{\"contact\":\"ACQUIRING\",\"visible\":\"none\","
                + "\"diagnostic\":\"facing-sweep-legal-sight\"}")
            + event(2, 101, "PERCEPTION", "decision-a", "plan-a", "route-a",
                "{\"contact\":\"NONE\",\"visible\":\"none\","
                    + "\"diagnostic\":\"normal-fov-rejected\"}"));

    HunterReplayAnalyzer.Analysis analysis = HunterReplayAnalyzer.analyze(session);

    assertEquals(0L, analysis.defects().stream()
        .filter(defect -> "PENDING_VISUAL_CONFIRMATION_LOST".equals(defect.type())).count());
  }

  @Test
  void reportsLegalAcquisitionDiscardedWithoutPhysicalCause() throws Exception {
    Files.writeString(
        session.resolve("events-0001.ndjson"),
        event(1, 100, "PERCEPTION", "decision-a", "plan-a", "route-a",
            "{\"contact\":\"ACQUIRING\",\"visible\":\"none\","
                + "\"diagnostic\":\"facing-sweep-legal-sight\"}")
            + event(2, 101, "PERCEPTION", "decision-a", "plan-a", "route-a",
                "{\"contact\":\"NONE\",\"visible\":\"none\","
                    + "\"diagnostic\":\"candidate-cleared\"}"));

    HunterReplayAnalyzer.Analysis analysis = HunterReplayAnalyzer.analyze(session);

    assertEquals(1L, analysis.defects().stream()
        .filter(defect -> "PENDING_VISUAL_CONFIRMATION_LOST".equals(defect.type())).count());
  }

  @Test
  void interceptMovementUsesItsOwnTargetInsteadOfPublicObjectiveDistance() throws Exception {
    StringBuilder frames = new StringBuilder();
    frames.append(event(1, 100, "PLAN", "decision-a", "plan-a", "route-a",
        "{\"goal\":\"INTERCEPT_APPROACH\",\"intent\":\"PUSH\","
            + "\"target\":\"(20.0,64.0,0.0)\"}"));
    long sequence = 2L;
    for (int index = 0; index < 90; index++) {
      frames.append(event(sequence++, 101L + index, "ACTION_FRAME", "decision-a", "plan-a", "route-a",
          "{\"movement\":\"SPRINT\",\"target\":\"(20.0,64.0,0.0)\","
              + "\"owners\":\"{MOVEMENT=strategic-plan}\"}"));
      frames.append(event(sequence++, 101L + index, "INPUT", "decision-a", "plan-a", "route-a",
          "{\"position\":\"(" + index * 0.1D + ",64.0,0.0)\","
              + "\"objectiveDistance\":\"" + (50.0D + index * 0.1D) + "\","
              + "\"assigned\":\"true\"}"));
    }
    Files.writeString(session.resolve("frames-0001.ndjson"), frames);

    HunterReplayAnalyzer.Analysis analysis = HunterReplayAnalyzer.analyze(session);

    assertEquals(0L, analysis.defects().stream()
        .filter(defect -> "PATH_LOOP".equals(defect.type())
            || "OBJECTIVE_NO_PROGRESS".equals(defect.type()))
        .count());
  }

  @Test
  void banishAttemptClearsObjectiveInteractionWatchdog() throws Exception {
    StringBuilder frames = new StringBuilder();
    for (int index = 0; index < 19; index++) {
      frames.append(event(
          index + 1L,
          100L + index,
          "INPUT",
          "decision-a",
          "plan-a",
          "route-a",
          "{\"position\":\"(4.2,64.0,0.0)\",\"objective\":\"(0.0,64.0,0.0)\","
              + "\"bounty\":\"UNBANISHED\",\"bossInteractionRange\":\"5.0\","
              + "\"assigned\":\"true\"}"));
    }
    frames.append(event(
        20,
        119,
        "ACTION_FRAME",
        "decision-a",
        "plan-a",
        "route-a",
        "{\"movement\":\"HOLD\",\"interaction\":\"START_BANISH\",\"owners\":\"{INTERACTION=strategic-plan}\"}"));
    Files.writeString(session.resolve("frames-0001.ndjson"), frames);

    HunterReplayAnalyzer.Analysis analysis = HunterReplayAnalyzer.analyze(session);

    assertEquals(
        0L,
        analysis.defects().stream()
            .filter(defect -> "OBJECTIVE_INTERACTION_NOT_ATTEMPTED".equals(defect.type()))
            .count());
  }

  @Test
  void pendingBanishIsNotReportedAsNeverAttempted() throws Exception {
    StringBuilder events = new StringBuilder();
    long sequence = 1L;
    events.append(event(
        sequence++,
        100,
        "ACTION_FRAME",
        "decision-a",
        "plan-a",
        "route-a",
        "{\"movement\":\"HOLD\",\"interaction\":\"START_BANISH\","
            + "\"owners\":\"{INTERACTION=strategic-plan}\"}"));
    events.append(event(
        sequence++,
        101,
        "ACTUATOR_RESULT",
        "decision-a",
        "plan-a",
        "route-a",
        "{\"movementResult\":\"hold\",\"interaction\":\"true:in-progress:attempt=1\"}"));
    for (int index = 0; index < 40; index++) {
      events.append(event(
          sequence++,
          102L + index,
          "INPUT",
          "decision-a",
          "plan-a",
          "route-a",
          "{\"position\":\"(4.2,64.0,0.0)\",\"objective\":\"(0.0,64.0,0.0)\","
              + "\"bounty\":\"UNBANISHED\",\"bossInteractionRange\":\"5.0\","
              + "\"assigned\":\"true\"}"));
    }
    Files.writeString(session.resolve("frames-0001.ndjson"), events);

    HunterReplayAnalyzer.Analysis analysis = HunterReplayAnalyzer.analyze(session);

    assertEquals(0L, analysis.defects().stream()
        .filter(defect -> "OBJECTIVE_INTERACTION_NOT_ATTEMPTED".equals(defect.type()))
        .count());
  }

  @Test
  void deathStopsTheDamageResponseWatchdog() throws Exception {
    Files.writeString(
        session.resolve("events-0001.ndjson"),
        event(1, 100, "EVIDENCE", "decision-a", "plan-a", "route-a", "{\"type\":\"DAMAGE\"}")
            + event(2, 101, "PERCEPTION", "decision-a", "plan-a", "route-a", "{\"visible\":\"target-a\"}")
            + event(3, 101, "COMBAT", "decision-a", "plan-a", "route-a", "{\"state\":\"STABILIZE\"}")
            + event(4, 102, "LIFECYCLE", "decision-a", "plan-a", "route-a", "{\"phase\":\"death\"}")
            + event(5, 220, "INPUT", "decision-a", "plan-a", "route-a", "{\"position\":\"(0.0,64.0,0.0)\"}"));

    HunterReplayAnalyzer.Analysis analysis = HunterReplayAnalyzer.analyze(session);

    assertEquals(0L, analysis.defects().stream()
        .filter(defect -> "DAMAGE_RESPONSE_NO_FIRE".equals(defect.type()))
        .count());
  }

  @Test
  void analyzesV4PlayerEncountersAndWritesComparisonReports() throws Exception {
    String player = "player-0123456789abcdef01234567";
    String hunter = "hunter-1";
    String encounter = "enc-test";
    String match = "match-test";
    StringBuilder events = new StringBuilder();
    long sequence = 1L;
    events.append(v4Event(sequence++, 10, "ENCOUNTER", null, player, "PLAYER", hunter,
        encounter, match, "{\"phase\":\"start\",\"evidence\":\"legal-los\"}"));
    for (int index = 0; index < 12; index++) {
      events.append(v4Event(sequence++, 10 + index, "PLAYER_FRAME", null, player, "PLAYER", hunter,
          encounter, match,
          "{\"position\":\"(" + index * 0.20D + ",64.0,0.0)\","
              + "\"horizontalSpeed\":\"0.200\",\"movementFlags\":\"normal\","
              + "\"sprinting\":\"true\",\"jumped\":\"false\",\"crouching\":\"false\"}"));
      events.append(v4Event(sequence++, 10 + index, "INPUT", hunter, hunter, "HUNTER", player,
          encounter, match,
          "{\"position\":\"(" + index * 0.40D + ",64.0,1.0)\","
              + "\"horizontalSpeed\":\"0.400\",\"movementFlags\":\"normal\","
              + "\"sprinting\":\"true\",\"jumped\":\"false\"}"));
    }
    events.append(v4Event(sequence, 60, "ENCOUNTER", null, player, "PLAYER", hunter,
        encounter, match, "{\"phase\":\"end\",\"reason\":\"quiet-timeout\"}"));
    Files.writeString(session.resolve("events-0001.ndjson"), events);

    HunterReplayAnalyzer.Analysis analysis = HunterReplayAnalyzer.analyze(session);

    assertTrue(analysis.defects().stream().anyMatch(defect -> "AI_MOVE_TOO_FAST".equals(defect.type())));
    assertTrue(analysis.defects().stream()
        .filter(defect -> "AI_MOVE_TOO_FAST".equals(defect.type()))
        .allMatch(defect -> encounter.equals(defect.encounterId())
            && player.equals(defect.counterpartActorId())
            && !defect.evidenceSequences().isEmpty()));
    assertTrue(Files.isRegularFile(session.resolve("player-behavior-summary.md")));
    assertTrue(Files.isRegularFile(session.resolve("ai-player-comparison.md")));
    String output = Files.readString(session.resolve("defects.json"));
    assertTrue(output.contains(player));
    assertFalse(output.contains("12345678-1234-5678-9abc-def012345678"));
  }

  @Test
  void encounterNoFireRequiresTheHuntersOwnConfirmedVisual() throws Exception {
    String player = "player-visual-policy";
    StringBuilder events = new StringBuilder();
    long sequence = 1L;
    events.append(v4Event(sequence++, 0, "ENCOUNTER", null, player, "PLAYER", "hunter-sound",
        "enc-sound", "match-test", "{\"phase\":\"start\",\"evidence\":\"hostile-gunfire\"}"));
    events.append(v4Event(sequence++, 0, "ENCOUNTER", null, player, "PLAYER", "hunter-visual",
        "enc-visual", "match-test", "{\"phase\":\"start\",\"evidence\":\"legal-los\"}"));
    events.append(v4Event(sequence++, 1, "PERCEPTION", "hunter-visual", "hunter-visual", "HUNTER", player,
        "enc-visual", "match-test", "{\"visible\":\"player-contact\"}"));
    events.append(v4Event(sequence++, 60, "ENCOUNTER", null, player, "PLAYER", "hunter-sound",
        "enc-sound", "match-test", "{\"phase\":\"end\"}"));
    events.append(v4Event(sequence, 60, "ENCOUNTER", null, player, "PLAYER", "hunter-visual",
        "enc-visual", "match-test", "{\"phase\":\"end\"}"));
    Files.writeString(session.resolve("events-0001.ndjson"), events);

    HunterReplayAnalyzer.Analysis analysis = HunterReplayAnalyzer.analyze(session);
    var defects = analysis.defects().stream()
        .filter(defect -> "AI_ENCOUNTER_NO_FIRE".equals(defect.type()))
        .toList();

    assertEquals(1, defects.size());
    assertEquals("enc-visual", defects.get(0).encounterId());
  }

  @Test
  void sparseFramesDoNotCreateAFalseObservationSegment() throws Exception {
    String player = "player-sparse";
    StringBuilder events = new StringBuilder();
    events.append(v4Event(1, 10, "PLAYER_FRAME", null, player, "PLAYER", null,
        null, "match-test",
        "{\"position\":\"(0.0,64.0,0.0)\",\"horizontalSpeed\":\"0.0\","
            + "\"movementFlags\":\"normal\",\"ads\":\"true\",\"crouching\":\"false\"}"));
    events.append(v4Event(2, 210, "PLAYER_FRAME", null, player, "PLAYER", null,
        null, "match-test",
        "{\"position\":\"(0.0,64.0,0.0)\",\"horizontalSpeed\":\"0.0\","
            + "\"movementFlags\":\"normal\",\"ads\":\"true\",\"crouching\":\"false\"}"));
    Files.writeString(session.resolve("events-0001.ndjson"), events);

    HunterReplayAnalyzer.analyze(session);

    String report = Files.readString(session.resolve("player-behavior-summary.md"));
    assertFalse(report.contains("- OBSERVE"));
  }

  @Test
  void crouchingWithoutTacticalContextIsWaitingNotAmbush() throws Exception {
    String player = "player-waiting";
    StringBuilder events = new StringBuilder();
    for (int index = 0; index < 6; index++) {
      events.append(v4Event(index + 1L, index * 4L, "PLAYER_FRAME", null, player, "PLAYER", null,
          null, "match-test",
          "{\"position\":\"(0.0,64.0,0.0)\",\"horizontalSpeed\":\"0.0\","
              + "\"movementFlags\":\"normal\",\"crouching\":\"true\"}"));
    }
    Files.writeString(session.resolve("events-0001.ndjson"), events);

    HunterReplayAnalyzer.analyze(session);

    String report = Files.readString(session.resolve("player-behavior-summary.md"));
    assertTrue(report.contains("- WAITING confidence="));
    assertFalse(report.contains("- WAITING_OR_AMBUSH"));
  }

  @Test
  void airborneAndTeleportedFramesDoNotInflateNormalMovementStatistics() throws Exception {
    String player = "player-special-motion";
    String events = v4Event(1, 10, "PLAYER_FRAME", null, player, "PLAYER", null,
        null, "match-test",
        "{\"position\":\"(100.0,80.0,100.0)\",\"horizontalSpeed\":\"4.0\","
            + "\"movementFlags\":\"normal\",\"teleported\":\"true\","
            + "\"airborne\":\"true\",\"jumped\":\"false\"}");
    Files.writeString(session.resolve("events-0001.ndjson"), events);

    HunterReplayAnalyzer.analyze(session);

    String report = Files.readString(session.resolve("player-behavior-summary.md"));
    assertTrue(report.contains("normal movement samples=0"));
    assertTrue(report.contains("jump ratio=0.000"));
  }

  @Test
  void knockbackFramesDoNotInflateNormalMovementStatistics() throws Exception {
    String player = "player-knockback";
    String events = v4Event(1, 10, "PLAYER_FRAME", null, player, "PLAYER", null,
        null, "match-test",
        "{\"position\":\"(1.0,64.0,0.0)\",\"horizontalSpeed\":\"1.5\","
            + "\"movementFlags\":\"knockback-window\",\"teleported\":\"false\"}");
    Files.writeString(session.resolve("events-0001.ndjson"), events);

    HunterReplayAnalyzer.analyze(session);

    String report = Files.readString(session.resolve("player-behavior-summary.md"));
    assertTrue(report.contains("normal movement samples=0"));
  }

  @Test
  void periodicRegenerationIsOneRecoveryEpisode() throws Exception {
    String player = "player-recovery";
    StringBuilder events = new StringBuilder();
    for (int index = 0; index < 12; index++) {
      long tick = index * 4L;
      int health = tick >= 44L ? 65 : tick >= 24L ? 60 : tick >= 4L ? 55 : 50;
      events.append(v4Event(index + 1L, tick, "PLAYER_FRAME", null, player, "PLAYER", null,
          null, "match-test",
          "{\"position\":\"(0.0,64.0,0.0)\",\"horizontalSpeed\":\"0.0\","
              + "\"movementFlags\":\"normal\",\"health\":\"" + health + "\"}"));
    }
    Files.writeString(session.resolve("events-0001.ndjson"), events);

    HunterReplayAnalyzer.analyze(session);

    String report = Files.readString(session.resolve("player-behavior-summary.md"));
    assertEquals(1, occurrences(report, "- RECOVER_OR_HEAL"));
  }

  @Test
  void onePlayersShotsAreAttributedToOnlyTheirExplicitEncounter() throws Exception {
    String player = "player-shooter";
    String hunterA = "hunter-a";
    String hunterB = "hunter-b";
    StringBuilder events = new StringBuilder();
    long sequence = 1L;
    events.append(v4Event(sequence++, 10, "ENCOUNTER", null, player, "PLAYER", hunterA,
        "enc-a", "match-test", "{\"phase\":\"start\"}"));
    events.append(v4Event(sequence++, 10, "ENCOUNTER", null, player, "PLAYER", hunterB,
        "enc-b", "match-test", "{\"phase\":\"start\"}"));
    for (int index = 0; index < 3; index++) {
      events.append(v4Event(sequence++, 20 + index * 5L, "PLAYER_ACTION", null, player, "PLAYER", hunterA,
          "enc-a", "match-test", "{\"action\":\"fire\",\"targetInference\":\"legal-los-aim-cone\"}"));
    }
    events.append(v4Event(sequence++, 60, "ENCOUNTER", null, player, "PLAYER", hunterA,
        "enc-a", "match-test", "{\"phase\":\"end\"}"));
    events.append(v4Event(sequence, 60, "ENCOUNTER", null, player, "PLAYER", hunterB,
        "enc-b", "match-test", "{\"phase\":\"end\"}"));
    Files.writeString(session.resolve("events-0001.ndjson"), events);

    HunterReplayAnalyzer.analyze(session);

    String report = Files.readString(session.resolve("player-behavior-summary.md"));
    assertEquals(1, occurrences(report, "- SUPPRESSION_OR_ENGAGE"));
  }

  @Test
  void anApproachingHunterDoesNotMakeAStationaryPlayerLookLikeThePursuer() throws Exception {
    String player = "player-stationary";
    String hunter = "hunter-approaching";
    StringBuilder events = new StringBuilder();
    long sequence = 1L;
    events.append(v4Event(sequence++, 0, "ENCOUNTER", null, player, "PLAYER", hunter,
        "enc-pursuit", "match-test", "{\"phase\":\"start\"}"));
    for (int index = 0; index < 6; index++) {
      long tick = index * 4L;
      events.append(v4Event(sequence++, tick, "PLAYER_FRAME", null, player, "PLAYER", hunter,
          "enc-pursuit", "match-test",
          "{\"position\":\"(0.0,64.0,0.0)\",\"horizontalSpeed\":\"0.0\",\"movementFlags\":\"normal\"}"));
      events.append(v4Event(sequence++, tick, "INPUT", hunter, hunter, "HUNTER", player,
          "enc-pursuit", "match-test",
          "{\"position\":\"(" + (20 - index * 2) + ",64.0,0.0)\",\"horizontalSpeed\":\"0.2\",\"movementFlags\":\"normal\"}"));
    }
    events.append(v4Event(sequence, 30, "ENCOUNTER", null, player, "PLAYER", hunter,
        "enc-pursuit", "match-test", "{\"phase\":\"end\"}"));
    Files.writeString(session.resolve("events-0001.ndjson"), events);

    HunterReplayAnalyzer.analyze(session);

    String report = Files.readString(session.resolve("player-behavior-summary.md"));
    assertFalse(report.contains("- PURSUIT"));
  }

  @Test
  void directorDeferredHunterContactIsNotReportedAsAimOverride() throws Exception {
    Files.writeString(
        session.resolve("events-0001.ndjson"),
        event(1, 10, "PERCEPTION", "decision-a", "plan-a", "route-a", "{\"visible\":\"target-a\"}")
            + event(2, 10, "DIRECTOR", "decision-a", "plan-a", "route-a",
                "{\"result\":\"observe-without-immediate-duel\"}"));
    Files.writeString(
        session.resolve("frames-0001.ndjson"),
        event(3, 10, "ACTION_FRAME", "decision-a", "plan-a", "route-a",
            "{\"movement\":\"HOLD\",\"weapon\":\"HOLD\",\"owners\":\"{VIEW=vigilance}\"}"));

    HunterReplayAnalyzer.Analysis analysis = HunterReplayAnalyzer.analyze(session);

    assertEquals(0L, analysis.defects().stream()
        .filter(defect -> "COMBAT_AIM_OVERRIDDEN".equals(defect.type())).count());
  }

  private static int occurrences(String text, String needle) {
    int count = 0;
    int offset = 0;
    while ((offset = text.indexOf(needle, offset)) >= 0) {
      count++;
      offset += needle.length();
    }
    return count;
  }

  private static String v4Event(
      long sequence,
      long sessionTick,
      String type,
      String hunterId,
      String actorId,
      String actorKind,
      String counterpart,
      String encounter,
      String match,
      String payload) {
    return "{\"schemaVersion\":4,\"sequence\":" + sequence
        + ",\"sessionTick\":" + sessionTick
        + ",\"gameTime\":" + sessionTick
        + ",\"monitorId\":1,\"hunterId\":" + json(hunterId)
        + ",\"actorId\":" + json(actorId)
        + ",\"actorKind\":" + json(actorKind)
        + ",\"counterpartActorId\":" + json(counterpart)
        + ",\"encounterId\":" + json(encounter)
        + ",\"matchId\":" + json(match)
        + ",\"decisionId\":null,\"planId\":null,\"evidenceId\":null,\"routeId\":null,"
        + "\"type\":" + json(type) + ",\"payload\":" + payload + "}\n";
  }

  private static String json(String value) {
    return value == null ? "null" : "\"" + value + "\"";
  }

  private static String eventWithSession(
      long sequence, long sessionTick, long gameTime, String type, String payload) {
    return "{\"schemaVersion\":3,\"sequence\":"
        + sequence
        + ",\"sessionTick\":"
        + sessionTick
        + ",\"gameTime\":"
        + gameTime
        + ",\"monitorId\":1,\"hunterId\":\"hunter-a\",\"matchId\":\"match-a\","
        + "\"decisionId\":\"decision-a\",\"planId\":\"plan-a\",\"evidenceId\":null,"
        + "\"routeId\":\"route-a\",\"type\":\""
        + type
        + "\",\"payload\":"
        + payload
        + "}\n";
  }

  private static String event(
      long sequence,
      long gameTime,
      String type,
      String decisionId,
      String planId,
      String routeId,
      String payload) {
    return "{\"schemaVersion\":2,\"sequence\":"
        + sequence
        + ",\"gameTime\":"
        + gameTime
        + ",\"monitorId\":1,\"hunterId\":\"hunter-a\",\"matchId\":\"match-a\","
        + "\"decisionId\":\""
        + decisionId
        + "\",\"planId\":\""
        + planId
        + "\",\"evidenceId\":null,\"routeId\":\""
        + routeId
        + "\",\"type\":\""
        + type
        + "\",\"payload\":"
        + payload
        + "}\n";
  }
}
