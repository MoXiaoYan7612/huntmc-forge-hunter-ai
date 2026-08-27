package cn.huntmc.hunterai.action;

import cn.huntmc.hunterai.HuntMcHunterAi;
import cn.huntmc.hunterai.combat.CloseRangeCombatTuning;
import cn.huntmc.hunterai.combat.HunterWeaponAdapter;
import cn.huntmc.hunterai.entity.HunterEntity;
import cn.huntmc.hunterai.navigation.RouteFailureMemory;
import cn.huntmc.hunterai.navigation.RouteHandle;
import cn.huntmc.hunterai.navigation.ValidatedRoute;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/** The only runtime component allowed to mutate Hunter movement, view, posture, weapon or interaction. */
public final class EntityActuator {
  private static final double ARRIVAL_DISTANCE_SQUARED = 0.85D * 0.85D;
  private static final double MOVEMENT_ARRIVAL_HORIZONTAL_SQUARED = 1.25D * 1.25D;
  private static final double MOVEMENT_ARRIVAL_VERTICAL = 1.25D;
  private static final double LOCAL_TACTICAL_FALLBACK_DISTANCE_SQUARED = 4.0D * 4.0D;
  private static final double PARTIAL_PATH_PROGRESS_MARGIN_SQUARED = 1.0D;
  private static final double PATH_TARGET_CHANGE_SQUARED = 1.5D * 1.5D;
  private static final long ROUTE_STALL_TICKS = 12L;
  private static final double ROUTE_PROGRESS_SQUARED = 0.35D * 0.35D;
  private static final double ROUTE_TARGET_CONVERGENCE = 0.05D;
  private static final double LADDER_SPEED = 0.78D;
  private static final double[] JUMP_LANDING_PROBES = {2.10D, 1.65D, 1.15D};
  private static final int[] JUMP_LANDING_Y_OFFSETS = {1, 0, -1, -2};

  private final HunterWeaponAdapter weapon = new HunterWeaponAdapter();
  private final PlayerMotionController localMotion = new PlayerMotionController();
  private final Map<UUID, RouteHandle> routeHandles = new HashMap<>();
  private final RouteFailureMemory routeFailureMemory = new RouteFailureMemory();
  private Vec3 activeTarget;
  private UUID activeRouteId;
  private Vec3 lastFailureTarget;
  private Vec3 lastProgressPosition;
  private double lastProgressTargetDistance = Double.NaN;
  private long lastProgressAt;
  private int routeFailures;
  private int stationaryMovementTicks;
  private float retainedCombatYaw;
  private float retainedCombatPitch;
  private long retainedCombatViewUntil = Long.MIN_VALUE;
  private long lastHealthRecoveryAt = Long.MIN_VALUE;

  /** Restores the last combat rotation after vanilla body control ran and before combat scores aim. */
  public void prepareTick(HunterEntity hunter, long now) {
    if (now > retainedCombatViewUntil) {
      retainedCombatViewUntil = Long.MIN_VALUE;
      return;
    }
    applyRotation(hunter, retainedCombatYaw, retainedCombatPitch, true);
  }

  public ActuatorResult apply(HunterEntity hunter, ActionFrame frame) {
    if (hunter.level().isClientSide)
      return new ActuatorResult(false, false, false, false, false, false, false, false, 0.0D,
          0.0F, 0.0F, null, null, null, 0, MovementProgressState.FAILED,
          "client-side-rejected", "not-applied", "not-applied");

    boolean postureApplied = applyPosture(hunter, frame.posture());
    boolean sprintRequested = frame.moveTarget() != null
        && frame.movement() != MovementMode.HOLD
        && !hunter.isCrouching()
        && !hunter.isClimbingLadder()
        && PlayerMotionController.shouldSprint(
            frame.movement(), frame.owners().get(ActionChannel.MOVEMENT));
    MovementOutcome movement = applyMovement(hunter, frame);
    ViewOutcome view = applyView(hunter, frame);
    retainCombatView(hunter, frame, view);
    WeaponOutcome weaponOutcome = applyWeapon(hunter, frame.weapon(), frame.lookTarget(), view);
    InteractionOutcome interaction = applyInteraction(hunter, frame.interaction());
    boolean sprintExecutionExpected = sprintRequested
        && movement.accepted()
        && movement.progress() != MovementProgressState.ARRIVED
        && movement.progress() != MovementProgressState.IDLE;
    return new ActuatorResult(
        movement.accepted(),
        view.applied(),
        postureApplied,
        weaponOutcome.applied(),
        interaction.accepted(),
        movement.jumped(),
        sprintExecutionExpected,
        hunter.isSprinting(),
        hunter.getDeltaMovement().horizontalDistance(),
        movement.forwardInput(),
        movement.strafeInput(),
        frame.routeId(),
        movement.pathEnd(),
        lastFailureTarget,
        routeFailures,
        movement.progress(),
        movement.result(),
        weaponOutcome.result(),
        interaction.result());
  }

  public HunterWeaponAdapter.AmmoState ammunition(HunterEntity hunter) {
    weapon.ensureEquipped(hunter);
    return weapon.ammoState(hunter);
  }

  public String weaponStatus(HunterEntity hunter) {
    return weapon.debugStatus(hunter);
  }

  public HunterWeaponAdapter.FireRate fireRate(HunterEntity hunter) {
    return weapon.fireRate(hunter);
  }

  public HunterWeaponAdapter.WeaponReadiness weaponReadiness(HunterEntity hunter) {
    return weapon.readiness(hunter);
  }

  public int routeFailures() {
    return routeFailures;
  }

  public void clearRouteFailures() {
    routeFailures = 0;
    lastFailureTarget = null;
    lastProgressPosition = null;
    lastProgressTargetDistance = Double.NaN;
    lastProgressAt = 0L;
  }

  public void clearNavigationState() {
    clearRouteFailures();
    activeTarget = null;
    activeRouteId = null;
    routeHandles.clear();
    routeFailureMemory.clear();
    retainedCombatViewUntil = Long.MIN_VALUE;
    lastHealthRecoveryAt = Long.MIN_VALUE;
  }

  /** Native navigation validation remains inside the actuator ownership boundary. */
  public ValidatedRoute validateRoute(
      HunterEntity hunter, Vec3 target, double exposureCost, double repetitionCost, String routeKey) {
    String identity = hunter.getUUID() + ":" + routeKey + ":" + target;
    UUID routeId = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    if (target == null)
      return new ValidatedRoute(
          routeId, null, List.of(), null, false, 0.0D, exposureCost, repetitionCost, "no-target");
    long now = hunter.level().getGameTime();
    if (movementTargetReached(hunter.position(), target))
      return new ValidatedRoute(
          routeId, target, List.of(target), target, true, 0.0D, exposureCost, repetitionCost, "");
    if (routeFailureMemory.blocked(hunter.position(), target, now))
      return new ValidatedRoute(
          routeId, target, List.of(target), null, false, 0.0D, exposureCost,
          repetitionCost + routeFailureMemory.penalty(hunter.position(), target, now),
          "recent-failure-backoff:" + routeFailureMemory.reason(hunter.position(), target, now));
    Path path = hunter.getNavigation().createPath(BlockPos.containing(target), 0);
    Vec3 end = pathEnd(path);
    if (path == null) {
      routeFailureMemory.record(routeId, hunter.position(), target, "path-unavailable", now);
      return new ValidatedRoute(
          routeId,
          target,
          List.of(target),
          null,
          false,
          0.0D,
          exposureCost,
          repetitionCost,
          "path-unavailable");
    }
    boolean executable = pathOffersExecutableProgress(hunter.position(), path);
    boolean reachable = executable
        && (path.canReach() || partialPathMakesProgress(hunter.position(), end, target));
    Vec3 executionTarget = executionTarget(target, end, path.canReach());
    double length = 0.0D;
    Vec3 previous = hunter.position();
    for (int index = 0; index < path.getNodeCount(); index++) {
      Vec3 next = path.getNode(index).asVec3();
      length += previous.distanceTo(next);
      previous = next;
    }
    ValidatedRoute validated = new ValidatedRoute(
        routeId,
        target,
        executionTarget == null ? List.of() : List.of(executionTarget),
        end,
        reachable,
        length,
        exposureCost,
        repetitionCost,
        reachable ? "" : executable ? "partial-no-progress" : "degenerate-path");
    if (reachable && executionTarget != null) {
      routeHandles.put(
          routeId,
          new RouteHandle(routeId, path, target, executionTarget, hunter.position(), now));
      pruneRouteHandles(now);
    } else {
      routeFailureMemory.record(
          routeId, hunter.position(), target, validated.failureReason(), now);
    }
    return validated;
  }

  private MovementOutcome applyMovement(HunterEntity hunter, ActionFrame frame) {
    boolean buoyancyJump = hunter.isInWater();
    if (buoyancyJump) hunter.getJumpControl().jump();
    if (frame.movement() == MovementMode.HOLD || frame.moveTarget() == null) {
      hunter.getNavigation().stop();
      localMotion.stop(hunter);
      activeTarget = null;
      activeRouteId = null;
      stationaryMovementTicks = 0;
      return new MovementOutcome(
          true, buoyancyJump, null, MovementProgressState.IDLE,
          buoyancyJump ? "water-surface-hold" : "hold", 0.0F, 0.0F);
    }

    Vec3 target = frame.moveTarget();
    if (movementTargetReached(hunter.position(), target)) {
      if (frame.routeId() != null) routeHandles.remove(frame.routeId());
      hunter.getNavigation().stop();
      localMotion.stop(hunter);
      activeTarget = null;
      activeRouteId = null;
      stationaryMovementTicks = 0;
      return new MovementOutcome(
          true, buoyancyJump, hunter.position(), MovementProgressState.ARRIVED,
          "arrived", 0.0F, 0.0F);
    }

    String movementOwner = frame.owners().get(ActionChannel.MOVEMENT);
    if ("combat-evasion".equals(movementOwner)
        || "damage-reaction".equals(movementOwner)) {
      return applyLocalCombatMovement(hunter, frame, target, buoyancyJump);
    }
    if (hunter.getNavigation().isDone()
        && activeRouteId == null
        && activeTarget != null
        && activeTarget.distanceToSqr(target) <= PATH_TARGET_CHANGE_SQUARED
        && canUseLocalTacticalFallback(
            movementOwner, frame.routeId(), hunter.position(), target)) {
      return applyLocalCombatMovement(hunter, frame, target, buoyancyJump);
    }

    boolean climbing = hunter.isClimbingLadder();
    PlayerMotionIntent requestedMotion = localMotion.intent(frame, hunter);
    boolean sprint = requestedMotion.sprint();
    double speed = climbing ? Math.min(LADDER_SPEED, frame.speed()) : calibratedSpeed(frame.speed(), sprint);
    boolean targetChanged = activeTarget == null
        || activeTarget.distanceToSqr(target) > PATH_TARGET_CHANGE_SQUARED;
    boolean routeChanged = !java.util.Objects.equals(activeRouteId, frame.routeId());
    boolean executionChanged = targetChanged || routeChanged;
    Path activePath = hunter.getNavigation().getPath();
    if (climbing && executionChanged && activePath != null && !hunter.getNavigation().isDone()) {
      ProgressCheck ladderProgress = updateProgress(hunter, target, frame.gameTime());
      if (ladderProgress.stalled()) {
        Vec3 failed = activeTarget == null ? target : activeTarget;
        recordRouteFailure(
            activeRouteId, hunter.position(), failed, "ladder-route-stalled", frame.gameTime());
        hunter.getNavigation().stop();
        localMotion.stop(hunter);
        activeTarget = null;
        activeRouteId = null;
        stationaryMovementTicks = 0;
        return new MovementOutcome(
            false,
            buoyancyJump,
            pathEnd(activePath),
            MovementProgressState.STALLED,
            "ladder-route-stalled",
            requestedMotion.forwardInput(),
            requestedMotion.strafeInput());
      }
      hunter.getNavigation().setSpeedModifier(speed);
      return new MovementOutcome(
          true,
          buoyancyJump,
          pathEnd(activePath),
          ladderProgress.madeProgress()
              ? MovementProgressState.PROGRESSING
              : MovementProgressState.PATH_ACCEPTED,
          "ladder-route-locked",
          requestedMotion.forwardInput(),
          requestedMotion.strafeInput());
    }

    ProgressCheck progress = targetChanged
        ? new ProgressCheck(false, false)
        : updateProgress(hunter, target, frame.gameTime());
    if (progress.stalled()) {
      if (canUseLocalTacticalFallback(
          movementOwner, frame.routeId(), hunter.position(), target)) {
        return restartLocalTacticalMovement(hunter, frame, target, buoyancyJump);
      }
      recordRouteFailure(
          activeRouteId, hunter.position(), activeTarget, "route-stalled", frame.gameTime());
      hunter.getNavigation().stop();
      localMotion.stop(hunter);
      activeTarget = null;
      activeRouteId = null;
      stationaryMovementTicks = 0;
      return new MovementOutcome(
          false,
          buoyancyJump,
          pathEnd(activePath),
          MovementProgressState.STALLED,
          "route-stalled",
          requestedMotion.forwardInput(),
          requestedMotion.strafeInput());
    }
    boolean pathAcceptedThisTick = false;
    if (executionChanged || activePath == null || hunter.getNavigation().isDone()) {
      RouteHandle validatedHandle = frame.routeId() == null
          ? null
          : routeHandles.get(frame.routeId());
      boolean handleMatches = validatedHandle != null
          && validatedHandle.matches(target, frame.gameTime());
      Path path = handleMatches
          ? validatedHandle.path()
          : frame.routeId() == null
              ? hunter.getNavigation().createPath(BlockPos.containing(target), 0)
              : null;
      Vec3 end = pathEnd(path);
      boolean executable = pathOffersExecutableProgress(hunter.position(), path);
      if (path == null
          || !executable
          || (!path.canReach() && !partialPathMakesProgress(hunter.position(), end, target))) {
        if (canUseLocalTacticalFallback(
            movementOwner, frame.routeId(), hunter.position(), target)) {
          return restartLocalTacticalMovement(hunter, frame, target, buoyancyJump);
        }
        String failure = path == null
            ? frame.routeId() == null ? "path-unavailable" : "validated-route-missing"
            : !executable ? "degenerate-path" : "partial-no-progress";
        recordRouteFailure(frame.routeId(), hunter.position(), target, failure, frame.gameTime());
        hunter.getNavigation().stop();
        localMotion.stop(hunter);
        if (frame.movement() == MovementMode.SAFE_DROP) {
          boolean stepped = stepOffSafeDrop(hunter, target, speed, 10);
          return new MovementOutcome(
              stepped, buoyancyJump, null,
              stepped ? MovementProgressState.RECOVERING : MovementProgressState.FAILED,
              stepped ? "safe-drop" : failure, 0.0F, 0.0F);
        }
        return new MovementOutcome(
            false,
            buoyancyJump,
            end,
            MovementProgressState.FAILED,
            failure,
            requestedMotion.forwardInput(),
            requestedMotion.strafeInput());
      }
      if (!hunter.getNavigation().moveTo(path, speed)) {
        if (canUseLocalTacticalFallback(
            movementOwner, frame.routeId(), hunter.position(), target)) {
          return restartLocalTacticalMovement(hunter, frame, target, buoyancyJump);
        }
        recordRouteFailure(
            frame.routeId(), hunter.position(), target, "navigation-rejected", frame.gameTime());
        hunter.getNavigation().stop();
        localMotion.stop(hunter);
        return new MovementOutcome(
            false,
            buoyancyJump,
            end,
            MovementProgressState.FAILED,
            "navigation-rejected",
            requestedMotion.forwardInput(),
            requestedMotion.strafeInput());
      }
      activeTarget = target;
      activeRouteId = frame.routeId();
      if (targetChanged || lastProgressPosition == null) {
        lastProgressPosition = hunter.position();
        lastProgressTargetDistance = hunter.position().distanceTo(target);
        lastProgressAt = frame.gameTime();
      }
      activePath = path;
      pathAcceptedThisTick = true;
    } else {
      hunter.getNavigation().setSpeedModifier(speed);
    }

    Vec3 localTarget = localMovementTarget(activePath, hunter, target);
    PlayerMotionIntent motionIntent = localMotion.intent(frame, hunter, localTarget);
    PlayerMotionController.MotionResult local = localMotion.apply(
        hunter,
        motionIntent,
        frame.movement(),
        frame.gameTime(),
        () -> safeJumpLanding(hunter, localTarget));
    boolean obstacleJump = false;
    if (hunter.getDeltaMovement().horizontalDistanceSqr() < 0.015D * 0.015D) {
      stationaryMovementTicks++;
      if (stationaryMovementTicks >= 3
          && immediateStepObstacle(hunter, localTarget)
          && safeJumpLanding(hunter, localTarget)) {
        obstacleJump = hunter.performActuatorJump();
        if (obstacleJump) stationaryMovementTicks = 0;
      }
    } else {
      stationaryMovementTicks = 0;
    }
    boolean jumped = buoyancyJump || local.jumped() || obstacleJump;
    return new MovementOutcome(
        true,
        jumped,
        pathEnd(activePath),
        obstacleJump
            ? MovementProgressState.RECOVERING
            : progress.madeProgress()
                ? MovementProgressState.PROGRESSING
                : MovementProgressState.PATH_ACCEPTED,
        obstacleJump ? "following-obstacle-jump" : pathAcceptedThisTick ? "path-accepted" : "following",
        motionIntent.forwardInput(),
        motionIntent.strafeInput());
  }

  private MovementOutcome applyLocalCombatMovement(
      HunterEntity hunter, ActionFrame frame, Vec3 target, boolean buoyancyJump) {
    hunter.getNavigation().stop();
    boolean targetChanged = activeTarget == null
        || activeTarget.distanceToSqr(target) > PATH_TARGET_CHANGE_SQUARED
        || activeRouteId != null;
    ProgressCheck progress = targetChanged
        ? new ProgressCheck(false, false)
        : updateProgress(hunter, target, frame.gameTime());
    if (progress.stalled()) {
      lastFailureTarget = target;
      localMotion.stop(hunter);
      activeTarget = null;
      activeRouteId = null;
      return new MovementOutcome(
          false, buoyancyJump, null, MovementProgressState.STALLED,
          "local-combat-stalled", 0.0F, 0.0F);
    }
    if (targetChanged) {
      activeTarget = target;
      lastProgressPosition = hunter.position();
      lastProgressTargetDistance = hunter.position().distanceTo(target);
      lastProgressAt = frame.gameTime();
    }
    activeRouteId = null;
    PlayerMotionIntent intent = localMotion.intent(frame, hunter, target);
    Vec3 lane = target.subtract(hunter.position()).multiply(1.0D, 0.0D, 1.0D);
    if (lane.horizontalDistanceSqr() < 1.0E-6D || !safeLocalCombatLane(hunter, lane)) {
      localMotion.stop(hunter);
      lastFailureTarget = target;
      activeTarget = null;
      stationaryMovementTicks = 0;
      return new MovementOutcome(
          false,
          buoyancyJump,
          null,
          MovementProgressState.FAILED,
          "local-evade-blocked",
          intent.forwardInput(),
          intent.strafeInput());
    }
    double speed = calibratedSpeed(frame.speed(), intent.sprint());
    hunter.performActuatorStrafe(intent.forwardInput(), intent.strafeInput(), speed);
    PlayerMotionController.MotionResult local = localMotion.apply(
        hunter,
        intent,
        frame.movement(),
        frame.gameTime(),
        () -> safeJumpLanding(hunter, target));
    stationaryMovementTicks = 0;
    return new MovementOutcome(
        true,
        buoyancyJump || local.jumped(),
        null,
        progress.madeProgress()
            ? MovementProgressState.PROGRESSING
            : MovementProgressState.PATH_ACCEPTED,
        local.jumped() ? "local-evade-jump" : "local-evade-strafe",
        intent.forwardInput(),
        intent.strafeInput());
  }

  private MovementOutcome restartLocalTacticalMovement(
      HunterEntity hunter, ActionFrame frame, Vec3 target, boolean buoyancyJump) {
    hunter.getNavigation().stop();
    localMotion.stop(hunter);
    activeTarget = null;
    activeRouteId = null;
    stationaryMovementTicks = 0;
    return applyLocalCombatMovement(hunter, frame, target, buoyancyJump);
  }

  private static boolean safeLocalCombatLane(HunterEntity hunter, Vec3 lane) {
    Vec3 step = lane.normalize().scale(0.42D);
    var moved = hunter.getBoundingBox().move(step.x, 0.0D, step.z);
    if (!hunter.level().noCollision(hunter, moved)) return false;
    return !hunter.level().noCollision(hunter, moved.move(0.0D, -0.25D, 0.0D));
  }

  private static boolean immediateStepObstacle(HunterEntity hunter, Vec3 target) {
    if (!hunter.onGround() || hunter.isClimbingLadder() || hunter.isInWater()) return false;
    Vec3 lane = target.subtract(hunter.position()).multiply(1.0D, 0.0D, 1.0D);
    if (lane.horizontalDistanceSqr() < 1.0E-6D) return false;
    Vec3 step = lane.normalize().scale(0.48D);
    var forward = hunter.getBoundingBox().move(step.x, 0.0D, step.z);
    if (hunter.level().noCollision(hunter, forward)) return hunter.horizontalCollision;
    return hunter.level().noCollision(hunter, forward.move(0.0D, 1.05D, 0.0D));
  }

  private ProgressCheck updateProgress(HunterEntity hunter, Vec3 target, long now) {
    if (lastProgressPosition == null) {
      lastProgressPosition = hunter.position();
      lastProgressTargetDistance = target == null ? Double.NaN : hunter.position().distanceTo(target);
      lastProgressAt = now;
      return new ProgressCheck(false, false);
    }
    double currentTargetDistance = target == null ? Double.NaN : hunter.position().distanceTo(target);
    MovementProgressState state = classifyMovementProgress(
        lastProgressPosition,
        hunter.position(),
        lastProgressTargetDistance,
        currentTargetDistance,
        lastProgressAt,
        now);
    if (state == MovementProgressState.PROGRESSING) {
      lastProgressPosition = hunter.position();
      lastProgressTargetDistance = currentTargetDistance;
      lastProgressAt = now;
      routeFailures = 0;
      lastFailureTarget = null;
      return new ProgressCheck(true, false);
    }
    return new ProgressCheck(false, state == MovementProgressState.STALLED);
  }

  static MovementProgressState classifyMovementProgress(
      Vec3 lastProgressPosition,
      Vec3 currentPosition,
      double lastTargetDistance,
      double currentTargetDistance,
      long lastProgressAt,
      long now) {
    if (lastProgressPosition == null || currentPosition == null)
      return MovementProgressState.PATH_ACCEPTED;
    boolean displaced = lastProgressPosition.distanceToSqr(currentPosition) >= ROUTE_PROGRESS_SQUARED;
    boolean converged = Double.isFinite(lastTargetDistance)
        && Double.isFinite(currentTargetDistance)
        && lastTargetDistance - currentTargetDistance >= ROUTE_TARGET_CONVERGENCE;
    if (displaced && converged)
      return MovementProgressState.PROGRESSING;
    return now >= lastProgressAt && now - lastProgressAt >= ROUTE_STALL_TICKS
        ? MovementProgressState.STALLED
        : MovementProgressState.PATH_ACCEPTED;
  }

  private static boolean safeJumpLanding(HunterEntity hunter, Vec3 destination) {
    Vec3 horizontal = destination.subtract(hunter.position()).multiply(1.0D, 0.0D, 1.0D);
    if (horizontal.lengthSqr() < 0.25D) return false;
    Vec3 direction = horizontal.normalize();
    for (double distance : JUMP_LANDING_PROBES) {
      Vec3 probe = hunter.position().add(direction.scale(distance));
      for (int yOffset : JUMP_LANDING_Y_OFFSETS) {
        BlockPos feet = BlockPos.containing(probe.x, hunter.getY() + yOffset, probe.z);
        if (safeSprintJumpCell(hunter, feet, probe.x, probe.z)) return true;
      }
    }
    return false;
  }

  private static boolean safeSprintJumpCell(
      HunterEntity hunter, BlockPos feet, double landingX, double landingZ) {
    var foot = hunter.level().getBlockState(feet);
    var head = hunter.level().getBlockState(feet.above());
    var support = hunter.level().getBlockState(feet.below());
    if (!foot.getCollisionShape(hunter.level(), feet).isEmpty()
        || !head.getCollisionShape(hunter.level(), feet.above()).isEmpty()
        || !foot.getFluidState().isEmpty()
        || !head.getFluidState().isEmpty()
        || support.getCollisionShape(hunter.level(), feet.below()).isEmpty()) return false;
    Vec3 landing = new Vec3(landingX, feet.getY(), landingZ);
    return hunter.level().noCollision(
        hunter, hunter.getBoundingBox().move(landing.subtract(hunter.position())));
  }

  private static boolean stepOffSafeDrop(HunterEntity hunter, Vec3 target, double speed, int maximumDrop) {
    if (!hunter.onGround() || hunter.isClimbingLadder() || hunter.isCrouching()) return false;
    Vec3 horizontal = target.subtract(hunter.position()).multiply(1.0D, 0.0D, 1.0D);
    if (horizontal.lengthSqr() < 1.0E-6D) return false;
    Vec3 direction = horizontal.normalize();
    for (double sideOffset : new double[] {0.0D, 0.35D, -0.35D}) {
      Vec3 side = new Vec3(-direction.z, 0.0D, direction.x);
      Vec3 stepDirection = direction.add(side.scale(sideOffset)).normalize();
      Vec3 edge = hunter.position().add(stepDirection.scale(1.15D));
      BlockPos column = BlockPos.containing(edge);
      for (int drop = 1; drop <= maximumDrop; drop++) {
        BlockPos feet = new BlockPos(column.getX(), hunter.blockPosition().getY() - drop, column.getZ());
        if (!safeLandingCell(hunter, feet)) continue;
        Vec3 landing = Vec3.atBottomCenterOf(feet);
        if (landing.distanceToSqr(target) + 1.0D >= hunter.position().distanceToSqr(target)) continue;
        hunter.setSprinting(false);
        hunter.getMoveControl().setWantedPosition(edge.x, hunter.getY(), edge.z, Math.max(0.70D, speed));
        return true;
      }
    }
    return false;
  }

  private static boolean safeLandingCell(HunterEntity hunter, BlockPos feet) {
    var foot = hunter.level().getBlockState(feet);
    var head = hunter.level().getBlockState(feet.above());
    var support = hunter.level().getBlockState(feet.below());
    return foot.isAir()
        && head.isAir()
        && foot.getFluidState().isEmpty()
        && head.getFluidState().isEmpty()
        && support.getFluidState().isEmpty()
        && support.isCollisionShapeFullBlock(hunter.level(), feet.below());
  }

  private static ViewOutcome applyView(HunterEntity hunter, ActionFrame frame) {
    if (frame.lookTarget() == null) return new ViewOutcome(false, Double.POSITIVE_INFINITY, "no-view-request");
    Vec3 offset = frame.lookTarget().subtract(hunter.getEyePosition());
    double horizontal = Math.sqrt(offset.x * offset.x + offset.z * offset.z);
    float desiredYaw = (float) (Mth.atan2(offset.z, offset.x) * (180.0D / Math.PI)) - 90.0F;
    float desiredPitch = (float) -(Mth.atan2(offset.y, horizontal) * (180.0D / Math.PI));
    boolean firing = frame.weapon() == WeaponCommand.ADS_FIRE || frame.weapon() == WeaponCommand.HIP_FIRE;
    String owner = frame.owners().get(ActionChannel.VIEW);
    double lookDistance = offset.length();
    float yawStep = combatYawStep(owner, firing, lookDistance);
    float pitchStep = combatPitchStep(owner, firing, lookDistance);
    boolean rotateBody = retainsCombatView(owner)
        || "route-awareness".equals(owner)
        || frame.movement() == MovementMode.HOLD && "vigilance".equals(owner);
    if (!rotateBody) {
      float headLimit = "vigilance".equals(owner) ? 110.0F : 80.0F;
      float bodyRelative = Mth.clamp(
          Mth.wrapDegrees(desiredYaw - hunter.yBodyRot), -headLimit, headLimit);
      desiredYaw = Mth.wrapDegrees(hunter.yBodyRot + bodyRelative);
    }
    float currentYaw = rotateBody ? hunter.getYRot() : hunter.getYHeadRot();
    float nextYaw = normalizedYawStep(currentYaw, desiredYaw, yawStep);
    float nextPitch = Mth.approachDegrees(hunter.getXRot(), desiredPitch, pitchStep);
    applyRotation(hunter, nextYaw, nextPitch, rotateBody);
    double error = Math.max(Math.abs(Mth.wrapDegrees(desiredYaw - nextYaw)), Math.abs(desiredPitch - nextPitch));
    return new ViewOutcome(true, error, "applied");
  }

  private void retainCombatView(HunterEntity hunter, ActionFrame frame, ViewOutcome view) {
    String owner = frame.owners().get(ActionChannel.VIEW);
    if (!view.applied() || !retainsCombatView(owner)) {
      retainedCombatViewUntil = Long.MIN_VALUE;
      return;
    }
    retainedCombatYaw = hunter.getYRot();
    retainedCombatPitch = hunter.getXRot();
    retainedCombatViewUntil = frame.gameTime() + 2L;
  }

  static boolean retainsCombatView(String owner) {
    return "combat-controller".equals(owner) || "damage-reaction".equals(owner);
  }

  static float normalizedYawStep(float currentYaw, float desiredYaw, float maximumStep) {
    return Mth.wrapDegrees(
        Mth.approachDegrees(
            Mth.wrapDegrees(currentYaw), Mth.wrapDegrees(desiredYaw), maximumStep));
  }

  static float combatYawStep(String owner, boolean firing) {
    return combatYawStep(owner, firing, Double.POSITIVE_INFINITY);
  }

  static float combatYawStep(String owner, boolean firing, double distance) {
    if ("combat-controller".equals(owner))
      return CloseRangeCombatTuning.combatYawStep(firing, distance);
    if ("damage-reaction".equals(owner)) return 14.0F;
    return 9.0F;
  }

  static float combatPitchStep(String owner, boolean firing) {
    return combatPitchStep(owner, firing, Double.POSITIVE_INFINITY);
  }

  static float combatPitchStep(String owner, boolean firing, double distance) {
    if ("combat-controller".equals(owner))
      return CloseRangeCombatTuning.combatPitchStep(firing, distance);
    if ("damage-reaction".equals(owner)) return 10.0F;
    return 7.0F;
  }

  private static void applyRotation(
      HunterEntity hunter, float yaw, float pitch, boolean rotateBody) {
    float normalizedYaw = Mth.wrapDegrees(yaw);
    float normalizedPitch = Mth.clamp(pitch, -90.0F, 90.0F);
    hunter.setYHeadRot(normalizedYaw);
    if (rotateBody) {
      hunter.setYRot(normalizedYaw);
      hunter.yBodyRot = normalizedYaw;
    }
    hunter.setXRot(normalizedPitch);
  }

  private WeaponOutcome applyWeapon(
      HunterEntity hunter, WeaponCommand command, Vec3 lookTarget, ViewOutcome view) {
    if (!weapon.ensureEquipped(hunter)) return new WeaponOutcome(false, "weapon-unavailable");
    // Run manual-action maintenance independently from the combat cadence. This is a normal TaCZ
    // bolt request, not a synthetic reload or a shortened gun cooldown.
    boolean manualActionStarted = command != WeaponCommand.RELOAD
        && command != WeaponCommand.SELECT_BEST_RANGE
        && weapon.cycleManualActionIfReady(hunter);
    switch (command) {
      case LOWER -> weapon.lowerWeapon(hunter);
      case ADS -> weapon.raiseWeapon(hunter);
      case SELECT_BEST_RANGE -> {
        HunterWeaponAdapter.EmptyWeaponAction recovery = weapon.recoverEmptyWeapon(hunter);
        if (recovery == HunterWeaponAdapter.EmptyWeaponAction.RELOAD_REQUESTED)
          return new WeaponOutcome(true, "range-selection-reload");
        double distance = lookTarget == null
            ? Double.NaN
            : hunter.getEyePosition().distanceTo(lookTarget);
        HunterWeaponAdapter.RangeSelection selection = weapon.selectForDistance(hunter, distance);
        weapon.raiseWeapon(hunter);
        String gun = selection.gunId() == null ? "none" : selection.gunId().toString();
        return new WeaponOutcome(
            selection.gunId() != null,
            (selection.switched() ? "switched-range:" : "range-current:")
                + gun
                + ";status=" + selection.status()
                + ";ready=" + selection.readyToFire()
                + ";score=" + String.format(java.util.Locale.ROOT, "%.2f", selection.score())
                + ";reason=" + selection.reason());
      }
      case RELOAD -> {
        weapon.lowerWeapon(hunter);
        HunterWeaponAdapter.EmptyWeaponAction recovery = weapon.recoverEmptyWeapon(hunter);
        if (recovery == HunterWeaponAdapter.EmptyWeaponAction.SWITCHED)
          return new WeaponOutcome(true, "switched-secondary");
      }
      case ADS_FIRE -> {
        weapon.raiseWeapon(hunter);
        if (manualActionStarted) return new WeaponOutcome(true, "IS_BOLTING");
        if (FireSafety.allyBlocksShot(hunter, lookTarget))
          return new WeaponOutcome(true, "FRIENDLY_LINE_BLOCKED");
        if (!view.applied() || view.errorDegrees() > 3.5D) return new WeaponOutcome(true, "aim-aligning");
        if (!weapon.isAimReady(hunter)) return new WeaponOutcome(true, "ADS_TRANSITION");
        if (weapon.shootCooldownRemainingTicks(hunter) > 0L)
          return new WeaponOutcome(true, "NATIVE_COOLDOWN");
        return new WeaponOutcome(
            true,
            weapon.tryFire(hunter)
                ? "fired-ads:" + weapon.lastShootDiagnostic()
                : weapon.lastShootResult());
      }
      case HIP_FIRE -> {
        weapon.lowerWeapon(hunter);
        if (manualActionStarted) return new WeaponOutcome(true, "IS_BOLTING");
        if (FireSafety.allyBlocksShot(hunter, lookTarget))
          return new WeaponOutcome(true, "FRIENDLY_LINE_BLOCKED");
        if (!view.applied() || view.errorDegrees() > 6.5D) return new WeaponOutcome(true, "hip-aligning");
        if (weapon.shootCooldownRemainingTicks(hunter) > 0L)
          return new WeaponOutcome(true, "NATIVE_COOLDOWN");
        return new WeaponOutcome(
            true,
            weapon.tryFire(hunter)
                ? "fired-hip:" + weapon.lastShootDiagnostic()
                : weapon.lastShootResult());
      }
      case HOLD -> {
        HunterWeaponAdapter.EmptyWeaponAction recovery = weapon.recoverEmptyWeapon(hunter);
        if (recovery == HunterWeaponAdapter.EmptyWeaponAction.SWITCHED)
          return new WeaponOutcome(true, "switched-secondary");
        if (recovery == HunterWeaponAdapter.EmptyWeaponAction.RELOAD_REQUESTED)
          return new WeaponOutcome(true, "auto-reload");
      }
    }
    return new WeaponOutcome(
        true,
        command.name().toLowerCase(java.util.Locale.ROOT)
            + (manualActionStarted ? ";manual-action-started" : ""));
  }

  static double distanceToSegmentSquared(Vec3 point, Vec3 start, Vec3 end) {
    return FireSafety.distanceToSegmentSquared(point, start, end);
  }

  private InteractionOutcome applyInteraction(HunterEntity hunter, InteractionCommand command) {
    if (command == InteractionCommand.NONE) return new InteractionOutcome(false, "none");
    if (command == InteractionCommand.RECOVER_HEALTH) {
      long now = hunter.level().getGameTime();
      if (now - lastHealthRecoveryAt < 20L) return new InteractionOutcome(false, "recovery-cooldown");
      if (hunter.isDeadOrDying() || hunter.getHealth() >= hunter.getMaxHealth())
        return new InteractionOutcome(false, "recovery-not-needed");
      hunter.heal(5.0F);
      lastHealthRecoveryAt = now;
      return new InteractionOutcome(true, "recovered-5");
    }
    HuntMcHunterAi mod = HuntMcHunterAi.instance();
    if (mod == null || !hunter.isMatchBot()) return new InteractionOutcome(false, "match-service-unavailable");
    String result = mod.hunterMatchService().submitInteraction(hunter, command);
    return new InteractionOutcome(
        "accepted".equals(result) || result.startsWith("in-progress"), result);
  }

  private static boolean applyPosture(HunterEntity hunter, PostureCommand posture) {
    boolean crouch = posture == PostureCommand.CROUCH;
    if (hunter.isShiftKeyDown() != crouch) hunter.setShiftKeyDown(crouch);
    Pose desired = crouch ? Pose.CROUCHING : Pose.STANDING;
    if (hunter.getPose() != desired) hunter.setPose(desired);
    if (crouch) hunter.setSprinting(false);
    return true;
  }

  static double calibratedSpeed(double requested, boolean sprint) {
    double base = requested <= 0.0D ? (sprint ? 1.0D : 0.95D) : requested;
    if (!sprint) return Mth.clamp(base, 0.70D, 0.95D);

    // Entity#setSprinting only synchronizes the sprint flag for a PathfinderMob. Unlike Player,
    // it does not install the vanilla 30% movement-speed modifier, so include the acceleration in
    // the navigation multiplier inside the single actuator ownership boundary.
    return Mth.clamp(base * 1.22D, 1.18D, 1.24D);
  }

  static boolean partialPathMakesProgress(Vec3 current, Vec3 end, Vec3 target) {
    if (current == null || end == null || target == null) return false;
    double currentDistance = current.distanceTo(target);
    double remainingDistance = end.distanceTo(target);
    double requiredProgress = Math.max(3.0D, Math.min(8.0D, currentDistance * 0.12D));
    return current.distanceToSqr(end) > PARTIAL_PATH_PROGRESS_MARGIN_SQUARED
        && currentDistance - remainingDistance >= requiredProgress;
  }

  static boolean pathEndOffersExecutableProgress(Vec3 current, Vec3 end) {
    return current != null
        && end != null
        && current.distanceToSqr(end) > ARRIVAL_DISTANCE_SQUARED;
  }

  static boolean movementTargetReached(Vec3 current, Vec3 target) {
    if (current == null || target == null) return false;
    double x = current.x - target.x;
    double z = current.z - target.z;
    return x * x + z * z <= MOVEMENT_ARRIVAL_HORIZONTAL_SQUARED
        && Math.abs(current.y - target.y) <= MOVEMENT_ARRIVAL_VERTICAL;
  }

  static boolean canUseLocalTacticalFallback(
      String owner, UUID routeId, Vec3 current, Vec3 target) {
    if (routeId != null || owner == null || current == null || target == null) return false;
    boolean localOwner = owner.startsWith("combat-") || "health-recovery".equals(owner);
    return localOwner
        && Math.abs(current.y - target.y) <= MOVEMENT_ARRIVAL_VERTICAL
        && current.distanceToSqr(target) <= LOCAL_TACTICAL_FALLBACK_DISTANCE_SQUARED;
  }

  private static boolean pathOffersExecutableProgress(Vec3 current, Path path) {
    return path != null
        && path.getNodeCount() > 0
        && !path.isDone()
        && pathEndOffersExecutableProgress(current, pathEnd(path));
  }

  static Vec3 executionTarget(Vec3 requestedTarget, Vec3 pathEnd, boolean completePath) {
    return completePath || pathEnd == null ? requestedTarget : pathEnd;
  }

  static Vec3 localMovementTarget(Path path, HunterEntity hunter, Vec3 fallback) {
    return path == null || path.isDone() ? fallback : path.getNextEntityPos(hunter);
  }

  private static Vec3 pathEnd(Path path) {
    return path == null || path.getEndNode() == null ? null : path.getEndNode().asVec3();
  }

  private void recordRouteFailure(
      UUID routeId, Vec3 origin, Vec3 target, String reason, long now) {
    routeFailures++;
    lastFailureTarget = target;
    if (routeId != null) routeHandles.remove(routeId);
    routeFailureMemory.record(routeId, origin, target, reason, now);
  }

  private void pruneRouteHandles(long now) {
    routeHandles.values().removeIf(handle -> now - handle.validatedAt() > 2400L);
    if (routeHandles.size() <= 96) return;
    routeHandles.entrySet().stream()
        .min(Map.Entry.comparingByValue(
            (first, second) -> Long.compare(first.validatedAt(), second.validatedAt())))
        .map(Map.Entry::getKey)
        .ifPresent(routeHandles::remove);
  }

  private record MovementOutcome(
      boolean accepted,
      boolean jumped,
      Vec3 pathEnd,
      MovementProgressState progress,
      String result,
      float forwardInput,
      float strafeInput) {}

  private record ProgressCheck(boolean madeProgress, boolean stalled) {}

  private record ViewOutcome(boolean applied, double errorDegrees, String result) {}

  private record WeaponOutcome(boolean applied, String result) {}

  private record InteractionOutcome(boolean accepted, String result) {}
}
