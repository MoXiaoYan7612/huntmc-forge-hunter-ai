package cn.huntmc.hunterai.navigation;

import cn.huntmc.hunterai.entity.HunterEntity;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;

/** Gives a path-driven Hunter the same jump input a player uses to climb an upward ladder. */
public final class HunterLadderMoveControl extends MoveControl {
  private static final double VERTICAL_CONTROL_EPSILON = 0.03D;
  private static final double HORIZONTAL_CONTROL_EPSILON = 1.0E-4D;

  private final HunterEntity hunter;
  private boolean playerLikeStrafe;

  public HunterLadderMoveControl(HunterEntity hunter) {
    super(hunter);
    this.hunter = hunter;
  }

  /**
   * Queues one player-shaped local movement input for the next native movement tick.
   *
   * <p>{@link MoveControl#strafe(float, float)} hard-codes a 0.25 speed modifier, which made a
   * full-strength combat dodge move only a few centimetres. The actuator has already collision
   * checked this input, so retain its calibrated speed while still letting LivingEntity travel
   * apply normal acceleration, friction and collision resolution.
   */
  public void strafePlayerLike(float forward, float strafe, double requestedSpeed) {
    operation = Operation.STRAFE;
    speedModifier = Mth.clamp(requestedSpeed, 0.70D, 1.24D);
    strafeForwards = Mth.clamp(forward, -1.0F, 1.0F);
    strafeRight = Mth.clamp(strafe, -1.0F, 1.0F);
    playerLikeStrafe = true;
  }

  @Override
  public void tick() {
    if (operation == Operation.STRAFE && playerLikeStrafe) {
      operation = Operation.WAIT;
      playerLikeStrafe = false;
      hunter.setSpeed(
          (float) (speedModifier * hunter.getAttributeValue(Attributes.MOVEMENT_SPEED)));
      hunter.setZza(strafeForwards);
      hunter.setXxa(strafeRight);
      return;
    }
    playerLikeStrafe = false;
    if (operation == Operation.MOVE_TO && hunter.isClimbingLadder()) {
      double horizontalDistanceSquared =
          (wantedX - hunter.getX()) * (wantedX - hunter.getX())
              + (wantedZ - hunter.getZ()) * (wantedZ - hunter.getZ());
      double verticalOffset = wantedY - hunter.getY();
      if (Math.abs(verticalOffset) > VERTICAL_CONTROL_EPSILON) {
        // Do not leave MoveControl in JUMPING state: its stock implementation only exits that
        // state on solid ground, which freezes a mob halfway up a ladder. A top/bottom exit can
        // also require a sideways move, so retain a normal forward input while holding climb.
        operation = Operation.WAIT;
        hunter.setSpeed((float) (speedModifier * hunter.getAttributeValue(Attributes.MOVEMENT_SPEED)));
        if (horizontalDistanceSquared > HORIZONTAL_CONTROL_EPSILON) {
          float yaw =
              (float) (Mth.atan2(wantedZ - hunter.getZ(), wantedX - hunter.getX()) * (180.0D / Math.PI))
                  - 90.0F;
          hunter.setYRot(rotlerp(hunter.getYRot(), yaw, 90.0F));
          hunter.setZza(1.0F);
        } else {
          hunter.setZza(0.0F);
        }
        hunter.setXxa(0.0F);
        if (verticalOffset > 0.0D) hunter.getJumpControl().jump();
        return;
      }
    }
    super.tick();
  }
}
