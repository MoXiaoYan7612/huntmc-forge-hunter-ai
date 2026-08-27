package cn.huntmc.hunterai.decision;

import cn.huntmc.hunterai.action.EntityActuator;
import cn.huntmc.hunterai.ai.HunterIntent;
import cn.huntmc.hunterai.ai.HunterPersonality;
import cn.huntmc.hunterai.ai.CombatEvaluation;
import cn.huntmc.hunterai.ai.OpportunityDetector;
import cn.huntmc.hunterai.ai.RecentBehavior;
import cn.huntmc.hunterai.ai.SelfModel;
import cn.huntmc.hunterai.ai.TacticalLayout;
import cn.huntmc.hunterai.ai.TacticalMap;
import cn.huntmc.hunterai.ai.TacticalNode;
import cn.huntmc.hunterai.ai.TacticalOpportunity;
import cn.huntmc.hunterai.ai.TacticalSpace;
import cn.huntmc.hunterai.ai.ThreatCorridor;
import cn.huntmc.hunterai.ai.UtilityDecision;
import cn.huntmc.hunterai.entity.HunterEntity;
import cn.huntmc.hunterai.director.GodDirector;
import cn.huntmc.hunterai.model.Evidence;
import cn.huntmc.hunterai.model.HunterInputSnapshot;
import cn.huntmc.hunterai.model.HunterWorldModel;
import cn.huntmc.hunterai.navigation.ValidatedRoute;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Builds bounded, spatially complete candidates and validates every selected route natively. */
public final class TacticalDecisionPlanner {
  private static final int MAX_CANDIDATES = 6;
  private final TacticalMap tacticalMap = new TacticalMap();
  private final RecentBehavior recent = new RecentBehavior();
  private final OpportunityDetector opportunityDetector = new OpportunityDetector();
  private final UtilityDecision utilityDecision = new UtilityDecision();
  private TacticalSpace lastSpace = new TacticalSpace(Vec3.ZERO, null, List.of(), 0.5D, 0.0D);
  private long lastStrategicOverwatchAt = Long.MIN_VALUE;

  public PlanningResult candidates(
      HunterEntity hunter,
      HunterInputSnapshot input,
      HunterWorldModel.Snapshot world,
      HunterPersonality personality,
      StrategicGoal goal,
      GodDirector.Role directorRole,
      LivingEntity visible,
      EntityActuator actuator,
      long now) {
    Vec3 threat = visible == null ? mostRelevantThreat(world, now) : visible.position();
    Vec3 tacticalAnchor =
        threat == null
            && (goal == StrategicGoal.STAGE_BOUNTY
                || goal == StrategicGoal.INTERCEPT_APPROACH)
            ? input.match().publicObjective()
            : threat;
    tacticalMap.refresh(hunter, tacticalAnchor == null ? hunter.position() : tacticalAnchor);
    List<ThreatCorridor> corridors = threatCorridors(hunter.position(), world, now);
    lastSpace = new TacticalSpace(
        hunter.position(),
        threat,
        enrichNodes(tacticalMap.nodes(), corridors),
        tacticalMap.localExposure(hunter),
        uncertainty(world)).withCorridors(corridors);

    List<DecisionCandidate> result = new ArrayList<>();
    if (visible != null) {
      addCombatCandidates(result, hunter, input, personality, goal, visible, actuator, now);
    } else {
      Evidence recentDeath = world.evidence().stream()
          .filter(item -> item.type() == Evidence.Type.DEATH)
          .filter(item -> now - item.observedAt() <= 140L)
          .filter(item -> item.confidence(now) >= 0.45D)
          .max(Comparator.comparingLong(Evidence::observedAt))
          .orElse(null);
      if (recentDeath != null) {
        Vec3 deathPosition = recentDeath.region().center();
        addLayoutCandidate(
            result, hunter, input, personality, goal, HunterIntent.AMBUSH, deathPosition,
            actuator, now, 0.67D + personality.ambushPreference() * 0.16D,
            0.12D, 0.16D, "post-kill-guard-ambush");
        addLayoutCandidate(
            result, hunter, input, personality, goal, HunterIntent.OVERWATCH, deathPosition,
            actuator, now, 0.64D + personality.patience() * 0.16D,
            0.10D, 0.14D, "post-kill-guard-overwatch");
      }
      boolean freshDirectAttack = hasFreshDirectAttack(world, now);
      boolean extractionThreat = goal == StrategicGoal.EXTRACT
          && credibleThreat(world, now) >= 0.65D
          && credibleHostileSources(world, now, 40L) >= 2;
      if (freshDirectAttack || extractionThreat)
        addThreatResponseCandidates(
            result, hunter, input, personality, goal, threat, actuator, now,
            freshDirectAttack ? "direct-attack" : "extract-threat");
      else if (world.encounter().multiPartyFight())
        addThirdPartyCandidates(result, hunter, input, personality, goal, threat, actuator, now);
      else if (goal == StrategicGoal.CLAIM_BOUNTY && credibleThreat(world, now) >= 0.65D) {
        addLayoutCandidate(
            result,
            hunter,
            input,
            personality,
            goal,
            HunterIntent.AMBUSH,
            threat,
            actuator,
            now,
            0.61D + personality.ambushPreference() * 0.18D,
            0.16D,
            0.22D,
            "bounty-threat-ambush");
        addLayoutCandidate(
            result,
            hunter,
            input,
            personality,
            goal,
            HunterIntent.OVERWATCH,
            threat,
            actuator,
            now,
            0.58D + personality.patience() * 0.18D,
            0.14D,
            0.20D,
            "bounty-threat-overwatch");
      }
      addStrategicCandidates(result, hunter, input, personality, goal, actuator, now);
    }
    if (result.stream().noneMatch(DecisionCandidate::feasible))
      addSafetyCandidate(result, hunter, input, personality, goal, threat, actuator, now);
    OpportunityDetector.Result opportunities = opportunityDetector.detect(
        lastSpace,
        visible != null,
        credibleThreat(world, now),
        selfSnapshot(input, world),
        hunter.profile().preferredRange(),
        hunter.profile().maximumRange());
    UtilityDecision.Decision utility = utilityDecision.choose(
        utilityContext(
            hunter, input, world, personality, visible != null, now, opportunities, result),
        new Random(hunter.getUUID().getMostSignificantBits()
            ^ Long.rotateLeft(hunter.getUUID().getLeastSignificantBits(), 17)
            ^ now / 20L));
    List<DecisionCandidate> selected = result.stream()
        .map(candidate -> withUtilityScore(candidate, utility, directorRole))
        .sorted(Comparator.comparingDouble(DecisionCandidate::analyticScore).reversed())
        .limit(MAX_CANDIDATES)
        .toList();
    return new PlanningResult(lastSpace, selected, utility, opportunities.summary());
  }

  public void remember(DecisionCandidate candidate, long now) {
    if (candidate == null) return;
    recent.recordIntent(candidate.intent(), now);
    recent.recordPosition(candidate.target(), now);
    if (candidate.source().contains("strategic-overwatch-stop")) lastStrategicOverwatchAt = now;
  }

  public TacticalSpace lastSpace() {
    return lastSpace;
  }

  public TacticalMap tacticalMap() {
    return tacticalMap;
  }

  public void reset() {
    recent.clear();
    lastStrategicOverwatchAt = Long.MIN_VALUE;
    lastSpace = new TacticalSpace(Vec3.ZERO, null, List.of(), 0.5D, 0.0D);
  }

  private void addCombatCandidates(
      List<DecisionCandidate> candidates,
      HunterEntity hunter,
      HunterInputSnapshot input,
      HunterPersonality personality,
      StrategicGoal strategicGoal,
      LivingEntity visible,
      EntityActuator actuator,
      long now) {
    Vec3 target = visible.position();
    double distance = hunter.position().distanceTo(target);
    HunterIntent directIntent = distance > hunter.profile().preferredRange() * 1.20D ? HunterIntent.PUSH : HunterIntent.HOLD_ANGLE;
    addLayoutCandidate(
        candidates, hunter, input, personality, strategicGoal, directIntent, target, actuator, now,
        0.58D + personality.aggression() * 0.22D, 0.58D, 0.24D, "visible-direct");
    addLayoutCandidate(
        candidates, hunter, input, personality, strategicGoal, HunterIntent.FLANK, target, actuator, now,
        0.52D + personality.flankPreference() * 0.28D, 0.38D, 0.36D, "visible-flank");
    if (strategicGoal != StrategicGoal.CONTEST_BANISH)
      addLayoutCandidate(
          candidates, hunter, input, personality, strategicGoal, HunterIntent.OVERWATCH, target, actuator, now,
          0.50D + personality.patience() * 0.22D, 0.24D, 0.22D, "visible-observe");
    if (input.self().healthRatio() < 0.48D || input.self().ammoRatio() < 0.18D)
      addLayoutCandidate(
          candidates, hunter, input, personality, strategicGoal, HunterIntent.DISENGAGE, target,
          actuator, now, 0.72D, 0.14D, 0.08D, "combat-break-contact");
  }

  private void addThirdPartyCandidates(
      List<DecisionCandidate> candidates,
      HunterEntity hunter,
      HunterInputSnapshot input,
      HunterPersonality personality,
      StrategicGoal goal,
      Vec3 threat,
      EntityActuator actuator,
      long now) {
    addLayoutCandidate(
        candidates, hunter, input, personality, goal, HunterIntent.AMBUSH, threat, actuator, now,
        0.63D + personality.ambushPreference() * 0.22D, 0.18D, 0.30D, "third-party-ambush");
    addLayoutCandidate(
        candidates, hunter, input, personality, goal, HunterIntent.INTERCEPT, threat, actuator, now,
        0.56D + personality.aggression() * 0.16D, 0.32D, 0.24D, "third-party-intercept");
    addLayoutCandidate(
        candidates, hunter, input, personality, goal, HunterIntent.OVERWATCH, threat, actuator, now,
        0.60D + personality.patience() * 0.18D, 0.14D, 0.32D, "third-party-harvest-wait");
  }

  private void addThreatResponseCandidates(
      List<DecisionCandidate> candidates,
      HunterEntity hunter,
      HunterInputSnapshot input,
      HunterPersonality personality,
      StrategicGoal goal,
      Vec3 threat,
      EntityActuator actuator,
      long now,
      String source) {
    if (threat == null) return;
    addLayoutCandidate(
        candidates, hunter, input, personality, goal, HunterIntent.REPOSITION, threat, actuator, now,
        0.70D + personality.coverPreference() * 0.16D, 0.22D, 0.08D,
        source + "-reposition");
    addLayoutCandidate(
        candidates, hunter, input, personality, goal, HunterIntent.OVERWATCH, threat, actuator, now,
        0.64D + personality.patience() * 0.14D, 0.16D, 0.12D,
        source + "-counter-angle");
  }

  private void addStrategicCandidates(
      List<DecisionCandidate> candidates,
      HunterEntity hunter,
      HunterInputSnapshot input,
      HunterPersonality personality,
      StrategicGoal goal,
      EntityActuator actuator,
      long now) {
    Vec3 objective = strategicObjective(hunter, input, goal);
    if (objective == null) return;
    double distance = hunter.position().distanceTo(objective);
    if (immediateBossInteraction(
        goal, distance, input.match().bossInteractionDistance())) {
      TacticalOpportunity immediateInteraction = opportunity(
          HunterIntent.PUSH,
          null,
          null,
          null,
          null,
          null,
          null,
          0.98D,
          lastSpace.localExposure(),
          "immediate-boss-interaction");
      candidates.add(candidate(
          goal,
          HunterIntent.PUSH,
          immediateInteraction,
          null,
          objective,
          0.98D,
          lastSpace.localExposure(),
          0.0D,
          0.65D,
          "immediate-boss-interaction",
          ""));
      return;
    }
    if (goal == StrategicGoal.STAGE_BOUNTY) {
      addLayoutCandidate(
          candidates, hunter, input, personality, goal, HunterIntent.AMBUSH, objective, actuator, now,
          0.65D + personality.ambushPreference() * 0.20D, 0.16D, 0.12D, "bounty-defense-ambush");
      addLayoutCandidate(
          candidates, hunter, input, personality, goal, HunterIntent.OVERWATCH, objective, actuator, now,
          0.62D + personality.highGroundPreference() * 0.20D, 0.19D, 0.10D, "bounty-defense-overwatch");
      return;
    }
    if (goal == StrategicGoal.EXTRACT) {
      addExtractionCandidates(candidates, hunter, input, personality, actuator, now);
      return;
    }
    if (goal == StrategicGoal.INTERCEPT_APPROACH && distance <= 12.0D) {
      Vec3 publicObjective = input.match().publicObjective();
      addLayoutCandidate(
          candidates, hunter, input, personality, goal, HunterIntent.AMBUSH,
          publicObjective, actuator, now,
          0.66D + personality.ambushPreference() * 0.20D,
          0.14D, 0.18D, "objective-perimeter-ambush");
      addLayoutCandidate(
          candidates, hunter, input, personality, goal, HunterIntent.OVERWATCH,
          publicObjective, actuator, now,
          0.61D + personality.patience() * 0.20D,
          0.16D, 0.16D, "objective-perimeter-overwatch");
      addLayoutCandidate(
          candidates, hunter, input, personality, goal, HunterIntent.INTERCEPT,
          publicObjective, actuator, now,
          0.58D + personality.flankPreference() * 0.18D,
          0.22D, 0.12D, "objective-perimeter-intercept");
      return;
    }

    if (shouldConsiderStrategicOverwatch(
        hunter.getUUID(), personality, distance, now, lastStrategicOverwatchAt)) {
      TacticalNode overwatch = tacticalMap.bestOverwatch(
          hunter, personality.highGroundPreference(), List.of());
      if (overwatch != null && overwatch.position().y - hunter.getY() >= 1.5D) {
        double exposure = Math.min(0.55D, lastSpace.localExposure() * 0.65D);
        double repetition = recent.positionPenalty(overwatch.position(), now);
        ValidatedRoute route = actuator.validateRoute(
            hunter,
            overwatch.position(),
            exposure,
            repetition,
            "strategic-overwatch-stop:" + now / 20L);
        double score = 0.50D
            + personality.highGroundPreference() * 0.18D
            + personality.patience() * 0.08D
            + tacticalPositionBonus(HunterIntent.OVERWATCH, overwatch, personality)
            - repetition * 0.16D;
        TacticalOpportunity opportunity = opportunity(
            HunterIntent.OVERWATCH,
            route,
            overwatch,
            overwatch,
            overwatch.canObserveThreat() ? overwatch : null,
            null,
            null,
            score,
            exposure,
            "strategic-overwatch-stop");
        candidates.add(candidate(
            goal,
            HunterIntent.OVERWATCH,
            opportunity,
            route,
            overwatch.position(),
            score,
            exposure,
            0.10D,
            Math.min(1.0D, overwatch.escapeDirections() / 4.0D),
            "strategic-overwatch-stop",
            route.reachable() ? "" : route.failureReason()));
      }
    }

    List<Vec3> routeTargets = diversifiedTargets(hunter, objective, distance, personality);
    double deadlineUrgency = deadlineUrgency(input);
    int routeIndex = 0;
    for (Vec3 routeTarget : routeTargets) {
      double repetition = recent.positionPenalty(routeTarget, now);
      double exposure = Math.min(1.0D, lastSpace.localExposure() * 0.55D + Math.abs(routeIndex - 2) * 0.05D);
      ValidatedRoute route = actuator.validateRoute(
          hunter, routeTarget, exposure, repetition, "strategic-" + goal + "-" + routeIndex + "-" + now / 20L);
      double progress = distance <= 1.0D
          ? 1.0D
          : Math.max(0.0D, Math.min(1.0D,
              (distance - routeTarget.distanceTo(objective)) / Math.max(1.0D, distance)));
      double score = 0.44D + progress * 0.24D + deadlineUrgency * 0.22D
          + personality.riskTolerance() * 0.06D - exposure * 0.10D - repetition * 0.16D;
      TacticalOpportunity opportunity = opportunity(
          HunterIntent.PUSH, route, null, null, null, null, null, score, exposure, "diverse-objective-route");
      candidates.add(candidate(
          goal, HunterIntent.PUSH, opportunity, route, routeTarget, score, exposure,
          Math.max(0.0D, 1.0D - progress), 0.48D, "strategic-route", route.reachable() ? "" : route.failureReason()));
      routeIndex++;
    }
  }

  private void addExtractionCandidates(
      List<DecisionCandidate> candidates,
      HunterEntity hunter,
      HunterInputSnapshot input,
      HunterPersonality personality,
      EntityActuator actuator,
      long now) {
    List<Vec3> exits = input.match().extractions();
    if (exits.isEmpty() && input.match().extraction() != null)
      exits = List.of(input.match().extraction());
    double deadlineUrgency = deadlineUrgency(input);
    int exitIndex = 0;
    for (Vec3 exit : exits) {
      double distance = hunter.position().distanceTo(exit);
      List<Vec3> lanes = diversifiedTargets(hunter, exit, distance, personality);
      int laneLimit = exits.size() <= 2 ? 3 : 2;
      int laneIndex = 0;
      for (Vec3 routeTarget : lanes.stream().limit(laneLimit).toList()) {
        double repetition = recent.positionPenalty(routeTarget, now);
        ValidatedRoute route = actuator.validateRoute(
            hunter,
            routeTarget,
            lastSpace.localExposure(),
            repetition,
            "extract-exit-" + exitIndex + "-lane-" + laneIndex + ":" + now / 20L);
        double threatRisk = routeThreatRisk(route, lastSpace.threatCorridors());
        double progress = distance <= 1.0D
            ? 1.0D
            : clamp((distance - routeTarget.distanceTo(exit)) / Math.max(1.0D, distance));
        double exposure = clamp(
            lastSpace.localExposure() * 0.42D
                + threatRisk * 0.52D
                + Math.abs(laneIndex - 1) * 0.025D);
        double detourCost = route.reachable() && distance > 1.0D
            ? clamp(Math.max(0.0D, route.length() - distance) / Math.max(16.0D, distance))
            : 1.0D;
        double score = 0.58D
            + progress * 0.20D
            + deadlineUrgency * 0.15D
            + personality.riskTolerance() * 0.04D
            - threatRisk * 0.62D
            - exposure * 0.12D
            - detourCost * 0.08D
            - repetition * 0.14D;
        String source = "extract-route:exit=" + exitIndex
            + ":lane=" + laneIndex
            + ":threat=" + decimal(threatRisk);
        TacticalOpportunity opportunity = opportunity(
            HunterIntent.PUSH,
            route,
            null,
            null,
            null,
            null,
            null,
            score,
            exposure,
            source);
        candidates.add(candidate(
            StrategicGoal.EXTRACT,
            HunterIntent.PUSH,
            opportunity,
            route,
            routeTarget,
            score,
            exposure,
            detourCost,
            clamp(1.0D - threatRisk),
            source,
            route.reachable() ? "" : route.failureReason()));
        laneIndex++;
      }
      exitIndex++;
    }
  }

  private static Vec3 strategicObjective(
      HunterEntity hunter, HunterInputSnapshot input, StrategicGoal goal) {
    if (goal == StrategicGoal.EXTRACT) return input.match().extraction();
    if (goal == StrategicGoal.REVIVE_TEAMMATE)
      return input.match().reviveTargets().stream()
          .min(Comparator.comparingDouble(target -> target.position().distanceToSqr(hunter.position())))
          .map(cn.huntmc.hunterai.match.ReviveTarget::position)
          .orElse(null);
    if (goal == StrategicGoal.INTERCEPT_APPROACH)
      return interceptionPoint(
          input.match().publicObjective(), hunter.getY(), hunter.getUUID());
    return input.match().publicObjective();
  }

  public static Vec3 interceptionPoint(Vec3 objective, double currentY, UUID hunterId) {
    if (objective == null || hunterId == null) return objective;
    long identity = hunterId.getMostSignificantBits()
        ^ Long.rotateLeft(hunterId.getLeastSignificantBits(), 23);
    identity ^= identity >>> 33;
    identity *= 0xff51afd7ed558ccdL;
    identity ^= identity >>> 33;
    double angleUnit = (identity & 0x1fffffL) / (double) 0x200000L;
    double radiusUnit = ((identity >>> 21) & 0xffffL) / (double) 0x10000L;
    double angle = angleUnit * Math.PI * 2.0D;
    double radius = 48.0D + radiusUnit * 24.0D;
    return new Vec3(
        objective.x + Math.cos(angle) * radius,
        currentY,
        objective.z + Math.sin(angle) * radius);
  }

  static boolean immediateBossInteraction(
      StrategicGoal goal, double distance, double interactionRange) {
    return goal == StrategicGoal.START_BANISH
        && Double.isFinite(distance)
        && Double.isFinite(interactionRange)
        && interactionRange > 0.0D
        && distance <= interactionRange;
  }

  private void addSafetyCandidate(
      List<DecisionCandidate> candidates,
      HunterEntity hunter,
      HunterInputSnapshot input,
      HunterPersonality personality,
      StrategicGoal goal,
      Vec3 threat,
      EntityActuator actuator,
      long now) {
    addLayoutCandidate(
        candidates, hunter, input, personality, goal, HunterIntent.LISTEN, threat, actuator, now,
        0.42D + personality.vigilance() * 0.16D, 0.10D, 0.15D, "safe-observe");
  }

  private void addLayoutCandidate(
      List<DecisionCandidate> candidates,
      HunterEntity hunter,
      HunterInputSnapshot input,
      HunterPersonality personality,
      StrategicGoal goal,
      HunterIntent intent,
      Vec3 threat,
      EntityActuator actuator,
      long now,
      double baseScore,
      double exposure,
      double delay,
      String source) {
    TacticalLayout layout =
        goal == StrategicGoal.STAGE_BOUNTY
            ? tacticalMap.bestBountyDefenseLayout(
                hunter, intent, input.match().publicObjective(), List.of(), recent, now)
            : tacticalMap.bestLayout(
                hunter,
                intent,
                threat,
                hunter.profile().preferredRange(),
                List.of(),
                recent,
                now);
    TacticalNode primary = layout == null ? tacticalMap.bestLocalCover() : layout.primary();
    Vec3 target = primary == null ? hunter.position() : primary.position();
    double repetition = recent.positionPenalty(target, now) + recent.intentPenalty(intent, now);
    ValidatedRoute route = actuator.validateRoute(hunter, target, exposure, repetition, source + ":" + now / 20L);
    double score = baseScore
        + personality.coverPreference() * (primary == null ? 0.0D : primary.hardCover()) * 0.12D
        + tacticalPositionBonus(intent, primary, personality)
        - repetition * 0.18D - exposure * 0.10D;
    TacticalNode waiting = layout == null ? primary : layout.waiting();
    TacticalNode first = layout == null ? primary : layout.firing();
    TacticalNode second = layout == null ? null : layout.secondaryFiring();
    TacticalNode retreat = layout == null ? primary : layout.retreat();
    waiting =
        validatedCompanion(
            hunter, waiting, route, actuator, exposure, repetition, source + ":waiting:" + now / 20L);
    first =
        validatedCompanion(
            hunter, first, route, actuator, exposure, repetition, source + ":firing-1:" + now / 20L);
    second =
        validatedCompanion(
            hunter, second, route, actuator, exposure, repetition, source + ":firing-2:" + now / 20L);
    ValidatedRoute retreatRoute =
        retreat == null
            ? null
            : actuator.validateRoute(
                hunter,
                retreat.position(),
                0.12D,
                repetition,
                source + ":retreat:" + now / 20L);
    if (retreatRoute != null && !retreatRoute.reachable()) {
      retreatRoute = null;
      retreat = null;
    }
    TacticalOpportunity opportunity = opportunity(
        intent, route, waiting, primary, first, second, retreatRoute, score, exposure, source);
    candidates.add(candidate(
        goal, intent, opportunity, route, target, score, exposure, delay,
        retreat == null ? 0.25D : Math.min(1.0D, retreat.escapeDirections() / 4.0D), source,
        route.reachable() ? "" : route.failureReason()));
  }

  private static TacticalNode validatedCompanion(
      HunterEntity hunter,
      TacticalNode node,
      ValidatedRoute primaryRoute,
      EntityActuator actuator,
      double exposure,
      double repetition,
      String routeKey) {
    if (node == null) return null;
    if (primaryRoute != null
        && primaryRoute.reachable()
        && primaryRoute.destination() != null
        && primaryRoute.destination().distanceToSqr(node.position()) <= 0.25D) return node;
    ValidatedRoute route =
        actuator.validateRoute(hunter, node.position(), exposure, repetition, routeKey);
    return route.reachable() ? node : null;
  }

  static double tacticalPositionBonus(
      HunterIntent intent, TacticalNode node, HunterPersonality personality) {
    if (node == null || personality == null) return 0.0D;
    if (intent == HunterIntent.OVERWATCH) {
      double elevation = Math.min(3.0D, Math.max(0.0D, node.verticalDelta()));
      return node.shelter() * 0.08D
          + elevation * personality.highGroundPreference() * 0.055D
          + node.entranceControl() * 0.05D
          + node.rearSafety() * 0.04D;
    }
    if (intent == HunterIntent.AMBUSH)
      return node.shelter() * 0.07D
          + node.entranceControl() * 0.06D
          + node.rearSafety() * 0.03D;
    return 0.0D;
  }

  private TacticalOpportunity opportunity(
      HunterIntent intent,
      ValidatedRoute route,
      TacticalNode waiting,
      TacticalNode observation,
      TacticalNode first,
      TacticalNode second,
      ValidatedRoute retreat,
      double score,
      double compromise,
      String source) {
    String identity = intent + ":" + source + ":" + (route == null ? "none" : route.id());
    UUID id = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    return new TacticalOpportunity(
        id, intent, route, waiting, observation, first, second, retreat,
        lastSpace.threatCorridors(), score, compromise, source);
  }

  private static DecisionCandidate candidate(
      StrategicGoal goal,
      HunterIntent intent,
      TacticalOpportunity opportunity,
      ValidatedRoute route,
      Vec3 target,
      double score,
      double exposure,
      double delay,
      double escape,
      String source,
      String infeasible) {
    String identity = goal + ":" + intent + ":" + source + ":" + (route == null ? target : route.id());
    return new DecisionCandidate(
        UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)),
        goal, intent, opportunity, route, target, clamp(score), clamp(exposure), clamp(delay),
        clamp(escape), source, infeasible);
  }

  static List<Vec3> diversifiedTargets(
      HunterEntity hunter, Vec3 objective, double distance, HunterPersonality personality) {
    return diversifiedTargets(hunter.position(), hunter.getUUID(), objective, distance, personality);
  }

  static boolean shouldConsiderStrategicOverwatch(
      UUID hunterId,
      HunterPersonality personality,
      double objectiveDistance,
      long now,
      long lastOverwatchAt) {
    if (hunterId == null || personality == null) return false;
    if (objectiveDistance < 18.0D || objectiveDistance > 56.0D) return false;
    if (personality.highGroundPreference() < 0.55D || personality.patience() < 0.40D) return false;
    if (lastOverwatchAt != Long.MIN_VALUE && now >= lastOverwatchAt && now - lastOverwatchAt < 600L)
      return false;
    int identity = Math.floorMod(hunterId.hashCode(), 100);
    int participation = 30 + (int) Math.round(personality.highGroundPreference() * 30.0D);
    return identity < participation;
  }

  static List<Vec3> diversifiedTargets(
      Vec3 hunterPosition,
      UUID hunterId,
      Vec3 objective,
      double distance,
      HunterPersonality personality) {
    if (distance <= 34.0D) return List.of(objective);
    Vec3 forward = objective.subtract(hunterPosition).multiply(1.0D, 0.0D, 1.0D).normalize();
    Vec3 side = new Vec3(-forward.z, 0.0D, forward.x);
    double advance = Math.min(42.0D, distance * 0.58D);
    double spread = 8.0D + personality.riskTolerance() * 12.0D;
    int rotation = Math.floorMod(hunterId.hashCode(), 5);
    List<Vec3> points = new ArrayList<>(5);
    for (int index = 0; index < 5; index++) {
      int lane = Math.floorMod(index + rotation, 5) - 2;
      points.add(hunterPosition.add(forward.scale(advance)).add(side.scale(lane * spread * 0.50D)));
    }
    return List.copyOf(points);
  }

  public static List<ThreatCorridor> threatCorridors(
      Vec3 hunterPosition, HunterWorldModel.Snapshot world, long now) {
    List<ThreatCorridor> result = new ArrayList<>();
    int index = 0;
    for (Evidence evidence : world.evidence().stream()
        .filter(item -> item.type() == Evidence.Type.GUNSHOT
            || item.type() == Evidence.Type.VISUAL
            || item.type() == Evidence.Type.DAMAGE)
        .filter(item -> item.confidence(now) >= 0.35D)
        .sorted(Comparator.comparingLong(Evidence::observedAt).reversed())
        .limit(6)
        .toList()) {
      Vec3 towardHunter = hunterPosition.subtract(evidence.region().center());
      Vec3 direction = towardHunter.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 0.0D, 1.0D) : towardHunter.normalize();
      result.add(new ThreatCorridor(
          "corridor-" + index++, evidence.region().center(), direction,
          Math.max(2.0D, evidence.region().radius() * 0.65D),
          evidence.region().center().distanceTo(hunterPosition) / 0.28D,
          evidence.confidence(now), evidence.type().name()));
    }
    return List.copyOf(result);
  }

  public static double routeThreatRisk(ValidatedRoute route, List<ThreatCorridor> corridors) {
    if (route == null || !route.reachable() || corridors == null || corridors.isEmpty()) return 0.0D;
    Vec3 destination = route.destination();
    if (route.waypoints().isEmpty() && destination == null) return 1.0D;
    List<Vec3> points = route.waypoints().isEmpty() ? List.of(destination) : route.waypoints();
    double safeProbability = 1.0D;
    for (ThreatCorridor corridor : corridors) {
      if (corridor == null || corridor.origin() == null || corridor.direction() == null) continue;
      Vec3 direction = corridor.direction().multiply(1.0D, 0.0D, 1.0D);
      if (direction.lengthSqr() < 1.0E-6D) continue;
      direction = direction.normalize();
      double corridorRisk = 0.0D;
      for (Vec3 point : points) {
        if (point == null) continue;
        Vec3 horizontalOffset = point.subtract(corridor.origin()).multiply(1.0D, 0.0D, 1.0D);
        double originDistance = horizontalOffset.length();
        double along = horizontalOffset.dot(direction);
        double perpendicular = along <= 0.0D
            ? originDistance
            : horizontalOffset.subtract(direction.scale(along)).length();
        double originRisk = Math.exp(-originDistance / Math.max(4.0D, corridor.width() + 5.0D));
        double rayRisk = along >= 0.0D && along <= 56.0D
            ? Math.exp(-perpendicular / Math.max(2.0D, corridor.width() + 2.0D))
                * (1.0D - along / 80.0D)
            : 0.0D;
        corridorRisk = Math.max(corridorRisk, Math.max(originRisk, rayRisk));
      }
      double sourceWeight = switch (corridor.source()) {
        case "DAMAGE" -> 1.0D;
        case "VISUAL" -> 0.90D;
        case "GUNSHOT" -> 0.76D;
        default -> 0.65D;
      };
      double weighted = clamp(corridorRisk * corridor.probability() * sourceWeight);
      safeProbability *= 1.0D - weighted;
    }
    return clamp(1.0D - safeProbability);
  }

  private static List<TacticalNode> enrichNodes(
      List<TacticalNode> nodes, List<ThreatCorridor> corridors) {
    if (corridors.isEmpty()) return List.copyOf(nodes);
    List<TacticalNode> enriched = new ArrayList<>(nodes.size());
    for (TacticalNode node : nodes) {
      double risk = corridors.stream()
          .mapToDouble(corridor -> corridor.probability()
              / Math.max(1.0D, node.position().distanceTo(corridor.origin()) * 0.10D))
          .sum();
      enriched.add(node.withSpatialRisk(
          node.rearSafety(), clamp(node.flankRisk() + risk * 0.20D),
          clamp(node.entranceControl() + corridors.size() * 0.05D),
          clamp(node.unknownRisk() + risk * 0.12D)));
    }
    return List.copyOf(enriched);
  }

  private static Vec3 mostCredibleThreat(HunterWorldModel.Snapshot world) {
    return world.contacts().stream()
        .filter(contact -> contact.region() != null)
        .max(Comparator.comparingDouble(contact -> contact.confidence()))
        .map(contact -> contact.region().center())
        .orElseGet(() -> world.evidence().stream()
            // Hearing is a sector hint only.  It must not become a route anchor or
            // make every Hunter converge on the same gunshot without visual/damage proof.
            .filter(item -> item.type() != Evidence.Type.GUNSHOT
                && item.type() != Evidence.Type.MOVEMENT_SOUND)
            .max(Comparator.comparingDouble(item -> item.sourceReliability()))
            .map(item -> item.region().center())
            .orElse(null));
  }

  static Vec3 mostRelevantThreat(HunterWorldModel.Snapshot world, long now) {
    return world.evidence().stream()
        .filter(item -> item.type() == Evidence.Type.DAMAGE)
        .filter(item -> now - item.observedAt() <= 20L)
        .filter(item -> item.confidence(now) >= 0.70D)
        .max(Comparator.comparingLong(Evidence::observedAt))
        .map(item -> item.region().center())
        .orElseGet(() -> mostCredibleThreat(world));
  }

  private static double uncertainty(HunterWorldModel.Snapshot world) {
    return world.contacts().stream()
        .filter(contact -> contact.region() != null)
        .mapToDouble(contact -> contact.region().radius())
        .max()
        .orElse(0.0D);
  }

  private static double credibleThreat(HunterWorldModel.Snapshot world, long now) {
    return world.evidence().stream()
        .filter(item -> item.type() != Evidence.Type.OBJECTIVE_CHANGE)
        .mapToDouble(item -> item.confidence(now))
        .max()
        .orElse(0.0D);
  }

  public static boolean hasFreshDirectAttack(HunterWorldModel.Snapshot world, long now) {
    return world.evidence().stream()
        .anyMatch(item -> item.type() == Evidence.Type.DAMAGE
            && now - item.observedAt() <= 20L
            && item.confidence(now) >= 0.70D);
  }

  private static int credibleHostileSources(
      HunterWorldModel.Snapshot world, long now, long window) {
    Set<String> sources = new HashSet<>();
    world.evidence().stream()
        .filter(item -> now - item.observedAt() <= window)
        .filter(item -> item.type() == Evidence.Type.GUNSHOT
            || item.type() == Evidence.Type.DAMAGE
            || item.type() == Evidence.Type.VISUAL)
        .filter(item -> item.confidence(now) >= 0.55D)
        .forEach(item -> sources.add(item.independenceKey()));
    return sources.size();
  }

  private static double deadlineUrgency(HunterInputSnapshot input) {
    long remaining = input.match().matchRemainingTicks();
    if (remaining < 0L) return 0.35D;
    if (remaining <= 1_200L) return 1.0D;
    if (remaining <= 2_400L) return 0.75D;
    return 0.35D;
  }

  private UtilityDecision.Context utilityContext(
      HunterEntity hunter,
      HunterInputSnapshot input,
      HunterWorldModel.Snapshot world,
      HunterPersonality personality,
      boolean visible,
      long now,
      OpportunityDetector.Result opportunities,
      List<DecisionCandidate> candidates) {
    java.util.EnumMap<HunterIntent, Double> scores = new java.util.EnumMap<>(HunterIntent.class);
    scores.putAll(opportunities.scores());
    for (DecisionCandidate candidate : candidates)
      scores.merge(candidate.intent(), candidate.analyticScore(), Math::max);
    Set<HunterIntent> available = candidates.stream()
        .filter(DecisionCandidate::feasible)
        .map(DecisionCandidate::intent)
        .collect(java.util.stream.Collectors.toSet());
    double immediateRisk = world.evidence().stream()
        .filter(item -> item.type() == Evidence.Type.DAMAGE && now - item.observedAt() <= 20L)
        .mapToDouble(item -> item.confidence(now))
        .max()
        .orElse(0.0D);
    double unknownRisk = java.util.Arrays.stream(world.exposure().unknownRisk()).max().orElse(0.0D);
    return new UtilityDecision.Context(
        visible,
        credibleThreat(world, now),
        selfSnapshot(input, world),
        immediateRisk * 0.85D,
        immediateRisk,
        unknownRisk,
        CombatEvaluation.unavailable(),
        personality,
        recent,
        Map.copyOf(scores),
        Set.copyOf(available),
        now);
  }

  private static SelfModel.Snapshot selfSnapshot(
      HunterInputSnapshot input, HunterWorldModel.Snapshot world) {
    return new SelfModel.Snapshot(
        input.self().healthRatio(),
        world.exposure().compromise(),
        0.5D,
        input.self().reloading(),
        false,
        false);
  }

  private static DecisionCandidate withUtilityScore(
      DecisionCandidate candidate,
      UtilityDecision.Decision utility,
      GodDirector.Role directorRole) {
    double utilityScore = utility.selectionScores().getOrDefault(candidate.intent(), 0.0D);
    double bias = roleBias(directorRole, candidate.intent());
    double blended = clamp(
        candidate.analyticScore() * 0.72D
            + clamp(utilityScore) * 0.28D
            + bias);
    String roleSuffix = bias <= 0.0D
        ? ""
        : ":role-" + directorRole.name().toLowerCase(java.util.Locale.ROOT);
    return new DecisionCandidate(
        candidate.id(),
        candidate.goal(),
        candidate.intent(),
        candidate.opportunity(),
        candidate.route(),
        candidate.target(),
        blended,
        candidate.exposure(),
        candidate.objectiveDelay(),
        candidate.escapeQuality(),
        candidate.source() + ":utility" + roleSuffix,
        candidate.infeasibleReason());
  }

  /**
   * A director role is a soft preference, never a command. It differentiates equally feasible
   * plans without allowing the global director to manufacture perception or own an action lane.
   */
  static double roleBias(GodDirector.Role role, HunterIntent intent) {
    if (role == null || intent == null) return 0.0D;
    return switch (role) {
      case OBJECTIVE_RUNNER -> intent == HunterIntent.PUSH ? 0.07D : 0.0D;
      case CONTESTER ->
          intent == HunterIntent.PUSH || intent == HunterIntent.INTERCEPT ? 0.05D : 0.0D;
      case DELAYED_CONTESTER ->
          intent == HunterIntent.AMBUSH || intent == HunterIntent.OVERWATCH ? 0.06D : 0.0D;
      case FLANKER -> intent == HunterIntent.FLANK ? 0.08D : 0.0D;
      case INTERCEPTOR ->
          intent == HunterIntent.INTERCEPT || intent == HunterIntent.FLANK ? 0.07D : 0.0D;
      case AMBUSHER -> intent == HunterIntent.AMBUSH
          ? 0.09D
          : intent == HunterIntent.OVERWATCH ? 0.04D : 0.0D;
      case OBSERVER -> intent == HunterIntent.OVERWATCH
          ? 0.09D
          : intent == HunterIntent.LISTEN ? 0.04D : 0.0D;
      case NEUTRAL -> 0.0D;
    };
  }

  private static double clamp(double value) {
    return Math.max(0.0D, Math.min(1.0D, value));
  }

  private static String decimal(double value) {
    return String.format(java.util.Locale.ROOT, "%.2f", value);
  }

  public record PlanningResult(
      TacticalSpace space,
      List<DecisionCandidate> candidates,
      UtilityDecision.Decision utility,
      String opportunitySummary) {
    public PlanningResult {
      candidates = List.copyOf(candidates);
      opportunitySummary = opportunitySummary == null ? "" : opportunitySummary;
    }
  }
}
