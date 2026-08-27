package cn.huntmc.hunterai.ai;

import cn.huntmc.hunterai.model.ContactSet;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;

/**
 * Selects one attention subject from decaying contact estimates.  It never receives an entity
 * reference, so changing combat attention cannot turn memory into live wall tracking.
 */
public final class ContactAttentionPolicy {
  private static final long MAX_ATTENTION_AGE_TICKS = 140L;
  private static final long LOW_CONFIDENCE_GRACE_TICKS = 40L;
  private static final double NORMAL_SWITCH_MARGIN = 0.18D;

  private ContactAttentionPolicy() {}

  public static Choice select(
      List<ContactSet.Contact> contacts,
      UUID currentTarget,
      UUID visibleTarget,
      UUID recentAttacker,
      Vec3 selfPosition,
      long now) {
    if (contacts == null || contacts.isEmpty() || selfPosition == null) return Choice.none();
    List<ScoredContact> scored = contacts.stream()
        .filter(contact -> contact != null && contact.id() != null && contact.region() != null)
        .filter(contact -> occurredWithin(contact.lastEvidenceAt(), now, MAX_ATTENTION_AGE_TICKS))
        // Give a fresh, weak bearing time to be confirmed, but do not let stale low-confidence
        // evidence occupy the combat lane long after the target has gone.
        .filter(contact -> contact.id().equals(recentAttacker)
            || contact.confidence() >= 0.10D
            || now - contact.lastEvidenceAt() <= LOW_CONFIDENCE_GRACE_TICKS)
        .map(contact -> score(contact, currentTarget, visibleTarget, recentAttacker, selfPosition, now))
        .sorted(Comparator.comparingDouble(ScoredContact::score).reversed())
        .toList();
    if (scored.isEmpty()) return Choice.none();

    ScoredContact best = scored.get(0);
    ScoredContact current = scored.stream()
        .filter(candidate -> candidate.contact().id().equals(currentTarget))
        .findFirst()
        .orElse(null);
    boolean forced = best.contact().id().equals(visibleTarget)
        || best.contact().id().equals(recentAttacker);
    if (!forced
        && current != null
        && !current.contact().id().equals(best.contact().id())
        && best.score() < current.score() + NORMAL_SWITCH_MARGIN) {
      best = current;
    }
    ContactSet.Contact selected = best.contact();
    String reason = selected.id().equals(visibleTarget)
        ? "current-legal-visual"
        : selected.id().equals(recentAttacker)
            ? "recent-direct-attacker"
            : selected.id().equals(currentTarget)
                ? "retained-attention-hysteresis"
                : "stronger-decaying-contact";
    return new Choice(
        selected.id(),
        selected.region().center(),
        selected.lastEvidenceAt(),
        selected.confidence(),
        best.score(),
        reason);
  }

  private static ScoredContact score(
      ContactSet.Contact contact,
      UUID currentTarget,
      UUID visibleTarget,
      UUID recentAttacker,
      Vec3 selfPosition,
      long now) {
    long age = Math.max(0L, now - contact.lastEvidenceAt());
    double recency = Math.max(0.0D, 1.0D - age / (double) MAX_ATTENTION_AGE_TICKS);
    double distance = selfPosition.distanceTo(contact.region().center());
    double proximity = 1.0D / (1.0D + distance / 18.0D);
    double score = contact.confidence() * 0.35D + recency * 0.32D + proximity * 0.18D;
    if (contact.id().equals(currentTarget)) score += 0.14D;
    if (contact.currentlyVisible() && contact.id().equals(visibleTarget)) score += 0.55D;
    if (contact.id().equals(recentAttacker)) score += 0.48D;
    return new ScoredContact(contact, score);
  }

  private static boolean occurredWithin(long eventTime, long now, long window) {
    return eventTime != Long.MIN_VALUE && now >= eventTime && now - eventTime <= window;
  }

  public record Choice(
      UUID targetId,
      Vec3 estimatedPosition,
      long evidenceAt,
      double confidence,
      double score,
      String reason) {
    public static Choice none() {
      return new Choice(null, null, Long.MIN_VALUE, 0.0D, 0.0D, "no-credible-contact");
    }

    public boolean present() {
      return targetId != null && estimatedPosition != null;
    }
  }

  private record ScoredContact(ContactSet.Contact contact, double score) {}
}
