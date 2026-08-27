package cn.huntmc.hunterai.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ContactSetTest {
  @Test
  void confidenceDecayDependsOnElapsedTicksInsteadOfSnapshotCount() {
    UUID target = new UUID(3L, 7L);
    ContactSet everyTick = new ContactSet();
    ContactSet once = new ContactSet();
    everyTick.observe(target, Vec3.ZERO, Vec3.ZERO, 100L);
    once.observe(target, Vec3.ZERO, Vec3.ZERO, 100L);

    for (long now = 101L; now <= 120L; now++) everyTick.prune(now);
    once.prune(120L);

    assertEquals(
        once.snapshot().get(0).confidence(),
        everyTick.snapshot().get(0).confidence(),
        1.0E-9D);
  }

  @Test
  void directDamageCreatesAnEstimatedContactWithoutMakingItVisible() {
    UUID hunter = new UUID(1L, 2L);
    UUID attacker = new UUID(8L, 9L);
    Vec3 sampledPosition = new Vec3(14.0D, 65.0D, -3.0D);
    HunterWorldModel model = new HunterWorldModel();

    model.damaged(hunter, attacker, sampledPosition, 100L, "damage:" + attacker);

    ContactSet.Contact contact = model.snapshot(100L).contacts().get(0);
    assertEquals(attacker, contact.id());
    assertEquals(sampledPosition, contact.region().center());
    assertFalse(contact.currentlyVisible());
  }
}
