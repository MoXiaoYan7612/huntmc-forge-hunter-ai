package cn.huntmc.hunterai.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class EvidenceTest {
  @Test
  void confidenceDecaysToZeroAndRegionExpandsAlongItsMotionTrend() {
    EvidenceRegion region =
        new EvidenceRegion(new Vec3(4.0D, 64.0D, 8.0D), 2.0D, new Vec3(0.1D, 0.0D, -0.2D), 0.05D);
    Evidence evidence =
        new Evidence(
            UUID.randomUUID(),
            Evidence.Type.LOST_CONTACT,
            UUID.randomUUID(),
            region,
            100L,
            200L,
            0.8D,
            "lost:test");

    assertEquals(0.8D, evidence.confidence(100L), 0.0001D);
    assertEquals(0.4D, evidence.confidence(150L), 0.0001D);
    assertEquals(0.0D, evidence.confidence(200L), 0.0001D);
    EvidenceRegion aged = region.atAge(20L);
    assertEquals(new Vec3(6.0D, 64.0D, 4.0D), aged.center());
    assertEquals(3.0D, aged.radius(), 0.0001D);
  }

  @Test
  void modalitiesFromTheSameActorShareOneIndependenceKey() {
    UUID actor = UUID.randomUUID();
    Evidence visual = new Evidence(
        UUID.randomUUID(), Evidence.Type.VISUAL, actor,
        new EvidenceRegion(Vec3.ZERO, 1.0D, Vec3.ZERO, 0.0D),
        1L, 20L, 1.0D, "visual:" + actor);
    Evidence sound = new Evidence(
        UUID.randomUUID(), Evidence.Type.GUNSHOT, null,
        new EvidenceRegion(Vec3.ZERO, 4.0D, Vec3.ZERO, 0.0D),
        1L, 20L, 0.7D, "sound:" + actor);

    assertEquals(visual.independenceKey(), sound.independenceKey());
  }
}
