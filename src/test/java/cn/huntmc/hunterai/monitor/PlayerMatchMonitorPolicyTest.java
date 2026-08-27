package cn.huntmc.hunterai.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.huntmc.bridge.contract.BridgeProtocol;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlayerMatchMonitorPolicyTest {
  @Test
  void regularSamplingUsesFourTickCadence() {
    assertTrue(PlayerMatchMonitor.shouldRecordRegularFrame(0));
    assertFalse(PlayerMatchMonitor.shouldRecordRegularFrame(3));
    assertTrue(PlayerMatchMonitor.shouldRecordRegularFrame(4));
  }

  @Test
  void encounterEndsOnlyAfterTwoHundredQuietTicks() {
    assertFalse(PlayerMatchMonitor.encounterExpired(100, 299));
    assertTrue(PlayerMatchMonitor.encounterExpired(100, 300));
  }

  @Test
  void pursuitRefreshRequiresCloseRangeAndMovementTowardTheOpponent() {
    assertFalse(PlayerMatchMonitor.isDeliberatePursuit(121.0D, 0.20D, 0.15D, 0.0D));
    assertFalse(PlayerMatchMonitor.isDeliberatePursuit(40.0D, 0.20D, 0.01D, 0.02D));
    assertTrue(PlayerMatchMonitor.isDeliberatePursuit(40.0D, 0.20D, 0.08D, 0.0D));
  }

  @Test
  void preRollIsBoundedToOneHundredFrames() {
    assertEquals(0, PlayerMatchMonitor.preRollStartIndex(100));
    assertEquals(1, PlayerMatchMonitor.preRollStartIndex(101));
    assertEquals(57, PlayerMatchMonitor.preRollStartIndex(157));
  }

  @Test
  void losBudgetStartRotatesAcrossPairs() {
    assertEquals(0, PlayerMatchMonitor.losStartIndex(0, 4));
    assertEquals(1, PlayerMatchMonitor.losStartIndex(1, 4));
    assertEquals(3, PlayerMatchMonitor.losStartIndex(7, 4));
  }

  @Test
  void onlyAiParticipatingRostersStartAndTheyUseFortyTickTail() {
    UUID match = UUID.randomUUID();
    BridgeProtocol.MatchActor player = new BridgeProtocol.MatchActor(
        UUID.randomUUID(), match, BridgeProtocol.MatchActorKind.PLAYER, "a", "world", "ALIVE", false);
    BridgeProtocol.MatchActor hunter = new BridgeProtocol.MatchActor(
        UUID.randomUUID(), match, BridgeProtocol.MatchActorKind.HUNTER, "b", "world", "ALIVE", false);

    assertFalse(PlayerMatchMonitor.hasHunter(List.of(player)));
    assertTrue(PlayerMatchMonitor.hasHunter(List.of(hunter)));
    assertTrue(PlayerMatchMonitor.hasHunter(List.of(player, hunter)));
    assertFalse(PlayerMatchMonitor.rosterGraceExpired(100, 139));
    assertTrue(PlayerMatchMonitor.rosterGraceExpired(100, 140));
  }

  @Test
  void fireTargetInferenceRequiresTheShootersOwnLineOfSight() {
    assertFalse(PlayerMatchMonitor.isLegalFireInference(false, 0.5D));
    assertTrue(PlayerMatchMonitor.isLegalFireInference(true, 5.0D));
    assertFalse(PlayerMatchMonitor.isLegalFireInference(true, 23.0D));
  }
}
