package cn.huntmc.hunterai.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.huntmc.hunterai.model.ContactSet;
import cn.huntmc.hunterai.model.EvidenceRegion;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ContactAttentionPolicyTest {
  private static final UUID CURRENT = new UUID(1L, 1L);
  private static final UUID ALTERNATIVE = new UUID(2L, 2L);

  @Test
  void smallScoreDifferenceDoesNotCauseTargetFlapping() {
    ContactAttentionPolicy.Choice choice = ContactAttentionPolicy.select(
        List.of(
            contact(CURRENT, new Vec3(18.0D, 64.0D, 0.0D), 98L, 0.72D, false),
            contact(ALTERNATIVE, new Vec3(16.0D, 64.0D, 0.0D), 100L, 0.76D, false)),
        CURRENT,
        null,
        null,
        new Vec3(0.0D, 64.0D, 0.0D),
        100L);

    assertEquals(CURRENT, choice.targetId());
  }

  @Test
  void legalVisualOrDirectAttackerCanPreemptStickyAttention() {
    List<ContactSet.Contact> contacts = List.of(
        contact(CURRENT, new Vec3(8.0D, 64.0D, 0.0D), 100L, 1.0D, false),
        contact(ALTERNATIVE, new Vec3(14.0D, 64.0D, 0.0D), 100L, 0.80D, true));

    assertEquals(
        ALTERNATIVE,
        ContactAttentionPolicy.select(
            contacts, CURRENT, ALTERNATIVE, null, new Vec3(0.0D, 64.0D, 0.0D), 100L)
            .targetId());
    assertEquals(
        ALTERNATIVE,
        ContactAttentionPolicy.select(
            contacts, CURRENT, null, ALTERNATIVE, new Vec3(0.0D, 64.0D, 0.0D), 100L)
            .targetId());
  }

  @Test
  void staleWeakContactExpiresButRecentAttackerIsRetained() {
    ContactSet.Contact weak =
        contact(ALTERNATIVE, new Vec3(14.0D, 64.0D, 0.0D), 100L, 0.05D, false);

    assertEquals(
        null,
        ContactAttentionPolicy.select(
            List.of(weak), null, null, null, new Vec3(0.0D, 64.0D, 0.0D), 150L)
            .targetId());
    assertEquals(
        ALTERNATIVE,
        ContactAttentionPolicy.select(
            List.of(weak), null, null, ALTERNATIVE, new Vec3(0.0D, 64.0D, 0.0D), 150L)
            .targetId());
  }

  private static ContactSet.Contact contact(
      UUID id, Vec3 position, long evidenceAt, double confidence, boolean visible) {
    return new ContactSet.Contact(
        id,
        new EvidenceRegion(position, 1.0D, Vec3.ZERO, 0.05D),
        evidenceAt,
        evidenceAt,
        confidence,
        visible);
  }
}
