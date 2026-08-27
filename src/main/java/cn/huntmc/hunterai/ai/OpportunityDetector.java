package cn.huntmc.hunterai.ai;

import cn.huntmc.hunterai.config.HunterAiConfig;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/** Creates explainable, bounded opportunity scores from the current tactical snapshot. */
public final class OpportunityDetector {
  public Result detect(
      TacticalSpace space,
      boolean visibleEnemy,
      double evidenceConfidence,
      SelfModel.Snapshot self,
      double preferredRange,
      double maximumRange) {
    EnumMap<HunterIntent, TacticalOpportunity> opportunities = new EnumMap<>(HunterIntent.class);
    TacticalNode cover = best(space, node -> node.isConcealed() && hasCombatCover(node));
    if (cover == null) cover = best(space, TacticalNode::isConcealed);
    TacticalNode engagement =
        best(space, node -> node.canObserveThreat() && !node.isExposed() && hasCombatCover(node));
    if (engagement == null) engagement = best(space, TacticalNode::canObserveThreat);
    TacticalNode overwatch =
        engagement != null
            ? engagement
            : best(
                space,
                node ->
                    node.escapeDirections() > 0
                        && !node.isExposed()
                        && (node.isConcealed() || hasCombatCover(node)));
    TacticalNode hidden = best(space, node -> node.isConcealed() && !node.canObserveThreat());
    TacticalNode search =
        space.threat() == null
            ? cover
            : space.nodes().stream()
                .filter(node -> !node.isExposed())
                .min(
                    java.util.Comparator.comparingDouble(
                        node -> node.position().distanceToSqr(space.threat()) - node.score() * 18.0D))
                .orElse(cover);

    double threatDistance =
        space.threat() == null ? Double.POSITIVE_INFINITY : space.origin().distanceTo(space.threat());
    boolean beyondMaximumRange = visibleEnemy && threatDistance > maximumRange;
    boolean inPreferredRange = visibleEnemy && threatDistance <= preferredRange;
    put(
        opportunities,
        HunterIntent.HOLD_ANGLE,
        engagement,
        visibleEnemy ? beyondMaximumRange ? 0.15D : inPreferredRange ? 0.95D : 0.76D : 0.18D,
        beyondMaximumRange ? "target-outside-weapon-range" : "visible-engagement-cover");
    put(
        opportunities,
        HunterIntent.OVERWATCH,
        overwatch,
        visibleEnemy ? 0.55D : evidenceConfidence >= 0.20D ? 0.52D : 0.42D,
        "covered-observation");
    put(
        opportunities,
        HunterIntent.PUSH,
        search,
        visibleEnemy ? beyondMaximumRange ? 0.95D : inPreferredRange ? 0.22D : 0.54D : 0.16D,
        beyondMaximumRange ? "close-distance-for-weapon-range" : "advance-on-confirmed-contact");
    put(opportunities, HunterIntent.REPOSITION, cover, 0.34D + self.compromise() * 0.72D, "reduce-exposure");
    put(opportunities, HunterIntent.FLANK, hidden, visibleEnemy ? 0.60D : evidenceConfidence * 0.47D, "hidden-side-route");
    put(opportunities, HunterIntent.AMBUSH, hidden == null ? cover : hidden, evidenceConfidence * 0.78D + (visibleEnemy ? 0.14D : 0.0D), "concealed-wait");
    put(opportunities, HunterIntent.LISTEN, cover, 0.36D + (1.0D - evidenceConfidence) * 0.48D, "covered-information-gathering");
    put(opportunities, HunterIntent.INTERCEPT, search, evidenceConfidence * 0.72D, "probability-area-intercept");
    put(opportunities, HunterIntent.SEARCH, search, evidenceConfidence * 0.64D, "last-evidence-search");
    put(opportunities, HunterIntent.DISENGAGE, cover, self.healthRatio() < 0.40D ? 1.0D : self.compromise() * 0.67D, "escape-to-cover");
    return new Result(Map.copyOf(opportunities));
  }

  private static TacticalNode best(TacticalSpace space, java.util.function.Predicate<TacticalNode> predicate) {
    return space.nodes().stream()
        .filter(predicate)
        .max(java.util.Comparator.comparingDouble(TacticalNode::score))
        .orElse(null);
  }

  private static boolean hasCombatCover(TacticalNode node) {
    return node.cover() >= HunterAiConfig.MIN_COMBAT_COVER.get();
  }

  private static void put(
      Map<HunterIntent, TacticalOpportunity> opportunities,
      HunterIntent intent,
      TacticalNode node,
      double score,
      String reason) {
    opportunities.put(intent, new TacticalOpportunity(intent, node, node == null ? 0.0D : score, reason));
  }

  public record Result(Map<HunterIntent, TacticalOpportunity> opportunities) {
    public Map<HunterIntent, Double> scores() {
      EnumMap<HunterIntent, Double> scores = new EnumMap<>(HunterIntent.class);
      opportunities.forEach((intent, opportunity) -> scores.put(intent, opportunity.score()));
      return scores;
    }

    public String summary() {
      return opportunities.values().stream()
          .sorted(java.util.Comparator.comparingDouble(TacticalOpportunity::score).reversed())
          .limit(4)
          .map(
              opportunity ->
                  opportunity.intent()
                      + "="
                      + String.format(Locale.ROOT, "%.2f", opportunity.score())
                      + "@"
                      + (opportunity.node() == null ? "none" : compact(opportunity.node())))
          .collect(java.util.stream.Collectors.joining(","));
    }

    private static String compact(TacticalNode node) {
      return String.format(Locale.ROOT, "%.0f/%.0f/%.0f", node.position().x, node.position().y, node.position().z);
    }
  }
}
