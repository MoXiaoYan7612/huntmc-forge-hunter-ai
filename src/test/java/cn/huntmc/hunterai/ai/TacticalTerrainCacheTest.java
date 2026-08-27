package cn.huntmc.hunterai.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TacticalTerrainCacheTest {
  @Test
  void terrainExpiresAtOneHundredTicks() {
    assertFalse(TacticalTerrainCache.expired(20L, 119L));
    assertTrue(TacticalTerrainCache.expired(20L, 120L));
  }

  @Test
  void timeRollbackInvalidatesOldWorldEntries() {
    assertTrue(TacticalTerrainCache.expired(200L, 10L));
  }
}
