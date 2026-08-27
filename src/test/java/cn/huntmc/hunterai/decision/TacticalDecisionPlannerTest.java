package cn.huntmc.hunterai.decision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.huntmc.hunterai.ai.HunterIntent;
import cn.huntmc.hunterai.ai.HunterPersonality;
import cn.huntmc.hunterai.director.GodDirector;
import cn.huntmc.hunterai.model.ContactSet;
import cn.huntmc.hunterai.model.EncounterModel;
import cn.huntmc.hunterai.model.Evidence;
import cn.huntmc.hunterai.model.EvidenceRegion;
import cn.huntmc.hunterai.model.ExposureMemory;
import cn.huntmc.hunterai.model.HunterWorldModel;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class TacticalDecisionPlannerTest {
  @Test
  void directorRoleOnlyAddsASoftMatchingTacticalPreference() {
    assertTrue(
        TacticalDecisionPlanner.roleBias(GodDirector.Role.AMBUSHER, HunterIntent.AMBUSH)
            > TacticalDecisionPlanner.roleBias(GodDirector.Role.AMBUSHER, HunterIntent.PUSH));
    assertTrue(
        TacticalDecisionPlanner.roleBias(GodDirector.Role.OBSERVER, HunterIntent.OVERWATCH)
            > TacticalDecisionPlanner.roleBias(GodDirector.Role.OBSERVER, HunterIntent.FLANK));
    assertEquals(0.0D, TacticalDecisionPlanner.roleBias(
        GodDirector.Role.NEUTRAL, HunterIntent.AMBUSH));
  }
  @Test
  void freshDirectAttackOverridesOlderHighConfidenceContactDirection() {
    UUID oldContact = new UUID(3L, 4L);
    UUID attacker = new UUID(5L, 6L);
    Vec3 oldPosition = new Vec3(40.0D, 64.0D, 0.0D);
    Vec3 attackPosition = new Vec3(-18.0D, 65.0D, 7.0D);
    ContactSet.Contact old = new ContactSet.Contact(
        oldContact,
        new EvidenceRegion(oldPosition, 0.45D, Vec3.ZERO, 0.04D),
        100L,
        100L,
        1.0D,
        false);
    Evidence damage = new Evidence(
        UUID.nameUUIDFromBytes("fresh-damage".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        Evidence.Type.DAMAGE,
        attacker,
        new EvidenceRegion(attackPosition, 3.0D, Vec3.ZERO, 0.10D),
        101L,
        221L,
        0.95D,
        "damage:" + attacker);
    HunterWorldModel.Snapshot world = new HunterWorldModel.Snapshot(
        List.of(old),
        List.of(damage),
        new EncounterModel.Snapshot(false, 0.0D, 1, 0, 1),
        new ExposureMemory.Snapshot(new double[8], 0.0D));

    assertEquals(attackPosition, TacticalDecisionPlanner.mostRelevantThreat(world, 101L));
  }

  @Test
  void longTransitProducesFiveLanesWhoseOrderingDiffersByHunter() {
    UUID firstHunter = new UUID(0L, 0L);
    UUID secondHunter = new UUID(0L, 1L);
    Vec3 origin = new Vec3(0.0D, 64.0D, 0.0D);
    Vec3 objective = new Vec3(100.0D, 64.0D, 0.0D);

    var first =
        TacticalDecisionPlanner.diversifiedTargets(
            origin, firstHunter, objective, 100.0D, HunterPersonality.fromUuid(firstHunter));
    var second =
        TacticalDecisionPlanner.diversifiedTargets(
            origin, secondHunter, objective, 100.0D, HunterPersonality.fromUuid(secondHunter));

    assertEquals(5, first.size());
    assertEquals(5, first.stream().distinct().count());
    assertNotEquals(first.get(2), second.get(2));
  }

  @Test
  void directAttackThreatExpiresBeforeItCanBecomeAStaleRouteConstraint() {
    UUID hunter = new UUID(3L, 4L);
    UUID attacker = new UUID(5L, 6L);
    HunterWorldModel model = new HunterWorldModel();
    model.damaged(
        hunter, attacker, new Vec3(8.0D, 64.0D, 2.0D), 100L, "damage:" + attacker);

    assertTrue(TacticalDecisionPlanner.hasFreshDirectAttack(model.snapshot(100L), 100L));
    assertFalse(TacticalDecisionPlanner.hasFreshDirectAttack(model.snapshot(121L), 121L));
  }

  @Test
  void bossInteractionInsideAuthoritativeRangeDoesNotRequireAnotherRoute() {
    assertTrue(TacticalDecisionPlanner.immediateBossInteraction(
        StrategicGoal.START_BANISH, 4.21D, 5.0D));
    assertFalse(TacticalDecisionPlanner.immediateBossInteraction(
        StrategicGoal.TRANSIT_TO_BOSS, 4.21D, 5.0D));
    assertFalse(TacticalDecisionPlanner.immediateBossInteraction(
        StrategicGoal.START_BANISH, 5.01D, 5.0D));
  }

  @Test
  void interceptionPointsAreStableDiversifiedAndOutsideTheObjectiveFight() {
    Vec3 objective = new Vec3(100.0D, 70.0D, -20.0D);
    UUID first = new UUID(0L, 1L);
    UUID second = new UUID(0L, 2L);

    Vec3 firstPoint = TacticalDecisionPlanner.interceptionPoint(objective, 64.0D, first);
    Vec3 repeated = TacticalDecisionPlanner.interceptionPoint(objective, 64.0D, first);
    Vec3 secondPoint = TacticalDecisionPlanner.interceptionPoint(objective, 64.0D, second);
    double firstHorizontalDistance = Math.hypot(
        firstPoint.x - objective.x, firstPoint.z - objective.z);

    assertEquals(firstPoint, repeated);
    assertNotEquals(firstPoint, secondPoint);
    assertTrue(firstHorizontalDistance >= 48.0D);
    assertTrue(firstHorizontalDistance < 72.0D);
    assertEquals(64.0D, firstPoint.y, 0.0001D);
  }

  @Test
  void onlySuitablePersonalitiesTakeABoundedStrategicOverwatchStop() {
    UUID hunter = new UUID(0L, 0L);
    HunterPersonality observer = new HunterPersonality(
        0.4D, 0.8D, 0.4D, 0.5D, 0.5D, 0.85D, 0.7D, 0.5D, 0.8D, 0.6D);
    HunterPersonality impatient = new HunterPersonality(
        0.7D, 0.2D, 0.7D, 0.3D, 0.6D, 0.85D, 0.5D, 0.7D, 0.4D, 0.6D);

    assertTrue(TacticalDecisionPlanner.shouldConsiderStrategicOverwatch(
        hunter, observer, 36.0D, 1_000L, Long.MIN_VALUE));
    assertFalse(TacticalDecisionPlanner.shouldConsiderStrategicOverwatch(
        hunter, observer, 36.0D, 1_200L, 900L));
    assertFalse(TacticalDecisionPlanner.shouldConsiderStrategicOverwatch(
        hunter, impatient, 36.0D, 1_000L, Long.MIN_VALUE));
  }
}
