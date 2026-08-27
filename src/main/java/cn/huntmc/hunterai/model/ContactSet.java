package cn.huntmc.hunterai.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;

/** Long-lived contact estimates contain no entity reference or hidden live state. */
public final class ContactSet {
  private static final long CONTACT_RETENTION_TICKS = 240L;
  private final Map<UUID, MutableContact> contacts = new HashMap<>();

  public void observe(UUID id, Vec3 position, Vec3 velocity, long now) {
    if (id == null || position == null) return;
    MutableContact contact = contacts.computeIfAbsent(id, MutableContact::new);
    contact.region = new EvidenceRegion(position, 0.45D, velocity, 0.045D);
    contact.lastVisualAt = now;
    contact.lastEvidenceAt = now;
    contact.confidence = 1.0D;
    contact.confidenceUpdatedAt = now;
    contact.regionUpdatedAt = now;
    contact.currentlyVisible = true;
  }

  public void beginTick() {
    contacts.values().forEach(contact -> contact.currentlyVisible = false);
  }

  public void remove(UUID id) {
    if (id != null) contacts.remove(id);
  }

  public boolean contains(UUID id) {
    return id != null && contacts.containsKey(id);
  }

  public void addEvidence(Evidence evidence, long now) {
    if (evidence.subjectId() == null) return;
    MutableContact contact = contacts.computeIfAbsent(evidence.subjectId(), MutableContact::new);
    contact.region = evidence.region().atAge(Math.max(0L, now - evidence.observedAt()));
    contact.lastEvidenceAt = Math.max(contact.lastEvidenceAt, evidence.observedAt());
    decayConfidence(contact, now);
    contact.confidence = Math.max(contact.confidence, evidence.confidence(now));
    contact.confidenceUpdatedAt = now;
    contact.regionUpdatedAt = now;
  }

  public void prune(long now) {
    contacts.entrySet().removeIf(entry -> now - entry.getValue().lastEvidenceAt > CONTACT_RETENTION_TICKS);
    contacts.values().forEach(
        contact -> {
          decayConfidence(contact, now);
          long regionAge = Math.max(0L, now - contact.regionUpdatedAt);
          if (!contact.currentlyVisible && contact.region != null && regionAge > 0L) {
            contact.region = contact.region.atAge(regionAge);
            contact.regionUpdatedAt = now;
          }
        });
  }

  private static void decayConfidence(MutableContact contact, long now) {
    if (contact.confidenceUpdatedAt == Long.MIN_VALUE) {
      contact.confidenceUpdatedAt = now;
      return;
    }
    long elapsed = Math.max(0L, now - contact.confidenceUpdatedAt);
    if (elapsed == 0L) return;
    contact.confidence = Math.max(0.0D, contact.confidence * Math.pow(0.985D, elapsed));
    contact.confidenceUpdatedAt = now;
  }

  public List<Contact> snapshot() {
    List<Contact> result = new ArrayList<>();
    for (MutableContact contact : contacts.values()) {
      result.add(
          new Contact(
              contact.id,
              contact.region,
              contact.lastVisualAt,
              contact.lastEvidenceAt,
              contact.confidence,
              contact.currentlyVisible));
    }
    result.sort(Comparator.comparing(contact -> contact.id().toString()));
    return List.copyOf(result);
  }

  public record Contact(
      UUID id,
      EvidenceRegion region,
      long lastVisualAt,
      long lastEvidenceAt,
      double confidence,
      boolean currentlyVisible) {}

  private static final class MutableContact {
    private final UUID id;
    private EvidenceRegion region;
    private long lastVisualAt = Long.MIN_VALUE;
    private long lastEvidenceAt = Long.MIN_VALUE;
    private double confidence;
    private long confidenceUpdatedAt = Long.MIN_VALUE;
    private long regionUpdatedAt = Long.MIN_VALUE;
    private boolean currentlyVisible;

    private MutableContact(UUID id) {
      this.id = id;
    }
  }
}
