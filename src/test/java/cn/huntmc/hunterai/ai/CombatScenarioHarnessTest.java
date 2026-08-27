package cn.huntmc.hunterai.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.huntmc.hunterai.action.MovementMode;
import cn.huntmc.hunterai.action.MovementProgressState;
import cn.huntmc.hunterai.action.WeaponCommand;
import org.junit.jupiter.api.Test;

class CombatScenarioHarnessTest {
  @Test
  void coverAndPlayerPreAimCannotConsumeAnAlignedMediumRangeShot() {
    CombatScenarioHarness harness = new CombatScenarioHarness();
    CombatScenarioHarness.Scenario scenario = new CombatScenarioHarness.Scenario(
        true, false, false, false, true, true, false, false, true, true, 8L, 18.0D);

    CombatScenarioHarness.Step first = harness.tick(100L, scenario, MovementProgressState.PATH_ACCEPTED);
    CombatScenarioHarness.Step next = harness.tick(101L, scenario, MovementProgressState.PROGRESSING);

    assertEquals(MovementMode.SPRINT, first.frame().movement());
    assertEquals(WeaponCommand.ADS_FIRE, first.frame().weapon());
    assertTrue(first.decision().immediateFireOpportunity());
    assertEquals(WeaponCommand.ADS_FIRE, next.frame().weapon());
    assertEquals(MovementProgressState.PROGRESSING, harness.previousResult().movementProgress());
  }

  @Test
  void closeEncounterKeepsReactionDelayThenFiresWhileMovingToCover() {
    CombatScenarioHarness harness = new CombatScenarioHarness();
    long delay = FirstTriggerGate.delayTicks(
        9.0D,
        false,
        java.util.UUID.fromString("57cb8b41-ddf7-4aba-b80a-5fb2cc1da763"),
        java.util.UUID.fromString("09ab4045-b9b0-447f-b8b1-4e74e3f175d6"),
        1L);
    CombatScenarioHarness.Scenario acquiring = new CombatScenarioHarness.Scenario(
        true, false, true, false, true, true, false, false, false, true, delay - 1L, 9.0D);
    CombatScenarioHarness.Scenario ready = new CombatScenarioHarness.Scenario(
        true, false, true, false, true, true, false, false, false, true, delay, 9.0D);

    assertEquals(
        WeaponCommand.ADS,
        harness.tick(100L, acquiring, MovementProgressState.PATH_ACCEPTED).frame().weapon());
    CombatScenarioHarness.Step firing = harness.tick(101L, ready, MovementProgressState.PATH_ACCEPTED);
    assertEquals(WeaponCommand.ADS_FIRE, firing.frame().weapon());
    assertEquals(MovementMode.SPRINT, firing.frame().movement());
  }

  @Test
  void confirmedDirectAttackerGetsAResponseAtTheTwoTickFloor() {
    CombatScenarioHarness harness = new CombatScenarioHarness();
    long delay = FirstTriggerGate.delayTicks(
        18.0D,
        true,
        java.util.UUID.fromString("57cb8b41-ddf7-4aba-b80a-5fb2cc1da763"),
        java.util.UUID.fromString("09ab4045-b9b0-447f-b8b1-4e74e3f175d6"),
        1L);
    CombatScenarioHarness.Scenario firstTick = new CombatScenarioHarness.Scenario(
        true, true, false, false, true, true, false, false, false, true, delay - 1L, 18.0D);
    CombatScenarioHarness.Scenario secondTick = new CombatScenarioHarness.Scenario(
        true, true, false, false, true, true, false, false, false, true, delay, 18.0D);

    assertEquals(
        WeaponCommand.ADS,
        harness.tick(100L, firstTick, MovementProgressState.PATH_ACCEPTED).frame().weapon());
    assertEquals(
        WeaponCommand.ADS_FIRE,
        harness.tick(101L, secondTick, MovementProgressState.PATH_ACCEPTED).frame().weapon());
  }

  @Test
  void emptyWeaponReleasesTheChannelToResourceRecovery() {
    CombatScenarioHarness harness = new CombatScenarioHarness();
    CombatScenarioHarness.Scenario empty = new CombatScenarioHarness.Scenario(
        true, false, false, true, false, false, false, false, true, true, 12L, 8.0D);

    CombatScenarioHarness.Step step = harness.tick(100L, empty, MovementProgressState.PATH_ACCEPTED);
    assertEquals(WeaponCommand.RELOAD, step.frame().weapon());
    assertEquals(
        "resource",
        step.frame().owners().get(cn.huntmc.hunterai.action.ActionChannel.WEAPON));
    assertFalse(step.decision().immediateFireOpportunity());
  }

  @Test
  void stalledCoverPathDoesNotChangeTheWeaponChannelOnTheNextTick() {
    CombatScenarioHarness harness = new CombatScenarioHarness();
    CombatScenarioHarness.Scenario scenario = new CombatScenarioHarness.Scenario(
        true, true, true, false, true, true, false, false, true, true, 3L, 7.0D);

    CombatScenarioHarness.Step stalled = harness.tick(100L, scenario, MovementProgressState.STALLED);
    CombatScenarioHarness.Step retry = harness.tick(101L, scenario, MovementProgressState.RECOVERING);

    assertEquals(MovementProgressState.STALLED, stalled.actuatorResult().movementProgress());
    assertEquals(WeaponCommand.ADS_FIRE, stalled.frame().weapon());
    assertEquals(WeaponCommand.ADS_FIRE, retry.frame().weapon());
  }

  @Test
  void survivalMovementUrgencyCannotBecomeASecondFirePermissionGate() {
    CombatScenarioHarness harness = new CombatScenarioHarness();
    CombatScenarioHarness.Scenario scenario = new CombatScenarioHarness.Scenario(
        true, false, false, false, true, true, false, true, true, true, 8L, 18.0D);

    CombatScenarioHarness.Step step =
        harness.tick(100L, scenario, MovementProgressState.PATH_ACCEPTED);

    assertTrue(step.decision().repositionMovementUrgent());
    assertEquals(FireGate.Status.FIRE, step.fireDecision().status());
    assertEquals(WeaponCommand.ADS_FIRE, step.frame().weapon());
  }
}
