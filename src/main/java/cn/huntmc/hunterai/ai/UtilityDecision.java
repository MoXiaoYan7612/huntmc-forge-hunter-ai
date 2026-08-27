package cn.huntmc.hunterai.ai;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

/** Scores feasible tactical intents from information, personality, pressure, and recent behavior. */
public final class UtilityDecision {
  private static final double INDIVIDUAL_SELECTION_VARIATION = 0.14D;

  public Decision choose(Context context) {
    return choose(context, null);
  }

  /**
   * Applies a small, per-Hunter variation only when several legal plans are similarly useful.
   * Urgent branches such as damage response and post-fire pursuit are selected by HunterCoordinator
   * before this scorer runs, so this variation never overrides immediate combat pressure.
   */
  public Decision choose(Context context, Random random) {
    EnumMap<HunterIntent, Double> scores = new EnumMap<>(HunterIntent.class);
    EnumMap<HunterIntent, Double> selectionScores = new EnumMap<>(HunterIntent.class);
    for (HunterIntent intent : HunterIntent.values()) {
      double opportunity = context.opportunityScores().getOrDefault(intent, 0.0D);
      double score = opportunity * personalityFit(intent, context.personality());
      score *= informationFit(intent, context.visibleEnemy(), context.evidenceConfidence());
      score *=
          threatFit(
              intent,
              context.self(),
              context.preAimRisk(),
              context.immediateThreatRisk(),
              context.combat());
      score *= informationGatheringFit(intent, context.visibleEnemy() ? 0.0D : context.unknownRisk());
      score -= context.history().intentPenalty(intent, context.now());
      if (!context.available().contains(intent)) score = -1.0D;
      scores.put(intent, score);
    }
    double bestScore =
        scores.values().stream().mapToDouble(Double::doubleValue).max().orElse(-1.0D);
    for (Map.Entry<HunterIntent, Double> entry : scores.entrySet()) {
      selectionScores.put(
          entry.getKey(), selectionScore(entry.getValue(), bestScore, context.visibleEnemy(), random));
    }
    HunterIntent selected =
        selectionScores.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(HunterIntent.LISTEN);
    return new Decision(
        selected,
        Map.copyOf(scores),
        Map.copyOf(selectionScores),
        explanation(selected, context, scores.get(selected)));
  }

  private static double selectionScore(
      double score, double bestScore, boolean visibleEnemy, Random random) {
    // Keep a clearly superior action stable. Variation exists only among the near-best legal
    // plans, where different players would reasonably make different choices.
    if (score < 0.0D
        || random == null
        || visibleEnemy
        || bestScore <= 0.0D
        || score < bestScore * 0.82D) return score;
    double variation = (random.nextDouble() * 2.0D - 1.0D) * INDIVIDUAL_SELECTION_VARIATION;
    return score * (1.0D + variation);
  }

  private static double personalityFit(HunterIntent intent, HunterPersonality personality) {
    return switch (intent) {
      case PUSH -> 0.45D + personality.aggression() * 0.70D + personality.riskTolerance() * 0.25D;
      case INTERCEPT, SEARCH -> 0.55D + personality.chasePersistence() * 0.55D;
      case FLANK -> 0.45D + personality.flankPreference() * 0.80D + personality.patience() * 0.15D;
      case AMBUSH, LISTEN -> 0.40D + personality.ambushPreference() * 0.45D + personality.patience() * 0.55D;
      case OVERWATCH, HOLD_ANGLE -> 0.50D + personality.vigilance() * 0.50D + personality.coverPreference() * 0.25D;
      case REPOSITION, DISENGAGE -> 0.45D + (1.0D - personality.riskTolerance()) * 0.35D + personality.coverPreference() * 0.55D;
    };
  }

  private static double informationFit(HunterIntent intent, boolean visibleEnemy, double confidence) {
    if (visibleEnemy) {
      return switch (intent) {
        case HOLD_ANGLE, PUSH, REPOSITION, FLANK, DISENGAGE -> 1.20D;
        case OVERWATCH -> 0.85D;
        case AMBUSH, LISTEN, INTERCEPT, SEARCH -> 0.40D;
      };
    }
    return switch (intent) {
      case AMBUSH, INTERCEPT, SEARCH, LISTEN, OVERWATCH -> 0.45D + confidence * 0.85D;
      case REPOSITION, DISENGAGE -> 0.70D;
      case HOLD_ANGLE, PUSH, FLANK -> 0.25D + confidence * 0.45D;
    };
  }

  private static double threatFit(
      HunterIntent intent,
      SelfModel.Snapshot self,
      double preAimRisk,
      double immediateThreatRisk,
      CombatEvaluation combat) {
    double pressure = Math.max(self.compromise(), Math.max(preAimRisk, immediateThreatRisk));
    if (self.healthRatio() < 0.35D || self.reloading()) {
      return switch (intent) {
        case REPOSITION, FLANK, LISTEN -> 1.45D;
        case DISENGAGE -> combat.escapeFavorable() ? 1.55D : 0.55D;
        case HOLD_ANGLE -> combat.escapeFavorable() ? 0.25D : 0.82D;
        case PUSH -> 0.25D;
        default -> 0.80D;
      };
    }
    return switch (intent) {
      case REPOSITION, DISENGAGE -> 0.70D + pressure * 0.90D;
      case FLANK -> 0.80D + pressure * 0.45D;
      case HOLD_ANGLE, PUSH -> 1.20D - pressure * 0.65D;
      default -> 1.0D;
    };
  }

  /** Unknown space calls for observation from cover, not a panic relocation. */
  private static double informationGatheringFit(HunterIntent intent, double unknownRisk) {
    double uncertainty = Math.max(0.0D, Math.min(1.0D, unknownRisk));
    return switch (intent) {
      case LISTEN -> 1.0D + uncertainty * 0.38D;
      case OVERWATCH -> 1.0D + uncertainty * 0.22D;
      case AMBUSH -> 1.0D + uncertainty * 0.12D;
      default -> 1.0D;
    };
  }

  private static String explanation(HunterIntent selected, Context context, double score) {
    return "utility="
        + String.format(java.util.Locale.ROOT, "%.2f", score)
        + " visible="
        + context.visibleEnemy()
        + " evidence="
        + String.format(java.util.Locale.ROOT, "%.2f", context.evidenceConfidence())
        + " compromise="
        + String.format(java.util.Locale.ROOT, "%.2f", context.self().compromise())
        + " immediate="
        + String.format(java.util.Locale.ROOT, "%.2f", context.immediateThreatRisk())
        + " unknown="
        + String.format(java.util.Locale.ROOT, "%.2f", context.unknownRisk());
  }

  public record Context(
      boolean visibleEnemy,
      double evidenceConfidence,
      SelfModel.Snapshot self,
      double preAimRisk,
      double immediateThreatRisk,
      double unknownRisk,
      CombatEvaluation combat,
      HunterPersonality personality,
      RecentBehavior history,
      Map<HunterIntent, Double> opportunityScores,
      java.util.Set<HunterIntent> available,
      long now) {}

  public record Decision(
      HunterIntent intent,
      Map<HunterIntent, Double> scores,
      Map<HunterIntent, Double> selectionScores,
      String reason) {
    public String scoreSummary() {
      return scores.entrySet().stream()
          .sorted(Map.Entry.<HunterIntent, Double>comparingByValue().reversed())
          .limit(4)
          .map(
              entry ->
                  entry.getKey().name()
                      + "="
                      + String.format(java.util.Locale.ROOT, "%.2f", entry.getValue()))
          .collect(java.util.stream.Collectors.joining(","));
    }

    public String selectionSummary() {
      return selectionScores.entrySet().stream()
          .filter(entry -> entry.getValue() >= 0.0D)
          .sorted(Map.Entry.<HunterIntent, Double>comparingByValue().reversed())
          .limit(3)
          .map(
              entry ->
                  entry.getKey().name()
                      + "="
                      + String.format(java.util.Locale.ROOT, "%.2f", entry.getValue()))
          .collect(java.util.stream.Collectors.joining(","));
    }
  }
}
