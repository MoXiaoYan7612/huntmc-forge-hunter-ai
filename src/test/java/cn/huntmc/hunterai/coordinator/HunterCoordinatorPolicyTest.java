package cn.huntmc.hunterai.coordinator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.huntmc.hunterai.ai.HunterIntent;
import cn.huntmc.hunterai.ai.TacticalNode;
import cn.huntmc.hunterai.decision.StrategicGoal;
import cn.huntmc.hunterai.director.GodDirector;
import cn.huntmc.hunterai.match.MatchDirective;
import cn.huntmc.hunterai.model.Evidence;
import cn.huntmc.hunterai.model.EvidenceRegion;
import cn.huntmc.hunterai.model.HunterInputSnapshot;
import cn.huntmc.hunterai.model.SelfSnapshot;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class HunterCoordinatorPolicyTest {
  @Test
  void directorSplitsObjectiveTransitFromPerimeterInterception() {
    HunterInputSnapshot unbanished = input(
        "team2", "", "UNBANISHED", 60.0D, false, 5.0D);

    assertEquals(
        StrategicGoal.TRANSIT_TO_BOSS,
        HunterCoordinator.chooseStrategicGoal(
            unbanished, StrategicGoal.SURVIVE, director(true, true)));
    assertEquals(
        StrategicGoal.INTERCEPT_APPROACH,
        HunterCoordinator.chooseStrategicGoal(
            unbanished, StrategicGoal.SURVIVE, director(false, false)));
  }

  @Test
  void missingPublicObjectiveCannotCreateAStationaryInterceptionPlan() {
    HunterInputSnapshot input = input(
        "team2", "", "UNBANISHED", 60.0D, false, 5.0D);
    MatchDirective original = input.match();
    MatchDirective withoutObjective = new MatchDirective(
        original.botId(),
        original.matchId(),
        original.teamId(),
        original.assigned(),
        original.active(),
        original.phase(),
        original.bountyState(),
        null,
        original.extraction(),
        original.extractions(),
        original.carryingBounty(),
        original.refreshedAt(),
        original.matchRemainingTicks(),
        original.bountyRemainingTicks(),
        original.bountySignalUncertaintyRadius(),
        original.banishingTeamId(),
        original.reviveTargets(),
        original.bossInteractionDistance());
    HunterInputSnapshot withoutObjectiveInput = new HunterInputSnapshot(
        input.hunterId(), input.monitorId(), input.gameTime(), input.self(), withoutObjective);

    assertEquals(
        StrategicGoal.SURVIVE,
        HunterCoordinator.chooseStrategicGoal(
            withoutObjectiveInput, StrategicGoal.INTERCEPT_APPROACH, director(false, false)));
  }

  @Test
  void inactiveMatchCannotSelectAnObjectiveInteractionGoal() {
    HunterInputSnapshot active = input(
        "team1", "", "UNBANISHED", 2.0D, false, 5.0D);
    MatchDirective original = active.match();
    MatchDirective preparing = new MatchDirective(
        original.botId(), original.matchId(), original.teamId(), true, false,
        "PREPARING", original.bountyState(), original.publicObjective(), original.extraction(),
        original.extractions(), original.carryingBounty(), original.refreshedAt(),
        original.matchRemainingTicks(), original.bountyRemainingTicks(),
        original.bountySignalUncertaintyRadius(), original.banishingTeamId(),
        original.reviveTargets(), original.bossInteractionDistance());
    HunterInputSnapshot input = new HunterInputSnapshot(
        active.hunterId(), active.monitorId(), active.gameTime(), active.self(), preparing);

    assertEquals(
        StrategicGoal.SURVIVE,
        HunterCoordinator.chooseStrategicGoal(input, StrategicGoal.START_BANISH));
  }

  @Test
  void supportRolesDoNotJoinEveryBanishClaimOrSignalChase() {
    HunterInputSnapshot enemyBanish = input("team2", "team1", 40.0D, false);
    HunterInputSnapshot enemyBounty = input(
        "team2", "team1", "AVAILABLE", 40.0D, false, 5.0D);
    HunterInputSnapshot carriedSignal = input(
        "team2", "team1", "CARRIED", 40.0D, false, 5.0D);
    GodDirector.Directive support = director(false, false);

    assertEquals(
        StrategicGoal.INTERCEPT_APPROACH,
        HunterCoordinator.chooseStrategicGoal(
            enemyBanish, StrategicGoal.TRANSIT_TO_BOSS, support));
    assertEquals(
        StrategicGoal.INTERCEPT_APPROACH,
        HunterCoordinator.chooseStrategicGoal(
            enemyBounty, StrategicGoal.TRANSIT_TO_BOSS, support));
    assertEquals(
        StrategicGoal.INTERCEPT_APPROACH,
        HunterCoordinator.chooseStrategicGoal(
            carriedSignal, StrategicGoal.TRANSIT_TO_BOSS, support));
  }

  @Test
  void banishingTeamCanClaimEvenWhenItsInitialDirectorRoleWasSupport() {
    HunterInputSnapshot ownBounty = input(
        "team1", "team1", "AVAILABLE", 20.0D, false, 5.0D);

    assertEquals(
        StrategicGoal.CLAIM_BOUNTY,
        HunterCoordinator.chooseStrategicGoal(
            ownBounty, StrategicGoal.INTERCEPT_APPROACH, director(false, false)));
  }

  @Test
  void onlyTheBanishingTeamStagesWhileOtherTeamsContest() {
    HunterInputSnapshot ownNear = input("team1", "team1", 20.0D, false);
    HunterInputSnapshot ownFallback = input("team1", "team1", 34.0D, false);
    HunterInputSnapshot enemy = input("team2", "team1", 20.0D, false);
    HunterInputSnapshot unknown = input("team2", "", 20.0D, false);

    assertEquals(
        StrategicGoal.STAGE_BOUNTY,
        HunterCoordinator.chooseStrategicGoal(ownNear, StrategicGoal.TRANSIT_TO_STAGE));
    assertEquals(
        StrategicGoal.STAGE_BOUNTY,
        HunterCoordinator.chooseStrategicGoal(ownFallback, StrategicGoal.STAGE_BOUNTY));
    assertEquals(
        StrategicGoal.TRANSIT_TO_STAGE,
        HunterCoordinator.chooseStrategicGoal(ownFallback, StrategicGoal.TRANSIT_TO_STAGE));
    assertEquals(
        StrategicGoal.CONTEST_BANISH,
        HunterCoordinator.chooseStrategicGoal(enemy, StrategicGoal.TRANSIT_TO_BOSS));
    assertEquals(
        StrategicGoal.CONTEST_BANISH,
        HunterCoordinator.chooseStrategicGoal(unknown, StrategicGoal.TRANSIT_TO_BOSS));
  }

  @Test
  void bountyCarrierKeepsExtractionGoalDuringAnEnemyBanishState() {
    HunterInputSnapshot carrier = input("team2", "team1", 40.0D, true);

    assertEquals(
        StrategicGoal.EXTRACT,
        HunterCoordinator.chooseStrategicGoal(carrier, StrategicGoal.CONTEST_BANISH));
  }

  @Test
  void enemyBanishAssaultUsesStableButDifferentPerimeterLanes() {
    Vec3 objective = new Vec3(100.0D, 64.0D, -30.0D);
    UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");

    Vec3 firstPoint = HunterCoordinator.contestAssaultPoint(objective, 67.0D, first, 1_200L);
    Vec3 samePhase = HunterCoordinator.contestAssaultPoint(objective, 67.0D, first, 1_298L);
    Vec3 nextPhase = HunterCoordinator.contestAssaultPoint(objective, 67.0D, first, 1_299L);
    Vec3 secondPoint = HunterCoordinator.contestAssaultPoint(objective, 67.0D, second, 1_200L);

    assertEquals(firstPoint, samePhase);
    assertEquals(67.0D, firstPoint.y, 0.0001D);
    assertTrue(firstPoint.distanceTo(objective) >= 6.5D);
    assertTrue(firstPoint.distanceTo(objective) <= 9.5D);
    assertFalse(firstPoint.equals(nextPhase));
    assertFalse(firstPoint.equals(secondPoint));
  }

  @Test
  void uninitializedEventTimestampIsNeverRecent() {
    assertFalse(HunterCoordinator.occurredWithin(Long.MIN_VALUE, 413_519_523L, 8L));
    assertTrue(HunterCoordinator.occurredWithin(413_519_520L, 413_519_523L, 8L));
    assertFalse(HunterCoordinator.occurredWithin(413_519_500L, 413_519_523L, 8L));
    assertFalse(HunterCoordinator.occurredWithin(413_519_524L, 413_519_523L, 8L));
  }

  @Test
  void expiredBountyDelayAlwaysReturnsToPublicBounty() {
    Vec3 bounty = new Vec3(30.0D, 64.0D, 30.0D);
    Vec3 ambush = new Vec3(18.0D, 66.0D, 22.0D);

    assertEquals(ambush, HunterCoordinator.claimTransitTarget(bounty, ambush, true, false));
    assertEquals(bounty, HunterCoordinator.claimTransitTarget(bounty, ambush, false, false));
    assertEquals(ambush, HunterCoordinator.claimTransitTarget(bounty, ambush, false, true));
  }

  @Test
  void objectiveInteractionUsesMarginInsideBridgeRange() {
    Vec3 objective = new Vec3(10.0D, 64.0D, 10.0D);

    assertTrue(HunterCoordinator.withinObjectiveInteractionRange(
        new Vec3(12.70D, 64.0D, 10.0D), objective));
    assertFalse(HunterCoordinator.withinObjectiveInteractionRange(
        new Vec3(12.80D, 64.0D, 10.0D), objective));
  }

  @Test
  void unbanishedBossUsesTheAuthoritativeBridgeRange() {
    HunterInputSnapshot inside = input(
        "team1", "", "UNBANISHED", 4.99D, false, 5.0D);
    HunterInputSnapshot outside = input(
        "team1", "", "UNBANISHED", 5.01D, false, 5.0D);

    assertEquals(
        StrategicGoal.START_BANISH,
        HunterCoordinator.chooseStrategicGoal(inside, StrategicGoal.TRANSIT_TO_BOSS));
    assertEquals(
        StrategicGoal.TRANSIT_TO_BOSS,
        HunterCoordinator.chooseStrategicGoal(outside, StrategicGoal.TRANSIT_TO_BOSS));
  }

  @Test
  void bountyTacticalDelayExpiresWithoutInvalidatingFutureClaiming() {
    long startedAt = 1_000L;

    assertTrue(HunterCoordinator.claimTacticalDelayActive(
        startedAt, 1_119L, HunterIntent.AMBUSH));
    assertFalse(HunterCoordinator.claimTacticalDelayActive(
        startedAt, 1_120L, HunterIntent.AMBUSH));
    assertFalse(HunterCoordinator.claimTacticalDelayActive(
        startedAt, 1_040L, HunterIntent.PUSH));
    assertFalse(HunterCoordinator.claimTacticalDelayActive(
        Long.MIN_VALUE, 1_040L, HunterIntent.OVERWATCH));
  }

  @Test
  void directDamageOnlyForcesEmergencyWhenCombatStateIsUnsafe() {
    assertFalse(HunterCoordinator.damageRequiresEmergency(
        0.80D, false, 4, true, 0.12D));
    assertFalse(HunterCoordinator.damageRequiresEmergency(
        0.70D, false, 4, false, 0.12D));
    assertTrue(HunterCoordinator.damageRequiresEmergency(
        0.28D, false, 4, true, 0.12D));
    assertTrue(HunterCoordinator.damageRequiresEmergency(
        0.45D, true, 0, true, 0.12D));
    assertTrue(HunterCoordinator.damageRequiresEmergency(
        0.45D, false, 4, true, 0.38D));
  }

  @Test
  void rejectedObjectiveInteractionSelectsAnotherPointInsideBridgeRange() {
    Vec3 objective = new Vec3(40.0D, 70.0D, -12.0D);
    UUID hunter = UUID.fromString("12345678-1234-5678-1234-567812345678");

    Vec3 first = HunterCoordinator.objectiveApproachPoint(objective, hunter, 1);
    Vec3 second = HunterCoordinator.objectiveApproachPoint(objective, hunter, 2);

    assertTrue(first.distanceTo(objective) < 2.75D);
    assertTrue(second.distanceTo(objective) < 2.75D);
    assertFalse(first.equals(second));
  }

  @Test
  void onlyCredibleNearbyEnemyEvidenceInterruptsSprintJumpTransit() {
    Vec3 origin = new Vec3(0.0D, 64.0D, 0.0D);
    Evidence farDamage = evidence(Evidence.Type.DAMAGE, new Vec3(120.0D, 64.0D, 0.0D));
    Evidence nearDamage = evidence(Evidence.Type.DAMAGE, new Vec3(20.0D, 64.0D, 0.0D));
    Evidence objective = evidence(Evidence.Type.OBJECTIVE_CHANGE, origin);

    assertFalse(HunterCoordinator.hasLocalMovementThreat(List.of(farDamage), origin, 100L));
    assertFalse(HunterCoordinator.hasLocalMovementThreat(List.of(objective), origin, 100L));
    assertTrue(HunterCoordinator.hasLocalMovementThreat(List.of(nearDamage), origin, 100L));
  }

  @Test
  void partialSafeResetRouteRemainsAContinuationCheckpoint() {
    Vec3 requestedCover = new Vec3(30.0D, 64.0D, 30.0D);
    Vec3 partialEnd = new Vec3(18.0D, 64.0D, 24.0D);

    assertFalse(HunterCoordinator.routeReachesRequestedTarget(requestedCover, partialEnd));
    assertTrue(HunterCoordinator.routeReachesRequestedTarget(requestedCover, requestedCover));
    assertFalse(HunterCoordinator.partialFallbackCheckpointReached(
        true, new Vec3(15.0D, 64.0D, 24.0D), partialEnd));
    assertTrue(HunterCoordinator.partialFallbackCheckpointReached(
        true, new Vec3(17.0D, 64.0D, 24.0D), partialEnd));
    assertFalse(HunterCoordinator.partialFallbackCheckpointReached(
        false, partialEnd, partialEnd));
  }

  @Test
  void bountyCrouchRequiresCoverEscapeAndActualExposureBenefit() {
    assertTrue(
        HunterCoordinator.crouchHasTacticalBenefit(
            node(0.55D, 0.70D, 0.40D, 2, false)));
    assertFalse(
        HunterCoordinator.crouchHasTacticalBenefit(
            node(0.10D, 1.00D, 0.80D, 3, true)));
    assertFalse(
        HunterCoordinator.crouchHasTacticalBenefit(
            node(0.55D, 0.70D, 0.40D, 1, false)));
    assertFalse(
        HunterCoordinator.crouchHasTacticalBenefit(
            node(0.55D, 0.50D, 0.42D, 2, false)));
  }

  @Test
  void flankPersistentHunterUsesAnAlternateSearchApproach() {
    UUID hunter = new UUID(2L, 4L);
    Vec3 self = Vec3.ZERO;
    Vec3 remembered = new Vec3(10.0D, 0.0D, 0.0D);
    TacticalNode direct = nodeAt(new Vec3(8.0D, 0.0D, 0.0D), 0.40D);
    TacticalNode lateral = nodeAt(new Vec3(10.0D, 0.0D, 5.0D), 0.55D);

    assertEquals(
        lateral.position(),
        HunterCoordinator.selectSearchApproach(
            self, remembered, List.of(direct, lateral), hunter, 0.80D));
    assertEquals(
        direct.position(),
        HunterCoordinator.selectSearchApproach(
            self, remembered, List.of(direct, lateral), hunter, 0.30D));
  }

  @Test
  void searchApproachNeverRunsAwayFromFreshEvidence() {
    UUID hunter = new UUID(2L, 4L);
    Vec3 self = Vec3.ZERO;
    Vec3 remembered = new Vec3(10.0D, 0.0D, 0.0D);
    TacticalNode wrongWay = nodeAt(new Vec3(-8.0D, 0.0D, 7.0D), 0.90D);
    TacticalNode progress = nodeAt(new Vec3(7.0D, 0.0D, 2.0D), 0.40D);

    assertEquals(
        progress.position(),
        HunterCoordinator.selectSearchApproach(
            self, remembered, List.of(wrongWay, progress), hunter, 0.80D));
  }

  @Test
  void combatSearchReleasesMovementWhenItHasReachedItsApproach() {
    Vec3 self = new Vec3(10.0D, 64.0D, 10.0D);

    assertFalse(HunterCoordinator.shouldAdvanceCombatSearch(self, self.add(2.0D, 0.0D, 0.0D)));
    assertTrue(HunterCoordinator.shouldAdvanceCombatSearch(self, self.add(2.1D, 0.0D, 0.0D)));
  }

  @Test
  void interceptionArrivalUsesTheSameTwoBlockRadiusAsMovementHold() {
    assertEquals(4.0D, HunterCoordinator.planArrivalDistanceSquared(StrategicGoal.INTERCEPT_APPROACH));
    assertEquals(3.0D, HunterCoordinator.planArrivalDistanceSquared(StrategicGoal.TRANSIT_TO_BOSS));
  }

  @Test
  void extractionIntermediateLegContinuesUntilARealExtractionZone() {
    assertTrue(HunterCoordinator.extractionLegNeedsContinuation(
        StrategicGoal.EXTRACT, false));
    assertFalse(HunterCoordinator.extractionLegNeedsContinuation(
        StrategicGoal.EXTRACT, true));
    assertFalse(HunterCoordinator.extractionLegNeedsContinuation(
        StrategicGoal.TRANSIT_TO_BOSS, false));
  }

  @Test
  void oneFootstepIsOnlyMemoryButRepeatedNearbyFootstepsTriggerConfirmation() {
    Vec3 self = new Vec3(0.0D, 64.0D, 0.0D);
    Evidence first = movementEvidence(90L, new Vec3(10.0D, 64.0D, 0.0D), "sound:player-a");
    Evidence second = movementEvidence(98L, new Vec3(9.0D, 64.0D, 1.0D), "sound:player-a");

    assertEquals(null, HunterCoordinator.corroboratedCloseMovementEvidence(List.of(first), self, 100L));
    assertEquals(
        second,
        HunterCoordinator.corroboratedCloseMovementEvidence(List.of(first, second), self, 100L));
  }

  private static TacticalNode node(
      double hardCover,
      double standingExposure,
      double crouchedExposure,
      int escapes,
      boolean openToSky) {
    return new TacticalNode(
        Vec3.ZERO,
        hardCover,
        openToSky ? 0.0D : 1.0D,
        openToSky,
        true,
        escapes,
        0.0D,
        hardCover,
        standingExposure,
        crouchedExposure,
        hardCover,
        escapes / 4.0D,
        1.0D - escapes / 4.0D,
        hardCover,
        openToSky ? 0.70D : 0.30D);
  }

  private static TacticalNode nodeAt(Vec3 position, double hardCover) {
    return new TacticalNode(
        position,
        hardCover,
        hardCover,
        false,
        true,
        3,
        0.0D,
        hardCover,
        0.35D,
        0.20D,
        hardCover,
        0.70D,
        0.20D,
        0.60D,
        0.25D);
  }

  private static GodDirector.Directive director(
      boolean pursueObjective, boolean immediateContest) {
    return new GodDirector.Directive(
        pursueObjective ? GodDirector.Role.OBJECTIVE_RUNNER : GodDirector.Role.AMBUSHER,
        pursueObjective,
        immediateContest,
        false,
        0.0D,
        200L,
        "test");
  }

  private static Evidence evidence(Evidence.Type type, Vec3 center) {
    return new Evidence(
        UUID.randomUUID(),
        type,
        UUID.randomUUID(),
        new EvidenceRegion(center, 1.0D, Vec3.ZERO, 0.0D),
        100L,
        200L,
        0.90D,
        "test");
  }

  private static Evidence movementEvidence(long observedAt, Vec3 center, String source) {
    return new Evidence(
        UUID.nameUUIDFromBytes((source + observedAt).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        Evidence.Type.MOVEMENT_SOUND,
        null,
        new EvidenceRegion(center, 4.0D, Vec3.ZERO, 0.08D),
        observedAt,
        observedAt + 80L,
        0.45D,
        source);
  }

  private static HunterInputSnapshot input(
      String teamId, String banishingTeamId, double objectiveDistance, boolean carrying) {
    return input(teamId, banishingTeamId, "BANISHING", objectiveDistance, carrying, 5.0D);
  }

  private static HunterInputSnapshot input(
      String teamId,
      String banishingTeamId,
      String bountyState,
      double objectiveDistance,
      boolean carrying,
      double bossInteractionDistance) {
    UUID hunterId = UUID.randomUUID();
    Vec3 position = new Vec3(0.0D, 64.0D, 0.0D);
    SelfSnapshot self = new SelfSnapshot(
        position,
        Vec3.ZERO,
        0.0F,
        0.0F,
        150.0D,
        150.0D,
        8,
        8,
        false,
        true,
        false,
        false,
        false,
        false);
    MatchDirective match = new MatchDirective(
        hunterId,
        UUID.randomUUID(),
        teamId,
        true,
        true,
        "ACTIVE",
        bountyState,
        new Vec3(objectiveDistance, 64.0D, 0.0D),
        new Vec3(100.0D, 64.0D, 0.0D),
        carrying,
        100L,
        1_200L,
        600L,
        0.0D,
        banishingTeamId,
        java.util.List.of(),
        bossInteractionDistance);
    return new HunterInputSnapshot(hunterId, 1, 100L, self, match);
  }
}
