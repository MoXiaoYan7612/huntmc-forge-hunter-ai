package cn.huntmc.hunterai.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VigilanceUncertaintyTest {
  @Test
  void neverObservedSectorStartsUnknownAndObservedSectorAgesNormally() {
    assertEquals(160L, VigilanceSystem.unseenTicks(Long.MIN_VALUE, 20L));
    assertEquals(0L, VigilanceSystem.unseenTicks(100L, 80L));
    assertTrue(VigilanceSystem.unseenTicks(100L, 180L) > 0L);
  }
}
