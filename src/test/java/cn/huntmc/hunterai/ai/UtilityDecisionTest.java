package cn.huntmc.hunterai.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UtilityDecisionTest {
  @Test
  void reloadPressurePrefersCoveredReposition() {
    EnumMap<HunterIntent, Double> scores = new EnumMap<>(HunterIntent.class);
    scores.put(HunterIntent.HOLD_ANGLE, 0.90D);
    scores.put(HunterIntent.REPOSITION, 0.75D);
    UtilityDecision.Decision decision =
        new UtilityDecision()
            .choose(
                new UtilityDecision.Context(
                    true,
                    1.0D,
                    new SelfModel.Snapshot(0.80D, 0.65D, 0.80D, true, false, false),
                    0.70D,
                    0.50D,
                    0.0D,
                    CombatEvaluation.assess(
                        new SelfModel.Snapshot(0.80D, 0.65D, 0.80D, true, false, false),
                        0.80D,
                        0.10D,
                        18.0D,
                        24.0D,
                        48.0D,
                        0.70D,
                        true),
                    HunterPersonality.fromUuid(new UUID(8L, 9L)),
                    new RecentBehavior(),
                    Map.copyOf(scores),
                    EnumSet.of(HunterIntent.HOLD_ANGLE, HunterIntent.REPOSITION),
                    100L));

    assertEquals(HunterIntent.REPOSITION, decision.intent());
  }

  @Test
  void unknownSpaceFavorsInformationGatheringWithoutRaisingRepositionScore() {
    EnumMap<HunterIntent, Double> opportunities = new EnumMap<>(HunterIntent.class);
    opportunities.put(HunterIntent.LISTEN, 0.60D);
    opportunities.put(HunterIntent.OVERWATCH, 0.60D);
    opportunities.put(HunterIntent.REPOSITION, 0.60D);
    SelfModel.Snapshot self = new SelfModel.Snapshot(1.0D, 0.0D, 0.0D, false, false, false);
    HunterPersonality personality = HunterPersonality.fromUuid(new UUID(31L, 47L));
    EnumSet<HunterIntent> available =
        EnumSet.of(HunterIntent.LISTEN, HunterIntent.OVERWATCH, HunterIntent.REPOSITION);

    UtilityDecision.Decision settled =
        new UtilityDecision()
            .choose(
                new UtilityDecision.Context(
                    false,
                    0.0D,
                    self,
                    0.0D,
                    0.0D,
                    0.0D,
                    CombatEvaluation.unavailable(),
                    personality,
                    new RecentBehavior(),
                    Map.copyOf(opportunities),
                    available,
                    100L));
    UtilityDecision.Decision uncertain =
        new UtilityDecision()
            .choose(
                new UtilityDecision.Context(
                    false,
                    0.0D,
                    self,
                    0.0D,
                    0.0D,
                    1.0D,
                    CombatEvaluation.unavailable(),
                    personality,
                    new RecentBehavior(),
                    Map.copyOf(opportunities),
                    available,
                    100L));

    assertTrue(uncertain.scores().get(HunterIntent.LISTEN) > settled.scores().get(HunterIntent.LISTEN));
    assertTrue(
        uncertain.scores().get(HunterIntent.OVERWATCH)
            > settled.scores().get(HunterIntent.OVERWATCH));
    assertEquals(
        settled.scores().get(HunterIntent.REPOSITION),
        uncertain.scores().get(HunterIntent.REPOSITION));
  }

  @Test
  void independentHunterRandomnessChangesOnlyTheSelectionTieBreakScores() {
    EnumMap<HunterIntent, Double> opportunities = new EnumMap<>(HunterIntent.class);
    opportunities.put(HunterIntent.LISTEN, 0.60D);
    opportunities.put(HunterIntent.OVERWATCH, 0.60D);
    opportunities.put(HunterIntent.AMBUSH, 0.60D);
    SelfModel.Snapshot self = new SelfModel.Snapshot(1.0D, 0.0D, 0.0D, false, false, false);
    HunterPersonality personality = HunterPersonality.fromUuid(new UUID(31L, 47L));
    UtilityDecision.Context context =
        new UtilityDecision.Context(
            false,
            0.45D,
            self,
            0.0D,
            0.0D,
            0.45D,
            CombatEvaluation.unavailable(),
            personality,
            new RecentBehavior(),
            Map.copyOf(opportunities),
            EnumSet.of(HunterIntent.LISTEN, HunterIntent.OVERWATCH, HunterIntent.AMBUSH),
            100L);

    UtilityDecision.Decision first = new UtilityDecision().choose(context, new Random(41L));
    UtilityDecision.Decision repeat = new UtilityDecision().choose(context, new Random(41L));
    UtilityDecision.Decision otherHunter = new UtilityDecision().choose(context, new Random(97L));

    assertEquals(first.selectionScores(), repeat.selectionScores());
    assertNotEquals(first.selectionScores(), otherHunter.selectionScores());
    assertEquals(first.scores(), otherHunter.scores());
  }
}
