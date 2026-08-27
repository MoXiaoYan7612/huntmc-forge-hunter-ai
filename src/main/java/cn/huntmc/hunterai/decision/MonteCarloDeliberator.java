package cn.huntmc.hunterai.decision;

import cn.huntmc.hunterai.ai.HunterPersonality;
import cn.huntmc.hunterai.model.HunterInputSnapshot;
import cn.huntmc.hunterai.model.HunterWorldModel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;
import java.util.UUID;

/** Bounded abstract rollout; it never reads Minecraft world or entity state. */
public final class MonteCarloDeliberator {
  public static final int SCENARIOS = 12;
  public static final int MAX_ANALYTIC_CANDIDATES = 6;
  public static final int MAX_ROLLOUT_CANDIDATES = 4;
  public static final long DEFAULT_HUNTER_BUDGET_MICROS = 800L;

  public RolloutBatch evaluate(
      HunterInputSnapshot input,
      HunterWorldModel.Snapshot world,
      HunterPersonality personality,
      UUID decisionId,
      List<DecisionCandidate> candidates,
      long budgetMicros) {
    long started = System.nanoTime();
    long seed = seed(input.hunterId(), input.match().matchId(), decisionId);
    List<DecisionCandidate> selected = candidates.stream()
        .filter(DecisionCandidate::feasible)
        .sorted(Comparator.comparingDouble(DecisionCandidate::analyticScore).reversed())
        .limit(MAX_ROLLOUT_CANDIDATES)
        .toList();
    List<ScenarioSample> samples = sharedSamples(seed, world);
    List<PredictionResult> predictions = new ArrayList<>();
    int completed = 0;
    boolean exhausted = false;
    for (DecisionCandidate candidate : selected) {
      if ((System.nanoTime() - started) / 1_000L >= budgetMicros) {
        exhausted = true;
        break;
      }
      List<PredictionResult.ScenarioResult> results = new ArrayList<>();
      for (int index = 0; index < samples.size(); index++) {
        if ((System.nanoTime() - started) / 1_000L >= budgetMicros) {
          exhausted = true;
          break;
        }
        results.add(simulate(index, samples.get(index), candidate, input, personality));
        completed++;
      }
      if (!results.isEmpty()) predictions.add(aggregate(candidate, seed, results, exhausted));
      if (exhausted) break;
    }
    long elapsed = (System.nanoTime() - started) / 1_000L;
    double confidence = selected.isEmpty()
        ? 0.0D
        : Math.min(1.0D, completed / (double) (selected.size() * SCENARIOS));
    return new RolloutBatch(predictions, exhausted, elapsed, completed, confidence);
  }

  private static List<ScenarioSample> sharedSamples(long seed, HunterWorldModel.Snapshot world) {
    SplittableRandom random = new SplittableRandom(seed);
    List<ScenarioSample> result = new ArrayList<>(SCENARIOS);
    double contactConfidence = world.contacts().stream().mapToDouble(contact -> contact.confidence()).max().orElse(0.0D);
    double thirdPartyBase = world.encounter().multiPartyFight() ? 0.62D : 0.16D;
    for (int index = 0; index < SCENARIOS; index++) {
      double stratum = (index + random.nextDouble()) / SCENARIOS;
      int enemyCount = stratum > 0.78D ? 3 : stratum > 0.38D ? 2 : 1;
      result.add(
          new ScenarioSample(
              40 + (int) Math.floor(stratum * 121.0D),
              clamp(0.25D + contactConfidence * 0.65D + random.nextDouble(-0.12D, 0.12D)),
              clamp(0.15D + stratum * 0.72D),
              enemyCount,
              random.nextDouble() < 0.36D + world.encounter().confidence() * 0.40D,
              random.nextDouble() < thirdPartyBase,
              random.nextDouble() < 0.50D,
              clamp(random.nextDouble(0.15D, 0.90D)),
              clamp(random.nextDouble(0.20D, 0.95D))));
    }
    return List.copyOf(result);
  }

  private static PredictionResult.ScenarioResult simulate(
      int index,
      ScenarioSample sample,
      DecisionCandidate candidate,
      HunterInputSnapshot input,
      HunterPersonality personality) {
    double aggressionExposure = candidate.intent() == cn.huntmc.hunterai.ai.HunterIntent.PUSH ? 0.18D : 0.0D;
    double ambushProtection = candidate.intent() == cn.huntmc.hunterai.ai.HunterIntent.AMBUSH ? 0.20D : 0.0D;
    double exposure = clamp(candidate.exposure() * 0.65D + sample.routeExposure() * 0.35D + aggressionExposure);
    double horizonExposure = (sample.horizonTicks() - 40.0D) / 120.0D * exposure * 0.10D;
    double detection =
        clamp(sample.detectedProbability() + exposure * 0.35D + horizonExposure - ambushProtection);
    double firstShot = sample.firstShot() ? 1.0D : 0.0D;
    double health = input.self().healthRatio();
    double ammo = input.self().ammoRatio();
    double combatPower = clamp(
        health * 0.34D + ammo * 0.18D + personality.aimStability() * 0.16D
            + personality.aggression() * 0.10D + firstShot * 0.12D + candidate.escapeQuality() * 0.10D);
    double opposition = clamp(sample.enemyPresence() * (0.48D + sample.enemyCount() * 0.16D)
        + (sample.thirdParty() ? 0.18D : 0.0D));
    double survival = clamp(0.62D + combatPower * 0.45D - opposition * 0.42D - detection * 0.18D);
    double damage = clamp((1.0D - survival) * 0.75D + exposure * 0.22D) * input.self().maxHealth();
    double objective = clamp(1.0D - candidate.objectiveDelay());
    double success = clamp(
        survival * 0.42D + objective * 0.28D + candidate.analyticScore() * 0.22D
            + candidate.escapeQuality() * 0.08D - (sample.thirdParty() ? 0.08D : 0.0D));
    double loss = clamp((1.0D - survival) * 0.60D + damage / Math.max(1.0D, input.self().maxHealth()) * 0.25D
        + candidate.objectiveDelay() * 0.15D);
    return new PredictionResult.ScenarioResult(
        index,
        sample.horizonTicks(),
        sample.enemyPresence(),
        detection,
        sample.enemyCount(),
        sample.fightEnds(),
        sample.thirdParty(),
        sample.firstShot(),
        exposure,
        clamp(sample.escapeFeasibility() * 0.6D + candidate.escapeQuality() * 0.4D),
        survival,
        damage,
        success,
        loss);
  }

  private static PredictionResult aggregate(
      DecisionCandidate candidate,
      long seed,
      List<PredictionResult.ScenarioResult> scenarios,
      boolean partial) {
    double success = scenarios.stream().mapToDouble(PredictionResult.ScenarioResult::success).average().orElse(0.0D);
    double survival = scenarios.stream().mapToDouble(PredictionResult.ScenarioResult::survival).average().orElse(0.0D);
    double damage = scenarios.stream().mapToDouble(PredictionResult.ScenarioResult::damage).average().orElse(0.0D);
    double exposure = scenarios.stream().mapToDouble(PredictionResult.ScenarioResult::routeExposure).average().orElse(1.0D);
    double thirdParty = scenarios.stream().filter(PredictionResult.ScenarioResult::thirdParty).count() / (double) scenarios.size();
    double escape = scenarios.stream().mapToDouble(PredictionResult.ScenarioResult::escapeFeasibility).average().orElse(0.0D);
    List<Double> losses = scenarios.stream().map(PredictionResult.ScenarioResult::loss).sorted(Comparator.reverseOrder()).toList();
    int worstCount = Math.max(1, (int) Math.ceil(losses.size() * 0.25D));
    double worst = losses.stream().limit(worstCount).mapToDouble(Double::doubleValue).average().orElse(1.0D);
    double confidence = partial ? scenarios.size() / (double) SCENARIOS : 1.0D;
    double ammoCost = candidate.intent() == cn.huntmc.hunterai.ai.HunterIntent.PUSH ? 0.35D : 0.15D;
    double endPosition = clamp(candidate.escapeQuality() * 0.55D + (1.0D - exposure) * 0.45D);
    double score = clamp(
        success * 0.25D + survival * 0.28D + endPosition * 0.12D + escape * 0.10D
            - damage * 0.002D - ammoCost * 0.06D - candidate.objectiveDelay() * 0.10D
            - exposure * 0.05D - thirdParty * 0.06D - worst * 0.14D);
    return new PredictionResult(
        candidate.id(), seed, scenarios, success, survival, damage, ammoCost,
        candidate.objectiveDelay(), endPosition, exposure, thirdParty, escape, confidence, worst, score);
  }

  static long seed(UUID hunterId, UUID matchId, UUID decisionId) {
    long value = hunterId.getMostSignificantBits() ^ Long.rotateLeft(hunterId.getLeastSignificantBits(), 17);
    if (matchId != null) value ^= matchId.getMostSignificantBits() ^ Long.rotateLeft(matchId.getLeastSignificantBits(), 29);
    value ^= decisionId.getMostSignificantBits() ^ Long.rotateLeft(decisionId.getLeastSignificantBits(), 41);
    value ^= value >>> 33;
    value *= 0xff51afd7ed558ccdL;
    value ^= value >>> 33;
    return value;
  }

  private static double clamp(double value) {
    return Math.max(0.0D, Math.min(1.0D, value));
  }

  private record ScenarioSample(
      int horizonTicks,
      double enemyPresence,
      double detectedProbability,
      int enemyCount,
      boolean fightEnds,
      boolean thirdParty,
      boolean firstShot,
      double routeExposure,
      double escapeFeasibility) {}
}
