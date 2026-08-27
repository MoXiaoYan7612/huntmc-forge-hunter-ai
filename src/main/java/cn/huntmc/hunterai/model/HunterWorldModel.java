package cn.huntmc.hunterai.model;

import cn.huntmc.hunterai.ai.PerceptionSystem;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Mutable coordinator-owned belief state. Every published view is immutable. */
public final class HunterWorldModel {
  private final ContactSet contacts = new ContactSet();
  private final EncounterModel encounter = new EncounterModel();
  private final ExposureMemory exposure = new ExposureMemory();
  private final List<Evidence> evidence = new ArrayList<>();
  private long evidenceSequence;
  private UUID lastVisibleId;

  public void beginTick() {
    contacts.beginTick();
  }

  public Evidence observeVisual(UUID hunterId, LivingEntity target, long now) {
    Vec3 velocity = target.getDeltaMovement();
    Evidence item = create(
        hunterId,
        Evidence.Type.VISUAL,
        target.getUUID(),
        new EvidenceRegion(target.position(), 0.45D, velocity, 0.035D),
        now,
        now + 80L,
        1.0D,
        "visual:" + target.getUUID());
    contacts.observe(target.getUUID(), target.position(), velocity, now);
    lastVisibleId = target.getUUID();
    accept(item);
    return item;
  }

  public Evidence loseVisual(UUID hunterId, Vec3 lastKnown, long now) {
    if (lastVisibleId == null || lastKnown == null) return null;
    Evidence item = create(
        hunterId,
        Evidence.Type.LOST_CONTACT,
        lastVisibleId,
        new EvidenceRegion(lastKnown, 1.5D, Vec3.ZERO, 0.10D),
        now,
        now + 200L,
        0.78D,
        "lost:" + lastVisibleId);
    lastVisibleId = null;
    accept(item);
    return item;
  }

  public Evidence hear(UUID hunterId, Vec3 source, float strength, long now, String sourceKey) {
    double reliability = Math.max(0.25D, Math.min(0.80D, strength * 0.72D));
    Evidence item = create(
        hunterId,
        strength >= 0.85F ? Evidence.Type.GUNSHOT : Evidence.Type.MOVEMENT_SOUND,
        null,
        new EvidenceRegion(source, Math.max(3.0D, 12.0D * (1.0D - reliability)), Vec3.ZERO, 0.08D),
        now,
        now + (strength >= 0.85F ? 140L : 80L),
        reliability,
        sourceKey == null ? "sound:unknown" : sourceKey);
    accept(item);
    return item;
  }

  public Evidence damaged(UUID hunterId, Vec3 source, long now, String sourceKey) {
    return damaged(hunterId, null, source, now, sourceKey);
  }

  public Evidence damaged(
      UUID hunterId, UUID attackerId, Vec3 source, long now, String sourceKey) {
    exposure.damaged();
    Vec3 center = source == null ? Vec3.ZERO : source;
    Evidence item = create(
        hunterId,
        Evidence.Type.DAMAGE,
        attackerId,
        new EvidenceRegion(center, source == null ? 18.0D : 3.0D, Vec3.ZERO, 0.10D),
        now,
        now + 160L,
        source == null ? 0.65D : 0.95D,
        sourceKey == null ? "damage:unknown" : sourceKey);
    accept(item);
    return item;
  }

  public void recordShot() {
    exposure.fired();
  }

  public void confirmFacing(float yaw, long now) {
    exposure.confirmFacing(yaw, now);
  }

  public Evidence confirmDeath(UUID hunterId, UUID targetId, Vec3 position, long now) {
    if (targetId == null || position == null) return null;
    contacts.remove(targetId);
    evidence.removeIf(item -> targetId.equals(item.subjectId()));
    if (targetId.equals(lastVisibleId)) lastVisibleId = null;
    Evidence item = create(
        hunterId,
        Evidence.Type.DEATH,
        targetId,
        new EvidenceRegion(position, 0.5D, Vec3.ZERO, 0.0D),
        now,
        // A confirmed kill is a short-lived tactical landmark. It is long enough to hold an
        // angle or shadow the likely rescuer, but it expires before becoming omniscient memory.
        now + 160L,
        1.0D,
        "death:" + targetId);
    evidence.add(item);
    return item;
  }

  /**
   * Removes stale target state after an entity lifecycle invalidation without creating tactical
   * death evidence. This is control-state hygiene, not a legal perception source.
   */
  public void invalidateContact(UUID targetId) {
    if (targetId == null) return;
    contacts.remove(targetId);
    evidence.removeIf(item -> targetId.equals(item.subjectId()));
    if (targetId.equals(lastVisibleId)) lastVisibleId = null;
  }

  public boolean referencesContact(UUID targetId) {
    if (targetId == null) return false;
    return targetId.equals(lastVisibleId)
        || contacts.contains(targetId)
        || evidence.stream().anyMatch(item -> targetId.equals(item.subjectId()));
  }

  public Snapshot snapshot(long now) {
    Iterator<Evidence> iterator = evidence.iterator();
    while (iterator.hasNext()) if (iterator.next().expiresAt() <= now) iterator.remove();
    contacts.prune(now);
    return new Snapshot(
        contacts.snapshot(), List.copyOf(evidence), encounter.snapshot(now), exposure.snapshot(now));
  }

  public int stalestSector(long now, float avoidYaw) {
    return exposure.stalestSector(now, avoidYaw);
  }

  private void accept(Evidence item) {
    evidence.add(item);
    encounter.accept(item);
    contacts.addEvidence(item, item.observedAt());
  }

  private Evidence create(
      UUID hunterId,
      Evidence.Type type,
      UUID subject,
      EvidenceRegion region,
      long observedAt,
      long expiresAt,
      double reliability,
      String sourceKey) {
    String identity = hunterId + ":" + observedAt + ":" + (++evidenceSequence) + ":" + type;
    UUID id = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    return new Evidence(id, type, subject, region, observedAt, expiresAt, reliability, sourceKey);
  }

  public record Snapshot(
      List<ContactSet.Contact> contacts,
      List<Evidence> evidence,
      EncounterModel.Snapshot encounter,
      ExposureMemory.Snapshot exposure) {
    public Snapshot {
      contacts = List.copyOf(contacts);
      evidence = List.copyOf(evidence);
    }

    public boolean hasVisibleContact() {
      return contacts.stream().anyMatch(ContactSet.Contact::currentlyVisible);
    }

    public int independentRecentSources(long now, long window) {
      Set<String> sources = new HashSet<>();
      evidence.stream()
          .filter(item -> now - item.observedAt() <= window)
          .forEach(item -> sources.add(item.independenceKey()));
      return sources.size();
    }
  }
}
