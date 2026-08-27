package cn.huntmc.hunterai.decision;

import java.util.List;
import java.util.UUID;

public record PredictionResult(
    UUID candidateId,
    long seed,
    List<ScenarioResult> scenarios,
    double successRate,
    double survivalRate,
    double expectedDamage,
    double ammunitionCost,
    double objectiveDelay,
    double endPositionValue,
    double exposure,
    double thirdPartyRisk,
    double escapeRate,
    double confidence,
    double worstQuartileLoss,
    double score) {
  public PredictionResult {
    scenarios = List.copyOf(scenarios == null ? List.of() : scenarios);
  }

  public record ScenarioResult(
      int index,
      int horizonTicks,
      double enemyPresence,
      double detectedProbability,
      int enemyCount,
      boolean fightEnds,
      boolean thirdParty,
      boolean firstShot,
      double routeExposure,
      double escapeFeasibility,
      double survival,
      double damage,
      double success,
      double loss) {}
}
