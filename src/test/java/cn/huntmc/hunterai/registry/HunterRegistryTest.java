package cn.huntmc.hunterai.registry;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class HunterRegistryTest {
  @Test
  void keepsMovementSamplesThroughTheirRetentionWindow() {
    assertFalse(HunterRegistry.isMovementSampleExpired(100L, 300L));
  }

  @Test
  void expiresMovementSamplesAfterTheirRetentionWindow() {
    assertTrue(HunterRegistry.isMovementSampleExpired(100L, 301L));
  }

  @Test
  void doesNotBroadcastNoiseBackToItsEmitter() {
    UUID emitter = new UUID(1L, 2L);

    assertFalse(HunterRegistry.shouldReceiveNoise(emitter, emitter));
    assertTrue(HunterRegistry.shouldReceiveNoise(new UUID(3L, 4L), emitter));
    assertTrue(HunterRegistry.shouldReceiveNoise(emitter, null));
  }

  @Test
  void serverPressureUsesTickWorkTimeThresholds() {
    assertEquals(HunterRegistry.ServerPressure.NORMAL, HunterRegistry.classifyServerPressure(44_999_999L));
    assertEquals(HunterRegistry.ServerPressure.ELEVATED, HunterRegistry.classifyServerPressure(45_000_000L));
    assertEquals(HunterRegistry.ServerPressure.CRITICAL, HunterRegistry.classifyServerPressure(50_000_000L));
  }
}
