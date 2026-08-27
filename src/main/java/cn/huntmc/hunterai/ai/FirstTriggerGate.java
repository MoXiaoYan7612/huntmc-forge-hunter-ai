package cn.huntmc.hunterai.ai;

import cn.huntmc.hunterai.combat.CloseRangeCombatTuning;
import java.util.UUID;

/** Adds a human trigger delay without delaying legal perception, attention, or physical turning. */
final class FirstTriggerGate {
  private FirstTriggerGate() {}

  static long eligibleAt(
      long observedAt,
      double distance,
      boolean directDamage,
      UUID hunterId,
      UUID targetId,
      long contactSequence) {
    return eligibleAt(
        observedAt,
        distance,
        directDamage,
        false,
        hunterId,
        targetId,
        contactSequence);
  }

  static long eligibleAt(
      long observedAt,
      double distance,
      boolean directDamage,
      boolean preAimedAmbush,
      UUID hunterId,
      UUID targetId,
      long contactSequence) {
    return observedAt
        + delayTicks(
            distance,
            directDamage,
            preAimedAmbush,
            hunterId,
            targetId,
            contactSequence);
  }

  static long delayTicks(
      double distance,
      boolean directDamage,
      UUID hunterId,
      UUID targetId,
      long contactSequence) {
    return delayTicks(distance, directDamage, false, hunterId, targetId, contactSequence);
  }

  static long delayTicks(
      double distance,
      boolean directDamage,
      boolean preAimedAmbush,
      UUID hunterId,
      UUID targetId,
      long contactSequence) {
    int variance = stableVariance(hunterId, targetId, contactSequence);
    if (directDamage) return 2L + variance;
    double influence = CloseRangeCombatTuning.influence(distance);
    if (preAimedAmbush && influence > 0.0D)
      return Math.round((2L + variance) * influence);
    if (!Double.isFinite(distance) || influence <= 0.0D) return 0L;
    if (distance <= 4.0D) return 7L + variance;
    if (distance <= 8.0D) return 6L + variance;
    if (distance <= 10.0D) return 5L + variance;
    return Math.round((5L + variance) * influence);
  }

  static long reacquireEligibleAt(
      long observedAt, double distance, UUID hunterId, UUID targetId, long episodeSequence) {
    double influence = CloseRangeCombatTuning.influence(distance);
    if (influence <= 0.0D) return observedAt;
    long delay = Math.round(
        (2L + stableVariance(hunterId, targetId, episodeSequence)) * influence);
    return observedAt + Math.max(1L, delay);
  }

  private static int stableVariance(UUID hunterId, UUID targetId, long contactSequence) {
    long hunter = hunterId == null
        ? 0L
        : hunterId.getMostSignificantBits() ^ Long.rotateLeft(hunterId.getLeastSignificantBits(), 17);
    long target = targetId == null
        ? 0L
        : targetId.getMostSignificantBits() ^ Long.rotateLeft(targetId.getLeastSignificantBits(), 31);
    long mixed = hunter ^ target ^ contactSequence * 0x9E3779B97F4A7C15L;
    mixed ^= mixed >>> 33;
    mixed *= 0xff51afd7ed558ccdL;
    mixed ^= mixed >>> 33;
    return Math.floorMod((int) mixed, 3);
  }
}
