package cn.huntmc.hunterai.decision;

import cn.huntmc.hunterai.action.ActionRequest;
import cn.huntmc.hunterai.action.ActuatorResult;
import cn.huntmc.hunterai.action.MovementMode;
import cn.huntmc.hunterai.action.MovementProgressState;
import cn.huntmc.hunterai.entity.HunterEntity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Bounded recovery below SAFE_RESET. It repairs movement or breaks contact through action requests. */
public final class RecoveryController {
  private static final long PATH_TIMEOUT_TICKS = 64L;
  private static final long DISENGAGE_TIMEOUT_TICKS = 100L;
  private static final long NO_PROGRESS_TIMEOUT_TICKS = 16L;
  private RecoveryMode mode = RecoveryMode.NONE;
  private long startedAt = Long.MIN_VALUE;
  private Vec3 failedTarget;
  private Vec3 threat;
  private String reason = "inactive";
  private int failedAttempts;
  private Vec3 recoveryOrigin;
  private Vec3 lastPosition;
  private long lastProgressAt = Long.MIN_VALUE;
  private ActuatorResult lastObservedResult;
  private Vec3 activeRecoveryTarget;
  private long activeRecoveryTargetStartedAt = Long.MIN_VALUE;

  public void startPathRecovery(HunterEntity hunter, Vec3 target, long now, String cause) {
    if (mode == RecoveryMode.TACTICAL_DISENGAGE) {
      failedTarget = target;
      activeRecoveryTarget = null;
      activeRecoveryTargetStartedAt = Long.MIN_VALUE;
      reason = "tactical-path:" + (cause == null ? "path-failure" : cause);
      return;
    }
    if (mode != RecoveryMode.PATH_RECOVERY) {
      mode = RecoveryMode.PATH_RECOVERY;
      startedAt = now;
      failedAttempts = 0;
      recoveryOrigin = hunter.position();
      lastPosition = hunter.position();
      lastProgressAt = now;
      lastObservedResult = null;
      activeRecoveryTarget = null;
      activeRecoveryTargetStartedAt = Long.MIN_VALUE;
    }
    failedTarget = target;
    activeRecoveryTarget = null;
    activeRecoveryTargetStartedAt = Long.MIN_VALUE;
    reason = cause == null ? "path-failure" : cause;
  }

  public void startTacticalDisengage(
      HunterEntity hunter, Vec3 currentThreat, long now, String cause) {
    if (mode != RecoveryMode.TACTICAL_DISENGAGE) {
      mode = RecoveryMode.TACTICAL_DISENGAGE;
      startedAt = now;
      failedAttempts = 0;
      recoveryOrigin = hunter.position();
      lastPosition = hunter.position();
      lastProgressAt = now;
      lastObservedResult = null;
      activeRecoveryTarget = null;
      activeRecoveryTargetStartedAt = Long.MIN_VALUE;
    }
    threat = currentThreat;
    reason = cause == null ? "emergency" : cause;
  }

  public void observe(HunterEntity hunter, ActuatorResult result, long now) {
    if (!active() || result == null || result == lastObservedResult) return;
    lastObservedResult = result;
    boolean madeProgress = result.movementProgress() == MovementProgressState.PROGRESSING;
    boolean movementFailed = result.movementProgress() == MovementProgressState.STALLED
        || result.movementProgress() == MovementProgressState.FAILED
        || !result.navigationAccepted();
    boolean progressTimedOut = !movementFailed
        && !madeProgress
        && noProgressTimedOut(lastProgressAt, now);
    failedAttempts = nextFailedAttempts(
        failedAttempts, !movementFailed && !progressTimedOut, madeProgress);
    if (movementFailed || progressTimedOut) {
      activeRecoveryTarget = null;
      activeRecoveryTargetStartedAt = Long.MIN_VALUE;
      lastPosition = hunter.position();
      lastProgressAt = now;
      if (progressTimedOut) reason = "recovery-no-real-progress";
    }
    if (madeProgress) {
      lastPosition = hunter.position();
      lastProgressAt = now;
      activeRecoveryTargetStartedAt = now;
    }
  }

  static int nextFailedAttempts(
      int currentFailures, boolean navigationAccepted, boolean madeProgress) {
    if (!navigationAccepted) return currentFailures + 1;
    return madeProgress ? 0 : currentFailures;
  }

  static boolean noProgressTimedOut(long lastProgressAt, long now) {
    return lastProgressAt != Long.MIN_VALUE
        && now >= lastProgressAt
        && now - lastProgressAt >= NO_PROGRESS_TIMEOUT_TICKS;
  }

  public List<ActionRequest> proposals(HunterEntity hunter, long now) {
    if (!active()) return List.of();
    List<ActionRequest> proposals = new ArrayList<>();
    if (mode == RecoveryMode.PATH_RECOVERY) {
      if (activeRecoveryTarget == null) {
        activeRecoveryTarget = pathRecoveryTarget(hunter, now);
        activeRecoveryTargetStartedAt = now;
      }
      Vec3 target = activeRecoveryTarget;
      MovementMode movement = failedTarget != null && failedTarget.y < hunter.getY() - 3.0D
          ? MovementMode.SAFE_DROP
          : MovementMode.EVADE;
      proposals.add(ActionRequest.movement(
          "path-recovery", 95, target, 0.96D, movement,
          "attempt=" + (failedAttempts + 1) + " cause=" + reason));
      if (failedTarget != null)
        proposals.add(ActionRequest.view(
            "path-recovery", 58, failedTarget.add(0.0D, 1.0D, 0.0D), "retain-route-bearing"));
      return List.copyOf(proposals);
    }

    Vec3 away = threat == null
        ? Vec3.directionFromRotation(0.0F, hunter.getYRot() + 180.0F)
        : hunter.position().subtract(threat).multiply(1.0D, 0.0D, 1.0D);
    if (away.lengthSqr() < 1.0E-6D) away = new Vec3(1.0D, 0.0D, 0.0D);
    away = away.normalize();
    Vec3 side = new Vec3(-away.z, 0.0D, away.x);
    if (((hunter.getUUID().hashCode() ^ (int) (startedAt / 20L)) & 1) != 0) side = side.scale(-1.0D);
    if (activeRecoveryTarget == null) {
      activeRecoveryTarget = tacticalDisengageTarget(hunter.position(), away, side, failedAttempts);
      activeRecoveryTargetStartedAt = now;
    }
    proposals.add(ActionRequest.movement(
        "tactical-disengage", 97, activeRecoveryTarget, 1.0D, MovementMode.EVADE,
        "break-contact cause=" + reason));
    if (threat != null) {
      proposals.add(ActionRequest.view(
          "tactical-disengage", 98, threat.add(0.0D, 1.3D, 0.0D), "retain-counterfire-bearing"));
    }
    return List.copyOf(proposals);
  }

  public boolean readyToReplan(long now) {
    if (!active() || lastPosition == null || recoveryOrigin == null) return false;
    double requiredDistance = mode == RecoveryMode.PATH_RECOVERY ? 2.0D : 4.0D;
    long minimumTicks = mode == RecoveryMode.PATH_RECOVERY ? 8L : 16L;
    return failedAttempts == 0
        && now - startedAt >= minimumTicks
        && recoveryOrigin.distanceToSqr(lastPosition) >= requiredDistance * requiredDistance;
  }

  public boolean shouldEscalate(long now) {
    if (!active()) return false;
    long timeout = mode == RecoveryMode.PATH_RECOVERY ? PATH_TIMEOUT_TICKS : DISENGAGE_TIMEOUT_TICKS;
    return failedAttempts >= 4 || now - startedAt >= timeout;
  }

  public boolean active() {
    return mode != RecoveryMode.NONE;
  }

  public void acceptValidatedTarget(Vec3 requestedTarget, Vec3 executionTarget) {
    if (active()
        && activeRecoveryTarget != null
        && requestedTarget != null
        && activeRecoveryTarget.distanceToSqr(requestedTarget) <= 0.01D
        && executionTarget != null) activeRecoveryTarget = executionTarget;
  }

  public void rejectUnreachableTarget(String failureReason) {
    if (!active()) return;
    failedAttempts++;
    activeRecoveryTarget = null;
    activeRecoveryTargetStartedAt = Long.MIN_VALUE;
    if (failureReason != null && !failureReason.isBlank()) reason = failureReason;
  }

  public RecoveryMode mode() {
    return mode;
  }

  public String summary(long now) {
    return "mode=" + mode + " reason=" + reason + " attempts=" + failedAttempts
        + " activeTicks=" + (active() ? Math.max(0L, now - startedAt) : 0L)
        + " targetTicks="
        + (activeRecoveryTargetStartedAt == Long.MIN_VALUE
            ? 0L
            : Math.max(0L, now - activeRecoveryTargetStartedAt));
  }

  public void finish() {
    mode = RecoveryMode.NONE;
    startedAt = Long.MIN_VALUE;
    failedTarget = null;
    threat = null;
    failedAttempts = 0;
    recoveryOrigin = null;
    lastPosition = null;
    lastProgressAt = Long.MIN_VALUE;
    lastObservedResult = null;
    activeRecoveryTarget = null;
    activeRecoveryTargetStartedAt = Long.MIN_VALUE;
    reason = "inactive";
  }

  private Vec3 pathRecoveryTarget(HunterEntity hunter, long now) {
    Vec3 toward = failedTarget == null
        ? Vec3.directionFromRotation(0.0F, hunter.getYRot())
        : failedTarget.subtract(hunter.position()).multiply(1.0D, 0.0D, 1.0D);
    if (toward.lengthSqr() < 1.0E-6D) toward = new Vec3(0.0D, 0.0D, 1.0D);
    toward = toward.normalize();
    Vec3 side = new Vec3(-toward.z, 0.0D, toward.x);
    if ((hunter.getUUID().hashCode() & 1) != 0) side = side.scale(-1.0D);
    int attempt = Math.floorMod(failedAttempts, 4);
    return switch (attempt) {
      case 0 -> hunter.position().add(side.scale(5.0D)).add(toward.scale(1.0D));
      case 1 -> hunter.position().subtract(side.scale(5.0D)).add(toward.scale(1.0D));
      case 2 -> hunter.position().subtract(toward.scale(4.5D)).add(side.scale(1.0D));
      default -> hunter.position().add(toward.scale(4.0D)).subtract(side.scale(1.0D));
    };
  }

  static Vec3 tacticalDisengageTarget(
      Vec3 position, Vec3 away, Vec3 side, int failedAttempts) {
    int attempt = Math.floorMod(failedAttempts, 4);
    return switch (attempt) {
      case 0 -> position.add(away.scale(5.5D)).add(side.scale(2.5D));
      case 1 -> position.add(away.scale(4.5D)).subtract(side.scale(3.5D));
      case 2 -> position.add(side.scale(5.0D)).subtract(away.scale(1.0D));
      default -> position.subtract(side.scale(5.0D)).subtract(away.scale(1.0D));
    };
  }
}
