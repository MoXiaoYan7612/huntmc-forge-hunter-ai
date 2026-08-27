package cn.huntmc.hunterai.navigation;

import cn.huntmc.hunterai.entity.HunterEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.phys.Vec3;

/** Ground navigation with deliberate, collision-validated ladder traversal for Hunters. */
public final class HunterPathNavigation extends GroundPathNavigation {
  static final double LADDER_VERTICAL_WAYPOINT_TOLERANCE = 0.20D;

  private final HunterEntity hunter;

  public HunterPathNavigation(HunterEntity hunter, Level level) {
    super(hunter, level);
    this.hunter = hunter;
  }

  @Override
  protected PathFinder createPathFinder(int maxVisitedNodes) {
    nodeEvaluator = new HunterNodeEvaluator();
    nodeEvaluator.setCanPassDoors(true);
    return new PathFinder(nodeEvaluator, maxVisitedNodes);
  }

  /** Vanilla ground navigation pauses in mid-air; a Hunter must keep advancing while on a ladder. */
  @Override
  protected boolean canUpdatePath() {
    return super.canUpdatePath() || hunter.onClimbable();
  }

  @Override
  protected Vec3 getTempMobPos() {
    return hunter.onClimbable() ? hunter.position() : super.getTempMobPos();
  }

  /** A ladder has no floor collision shape, so its node Y is the requested movement height. */
  @Override
  protected double getGroundY(Vec3 target) {
    return isNextNodeClimbable() ? target.y : super.getGroundY(target);
  }

  /**
   * GroundPathNavigation advances a normal node when it is within one vertical block. That is
   * correct for stairs, but would skip an entire ladder column. Ladder nodes require the Hunter
   * to actually reach the rung before the next vertical command is issued.
   */
  @Override
  protected void followThePath() {
    if (path != null && !path.isDone() && isNextNodeClimbable()) {
      Vec3 position = getTempMobPos();
      maxDistanceToWaypoint =
          hunter.getBbWidth() > 0.75F ? hunter.getBbWidth() / 2.0F : 0.75F - hunter.getBbWidth() / 2.0F;
      Vec3i node = path.getNextNodePos();
      double horizontalDistance =
          Math.hypot(
              hunter.getX() - (node.getX() + (hunter.getBbWidth() + 1.0D) / 2.0D),
              hunter.getZ() - (node.getZ() + (hunter.getBbWidth() + 1.0D) / 2.0D));
      double verticalDistance = Math.abs(hunter.getY() - node.getY());
      if (reachedLadderWaypoint(horizontalDistance, verticalDistance, maxDistanceToWaypoint)) {
        path.advance();
      }
      doStuckDetection(position);
      return;
    }
    super.followThePath();
  }

  public boolean isClimbingLadder() {
    return hunter.onClimbable() && path != null && !path.isDone();
  }

  public ClimbDirection climbDirection() {
    if (!isClimbingLadder()) return ClimbDirection.NONE;
    Node next = path.getNextNode();
    return climbDirection(hunter.getY(), next.y);
  }

  static boolean reachedLadderWaypoint(
      double horizontalDistance, double verticalDistance, double horizontalTolerance) {
    return horizontalDistance <= horizontalTolerance
        && verticalDistance <= LADDER_VERTICAL_WAYPOINT_TOLERANCE;
  }

  static ClimbDirection climbDirection(double currentY, double targetY) {
    if (targetY > currentY + 0.05D) return ClimbDirection.UP;
    if (targetY < currentY - 0.05D) return ClimbDirection.DOWN;
    return ClimbDirection.NONE;
  }

  private boolean isNextNodeClimbable() {
    return path != null && !path.isDone() && isClimbableNode(path.getNextNode());
  }

  private boolean isClimbableNode(Node node) {
    return HunterNodeEvaluator.isClimbable(level, new BlockPos(node.x, node.y, node.z));
  }

  public enum ClimbDirection {
    UP,
    DOWN,
    NONE
  }
}
