package cn.huntmc.hunterai.decision;

import cn.huntmc.hunterai.model.Evidence;
import cn.huntmc.hunterai.model.HunterWorldModel;
import cn.huntmc.hunterai.model.IntelAssessment;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Applies the event-driven intelligence thresholds and rollout cooldown. */
public final class DeliberationGate {
  public static final double ANALYTIC_THRESHOLD = 0.35D;
  public static final double ROLLOUT_THRESHOLD = 0.65D;
  public static final long ROLLOUT_COOLDOWN_TICKS = 40L;
  private long lastRolloutAt = Long.MIN_VALUE / 2L;

  public IntelAssessment assess(
      HunterWorldModel.Snapshot world,
      long now,
      int meaningfulCandidates,
      boolean planInvalid,
      boolean objectiveChanged,
      boolean newConfirmedEnemy,
      boolean emergency,
      double planRelevance) {
    List<Evidence> recent = world.evidence().stream()
        .filter(item -> now - item.observedAt() <= 80L)
        .toList();
    Evidence strongest = recent.stream()
        .max(Comparator.comparingDouble(item -> item.confidence(now)))
        .orElse(null);
    double source = strongest == null ? 0.0D : strongest.sourceReliability();
    double recency = strongest == null ? 0.0D : strongest.confidence(now) / Math.max(0.01D, source);
    double localization = strongest == null
        ? 0.0D
        : Math.max(0.20D, 1.0D - strongest.region().radius() / 30.0D);
    Set<String> sources = new HashSet<>();
    recent.forEach(item -> sources.add(item.independenceKey()));
    double independent = Math.min(1.0D, 0.45D + Math.max(0, sources.size() - 1) * 0.28D);
    double consistency = spatialConsistency(recent);
    double relevance = clamp(planRelevance);
    if (planInvalid || objectiveChanged) relevance = 1.0D;
    double quality = source * recency * consistency * independent * localization * relevance;

    boolean onlyOneSound = recent.size() == 1
        && (recent.get(0).type() == Evidence.Type.GUNSHOT
            || recent.get(0).type() == Evidence.Type.MOVEMENT_SOUND);
    boolean freshDirectAttack = recent.stream()
        .anyMatch(item -> item.type() == Evidence.Type.DAMAGE
            && now - item.observedAt() <= 20L
            && item.confidence(now) >= 0.70D);
    boolean bypassCooldown = planInvalid || objectiveChanged || newConfirmedEnemy;
    DeliberationMode mode;
    String reason;
    if (emergency) {
      mode = DeliberationMode.NONE;
      reason = "emergency-first-reach-safety";
    } else if (onlyOneSound) {
      mode = DeliberationMode.NONE;
      reason = "single-remote-sound-memory-only";
    } else if (freshDirectAttack && quality < ROLLOUT_THRESHOLD) {
      mode = DeliberationMode.ANALYTIC;
      reason = "direct-attack-analytic";
    } else if (quality < ANALYTIC_THRESHOLD) {
      mode = DeliberationMode.NONE;
      reason = "intel-below-analytic-threshold";
    } else if (quality < ROLLOUT_THRESHOLD || meaningfulCandidates < 2) {
      mode = DeliberationMode.ANALYTIC;
      reason = meaningfulCandidates < 2 ? "no-meaningful-choice" : "analytic-quality";
    } else if (!bypassCooldown && now - lastRolloutAt < ROLLOUT_COOLDOWN_TICKS) {
      mode = DeliberationMode.ANALYTIC;
      reason = "rollout-cooldown";
    } else {
      mode = DeliberationMode.ROLLOUT;
      lastRolloutAt = now;
      reason = bypassCooldown ? "high-intel-bypass" : "high-intel-choice";
    }
    return new IntelAssessment(
        source, recency, consistency, independent, localization, relevance, quality, mode, reason);
  }

  private static double spatialConsistency(List<Evidence> evidence) {
    if (evidence.isEmpty()) return 0.0D;
    if (evidence.size() == 1) return 0.82D;
    Evidence newest = evidence.get(evidence.size() - 1);
    long consistent = evidence.stream()
        .filter(item -> item.region().center().distanceTo(newest.region().center())
            <= item.region().radius() + newest.region().radius() + 12.0D)
        .count();
    return Math.max(0.35D, consistent / (double) evidence.size());
  }

  private static double clamp(double value) {
    return Math.max(0.0D, Math.min(1.0D, value));
  }
}
