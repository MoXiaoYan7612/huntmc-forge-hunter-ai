package cn.huntmc.hunterai.monitor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Offline replay and defect analyzer. It never accesses a live Minecraft world. */
public final class HunterReplayAnalyzer {
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
  private static final long MAX_BEHAVIOR_FRAME_GAP = 8L;
  private static final long RECOVERY_MERGE_GAP = 40L;
  private static final Set<String> CONTINUOUS_DEFECT_TYPES = Set.of(
      "PATH_LOOP",
      "PATH_EXECUTION_FAILURE",
      "FAILED_ROUTE_REGION_RETRY",
      "SPRINT_EXECUTION_MISMATCH",
      "PENDING_VISUAL_CONFIRMATION_LOST",
      "OBJECTIVE_NO_PROGRESS",
      "NAVIGATION_FALSE_SUCCESS",
      "INTERCEPT_HOLD_STALL",
      "FALLBACK_LOOP",
      "DEAD_TARGET_RETENTION");

  private HunterReplayAnalyzer() {}

  public static Analysis analyze(Path sessionDirectory) throws IOException {
    if (sessionDirectory == null || !Files.isDirectory(sessionDirectory))
      throw new IOException("Replay session directory does not exist: " + sessionDirectory);
    List<Event> events = readEvents(sessionDirectory);
    Analysis analysis = enrichWithPlayerComparison(inspect(events), events);
    Files.writeString(
        sessionDirectory.resolve("session-summary.md"),
        summary(analysis),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);
    Files.writeString(
        sessionDirectory.resolve("defects.json"),
        GSON.toJson(analysis) + "\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);
    Files.writeString(
        sessionDirectory.resolve("player-behavior-summary.md"),
        playerBehaviorSummary(events),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);
    Files.writeString(
        sessionDirectory.resolve("ai-player-comparison.md"),
        aiPlayerComparison(events, analysis),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);
    return analysis;
  }

  public static String compare(Path first, Path second) throws IOException {
    Analysis left = inspect(readEvents(first));
    Analysis right = inspect(readEvents(second));
    String report = "# Hunter Replay Comparison\n\n"
        + "| Metric | First | Second |\n| --- | ---: | ---: |\n"
        + row("Events", left.eventCount(), right.eventCount())
        + row("Hunters", left.hunters().size(), right.hunters().size())
        + row("Plans", left.planCount(), right.planCount())
        + row("Shots", left.shotCount(), right.shotCount())
        + row("Rollout predictions", left.predictionCount(), right.predictionCount())
        + row("Unique plan signatures", left.uniquePlanSignatures(), right.uniquePlanSignatures())
        + row("Executed plans", left.executedPlanCount(), right.executedPlanCount())
        + decimalRow("Plan execution rate", left.planExecutionRate(), right.planExecutionRate())
        + decimalRow("Objective blocks/tick", left.objectiveProgressPerTick(), right.objectiveProgressPerTick())
        + decimalRow("Shots/visible frame", left.shotsPerVisibleFrame(), right.shotsPerVisibleFrame())
        + decimalRow("Prediction mean error", left.predictionMeanError(), right.predictionMeanError())
        + decimalRow("Behavior diversity", left.behaviorDiversity(), right.behaviorDiversity())
        + decimalRow("Coordinator average us", left.averageCoordinatorMicros(), right.averageCoordinatorMicros())
        + decimalRow("Coordinator P95 us", left.p95CoordinatorMicros(), right.p95CoordinatorMicros())
        + row("Defects", left.defects().size(), right.defects().size())
        + row("Sequence gaps", left.sequenceGaps(), right.sequenceGaps());
    Files.writeString(
        second.resolve("comparison-with-" + first.getFileName() + ".md"),
        report,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);
    return report;
  }

  /** Returns replay events and defects matching an offline investigation filter. */
  public static ReplayQuery query(Path session, ReplayFilter requestedFilter) throws IOException {
    ReplayFilter filter = requestedFilter == null ? ReplayFilter.all() : requestedFilter;
    List<Event> events = readEvents(session);
    List<TimelineEntry> timeline =
        events.stream().filter(filter::matches).map(Event::timelineEntry).toList();
    Map<Long, Event> bySequence = new HashMap<>();
    events.forEach(event -> bySequence.put(event.sequence(), event));
    List<Defect> defects =
        inspect(events).defects().stream()
            .filter(defect -> filter.matches(defect, bySequence.get(defect.sequence())))
            .toList();
    return new ReplayQuery(timeline, defects);
  }

  public static void main(String[] args) throws Exception {
    if (args.length == 1) {
      Analysis result = analyze(Path.of(args[0]));
      System.out.println("Analyzed " + result.eventCount() + " events; defects=" + result.defects().size());
      return;
    }
    if (args.length == 2) {
      System.out.println(compare(Path.of(args[0]), Path.of(args[1])));
      return;
    }
    throw new IllegalArgumentException("Usage: HunterReplayAnalyzer <session> [comparison-session]");
  }

  private static Analysis inspect(List<Event> events) {
    List<Defect> defects = new ArrayList<>();
    Set<String> defectKeys = new HashSet<>();
    Map<String, HunterState> hunters = new LinkedHashMap<>();
    Map<String, Set<String>> simultaneousPlans = new HashMap<>();
    Map<String, Set<String>> simultaneousViews = new HashMap<>();
    Map<String, Double> predictionSuccess = new HashMap<>();
    List<Double> coordinatorMicros = new ArrayList<>();
    long previousSequence = 0L;
    long sequenceGaps = 0L;
    int planCount = 0;
    int shotCount = 0;
    int predictionCount = 0;
    Set<String> planSignatures = new HashSet<>();

    for (Event event : events) {
      long expectedSequence = previousSequence == 0L ? 1L : previousSequence + 1L;
      if (event.sequence() != expectedSequence) {
        sequenceGaps += Math.max(1L, Math.abs(event.sequence() - expectedSequence));
        add(defects, defectKeys, new Defect(
            "LOG_SEQUENCE_GAP", "CRITICAL", event.hunterId(), event.sequence(),
            "Expected sequence " + expectedSequence + " but found " + event.sequence()));
      }
      previousSequence = event.sequence();
      if (event.hunterId() == null) continue;
      HunterState state = hunters.computeIfAbsent(event.hunterId(), HunterState::new);
      state.lastSequence = event.sequence();
      state.lastGameTime = event.sessionTick();

      switch (event.type()) {
        case "INPUT" -> inspectInput(event, state, defects, defectKeys);
        case "PERCEPTION" -> inspectPerception(event, state, defects, defectKeys);
        case "EVIDENCE" -> {
          if ("DAMAGE".equals(field(event, "type")) && !state.lifecycleEnded) {
            state.lastDamageEvidenceAt = event.sessionTick();
            state.damageContactObserved = false;
            state.damageResponseReported = false;
          }
        }
        case "OUTCOME" -> inspectOutcome(event, state, defects, defectKeys);
        case "ACTION_FRAME" -> {
          inspectFrame(event, state, defects, defectKeys);
          String look = field(event, "look");
          if (!look.isBlank() && !"none".equals(look)) {
            String key = event.sessionTick() + ":" + look;
            Set<String> same = simultaneousViews.computeIfAbsent(key, ignored -> new HashSet<>());
            same.add(event.hunterId());
            if (same.size() == 3)
              add(defects, defectKeys, new Defect(
                  "SYNCHRONIZED_VIEW", "MEDIUM", event.hunterId(), event.sequence(),
                  "Three Hunters used the same look target on the same tick: " + look));
          }
        }
        case "ACTUATOR_RESULT" -> inspectActuator(event, state, defects, defectKeys);
        case "LIFECYCLE" -> inspectLifecycle(event, state);
        case "COMBAT" -> {
          inspectCombat(event, state, defects, defectKeys);
        }
        case "DIRECTOR" -> {
          if ("observe-without-immediate-duel".equals(field(event, "result"))) {
            state.directorDeferredAt = event.sessionTick();
          }
        }
        case "PLAN" -> {
          String goal = field(event, "goal");
          String intent = field(event, "intent");
          if (!goal.isBlank() && !intent.isBlank()) {
            planCount++;
            if (state.planBurstStartedAt < 0L
                || event.sessionTick() - state.planBurstStartedAt > 20L) {
              state.planBurstStartedAt = event.sessionTick();
              state.planCommitsInBurst = 1;
              state.planChurnReported = false;
            } else {
              state.planCommitsInBurst++;
              if (!state.planChurnReported && state.planCommitsInBurst >= 10) {
                add(
                    defects,
                    defectKeys,
                    new Defect(
                        "PLAN_CHURN",
                        "CRITICAL",
                        event.hunterId(),
                        event.sequence(),
                        "Hunter committed "
                            + state.planCommitsInBurst
                            + " plans within 20 ticks"));
                state.planChurnReported = true;
              }
            }
            String signature = goal + ":" + intent;
            planSignatures.add(signature);
            state.finishPrediction(event.sequence(), defects, defectKeys);
            state.beginPlan(event, signature, predictionSuccess.get(field(event, "candidateId")));
            String routeId = event.routeId() == null ? field(event, "routeId") : event.routeId();
            String routeTarget = field(event, "target");
            if (!routeId.isBlank() && !"none".equals(routeId)) {
              state.routeUses++;
              state.routeIds.add(routeId);
              if (!routeTarget.isBlank() && !"none".equals(routeTarget))
                state.routeTargets.add(routeTarget);
            }
            if (!"SAFE_RESET".equals(goal)) {
              state.fallbackActive = false;
              state.fallbackLoopReported = false;
            } else {
              String safeResetPlanId =
                  event.planId() == null ? field(event, "planId") : event.planId();
              if (state.lastSafeResetPlanId != null
                  && !state.lastSafeResetPlanId.equals(safeResetPlanId)
                  && state.lastFallbackEventAt != Long.MIN_VALUE
                  && event.sessionTick() >= state.lastFallbackEventAt
                  && event.sessionTick() - state.lastFallbackEventAt <= 40L) {
                add(defects, defectKeys, new Defect(
                    "FALLBACK_REENTRY", "HIGH", event.hunterId(), event.sequence(),
                    "A new SAFE_RESET episode started within 40 ticks of the previous episode"));
              }
              if (!safeResetPlanId.isBlank()
                  && !safeResetPlanId.equals(state.lastSafeResetPlanId)) {
                state.fallbackActive = false;
                state.fallbackLoopReported = false;
                state.lastSafeResetPlanId = safeResetPlanId;
              }
            }
            String synchronizedKey = event.sessionTick() + ":" + signature + ":" + routeTarget;
            Set<String> same =
                simultaneousPlans.computeIfAbsent(synchronizedKey, ignored -> new HashSet<>());
            same.add(event.hunterId());
            if (same.size() == 3)
              add(defects, defectKeys, new Defect(
                  "SYNCHRONIZED_PLAN", "MEDIUM", event.hunterId(), event.sequence(),
                  "Three Hunters committed the same plan and target on the same tick: "
                      + signature + ":" + routeTarget));
          }
        }
        case "PREDICTION" -> {
          if (field(event, "scenario").isBlank()) {
            predictionCount++;
            Double success = number(field(event, "success"));
            if (success != null) predictionSuccess.put(field(event, "candidateId"), success);
          }
        }
        case "FALLBACK" -> {
          if ("SAFE_RESET".equals(field(event, "level"))) {
            String phase = field(event, "phase");
            if (!state.fallbackActive || !phase.equals(state.lastFallbackPhase)) state.fallbackCount++;
            if (!state.fallbackActive) {
              state.fallbackStartedAt = event.sessionTick();
              state.fallbackLoopReported = false;
            }
            state.fallbackActive = true;
            state.lastFallbackPhase = phase;
            state.lastFallbackEventAt = event.sessionTick();
            if (!state.fallbackLoopReported && event.sessionTick() - state.fallbackStartedAt > 165L) {
              add(defects, defectKeys, new Defect(
                  "FALLBACK_LOOP", "HIGH", event.hunterId(), event.sequence(),
                  "One SAFE_RESET episode remained active beyond its 160 tick deadline"));
              state.fallbackLoopReported = true;
            }
          }
        }
        case "PERFORMANCE", "PERF" -> {
          Double micros = number(field(event, "coordinatorMicros"));
          if (micros == null) micros = number(field(event, "brainMicros"));
          if (micros != null) coordinatorMicros.add(micros);
        }
        default -> {}
      }
      if ("ACTUATOR_RESULT".equals(event.type()) && field(event, "weapon").contains("fired")) {
        shotCount++;
        state.lastShotAt = event.sessionTick();
      }
    }

    for (HunterState state : hunters.values()) {
      state.finishPrediction(state.lastSequence, defects, defectKeys);
      if (!state.damageResponseReported
          && !state.lifecycleEnded
          && state.damageContactObserved
          && state.lastDamageEvidenceAt != Long.MIN_VALUE
          && state.lastGameTime - state.lastDamageEvidenceAt >= 80L
          && state.lastShotAt < state.lastDamageEvidenceAt) {
        add(defects, defectKeys, new Defect(
            "DAMAGE_RESPONSE_NO_FIRE", "HIGH", state.id, state.lastSequence,
            "After direct-damage evidence the combat controller entered a stabilized fire state, "
                + "but no successful counterfire occurred during the next 80 ticks"));
        state.damageResponseReported = true;
      }
      if (state.pendingPlanId != null && !state.pendingPlanExecuted
          && state.lastGameTime - state.lastPlanAt >= 20L)
        add(defects, defectKeys, new Defect(
            "PLAN_NOT_EXECUTED", "HIGH", state.id, state.lastSequence,
            "Committed plan produced no matching action frame within 20 ticks"));
      if (state.routeTargets.size() > 0 && state.routeUses > 8 && state.routeTargets.size() <= 2)
        add(defects, defectKeys, new Defect(
            "REPEATED_ROUTE", "MEDIUM", state.id, state.lastSequence,
            "Hunter used " + state.routeTargets.size() + " route targets across "
                + state.routeUses + " plans"));
    }

    int executedPlans = hunters.values().stream().mapToInt(state -> state.executedPlanCount).sum();
    long visibleFrames = hunters.values().stream().mapToLong(state -> state.visibleFrames).sum();
    double objectiveProgress = hunters.values().stream().mapToDouble(HunterState::objectiveProgress).sum();
    long objectiveTicks = hunters.values().stream().mapToLong(HunterState::objectiveTicks).sum();
    double predictionError = hunters.values().stream()
        .flatMap(state -> state.predictionErrors.stream())
        .mapToDouble(Double::doubleValue)
        .average()
        .orElse(0.0D);
    double routeDiversity = hunters.values().stream()
        .filter(state -> state.routeUses > 0)
        .mapToDouble(state -> state.routeTargets.size() / (double) state.routeUses)
        .average()
        .orElse(1.0D);
    double planDiversity = planCount == 0 ? 1.0D : planSignatures.size() / (double) planCount;
    coordinatorMicros.sort(Double::compareTo);
    double averageMicros = coordinatorMicros.stream().mapToDouble(Double::doubleValue).average().orElse(0.0D);
    double p95Micros = coordinatorMicros.isEmpty()
        ? 0.0D
        : coordinatorMicros.get(
            Math.min(coordinatorMicros.size() - 1, (int) Math.ceil(coordinatorMicros.size() * 0.95D) - 1));

    Map<String, HunterSummary> summaries = new LinkedHashMap<>();
    hunters.forEach(
        (id, state) -> summaries.put(
            id,
            new HunterSummary(
                id,
                state.lastGameTime,
                state.lastPlan,
                state.lastMovement,
                state.lastWeapon,
                state.planCount,
                state.fallbackCount,
                state.routeTargets.size())));
    return new Analysis(
        HunterMonitorService.SCHEMA_VERSION,
        events.size(),
        events.isEmpty() ? 0L : events.get(0).sequence(),
        events.isEmpty() ? 0L : events.get(events.size() - 1).sequence(),
        sequenceGaps,
        planCount,
        shotCount,
        predictionCount,
        planSignatures.size(),
        executedPlans,
        planCount == 0 ? 1.0D : executedPlans / (double) planCount,
        objectiveTicks == 0L ? 0.0D : objectiveProgress / objectiveTicks,
        visibleFrames == 0L ? 0.0D : shotCount / (double) visibleFrames,
        predictionError,
        Math.max(0.0D, Math.min(1.0D, (routeDiversity + planDiversity) * 0.5D)),
        averageMicros,
        p95Micros,
        summaries,
        aggregateContinuousDefects(defects, events));
  }

  private static void inspectInput(
      Event event, HunterState state, List<Defect> defects, Set<String> keys) {
    // Minecraft does not promise that Entity yaw is stored in [-180, 180]. Values in [0, 360]
    // and accumulated turns are legal; rotation defects must be derived from wrapped per-frame
    // deltas, not from the absolute serialized value.
    boolean assigned = Boolean.parseBoolean(field(event, "assigned"));
    if (assigned && state.lastInputAt >= 0L && event.sessionTick() - state.lastInputAt > 1L) {
      if (!state.tickGapActive) {
        add(defects, keys, new Defect(
            "AI_TICK_GAP", "CRITICAL", event.hunterId(), event.sequence(),
            "Assigned match Hunter skipped "
                + (event.sessionTick() - state.lastInputAt - 1L)
                + " server ticks before the next INPUT"));
      }
      state.tickGapActive = true;
    } else {
      state.tickGapActive = false;
    }
    state.lastInputAt = event.sessionTick();
    Vec3 position = vector(field(event, "position"));
    if (position == null) return;
    inspectObjectiveInteraction(event, state, position, defects, keys);
    if (state.lastPosition != null && state.movementExpected
        && distanceSquared(position, state.lastPosition) < 0.01D) state.stalledTicks++;
    else state.stalledTicks = 0;
    if (state.stalledTicks == 40)
      add(defects, keys, new Defect(
          "MOVEMENT_STALL", "HIGH", event.hunterId(), event.sequence(),
          "Hunter requested movement but position did not change for 40 input frames"));
    if (state.pathLoopMovementExpected && state.lastMovementTarget != null) {
      double movementTargetDistance = Math.sqrt(distanceSquared(position, state.lastMovementTarget));
      if (state.lastMovementTargetDistance != null
          && state.lastMovementTargetDistance - movementTargetDistance < 0.05D) {
        state.movementTargetStallTicks++;
      } else {
        state.movementTargetStallTicks = 0;
      }
      state.lastMovementTargetDistance = movementTargetDistance;
      String cell = cell(position);
      state.recentCells.addLast(cell);
      state.cellVisits.merge(cell, 1, Integer::sum);
      if (state.recentCells.size() > 120) {
        String expired = state.recentCells.removeFirst();
        state.cellVisits.computeIfPresent(expired, (ignored, count) -> count <= 1 ? null : count - 1);
      }
      if (state.cellVisits.getOrDefault(cell, 0) == 8 && state.movementTargetStallTicks > 20)
        add(defects, keys, new Defect(
            "PATH_LOOP", "HIGH", event.hunterId(), event.sequence(),
            "Hunter revisited two-block cell " + cell
                + " eight times without progress toward the active movement target"));
    } else {
      state.recentCells.clear();
      state.cellVisits.clear();
      state.movementTargetStallTicks = 0;
      state.lastMovementTargetDistance = null;
    }
    state.lastPosition = position;
  }

  private static void inspectObjectiveInteraction(
      Event event,
      HunterState state,
      Vec3 position,
      List<Defect> defects,
      Set<String> keys) {
    Vec3 objective = vector(field(event, "objective"));
    Double publishedRange = number(field(event, "bossInteractionRange"));
    double range = publishedRange == null ? 5.0D : publishedRange;
    boolean eligible = "UNBANISHED".equals(field(event, "bounty"))
        && objective != null
        && Double.isFinite(range)
        && range > 0.0D
        && distanceSquared(position, objective) <= range * range
        && !state.visible;
    if (event.sessionTick() <= state.bossInteractionSubmittedUntil) {
      state.bossInteractionClearTicks = 0;
      state.bossInteractionReported = false;
      return;
    }
    if (!eligible) {
      state.bossInteractionClearTicks = 0;
      state.bossInteractionReported = false;
      return;
    }
    state.bossInteractionClearTicks++;
    if (state.bossInteractionClearTicks >= 20 && !state.bossInteractionReported) {
      add(
          defects,
          keys,
          new Defect(
              "OBJECTIVE_INTERACTION_NOT_ATTEMPTED",
              "CRITICAL",
              event.hunterId(),
              event.sequence(),
              "Hunter remained inside the published Boss interaction range for 20 clear ticks "
                  + "without submitting START_BANISH; distance="
                  + String.format(
                      Locale.ROOT, "%.2f", Math.sqrt(distanceSquared(position, objective)))
                  + " range="
                  + String.format(Locale.ROOT, "%.2f", range)));
      state.bossInteractionReported = true;
    }
  }

  private static void inspectCombat(
      Event event, HunterState state, List<Defect> defects, Set<String> keys) {
    state.combatState = field(event, "state");
    state.combatReason = field(event, "reason");
    String fireBlockReason = field(event, "fireBlockReason");
    if (!fireBlockReason.isBlank()) state.lastFireBlockReason = fireBlockReason;
    if (state.visible
        && state.lastDamageEvidenceAt != Long.MIN_VALUE
        && event.sessionTick() >= state.lastDamageEvidenceAt
        && event.sessionTick() - state.lastDamageEvidenceAt <= 200L
        && ("STABILIZE".equals(state.combatState)
            || "BURST".equals(state.combatState)
            || "RECOVER".equals(state.combatState))) {
      state.damageContactObserved = true;
    }
    String target = field(event, "target");
    if (!target.isBlank() && state.confirmedDeadTargets.contains(target))
      add(
          defects,
          keys,
          new Defect(
              "DEAD_TARGET_RETENTION",
              "CRITICAL",
              event.hunterId(),
              event.sequence(),
              "Combat controller retained confirmed-dead target " + target));
    Double aimError = number(field(event, "aimError"));
    if (state.visible
        && "TRACK".equals(state.combatState)
        && aimError != null
        && aimError > 3.5D) {
      boolean unchanged =
          state.lastAimError != null && Math.abs(state.lastAimError - aimError) <= 0.10D;
      if (!unchanged || state.lastCombatAt < 0L) {
        state.aimStagnantSince = event.sessionTick();
        state.aimStagnationReported = false;
      } else if (!state.aimStagnationReported
          && event.sessionTick() - state.aimStagnantSince >= 20L) {
        add(defects, keys, new Defect(
            "AIM_NOT_CONVERGING", "CRITICAL", event.hunterId(), event.sequence(),
            "Combat remained in TRACK with unchanged aim error=" + aimError + " for 20 ticks"));
        state.aimStagnationReported = true;
      }
      state.lastAimError = aimError;
    } else {
      state.lastAimError = null;
      state.aimStagnantSince = -1L;
      state.aimStagnationReported = false;
    }
    state.lastCombatAt = event.sessionTick();
  }

  private static void inspectPerception(
      Event event, HunterState state, List<Defect> defects, Set<String> keys) {
    String visibleField = field(event, "visible");
    boolean visible = !visibleField.isBlank()
        && !"null".equals(visibleField)
        && !"none".equals(visibleField);
    String contact = field(event, "contact");
    String diagnostic = field(event, "diagnostic");
    if ("ACQUIRING".equals(contact) && diagnostic.contains("legal-sight")) {
      state.pendingLegalContactAt = event.sessionTick();
      state.pendingLegalContactSequence = event.sequence();
      state.pendingContactLossReported = false;
    } else if ("CONFIRMED".equals(contact)) {
      state.pendingLegalContactAt = Long.MIN_VALUE;
      state.pendingContactLossReported = false;
    } else if ("NONE".equals(contact)
        && state.pendingLegalContactAt != Long.MIN_VALUE
        && event.sessionTick() - state.pendingLegalContactAt <= 4L
        && !legitimatePendingContactLoss(event)
        && !state.pendingContactLossReported) {
      add(defects, keys, new Defect(
          "PENDING_VISUAL_CONFIRMATION_LOST", "HIGH", event.hunterId(), event.sequence(),
          "A current legal sight entered ACQUIRING but was discarded before bounded confirmation; "
              + "firstSequence=" + state.pendingLegalContactSequence));
      state.pendingContactLossReported = true;
      state.pendingLegalContactAt = Long.MIN_VALUE;
    }
    if (visible) {
      state.visibleStreak++;
      state.visibleFrames++;
      if (state.visibleStartedAt < 0L) state.visibleStartedAt = event.sessionTick();
      if (state.visibleStreak == 240)
        add(defects, keys, new Defect(
            "LONG_ENGAGEMENT", "MEDIUM", event.hunterId(), event.sequence(),
            "Visible-contact engagement reached 240 ticks without ending"));
    } else {
      state.visibleStreak = 0;
      state.visibleStartedAt = -1L;
    }
    state.visible = visible;
  }

  private static void inspectFrame(
      Event event, HunterState state, List<Defect> defects, Set<String> keys) {
    state.lastMovement = field(event, "movement");
    state.lastWeapon = field(event, "weapon");
    state.movementExpected = !state.lastMovement.isBlank() && !"HOLD".equals(state.lastMovement);
    String owners = field(event, "owners");
    boolean pathDrivenOwner = owners.contains("MOVEMENT=strategic-plan")
        || owners.contains("MOVEMENT=objective-recovery")
        || owners.contains("MOVEMENT=path-recovery")
        || owners.contains("MOVEMENT=safe-reset");
    Vec3 movementTarget = vector(field(event, "target"));
    if (!pathDrivenOwner || !state.movementExpected || movementTarget == null) {
      state.pathLoopMovementExpected = false;
      state.lastMovementTarget = null;
      state.lastMovementTargetDistance = null;
      state.movementTargetStallTicks = 0;
    } else {
      if (state.lastMovementTarget == null
          || distanceSquared(state.lastMovementTarget, movementTarget) > 0.25D) {
        state.lastMovementTargetDistance = null;
        state.movementTargetStallTicks = 0;
        state.recentCells.clear();
        state.cellVisits.clear();
      }
      state.pathLoopMovementExpected = true;
      state.lastMovementTarget = movementTarget;
    }
    state.objectiveMovementExpected = state.movementExpected
        && owners.contains("MOVEMENT=strategic-plan")
        && objectiveDirectedPlan(state.lastPlan);
    boolean stalledInterceptPush = "INTERCEPT_APPROACH:PUSH".equals(state.lastPlan)
        && "HOLD".equals(state.lastMovement)
        && !state.visible;
    if (stalledInterceptPush) {
      if (state.interceptHoldStartedAt == Long.MIN_VALUE)
        state.interceptHoldStartedAt = event.sessionTick();
      if (!state.interceptHoldReported
          && event.sessionTick() - state.interceptHoldStartedAt >= 80L) {
        add(defects, keys, new Defect(
            "INTERCEPT_HOLD_STALL", "CRITICAL", event.hunterId(), event.sequence(),
            "INTERCEPT_APPROACH + PUSH remained stationary without contact for 80 ticks"));
        state.interceptHoldReported = true;
      }
    } else {
      state.interceptHoldStartedAt = Long.MIN_VALUE;
      state.interceptHoldReported = false;
    }
    if ("START_BANISH".equals(field(event, "interaction"))) {
      state.bossInteractionClearTicks = 0;
      state.bossInteractionReported = false;
      state.bossInteractionSubmittedUntil = event.sessionTick() + 40L;
    }
    if (state.pendingPlanId != null && !state.pendingPlanExecuted) {
      boolean matchingPlan = event.planId() != null && event.planId().equals(state.pendingPlanId);
      if (matchingPlan && !field(event, "owners").isBlank() && !"{}".equals(field(event, "owners"))) {
        state.pendingPlanExecuted = true;
        state.executedPlanCount++;
      } else if (event.sessionTick() - state.lastPlanAt > 20L) {
        add(defects, keys, new Defect(
            "PLAN_NOT_EXECUTED", "HIGH", event.hunterId(), event.sequence(),
            "Committed plan produced no matching action frame within 20 ticks"));
      }
    }
    boolean combatOwnsView = field(event, "owners").contains("VIEW=combat-controller")
        || field(event, "owners").contains("VIEW=visible-combat")
        || field(event, "owners").contains("VIEW=damage-reaction")
        || field(event, "owners").contains("VIEW=threat-awareness");
    boolean deliberatelyDeferred = state.directorDeferredAt == event.sessionTick();
    if (state.visible && !combatOwnsView && !deliberatelyDeferred) {
      if (!state.aimOverrideActive)
        add(defects, keys, new Defect(
            "COMBAT_AIM_OVERRIDDEN", "CRITICAL", event.hunterId(), event.sequence(),
            "A visible target existed but the final view owner was not combat-controller"));
      state.aimOverrideActive = true;
    } else {
      state.aimOverrideActive = false;
    }
    boolean fireRequested = "ADS_FIRE".equals(state.lastWeapon) || "HIP_FIRE".equals(state.lastWeapon);
    if (state.visible && fireRequested) {
      if (state.fireRequestedAt < 0L) state.fireRequestedAt = event.sessionTick();
    } else {
      state.fireRequestedAt = -1L;
      if (!state.visible || state.lastShotAt >= 0L) state.seenNotFiredReported = false;
    }
  }

  private static void inspectActuator(
      Event event, HunterState state, List<Defect> defects, Set<String> keys) {
    String movementResult = field(event, "movementResult");
    boolean sprintRequested = Boolean.parseBoolean(field(event, "sprintRequested"));
    boolean sprinting = Boolean.parseBoolean(field(event, "sprinting"));
    Double horizontalSpeed = number(field(event, "horizontalSpeed"));
    String movementProgress = field(event, "movementProgress");
    String interactionResult = field(event, "interaction");
    if (interactionResult.contains("in-progress") || interactionResult.contains("accepted")) {
      state.bossInteractionSubmittedUntil = event.sessionTick() + 120L;
      state.bossInteractionClearTicks = 0;
      state.bossInteractionReported = false;
    } else if (interactionResult.contains("rejected")
        || interactionResult.contains("interaction-timeout")) {
      state.bossInteractionSubmittedUntil = Long.MIN_VALUE;
    }
    boolean sprintShouldBeActive = sprintRequested
        && ("PATH_ACCEPTED".equals(movementProgress)
            || "PROGRESSING".equals(movementProgress)
            || "RECOVERING".equals(movementProgress))
        && !movementResult.contains("arrived")
        && !movementResult.contains("hold")
        && !movementResult.contains("unavailable")
        && !movementResult.contains("rejected")
        && !movementResult.contains("stalled");
    if (sprintShouldBeActive && !sprinting) state.sprintMismatchTicks++;
    else state.sprintMismatchTicks = 0;
    if (state.sprintMismatchTicks == 10)
      add(
          defects,
          keys,
          new Defect(
              "SPRINT_EXECUTION_MISMATCH",
              "HIGH",
              event.hunterId(),
              event.sequence(),
              "Sprint was requested but the entity did not remain in sprint state for 10 ticks"));
    if (sprintRequested
        && movementResult.contains("following")
        && horizontalSpeed != null
        && horizontalSpeed < 0.03D) state.falseNavigationTicks++;
    else state.falseNavigationTicks = 0;
    if (state.falseNavigationTicks == 40)
      add(
          defects,
          keys,
          new Defect(
              "NAVIGATION_FALSE_SUCCESS",
              "HIGH",
              event.hunterId(),
              event.sequence(),
              "Navigation reported following while sprint speed stayed below 0.03 for 40 ticks"));
    boolean failure = movementResult.contains("unavailable") || movementResult.contains("rejected")
        || movementResult.contains("no-progress") || movementResult.contains("stalled")
        || movementResult.contains("degenerate-path")
        || movementResult.contains("validated-route-missing");
    if (failure
        || movementResult.contains("arrived")
        || movementResult.contains("hold")) {
      state.movementExpected = false;
      state.objectiveMovementExpected = false;
      state.pathLoopMovementExpected = false;
      state.stalledTicks = 0;
      state.recentCells.clear();
      state.cellVisits.clear();
    } else if (movementResult.contains("following")
        || movementResult.contains("local-evade-")
        || movementResult.contains("safe-drop")
        || movementResult.contains("replanned")) {
      state.movementExpected = true;
    }
    if (failure) {
      Vec3 failureTarget = vector(field(event, "failureTarget"));
      if (failureTarget != null) {
        String region = failureRegion(failureTarget);
        RegionFailure previous = state.failedRouteRegions.get(region);
        int repeats = previous == null || event.sessionTick() - previous.lastAt() > 400L
            ? 1
            : previous.count() + 1;
        state.failedRouteRegions.put(region, new RegionFailure(repeats, event.sessionTick()));
        if (repeats == 3)
          add(defects, keys, new Defect(
              "FAILED_ROUTE_REGION_RETRY", "HIGH", event.hunterId(), event.sequence(),
              "Three route failures targeted the same eight-block region " + region));
      }
      if (!state.pathFailureActive)
        add(defects, keys, new Defect(
            "PATH_EXECUTION_FAILURE", "HIGH", event.hunterId(), event.sequence(),
            movementResult + " target=" + field(event, "failureTarget")
                + " routeId=" + event.routeId()));
      state.pathFailureActive = true;
    } else if (movementResult.contains("following")
        || movementResult.contains("local-evade-")
        || movementResult.contains("arrived")
        || movementResult.contains("safe-drop")) {
      state.pathFailureActive = false;
    }

    String weaponResult = field(event, "weapon");
    if (weaponResult.contains("fired")) {
      state.physicalFireBlockActive = false;
      state.nativeReadySince = Long.MIN_VALUE;
      state.lastFireBlockReason = "NONE";
      return;
    }
    if (isPhysicalFireBlock(weaponResult)) {
      state.physicalFireBlockActive = true;
      state.nativeReadySince = Long.MIN_VALUE;
      state.fireRequestedAt = -1L;
      state.seenNotFiredReported = false;
      state.lastFireBlockReason = normalizedPhysicalFireBlock(weaponResult);
      return;
    }
    if (state.physicalFireBlockActive) {
      state.physicalFireBlockActive = false;
      // The two-tick follow-up watchdog applies only after a real shot. Initial ADS can complete
      // before a large physical turn or the close-range FirstTriggerGate, both of which are legal.
      state.nativeReadySince = state.visibleStartedAt >= 0L
              && state.lastShotAt >= state.visibleStartedAt
          ? event.sessionTick()
          : Long.MIN_VALUE;
    }
    boolean fireRequested = "ADS_FIRE".equals(state.lastWeapon)
        || "HIP_FIRE".equals(state.lastWeapon);
    if (state.nativeReadySince != Long.MIN_VALUE
        && state.visible
        && event.sessionTick() - state.nativeReadySince > 2L
        && state.lastShotAt < state.nativeReadySince) {
      add(defects, keys, new Defect(
          "SEEN_NOT_FIRED", "HIGH", event.hunterId(), event.sequence(),
          "TaCZ physical block cleared, but no follow-up shot was submitted within two ticks; "
              + "fireRequested=" + fireRequested
              + " combatState=" + state.combatState
              + " fireBlockReason=" + state.lastFireBlockReason));
      state.seenNotFiredReported = true;
      state.nativeReadySince = Long.MIN_VALUE;
    }
  }

  private static void inspectLifecycle(Event event, HunterState state) {
    String phase = field(event, "phase");
    if ("target-invalidated".equals(phase)) {
      String target = field(event, "target");
      if (!target.isBlank()) state.confirmedDeadTargets.add(target);
      return;
    }
    if ("death".equals(phase)
        || "removed".equals(phase)
        || "seat-revoked".equals(phase)
        || "seat-desynchronized".equals(phase)) {
      state.lifecycleEnded = true;
      state.lastDamageEvidenceAt = Long.MIN_VALUE;
      state.damageContactObserved = false;
      state.damageResponseReported = true;
      state.bossInteractionSubmittedUntil = Long.MIN_VALUE;
      return;
    }
    if ("spawned".equals(phase) || "seat".equals(phase)) state.lifecycleEnded = false;
  }

  private static boolean isPhysicalFireBlock(String weaponResult) {
    if (weaponResult == null || weaponResult.isBlank()) return false;
    String normalized = weaponResult.toUpperCase(Locale.ROOT).replace('-', '_');
    return normalized.contains("NATIVE_COOLDOWN")
        || normalized.contains("NEED_BOLT")
        || normalized.contains("IS_BOLTING")
        || normalized.contains("ADS_TRANSITION")
        // Schema v2/v3 used these shorter TaCZ actuator strings.
        || normalized.equals("COOLDOWN")
        || normalized.equals("BOLTING");
  }

  private static String normalizedPhysicalFireBlock(String weaponResult) {
    String normalized = weaponResult == null
        ? "UNKNOWN"
        : weaponResult.toUpperCase(Locale.ROOT).replace('-', '_');
    if (normalized.contains("NATIVE_COOLDOWN") || normalized.equals("COOLDOWN"))
      return "NATIVE_COOLDOWN";
    if (normalized.contains("NEED_BOLT")) return "NEED_BOLT";
    if (normalized.contains("IS_BOLTING") || normalized.equals("BOLTING")) return "IS_BOLTING";
    if (normalized.contains("ADS_TRANSITION")) return "ADS_TRANSITION";
    return normalized;
  }

  private static void inspectOutcome(
      Event event, HunterState state, List<Defect> defects, Set<String> keys) {
    if ("confirmed-kill".equals(field(event, "result"))) {
      String target = field(event, "target");
      if (!target.isBlank()) state.confirmedDeadTargets.add(target);
    }
    Double objectiveDistance = number(field(event, "objectiveDistance"));
    if (objectiveDistance != null) {
      if (state.firstObjectiveDistance == null) {
        state.firstObjectiveDistance = objectiveDistance;
        state.firstObjectiveAt = event.sessionTick();
      }
      if (state.lastObjectiveDistance != null
          && state.objectiveMovementExpected
          && state.lastObjectiveDistance - objectiveDistance < 0.05D) state.objectiveStallTicks++;
      else state.objectiveStallTicks = 0;
      if (state.objectiveStallTicks == 200)
        add(defects, keys, new Defect(
            "OBJECTIVE_NO_PROGRESS", "HIGH", event.hunterId(), event.sequence(),
            "Objective distance did not improve while movement was requested for 200 ticks"));
      state.lastObjectiveDistance = objectiveDistance;
      state.lastObjectiveAt = event.sessionTick();
    }
    Double healthDelta = number(field(event, "healthDelta"));
    if (healthDelta != null && healthDelta < 0.0D) state.damageTaken -= healthDelta;
    state.updatePrediction(event, healthDelta == null ? 0.0D : healthDelta, defects, keys);
  }

  private static Analysis enrichWithPlayerComparison(Analysis base, List<Event> events) {
    Map<Long, Event> bySequence = new HashMap<>();
    events.forEach(event -> bySequence.put(event.sequence(), event));
    List<Defect> defects = new ArrayList<>();
    for (Defect defect : base.defects()) {
      Event evidence = bySequence.get(defect.sequence());
      defects.add(
          evidence == null
              ? defect
              : new Defect(
                  defect.type(),
                  defect.severity(),
                  defect.hunterId(),
                  defect.sequence(),
                  defect.description(),
                  firstNonBlank(evidence.actorId(), defect.actorId(), defect.hunterId()),
                  firstNonBlank(evidence.counterpartActorId(), defect.counterpartActorId()),
                  firstNonBlank(evidence.matchId(), defect.matchId()),
                  firstNonBlank(evidence.encounterId(), defect.encounterId()),
                  defect.evidenceSequences()));
    }

    Map<String, List<Event>> frames = actorFrames(events);
    List<EncounterWindow> encounters = encounterWindows(events);
    Map<String, List<Event>> actorEvents = eventsByActor(events);
    Map<String, Set<Long>> combatTicks = combatTicks(events);
    Set<String> keys = new HashSet<>();
    for (Defect defect : defects) keys.add(defect.type() + ":" + defect.actorId() + ":" + defect.encounterId());
    for (EncounterWindow encounter : encounters) {
      List<Event> player = framesIn(frames.get(encounter.playerActorId), encounter);
      List<Event> hunter = framesIn(frames.get(encounter.hunterActorId), encounter);
      MotionStats playerMotion = motionStats(player);
      MotionStats hunterMotion = motionStats(hunter);
      if (playerMotion.samples >= 10
          && hunterMotion.samples >= 10
          && hunterMotion.averageSpeed > Math.max(0.30D, playerMotion.averageSpeed * 1.25D)) {
        addComparisonDefect(
            defects,
            keys,
            new Defect(
                "AI_MOVE_TOO_FAST",
                "HIGH",
                encounter.hunterActorId,
                encounter.startSequence,
                String.format(
                    Locale.ROOT,
                    "Encounter AI average horizontal speed %.3f exceeded player %.3f by more than 25%%",
                    hunterMotion.averageSpeed,
                    playerMotion.averageSpeed),
                encounter.hunterActorId,
                encounter.playerActorId,
                encounter.matchId,
                encounter.id,
                evidence(player, hunter)));
      }

      Event exposedCombat =
          hunter.stream()
              .filter(event -> number(field(event, "exposure")) != null)
              .filter(event -> number(field(event, "exposure")) >= 0.75D)
              .filter(event -> isCombatTick(combatTicks, event.sessionTick(), encounter.hunterActorId))
              .findFirst()
              .orElse(null);
      if (exposedCombat != null) {
        addComparisonDefect(
            defects,
            keys,
            new Defect(
                "AI_OPEN_FIELD_COMBAT",
                "HIGH",
                encounter.hunterActorId,
                exposedCombat.sequence(),
                "AI remained in a highly exposed position during a player encounter",
                encounter.hunterActorId,
                encounter.playerActorId,
                encounter.matchId,
                encounter.id,
                List.of(exposedCombat.sequence(), encounter.startSequence)));
      }

      long hunterShots =
          actorEvents.getOrDefault(encounter.hunterActorId, List.of()).stream()
              .filter(event -> inWindow(event, encounter))
              .filter(event -> "ACTUATOR_RESULT".equals(event.type()) && field(event, "weapon").contains("fired"))
              .count();
      boolean hunterConfirmedVisual = actorEvents
          .getOrDefault(encounter.hunterActorId, List.of())
          .stream()
          .filter(event -> inWindow(event, encounter))
          .filter(event -> "PERCEPTION".equals(event.type()))
          .map(event -> field(event, "visible"))
          .anyMatch(visible -> !visible.isBlank()
              && !"none".equals(visible)
              && !"null".equals(visible));
      if (encounter.durationTicks() >= 40L && hunterConfirmedVisual && hunterShots == 0L) {
        String lastFireBlock = actorEvents.getOrDefault(encounter.hunterActorId, List.of()).stream()
            .filter(event -> inWindow(event, encounter))
            .filter(event -> "COMBAT".equals(event.type()))
            .map(event -> field(event, "fireBlockReason"))
            .filter(reason -> !reason.isBlank())
            .reduce((first, second) -> second)
            .orElse("not-recorded");
        addComparisonDefect(
            defects,
            keys,
            new Defect(
                "AI_ENCOUNTER_NO_FIRE",
                "HIGH",
                encounter.hunterActorId,
                encounter.startSequence,
                "A player/Hunter encounter remained active for at least 40 ticks without a Hunter shot; "
                    + "lastFireBlockReason=" + lastFireBlock,
                encounter.hunterActorId,
                encounter.playerActorId,
                encounter.matchId,
                encounter.id,
                List.of(encounter.startSequence, encounter.endSequence)));
      }
    }
    Event slowTelemetry = events.stream()
        .filter(event -> "PERFORMANCE".equals(event.type()))
        .filter(event -> value(field(event, "playerTelemetryAverageMicros")) > 1_500.0D
            || value(field(event, "playerTelemetryP95Micros")) > 3_000.0D)
        .findFirst()
        .orElse(null);
    if (slowTelemetry != null) {
      addComparisonDefect(
          defects,
          keys,
          new Defect(
              "PLAYER_MONITOR_BUDGET_EXCEEDED",
              "HIGH",
              null,
              slowTelemetry.sequence(),
              "Player monitoring exceeded the 1.5ms average or 3ms P95 budget",
              "monitor",
              null,
              slowTelemetry.matchId(),
              null,
              List.of(slowTelemetry.sequence())));
    }

    return new Analysis(
        base.schemaVersion(),
        base.eventCount(),
        base.firstSequence(),
        base.lastSequence(),
        base.sequenceGaps(),
        base.planCount(),
        base.shotCount(),
        base.predictionCount(),
        base.uniquePlanSignatures(),
        base.executedPlanCount(),
        base.planExecutionRate(),
        base.objectiveProgressPerTick(),
        base.shotsPerVisibleFrame(),
        base.predictionMeanError(),
        base.behaviorDiversity(),
        base.averageCoordinatorMicros(),
        base.p95CoordinatorMicros(),
        base.hunters(),
        defects);
  }

  private static String playerBehaviorSummary(List<Event> events) {
    StringBuilder text = new StringBuilder("# Player Behavior Summary\n\n");
    Map<String, List<Event>> frames = actorFrames(events);
    List<EncounterWindow> encounters = encounterWindows(events);
    List<String> players =
        events.stream()
            .filter(event -> "PLAYER".equals(event.actorKind()))
            .map(Event::actorId)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .sorted()
            .toList();
    text.append("- Players: ").append(players.size()).append('\n');
    text.append("- Player/Hunter encounters: ").append(encounters.size()).append("\n\n");
    for (String actor : players) {
      List<Event> actorFrames = frames.getOrDefault(actor, List.of());
      MotionStats motion = motionStats(actorFrames);
      List<BehaviorSegment> segments = new ArrayList<>(inferPlayerBehavior(actorFrames));
      segments.addAll(inferEncounterBehavior(actor, events, frames, encounters));
      text.append("## ").append(actor).append("\n\n");
      text.append(String.format(
          Locale.ROOT,
          "Frames=%d, normal movement samples=%d, average/max horizontal speed=%.3f/%.3f, sprint ratio=%.3f, jump ratio=%.3f.\n\n",
          actorFrames.size(), motion.samples, motion.averageSpeed, motion.maximumSpeed,
          motion.sprintRatio, motion.jumpRatio));
      if (segments.isEmpty()) text.append("No behavior segment met the minimum evidence threshold.\n\n");
      for (BehaviorSegment segment : segments) {
        text.append("- ").append(segment.behavior)
            .append(" confidence=").append(String.format(Locale.ROOT, "%.2f", segment.confidence))
            .append(" ticks=").append(segment.duration)
            .append(" evidence=").append(segment.evidenceSequences).append('\n');
      }
      if (!segments.isEmpty()) text.append('\n');
    }
    return text.toString();
  }

  private static String aiPlayerComparison(List<Event> events, Analysis analysis) {
    Map<String, List<Event>> frames = actorFrames(events);
    List<Event> playerFrames = frames.entrySet().stream()
        .filter(entry -> entry.getKey().startsWith("player-"))
        .flatMap(entry -> entry.getValue().stream()).toList();
    List<Event> hunterFrames = frames.entrySet().stream()
        .filter(entry -> entry.getKey().startsWith("hunter-"))
        .flatMap(entry -> entry.getValue().stream()).toList();
    MotionStats players = motionStats(playerFrames);
    MotionStats hunters = motionStats(hunterFrames);
    double telemetryAverage = events.stream()
        .filter(event -> "PERFORMANCE".equals(event.type()))
        .mapToDouble(event -> value(field(event, "playerTelemetryAverageMicros")))
        .filter(value -> value > 0.0D)
        .average().orElse(0.0D);
    double telemetryP95 = events.stream()
        .filter(event -> "PERFORMANCE".equals(event.type()))
        .mapToDouble(event -> value(field(event, "playerTelemetryP95Micros")))
        .max().orElse(0.0D);
    StringBuilder text = new StringBuilder("# AI / Player Comparison\n\n");
    text.append("Only recorded observations are compared; this report does not modify runtime behavior.\n\n");
    text.append(String.format(
        Locale.ROOT,
        "Player monitor rolling average/max-P95: %.1f/%.1f us.\n\n",
        telemetryAverage,
        telemetryP95));
    text.append("| Metric | Players | Hunters |\n| --- | ---: | ---: |\n");
    text.append(String.format(Locale.ROOT, "| Normal movement samples | %d | %d |%n", players.samples, hunters.samples));
    text.append(String.format(Locale.ROOT, "| Average horizontal speed | %.3f | %.3f |%n", players.averageSpeed, hunters.averageSpeed));
    text.append(String.format(Locale.ROOT, "| Maximum horizontal speed | %.3f | %.3f |%n", players.maximumSpeed, hunters.maximumSpeed));
    text.append(String.format(Locale.ROOT, "| Sprint ratio | %.3f | %.3f |%n", players.sprintRatio, hunters.sprintRatio));
    text.append(String.format(Locale.ROOT, "| Jump ratio | %.3f | %.3f |%n", players.jumpRatio, hunters.jumpRatio));
    text.append("\n## Encounter Findings\n\n");
    List<Defect> comparison = analysis.defects().stream()
        .filter(defect -> defect.encounterId() != null)
        .toList();
    if (comparison.isEmpty()) text.append("No encounter-specific defect crossed an automatic threshold.\n");
    for (Defect defect : comparison) {
      text.append("- [").append(defect.severity()).append("] ").append(defect.type())
          .append(" actor=").append(defect.actorId())
          .append(" counterpart=").append(defect.counterpartActorId())
          .append(" match=").append(defect.matchId())
          .append(" encounter=").append(defect.encounterId())
          .append(" evidence=").append(defect.evidenceSequences())
          .append(": ").append(defect.description()).append('\n');
    }
    text.append("\n## Code Areas To Inspect\n\n")
        .append("- Movement speed, sprint and jump differences: `PlayerMotionController` and `EntityActuator`.\n")
        .append("- No-fire or delayed-fire encounters: `CombatController`, `CombatAimLock` and `HunterWeaponAdapter`.\n")
        .append("- Exposed combat and delayed cover use: `CombatCoverPolicy`, `TacticalMap` and `RecoveryController`.\n")
        .append("- Pursuit and target retention: `HunterEngagementPolicy`, `ContactSet` and `EngagementState`.\n");
    return text.toString();
  }

  private static Map<String, List<Event>> actorFrames(List<Event> events) {
    Map<String, Map<Long, Event>> byActorTick = new LinkedHashMap<>();
    for (Event event : events) {
      if (!("PLAYER_FRAME".equals(event.type()) || "INPUT".equals(event.type()))) continue;
      String actor = firstNonBlank(event.actorId(), event.hunterId());
      if (actor == null) continue;
      Map<Long, Event> ticks = byActorTick.computeIfAbsent(actor, ignored -> new LinkedHashMap<>());
      Event previous = ticks.get(event.sessionTick());
      if (previous == null || !"pre-roll".equals(field(event, "sampleMode"))) ticks.put(event.sessionTick(), event);
    }
    Map<String, List<Event>> result = new LinkedHashMap<>();
    byActorTick.forEach((actor, ticks) -> result.put(actor, ticks.values().stream()
        .sorted(Comparator.comparingLong(Event::sessionTick)).toList()));
    return result;
  }

  private static Map<String, List<Event>> eventsByActor(List<Event> events) {
    Map<String, List<Event>> result = new HashMap<>();
    for (Event event : events) {
      String actor = firstNonBlank(event.actorId(), event.hunterId());
      if (actor != null) result.computeIfAbsent(actor, ignored -> new ArrayList<>()).add(event);
    }
    return result;
  }

  private static Map<String, Set<Long>> combatTicks(List<Event> events) {
    Map<String, Set<Long>> result = new HashMap<>();
    for (Event event : events) {
      if (!("COMBAT".equals(event.type()) || "ACTUATOR_RESULT".equals(event.type()))) continue;
      String actor = firstNonBlank(event.actorId(), event.hunterId());
      if (actor != null) result.computeIfAbsent(actor, ignored -> new HashSet<>()).add(event.sessionTick());
    }
    return result;
  }

  private static List<EncounterWindow> encounterWindows(List<Event> events) {
    Map<String, EncounterWindowBuilder> active = new LinkedHashMap<>();
    List<EncounterWindow> result = new ArrayList<>();
    for (Event event : events) {
      if (!"ENCOUNTER".equals(event.type()) || event.encounterId() == null) continue;
      if ("start".equals(field(event, "phase"))) {
        active.put(event.encounterId(), new EncounterWindowBuilder(event));
      } else if ("end".equals(field(event, "phase"))) {
        EncounterWindowBuilder builder = active.remove(event.encounterId());
        if (builder != null) result.add(builder.finish(event));
      }
    }
    for (EncounterWindowBuilder builder : active.values()) result.add(builder.finish(null));
    return List.copyOf(result);
  }

  private static List<Event> framesIn(List<Event> frames, EncounterWindow encounter) {
    if (frames == null) return List.of();
    return frames.stream().filter(event -> inWindow(event, encounter)).toList();
  }

  private static boolean inWindow(Event event, EncounterWindow encounter) {
    return event.sessionTick() >= encounter.startedAt && event.sessionTick() <= encounter.endedAt;
  }

  private static MotionStats motionStats(List<Event> frames) {
    double total = 0.0D;
    double maximum = 0.0D;
    int samples = 0;
    int sprint = 0;
    int jump = 0;
    boolean previousJumped = false;
    long previousSampleTick = Long.MIN_VALUE;
    Vec3 previous = null;
    long previousTick = -1L;
    for (Event event : frames) {
      if (!normalFrame(event)) continue;
      Double speed = number(field(event, "horizontalSpeed"));
      Vec3 position = vector(field(event, "position"));
      if (speed == null && position != null && previous != null && event.sessionTick() > previousTick) {
        speed = Math.sqrt(distanceSquared(position, previous)) / (event.sessionTick() - previousTick);
      }
      if (position != null) {
        previous = position;
        previousTick = event.sessionTick();
      }
      if (speed == null || !Double.isFinite(speed)) continue;
      total += speed;
      maximum = Math.max(maximum, speed);
      samples++;
      if (Boolean.parseBoolean(field(event, "sprinting"))
          || Boolean.parseBoolean(field(event, "sprint"))
          || "SPRINT".equals(field(event, "movement"))) sprint++;
      boolean jumped = Boolean.parseBoolean(field(event, "jumped"));
      if (jumped
          && (!previousJumped || previousSampleTick == Long.MIN_VALUE
              || event.sessionTick() - previousSampleTick > 2L)) jump++;
      previousJumped = jumped;
      previousSampleTick = event.sessionTick();
    }
    return new MotionStats(samples, samples == 0 ? 0.0D : total / samples, maximum,
        samples == 0 ? 0.0D : sprint / (double) samples,
        samples == 0 ? 0.0D : jump / (double) samples);
  }

  private static List<BehaviorSegment> inferPlayerBehavior(List<Event> frames) {
    List<BehaviorSegment> result = new ArrayList<>();
    addBehaviorRuns(
        result,
        frames,
        "TRANSIT",
        frame -> normalFrame(frame)
            && Boolean.parseBoolean(field(frame, "sprinting"))
            && value(field(frame, "horizontalSpeed")) >= 0.14D,
        20L,
        0.85D);
    addBehaviorRuns(
        result,
        frames,
        "OBSERVE",
        frame -> normalFrame(frame)
            && value(field(frame, "horizontalSpeed")) < 0.05D
            && !Boolean.parseBoolean(field(frame, "crouching"))
            && Boolean.parseBoolean(field(frame, "ads")),
        20L,
        0.70D);
    addWaitingRuns(result, frames);
    addRecoveryRuns(result, frames);
    return result;
  }

  private static void addBehaviorRuns(
      List<BehaviorSegment> result,
      List<Event> frames,
      String behavior,
      java.util.function.Predicate<Event> predicate,
      long minimumTicks,
      double baseConfidence) {
    int start = -1;
    for (int index = 0; index <= frames.size(); index++) {
      Event frame = index < frames.size() ? frames.get(index) : null;
      boolean matches = frame != null && predicate.test(frame);
      boolean discontinuity = matches
          && start >= 0
          && index > 0
          && frame.sessionTick() - frames.get(index - 1).sessionTick() > MAX_BEHAVIOR_FRAME_GAP;
      if (discontinuity) {
        emitBehaviorRun(result, frames, start, index - 1, behavior, minimumTicks, baseConfidence);
        start = index;
        continue;
      }
      if (matches && start < 0) start = index;
      if ((!matches || index == frames.size()) && start >= 0) {
        emitBehaviorRun(result, frames, start, index - 1, behavior, minimumTicks, baseConfidence);
        start = -1;
      }
    }
  }

  private static void emitBehaviorRun(
      List<BehaviorSegment> result,
      List<Event> frames,
      int start,
      int end,
      String behavior,
      long minimumTicks,
      double baseConfidence) {
    if (start < 0 || end < start) return;
    long duration = frames.get(end).sessionTick() - frames.get(start).sessionTick() + 1L;
    if (duration < minimumTicks) return;
    List<Event> segment = frames.subList(start, end + 1);
    result.add(new BehaviorSegment(
        behavior,
        clamp(baseConfidence + Math.min(0.15D, duration / 300.0D)),
        duration,
        List.of(segment.get(0).sequence(), segment.get(segment.size() - 1).sequence())));
  }

  private static void addWaitingRuns(List<BehaviorSegment> result, List<Event> frames) {
    int start = -1;
    for (int index = 0; index <= frames.size(); index++) {
      Event frame = index < frames.size() ? frames.get(index) : null;
      boolean matches = frame != null
          && normalFrame(frame)
          && Boolean.parseBoolean(field(frame, "crouching"))
          && value(field(frame, "horizontalSpeed")) < 0.035D;
      boolean discontinuity = matches
          && start >= 0
          && index > 0
          && frame.sessionTick() - frames.get(index - 1).sessionTick() > MAX_BEHAVIOR_FRAME_GAP;
      if (discontinuity) {
        emitWaitingRun(result, frames, start, index - 1);
        start = index;
        continue;
      }
      if (matches && start < 0) start = index;
      if ((!matches || index == frames.size()) && start >= 0) {
        emitWaitingRun(result, frames, start, index - 1);
        start = -1;
      }
    }
  }

  private static void emitWaitingRun(
      List<BehaviorSegment> result, List<Event> frames, int start, int end) {
    if (start < 0 || end < start) return;
    List<Event> segment = frames.subList(start, end + 1);
    long duration = segment.get(segment.size() - 1).sessionTick() - segment.get(0).sessionTick() + 1L;
    if (duration < 20L) return;
    boolean usefulCover = segment.stream().anyMatch(frame -> {
      Double hardCover = number(field(frame, "hardCover"));
      Double exposure = number(field(frame, "exposure"));
      return hardCover != null && hardCover >= 1.0D || exposure != null && exposure <= 0.55D;
    });
    boolean hasRetreat = segment.stream().anyMatch(frame -> value(field(frame, "escapeDirections")) >= 1.0D);
    boolean ambushEvidence = usefulCover && hasRetreat;
    result.add(new BehaviorSegment(
        ambushEvidence ? "WAITING_OR_AMBUSH" : "WAITING",
        clamp((ambushEvidence ? 0.70D : 0.58D) + Math.min(0.15D, duration / 300.0D)),
        duration,
        List.of(segment.get(0).sequence(), segment.get(segment.size() - 1).sequence())));
  }

  private static void addRecoveryRuns(List<BehaviorSegment> result, List<Event> frames) {
    Event firstEvidence = null;
    Event lastEvidence = null;
    long lastRecoveryAt = Long.MIN_VALUE;
    for (int index = 1; index <= frames.size(); index++) {
      boolean healing = false;
      Event previous = null;
      Event current = null;
      if (index < frames.size()) {
        previous = frames.get(index - 1);
        current = frames.get(index);
        Double previousHealth = number(field(previous, "health"));
        Double currentHealth = number(field(current, "health"));
        healing = previousHealth != null
            && currentHealth != null
            && currentHealth - previousHealth >= 0.5D
            && current.sessionTick() - previous.sessionTick() <= MAX_BEHAVIOR_FRAME_GAP;
      }
      if (healing && firstEvidence == null) {
        firstEvidence = previous;
        lastEvidence = current;
        lastRecoveryAt = current.sessionTick();
      } else if (healing && current.sessionTick() - lastRecoveryAt <= RECOVERY_MERGE_GAP) {
        lastEvidence = current;
        lastRecoveryAt = current.sessionTick();
      } else if (healing) {
        emitRecoveryRun(result, firstEvidence, lastEvidence);
        firstEvidence = previous;
        lastEvidence = current;
        lastRecoveryAt = current.sessionTick();
      } else if (firstEvidence != null
          && (current == null || current.sessionTick() - lastRecoveryAt > RECOVERY_MERGE_GAP)) {
        emitRecoveryRun(result, firstEvidence, lastEvidence);
        firstEvidence = null;
        lastEvidence = null;
        lastRecoveryAt = Long.MIN_VALUE;
      }
    }
  }

  private static void emitRecoveryRun(
      List<BehaviorSegment> result, Event firstEvidence, Event lastEvidence) {
    if (firstEvidence == null || lastEvidence == null) return;
    result.add(new BehaviorSegment(
        "RECOVER_OR_HEAL",
        0.90D,
        Math.max(1L, lastEvidence.sessionTick() - firstEvidence.sessionTick()),
        List.of(firstEvidence.sequence(), lastEvidence.sequence())));
  }

  private static List<BehaviorSegment> inferEncounterBehavior(
      String playerActor,
      List<Event> events,
      Map<String, List<Event>> frames,
      List<EncounterWindow> encounters) {
    List<BehaviorSegment> result = new ArrayList<>();
    Map<String, List<Event>> actorEvents = eventsByActor(events);
    for (EncounterWindow encounter : encounters) {
      if (!playerActor.equals(encounter.playerActorId)) continue;
      List<Event> player = framesIn(frames.get(playerActor), encounter);
      List<Event> hunter = framesIn(frames.get(encounter.hunterActorId), encounter);
      ApproachTrend trend = approachTrend(player, hunter);
      if (trend.samples >= 4
          && trend.startDistance - trend.endDistance >= 4.0D
          && trend.playerContribution >= 2.0D
          && trend.playerContribution > trend.hunterContribution + 0.5D) {
        result.add(new BehaviorSegment(
            "PURSUIT",
            clamp(0.60D + Math.min(0.30D, (trend.startDistance - trend.endDistance) / 30.0D)),
            encounter.durationTicks(),
            List.of(encounter.startSequence, encounter.endSequence)));
      } else if (trend.samples >= 4
          && trend.endDistance - trend.startDistance >= 4.0D
          && trend.playerContribution <= -2.0D) {
        result.add(new BehaviorSegment(
            "RETREAT",
            clamp(0.60D + Math.min(0.30D, (trend.endDistance - trend.startDistance) / 30.0D)),
            encounter.durationTicks(),
            List.of(encounter.startSequence, encounter.endSequence)));
      } else if (trend.samples >= 4 && traveledDistance(player) >= 8.0D) {
        result.add(new BehaviorSegment(
            "FLANK_OR_REPOSITION",
            0.55D,
            encounter.durationTicks(),
            List.of(encounter.startSequence, encounter.endSequence)));
      }
      List<Event> actions = actorEvents.getOrDefault(playerActor, List.of()).stream()
          .filter(event -> inWindow(event, encounter))
          .filter(event -> "PLAYER_ACTION".equals(event.type()) && "fire".equals(field(event, "action")))
          .filter(event -> fireBelongsToEncounter(event, encounter, encounters))
          .toList();
      if (actions.size() >= 3) {
        result.add(new BehaviorSegment(
            "SUPPRESSION_OR_ENGAGE",
            Math.min(0.95D, 0.65D + actions.size() * 0.03D),
            actions.get(actions.size() - 1).sessionTick() - actions.get(0).sessionTick() + 1L,
            actions.stream().limit(6).map(Event::sequence).toList()));
      }
    }
    return result;
  }

  private static boolean fireBelongsToEncounter(
      Event fire, EncounterWindow encounter, List<EncounterWindow> encounters) {
    if (fire.encounterId() != null && !fire.encounterId().isBlank()) {
      return encounter.id.equals(fire.encounterId());
    }
    if (fire.counterpartActorId() != null && !fire.counterpartActorId().isBlank()) {
      return encounter.hunterActorId.equals(fire.counterpartActorId());
    }
    long active = encounters.stream()
        .filter(candidate -> encounter.playerActorId.equals(candidate.playerActorId))
        .filter(candidate -> fire.sessionTick() >= candidate.startedAt && fire.sessionTick() <= candidate.endedAt)
        .count();
    return active == 1L;
  }

  private static ApproachTrend approachTrend(List<Event> first, List<Event> second) {
    Map<Long, Vec3> secondByTick = new HashMap<>();
    for (Event event : second) {
      Vec3 position = vector(field(event, "position"));
      if (position != null) secondByTick.put(event.sessionTick(), position);
    }
    double start = 0.0D;
    double end = 0.0D;
    double playerContribution = 0.0D;
    double hunterContribution = 0.0D;
    int samples = 0;
    Vec3 previousPlayer = null;
    Vec3 previousHunter = null;
    for (Event event : first) {
      Vec3 firstPosition = vector(field(event, "position"));
      Vec3 secondPosition = secondByTick.get(event.sessionTick());
      if (firstPosition == null || secondPosition == null) continue;
      double distance = Math.sqrt(distanceSquared(firstPosition, secondPosition));
      if (samples == 0) start = distance;
      if (previousPlayer != null && previousHunter != null) {
        double previousDistance = Math.sqrt(distanceSquared(previousPlayer, previousHunter));
        double playerOnlyDistance = Math.sqrt(distanceSquared(firstPosition, previousHunter));
        double hunterOnlyDistance = Math.sqrt(distanceSquared(previousPlayer, secondPosition));
        playerContribution += 0.5D
            * ((previousDistance - playerOnlyDistance) + (hunterOnlyDistance - distance));
        hunterContribution += 0.5D
            * ((previousDistance - hunterOnlyDistance) + (playerOnlyDistance - distance));
      }
      end = distance;
      previousPlayer = firstPosition;
      previousHunter = secondPosition;
      samples++;
    }
    return new ApproachTrend(samples, start, end, playerContribution, hunterContribution);
  }

  private static double traveledDistance(List<Event> frames) {
    Vec3 previous = null;
    double total = 0.0D;
    for (Event event : frames) {
      Vec3 current = vector(field(event, "position"));
      if (current != null && previous != null) total += Math.sqrt(distanceSquared(previous, current));
      if (current != null) previous = current;
    }
    return total;
  }

  private static double value(String raw) {
    Double parsed = number(raw);
    return parsed == null ? 0.0D : parsed;
  }

  private static boolean isCombatTick(
      Map<String, Set<Long>> combatTicks, long tick, String actorId) {
    Set<Long> ticks = combatTicks.getOrDefault(actorId, Set.of());
    for (long candidate = tick - 5L; candidate <= tick + 5L; candidate++) {
      if (ticks.contains(candidate)) return true;
    }
    return false;
  }

  private static boolean normalMovement(String flags) {
    if (flags == null || flags.isBlank() || "normal".equals(flags)) return true;
    return !(flags.contains("vehicle")
        || flags.contains("water")
        || flags.contains("ladder")
        || flags.contains("fall-flying")
        || flags.contains("falling")
        || flags.contains("knockback-window")
        || flags.contains("creative")
        || flags.contains("spectator")
        || flags.contains("teleport"));
  }

  private static boolean normalFrame(Event frame) {
    return frame != null
        && normalMovement(field(frame, "movementFlags"))
        && !Boolean.parseBoolean(field(frame, "teleported"));
  }

  private static List<Long> evidence(List<Event> player, List<Event> hunter) {
    List<Long> values = new ArrayList<>();
    if (!player.isEmpty()) {
      values.add(player.get(0).sequence());
      values.add(player.get(player.size() - 1).sequence());
    }
    if (!hunter.isEmpty()) {
      values.add(hunter.get(0).sequence());
      values.add(hunter.get(hunter.size() - 1).sequence());
    }
    return values.isEmpty() ? List.of(0L) : List.copyOf(values);
  }

  private static void addComparisonDefect(List<Defect> defects, Set<String> keys, Defect defect) {
    String key = defect.type() + ":" + defect.actorId() + ":" + defect.encounterId();
    if (keys.add(key)) defects.add(defect);
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) if (value != null && !value.isBlank()) return value;
    return null;
  }

  private record MotionStats(
      int samples, double averageSpeed, double maximumSpeed, double sprintRatio, double jumpRatio) {}

  private record BehaviorSegment(
      String behavior, double confidence, long duration, List<Long> evidenceSequences) {}

  private record ApproachTrend(
      int samples,
      double startDistance,
      double endDistance,
      double playerContribution,
      double hunterContribution) {}

  private record EncounterWindow(
      String id,
      String matchId,
      String playerActorId,
      String hunterActorId,
      long startedAt,
      long endedAt,
      long startSequence,
      long endSequence) {
    private long durationTicks() {
      return Math.max(0L, endedAt - startedAt);
    }
  }

  private static final class EncounterWindowBuilder {
    private final Event start;

    private EncounterWindowBuilder(Event start) {
      this.start = start;
    }

    private EncounterWindow finish(Event end) {
      long endedAt = end == null ? start.sessionTick() : end.sessionTick();
      long endSequence = end == null ? start.sequence() : end.sequence();
      String player = "PLAYER".equals(start.actorKind()) ? start.actorId() : start.counterpartActorId();
      String hunter = "PLAYER".equals(start.actorKind()) ? start.counterpartActorId() : start.actorId();
      return new EncounterWindow(
          start.encounterId(), start.matchId(), player, hunter, start.sessionTick(), endedAt,
          start.sequence(), endSequence);
    }
  }

  private static List<Event> readEvents(Path session) throws IOException {
    List<Path> files;
    try (var paths = Files.list(session)) {
      files = paths
          .filter(path -> path.getFileName().toString().matches("(events|frames|snapshots|predictions|performance|encounters)-\\d{4}\\.ndjson"))
          .sorted()
          .toList();
    }
    List<Event> events = new ArrayList<>();
    for (Path file : files) {
      try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
        lines.filter(line -> !line.isBlank()).forEach(line -> events.add(parse(line)));
      }
    }
    events.sort(Comparator.comparingLong(Event::sequence));
    return List.copyOf(events);
  }

  private static Event parse(String line) {
    JsonObject object = JsonParser.parseString(line).getAsJsonObject();
    Map<String, String> payload = new LinkedHashMap<>();
    JsonObject payloadJson = object.getAsJsonObject("payload");
    if (payloadJson != null)
      for (Map.Entry<String, JsonElement> entry : payloadJson.entrySet())
        payload.put(entry.getKey(), entry.getValue().isJsonNull() ? "null" : entry.getValue().getAsString());
    return new Event(
        object.get("sequence").getAsLong(),
        object.has("sessionTick")
            ? object.get("sessionTick").getAsLong()
            : object.get("gameTime").getAsLong(),
        object.get("gameTime").getAsLong(),
        nullableString(object.get("hunterId")),
        nullableString(object.get("actorId")),
        nullableString(object.get("actorKind")),
        nullableString(object.get("counterpartActorId")),
        nullableString(object.get("encounterId")),
        nullableString(object.get("matchId")),
        nullableString(object.get("decisionId")),
        nullableString(object.get("planId")),
        nullableString(object.get("evidenceId")),
        nullableString(object.get("routeId")),
        object.get("type").getAsString(),
        Map.copyOf(payload));
  }

  private static String nullableString(JsonElement element) {
    return element == null || element.isJsonNull() ? null : element.getAsString();
  }

  private static String summary(Analysis analysis) {
    StringBuilder text = new StringBuilder("# Hunter AI Replay Summary\n\n");
    text.append("- Events: ").append(analysis.eventCount()).append('\n');
    text.append("- Hunters: ").append(analysis.hunters().size()).append('\n');
    text.append("- Sequence gaps: ").append(analysis.sequenceGaps()).append('\n');
    text.append("- Plans: ").append(analysis.planCount()).append('\n');
    text.append("- Shots: ").append(analysis.shotCount()).append('\n');
    text.append("- Predictions: ").append(analysis.predictionCount()).append('\n');
    text.append("- Plan execution rate: ")
        .append(String.format(Locale.ROOT, "%.3f", analysis.planExecutionRate())).append('\n');
    text.append("- Objective progress/tick: ")
        .append(String.format(Locale.ROOT, "%.4f", analysis.objectiveProgressPerTick())).append('\n');
    text.append("- Shots/visible frame: ")
        .append(String.format(Locale.ROOT, "%.4f", analysis.shotsPerVisibleFrame())).append('\n');
    text.append("- Prediction mean error: ")
        .append(String.format(Locale.ROOT, "%.4f", analysis.predictionMeanError())).append('\n');
    text.append("- Behavior diversity: ")
        .append(String.format(Locale.ROOT, "%.4f", analysis.behaviorDiversity())).append('\n');
    text.append("- Coordinator average/P95 us: ")
        .append(String.format(
            Locale.ROOT,
            "%.1f / %.1f",
            analysis.averageCoordinatorMicros(),
            analysis.p95CoordinatorMicros()))
        .append('\n');
    text.append("- Defects: ").append(analysis.defects().size()).append("\n\n");
    text.append("## Hunters\n\n");
    for (HunterSummary hunter : analysis.hunters().values())
      text.append("- ").append(hunter.hunterId())
          .append(": plan=").append(hunter.lastPlan())
          .append(", movement=").append(hunter.lastMovement())
          .append(", weapon=").append(hunter.lastWeapon())
          .append(", plans=").append(hunter.planCount())
          .append(", fallbacks=").append(hunter.fallbackCount()).append('\n');
    text.append("\n## Defects\n\n");
    if (analysis.defects().isEmpty()) text.append("No automatically detected defects.\n");
    for (Defect defect : analysis.defects())
      text.append("- [").append(defect.severity()).append("] ").append(defect.type())
          .append(" hunter=").append(defect.hunterId())
          .append(" sequence=").append(defect.sequence())
          .append(": ").append(defect.description()).append('\n');
    return text.toString();
  }

  private static String row(String name, long first, long second) {
    return "| " + name + " | " + first + " | " + second + " |\n";
  }

  private static String decimalRow(String name, double first, double second) {
    return String.format(Locale.ROOT, "| %s | %.4f | %.4f |%n", name, first, second);
  }

  private static void add(List<Defect> defects, Set<String> keys, Defect defect) {
    String occurrence = CONTINUOUS_DEFECT_TYPES.contains(defect.type())
        ? Long.toString(defect.sequence())
        : Long.toString(defect.sequence() / 40L);
    String key = defect.type() + ":" + defect.hunterId() + ":" + occurrence;
    if (keys.add(key)) defects.add(defect);
  }

  private static boolean legitimatePendingContactLoss(Event event) {
    String diagnostic = field(event, "diagnostic");
    return value(field(event, "losRejected")) > 0.0D
        || value(field(event, "fovRejected")) > 0.0D
        || value(field(event, "rayRejected")) > 0.0D
        || diagnostic.contains("los-blocked")
        || diagnostic.contains("fov-rejected")
        || diagnostic.contains("ray-budget");
  }

  private static boolean objectiveDirectedPlan(String plan) {
    if (plan == null) return false;
    return plan.startsWith("TRANSIT_TO_BOSS:")
        || plan.startsWith("TRANSIT_TO_STAGE:")
        || plan.startsWith("START_BANISH:")
        || plan.startsWith("CONTEST_BANISH:")
        || plan.startsWith("CLAIM_BOUNTY:")
        || plan.startsWith("HUNT_SIGNAL:")
        || plan.startsWith("EXTRACT:");
  }

  private static List<Defect> aggregateContinuousDefects(
      List<Defect> defects, List<Event> events) {
    Map<Long, Event> bySequence = new HashMap<>();
    events.forEach(event -> bySequence.put(event.sequence(), event));
    List<DefectCluster> clusters = new ArrayList<>();
    Map<String, DefectCluster> active = new HashMap<>();
    for (Defect defect : defects) {
      Event event = bySequence.get(defect.sequence());
      long tick = event == null ? defect.sequence() : event.sessionTick();
      String region = event == null ? "global" : defectRegion(event);
      String key = defect.type() + ":" + defect.hunterId() + ":" + region;
      DefectCluster cluster = active.get(key);
      if (!CONTINUOUS_DEFECT_TYPES.contains(defect.type())
          || cluster == null
          || tick - cluster.lastTick > 200L) {
        cluster = new DefectCluster(defect, tick);
        clusters.add(cluster);
        if (CONTINUOUS_DEFECT_TYPES.contains(defect.type())) active.put(key, cluster);
      } else {
        cluster.append(defect, tick);
      }
    }
    return clusters.stream()
        .map(DefectCluster::finish)
        .sorted(Comparator.comparingLong(Defect::sequence))
        .toList();
  }

  private static String defectRegion(Event event) {
    Vec3 position = vector(field(event, "failureTarget"));
    if (position == null) position = vector(field(event, "position"));
    if (position == null) position = vector(field(event, "target"));
    return position == null ? "global" : failureRegion(position);
  }

  private static String field(Event event, String name) {
    return event.payload().getOrDefault(name, "");
  }

  private static Vec3 vector(String text) {
    if (text == null || !text.startsWith("(") || !text.endsWith(")")) return null;
    String[] values = text.substring(1, text.length() - 1).split(",");
    if (values.length != 3) return null;
    try {
      return new Vec3(Double.parseDouble(values[0]), Double.parseDouble(values[1]), Double.parseDouble(values[2]));
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  private static double distanceSquared(Vec3 first, Vec3 second) {
    double x = first.x() - second.x();
    double y = first.y() - second.y();
    double z = first.z() - second.z();
    return x * x + y * y + z * z;
  }

  private static Double number(String value) {
    if (value == null || value.isBlank() || "none".equals(value)) return null;
    try {
      return Double.parseDouble(value);
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  private static String cell(Vec3 position) {
    return (int) Math.floor(position.x() / 2.0D) + ":"
        + (int) Math.floor(position.y() / 2.0D) + ":"
        + (int) Math.floor(position.z() / 2.0D);
  }

  private static String failureRegion(Vec3 position) {
    return (int) Math.floor(position.x() / 8.0D) + ":"
        + (int) Math.floor(position.y() / 5.0D) + ":"
        + (int) Math.floor(position.z() / 8.0D);
  }

  private static double clamp(double value) {
    return Math.max(0.0D, Math.min(1.0D, value));
  }

  public record Analysis(
      int schemaVersion,
      long eventCount,
      long firstSequence,
      long lastSequence,
      long sequenceGaps,
      int planCount,
      int shotCount,
      int predictionCount,
      int uniquePlanSignatures,
      int executedPlanCount,
      double planExecutionRate,
      double objectiveProgressPerTick,
      double shotsPerVisibleFrame,
      double predictionMeanError,
      double behaviorDiversity,
      double averageCoordinatorMicros,
      double p95CoordinatorMicros,
      Map<String, HunterSummary> hunters,
      List<Defect> defects) {
    public Analysis {
      hunters = Map.copyOf(hunters);
      defects = List.copyOf(defects);
    }
  }

  public record HunterSummary(
      String hunterId,
      long lastGameTime,
      String lastPlan,
      String lastMovement,
      String lastWeapon,
      int planCount,
      int fallbackCount,
      int uniqueRoutes) {}

  public record Defect(
      String type,
      String severity,
      String hunterId,
      long sequence,
      String description,
      String actorId,
      String counterpartActorId,
      String matchId,
      String encounterId,
      List<Long> evidenceSequences) {
    public Defect(
        String type, String severity, String hunterId, long sequence, String description) {
      this(type, severity, hunterId, sequence, description, hunterId, null, null, null, List.of(sequence));
    }

    public Defect {
      evidenceSequences =
          List.copyOf(evidenceSequences == null || evidenceSequences.isEmpty()
              ? List.of(sequence)
              : evidenceSequences);
    }
  }

  public record ReplayFilter(
      String hunterId,
      Long fromGameTime,
      Long toGameTime,
      String decisionId,
      String planId,
      String goal,
      String eventType,
      String defectType) {
    public static ReplayFilter all() {
      return new ReplayFilter(null, null, null, null, null, null, null, null);
    }

    private boolean matches(Event event) {
      return optionalEquals(hunterId, event.hunterId())
          && (fromGameTime == null || event.sessionTick() >= fromGameTime)
          && (toGameTime == null || event.sessionTick() <= toGameTime)
          && optionalEquals(decisionId, event.decisionId())
          && optionalEquals(planId, event.planId())
          && optionalEquals(goal, event.payload().get("goal"))
          && optionalEquals(eventType, event.type());
    }

    private boolean matches(Defect defect, Event evidence) {
      return optionalEquals(hunterId, defect.hunterId())
          && optionalEquals(defectType, defect.type())
          && (evidence == null || matchesIgnoringEventKind(evidence));
    }

    private boolean matchesIgnoringEventKind(Event event) {
      return (fromGameTime == null || event.sessionTick() >= fromGameTime)
          && (toGameTime == null || event.sessionTick() <= toGameTime)
          && optionalEquals(decisionId, event.decisionId())
          && optionalEquals(planId, event.planId())
          && optionalEquals(goal, event.payload().get("goal"));
    }

    private static boolean optionalEquals(String expected, String actual) {
      return expected == null || expected.isBlank() || expected.equals(actual);
    }
  }

  public record ReplayQuery(List<TimelineEntry> timeline, List<Defect> defects) {
    public ReplayQuery {
      timeline = List.copyOf(timeline);
      defects = List.copyOf(defects);
    }
  }

  public record TimelineEntry(
      long sequence,
      long sessionTick,
      long gameTime,
      String hunterId,
      String matchId,
      String decisionId,
      String planId,
      String evidenceId,
      String routeId,
      String type,
      Map<String, String> payload) {
    public TimelineEntry {
      payload = Map.copyOf(payload);
    }
  }

  private record Event(
      long sequence,
      long sessionTick,
      long gameTime,
      String hunterId,
      String actorId,
      String actorKind,
      String counterpartActorId,
      String encounterId,
      String matchId,
      String decisionId,
      String planId,
      String evidenceId,
      String routeId,
      String type,
      Map<String, String> payload) {
    private TimelineEntry timelineEntry() {
      return new TimelineEntry(
          sequence,
          sessionTick,
          gameTime,
          hunterId,
          matchId,
          decisionId,
          planId,
          evidenceId,
          routeId,
          type,
          payload);
    }
  }

  private record Vec3(double x, double y, double z) {}

  private record RegionFailure(int count, long lastAt) {}

  private static final class DefectCluster {
    private final Defect first;
    private final long firstTick;
    private final List<Long> evidence = new ArrayList<>();
    private long lastTick;
    private long lastSequence;
    private int occurrences = 1;

    private DefectCluster(Defect first, long tick) {
      this.first = first;
      this.firstTick = tick;
      this.lastTick = tick;
      this.lastSequence = first.sequence();
      this.evidence.addAll(first.evidenceSequences());
    }

    private void append(Defect defect, long tick) {
      occurrences++;
      lastTick = tick;
      lastSequence = defect.sequence();
      for (Long sequence : defect.evidenceSequences()) {
        if (!evidence.contains(sequence)) evidence.add(sequence);
      }
    }

    private Defect finish() {
      if (occurrences == 1) return first;
      return new Defect(
          first.type(),
          first.severity(),
          first.hunterId(),
          first.sequence(),
          first.description()
              + "; occurrences=" + occurrences
              + "; lastSequence=" + lastSequence
              + "; durationTicks=" + Math.max(0L, lastTick - firstTick),
          first.actorId(),
          first.counterpartActorId(),
          first.matchId(),
          first.encounterId(),
          evidence);
    }
  }

  private static final class HunterState {
    private final String id;
    private Vec3 lastPosition;
    private boolean movementExpected;
    private boolean objectiveMovementExpected;
    private boolean pathLoopMovementExpected;
    private Vec3 lastMovementTarget;
    private Double lastMovementTargetDistance;
    private int movementTargetStallTicks;
    private int stalledTicks;
    private int objectiveStallTicks;
    private boolean visible;
    private long visibleStartedAt = -1L;
    private long visibleStreak;
    private long visibleFrames;
    private long lastShotAt = -1L;
    private long fireRequestedAt = -1L;
    private boolean seenNotFiredReported;
    private boolean physicalFireBlockActive;
    private long nativeReadySince = Long.MIN_VALUE;
    private String lastFireBlockReason = "unknown";
    private boolean aimOverrideActive;
    private long directorDeferredAt = Long.MIN_VALUE;
    private boolean pathFailureActive;
    private int sprintMismatchTicks;
    private int falseNavigationTicks;
    private int bossInteractionClearTicks;
    private boolean bossInteractionReported;
    private long bossInteractionSubmittedUntil = Long.MIN_VALUE;
    private long pendingLegalContactAt = Long.MIN_VALUE;
    private long pendingLegalContactSequence;
    private boolean pendingContactLossReported;
    private long lastDamageEvidenceAt = Long.MIN_VALUE;
    private boolean damageContactObserved;
    private boolean damageResponseReported;
    private boolean lifecycleEnded;
    private long interceptHoldStartedAt = Long.MIN_VALUE;
    private boolean interceptHoldReported;
    private final Set<String> confirmedDeadTargets = new HashSet<>();
    private String combatState = "unknown";
    private String combatReason = "unknown";
    private long lastSequence;
    private long lastGameTime;
    private String lastPlan = "none";
    private String lastMovement = "none";
    private String lastWeapon = "none";
    private int planCount;
    private long planBurstStartedAt = -1L;
    private int planCommitsInBurst;
    private boolean planChurnReported;
    private int executedPlanCount;
    private String pendingPlanId;
    private boolean pendingPlanExecuted;
    private long lastPlanAt;
    private int fallbackCount;
    private boolean fallbackActive;
    private boolean fallbackLoopReported;
    private long fallbackStartedAt;
    private long lastFallbackEventAt = Long.MIN_VALUE;
    private String lastSafeResetPlanId;
    private String lastFallbackPhase = "";
    private long lastInputAt = -1L;
    private boolean tickGapActive;
    private Double lastAimError;
    private long aimStagnantSince = -1L;
    private long lastCombatAt = -1L;
    private boolean aimStagnationReported;
    private int routeUses;
    private final Set<String> routeIds = new HashSet<>();
    private final Set<String> routeTargets = new HashSet<>();
    private final Deque<String> recentCells = new ArrayDeque<>();
    private final Map<String, Integer> cellVisits = new HashMap<>();
    private final Map<String, RegionFailure> failedRouteRegions = new HashMap<>();
    private Double firstObjectiveDistance;
    private Double lastObjectiveDistance;
    private long firstObjectiveAt;
    private long lastObjectiveAt;
    private double damageTaken;
    private Double activePrediction;
    private long predictionStartedAt;
    private long predictionLastSequence;
    private double predictionProgress;
    private double predictionDamage;
    private final List<Double> predictionErrors = new ArrayList<>();

    private HunterState(String id) {
      this.id = id;
    }

    private void beginPlan(Event event, String signature, Double expectedSuccess) {
      planCount++;
      lastPlan = signature;
      pendingPlanId = event.planId() == null ? field(event, "planId") : event.planId();
      pendingPlanExecuted = false;
      lastPlanAt = event.sessionTick();
      activePrediction = expectedSuccess;
      predictionStartedAt = event.sessionTick();
      predictionLastSequence = event.sequence();
      predictionProgress = 0.0D;
      predictionDamage = 0.0D;
    }

    private void updatePrediction(
        Event event, double healthDelta, List<Defect> defects, Set<String> keys) {
      if (activePrediction == null) return;
      Double progress = number(field(event, "objectiveProgress"));
      if (progress != null) predictionProgress += progress;
      if (healthDelta < 0.0D) predictionDamage -= healthDelta;
      predictionLastSequence = event.sequence();
      if (event.sessionTick() - predictionStartedAt >= 40L)
        finishPrediction(event.sequence(), defects, keys);
    }

    private void finishPrediction(long sequence, List<Defect> defects, Set<String> keys) {
      if (activePrediction == null) return;
      long age = Math.max(0L, lastGameTime - predictionStartedAt);
      if (age >= 10L) {
        double observed = clamp(
            0.45D
                + Math.max(-0.30D, Math.min(0.35D, predictionProgress * 0.03D))
                - Math.min(0.45D, predictionDamage / 150.0D));
        double error = Math.abs(activePrediction - observed);
        predictionErrors.add(error);
        if (error > 0.45D)
          add(defects, keys, new Defect(
              "PREDICTION_ERROR", "MEDIUM", id, sequence,
              String.format(
                  Locale.ROOT,
                  "Predicted success %.3f but observed proxy was %.3f over %d ticks",
                  activePrediction,
                  observed,
                  age)));
      }
      activePrediction = null;
      predictionProgress = 0.0D;
      predictionDamage = 0.0D;
    }

    private double objectiveProgress() {
      if (firstObjectiveDistance == null || lastObjectiveDistance == null) return 0.0D;
      return firstObjectiveDistance - lastObjectiveDistance;
    }

    private long objectiveTicks() {
      return Math.max(0L, lastObjectiveAt - firstObjectiveAt);
    }
  }
}
