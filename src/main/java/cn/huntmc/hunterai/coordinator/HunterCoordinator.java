package cn.huntmc.hunterai.coordinator;

import cn.huntmc.hunterai.HuntMcHunterAi;
import cn.huntmc.hunterai.action.ActionArbiter;
import cn.huntmc.hunterai.action.ActionFrame;
import cn.huntmc.hunterai.action.ActionChannel;
import cn.huntmc.hunterai.action.ActionRequest;
import cn.huntmc.hunterai.action.ActuatorResult;
import cn.huntmc.hunterai.action.ArbitrationResult;
import cn.huntmc.hunterai.action.EntityActuator;
import cn.huntmc.hunterai.action.InteractionCommand;
import cn.huntmc.hunterai.action.MovementMode;
import cn.huntmc.hunterai.action.PostureCommand;
import cn.huntmc.hunterai.action.WeaponCommand;
import cn.huntmc.hunterai.ai.EnemyBelief;
import cn.huntmc.hunterai.ai.CombatController;
import cn.huntmc.hunterai.ai.ContactAttentionPolicy;
import cn.huntmc.hunterai.ai.HunterIntent;
import cn.huntmc.hunterai.ai.HunterPersonality;
import cn.huntmc.hunterai.ai.PerceptionSystem;
import cn.huntmc.hunterai.ai.TacticalNode;
import cn.huntmc.hunterai.ai.VigilanceSystem;
import cn.huntmc.hunterai.debug.HunterDebugTrace;
import cn.huntmc.hunterai.decision.ContingencyBranch;
import cn.huntmc.hunterai.decision.DecisionCandidate;
import cn.huntmc.hunterai.decision.DeliberationGate;
import cn.huntmc.hunterai.decision.DeliberationMode;
import cn.huntmc.hunterai.decision.FallbackPlan;
import cn.huntmc.hunterai.decision.HunterPlan;
import cn.huntmc.hunterai.decision.InterruptLevel;
import cn.huntmc.hunterai.decision.MonteCarloDeliberator;
import cn.huntmc.hunterai.decision.PlanPhase;
import cn.huntmc.hunterai.decision.PlanCommitPolicy;
import cn.huntmc.hunterai.decision.PlanValidity;
import cn.huntmc.hunterai.decision.PredictionResult;
import cn.huntmc.hunterai.decision.RolloutBatch;
import cn.huntmc.hunterai.decision.RecoveryController;
import cn.huntmc.hunterai.decision.RecoveryMode;
import cn.huntmc.hunterai.decision.SafeResetController;
import cn.huntmc.hunterai.decision.StrategicGoal;
import cn.huntmc.hunterai.decision.TacticalDecisionPlanner;
import cn.huntmc.hunterai.director.GodDirector;
import cn.huntmc.hunterai.entity.HunterEntity;
import cn.huntmc.hunterai.match.MatchDirective;
import cn.huntmc.hunterai.model.Evidence;
import cn.huntmc.hunterai.model.HunterInputSnapshot;
import cn.huntmc.hunterai.model.HunterWorldModel;
import cn.huntmc.hunterai.model.IntelAssessment;
import cn.huntmc.hunterai.model.SelfSnapshot;
import cn.huntmc.hunterai.navigation.ValidatedRoute;
import cn.huntmc.hunterai.combat.HunterWeaponAdapter;
import com.mojang.logging.LogUtils;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/** Single owner for perception, deliberation, planning, arbitration and one final action frame. */
public final class HunterCoordinator {
  private static final long SAFE_RESET_REENTRY_COOLDOWN_TICKS = 200L;
  private static final Logger LOG = LogUtils.getLogger();
  private static final long POST_CONTACT_RESUME_TICKS = 40L;
  private static final long NORMAL_COMMITMENT_TICKS = 80L;
  private static final long PURSUIT_COMMITMENT_TICKS = 120L;
  private static final long MAX_BOUNTY_TACTICAL_DELAY_TICKS = 120L;
  private static final long DAMAGE_CONFIRM_TICKS = 3L;
  private static final long DAMAGE_ATTENTION_TICKS = 12L;
  private static final long DAMAGE_THREAT_MEMORY_TICKS = 140L;
  private static final long CONTACT_SEARCH_MEMORY_TICKS = 140L;
  private static final double STANDARD_INTERACTION_RANGE = 2.75D;
  private static final double BOUNTY_STAGE_ENTER_DISTANCE = 28.0D;
  private static final double BOUNTY_STAGE_EXIT_DISTANCE = 38.0D;
  private static final double LOW_CONFIDENCE_EXPOSURE_LIMIT = 0.50D;
  private static final double FALLBACK_CHECKPOINT_ARRIVAL_DISTANCE_SQUARED = 1.6D * 1.6D;

  private PerceptionSystem perception = new PerceptionSystem();
  private final CombatController combat = new CombatController();
  private VigilanceSystem vigilance = new VigilanceSystem();
  private EnemyBelief legacyBeliefBoundary = new EnemyBelief();
  private HunterWorldModel worldModel = new HunterWorldModel();
  private final DeliberationGate gate = new DeliberationGate();
  private final TacticalDecisionPlanner planner = new TacticalDecisionPlanner();
  private final MonteCarloDeliberator deliberator = new MonteCarloDeliberator();
  private final SafeResetController safeReset = new SafeResetController();
  private final RecoveryController recovery = new RecoveryController();
  private final ActionArbiter arbiter = new ActionArbiter();
  private final EntityActuator actuator = new EntityActuator();
  private final HunterDebugTrace trace = new HunterDebugTrace();
  private final Deque<PendingEvidence> pendingEvidence = new ArrayDeque<>();

  private HunterPersonality personality;
  private HunterPlan plan;
  private HunterPlan suspendedStrategicPlan;
  private StrategicGoal strategicGoal = StrategicGoal.SURVIVE;
  private MatchDirective previousDirective;
  private GodDirector.Directive directorDirective = GodDirector.Directive.neutral(0L);
  private String lastDirectorSignature = "none";
  private IntelAssessment lastIntel = new IntelAssessment(0, 0, 0, 0, 0, 0, 0, DeliberationMode.NONE, "initial");
  private ArbitrationResult lastArbitration;
  private ActuatorResult lastActuator;
  private LivingEntity visibleThisTick;
  private UUID directorDeferredContactId;
  private Vec3 lastVisiblePosition;
  private Vec3 lastMaterialVisualPosition;
  private long lastMaterialVisualAt = Long.MIN_VALUE;
  private long lastVisibleAt = Long.MIN_VALUE;
  private long lastPursuitLogAt = Long.MIN_VALUE;
  private ContactAttentionPolicy.Choice combatAttention = ContactAttentionPolicy.Choice.none();
  private BehaviorLane lastBehaviorLane;
  private long lastDamageAt = Long.MIN_VALUE;
  private Vec3 damageSource;
  private long damageSourceObservedAt = Long.MIN_VALUE;
  private UUID damageAttackerId;
  private float lastDamageAmount;
  private long lastDamageAmountObservedAt = Long.MIN_VALUE;
  private long lastQueuedDamageAt = Long.MIN_VALUE;
  private UUID lastQueuedDamageAttackerId;
  private boolean wasVisible;
  private int newEvidenceCount;
  private boolean directAttackEvidenceUpdated;
  private long decisionSequence;
  private UUID currentDecisionId;
  private Vec3 observationTarget;
  private long nextObservationAt;
  private String lastPlanSignature = "none";
  private int planSwitchesInWindow;
  private long planSwitchWindowStartedAt;
  private Vec3 previousTickPosition;
  private Vec3 lastTraversalPosition;
  private double previousTickHealth = Double.NaN;
  private double previousObjectiveDistance = Double.NaN;
  private long claimWindowStartedAt = Long.MIN_VALUE;
  private int handledRouteFailures;
  private long lastFailureLogAt = Long.MIN_VALUE;
  private boolean contingencyReplanPending;
  private ActuatorResult lastObservedInteractionResult;
  private int objectiveInteractionFailures;
  private Vec3 objectiveRecoveryTarget;
  private long safeResetCooldownUntil = Long.MIN_VALUE;
  private String fallbackRouteOwner;
  private Vec3 fallbackRouteRequestedTarget;
  private Vec3 fallbackRouteExecutionTarget;
  private UUID fallbackRouteId;
  private boolean fallbackRouteNeedsContinuation;
  private Vec3 interactionScanObjective;
  private long interactionScanStartedAt = Long.MIN_VALUE;
  private long interactionCommittedUntil = Long.MIN_VALUE;

  public void tick(HunterEntity hunter) {
    try {
      tickInternal(hunter);
    } catch (RuntimeException exception) {
      handleTickFailure(hunter, exception);
    }
  }

  private void tickInternal(HunterEntity hunter) {
    long startedAt = System.nanoTime();
    long now = hunter.level().getGameTime();
    actuator.prepareTick(hunter, now);
    if (personality == null) personality = HunterPersonality.fromUuid(hunter.getUUID());
    MatchDirective directive = directive(hunter, now);
    HuntMcHunterAi mod = HuntMcHunterAi.instance();
    directorDirective = mod == null
        ? GodDirector.Directive.neutral(now)
        : mod.hunterRegistry().directorDirective(hunter, directive, now);
    String directorSignature = directorDirective.role()
        + ":" + directorDirective.pursueObjective()
        + ":" + directorDirective.immediateContest()
        + ":" + directorDirective.engageHunterContacts();
    if (!directorSignature.equals(lastDirectorSignature)) {
      lastDirectorSignature = directorSignature;
      record(
          hunter,
          now,
          "DIRECTOR",
          "role=" + directorDirective.role()
              + " pursueObjective=" + directorDirective.pursueObjective()
              + " immediateContest=" + directorDirective.immediateContest()
              + " engageHunters=" + directorDirective.engageHunterContacts()
              + " crowding=" + decimal(directorDirective.objectiveCrowding())
              + " validUntil=" + directorDirective.validUntil()
              + " reason=[" + directorDirective.reason() + "]");
    }
    HunterWeaponAdapter.AmmoState ammunition = actuator.ammunition(hunter);
    HunterInputSnapshot input = new HunterInputSnapshot(
        hunter.getUUID(),
        hunter.monitorId(),
        now,
        new SelfSnapshot(
            hunter.position(), hunter.getDeltaMovement(), hunter.getYRot(), hunter.getXRot(),
            hunter.getHealth(), hunter.getMaxHealth(), ammunition.ammunition(), ammunition.capacity(),
            ammunition.reloading(), hunter.onGround(), hunter.isCrouching(), hunter.isSprinting(),
            hunter.isClimbingLadder(), hunter.isInWater()),
        directive);
    boolean teleported = lastTraversalPosition != null
        && lastTraversalPosition.distanceToSqr(input.self().position()) > 64.0D;
    recordOutcome(hunter, input);
    observeInteractionResult(hunter, now);
    vigilance.noteTraversal(lastTraversalPosition, input.self().position(), now);
    lastTraversalPosition = input.self().position();
    record(hunter, now, "INPUT", inputSummary(hunter, input, teleported));

    if (directive.assigned() && !directive.active()) {
      applyInactiveFrame(hunter, input);
      recordPerformance(hunter, now, startedAt, 0L, 0);
      return;
    }

    worldModel.beginTick();
    perception.tick(hunter, legacyBeliefBoundary);
    visibleThisTick = perception.visibleTarget();
    boolean previouslyVisible = wasVisible;
    directAttackEvidenceUpdated = false;
    processPendingEvidence(hunter, now);
    updateVisualEvidence(hunter, now);
    HunterWorldModel.Snapshot world = worldModel.snapshot(now);
    if (directorDefersHunterContact(hunter, visibleThisTick, now)) {
      UUID deferred = visibleThisTick.getUUID();
      if (!deferred.equals(directorDeferredContactId)) {
        directorDeferredContactId = deferred;
        record(
            hunter,
            now,
            "DIRECTOR",
            "role=" + directorDirective.role()
                + " contact=" + deferred
                + " result=observe-without-immediate-duel reason=global-match-pacing");
      }
      perception.deferVisibleTarget(deferred);
      visibleThisTick = null;
    } else {
      directorDeferredContactId = null;
    }
    updateCombatAttention(hunter, world, now);
    recordPerception(hunter, now);
    if (now % 20L == 0L || newEvidenceCount > 0) recordWorldModel(hunter, now, world);

    StrategicGoal nextStrategicGoal = chooseStrategicGoal(input, strategicGoal, directorDirective);
    boolean objectiveChanged = objectiveChanged(previousDirective, directive) || nextStrategicGoal != strategicGoal;
    if (objectiveChanged) {
      record(hunter, now, "EVIDENCE", "type=OBJECTIVE_CHANGE previous=" + strategicGoal + " next=" + nextStrategicGoal);
      newEvidenceCount++;
      objectiveInteractionFailures = 0;
      objectiveRecoveryTarget = null;
    }
    if (nextStrategicGoal == StrategicGoal.CLAIM_BOUNTY && strategicGoal != StrategicGoal.CLAIM_BOUNTY)
      claimWindowStartedAt = now;
    else if (nextStrategicGoal != StrategicGoal.CLAIM_BOUNTY) claimWindowStartedAt = Long.MIN_VALUE;
    strategicGoal = nextStrategicGoal;
    previousDirective = directive;

    if (!occurredWithin(damageSourceObservedAt, now, DAMAGE_THREAT_MEMORY_TICKS)) {
      damageSource = null;
      damageAttackerId = null;
    }

    InterruptLevel interrupt = interruptLevel(input, world, now);
    boolean newVisibleContact = visibleThisTick != null && !previouslyVisible;
    boolean planInvalid = plan == null || plan.validity() == PlanValidity.INVALID || planGoalInvalid(plan, strategicGoal);
    if (!planInvalid
        && newEvidenceCount > 0
        && strategicGoal == StrategicGoal.EXTRACT
        && plan.opportunity() != null
        && plan.opportunity().approachRoute() != null) {
      double routeThreatRisk = TacticalDecisionPlanner.routeThreatRisk(
          plan.opportunity().approachRoute(),
          TacticalDecisionPlanner.threatCorridors(hunter.position(), world, now));
      if (routeThreatRisk >= 0.62D) {
        plan = plan.invalidated("new-threat-on-extraction-route");
        planInvalid = true;
        record(
            hunter,
            now,
            "PLAN",
            "result=invalidated reason=new-threat-on-extraction-route risk="
                + decimal(routeThreatRisk));
      }
    }
    int routeFailures = actuator.routeFailures();
    if (routeFailures == 0) handledRouteFailures = 0;
    if (routeFailures > handledRouteFailures) {
      planInvalid = true;
      Vec3 failedTarget = lastActuator == null ? null : lastActuator.failureTarget();
      recovery.startPathRecovery(hunter, failedTarget, now, "route-failure-" + routeFailures);
      handledRouteFailures = routeFailures;
    }
    if (planSwitchOscillating(now)) startSafeReset(hunter, input, world, now, "plan-oscillation");
    boolean directDamageInterrupt = occurredWithin(lastDamageAt, now, DAMAGE_ATTENTION_TICKS)
        && currentDamageThreat(now) != null;
    if (directDamageInterrupt || visibleThisTick != null) resetObjectiveInteractionScan();
    if (safeReset.active(now)
        && (directDamageInterrupt
            || visibleThisTick != null
                && !"emergency-damage-or-resource-state".equals(safeReset.reason()))) {
      record(
          hunter,
          now,
          "FALLBACK",
          "level=SAFE_RESET result=interrupted-by="
              + (directDamageInterrupt ? "direct-damage" : "confirmed-contact"));
      finishSafeReset(now);
      actuator.clearRouteFailures();
      plan = null;
      planInvalid = true;
    }
    if (interrupt == InterruptLevel.L3_EMERGENCY && !safeReset.active(now))
      recovery.startTacticalDisengage(
          hunter,
          visibleThisTick == null ? currentDamageThreat(now) : visibleThisTick.position(),
          now,
          "emergency-damage-or-resource-state");
    else if (directDamageInterrupt
        && recovery.active()
        && recovery.mode() == RecoveryMode.PATH_RECOVERY)
      recovery.startTacticalDisengage(
          hunter,
          currentDamageThreat(now),
          now,
          "direct-damage-preempts-path-recovery");

    if (safeReset.active(now)) {
      executeSafeReset(hunter, input, world, now);
      if (safeReset.readyToReplan()) {
        finishSafeReset(now);
        actuator.clearRouteFailures();
        plan = null;
        suspendedStrategicPlan = null;
      }
      recordPerformance(hunter, now, startedAt, 0L, 0);
      return;
    }

    if (recovery.active() && recovery.mode() == RecoveryMode.PATH_RECOVERY && visibleThisTick != null) {
      record(hunter, now, "FALLBACK", "level=PATH_RECOVERY result=interrupted-by-confirmed-contact");
      recovery.finish();
      planInvalid = true;
    }
    if (recovery.active()) {
      recovery.observe(hunter, lastActuator, now);
      if (recovery.shouldEscalate(now)) {
        String recoverySummary = recovery.summary(now);
        recovery.finish();
        clearFallbackRoute();
        if (startSafeReset(hunter, input, world, now, "recovery-exhausted:" + recoverySummary)) {
          executeSafeReset(hunter, input, world, now);
          recordPerformance(hunter, now, startedAt, 0L, 0);
          return;
        }
        actuator.clearRouteFailures();
        plan = null;
        planInvalid = true;
      }
      if (recovery.active() && recovery.readyToReplan(now)) {
        record(hunter, now, "FALLBACK", "level=" + recovery.mode() + " result=progress-restored");
        recovery.finish();
        clearFallbackRoute();
        actuator.clearRouteFailures();
        plan = null;
        planInvalid = true;
      } else if (recovery.active()) {
        executeRecovery(hunter, input, world, now);
        recordPerformance(hunter, now, startedAt, 0L, 0);
        return;
      }
    }

    String contingency = null;
    if (newVisibleContact) contingency = "visible-enemy";
    else if (!planInvalid && routeFailures > handledRouteFailures) contingency = "route-failure";
    if (contingency != null && activateContingency(hunter, contingency, now)) {
      handledRouteFailures = routeFailures;
      executePlan(hunter, input, world, now);
      recordPerformance(hunter, now, startedAt, 0L, 0);
      return;
    }

    boolean resumeAfterCombat = plan != null
        && plan.reason().contains("visible-")
        && visibleThisTick == null
        && now - lastVisibleAt >= POST_CONTACT_RESUME_TICKS;
    if (resumeAfterCombat && resumableStrategicPlan(now)) {
      plan = suspendedStrategicPlan.withPhase(PlanPhase.APPROACH, "resume-after-combat");
      suspendedStrategicPlan = null;
      planInvalid = false;
      record(hunter, now, "PLAN", "planId=" + plan.id() + " goal=" + plan.goal()
          + " intent=" + plan.intent() + " phase=" + plan.phase() + " reason=resume-after-combat");
    } else if (resumeAfterCombat) {
      suspendedStrategicPlan = null;
      planInvalid = true;
    }

    boolean eventRequiresAssessment =
        planInvalid || objectiveChanged || newEvidenceCount > 0 || newVisibleContact || contingencyReplanPending;
    long rolloutMicros = 0L;
    int rolloutSamples = 0;
    if (eventRequiresAssessment) {
      currentDecisionId = decisionId(hunter.getUUID(), input.match().matchId(), ++decisionSequence, now);
      int estimatedChoices = estimateMeaningfulChoices(world, visibleThisTick, strategicGoal);
      boolean freshDirectAttack = TacticalDecisionPlanner.hasFreshDirectAttack(world, now);
      lastIntel = gate.assess(
          world, now, estimatedChoices, planInvalid, objectiveChanged,
          newVisibleContact || directAttackEvidenceUpdated,
          interrupt == InterruptLevel.L3_EMERGENCY,
          freshDirectAttack ? 1.0D : planRelevance(world, strategicGoal));
      recordIntel(hunter, now, lastIntel);
      TacticalDecisionPlanner.PlanningResult planning = null;
      if (planInvalid
          || objectiveChanged
          || lastIntel.mode() != DeliberationMode.NONE
          || newVisibleContact
          || contingencyReplanPending) {
        planning = planner.candidates(
            hunter,
            input,
            world,
            personality,
            strategicGoal,
            directorDirective.role(),
            visibleThisTick,
            actuator,
            now);
        record(
            hunter,
            now,
            "OPPORTUNITY",
            "summary=[" + planning.opportunitySummary() + "]");
        record(
            hunter,
            now,
            "UTILITY",
            "selected=" + planning.utility().intent()
                + " scores=[" + planning.utility().scoreSummary() + "]"
                + " selection=[" + planning.utility().selectionSummary() + "]"
                + " reason=" + planning.utility().reason());
        recordCandidates(hunter, now, planning.candidates());
        Selection selection =
            selectCandidate(hunter, input, world, planning.candidates(), currentDecisionId, now);
        rolloutMicros = selection.rolloutMicros();
        rolloutSamples = selection.rolloutSamples();
        if (selection.candidate() != null
            && shouldCommit(
                selection,
                plan,
                planInvalid,
                objectiveChanged,
                newVisibleContact || directAttackEvidenceUpdated || contingencyReplanPending)) {
          commitPlan(hunter, selection.candidate(), selection.score(), now, selection.reason());
        } else if (plan == null || planInvalid || contingencyReplanPending) {
          recovery.startPathRecovery(
              hunter,
              strategicRecoveryTarget(hunter, input, strategicGoal),
              now,
              "no-acceptable-plan");
        }
      }
      newEvidenceCount = 0;
      contingencyReplanPending = false;
    } else {
      record(hunter, now, "SIMULATION_GATE", "mode=NONE reason=plan-valid-no-material-event");
    }

    if (safeReset.active(now)) executeSafeReset(hunter, input, world, now);
    else if (recovery.active()) executeRecovery(hunter, input, world, now);
    else executePlan(hunter, input, world, now);
    recordPerformance(hunter, now, startedAt, rolloutMicros, rolloutSamples);
  }

  private void handleTickFailure(HunterEntity hunter, RuntimeException exception) {
    long now = hunter.level().getGameTime();
    boolean report = lastFailureLogAt == Long.MIN_VALUE || now - lastFailureLogAt >= 20L;
    if (report) {
      lastFailureLogAt = now;
      LOG.error("Hunter coordinator tick failed for {}. Applying a bounded hold frame.", hunter.getUUID(), exception);
    }
    if (plan != null) plan = plan.invalidated("coordinator-error");
    contingencyReplanPending = false;
    if (report) {
      try {
        record(
            hunter,
            now,
            "LIFECYCLE",
            "phase=coordinator-error result=bounded-hold error=" + exception.getClass().getSimpleName());
      } catch (RuntimeException telemetryException) {
        LOG.error("Unable to record Hunter coordinator failure for {}.", hunter.getUUID(), telemetryException);
      }
    }
    try {
      List<ActionRequest> requests = List.of(
          ActionRequest.movement("coordinator-failure", 100, null, 0.0D, MovementMode.HOLD, "bounded-hold"),
          ActionRequest.posture("coordinator-failure", 100, PostureCommand.STAND, "bounded-hold"),
          ActionRequest.weapon("coordinator-failure", 100, WeaponCommand.LOWER, "bounded-hold"));
      lastArbitration = arbiter.arbitrate(now, requests);
      lastActuator = actuator.apply(hunter, lastArbitration.frame());
    } catch (RuntimeException actuatorException) {
      LOG.error("Hunter coordinator failure frame could not be applied for {}.", hunter.getUUID(), actuatorException);
    }
  }

  private boolean activateContingency(HunterEntity hunter, String condition, long now) {
    if (plan == null
        || plan.goal() == StrategicGoal.SAFE_RESET
        || plan.validity() != PlanValidity.VALID
        || plan.reason().equals("contingency:" + condition)) return false;
    ContingencyBranch branch = plan.contingencies().stream()
        .filter(candidate -> candidate.condition().equals(condition))
        .findFirst()
        .orElse(null);
    if (branch == null) return false;
    suspendedStrategicPlan = plan;
    plan = plan.activate(branch, "contingency:" + condition);
    contingencyReplanPending = true;
    record(
        hunter,
        now,
        "PLAN",
        "planId=" + plan.id() + " goal=" + plan.goal() + " intent=" + plan.intent()
            + " phase=" + plan.phase() + " contingency=" + condition
            + " result=activated reason=" + plan.reason());
    return true;
  }

  public void hear(HunterEntity hunter, UUID emitterId, Vec3 source, float strength, long gameTime) {
    if (source == null) return;
    String sourceKey = "sound:" + (emitterId == null ? quantized(source) : emitterId);
    pendingEvidence.addLast(
        new PendingEvidence(Evidence.Type.GUNSHOT, null, source, strength, gameTime, sourceKey));
  }

  public void onDamaged(
      HunterEntity hunter,
      String damageType,
      UUID attackerId,
      Vec3 source,
      float amount,
      long gameTime) {
    lastDamageAt = gameTime;
    lastDamageAmount = gameTime == lastDamageAmountObservedAt
        ? Math.max(lastDamageAmount, amount)
        : amount;
    lastDamageAmountObservedAt = gameTime;
    perception.beginDirectThreatConfirmation(attackerId, gameTime);
    if (source != null) perception.beginFacingSweep(yawTo(hunter.position(), source), gameTime);
    if (source != null) {
      damageSource = source;
      damageSourceObservedAt = gameTime;
      damageAttackerId = attackerId;
    } else {
      damageSource = null;
      damageSourceObservedAt = Long.MIN_VALUE;
      damageAttackerId = null;
    }
    if (gameTime == lastQueuedDamageAt
        && java.util.Objects.equals(attackerId, lastQueuedDamageAttackerId)) return;
    lastQueuedDamageAt = gameTime;
    lastQueuedDamageAttackerId = attackerId;
    String sourceKey = attackerId == null
        ? "damage:" + (damageType == null ? "unknown" : damageType)
        : "damage:" + attackerId;
    pendingEvidence.addLast(
        new PendingEvidence(
            Evidence.Type.DAMAGE,
            attackerId,
            source,
            Math.max(0.1F, amount),
            gameTime,
            sourceKey));
  }

  public void onConfirmedKill(HunterEntity hunter, UUID victimId, Vec3 position, long gameTime) {
    if (victimId == null) return;
    if (victimId.equals(damageAttackerId)) {
      damageSource = null;
      damageSourceObservedAt = Long.MIN_VALUE;
      damageAttackerId = null;
    }
    combat.confirmTargetDeath(victimId, gameTime);
    // Death is a material tactical event: invalidate the old attention plan so the next decision
    // can choose a guarded corpse angle or a newly visible second threat.
    newEvidenceCount++;
    contingencyReplanPending = true;
    Evidence death = worldModel.confirmDeath(hunter.getUUID(), victimId, position, gameTime);
    if (death != null)
      record(
          hunter,
          gameTime,
          "OUTCOME",
          "result=confirmed-kill target=" + victimId + " position=" + position);
  }

  /**
   * Clears control state for a target whose entity lifecycle ended. The registry calls this only
   * when this coordinator already references the target, so it cannot reveal an unknown death.
   */
  public void onTargetLifecycleInvalidated(
      HunterEntity hunter, UUID targetId, long gameTime, String lifecycleReason) {
    if (targetId == null || !referencesTarget(targetId)) return;
    String reason = lifecycleReason == null || lifecycleReason.isBlank()
        ? "entity-invalid"
        : lifecycleReason;
    boolean controlledTarget = targetId.equals(combat.targetId())
        || targetId.equals(combatAttention.targetId())
        || targetId.equals(directorDeferredContactId)
        || targetId.equals(legacyBeliefBoundary.lastSeenTarget());
    combat.confirmTargetDeath(targetId, gameTime);
    perception.invalidateTarget(targetId);
    legacyBeliefBoundary.forgetTarget(targetId, gameTime);
    worldModel.invalidateContact(targetId);
    if (targetId.equals(combatAttention.targetId()))
      combatAttention = ContactAttentionPolicy.Choice.none();
    if (targetId.equals(directorDeferredContactId)) directorDeferredContactId = null;
    if (visibleThisTick != null && targetId.equals(visibleThisTick.getUUID())) visibleThisTick = null;
    if (targetId.equals(damageAttackerId)) {
      damageSource = null;
      damageSourceObservedAt = Long.MIN_VALUE;
      damageAttackerId = null;
    }
    if (controlledTarget && plan != null && isThreatResponsePlan(plan))
      plan = plan.invalidated("target-lifecycle-" + reason);
    contingencyReplanPending |= controlledTarget;
    record(
        hunter,
        gameTime,
        "LIFECYCLE",
        "phase=target-invalidated target=" + targetId + " reason=" + reason
            + " planInvalidated=" + (plan != null && plan.validity() == PlanValidity.INVALID));
  }

  public boolean referencesTarget(UUID targetId) {
    if (targetId == null) return false;
    return targetId.equals(combat.targetId())
        || targetId.equals(combatAttention.targetId())
        || targetId.equals(directorDeferredContactId)
        || targetId.equals(legacyBeliefBoundary.lastSeenTarget())
        || worldModel.referencesContact(targetId)
        || visibleThisTick != null && targetId.equals(visibleThisTick.getUUID());
  }

  public HunterIntent intent() {
    return plan == null ? HunterIntent.LISTEN : plan.intent();
  }

  public StrategicGoal strategicGoal() {
    return strategicGoal;
  }

  public List<String> inspectLines(HunterEntity hunter) {
    long now = hunter.level().getGameTime();
    return List.of(
        "Hunter #" + hunter.monitorId() + " goal=" + strategicGoal + " intent=" + intent(),
        "plan=" + (plan == null ? "none" : plan.id() + " phase=" + plan.phase() + " reason=" + plan.reason()),
        "intel=" + decimal(lastIntel.quality()) + " mode=" + lastIntel.mode() + " reason=" + lastIntel.reason(),
        "perception=" + perception.lastSnapshot().contactState() + " visible=" + perception.lastSnapshot().visibleTarget(),
        "actuator=" + (lastActuator == null ? "none" : lastActuator.movementResult() + "/" + lastActuator.weaponResult()),
        "combat=" + combat.summary(now),
        "recovery=" + recovery.summary(now),
        "fallback=" + (safeReset.active(now) ? safeReset.snapshot().phase() + ":" + safeReset.reason() : "inactive"),
        "weapon=" + actuator.weaponStatus(hunter));
  }

  public List<String> recentTrace(int lines) {
    return trace.latest(lines);
  }

  public void clearTrace() {
    trace.clear();
  }

  /** Clears every fact and action state that is scoped to one match-seat assignment. */
  public void resetAssignment() {
    perception = new PerceptionSystem();
    legacyBeliefBoundary = new EnemyBelief();
    worldModel = new HunterWorldModel();
    vigilance = new VigilanceSystem();
    planner.reset();
    pendingEvidence.clear();
    combat.reset();
    recovery.finish();
    safeReset.finish();
    safeResetCooldownUntil = Long.MIN_VALUE;
    clearFallbackRoute();
    actuator.clearNavigationState();
    plan = null;
    suspendedStrategicPlan = null;
    strategicGoal = StrategicGoal.SURVIVE;
    previousDirective = null;
    directorDirective = GodDirector.Directive.neutral(0L);
    lastDirectorSignature = "none";
    visibleThisTick = null;
    directorDeferredContactId = null;
    lastVisiblePosition = null;
    lastMaterialVisualPosition = null;
    lastMaterialVisualAt = Long.MIN_VALUE;
    lastVisibleAt = Long.MIN_VALUE;
    lastPursuitLogAt = Long.MIN_VALUE;
    combatAttention = ContactAttentionPolicy.Choice.none();
    lastBehaviorLane = null;
    lastDamageAt = Long.MIN_VALUE;
    damageSource = null;
    damageSourceObservedAt = Long.MIN_VALUE;
    damageAttackerId = null;
    lastDamageAmount = 0.0F;
    lastDamageAmountObservedAt = Long.MIN_VALUE;
    lastQueuedDamageAt = Long.MIN_VALUE;
    lastQueuedDamageAttackerId = null;
    wasVisible = false;
    newEvidenceCount = 0;
    directAttackEvidenceUpdated = false;
    currentDecisionId = null;
    lastArbitration = null;
    lastActuator = null;
    observationTarget = null;
    nextObservationAt = 0L;
    lastPlanSignature = "none";
    planSwitchesInWindow = 0;
    planSwitchWindowStartedAt = 0L;
    previousTickPosition = null;
    previousTickHealth = Double.NaN;
    previousObjectiveDistance = Double.NaN;
    lastTraversalPosition = null;
    claimWindowStartedAt = Long.MIN_VALUE;
    handledRouteFailures = 0;
    contingencyReplanPending = false;
    lastObservedInteractionResult = null;
    objectiveInteractionFailures = 0;
    objectiveRecoveryTarget = null;
    resetObjectiveInteractionScan();
  }

  private MatchDirective directive(HunterEntity hunter, long now) {
    HuntMcHunterAi mod = HuntMcHunterAi.instance();
    return mod == null ? MatchDirective.standalone(now) : mod.hunterMatchService().directiveFor(hunter);
  }

  private void applyInactiveFrame(HunterEntity hunter, HunterInputSnapshot input) {
    long now = input.gameTime();
    List<ActionRequest> proposals = List.of(
        ActionRequest.movement("lifecycle", 100, null, 0.0D, MovementMode.HOLD, "match-inactive"),
        ActionRequest.posture("lifecycle", 100, PostureCommand.STAND, "match-inactive"),
        ActionRequest.weapon("lifecycle", 100, WeaponCommand.LOWER, "match-inactive"));
    apply(hunter, now, proposals);
  }

  private void processPendingEvidence(HunterEntity hunter, long now) {
    while (!pendingEvidence.isEmpty()) {
      PendingEvidence pending = pendingEvidence.removeFirst();
      Evidence evidence;
      if (pending.type() == Evidence.Type.DAMAGE) {
        evidence = worldModel.damaged(
            hunter.getUUID(), pending.subjectId(), pending.source(), pending.time(), pending.sourceKey());
        if (pending.subjectId() != null) directAttackEvidenceUpdated = true;
      } else {
        evidence = worldModel.hear(
            hunter.getUUID(), pending.source(), pending.strength(), pending.time(), pending.sourceKey());
      }
      newEvidenceCount++;
      record(hunter, now, "EVIDENCE", evidenceSummary(evidence, now));
    }
  }

  private void updateVisualEvidence(HunterEntity hunter, long now) {
    if (visibleThisTick != null) {
      Evidence evidence = worldModel.observeVisual(hunter.getUUID(), visibleThisTick, now);
      Vec3 observedPosition = visibleThisTick.position();
      boolean material = !wasVisible
          || lastMaterialVisualPosition == null
          || lastMaterialVisualPosition.distanceToSqr(observedPosition) >= 4.0D
          || now - lastMaterialVisualAt >= 20L;
      lastVisiblePosition = observedPosition;
      lastVisibleAt = now;
      if (material) {
        newEvidenceCount++;
        lastMaterialVisualPosition = observedPosition;
        lastMaterialVisualAt = now;
      }
      record(hunter, now, "EVIDENCE", evidenceSummary(evidence, now));
      wasVisible = true;
      return;
    }
    if (wasVisible) {
      Evidence lost = worldModel.loseVisual(hunter.getUUID(), lastVisiblePosition, now);
      if (lost != null) {
        newEvidenceCount++;
        record(hunter, now, "EVIDENCE", evidenceSummary(lost, now));
      }
    }
    wasVisible = false;
  }

  private void updateCombatAttention(
      HunterEntity hunter, HunterWorldModel.Snapshot world, long now) {
    UUID visibleId = visibleThisTick == null ? null : visibleThisTick.getUUID();
    UUID recentAttacker = occurredWithin(
            damageSourceObservedAt, now, DAMAGE_THREAT_MEMORY_TICKS)
        ? damageAttackerId
        : null;
    List<cn.huntmc.hunterai.model.ContactSet.Contact> eligibleContacts =
        directorDeferredContactId == null
            ? world.contacts()
            : world.contacts().stream()
                .filter(contact -> !directorDeferredContactId.equals(contact.id()))
                .toList();
    ContactAttentionPolicy.Choice next = ContactAttentionPolicy.select(
        eligibleContacts,
        combatAttention.targetId(),
        visibleId,
        recentAttacker,
        hunter.position(),
        now);
    if (!java.util.Objects.equals(combatAttention.targetId(), next.targetId())) {
      record(
          hunter,
          now,
          "ATTENTION",
          "previous=" + combatAttention.targetId()
              + " next=" + next.targetId()
              + " estimate=" + vector(next.estimatedPosition())
              + " confidence=" + decimal(next.confidence())
              + " score=" + decimal(next.score())
              + " reason=" + next.reason());
    }
    combatAttention = next;
  }

  static StrategicGoal chooseStrategicGoal(
      HunterInputSnapshot input, StrategicGoal currentGoal) {
    return chooseStrategicGoal(input, currentGoal, GodDirector.Directive.neutral(input.gameTime()));
  }

  static StrategicGoal chooseStrategicGoal(
      HunterInputSnapshot input,
      StrategicGoal currentGoal,
      GodDirector.Directive director) {
    MatchDirective match = input.match();
    if (!match.assigned()) return StrategicGoal.SURVIVE;
    if (!match.active()) return StrategicGoal.SURVIVE;
    Vec3 revive = match.reviveTargets().stream()
        .min(java.util.Comparator.comparingDouble(target -> target.position().distanceToSqr(input.self().position())))
        .map(cn.huntmc.hunterai.match.ReviveTarget::position)
        .orElse(null);
    if (revive != null
        && input.self().healthRatio() >= 0.50D
        && input.self().position().distanceTo(revive) <= (match.carryingBounty() ? 6.0D : 40.0D))
      return StrategicGoal.REVIVE_TEAMMATE;
    if (match.carryingBounty()) return StrategicGoal.EXTRACT;
    Vec3 objective = match.publicObjective();
    if (objective == null) return StrategicGoal.SURVIVE;
    double distance = input.self().position().distanceTo(objective);
    boolean pursueObjective = director == null || director.pursueObjective();
    if (match.bossNeedsBanish()) {
      if (!pursueObjective) return StrategicGoal.INTERCEPT_APPROACH;
      return distance <= effectiveBossInteractionRange(match)
          ? StrategicGoal.START_BANISH
          : StrategicGoal.TRANSIT_TO_BOSS;
    }
    if (match.banishing()) {
      if (!match.ownTeamBanishing()) {
        boolean finalContestWindow = match.bountyRemainingTicks() >= 0L
            && match.bountyRemainingTicks() <= 600L;
        boolean delayedContester = director != null
            && director.role() == GodDirector.Role.DELAYED_CONTESTER;
        if (director != null
            && !director.immediateContest()
            && !(delayedContester && finalContestWindow))
          return StrategicGoal.INTERCEPT_APPROACH;
        return StrategicGoal.CONTEST_BANISH;
      }
      boolean retainStagedPosition = currentGoal == StrategicGoal.STAGE_BOUNTY
          && distance <= BOUNTY_STAGE_EXIT_DISTANCE;
      return retainStagedPosition || distance <= BOUNTY_STAGE_ENTER_DISTANCE
          ? StrategicGoal.STAGE_BOUNTY
          : StrategicGoal.TRANSIT_TO_STAGE;
    }
    if (match.bountyAvailable())
      return pursueObjective || ownsBanish(match)
          ? StrategicGoal.CLAIM_BOUNTY
          : StrategicGoal.INTERCEPT_APPROACH;
    if ("CARRIED".equals(match.bountyState()))
      return pursueObjective
          ? StrategicGoal.HUNT_SIGNAL
          : StrategicGoal.INTERCEPT_APPROACH;
    if ("EXTRACTED".equals(match.bountyState())) return StrategicGoal.SURVIVE;
    return pursueObjective ? StrategicGoal.TRANSIT_TO_BOSS : StrategicGoal.INTERCEPT_APPROACH;
  }

  private static boolean ownsBanish(MatchDirective match) {
    return match != null
        && !match.teamId().isBlank()
        && match.teamId().equals(match.banishingTeamId());
  }

  private static Vec3 strategicRecoveryTarget(
      HunterEntity hunter, HunterInputSnapshot input, StrategicGoal goal) {
    if (input.match().carryingBounty() && input.match().extraction() != null)
      return input.match().extraction();
    if (goal == StrategicGoal.INTERCEPT_APPROACH)
      return TacticalDecisionPlanner.interceptionPoint(
          input.match().publicObjective(), hunter.getY(), hunter.getUUID());
    return input.match().publicObjective();
  }

  private boolean directorDefersHunterContact(
      HunterEntity hunter, LivingEntity contact, long now) {
    if (!(contact instanceof HunterEntity other)
        || directorDirective == null
        || directorDirective.engageHunterContacts()
        || hunter.position().distanceToSqr(other.position()) <= 10.0D * 10.0D) return false;
    return !(occurredWithin(lastDamageAt, now, 20L)
        && damageAttackerId != null
        && damageAttackerId.equals(other.getUUID()));
  }

  private InterruptLevel interruptLevel(
      HunterInputSnapshot input, HunterWorldModel.Snapshot world, long now) {
    if (input.match().assigned() && !input.match().active()) return InterruptLevel.L4_LIFECYCLE;
    boolean recentDamage = occurredWithin(lastDamageAt, now, 8L);
    boolean attackerVisible = visibleThisTick != null
        && (damageAttackerId == null || damageAttackerId.equals(visibleThisTick.getUUID()));
    double damageRatio = lastDamageAmount / Math.max(1.0D, input.self().maxHealth());
    if (input.self().healthRatio() < 0.22D
        || input.self().reloading() && visibleThisTick != null
            && input.self().position().distanceTo(visibleThisTick.position()) <= 10.0D
        || recentDamage && damageRequiresEmergency(
            input.self().healthRatio(), input.self().reloading(), input.self().ammunition(),
            attackerVisible, damageRatio))
      return InterruptLevel.L3_EMERGENCY;
    if (recentDamage || visibleThisTick != null || world.encounter().multiPartyFight())
      return InterruptLevel.L2_TACTICAL;
    if (newEvidenceCount > 0) return InterruptLevel.L1_ATTENTION;
    return InterruptLevel.L0_INFORMATION;
  }

  static boolean damageRequiresEmergency(
      double healthRatio,
      boolean reloading,
      int ammunition,
      boolean attackerVisible,
      double damageRatio) {
    if (healthRatio < 0.32D) return true;
    if (attackerVisible && healthRatio < 0.55D && (reloading || ammunition <= 0)) return true;
    return damageRatio >= 0.35D && healthRatio < 0.50D;
  }

  private Selection selectCandidate(
      HunterEntity hunter,
      HunterInputSnapshot input,
      HunterWorldModel.Snapshot world,
      List<DecisionCandidate> candidates,
      UUID decisionId,
      long now) {
    List<DecisionCandidate> feasible = candidates.stream().filter(DecisionCandidate::feasible).toList();
    if (directAttackEvidenceUpdated) {
      List<DecisionCandidate> directResponses = feasible.stream()
          .filter(candidate -> candidate.source().contains("direct-attack"))
          .toList();
      if (!directResponses.isEmpty()) feasible = directResponses;
    }
    if (feasible.isEmpty()) return Selection.none("all-candidates-infeasible");
    if (lastIntel.mode() != DeliberationMode.ROLLOUT) {
      DecisionCandidate best = feasible.stream()
          .max(Comparator.comparingDouble(DecisionCandidate::analyticScore))
          .orElse(null);
      return best == null
          ? Selection.none("analytic-no-candidate")
          : new Selection(best, best.analyticScore(), "analytic:" + lastIntel.reason(), 0L, 0, 1.0D);
    }

    int budget = HuntMcHunterAi.instance() == null
        ? 0
        : HuntMcHunterAi.instance().hunterRegistry().reserveDeliberationMicros();
    if (budget <= 0) {
      DecisionCandidate best = feasible.stream()
          .filter(candidate -> candidate.exposure() <= LOW_CONFIDENCE_EXPOSURE_LIMIT)
          .max(Comparator.comparingDouble(DecisionCandidate::analyticScore))
          .orElse(null);
      record(hunter, now, "FALLBACK", "level=ANALYTIC reason=global-rollout-budget-exhausted");
      return best == null
          ? Selection.none("budget-no-safe-analytic-candidate")
          : new Selection(best, best.analyticScore(), "budget-analytic", 0L, 0, 0.0D);
    }
    RolloutBatch batch = deliberator.evaluate(input, world, personality, decisionId, feasible, budget);
    Map<UUID, DecisionCandidate> byId = new HashMap<>();
    feasible.forEach(candidate -> byId.put(candidate.id(), candidate));
    for (PredictionResult prediction : batch.predictions())
      recordPrediction(hunter, now, decisionId, prediction);
    PredictionResult bestPrediction = batch.predictions().stream()
        .filter(prediction -> prediction.confidence() >= 0.45D
            || byId.get(prediction.candidateId()).exposure() <= LOW_CONFIDENCE_EXPOSURE_LIMIT)
        .max(Comparator.comparingDouble(PredictionResult::score))
        .orElse(null);
    if (batch.budgetExhausted())
      record(hunter, now, "FALLBACK", "level=PARTIAL_ROLLOUT reason=hunter-budget-exhausted samples=" + batch.samplesCompleted());
    return bestPrediction == null
        ? Selection.none("rollout-no-safe-candidate")
        : new Selection(
            byId.get(bestPrediction.candidateId()), bestPrediction.score(), "rollout",
            batch.elapsedMicros(), batch.samplesCompleted(), bestPrediction.confidence());
  }

  private boolean shouldCommit(
      Selection selection,
      HunterPlan current,
      boolean invalid,
      boolean objectiveChanged,
      boolean tacticalOverride) {
    if (selection.candidate() == null) return false;
    if (current == null) return true;
    return PlanCommitPolicy.shouldReplace(
        current.score(), selection.score(), invalid || objectiveChanged || tacticalOverride);
  }

  private void commitPlan(
      HunterEntity hunter, DecisionCandidate candidate, double score, long now, String reason) {
    if (plan != null && visibleThisTick != null && !plan.reason().contains("visible-")) suspendedStrategicPlan = plan;
    long commitment = switch (candidate.intent()) {
      case PUSH, SEARCH, INTERCEPT -> PURSUIT_COMMITMENT_TICKS;
      case AMBUSH, OVERWATCH, LISTEN -> 100L;
      default -> NORMAL_COMMITMENT_TICKS;
    };
    long deadline = inputDeadline(now);
    UUID planId = UUID.nameUUIDFromBytes(
        (hunter.getUUID() + ":plan:" + (++decisionSequence) + ":" + now).getBytes(StandardCharsets.UTF_8));
    plan = new HunterPlan(
        planId,
        candidate.goal(),
        candidate.intent(),
        candidate.opportunity(),
        PlanPhase.APPROACH,
        contingencies(),
        now + commitment,
        deadline,
        PlanValidity.VALID,
        FallbackPlan.inactive(),
        score,
        reason + ":" + candidate.source());
    planner.remember(candidate, now);
    notePlanSwitch(plan, now);
    record(
        hunter, now, "PLAN",
        "planId=" + plan.id() + " goal=" + plan.goal() + " intent=" + plan.intent()
            + " candidateId=" + candidate.id()
            + " phase=" + plan.phase() + " committedUntil=" + plan.committedUntil()
            + " deadline=" + plan.deadline() + " score=" + decimal(score)
            + " routeId=" + (candidate.route() == null ? "none" : candidate.route().id())
            + " target=" + vector(candidate.target())
            + " spatialChain=" + opportunitySummary(candidate.opportunity())
            + " contingencies=" + plan.contingencies()
            + " reason=" + plan.reason());
  }

  private void executePlan(
      HunterEntity hunter, HunterInputSnapshot input, HunterWorldModel.Snapshot world, long now) {
    if (plan == null) {
      recovery.startPathRecovery(
          hunter,
          strategicRecoveryTarget(hunter, input, strategicGoal),
          now,
          "missing-plan-at-execution");
      executeRecovery(hunter, input, world, now);
      return;
    }
    List<ActionRequest> proposals = new ArrayList<>();
    addPendingVisualAcquisition(proposals);
    BehaviorLane lane = selectBehaviorLane(hunter, input, now);
    if (lane != lastBehaviorLane) {
      record(
          hunter,
          now,
          "CONTROL_LANE",
          "previous=" + lastBehaviorLane + " next=" + lane
              + " visible=" + (visibleThisTick == null ? "none" : visibleThisTick.getUUID())
              + " attention=" + combatAttention.targetId());
      lastBehaviorLane = lane;
    }
    switch (lane) {
      case ACTIVE_COMBAT -> {
        addDamageReaction(proposals, hunter, now);
        addCombat(proposals, hunter, input, world, now);
        addCombatSearch(proposals, hunter, now);
        addResourceControl(proposals, hunter, input, now);
      }
      case COMBAT_SEARCH -> {
        addCombat(proposals, hunter, input, world, now);
        addCombatSearch(proposals, hunter, now);
        addResourceControl(proposals, hunter, input, now);
      }
      case OBJECTIVE_INTERACTION -> {
        addPlanMovement(proposals, hunter, input, world, now);
        addVigilance(proposals, hunter, input, world, now);
        addResourceControl(proposals, hunter, input, now);
      }
      case TACTICAL_OPERATION, STRATEGIC_TRANSIT -> {
        addPlanMovement(proposals, hunter, input, world, now);
        addVigilance(proposals, hunter, input, world, now);
        // Let the controller expire old state and report it, but an empty combat controller can
        // no longer compete with the lane that owns this tick.
        addCombat(proposals, hunter, input, world, now);
        addResourceControl(proposals, hunter, input, now);
      }
    }
    apply(hunter, now, proposals);
    updatePlanProgress(hunter, input, now);
  }

  private void addPendingVisualAcquisition(List<ActionRequest> proposals) {
    Vec3 legalPoint = perception.pendingLegalObservationPoint();
    if (legalPoint == null || visibleThisTick != null) return;
    proposals.add(
        ActionRequest.view(
            "combat-controller", 97, legalPoint, "finish-current-legal-visual-identification"));
  }

  private BehaviorLane selectBehaviorLane(
      HunterEntity hunter, HunterInputSnapshot input, long now) {
    boolean freshDirectDamage = occurredWithin(lastDamageAt, now, DAMAGE_ATTENTION_TICKS)
        && currentDamageThreat(now) != null;
    boolean combatSearch = visibleThisTick == null && combatSearchActive(now);
    boolean objectiveInteraction = isObjectiveInteractionReady(hunter, input);
    boolean tacticalOperation = isThreatResponsePlan(plan)
        || plan.intent() == HunterIntent.AMBUSH
        || plan.intent() == HunterIntent.OVERWATCH
        || plan.intent() == HunterIntent.FLANK
        || plan.intent() == HunterIntent.INTERCEPT
        || plan.intent() == HunterIntent.SEARCH;
    return BehaviorLanePolicy.select(
        visibleThisTick != null,
        freshDirectDamage,
        combatSearch,
        objectiveInteraction,
        tacticalOperation);
  }

  private boolean combatSearchActive(long now) {
    if (!combatAttention.present()) return combat.hasAttention(now);
    long evidenceAge = now - combatAttention.evidenceAt();
    boolean recentDirectAttacker = combatAttention.targetId().equals(damageAttackerId)
        && occurredWithin(damageSourceObservedAt, now, DAMAGE_THREAT_MEMORY_TICKS);
    if (!recentDirectAttacker && evidenceAge > 40L && combatAttention.confidence() < 0.10D)
      return false;
    long persistence = 50L + Math.round(personality.chasePersistence() * 70.0D);
    if (recentDirectAttacker) {
      persistence = CONTACT_SEARCH_MEMORY_TICKS;
    }
    return occurredWithin(combatAttention.evidenceAt(), now, persistence);
  }

  private boolean isObjectiveInteractionReady(HunterEntity hunter, HunterInputSnapshot input) {
    Vec3 objective = input.match().publicObjective();
    return switch (plan.goal()) {
      case START_BANISH -> withinBossInteractionRange(hunter.position(), objective, input.match());
      case CLAIM_BOUNTY -> withinObjectiveInteractionRange(hunter.position(), objective);
      case EXTRACT -> input.match().extractions().stream()
          .anyMatch(extraction -> withinObjectiveInteractionRange(hunter.position(), extraction));
      case REVIVE_TEAMMATE -> input.match().reviveTargets().stream()
          .anyMatch(target -> hunter.position().distanceToSqr(target.position()) <= 9.0D);
      default -> false;
    };
  }

  private void addPlanMovement(
      List<ActionRequest> proposals,
      HunterEntity hunter,
      HunterInputSnapshot input,
      HunterWorldModel.Snapshot world,
      long now) {
    Vec3 publicObjective = input.match().publicObjective();
    Vec3 planTarget = plan.opportunity() == null || plan.opportunity().approachRoute() == null
        ? publicObjective
        : plan.opportunity().approachRoute().destination();
    if (plan.goal() == StrategicGoal.EXTRACT && input.match().extraction() != null)
      publicObjective = input.match().extraction();

    if (plan.goal() == StrategicGoal.REVIVE_TEAMMATE) {
      Vec3 revive = input.match().reviveTargets().stream()
          .min(java.util.Comparator.comparingDouble(target -> target.position().distanceToSqr(hunter.position())))
          .map(cn.huntmc.hunterai.match.ReviveTarget::position)
          .orElse(null);
      if (revive == null) return;
      if (hunter.position().distanceToSqr(revive) <= 9.0D) {
        proposals.add(ActionRequest.movement(
            "strategic-plan", 84, null, 0.0D, MovementMode.HOLD, "hold-for-teammate-revive"));
        proposals.add(ActionRequest.view(
            "strategic-plan", 86, revive.add(0.0D, 0.55D, 0.0D), "face-downed-teammate"));
        if (visibleThisTick == null && currentDamageThreat(now, 20L) == null)
          proposals.add(ActionRequest.interaction(
              "strategic-plan", 91, InteractionCommand.REVIVE, "revive-clear-teammate"));
      } else {
        proposals.add(ActionRequest.movement(
            "strategic-plan",
            82,
            planTarget == null ? revive : planTarget,
            planTarget == null ? null : planRouteId(),
            1.0D,
            MovementMode.SPRINT,
            "approach-downed-teammate"));
      }
      return;
    }

    if (visibleThisTick != null && !plan.reason().contains("visible-")) {
      proposals.add(ActionRequest.movement(
          "tactical-interrupt", 84, null, 0.0D, MovementMode.HOLD, "pause-strategic-route-for-contact"));
      return;
    }

    if (isThreatResponsePlan(plan) && planTarget != null) {
      double distance = hunter.position().distanceTo(planTarget);
      proposals.add(ActionRequest.movement(
          "tactical-plan", 86, planTarget, planRouteId(), distance > 4.0D ? 1.0D : 0.92D,
          distance > 4.0D ? MovementMode.SPRINT : MovementMode.WALK,
          "execute-threat-response-route"));
      return;
    }

    if (plan.goal() == StrategicGoal.EXTRACT) {
      Vec3 extraction = nearestExtraction(hunter.position(), input.match());
      if (extraction == null) {
        proposals.add(ActionRequest.movement(
            "strategic-plan", 82, null, 0.0D, MovementMode.HOLD, "extraction-point-unavailable"));
        return;
      }
      boolean freshDamage = currentDamageThreat(now, DAMAGE_ATTENTION_TICKS) != null;
      if (withinObjectiveInteractionRange(hunter.position(), extraction)) {
        proposals.add(ActionRequest.movement(
            "strategic-plan", 82, null, 0.0D, MovementMode.HOLD, "hold-inside-extraction-zone"));
        proposals.add(ActionRequest.posture(
            "strategic-plan", 82, PostureCommand.STAND, "remain-mobile-at-extraction"));
        if (!freshDamage) {
          proposals.add(ActionRequest.view(
              "strategic-plan", 60, extraction.add(0.0D, 1.0D, 0.0D), "confirm-extraction-zone"));
          proposals.add(ActionRequest.interaction(
              "strategic-plan", 90, InteractionCommand.EXTRACT, "continue-bounty-extraction"));
        }
      } else {
        Vec3 transitTarget = planTarget == null ? extraction : planTarget;
        double distance = hunter.position().distanceTo(transitTarget);
        boolean threatFree = !freshDamage
            && !hasLocalMovementThreat(world.evidence(), hunter.position(), now);
        proposals.add(ActionRequest.movement(
            "strategic-plan",
            82,
            transitTarget,
            planTarget == null ? null : planRouteId(),
            1.03D,
            distance > 18.0D && threatFree
                ? MovementMode.SPRINT_JUMP_BURST
                : MovementMode.SPRINT,
            "bounty-carrier-transit-to-extraction"));
      }
      return;
    }

    if (plan.goal() == StrategicGoal.START_BANISH) {
      if (withinBossInteractionRange(hunter.position(), publicObjective, input.match())) {
        proposals.add(ActionRequest.movement("strategic-plan", 82, null, 0.0D, MovementMode.HOLD, "boss-interaction-range"));
        proposals.add(ActionRequest.posture(
            "strategic-plan", 54, PostureCommand.STAND, "remain-ready-during-banish-interaction"));
        boolean perimeterConfirmed = objectiveInteractionScanReady(publicObjective, now);
        if (perimeterConfirmed) {
          // The bridge owns the actual interaction. Keep only a low-priority confirmation look so
          // vigilance can continue clearing doors, roofs and the Hunter's rear while progress runs.
          proposals.add(ActionRequest.view(
              "strategic-plan", 46, publicObjective, "confirm-boss-without-locking-attention"));
        }
        if (visibleThisTick == null
            && perimeterConfirmed
            && !addObjectiveRecoveryMovement(proposals, hunter, publicObjective, now, "banish"))
          proposals.add(ActionRequest.interaction(
              "strategic-plan", 88, InteractionCommand.START_BANISH, "start-banish"));
      } else {
        resetObjectiveInteractionScan();
        Vec3 transitTarget = planTarget == null ? publicObjective : planTarget;
        if (transitTarget == null) return;
        proposals.add(ActionRequest.movement(
            "strategic-plan",
            82,
            transitTarget,
            planTarget == null ? null : planRouteId(),
            1.0D,
            MovementMode.SPRINT,
            "close-boss-distance"));
      }
      return;
    }

    if (plan.goal() == StrategicGoal.CLAIM_BOUNTY) {
      boolean tacticalDelay = claimTacticalDelayActive(claimWindowStartedAt, now, plan.intent());
      Vec3 claimTarget =
          claimTransitTarget(publicObjective, planTarget, tacticalDelay, visibleThisTick != null);
      if (withinObjectiveInteractionRange(hunter.position(), publicObjective)
          && visibleThisTick == null
          && !tacticalDelay) {
        proposals.add(ActionRequest.movement("strategic-plan", 84, null, 0.0D, MovementMode.HOLD, "bounty-pickup-range"));
        proposals.add(ActionRequest.view("strategic-plan", 84, publicObjective, "face-bounty"));
        if (!addObjectiveRecoveryMovement(proposals, hunter, publicObjective, now, "claim"))
          proposals.add(ActionRequest.interaction(
              "strategic-plan", 90, InteractionCommand.CLAIM_BOUNTY, "claim-clear-bounty"));
      } else if (claimTarget != null && hunter.position().distanceToSqr(claimTarget) > 2.5D) {
        double claimDistance = hunter.position().distanceTo(claimTarget);
        proposals.add(ActionRequest.movement(
            "strategic-plan", 82, claimTarget,
            samePosition(claimTarget, planTarget) ? planRouteId() : null, 1.03D,
            visibleThisTick == null && claimDistance > 18.0D
                ? MovementMode.SPRINT_JUMP_BURST
                : MovementMode.SPRINT,
            tacticalDelay ? "claim-bounty-tactical-position" : "claim-bounty-deadline-transit"));
      } else {
        proposals.add(ActionRequest.movement(
            "strategic-plan", 82, null, 0.0D, MovementMode.HOLD, "claim-bounty-tactical-delay"));
      }
      return;
    }

    if (plan.goal() == StrategicGoal.CONTEST_BANISH) {
      Vec3 contestTarget = planTarget == null ? publicObjective : planTarget;
      if (contestTarget == null) return;
      double objectiveDistance = publicObjective == null
          ? hunter.position().distanceTo(contestTarget)
          : hunter.position().distanceTo(publicObjective);
      if (publicObjective != null && objectiveDistance <= 12.0D && visibleThisTick == null) {
        Vec3 assaultPoint = contestAssaultPoint(publicObjective, hunter.getY(), hunter.getUUID(), now);
        proposals.add(ActionRequest.movement(
            "strategic-plan", 84, assaultPoint, 1.02D, MovementMode.SPRINT,
            "sweep-enemy-banish-perimeter"));
        proposals.add(ActionRequest.posture(
            "strategic-plan", 82, PostureCommand.STAND, "mobile-enemy-banish-assault"));
      } else {
        double distance = hunter.position().distanceTo(contestTarget);
        proposals.add(ActionRequest.movement(
            "strategic-plan",
            84,
            contestTarget,
            planTarget == null ? null : planRouteId(),
            1.03D,
            distance > 12.0D ? MovementMode.SPRINT_JUMP_BURST : MovementMode.SPRINT,
            "close-distance-to-enemy-banish"));
      }
      return;
    }

    if (plan.goal() == StrategicGoal.STAGE_BOUNTY) {
      if (planTarget != null && hunter.position().distanceToSqr(planTarget) > 3.0D) {
        proposals.add(ActionRequest.movement(
            "strategic-plan", 76, planTarget, planRouteId(), 0.96D,
            MovementMode.SPRINT, "reach-bounty-stage"));
      } else {
        proposals.add(ActionRequest.movement(
            "strategic-plan", 76, null, 0.0D, MovementMode.HOLD, "hold-bounty-stage"));
        TacticalNode stageNode = planner.tacticalMap().nearestNode(hunter.position());
        boolean crouchHasRealBenefit = crouchHasTacticalBenefit(stageNode);
        proposals.add(
            ActionRequest.posture(
                "strategic-plan",
                72,
                crouchHasRealBenefit ? PostureCommand.CROUCH : PostureCommand.STAND,
                crouchHasRealBenefit
                    ? "covered-bounty-defense"
                    : "standing-bounty-observation"));
      }
      return;
    }

    if (plan.goal() == StrategicGoal.INTERCEPT_APPROACH) {
      if (planTarget != null && hunter.position().distanceToSqr(planTarget) > 4.0D) {
        double distance = hunter.position().distanceTo(planTarget);
        boolean threatFree = visibleThisTick == null
            && !hasLocalMovementThreat(world.evidence(), hunter.position(), now);
        proposals.add(ActionRequest.movement(
            "strategic-plan", 76, planTarget, planRouteId(), 0.98D,
            distance > 18.0D && threatFree
                ? MovementMode.SPRINT_JUMP_BURST
                : MovementMode.SPRINT,
            "reach-diversified-interception-perimeter"));
      } else {
        proposals.add(ActionRequest.movement(
            "strategic-plan", 72, null, 0.0D, MovementMode.HOLD,
            "hold-covered-interception-position"));
        TacticalNode interceptNode = planner.tacticalMap().nearestNode(hunter.position());
        boolean concealedCrouch = crouchHasTacticalBenefit(interceptNode);
        proposals.add(ActionRequest.posture(
            "strategic-plan", 68,
            concealedCrouch ? PostureCommand.CROUCH : PostureCommand.STAND,
            concealedCrouch ? "concealed-interception" : "mobile-interception-observation"));
      }
      return;
    }

    if (visibleThisTick != null && plan.intent() != HunterIntent.PUSH && plan.intent() != HunterIntent.FLANK) {
      proposals.add(ActionRequest.movement(
          "tactical-plan", 74, null, 0.0D, MovementMode.HOLD, "stable-combat-position"));
      return;
    }

    if (planTarget != null) {
      double distance = hunter.position().distanceTo(planTarget);
      boolean threatFree = visibleThisTick == null
          && !hasLocalMovementThreat(world.evidence(), hunter.position(), now);
      MovementMode mode = distance > 18.0D && threatFree
          ? MovementMode.SPRINT_JUMP_BURST
          : distance > 4.0D ? MovementMode.SPRINT : MovementMode.WALK;
      if (plan.intent() == HunterIntent.DISENGAGE) mode = MovementMode.EVADE;
      proposals.add(ActionRequest.movement(
          "strategic-plan", 72, planTarget, planRouteId(),
          mode == MovementMode.WALK ? 0.92D : 1.0D, mode,
          "execute-plan-route"));
    } else {
      proposals.add(ActionRequest.movement(
          "strategic-plan", 70, null, 0.0D, MovementMode.HOLD, "plan-has-no-route"));
    }
  }

  static boolean crouchHasTacticalBenefit(TacticalNode node) {
    return node != null
        && (node.isConcealed() || node.hardCover() >= 0.35D)
        && node.escapeDirections() >= 2
        && node.standingExposure() - node.crouchedExposure() >= 0.15D;
  }

  static Vec3 claimTransitTarget(
      Vec3 publicObjective, Vec3 tacticalTarget, boolean tacticalDelay, boolean immediateThreat) {
    return (tacticalDelay || immediateThreat) && tacticalTarget != null
        ? tacticalTarget
        : publicObjective;
  }

  static boolean withinObjectiveInteractionRange(Vec3 position, Vec3 objective) {
    return position != null
        && objective != null
        && position.distanceToSqr(objective)
            <= STANDARD_INTERACTION_RANGE * STANDARD_INTERACTION_RANGE;
  }

  static Vec3 nearestExtraction(Vec3 position, MatchDirective directive) {
    if (directive == null) return null;
    List<Vec3> choices = directive.extractions().isEmpty()
        ? directive.extraction() == null ? List.of() : List.of(directive.extraction())
        : directive.extractions();
    if (position == null) return choices.isEmpty() ? null : choices.get(0);
    return choices.stream()
        .min(java.util.Comparator.comparingDouble(position::distanceToSqr))
        .orElse(null);
  }

  static boolean withinBossInteractionRange(
      Vec3 position, Vec3 objective, MatchDirective directive) {
    double range = effectiveBossInteractionRange(directive);
    return position != null
        && objective != null
        && position.distanceToSqr(objective) <= range * range;
  }

  static double effectiveBossInteractionRange(MatchDirective directive) {
    return directive == null
        ? cn.huntmc.bridge.contract.BridgeProtocol.DEFAULT_BOSS_INTERACTION_DISTANCE
        : directive.bossInteractionDistance();
  }

  static boolean claimTacticalDelayActive(
      long claimStartedAt, long now, HunterIntent intent) {
    boolean delayingIntent = intent == HunterIntent.AMBUSH
        || intent == HunterIntent.OVERWATCH
        || intent == HunterIntent.LISTEN
        || intent == HunterIntent.INTERCEPT;
    return delayingIntent
        && claimStartedAt != Long.MIN_VALUE
        && now >= claimStartedAt
        && now - claimStartedAt < MAX_BOUNTY_TACTICAL_DELAY_TICKS;
  }

  private void observeInteractionResult(HunterEntity hunter, long now) {
    if (lastActuator == null
        || lastActuator == lastObservedInteractionResult
        || lastArbitration == null
        || lastArbitration.frame().interaction() == InteractionCommand.NONE) return;
    lastObservedInteractionResult = lastActuator;
    String result = lastActuator.interactionResult();
    if (lastActuator.interactionAccepted()) {
      objectiveInteractionFailures = 0;
      objectiveRecoveryTarget = null;
    } else if (result.startsWith("rejected") || result.startsWith("error")) {
      objectiveInteractionFailures = Math.min(8, objectiveInteractionFailures + 1);
      objectiveRecoveryTarget = null;
      record(
          hunter,
          now,
          "FALLBACK",
          "level=OBJECTIVE_APPROACH interaction=" + lastArbitration.frame().interaction()
              + " failures=" + objectiveInteractionFailures + " result=" + result);
    }
  }

  private boolean addObjectiveRecoveryMovement(
      List<ActionRequest> proposals,
      HunterEntity hunter,
      Vec3 objective,
      long now,
      String purpose) {
    if (objectiveInteractionFailures <= 0 || objective == null) return false;
    if (objectiveRecoveryTarget == null)
      objectiveRecoveryTarget = objectiveApproachPoint(
          objective, hunter.getUUID(), objectiveInteractionFailures);
    if (hunter.position().distanceToSqr(objectiveRecoveryTarget) <= 1.4D * 1.4D) return false;
    ValidatedRoute route = actuator.validateRoute(
        hunter,
        objectiveRecoveryTarget,
        0.15D,
        objectiveInteractionFailures * 0.05D,
        "objective-approach-" + purpose + "-" + objectiveInteractionFailures + "-" + now / 20L);
    if (!route.reachable()) {
      objectiveInteractionFailures = Math.min(8, objectiveInteractionFailures + 1);
      objectiveRecoveryTarget = null;
      return false;
    }
    proposals.add(ActionRequest.movement(
        "objective-recovery",
        93,
        route.destination(),
        route.id(),
        0.95D,
        MovementMode.SPRINT,
        "alternate-interaction-approach-" + purpose));
    return true;
  }

  static Vec3 objectiveApproachPoint(Vec3 objective, UUID hunterId, int attempt) {
    int lane = Math.floorMod(hunterId.hashCode() + Math.max(1, attempt) * 3, 8);
    double angle = lane * Math.PI / 4.0D;
    double radius = 1.75D + Math.floorMod(attempt, 2) * 0.45D;
    return objective.add(Math.cos(angle) * radius, 0.0D, Math.sin(angle) * radius);
  }

  private boolean objectiveInteractionScanReady(Vec3 objective, long now) {
    if (objective == null) return false;
    if (interactionScanObjective == null
        || interactionScanObjective.distanceToSqr(objective) > 1.0D) {
      interactionScanObjective = objective;
      interactionScanStartedAt = now;
      interactionCommittedUntil = Long.MIN_VALUE;
      return false;
    }
    if (interactionCommittedUntil != Long.MIN_VALUE && now <= interactionCommittedUntil) return true;
    if (!occurredWithin(interactionScanStartedAt, now, 40L)) {
      interactionScanStartedAt = now;
      return false;
    }
    if (now - interactionScanStartedAt < 12L) return false;
    interactionCommittedUntil = now + 60L;
    return true;
  }

  private void resetObjectiveInteractionScan() {
    interactionScanObjective = null;
    interactionScanStartedAt = Long.MIN_VALUE;
    interactionCommittedUntil = Long.MIN_VALUE;
  }

  static Vec3 contestAssaultPoint(Vec3 objective, double currentY, UUID hunterId, long now) {
    int identity = hunterId.hashCode();
    long phase = Math.floorDiv(now + Math.floorMod(identity, 80), 100L);
    int lane = Math.floorMod(identity + (int) (phase * 5L), 12);
    double angle = lane * Math.PI / 6.0D;
    double radius = 6.5D + Math.floorMod(identity >>> 4, 4);
    return new Vec3(
        objective.x + Math.cos(angle) * radius,
        currentY,
        objective.z + Math.sin(angle) * radius);
  }

  private void addVigilance(
      List<ActionRequest> proposals,
      HunterEntity hunter,
      HunterInputSnapshot input,
      HunterWorldModel.Snapshot world,
      long now) {
    if (visibleThisTick != null) {
      vigilance.resetLostTargetSearch();
      return;
    }
    Evidence noise = world.evidence().stream()
        .filter(item -> item.type() == Evidence.Type.GUNSHOT || item.type() == Evidence.Type.MOVEMENT_SOUND)
        .max(Comparator.comparingLong(Evidence::observedAt))
        .orElse(null);
    Evidence surprise = world.evidence().stream()
        .filter(item -> item.type() == Evidence.Type.DAMAGE)
        .max(Comparator.comparingLong(Evidence::observedAt))
        .orElse(null);
    vigilance.refresh(
        planner.tacticalMap(),
        hunter,
        noise == null ? null : noise.region().center(),
        noise == null ? Long.MIN_VALUE : noise.observedAt(),
        noise == null ? 0.0D : noise.confidence(now),
        surprise == null ? null : surprise.region().center(),
        surprise == null ? Long.MIN_VALUE : surprise.observedAt());
    if (vigilance.confirmObservation(hunter.getYHeadRot(), now))
      worldModel.confirmFacing(hunter.getYHeadRot(), now);
    Vec3 recentAttack = currentDamageThreat(now, DAMAGE_ATTENTION_TICKS);
    if (recentAttack != null) {
      Vec3 look = recentAttack.add(0.0D, 1.3D, 0.0D);
      proposals.add(ActionRequest.view(
          "threat-awareness", 78, look, "confirm-recent-direct-attack"));
      perception.beginFacingSweep(yawTo(hunter.position(), look), now);
      return;
    }
    Evidence closeFootsteps = corroboratedCloseMovementEvidence(
        world.evidence(), hunter.position(), now);
    if (closeFootsteps != null) {
      Vec3 look = closeFootsteps.region().center().add(0.0D, 1.2D, 0.0D);
      proposals.add(ActionRequest.view(
          "footstep-confirmation", 67, look, "corroborated-nearby-movement"));
      perception.beginFacingSweep(yawTo(hunter.position(), look), now);
      return;
    }
    Vec3 searchEstimate = combatAttention.present()
        ? combatAttention.estimatedPosition()
        : lastVisiblePosition;
    long searchEvidenceAt = combatAttention.present()
        ? combatAttention.evidenceAt()
        : lastVisibleAt;
    if (searchEstimate != null
        && occurredWithin(searchEvidenceAt, now, CONTACT_SEARCH_MEMORY_TICKS)) {
      Float searchYaw = vigilance.nextLostTargetSearchYaw(hunter, searchEstimate, now);
      if (searchYaw != null) {
        Vec3 searchDirection = Vec3.directionFromRotation(0.0F, searchYaw);
        Vec3 searchTarget = hunter.getEyePosition().add(searchDirection.scale(24.0D));
        proposals.add(ActionRequest.view(
            "contact-search", 72, searchTarget, "search-recent-legal-contact-area"));
        perception.beginFacingSweep(searchYaw, now);
        return;
      }
    } else {
      vigilance.resetLostTargetSearch();
    }
    Vec3 routeTarget = plan == null || plan.opportunity() == null || plan.opportunity().approachRoute() == null
        ? input.match().publicObjective()
        : plan.opportunity().approachRoute().destination();
    boolean movingFast = routeTarget != null && hunter.position().distanceTo(routeTarget) > 5.0D
        && plan.goal() != StrategicGoal.STAGE_BOUNTY;
    boolean vulnerable = input.self().healthRatio() < 0.45D
        || input.self().reloading()
        || planner.lastSpace().localExposure() >= 0.60D;
    Random scanRandom = new Random(
        hunter.getUUID().getMostSignificantBits()
            ^ Long.rotateLeft(hunter.getUUID().getLeastSignificantBits(), 13)
            ^ now / 20L);
    Float scanYaw = vigilance.nextScanYaw(hunter, false, vulnerable, scanRandom);
    if (movingFast) {
      if (scanYaw != null && vigilance.scanStartedAt(now)) {
        Vec3 direction = Vec3.directionFromRotation(vigilance.activeScanPitch(now), scanYaw);
        observationTarget = hunter.getEyePosition().add(direction.scale(24.0D));
        nextObservationAt = now
            + VigilanceSystem.travelGlanceDuration(hunter.getYHeadRot(), scanYaw);
        record(hunter, now, "VIGILANCE", vigilance.summary(now) + " mode=travel-glance");
      }
      if (observationTarget != null && now < nextObservationAt) {
        proposals.add(ActionRequest.view(
            "vigilance", 49, observationTarget, "brief-unpredictable-travel-glance"));
        perception.beginFacingSweep(yawTo(hunter.position(), observationTarget), now);
        return;
      }
      Vec3 forwardLook = routeTarget.add(0.0D, 1.2D, 0.0D);
      proposals.add(ActionRequest.view("route-awareness", 48, forwardLook, "scan-current-travel-bearing"));
      perception.beginFacingSweep(yawTo(hunter.position(), forwardLook), now);
      worldModel.confirmFacing(yawTo(hunter.position(), forwardLook), now);
      return;
    }
    if (scanYaw != null && (observationTarget == null || now >= nextObservationAt || vigilance.scanStartedAt(now))) {
      Vec3 direction = Vec3.directionFromRotation(vigilance.activeScanPitch(now), scanYaw);
      observationTarget = hunter.getEyePosition().add(direction.scale(24.0D));
      nextObservationAt = now + 6L;
      perception.beginFacingSweep(scanYaw, now);
      record(hunter, now, "VIGILANCE", vigilance.summary(now) + " mode=deliberate-observation");
    }
    proposals.add(ActionRequest.view("vigilance", 52, observationTarget, "probability-and-uncertainty-sector"));
    if (plan.intent() == HunterIntent.AMBUSH
        || plan.intent() == HunterIntent.OVERWATCH
        || plan.intent() == HunterIntent.LISTEN) {
      TacticalNode observationNode = planner.tacticalMap().nearestNode(hunter.position());
      proposals.add(ActionRequest.posture(
          "vigilance",
          50,
          crouchHasTacticalBenefit(observationNode) ? PostureCommand.CROUCH : PostureCommand.STAND,
          crouchHasTacticalBenefit(observationNode)
              ? "covered-concealed-observation"
              : "do-not-crouch-in-exposed-observation"));
    }
  }

  static Evidence corroboratedCloseMovementEvidence(
      List<Evidence> evidence, Vec3 selfPosition, long now) {
    if (evidence == null || selfPosition == null) return null;
    Evidence latest = evidence.stream()
        .filter(item -> item.type() == Evidence.Type.MOVEMENT_SOUND)
        .filter(item -> occurredWithin(item.observedAt(), now, 24L))
        .filter(item -> item.confidence(now) >= 0.20D)
        .filter(item -> item.region().center().distanceToSqr(selfPosition) <= 22.0D * 22.0D)
        .max(Comparator.comparingLong(Evidence::observedAt))
        .orElse(null);
    if (latest == null) return null;
    long corroboratingSamples = evidence.stream()
        .filter(other -> other.type() == Evidence.Type.MOVEMENT_SOUND)
        .filter(other -> other.independenceKey().equals(latest.independenceKey()))
        .filter(other -> occurredWithin(other.observedAt(), now, 32L))
        .limit(2L)
        .count();
    return corroboratingSamples >= 2L ? latest : null;
  }

  private void addDamageReaction(
      List<ActionRequest> proposals, HunterEntity hunter, long now) {
    Vec3 threat = currentDamageThreat(now);
    if (threat == null
        || visibleThisTick != null
        || !occurredWithin(lastDamageAt, now, DAMAGE_CONFIRM_TICKS)) return;
    Vec3 away = hunter.position().subtract(threat).multiply(1.0D, 0.0D, 1.0D);
    if (away.lengthSqr() < 1.0E-6D) away = new Vec3(1.0D, 0.0D, 0.0D);
    Vec3 side = new Vec3(-away.z, 0.0D, away.x).normalize();
    long mixed = hunter.getUUID().getMostSignificantBits() ^ lastDamageAt;
    if ((mixed & 1L) != 0L) side = side.scale(-1.0D);
    proposals.add(ActionRequest.movement(
        "damage-reaction", 92, hunter.position().add(side.scale(3.5D)), 0.98D,
        MovementMode.EVADE, "brief-strafe-while-confirming-attacker"));
    proposals.add(ActionRequest.view(
        "damage-reaction", 99, threat.add(0.0D, 1.3D, 0.0D),
        "turn-to-fresh-damage-source"));
  }

  private void addCombat(
      List<ActionRequest> proposals,
      HunterEntity hunter,
      HunterInputSnapshot input,
      HunterWorldModel.Snapshot world,
      long now) {
    CombatController.Output output = combat.tick(
        hunter,
        visibleThisTick,
        currentDamageThreat(now),
        damageAttackerId,
        lastDamageAt,
        lastDamageAmount,
        input,
        plan,
        personality,
        planner.lastSpace(),
        actuator.fireRate(hunter),
        actuator.weaponReadiness(hunter),
        lastActuator,
        now);
    proposals.addAll(output.proposals());
    record(
        hunter,
        now,
        "COMBAT",
        combat.summary(now)
            + " aimPoint=" + vector(output.aimPoint())
            + " aimError=" + (Double.isFinite(output.aimError()) ? decimal(output.aimError()) : "none")
            + " fireRequested=" + output.fireRequested());
  }

  private void addCombatSearch(
      List<ActionRequest> proposals, HunterEntity hunter, long now) {
    if (visibleThisTick != null || !combatAttention.present() || !combatSearchActive(now)) return;
    Vec3 estimate = combatAttention.estimatedPosition();
    Vec3 approach = selectSearchApproach(
        hunter.position(),
        estimate,
        planner.tacticalMap().pursuitApproaches(estimate, List.of()),
        hunter.getUUID(),
        personality.flankPreference());
    long age = Math.max(0L, now - combatAttention.evidenceAt());
    if (age <= 10L && combatAttention.targetId().equals(damageAttackerId)) {
      proposals.add(ActionRequest.view(
          "combat-search", 99, estimate.add(0.0D, 1.3D, 0.0D),
          "confirm-recent-attacker-sector"));
    } else {
      proposals.add(ActionRequest.view(
          "combat-search", 89, estimate.add(0.0D, 1.2D, 0.0D),
          "search-decaying-contact-region"));
    }
    if (shouldAdvanceCombatSearch(hunter.position(), approach)) {
      proposals.add(ActionRequest.movement(
          "combat-search", 88, approach, 1.0D, MovementMode.SPRINT,
          personality.flankPreference() >= 0.55D
              ? "alternate-approach-to-last-contact"
              : "bounded-approach-to-last-contact"));
    }
    if (lastPursuitLogAt == Long.MIN_VALUE || now - lastPursuitLogAt >= 20L) {
      lastPursuitLogAt = now;
      record(hunter, now, "COMBAT", "search=attention-region target=" + combatAttention.targetId()
          + " estimate=" + vector(estimate)
          + " approach=" + vector(approach)
          + " evidenceAge=" + age
          + " confidence=" + decimal(combatAttention.confidence()));
    }
  }

  static boolean shouldAdvanceCombatSearch(Vec3 self, Vec3 approach) {
    return self != null && approach != null && self.distanceToSqr(approach) > 4.0D;
  }

  static Vec3 selectSearchApproach(
      Vec3 self,
      Vec3 remembered,
      List<TacticalNode> candidates,
      UUID hunterId,
      double flankPreference) {
    if (remembered == null) return self;
    if (candidates == null || candidates.isEmpty() || self == null || hunterId == null)
      return remembered;
    double currentDistance = self.distanceTo(remembered);
    List<TacticalNode> progressing = candidates.stream()
        .filter(node -> node != null && node.position() != null)
        .filter(node -> node.position().distanceTo(remembered)
            <= Math.max(1.5D, currentDistance - 1.5D))
        .filter(node -> node.position().distanceTo(self) <= 16.0D)
        .toList();
    if (progressing.isEmpty()) return remembered;
    if (flankPreference < 0.55D) return progressing.get(0).position();
    Vec3 direct = remembered.subtract(self).multiply(1.0D, 0.0D, 1.0D);
    if (direct.lengthSqr() < 1.0E-6D) return candidates.get(0).position();
    direct = direct.normalize();
    final Vec3 bearing = direct;
    boolean left = ((hunterId.getMostSignificantBits() ^ hunterId.getLeastSignificantBits()) & 1L) == 0L;
    return progressing.stream()
        .filter(node -> node.position().distanceTo(remembered) >= 2.5D)
        .max(Comparator.comparingDouble(node -> {
          Vec3 fromTarget = node.position().subtract(remembered).multiply(1.0D, 0.0D, 1.0D);
          if (fromTarget.lengthSqr() < 1.0E-6D) return -10.0D;
          Vec3 normalized = fromTarget.normalize();
          double lateral = Math.abs(bearing.x * normalized.z - bearing.z * normalized.x);
          double signed = bearing.x * normalized.z - bearing.z * normalized.x;
          double preferredSide = (left ? signed : -signed) > 0.0D ? 0.18D : 0.0D;
          return lateral * 0.70D
              + preferredSide
              + node.hardCover() * 0.22D
              + node.escapeDirections() * 0.03D
              - Math.min(1.0D, node.position().distanceTo(remembered) / 16.0D) * 0.12D;
        }))
        .map(TacticalNode::position)
        .orElse(progressing.get(0).position());
  }

  private void addResourceControl(
      List<ActionRequest> proposals, HunterEntity hunter, HunterInputSnapshot input, long now) {
    if (input.self().reloading() || input.self().ammunition() <= 0)
      proposals.add(ActionRequest.weapon("resource", 88, WeaponCommand.RELOAD, "magazine-empty-or-reloading"));
    else if (input.self().ammoRatio() < 0.18D)
      proposals.add(ActionRequest.weapon("resource", 42, WeaponCommand.RELOAD, "low-ammunition-safe-reload"));

    boolean eligibleToRecover = input.self().healthRatio() < 0.55D
        && visibleThisTick == null
        && currentDamageThreat(now, 60L) == null
        && (lastDamageAt == Long.MIN_VALUE || now - lastDamageAt >= 60L);
    if (!eligibleToRecover) return;
    TacticalNode cover = planner.tacticalMap().bestConcealedFrom(
        hunter, currentDamageThreat(now, 160L), List.of());
    double coverDistanceSquared = cover == null
        ? Double.POSITIVE_INFINITY
        : hunter.position().distanceToSqr(cover.position());
    boolean reachedConcealedNode = cover != null
        && cover.isConcealed()
        && coverDistanceSquared <= 0.85D * 0.85D;
    if (cover != null
        && !reachedConcealedNode
        && (planner.lastSpace().localExposure() > 0.48D || coverDistanceSquared > 6.25D)) {
      proposals.add(ActionRequest.movement(
          "health-recovery", 89, cover.position(), 1.0D, MovementMode.SPRINT,
          "reach-cover-before-health-recovery"));
      return;
    }
    if (!reachedConcealedNode && planner.lastSpace().localExposure() > 0.48D) return;
    proposals.add(ActionRequest.posture(
        "health-recovery", 83, PostureCommand.CROUCH, "recover-behind-cover"));
    proposals.add(ActionRequest.interaction(
        "health-recovery", 84, InteractionCommand.RECOVER_HEALTH, "natural-match-health-recovery"));
  }

  private void updatePlanProgress(HunterEntity hunter, HunterInputSnapshot input, long now) {
    if (plan == null) return;
    boolean reachedInterceptionStage = plan.goal() == StrategicGoal.INTERCEPT_APPROACH
        && plan.phase() != PlanPhase.APPROACH;
    boolean boundedObservation = plan.goal() != StrategicGoal.STAGE_BOUNTY
        && (plan.intent() == HunterIntent.AMBUSH
            || plan.intent() == HunterIntent.OVERWATCH
            || plan.intent() == HunterIntent.LISTEN
            || reachedInterceptionStage);
    if (boundedObservation && visibleThisTick == null && now >= plan.committedUntil()) {
      plan = plan.invalidated("observation-window-complete");
      record(
          hunter,
          now,
          "PLAN",
          "planId=" + plan.id() + " result=invalidated reason=observation-window-complete");
      return;
    }
    ValidatedRoute route =
        plan.opportunity() == null ? null : plan.opportunity().approachRoute();
    Vec3 target = route == null ? input.match().publicObjective() : route.destination();
    if (plan.goal() == StrategicGoal.INTERCEPT_APPROACH && target == null) {
      plan = plan.invalidated("interception-target-unavailable");
      record(
          hunter,
          now,
          "PLAN",
          "planId=" + plan.id() + " result=invalidated reason=interception-target-unavailable");
      return;
    }
    if (target != null
        && hunter.position().distanceToSqr(target) <= planArrivalDistanceSquared(plan.goal())
        && plan.phase() == PlanPhase.APPROACH) {
      if (route != null && !route.reachesRequestedTarget() && route.requestedTarget() != null) {
        ValidatedRoute continuation =
            actuator.validateRoute(
                hunter,
                route.requestedTarget(),
                route.exposureCost(),
                route.repetitionCost(),
                "plan-continuation:" + plan.id() + ":" + now / 20L);
        if (continuation.reachable() && continuation.destination() != null) {
          plan =
              plan.withOpportunity(
                  plan.opportunity().withApproachRoute(continuation),
                  "partial-route-continuation");
          record(
              hunter,
              now,
              "PLAN",
              "planId="
                  + plan.id()
                  + " phase="
                  + plan.phase()
                  + " reason=partial-route-continuation requested="
                  + vector(route.requestedTarget())
                  + " checkpoint="
                  + vector(continuation.destination()));
        } else {
          recovery.startPathRecovery(
              hunter, route.requestedTarget(), now, "strategic-partial-continuation-failed");
          plan = plan.invalidated("partial-route-continuation-unavailable");
          record(
              hunter,
              now,
              "FALLBACK",
              "level=PATH_RECOVERY result=continuation-rejected target="
                  + vector(route.requestedTarget())
                  + " reason="
                  + continuation.failureReason());
        }
        return;
      }
      plan = plan.withPhase(
          plan.goal() == StrategicGoal.STAGE_BOUNTY ? PlanPhase.OBSERVE : PlanPhase.STAGE,
          "route-target-reached");
      record(hunter, now, "PLAN", "planId=" + plan.id() + " phase=" + plan.phase() + " reason=target-reached");
      if (plan.goal() == StrategicGoal.TRANSIT_TO_BOSS
          || plan.goal() == StrategicGoal.TRANSIT_TO_STAGE
          || plan.goal() == StrategicGoal.CONTEST_BANISH
          || plan.goal() == StrategicGoal.HUNT_SIGNAL
          || extractionLegNeedsContinuation(
              plan.goal(), isObjectiveInteractionReady(hunter, input))
          || plan.goal() == StrategicGoal.INTERCEPT_APPROACH
              && plan.intent() == HunterIntent.PUSH
          || isThreatResponsePlan(plan)) {
        plan = plan.invalidated("intermediate-route-complete");
      }
    }
    if (visibleThisTick != null && plan.phase() != PlanPhase.ENGAGE)
      plan = plan.withPhase(PlanPhase.ENGAGE, "visible-contact");
  }

  static double planArrivalDistanceSquared(StrategicGoal goal) {
    // INTERCEPT movement starts HOLD at two blocks. Progress accounting must use the same radius;
    // the former sqrt(3)-block threshold left a permanent dead band between the two checks.
    return goal == StrategicGoal.INTERCEPT_APPROACH ? 4.0D : 3.0D;
  }

  static boolean extractionLegNeedsContinuation(
      StrategicGoal goal, boolean insideExtractionZone) {
    return goal == StrategicGoal.EXTRACT && !insideExtractionZone;
  }

  private boolean startSafeReset(
      HunterEntity hunter,
      HunterInputSnapshot input,
      HunterWorldModel.Snapshot world,
      long now,
      String reason) {
    if (safeReset.active(now)) return true;
    if (now < safeResetCooldownUntil && !reason.contains("emergency")) {
      record(
          hunter,
          now,
          "FALLBACK",
          "level=SAFE_RESET result=reentry-suppressed cooldownUntil="
              + safeResetCooldownUntil
              + " reason="
              + reason);
      return false;
    }
    recovery.finish();
    clearFallbackRoute();
    Vec3 threat = visibleThisTick != null ? visibleThisTick.position() : currentDamageThreat(now);
    Vec3 strategicAnchor = threat == null
            && plan != null
            && plan.opportunity() != null
            && plan.opportunity().approachRoute() != null
        ? plan.opportunity().approachRoute().requestedTarget()
        : input.match().carryingBounty() && input.match().extraction() != null
            ? input.match().extraction()
            : strategicRecoveryTarget(hunter, input, strategicGoal);
    if (plan != null && plan.goal() != StrategicGoal.SAFE_RESET) suspendedStrategicPlan = plan;
    safeReset.start(hunter, planner.lastSpace(), threat, strategicAnchor, now, reason);
    actuator.clearRouteFailures();
    currentDecisionId = decisionId(hunter.getUUID(), input.match().matchId(), ++decisionSequence, now);
    plan = new HunterPlan(
        currentDecisionId,
        StrategicGoal.SAFE_RESET,
        HunterIntent.DISENGAGE,
        null,
        PlanPhase.ESCAPE,
        List.of(),
        now + SafeResetController.MAX_SAFE_RESET_TICKS,
        now + SafeResetController.MAX_SAFE_RESET_TICKS,
        PlanValidity.VALID,
        safeReset.snapshot(),
        1.0D,
        reason);
    record(hunter, now, "FALLBACK", "level=SAFE_RESET phase=" + safeReset.snapshot().phase() + " reason=" + reason);
    return true;
  }

  private void executeSafeReset(
      HunterEntity hunter, HunterInputSnapshot input, HunterWorldModel.Snapshot world, long now) {
    Vec3 threat = visibleThisTick != null ? visibleThisTick.position() : currentDamageThreat(now);
    safeReset.observeActuatorResult(lastActuator, hunter, threat, now);
    List<ActionRequest> proposals =
        new ArrayList<>(prepareFallbackRoutes(hunter, safeReset.proposals(hunter, input, threat, now), threat, now));
    addCombat(proposals, hunter, input, world, now);
    addResourceControl(proposals, hunter, input, now);
    apply(hunter, now, proposals);
    record(hunter, now, "FALLBACK", "level=SAFE_RESET phase=" + safeReset.snapshot().phase() + " reason=" + safeReset.reason());
  }

  private void executeRecovery(
      HunterEntity hunter, HunterInputSnapshot input, HunterWorldModel.Snapshot world, long now) {
    List<ActionRequest> proposals =
        new ArrayList<>(prepareFallbackRoutes(hunter, recovery.proposals(hunter, now), null, now));
    addCombat(proposals, hunter, input, world, now);
    addResourceControl(proposals, hunter, input, now);
    apply(hunter, now, proposals);
    record(hunter, now, "FALLBACK", "level=" + recovery.mode() + " " + recovery.summary(now));
  }

  private void apply(HunterEntity hunter, long now, List<ActionRequest> proposals) {
    for (ActionRequest proposal : proposals)
      record(hunter, now, "ACTION_PROPOSAL", proposalSummary(proposal));
    lastArbitration = arbiter.arbitrate(now, proposals);
    for (ArbitrationResult.Decision decision : lastArbitration.decisions())
      record(
          hunter, now, "ARBITRATION",
          "owner=" + decision.owner() + " channel=" + decision.channel()
              + " accepted=" + decision.accepted() + " reason=" + decision.reason());
    ActionFrame frame = lastArbitration.frame();
    record(hunter, now, "ACTION_FRAME", frameSummary(frame));
    lastActuator = actuator.apply(hunter, frame);
    if (lastActuator.weaponResult().startsWith("fired")) worldModel.recordShot();
    record(hunter, now, "ACTUATOR_RESULT", actuatorSummary(hunter, lastActuator));
  }

  private List<ActionRequest> prepareFallbackRoutes(
      HunterEntity hunter,
      List<ActionRequest> proposals,
      Vec3 threat,
      long now) {
    List<ActionRequest> prepared = new ArrayList<>();
    for (ActionRequest proposal : proposals) {
      if (!proposal.channels().contains(ActionChannel.MOVEMENT)
          || proposal.moveTarget() == null
          || proposal.routeId() != null
          || proposal.movement() == MovementMode.SAFE_DROP) {
        prepared.add(proposal);
        continue;
      }
      Vec3 requestedTarget = proposal.moveTarget();
      if (proposal.owner().equals(fallbackRouteOwner)
          && fallbackRouteRequestedTarget != null
          && fallbackRouteRequestedTarget.distanceToSqr(requestedTarget) <= 0.01D
          && fallbackRouteId != null
          && fallbackRouteExecutionTarget != null) {
        if (!partialFallbackCheckpointReached(
            fallbackRouteNeedsContinuation, hunter.position(), fallbackRouteExecutionTarget)) {
          prepared.add(proposal.withValidatedMovement(fallbackRouteExecutionTarget, fallbackRouteId));
          continue;
        }
        record(
            hunter,
            now,
            "FALLBACK",
            "level=" + proposal.owner() + " result=partial-checkpoint-reached target="
                + vector(requestedTarget));
        clearFallbackRoute();
      }
      ValidatedRoute route =
          actuator.validateRoute(
              hunter,
              requestedTarget,
              0.0D,
              0.0D,
              "fallback:" + proposal.owner() + ":" + proposal.reason());
      if (route.reachable() && route.destination() != null) {
        boolean reachesRequestedTarget =
            routeReachesRequestedTarget(requestedTarget, route.destination());
        fallbackRouteOwner = proposal.owner();
        fallbackRouteExecutionTarget = route.destination();
        fallbackRouteId = route.id();
        if ("safe-reset".equals(proposal.owner())) {
          fallbackRouteRequestedTarget = requestedTarget;
          fallbackRouteNeedsContinuation = !reachesRequestedTarget;
          if (reachesRequestedTarget) {
            safeReset.acceptValidatedTarget(requestedTarget, route.destination());
          }
        } else {
          recovery.acceptValidatedTarget(requestedTarget, route.destination());
          fallbackRouteRequestedTarget = route.destination();
          fallbackRouteNeedsContinuation = false;
        }
        prepared.add(proposal.withValidatedMovement(route.destination(), route.id()));
      } else {
        record(
            hunter,
            now,
            "FALLBACK",
            "level=" + proposal.owner() + " result=route-rejected target=" + vector(requestedTarget)
                + " reason=" + route.failureReason());
        clearFallbackRoute();
        if ("safe-reset".equals(proposal.owner())) {
          safeReset.rejectUnreachableTarget(hunter, threat, now, route.failureReason());
        } else {
          recovery.rejectUnreachableTarget(route.failureReason());
        }
      }
    }
    return List.copyOf(prepared);
  }

  private void finishSafeReset(long now) {
    safeReset.finish();
    safeResetCooldownUntil = now + SAFE_RESET_REENTRY_COOLDOWN_TICKS;
    clearFallbackRoute();
  }

  private void clearFallbackRoute() {
    fallbackRouteOwner = null;
    fallbackRouteRequestedTarget = null;
    fallbackRouteExecutionTarget = null;
    fallbackRouteId = null;
    fallbackRouteNeedsContinuation = false;
  }

  static boolean routeReachesRequestedTarget(Vec3 requestedTarget, Vec3 executionTarget) {
    return requestedTarget != null
        && executionTarget != null
        && requestedTarget.distanceToSqr(executionTarget) <= 0.01D;
  }

  static boolean partialFallbackCheckpointReached(
      boolean needsContinuation, Vec3 position, Vec3 executionTarget) {
    return needsContinuation
        && position != null
        && executionTarget != null
        && position.distanceToSqr(executionTarget)
            <= FALLBACK_CHECKPOINT_ARRIVAL_DISTANCE_SQUARED;
  }

  private void recordPerception(HunterEntity hunter, long now) {
    PerceptionSystem.PerceptionSnapshot snapshot = perception.lastSnapshot();
    record(
        hunter, now, "PERCEPTION",
        "candidates=" + snapshot.candidates() + " distant=" + snapshot.distantCandidates()
            + " fovRejected=" + snapshot.fovRejected() + " losRejected=" + snapshot.lineOfSightRejected()
            + " rayRejected=" + snapshot.rayBudgetRejected() + " contact=" + snapshot.contactState()
            + " visible=" + (visibleThisTick == null ? "none" : visibleThisTick.getUUID())
            + " diagnostic=" + snapshot.diagnosticResult());
  }

  private void recordIntel(HunterEntity hunter, long now, IntelAssessment intel) {
    record(
        hunter, now, "INTEL_ASSESSMENT",
        "source=" + decimal(intel.sourceReliability()) + " recency=" + decimal(intel.recency())
            + " consistency=" + decimal(intel.spatialConsistency())
            + " independent=" + decimal(intel.independentEvidence())
            + " localization=" + decimal(intel.localizationQuality())
            + " relevance=" + decimal(intel.relevance()) + " quality=" + decimal(intel.quality()));
    record(hunter, now, "SIMULATION_GATE", "mode=" + intel.mode() + " reason=" + intel.reason());
  }

  private void recordCandidates(HunterEntity hunter, long now, List<DecisionCandidate> candidates) {
    for (DecisionCandidate candidate : candidates)
      record(
          hunter, now, "CANDIDATE",
          "candidateId=" + candidate.id() + " goal=" + candidate.goal() + " intent=" + candidate.intent()
              + " score=" + decimal(candidate.analyticScore()) + " exposure=" + decimal(candidate.exposure())
              + " routeId=" + (candidate.route() == null ? "none" : candidate.route().id())
              + " routeEnd="
              + vector(candidate.route() == null ? null : candidate.route().pathEnd())
              + " routeLength="
              + (candidate.route() == null ? "none" : decimal(candidate.route().length()))
              + " spatialChain=" + opportunitySummary(candidate.opportunity())
              + " feasible=" + candidate.feasible() + " rejected=" + candidate.infeasibleReason()
              + " source=" + candidate.source());
  }

  private void recordPrediction(
      HunterEntity hunter, long now, UUID decisionId, PredictionResult prediction) {
    record(
        hunter, now, "PREDICTION",
        "decisionId=" + decisionId + " candidateId=" + prediction.candidateId()
            + " seed=" + prediction.seed() + " samples=" + prediction.scenarios().size()
            + " success=" + decimal(prediction.successRate())
            + " survival=" + decimal(prediction.survivalRate())
             + " damage=" + decimal(prediction.expectedDamage())
             + " ammoCost=" + decimal(prediction.ammunitionCost())
             + " objectiveDelay=" + decimal(prediction.objectiveDelay())
             + " endPositionValue=" + decimal(prediction.endPositionValue())
             + " exposure=" + decimal(prediction.exposure())
             + " thirdPartyRisk=" + decimal(prediction.thirdPartyRisk())
             + " escape=" + decimal(prediction.escapeRate())
            + " worstQuartile=" + decimal(prediction.worstQuartileLoss())
            + " confidence=" + decimal(prediction.confidence())
            + " score=" + decimal(prediction.score()));
    for (PredictionResult.ScenarioResult scenario : prediction.scenarios())
      record(
          hunter, now, "PREDICTION",
          "decisionId=" + decisionId + " candidateId=" + prediction.candidateId()
              + " seed=" + prediction.seed() + " scenario=" + scenario.index()
              + " horizonTicks=" + scenario.horizonTicks()
              + " enemyPresence=" + decimal(scenario.enemyPresence())
              + " enemies=" + scenario.enemyCount()
              + " detected=" + decimal(scenario.detectedProbability())
              + " fightEnds=" + scenario.fightEnds()
              + " thirdParty=" + scenario.thirdParty() + " firstShot=" + scenario.firstShot()
              + " exposure=" + decimal(scenario.routeExposure())
              + " escape=" + decimal(scenario.escapeFeasibility())
              + " survival=" + decimal(scenario.survival())
              + " damage=" + decimal(scenario.damage())
              + " success=" + decimal(scenario.success())
              + " loss=" + decimal(scenario.loss()));
  }

  private void recordWorldModel(
      HunterEntity hunter, long now, HunterWorldModel.Snapshot world) {
    record(
        hunter, now, "WORLD_MODEL",
        "contacts=" + world.contacts().size() + " evidence=" + world.evidence().size()
            + " multiParty=" + world.encounter().multiPartyFight()
            + " encounterConfidence=" + decimal(world.encounter().confidence())
            + " independentSources=" + world.encounter().independentSources()
            + " compromise=" + decimal(world.exposure().compromise())
            + " tacticalNodes=" + planner.lastSpace().nodes().size()
            + " tacticalEdges=" + planner.lastSpace().edges().size()
            + " corridors=" + planner.lastSpace().threatCorridors().size());
  }

  private void recordOutcome(HunterEntity hunter, HunterInputSnapshot input) {
    Vec3 position = input.self().position();
    double displacement = previousTickPosition == null ? 0.0D : previousTickPosition.distanceTo(position);
    double healthDelta = Double.isNaN(previousTickHealth) ? 0.0D : input.self().health() - previousTickHealth;
    double objectiveDistance = input.match().publicObjective() == null
        ? Double.NaN
        : position.distanceTo(input.match().publicObjective());
    double objectiveProgress = Double.isNaN(previousObjectiveDistance) || Double.isNaN(objectiveDistance)
        ? 0.0D
        : previousObjectiveDistance - objectiveDistance;
    record(
        hunter, input.gameTime(), "OUTCOME",
        "position=" + vector(position) + " displacement=" + decimal(displacement)
            + " healthDelta=" + decimal(healthDelta)
            + " objectiveDistance=" + (Double.isNaN(objectiveDistance) ? "none" : decimal(objectiveDistance))
            + " objectiveProgress=" + decimal(objectiveProgress));
    previousTickPosition = position;
    previousTickHealth = input.self().health();
    previousObjectiveDistance = objectiveDistance;
  }

  private void recordPerformance(
      HunterEntity hunter, long now, long startedAt, long rolloutMicros, int samples) {
    long elapsed = System.nanoTime() - startedAt;
    record(
        hunter, now, "PERFORMANCE",
        "coordinatorMicros=" + String.format(Locale.ROOT, "%.1f", elapsed / 1_000.0D)
            + " rolloutMicros=" + rolloutMicros + " samples=" + samples);
    HuntMcHunterAi mod = HuntMcHunterAi.instance();
    if (mod != null) mod.hunterRegistry().recordBrainNanos(hunter, elapsed);
  }

  private void record(HunterEntity hunter, long now, String category, String detail) {
    String contextual = detail == null ? "" : detail;
    if (currentDecisionId != null && !hasField(contextual, "decisionId"))
      contextual = "decisionId=" + currentDecisionId + (contextual.isBlank() ? "" : " " + contextual);
    if (plan != null && !hasField(contextual, "planId"))
      contextual = "planId=" + plan.id() + (contextual.isBlank() ? "" : " " + contextual);
    if (plan != null
        && plan.opportunity() != null
        && plan.opportunity().approachRoute() != null
        && !hasField(contextual, "routeId"))
      contextual = "routeId=" + plan.opportunity().approachRoute().id()
          + (contextual.isBlank() ? "" : " " + contextual);
    trace.record(now, category, contextual);
    HuntMcHunterAi mod = HuntMcHunterAi.instance();
    if (mod != null) mod.hunterRegistry().recordMonitor(hunter, now, category, contextual);
  }

  private static boolean hasField(String detail, String name) {
    return detail.startsWith(name + "=") || detail.contains(" " + name + "=");
  }

  private static boolean objectiveChanged(MatchDirective previous, MatchDirective current) {
    if (previous == null) return current.assigned();
    if (!previous.bountyState().equals(current.bountyState())) return true;
    if (previous.carryingBounty() != current.carryingBounty()) return true;
    if (!previous.banishingTeamId().equals(current.banishingTeamId())) return true;
    Vec3 first = previous.publicObjective();
    Vec3 second = current.publicObjective();
    if (first == null || second == null) return first != second;
    double allowed = Math.max(1.0D, current.bountySignalUncertaintyRadius());
    return first.distanceToSqr(second) > allowed * allowed;
  }

  private static boolean planGoalInvalid(HunterPlan plan, StrategicGoal goal) {
    return plan.goal() != goal && plan.goal() != StrategicGoal.SAFE_RESET;
  }

  private UUID planRouteId() {
    return plan == null
            || plan.opportunity() == null
            || plan.opportunity().approachRoute() == null
        ? null
        : plan.opportunity().approachRoute().id();
  }

  private static boolean samePosition(Vec3 first, Vec3 second) {
    return first != null && second != null && first.distanceToSqr(second) <= 0.01D;
  }

  private static boolean isThreatResponsePlan(HunterPlan plan) {
    return plan != null
        && (plan.reason().contains("direct-attack") || plan.reason().contains("extract-threat"));
  }

  private boolean resumableStrategicPlan(long now) {
    return suspendedStrategicPlan != null
        && suspendedStrategicPlan.validity() == PlanValidity.VALID
        && suspendedStrategicPlan.goal() == strategicGoal
        && (suspendedStrategicPlan.deadline() == Long.MAX_VALUE
            || now <= suspendedStrategicPlan.deadline());
  }

  private static int estimateMeaningfulChoices(
      HunterWorldModel.Snapshot world, LivingEntity visible, StrategicGoal goal) {
    if (visible != null) return 4;
    if (world.encounter().multiPartyFight()) return 3;
    if (goal == StrategicGoal.STAGE_BOUNTY || goal == StrategicGoal.INTERCEPT_APPROACH) return 3;
    if (goal != StrategicGoal.SURVIVE) return 5;
    return 1;
  }

  private static double planRelevance(
      HunterWorldModel.Snapshot world, StrategicGoal goal) {
    if (world.hasVisibleContact()) return 1.0D;
    if (world.encounter().multiPartyFight()) return 0.90D;
    if (goal == StrategicGoal.EXTRACT) return 0.95D;
    if (goal == StrategicGoal.CLAIM_BOUNTY || goal == StrategicGoal.STAGE_BOUNTY) return 0.85D;
    if (goal == StrategicGoal.INTERCEPT_APPROACH) return 0.70D;
    return 0.55D;
  }

  private Vec3 currentDamageThreat(long now) {
    return currentDamageThreat(now, DAMAGE_THREAT_MEMORY_TICKS);
  }

  private Vec3 currentDamageThreat(long now, long maxAge) {
    return occurredWithin(damageSourceObservedAt, now, maxAge)
        ? damageSource
        : null;
  }

  private void notePlanSwitch(HunterPlan next, long now) {
    if (now - planSwitchWindowStartedAt > 120L) {
      planSwitchWindowStartedAt = now;
      planSwitchesInWindow = 0;
    }
    String signature = next.goal() + ":" + next.intent();
    if (!signature.equals(lastPlanSignature)) planSwitchesInWindow++;
    lastPlanSignature = signature;
  }

  private boolean planSwitchOscillating(long now) {
    if (now - planSwitchWindowStartedAt > 120L) return false;
    return planSwitchesInWindow >= 5;
  }

  private long inputDeadline(long now) {
    if (previousDirective == null || previousDirective.matchRemainingTicks() < 0L) return Long.MAX_VALUE;
    return now + previousDirective.matchRemainingTicks();
  }

  private static List<ContingencyBranch> contingencies() {
    return List.of(
        new ContingencyBranch("visible-enemy", HunterIntent.REPOSITION, PlanPhase.ENGAGE),
        new ContingencyBranch("route-failure", HunterIntent.DISENGAGE, PlanPhase.ESCAPE));
  }

  private static UUID decisionId(UUID hunterId, UUID matchId, long sequence, long now) {
    String value = hunterId + ":" + matchId + ":" + sequence + ":" + now;
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
  }

  private static float yawTo(Vec3 from, Vec3 to) {
    Vec3 offset = to.subtract(from);
    return (float) (Mth.atan2(offset.z, offset.x) * (180.0D / Math.PI)) - 90.0F;
  }

  private static long mix(UUID id, long salt) {
    long value = id.getMostSignificantBits() ^ Long.rotateLeft(id.getLeastSignificantBits(), 19) ^ salt;
    value ^= value >>> 33;
    value *= 0xff51afd7ed558ccdL;
    return value ^ (value >>> 33);
  }

  static boolean occurredWithin(long eventAt, long now, long windowTicks) {
    if (eventAt == Long.MIN_VALUE || windowTicks < 0L || eventAt > now) return false;
    return eventAt > Long.MAX_VALUE - windowTicks || now <= eventAt + windowTicks;
  }

  static boolean hasLocalMovementThreat(List<Evidence> evidence, Vec3 position, long now) {
    if (evidence == null || position == null) return false;
    return evidence.stream()
        .filter(observation -> observation.confidence(now) >= 0.60D)
        .filter(
            observation ->
                observation.type() != Evidence.Type.OBJECTIVE_CHANGE
                    && observation.type() != Evidence.Type.DEATH)
        .anyMatch(
            observation -> {
              var region = observation.region().atAge(Math.max(0L, now - observation.observedAt()));
              double localRadius = 48.0D + region.radius();
              return position.distanceToSqr(region.center()) <= localRadius * localRadius;
            });
  }

  private static String quantized(Vec3 position) {
    return Math.round(position.x / 4.0D) + ":" + Math.round(position.y / 4.0D) + ":" + Math.round(position.z / 4.0D);
  }

  private String inputSummary(
      HunterEntity hunter, HunterInputSnapshot input, boolean teleported) {
    List<String> flags = new ArrayList<>();
    if (hunter.isPassenger()) flags.add("vehicle");
    if (input.self().inWater()) flags.add("water");
    if (input.self().climbing()) flags.add("ladder");
    if (hunter.isFallFlying()) flags.add("fall-flying");
    if (hunter.fallDistance > 1.5F) flags.add("falling");
    if (hunter.hurtTime > 0) flags.add("knockback-window");
    if (teleported) flags.add("teleport");
    String movementFlags = flags.isEmpty() ? "normal" : String.join(",", flags);
    return "phase=pre-action position=" + vector(input.self().position()) + " velocity=" + vector(input.self().velocity())
        + " horizontalSpeed=" + decimal(input.self().velocity().horizontalDistance())
        + " yaw=" + decimal(input.self().yaw()) + " pitch=" + decimal(input.self().pitch())
        + " health=" + decimal(input.self().health()) + "/" + decimal(input.self().maxHealth())
        + " ammo=" + input.self().ammunition() + "/" + input.self().magazineCapacity()
        + " crouch=" + input.self().crouching() + " sprint=" + input.self().sprinting()
        + " crouching=" + input.self().crouching() + " sprinting=" + input.self().sprinting()
        + " airborne=" + !input.self().onGround()
        + " jumped=" + (lastActuator != null && lastActuator.jumped())
        + " movementFlags=" + movementFlags
        + " teleported=" + teleported
        + " assigned=" + input.match().assigned() + " active=" + input.match().active()
        + " team=" + input.match().teamId()
        + " objective=" + vector(input.match().publicObjective())
        + " bounty=" + input.match().bountyState()
        + " bossInteractionRange=" + decimal(input.match().bossInteractionDistance())
        + " banishingTeam=" + input.match().banishingTeamId()
        + " carrying=" + input.match().carryingBounty();
  }

  private static String evidenceSummary(Evidence evidence, long now) {
    return "evidenceId=" + evidence.id() + " type=" + evidence.type()
        + " center=" + vector(evidence.region().center()) + " radius=" + decimal(evidence.region().radius())
        + " confidence=" + decimal(evidence.confidence(now)) + " expiresAt=" + evidence.expiresAt()
        + " source=" + evidence.sourceKey();
  }

  private static String proposalSummary(ActionRequest proposal) {
    return "owner=" + proposal.owner() + " priority=" + proposal.priority()
        + " channels=" + proposal.channels() + " movement=" + proposal.movement()
        + " target=" + vector(proposal.moveTarget()) + " routeId=" + proposal.routeId()
        + " look=" + vector(proposal.lookTarget())
        + " posture=" + proposal.posture() + " weapon=" + proposal.weapon()
        + " interaction=" + proposal.interaction() + " reason=" + proposal.reason();
  }

  private static String frameSummary(ActionFrame frame) {
    return "movement=" + frame.movement() + " target=" + vector(frame.moveTarget())
        + " routeId=" + frame.routeId() + " speed=" + decimal(frame.speed())
        + " look=" + vector(frame.lookTarget())
        + " posture=" + frame.posture() + " weapon=" + frame.weapon()
        + " interaction=" + frame.interaction()
        + " weaponChannelOwner=" + frame.owners().getOrDefault(ActionChannel.WEAPON, "none")
        + " owners=" + frame.owners();
  }

  private static String actuatorSummary(HunterEntity hunter, ActuatorResult result) {
    return "phase=post-action position=" + vector(hunter.position())
        + " navigation=" + result.navigationAccepted()
        + " movementProgress=" + result.movementProgress()
        + " movementResult=" + result.movementResult()
        + " routeId=" + result.routeId() + " pathEnd=" + vector(result.pathEnd())
        + " failureTarget=" + vector(result.failureTarget())
        + " routeFailures=" + result.consecutiveRouteFailures() + " jumped=" + result.jumped()
        + " sprintRequested=" + result.sprintRequested() + " sprinting=" + result.sprinting()
        + " horizontalSpeed=" + decimal(result.horizontalSpeed())
        + " forwardInput=" + decimal(result.forwardInput())
        + " strafeInput=" + decimal(result.strafeInput())
        + " view=" + result.viewApplied() + " posture=" + result.postureApplied()
        + " weapon=" + result.weaponApplied() + ":" + result.weaponResult()
        + " interaction=" + result.interactionAccepted() + ":" + result.interactionResult();
  }

  private static String vector(Vec3 value) {
    return value == null ? "none" : String.format(Locale.ROOT, "(%.1f,%.1f,%.1f)", value.x, value.y, value.z);
  }

  private static String opportunitySummary(cn.huntmc.hunterai.ai.TacticalOpportunity opportunity) {
    if (opportunity == null) return "none";
    return "[waiting:"
        + nodeVector(opportunity.waitingPoint())
        + ",observation:"
        + nodeVector(opportunity.observationPoint())
        + ",firing1:"
        + nodeVector(opportunity.firstFiringPoint())
        + ",firing2:"
        + nodeVector(opportunity.secondFiringPoint())
        + ",retreat:"
        + vector(
            opportunity.retreatRoute() == null
                ? null
                : opportunity.retreatRoute().destination())
        + "]";
  }

  private static String nodeVector(cn.huntmc.hunterai.ai.TacticalNode node) {
    return node == null ? "none" : vector(node.position());
  }

  private static String decimal(double value) {
    return String.format(Locale.ROOT, "%.2f", value);
  }

  private record PendingEvidence(
      Evidence.Type type,
      UUID subjectId,
      Vec3 source,
      float strength,
      long time,
      String sourceKey) {}

  private record Selection(
      DecisionCandidate candidate,
      double score,
      String reason,
      long rolloutMicros,
      int rolloutSamples,
      double confidence) {
    private static Selection none(String reason) {
      return new Selection(null, 0.0D, reason, 0L, 0, 0.0D);
    }
  }
}
