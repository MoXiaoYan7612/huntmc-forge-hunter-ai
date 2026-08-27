package cn.huntmc.hunterai.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.huntmc.hunterai.ai.EnemyBelief;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class TargetLifecycleInvalidationTest {
  @Test
  void worldModelInvalidationRemovesContactAndSubjectEvidenceWithoutCreatingDeathEvidence() {
    HunterWorldModel model = new HunterWorldModel();
    UUID hunter = UUID.randomUUID();
    UUID target = UUID.randomUUID();
    model.damaged(hunter, target, new Vec3(4.0D, 64.0D, 0.0D), 100L, "damage:" + target);

    assertTrue(model.snapshot(100L).contacts().stream().anyMatch(contact -> target.equals(contact.id())));
    model.invalidateContact(target);
    HunterWorldModel.Snapshot snapshot = model.snapshot(101L);

    assertFalse(snapshot.contacts().stream().anyMatch(contact -> target.equals(contact.id())));
    assertFalse(snapshot.evidence().stream().anyMatch(evidence -> target.equals(evidence.subjectId())));
    assertFalse(snapshot.evidence().stream().anyMatch(evidence -> evidence.type() == Evidence.Type.DEATH));
  }

  @Test
  void forgettingVisualTargetRetainsIndependentSoundMemory() {
    EnemyBelief belief = new EnemyBelief();
    UUID target = UUID.randomUUID();
    Vec3 sound = new Vec3(9.0D, 64.0D, 2.0D);
    belief.observe(target, new Vec3(4.0D, 64.0D, 0.0D), 100L);
    belief.hear(sound, 0.8F, 101L);

    belief.forgetTarget(target, 102L);

    assertNull(belief.lastSeenTarget());
    assertTrue(belief.hasEvidence());
    assertTrue(sound.distanceTo(belief.predictedPosition(102L)) < 1.0D);
  }
}
