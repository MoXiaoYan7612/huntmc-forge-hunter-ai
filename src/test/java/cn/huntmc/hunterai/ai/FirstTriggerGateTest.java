package cn.huntmc.hunterai.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class FirstTriggerGateTest {
  private static final UUID HUNTER =
      UUID.fromString("57cb8b41-ddf7-4aba-b80a-5fb2cc1da763");
  private static final UUID TARGET =
      UUID.fromString("09ab4045-b9b0-447f-b8b1-4e74e3f175d6");

  @Test
  void pointBlankContactKeepsAResponsiveSevenToNineTickTriggerWindow() {
    long delay = FirstTriggerGate.delayTicks(3.0D, false, HUNTER, TARGET, 1L);
    assertTrue(delay >= 7L && delay <= 9L);
  }

  @Test
  void ordinaryCloseContactNeverBecomesFasterAtSixToTenBlocks() {
    long sixBlocks = FirstTriggerGate.delayTicks(6.0D, false, HUNTER, TARGET, 2L);
    long nineBlocks = FirstTriggerGate.delayTicks(9.0D, false, HUNTER, TARGET, 2L);
    assertTrue(sixBlocks >= 6L && sixBlocks <= 8L);
    assertTrue(nineBlocks >= 5L && nineBlocks <= 7L);
    assertTrue(sixBlocks >= nineBlocks);
  }

  @Test
  void mediumAndLongRangeHaveNoSecondRecognitionDelay() {
    long transition = FirstTriggerGate.delayTicks(12.0D, false, HUNTER, TARGET, 3L);
    assertTrue(transition >= 2L && transition <= 4L);
    assertEquals(0L, FirstTriggerGate.delayTicks(14.0D, false, HUNTER, TARGET, 3L));
    assertEquals(0L, FirstTriggerGate.delayTicks(120.0D, false, HUNTER, TARGET, 3L));
    assertEquals(0L, FirstTriggerGate.delayTicks(Double.POSITIVE_INFINITY, false, HUNTER, TARGET, 3L));
  }

  @Test
  void confirmedDirectDamageAllowsReturnFireWithinTwoToFourTicks() {
    long delay = FirstTriggerGate.delayTicks(40.0D, true, HUNTER, TARGET, 4L);
    assertTrue(delay >= 2L && delay <= 4L);
  }

  @Test
  void genuinePreAimedAmbushKeepsItsStrategicAdvantage() {
    long delay = FirstTriggerGate.delayTicks(5.0D, false, true, HUNTER, TARGET, 5L);
    assertTrue(delay >= 2L && delay <= 4L);
  }

  @Test
  void longCloseReacquireCannotFireOnTheSameTick() {
    long eligible = FirstTriggerGate.reacquireEligibleAt(100L, 7.0D, HUNTER, TARGET, 8L);
    assertTrue(eligible >= 102L && eligible <= 104L);
    assertTrue(FirstTriggerGate.reacquireEligibleAt(100L, 12.0D, HUNTER, TARGET, 8L) > 100L);
    assertEquals(100L, FirstTriggerGate.reacquireEligibleAt(100L, 14.0D, HUNTER, TARGET, 8L));
  }

  @Test
  void varianceIsStableForOneContactButDiffersAcrossContactSequences() {
    long first = FirstTriggerGate.delayTicks(5.0D, false, HUNTER, TARGET, 10L);
    assertEquals(first, FirstTriggerGate.delayTicks(5.0D, false, HUNTER, TARGET, 10L));
    boolean foundDifferent = false;
    for (long sequence = 11L; sequence < 20L; sequence++) {
      if (FirstTriggerGate.delayTicks(5.0D, false, HUNTER, TARGET, sequence) != first) {
        foundDifferent = true;
        break;
      }
    }
    assertTrue(foundDifferent);
  }
}
