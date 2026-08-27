package cn.huntmc.hunterai.decision;

import cn.huntmc.hunterai.action.ActionRequest;
import cn.huntmc.hunterai.action.ActuatorResult;
import cn.huntmc.hunterai.action.MovementMode;
import cn.huntmc.hunterai.action.MovementProgressState;
import cn.huntmc.hunterai.action.PostureCommand;
import cn.huntmc.hunterai.action.WeaponCommand;
import cn.huntmc.hunterai.ai.TacticalNode;
import cn.huntmc.hunterai.ai.TacticalSpace;
import cn.huntmc.hunterai.entity.HunterEntity;
import cn.huntmc.hunterai.model.HunterInputSnapshot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;

/** Final bounded fallback. It proposes actions but never controls the entity directly. */
public final class SafeResetController {
  public static final long MAX_SAFE_RESET_TICKS = 160L;
  private SafeResetPhase phase = SafeResetPhase.REASSESS;
  private long startedAt = Long.MIN_VALUE;
  private Vec3 immediateCover;
  private Vec3 deepCover;
  private List<Vec3> immediateCandidates = List.of();
  private List<Vec3> deepCandidates = List.of();
  private int immediateCandidateIndex;
  private int deepCandidateIndex;
  private int recoveryAttempt;
  private Vec3 localRecoveryTarget;
  private Vec3 strategicAnchor;
  private long lastObservedFailureAt = Long.MIN_VALUE;
  private boolean safeAtStart;
  private String reason = "inactive";

  public void start(
      HunterEntity hunter,
      TacticalSpace space,
      Vec3 threat,
      Vec3 strategicAnchor,
      long now,
      String trigger) {
    if (active(now)) return;
    startedAt = now;
    reason = trigger == null ? "unknown" : trigger;
    this.strategicAnchor = strategicAnchor;
    immediateCandidates = selectCovers(space, hunter.position(), threat, strategicAnchor, false);
    deepCandidates = threat == null
        ? List.of()
        : selectCovers(space, hunter.position(), threat, strategicAnchor, true);
    immediateCandidateIndex = 0;
    deepCandidateIndex = 0;
    recoveryAttempt = 0;
    immediateCover = current(immediateCandidates, immediateCandidateIndex);
    deepCover = current(deepCandidates, deepCandidateIndex);
    safeAtStart = threat != null
        && space.localExposure() <= 0.24D
        && space.nodes().stream()
            .filter(TacticalNode::isConcealed)
            .anyMatch(node -> node.position().distanceToSqr(hunter.position()) <= 4.0D);
    localRecoveryTarget =
        immediateCover == null
            ? maneuverTarget(
                hunter.position(), threat, strategicAnchor, hunter.getUUID(), recoveryAttempt)
            : null;
    phase = safeAtStart ? SafeResetPhase.RECOVER : SafeResetPhase.BREAK_CONTACT;
  }

  /** Consumes only the previous frame result and advances through bounded recovery alternatives. */
  public void observeActuatorResult(
      ActuatorResult result, HunterEntity hunter, Vec3 threat, long now) {
    if (!active(now)
        || result == null
        || now <= startedAt
        || now == lastObservedFailureAt
        || !isMovementFailure(result)) return;
    lastObservedFailureAt = now;
    advanceFailedMovement(hunter, threat);
  }

  public void acceptValidatedTarget(Vec3 requestedTarget, Vec3 executionTarget) {
    if (requestedTarget == null || executionTarget == null) return;
    if (immediateCover != null && immediateCover.distanceToSqr(requestedTarget) <= 0.01D) {
      immediateCover = executionTarget;
    } else if (deepCover != null && deepCover.distanceToSqr(requestedTarget) <= 0.01D) {
      deepCover = executionTarget;
    } else if (localRecoveryTarget != null
        && localRecoveryTarget.distanceToSqr(requestedTarget) <= 0.01D) {
      localRecoveryTarget = executionTarget;
    }
  }

  public void rejectUnreachableTarget(
      HunterEntity hunter, Vec3 threat, long now, String failureReason) {
    if (!active(now)) return;
    lastObservedFailureAt = now;
    if (failureReason != null && !failureReason.isBlank()) {
      reason = reason + ":route=" + failureReason;
    }
    advanceFailedMovement(hunter, threat);
  }

  private void advanceFailedMovement(HunterEntity hunter, Vec3 threat) {
    if (phase == SafeResetPhase.BREAK_CONTACT || phase == SafeResetPhase.REACH_IMMEDIATE_COVER) {
      if (immediateCandidateIndex + 1 < immediateCandidates.size()) {
        immediateCover = immediateCandidates.get(++immediateCandidateIndex);
        localRecoveryTarget = null;
        return;
      }
      immediateCandidateIndex = immediateCandidates.size();
      immediateCover = null;
    } else if (phase == SafeResetPhase.MOVE_TO_DEEP_COVER) {
      if (deepCandidateIndex + 1 < deepCandidates.size()) {
        deepCover = deepCandidates.get(++deepCandidateIndex);
        localRecoveryTarget = null;
        return;
      }
      deepCandidateIndex = deepCandidates.size();
      deepCover = null;
    } else {
      return;
    }

    recoveryAttempt =
        localRecoveryTarget == null ? 0 : nextRecoveryAttempt(recoveryAttempt);
    if (recoveryAttempt >= RecoveryManeuver.values().length) {
      phase = SafeResetPhase.REASSESS;
      localRecoveryTarget = null;
    } else {
      localRecoveryTarget =
          maneuverTarget(
              hunter.position(), threat, strategicAnchor, hunter.getUUID(), recoveryAttempt);
    }
  }

  public List<ActionRequest> proposals(
      HunterEntity hunter, HunterInputSnapshot input, Vec3 threat, long now) {
    if (!active(now)) return List.of();
    if (now - startedAt >= MAX_SAFE_RESET_TICKS) phase = SafeResetPhase.REASSESS;
    advance(hunter, input, now);
    List<ActionRequest> result = new ArrayList<>();
    switch (phase) {
      case BREAK_CONTACT -> {
        Vec3 target = movementTarget(hunter, threat);
        result.add(ActionRequest.movement(
            "safe-reset", 100, target, 1.0D, recoveryMovementMode(),
            "break-contact:" + recoveryName() + ":" + reason));
        result.add(ActionRequest.posture(
            "safe-reset", 100, PostureCommand.STAND, "break-contact-mobile"));
        if (threat != null)
          result.add(ActionRequest.view(
              "safe-reset", 96, threat, "watch-threat-during-break-contact"));
        result.add(ActionRequest.weapon(
            "safe-reset", 94,
            input.self().reloading() || input.self().ammunition() <= 0
                ? WeaponCommand.RELOAD
                : WeaponCommand.HOLD,
            "survival-weapon-state"));
      }
      case REACH_IMMEDIATE_COVER -> {
        Vec3 target = movementTarget(hunter, threat);
        result.add(ActionRequest.movement(
            "safe-reset", 98, target, 1.0D, recoveryMovementMode(),
            "reach-immediate-cover:" + recoveryName() + ":" + reason));
        if (threat != null) result.add(ActionRequest.view("safe-reset", 96, threat, "watch-threat-during-retreat"));
        result.add(ActionRequest.weapon(
            "safe-reset", 94,
            input.self().reloading() || input.self().ammunition() <= 0
                ? WeaponCommand.RELOAD
                : WeaponCommand.HOLD,
            "survival-weapon-state"));
      }
      case MOVE_TO_DEEP_COVER -> {
        Vec3 target = deepCover != null ? deepCover : movementTarget(hunter, threat);
        if (target != null)
          result.add(ActionRequest.movement(
              "safe-reset", 96, target, 0.98D, recoveryMovementMode(),
              "move-deep-cover:" + recoveryName() + ":" + reason));
        if (threat != null) result.add(ActionRequest.view("safe-reset", 94, threat, "retain-threat-bearing"));
      }
      case RECOVER -> {
        result.add(ActionRequest.movement(
            "safe-reset", 95, null, 0.0D, MovementMode.HOLD, "recover-in-cover"));
        result.add(ActionRequest.posture(
            "safe-reset", 95, PostureCommand.CROUCH, "recover-concealed"));
        result.add(ActionRequest.weapon(
            "safe-reset", 95,
            input.self().ammunition() <= 0 || input.self().ammoRatio() < 0.25D
                ? WeaponCommand.RELOAD
                : WeaponCommand.HOLD,
            "recover-ammunition"));
      }
      case REASSESS -> {
        if (safeAtStart || immediateCover != null || deepCover != null) {
          result.add(ActionRequest.movement(
              "safe-reset", 90, null, 0.0D, MovementMode.HOLD, "reassess-complete"));
        } else {
          result.add(ActionRequest.movement(
              "safe-reset", 90,
              lowestThreatDirection(
                  hunter.position(), threat, strategicAnchor, hunter.getUUID()),
              0.95D, MovementMode.EVADE, "reassess-while-leaving-open-ground"));
        }
        result.add(ActionRequest.posture(
            "safe-reset", 90, PostureCommand.CROUCH, "reassess-from-cover"));
      }
    }
    return List.copyOf(result);
  }

  public boolean active(long now) {
    return startedAt != Long.MIN_VALUE;
  }

  public boolean readyToReplan() {
    return phase == SafeResetPhase.REASSESS;
  }

  public void finish() {
    phase = SafeResetPhase.REASSESS;
    startedAt = Long.MIN_VALUE;
    immediateCover = null;
    deepCover = null;
    immediateCandidates = List.of();
    deepCandidates = List.of();
    localRecoveryTarget = null;
    strategicAnchor = null;
    recoveryAttempt = 0;
    lastObservedFailureAt = Long.MIN_VALUE;
    safeAtStart = false;
    reason = "inactive";
  }

  public FallbackPlan snapshot() {
    return new FallbackPlan(
        phase, immediateCover, deepCover,
        startedAt == Long.MIN_VALUE ? Long.MIN_VALUE : startedAt + MAX_SAFE_RESET_TICKS);
  }

  public String reason() {
    return reason;
  }

  private void advance(HunterEntity hunter, HunterInputSnapshot input, long now) {
    boolean immediateReached =
        immediateCover != null && hunter.position().distanceToSqr(immediateCover) <= 3.0D;
    if (!immediateReached && immediateCover == null && localRecoveryTarget != null) {
      immediateReached = hunter.position().distanceToSqr(localRecoveryTarget) <= 3.0D;
    }
    boolean deepReached = deepCover != null && hunter.position().distanceToSqr(deepCover) <= 3.0D;
    boolean recovered =
        now - startedAt >= 40L
            && !input.self().reloading()
            && input.self().healthRatio() >= 0.20D;
    phase = nextPhase(
        phase,
        now <= startedAt,
        immediateReached,
        deepCover != null,
        deepReached,
        recovered,
        now - startedAt >= MAX_SAFE_RESET_TICKS);
  }

  static SafeResetPhase nextPhase(
      SafeResetPhase current,
      boolean firstTick,
      boolean immediateReached,
      boolean hasDeepCover,
      boolean deepReached,
      boolean recovered,
      boolean timedOut) {
    if (timedOut) return SafeResetPhase.REASSESS;
    return switch (current) {
      case BREAK_CONTACT -> firstTick ? SafeResetPhase.BREAK_CONTACT : SafeResetPhase.REACH_IMMEDIATE_COVER;
      case REACH_IMMEDIATE_COVER ->
          immediateReached
              ? (hasDeepCover ? SafeResetPhase.MOVE_TO_DEEP_COVER : SafeResetPhase.RECOVER)
              : current;
      case MOVE_TO_DEEP_COVER -> deepReached ? SafeResetPhase.RECOVER : current;
      case RECOVER -> recovered ? SafeResetPhase.REASSESS : current;
      case REASSESS -> current;
    };
  }

  private static List<Vec3> selectCovers(
      TacticalSpace space, Vec3 origin, Vec3 threat, Vec3 strategicAnchor, boolean deep) {
    return space.nodes().stream()
        .filter(TacticalNode::isConcealed)
        .filter(node -> node.escapeDirections() > 0)
        .filter(node -> !deep || node.position().distanceToSqr(origin) >= 16.0D)
        .filter(node -> threat != null
            || strategicAnchor == null
            || node.position().distanceTo(strategicAnchor)
                <= origin.distanceTo(strategicAnchor) + 2.0D)
        .sorted(
            Comparator.comparingDouble(
                (TacticalNode node) -> node.hardCover() * 0.34D
                    + node.rearSafety() * 0.22D
                    + (1.0D - node.flankRisk()) * 0.16D
                    + node.escapeDirections() * 0.07D
                    + (threat == null
                        ? 0.0D
                        : Math.min(1.0D, node.position().distanceTo(threat) / 24.0D) * 0.18D)
                    + objectiveProgress(origin, node.position(), strategicAnchor)
                        * (threat == null ? 0.35D : 0.10D))
                .reversed())
        .limit(6)
        .map(TacticalNode::position)
        .toList();
  }

  private Vec3 movementTarget(HunterEntity hunter, Vec3 threat) {
    if (phase == SafeResetPhase.MOVE_TO_DEEP_COVER && deepCover != null) return deepCover;
    if (immediateCover != null) return immediateCover;
    if (localRecoveryTarget == null) {
      localRecoveryTarget =
          maneuverTarget(
              hunter.position(), threat, strategicAnchor, hunter.getUUID(), recoveryAttempt);
    }
    return localRecoveryTarget;
  }

  private MovementMode recoveryMovementMode() {
    if (immediateCover != null || deepCover != null) return MovementMode.SPRINT;
    if (recoveryAttempt >= RecoveryManeuver.values().length) return MovementMode.EVADE;
    return switch (RecoveryManeuver.values()[recoveryAttempt]) {
      case BACK, SIDE_LEFT, SIDE_RIGHT -> MovementMode.EVADE;
      case LEGAL_JUMP -> MovementMode.SPRINT_JUMP_BURST;
      case SAFE_DROP -> MovementMode.SAFE_DROP;
    };
  }

  private String recoveryName() {
    if (deepCover != null && phase == SafeResetPhase.MOVE_TO_DEEP_COVER)
      return "validated-deep-cover-" + deepCandidateIndex;
    if (immediateCover != null) return "validated-immediate-cover-" + immediateCandidateIndex;
    return recoveryAttempt >= RecoveryManeuver.values().length
        ? "alternatives-exhausted"
        : RecoveryManeuver.values()[recoveryAttempt].name().toLowerCase(java.util.Locale.ROOT);
  }

  private static Vec3 current(List<Vec3> candidates, int index) {
    return index >= 0 && index < candidates.size() ? candidates.get(index) : null;
  }

  static boolean isMovementFailure(String result) {
    if (result == null) return false;
    return "path-unavailable".equals(result)
        || "partial-no-progress".equals(result)
        || "navigation-rejected".equals(result)
        || "route-stalled".equals(result)
        || "ladder-route-stalled".equals(result)
        || "local-combat-stalled".equals(result)
        || "degenerate-path".equals(result)
        || "validated-route-missing".equals(result)
        || "local-evade-blocked".equals(result);
  }

  static boolean isMovementFailure(ActuatorResult result) {
    return result != null
        && (result.movementProgress() == MovementProgressState.STALLED
            || result.movementProgress() == MovementProgressState.FAILED
            || !result.navigationAccepted()
            || isMovementFailure(result.movementResult()));
  }

  static int nextRecoveryAttempt(int current) {
    return Math.min(RecoveryManeuver.values().length, current + 1);
  }

  private static Vec3 maneuverTarget(
      Vec3 origin, Vec3 threat, Vec3 strategicAnchor, UUID hunterId, int attempt) {
    Vec3 base = recoveryDirection(origin, threat, strategicAnchor, hunterId);
    Vec3 side = new Vec3(-base.z, 0.0D, base.x);
    RecoveryManeuver maneuver =
        RecoveryManeuver.values()[Math.min(attempt, RecoveryManeuver.values().length - 1)];
    Vec3 direction =
        switch (maneuver) {
          case BACK, LEGAL_JUMP, SAFE_DROP -> base;
          case SIDE_LEFT -> side;
          case SIDE_RIGHT -> side.scale(-1.0D);
        };
    return origin.add(direction.scale(maneuver == RecoveryManeuver.SAFE_DROP ? 6.0D : 4.5D));
  }

  private static Vec3 lowestThreatDirection(
      Vec3 origin, Vec3 threat, Vec3 strategicAnchor, UUID hunterId) {
    return origin.add(recoveryDirection(origin, threat, strategicAnchor, hunterId).scale(8.0D));
  }

  static Vec3 recoveryDirection(
      Vec3 origin, Vec3 threat, Vec3 strategicAnchor, UUID hunterId) {
    if (threat != null) {
      Vec3 away = origin.subtract(threat).multiply(1.0D, 0.0D, 1.0D);
      if (away.lengthSqr() >= 1.0E-6D) return away.normalize();
    }
    if (strategicAnchor != null) {
      Vec3 towardObjective = strategicAnchor.subtract(origin).multiply(1.0D, 0.0D, 1.0D);
      if (towardObjective.lengthSqr() >= 1.0E-6D) return towardObjective.normalize();
    }
    long mixed = hunterId.getMostSignificantBits()
        ^ Long.rotateLeft(hunterId.getLeastSignificantBits(), 17);
    double angle = Math.floorMod(mixed, 360L) * Math.PI / 180.0D;
    return new Vec3(Math.cos(angle), 0.0D, Math.sin(angle));
  }

  private static double objectiveProgress(Vec3 origin, Vec3 candidate, Vec3 objective) {
    if (objective == null) return 0.0D;
    return Math.max(
        -1.0D,
        Math.min(1.0D, (origin.distanceTo(objective) - candidate.distanceTo(objective)) / 12.0D));
  }

  private enum RecoveryManeuver {
    BACK,
    SIDE_LEFT,
    SIDE_RIGHT,
    LEGAL_JUMP,
    SAFE_DROP
  }
}
