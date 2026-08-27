package cn.huntmc.hunterai.navigation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class RouteFailureMemoryTest {
  @Test
  void blocksTheFailedTargetCellButExpiresItsBackoff() {
    RouteFailureMemory memory = new RouteFailureMemory();
    Vec3 failed = new Vec3(20.5D, 64.0D, -8.5D);

    memory.record(UUID.randomUUID(), failed, "path-unavailable", 100L);

    assertTrue(memory.blocked(failed.add(0.1D, 0.0D, 0.1D), 199L));
    assertFalse(memory.blocked(failed, 200L));
  }

  @Test
  void repeatedFailuresIncreaseThePenaltyAndBackoff() {
    RouteFailureMemory memory = new RouteFailureMemory();
    Vec3 failed = new Vec3(4.0D, 70.0D, 4.0D);

    memory.record(UUID.randomUUID(), failed, "stalled", 10L);
    double firstPenalty = memory.penalty(failed, 20L);
    memory.record(UUID.randomUUID(), failed, "stalled", 30L);

    assertTrue(memory.penalty(failed, 40L) > firstPenalty);
    assertTrue(memory.blocked(failed, 229L));
  }

  @Test
  void aNewRouteIdCannotImmediatelyRetryTheSameEndpointButMayUseANearbyRecoveryLane() {
    RouteFailureMemory memory = new RouteFailureMemory();
    Vec3 failed = new Vec3(100.0D, 64.0D, -600.0D);

    memory.record(UUID.randomUUID(), failed, "navigation-rejected", 20L);

    assertTrue(memory.blocked(failed.add(1.5D, 1.0D, 0.5D), 21L));
    assertFalse(memory.blocked(failed.add(5.0D, 0.0D, 0.0D), 21L));
  }

  @Test
  void originAwareBackoffBlocksTheSameCorridorButAllowsADifferentApproach() {
    RouteFailureMemory memory = new RouteFailureMemory();
    Vec3 origin = new Vec3(0.0D, 64.0D, 0.0D);
    Vec3 failed = new Vec3(30.0D, 64.0D, 0.0D);

    memory.record(UUID.randomUUID(), origin, failed, "stalled", 100L);

    assertTrue(memory.blocked(
        origin.add(1.0D, 0.0D, 0.5D), failed.add(3.0D, 0.0D, 0.5D), 101L));
    assertTrue(memory.blocked(
        origin.add(1.0D, 0.0D, 0.5D), failed.add(40.0D, 0.0D, 0.5D), 101L));
    assertFalse(memory.blocked(
        origin.add(0.0D, 0.0D, 8.0D), failed.add(0.0D, 0.0D, 8.0D), 101L));
  }

  @Test
  void aSecondNearbyFailureEscalatesToARegionBackoff() {
    RouteFailureMemory memory = new RouteFailureMemory();
    Vec3 first = new Vec3(80.0D, 64.0D, -560.0D);
    Vec3 recoveryProbe = first.add(5.0D, 0.0D, 3.0D);

    memory.record(UUID.randomUUID(), first, "route-stalled", 100L);
    assertFalse(memory.blocked(recoveryProbe, 101L));

    memory.record(UUID.randomUUID(), recoveryProbe, "partial-no-progress", 102L);

    assertTrue(memory.blocked(first.add(-1.5D, 0.0D, 1.0D), 103L));
    assertTrue(memory.blocked(recoveryProbe.add(6.0D, 0.0D, 0.0D), 103L));
    assertFalse(memory.blocked(recoveryProbe.add(9.0D, 0.0D, 0.0D), 103L));
  }
}
