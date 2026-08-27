package cn.huntmc.hunterai.action;

import cn.huntmc.hunterai.entity.HunterEntity;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Applies continuous player-like movement input on top of a validated navigation corridor. */
public final class PlayerMotionController {
  private static final long MINIMUM_SPRINT_RUN_UP_TICKS = 4L;
  private static final double MINIMUM_SPRINT_SPEED_SQUARED = 0.085D * 0.085D;
  private int jumpsRemaining;
  private long nextJumpAt;
  private long nextBurstAt = Long.MIN_VALUE;
  private long sprintStartedAt = Long.MIN_VALUE;
  private boolean waitingForBurstLanding;
  private boolean combatDodgeJumpConsumed;
  private MovementMode previousMode = MovementMode.HOLD;

  public PlayerMotionIntent intent(ActionFrame frame, HunterEntity hunter) {
    return intent(frame, hunter, frame.moveTarget());
  }

  public PlayerMotionIntent intent(ActionFrame frame, HunterEntity hunter, Vec3 localTarget) {
    boolean moving = frame.moveTarget() != null && frame.movement() != MovementMode.HOLD;
    String movementOwner = frame.owners().get(ActionChannel.MOVEMENT);
    boolean sprint = moving
        && !hunter.isCrouching()
        && !hunter.isClimbingLadder()
        && shouldSprint(frame.movement(), movementOwner);
    boolean combatStability = frame.weapon() == WeaponCommand.ADS || frame.weapon() == WeaponCommand.ADS_FIRE;
    RelativeInput movementInput = relativeMovementInput(frame, hunter, localTarget, moving);
    return new PlayerMotionIntent(
        localTarget,
        movementInput.forward(),
        movementInput.strafe(),
        sprint,
        (frame.movement() == MovementMode.SPRINT_JUMP_BURST
                || frame.movement() == MovementMode.COMBAT_DODGE_JUMP)
            && !combatStability,
        frame.posture() == PostureCommand.CROUCH,
        frame.lookTarget(),
        frame.gameTime() + 1L,
        combatStability
            ? PlayerMotionIntent.StopCondition.COMBAT_STABILITY
            : PlayerMotionIntent.StopCondition.ARRIVAL_OR_BLOCKED);
  }

  private static RelativeInput relativeMovementInput(
      ActionFrame frame, HunterEntity hunter, Vec3 localTarget, boolean moving) {
    if (!moving || localTarget == null) return new RelativeInput(0.0F, 0.0F);
    String owner = frame.owners().get(ActionChannel.MOVEMENT);
    boolean combatEvasion = "combat-evasion".equals(owner)
        || "damage-reaction".equals(owner);
    if (!combatEvasion || frame.lookTarget() == null) return new RelativeInput(1.0F, 0.0F);
    Vec3 movement = localTarget.subtract(hunter.position()).multiply(1.0D, 0.0D, 1.0D);
    Vec3 facing = frame.lookTarget().subtract(hunter.getEyePosition()).multiply(1.0D, 0.0D, 1.0D);
    return relativeInput(movement, facing);
  }

  static RelativeInput relativeInput(Vec3 movement, Vec3 facing) {
    if (movement == null || facing == null
        || movement.horizontalDistanceSqr() < 1.0E-8D
        || facing.horizontalDistanceSqr() < 1.0E-8D) return new RelativeInput(0.0F, 0.0F);
    Vec3 desired = movement.normalize();
    Vec3 forward = facing.normalize();
    Vec3 left = new Vec3(forward.z, 0.0D, -forward.x);
    return new RelativeInput(
        (float) Mth.clamp(desired.dot(forward), -1.0D, 1.0D),
        (float) Mth.clamp(desired.dot(left), -1.0D, 1.0D));
  }

  public MotionResult apply(
      HunterEntity hunter,
      PlayerMotionIntent intent,
      MovementMode mode,
      long now,
      BooleanSupplier safeLanding) {
    if (intent.target() == null || mode == MovementMode.HOLD) {
      stop(hunter);
      previousMode = mode;
      return new MotionResult(false, false, "local-hold");
    }

    if (intent.sprint() && !sprintMode(previousMode)) sprintStartedAt = now;
    if (!intent.sprint()) sprintStartedAt = Long.MIN_VALUE;
    hunter.setSprinting(intent.sprint());
    // Navigation owns body yaw; vigilance now rotates only the head while moving. Preserve the
    // native Mob input so MoveControl can accelerate and collide normally on Forge.
    hunter.setZza(intent.forwardInput());
    hunter.setXxa(intent.strafeInput());
    boolean jumped = false;
    updateBurstLanding(hunter, now);
    if (mode == MovementMode.COMBAT_DODGE_JUMP
        && intent.jumpRequested()
        && !combatDodgeJumpConsumed
        && hunter.onGround()
        && !hunter.isInWater()
        && !hunter.isClimbingLadder()
        && !lowCeiling(hunter)
        && safeLanding.getAsBoolean()) {
      jumped = hunter.performActuatorJump();
      combatDodgeJumpConsumed = jumped;
    } else if (mode == MovementMode.SPRINT_JUMP_BURST
        && intent.jumpRequested()
        && !waitingForBurstLanding
        && (nextBurstAt == Long.MIN_VALUE || now >= nextBurstAt)
        && hunter.onGround()
        && !hunter.isInWater()
        && !hunter.isClimbingLadder()
        && !lowCeiling(hunter)
        && sprintRunUpReady(now, sprintStartedAt, hunter.getDeltaMovement().horizontalDistanceSqr())
        && alignedToRoute(hunter, intent.target(), 28.0F)
        && safeLanding.getAsBoolean()) {
      if (previousMode != MovementMode.SPRINT_JUMP_BURST || jumpsRemaining <= 0) {
        jumpsRemaining = plannedJumpCount(hunter.getUUID(), now);
      }
      if (now >= nextJumpAt) {
        jumped = hunter.performActuatorJump();
        if (jumped) {
          jumpsRemaining--;
          nextJumpAt = now + 1L;
          if (jumpsRemaining <= 0) {
            waitingForBurstLanding = true;
            nextBurstAt = Long.MIN_VALUE;
          }
        }
      }
    } else if (mode != MovementMode.SPRINT_JUMP_BURST) {
      jumpsRemaining = 0;
      nextBurstAt = Long.MIN_VALUE;
      waitingForBurstLanding = false;
    }
    if (mode != MovementMode.COMBAT_DODGE_JUMP) combatDodgeJumpConsumed = false;
    previousMode = mode;
    return new MotionResult(
        hunter.isSprinting(),
        jumped,
        jumped
            ? mode == MovementMode.COMBAT_DODGE_JUMP ? "combat-dodge-jump" : "sprint-jump"
            : "navigation-locomotion");
  }

  public void stop(HunterEntity hunter) {
    hunter.setSprinting(false);
    hunter.setZza(0.0F);
    hunter.setXxa(0.0F);
    jumpsRemaining = 0;
    nextBurstAt = Long.MIN_VALUE;
    waitingForBurstLanding = false;
    combatDodgeJumpConsumed = false;
    sprintStartedAt = Long.MIN_VALUE;
    previousMode = MovementMode.HOLD;
  }

  private void updateBurstLanding(HunterEntity hunter, long now) {
    if (!waitingForBurstLanding || !hunter.onGround()) return;
    if (nextBurstAt == Long.MIN_VALUE) {
      nextBurstAt = now + 8L + Math.floorMod(mix(hunter.getUUID(), now + 31L), 15);
    }
    if (now >= nextBurstAt) {
      waitingForBurstLanding = false;
      jumpsRemaining = 0;
    }
  }

  static boolean alignedToRoute(HunterEntity hunter, Vec3 target, float maximumError) {
    Vec3 offset = target.subtract(hunter.position());
    if (offset.horizontalDistanceSqr() < 1.0E-6D) return false;
    float desiredYaw = (float) (Mth.atan2(offset.z, offset.x) * (180.0D / Math.PI)) - 90.0F;
    Vec3 velocity = hunter.getDeltaMovement().multiply(1.0D, 0.0D, 1.0D);
    if (velocity.horizontalDistanceSqr() < 1.0E-4D) return true;
    float movementYaw =
        (float) (Mth.atan2(velocity.z, velocity.x) * (180.0D / Math.PI)) - 90.0F;
    return Math.abs(Mth.wrapDegrees(desiredYaw - movementYaw)) <= maximumError;
  }

  static boolean sprintRunUpReady(long now, long sprintStartedAt, double horizontalSpeedSquared) {
    return sprintStartedAt != Long.MIN_VALUE
        && now >= sprintStartedAt
        && now - sprintStartedAt >= MINIMUM_SPRINT_RUN_UP_TICKS
        && horizontalSpeedSquared >= MINIMUM_SPRINT_SPEED_SQUARED;
  }

  static int plannedJumpCount(UUID hunterId, long now) {
    return 2 + Math.floorMod(mix(hunterId, now), 3);
  }

  private static boolean sprintMode(MovementMode mode) {
    return mode == MovementMode.SPRINT
        || mode == MovementMode.SPRINT_JUMP_BURST
        || mode == MovementMode.EVADE;
  }

  private static boolean lowCeiling(HunterEntity hunter) {
    var head = hunter.blockPosition().above(2);
    return !hunter.level().getBlockState(head).getCollisionShape(hunter.level(), head).isEmpty();
  }

  private static int mix(UUID id, long salt) {
    long value = id.getMostSignificantBits() ^ Long.rotateLeft(id.getLeastSignificantBits(), 21) ^ salt;
    value ^= value >>> 33;
    value *= 0xff51afd7ed558ccdL;
    return (int) (value ^ (value >>> 32));
  }

  static boolean shouldSprint(MovementMode mode, String owner) {
    if ("combat-evasion".equals(owner)) return false;
    return sprintMode(mode);
  }

  public record MotionResult(boolean sprinting, boolean jumped, String reason) {}

  record RelativeInput(float forward, float strafe) {}
}
