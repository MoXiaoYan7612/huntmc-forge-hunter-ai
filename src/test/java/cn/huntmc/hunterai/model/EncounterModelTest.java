package cn.huntmc.hunterai.model;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class EncounterModelTest {
  @Test
  void gunshotsAloneDoNotInventAMultiPartyFight() {
    EncounterModel encounter = new EncounterModel();
    for (int index = 0; index < 4; index++) {
      UUID emitter = new UUID(0L, index + 1L);
      encounter.accept(new Evidence(
          UUID.randomUUID(), Evidence.Type.GUNSHOT, null,
          new EvidenceRegion(new Vec3(index * 4.0D, 64.0D, 0.0D), 5.0D, Vec3.ZERO, 0.0D),
          100L + index, 200L, 0.72D, "sound:" + emitter));
    }

    assertFalse(encounter.snapshot(110L).multiPartyFight());
  }

  @Test
  void visualAndDamageFromOneActorStillCountAsOneParty() {
    EncounterModel encounter = new EncounterModel();
    UUID actor = UUID.randomUUID();
    encounter.accept(new Evidence(
        UUID.randomUUID(), Evidence.Type.VISUAL, actor,
        new EvidenceRegion(Vec3.ZERO, 1.0D, Vec3.ZERO, 0.0D),
        100L, 200L, 1.0D, "visual:" + actor));
    encounter.accept(new Evidence(
        UUID.randomUUID(), Evidence.Type.DAMAGE, actor,
        new EvidenceRegion(Vec3.ZERO, 3.0D, Vec3.ZERO, 0.0D),
        101L, 200L, 0.95D, "damage:" + actor));
    encounter.accept(new Evidence(
        UUID.randomUUID(), Evidence.Type.GUNSHOT, null,
        new EvidenceRegion(Vec3.ZERO, 4.0D, Vec3.ZERO, 0.0D),
        102L, 200L, 0.72D, "sound:" + actor));

    assertFalse(encounter.snapshot(110L).multiPartyFight());
  }
}
