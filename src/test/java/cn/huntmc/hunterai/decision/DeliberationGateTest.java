package cn.huntmc.hunterai.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.huntmc.hunterai.model.ContactSet;
import cn.huntmc.hunterai.model.EncounterModel;
import cn.huntmc.hunterai.model.Evidence;
import cn.huntmc.hunterai.model.EvidenceRegion;
import cn.huntmc.hunterai.model.ExposureMemory;
import cn.huntmc.hunterai.model.HunterWorldModel;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class DeliberationGateTest {
  private static final UUID HUNTER = new UUID(1L, 2L);

  @Test
  void oneRemoteGunshotOnlyUpdatesMemory() {
    HunterWorldModel model = new HunterWorldModel();
    model.hear(HUNTER, new Vec3(80.0D, 64.0D, 0.0D), 1.0F, 100L, "weapon:a");

    var result = new DeliberationGate().assess(model.snapshot(100L), 100L, 4, false, false, false, false, 1.0D);

    assertEquals(DeliberationMode.NONE, result.mode());
    assertEquals("single-remote-sound-memory-only", result.reason());
  }

  @Test
  void repeatedSameSourceDoesNotBecomeIndependentEvidence() {
    Evidence first = evidence(Evidence.Type.GUNSHOT, "weapon:a", 100L, 0.80D, 5.0D);
    Evidence second = evidence(Evidence.Type.GUNSHOT, "weapon:a", 102L, 0.80D, 5.0D);
    var world = snapshot(List.of(first, second), false);

    var result = new DeliberationGate().assess(world, 102L, 4, false, false, false, false, 1.0D);

    assertEquals(0.45D, result.independentEvidence(), 0.0001D);
    assertEquals(DeliberationMode.NONE, result.mode());
  }

  @Test
  void freshDirectDamageAlwaysReceivesAtLeastAnalyticAssessment() {
    UUID attacker = new UUID(7L, 9L);
    HunterWorldModel model = new HunterWorldModel();
    model.damaged(
        HUNTER, attacker, new Vec3(12.0D, 64.0D, 12.0D), 100L, "damage:" + attacker);

    var result = new DeliberationGate().assess(
        model.snapshot(100L), 100L, 4, false, false, true, false, 0.20D);

    assertEquals(DeliberationMode.ANALYTIC, result.mode());
    assertEquals("direct-attack-analytic", result.reason());
  }

  @Test
  void visualAndMatchingGunshotCanTriggerRollout() {
    Evidence visual = evidence(Evidence.Type.VISUAL, "visual:enemy", 100L, 1.0D, 0.45D);
    Evidence gunshot = evidence(Evidence.Type.GUNSHOT, "weapon:enemy", 100L, 1.0D, 0.45D);
    ContactSet.Contact contact =
        new ContactSet.Contact(
            new UUID(5L, 6L), visual.region(), 100L, 100L, 1.0D, true);
    var world = new HunterWorldModel.Snapshot(
        List.of(contact),
        List.of(visual, gunshot),
        new EncounterModel.Snapshot(false, 0.4D, 2, 1, 0),
        new ExposureMemory.Snapshot(new double[8], 0.0D));

    var result = new DeliberationGate().assess(world, 100L, 4, false, false, true, false, 1.0D);

    assertEquals(DeliberationMode.ROLLOUT, result.mode());
    assertTrue(result.quality() >= DeliberationGate.ROLLOUT_THRESHOLD);
  }

  @Test
  void onlyNewConfirmedEnemyBypassesRolloutCooldown() {
    Evidence visual = evidence(Evidence.Type.VISUAL, "visual:enemy", 100L, 1.0D, 0.45D);
    Evidence gunshot = evidence(Evidence.Type.GUNSHOT, "weapon:enemy", 100L, 1.0D, 0.45D);
    ContactSet.Contact contact =
        new ContactSet.Contact(
            new UUID(5L, 6L), visual.region(), 100L, 100L, 1.0D, true);
    var world =
        new HunterWorldModel.Snapshot(
            List.of(contact),
            List.of(visual, gunshot),
            new EncounterModel.Snapshot(false, 0.4D, 2, 1, 0),
            new ExposureMemory.Snapshot(new double[8], 0.0D));
    DeliberationGate gate = new DeliberationGate();

    assertEquals(
        DeliberationMode.ROLLOUT,
        gate.assess(world, 100L, 4, false, false, true, false, 1.0D).mode());
    var stableContact = gate.assess(world, 101L, 4, false, false, false, false, 1.0D);

    assertEquals(DeliberationMode.ANALYTIC, stableContact.mode());
    assertEquals("rollout-cooldown", stableContact.reason());
  }

  private static Evidence evidence(
      Evidence.Type type, String source, long observedAt, double reliability, double radius) {
    return new Evidence(
        UUID.nameUUIDFromBytes((type + source + observedAt).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        type,
        null,
        new EvidenceRegion(new Vec3(12.0D, 64.0D, 12.0D), radius, Vec3.ZERO, 0.05D),
        observedAt,
        observedAt + 100L,
        reliability,
        source);
  }

  private static HunterWorldModel.Snapshot snapshot(List<Evidence> evidence, boolean visible) {
    return new HunterWorldModel.Snapshot(
        List.of(),
        evidence,
        new EncounterModel.Snapshot(false, 0.2D, 1, evidence.size(), 0),
        new ExposureMemory.Snapshot(new double[8], 0.0D));
  }
}
