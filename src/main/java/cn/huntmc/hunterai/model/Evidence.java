package cn.huntmc.hunterai.model;

import java.util.UUID;

/** One legal observation; exact entity references never cross this boundary. */
public record Evidence(
    UUID id,
    Type type,
    UUID subjectId,
    EvidenceRegion region,
    long observedAt,
    long expiresAt,
    double sourceReliability,
    String sourceKey) {
  public Evidence {
    if (id == null || type == null || region == null) throw new IllegalArgumentException("evidence fields are required");
    sourceKey = sourceKey == null ? "" : sourceKey;
  }

  public double confidence(long now) {
    if (now >= expiresAt) return 0.0D;
    long duration = Math.max(1L, expiresAt - observedAt);
    double recency = 1.0D - Math.max(0L, now - observedAt) / (double) duration;
    return Math.max(0.0D, Math.min(1.0D, sourceReliability * recency));
  }

  /** Groups different sensing modalities from the same actor as one independent source. */
  public String independenceKey() {
    if (subjectId != null) return "actor:" + subjectId;
    if (sourceKey.startsWith("sound:") && sourceKey.length() == "sound:".length() + 36)
      return "actor:" + sourceKey.substring("sound:".length());
    return sourceKey;
  }

  public enum Type {
    VISUAL,
    GUNSHOT,
    MOVEMENT_SOUND,
    DAMAGE,
    LOST_CONTACT,
    DEATH,
    OBJECTIVE_CHANGE
  }
}
