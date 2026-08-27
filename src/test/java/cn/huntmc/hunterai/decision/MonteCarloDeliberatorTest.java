package cn.huntmc.hunterai.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.huntmc.hunterai.ai.HunterIntent;
import cn.huntmc.hunterai.ai.HunterPersonality;
import cn.huntmc.hunterai.match.MatchDirective;
import cn.huntmc.hunterai.model.EncounterModel;
import cn.huntmc.hunterai.model.ExposureMemory;
import cn.huntmc.hunterai.model.HunterInputSnapshot;
import cn.huntmc.hunterai.model.HunterWorldModel;
import cn.huntmc.hunterai.model.SelfSnapshot;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class MonteCarloDeliberatorTest {
  @Test
  void sameDecisionUsesTheSameSharedScenariosAndScores() {
    UUID hunter = new UUID(11L, 13L);
    UUID match = new UUID(17L, 19L);
    UUID decision = new UUID(23L, 29L);
    HunterInputSnapshot input = new HunterInputSnapshot(
        hunter,
        1,
        100L,
        new SelfSnapshot(Vec3.ZERO, Vec3.ZERO, 0.0F, 0.0F, 120.0D, 150.0D, 6, 8,
            false, true, false, false, false, false),
        new MatchDirective(hunter, match, "a", true, true, "ACTIVE", "BANISHING",
            new Vec3(30.0D, 64.0D, 0.0D), null, false, 100L, 2400L, 600L, 0.0D));
    HunterWorldModel.Snapshot world = new HunterWorldModel.Snapshot(
        List.of(), List.of(), new EncounterModel.Snapshot(true, 0.7D, 3, 4, 0),
        new ExposureMemory.Snapshot(new double[8], 0.3D));
    List<DecisionCandidate> candidates = List.of(
        candidate(HunterIntent.AMBUSH, 0.72D, 0.2D),
        candidate(HunterIntent.PUSH, 0.68D, 0.6D));
    MonteCarloDeliberator engine = new MonteCarloDeliberator();

    RolloutBatch first = engine.evaluate(input, world, HunterPersonality.fromUuid(hunter), decision, candidates, 100_000L);
    RolloutBatch second = engine.evaluate(input, world, HunterPersonality.fromUuid(hunter), decision, candidates, 100_000L);

    assertEquals(first.predictions(), second.predictions());
    assertEquals(MonteCarloDeliberator.SCENARIOS * candidates.size(), first.samplesCompleted());
    first.predictions().stream()
        .flatMap(prediction -> prediction.scenarios().stream())
        .forEach(
            scenario ->
                org.junit.jupiter.api.Assertions.assertTrue(
                    scenario.horizonTicks() >= 40 && scenario.horizonTicks() <= 160));
  }

  private static DecisionCandidate candidate(HunterIntent intent, double score, double exposure) {
    return new DecisionCandidate(
        UUID.nameUUIDFromBytes(intent.name().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        StrategicGoal.STAGE_BOUNTY,
        intent,
        null,
        null,
        new Vec3(4.0D, 64.0D, 4.0D),
        score,
        exposure,
        0.2D,
        0.8D,
        "test",
        "");
  }
}
