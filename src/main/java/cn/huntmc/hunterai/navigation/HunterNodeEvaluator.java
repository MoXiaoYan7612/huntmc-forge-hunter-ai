package cn.huntmc.hunterai.navigation;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * Keeps vanilla land-node behaviour (including stairs) while adding legal vertical edges inside
 * climbable blocks. Nodes are still collision and headroom checked by {@link WalkNodeEvaluator};
 * this only teaches the path finder that a ladder has an upward and downward continuation.
 */
public final class HunterNodeEvaluator extends WalkNodeEvaluator {
  private static final float CLIMBABLE_COST_MALUS = 0.40F;

  @Override
  public Node getStart() {
    Node climbableStart = climbableNode(mob.blockPosition());
    return climbableStart != null ? climbableStart : super.getStart();
  }

  @Override
  public int getNeighbors(Node[] neighbors, Node current) {
    int count = super.getNeighbors(neighbors, current);
    BlockPos currentPos = current.asBlockPos();
    if (!isClimbable(currentPos)) return count;

    count = addNeighbor(neighbors, count, climbableNode(currentPos.above()));
    count = addNeighbor(neighbors, count, climbableNode(currentPos.below()));

    // A landing normally sits one block above the top rung (or one block below the lowest rung).
    // WalkNodeEvaluator handles level exits itself; these two bounded alternatives handle the
    // vertical offset without allowing a diagonal shortcut through a ceiling.
    for (Direction direction : Direction.Plane.HORIZONTAL) {
      BlockPos adjacent = currentPos.relative(direction);
      count = addNeighbor(neighbors, count, dismountNode(adjacent.above()));
      count = addNeighbor(neighbors, count, dismountNode(adjacent.below()));
    }
    return count;
  }

  /** Treat tagged ladders/vines as occupied walking cells only after the full mob-volume check. */
  @Override
  public BlockPathTypes getBlockPathType(BlockGetter level, int x, int y, int z) {
    BlockPos position = new BlockPos(x, y, z);
    return isClimbable(level, position)
        ? BlockPathTypes.WALKABLE
        : super.getBlockPathType(level, x, y, z);
  }

  private Node climbableNode(BlockPos position) {
    if (!isClimbable(position)) return null;
    BlockPathTypes type = getCachedBlockType(mob, position.getX(), position.getY(), position.getZ());
    float malus = mob.getPathfindingMalus(type);
    if (malus < 0.0F) return null;

    Node node = getNode(position);
    node.type = BlockPathTypes.WALKABLE;
    node.costMalus = Math.max(node.costMalus, malus + CLIMBABLE_COST_MALUS);
    return node;
  }

  private Node dismountNode(BlockPos position) {
    if (isClimbable(position)) return null;
    BlockPathTypes type = getCachedBlockType(mob, position.getX(), position.getY(), position.getZ());
    float malus = mob.getPathfindingMalus(type);
    if (malus < 0.0F || (type != BlockPathTypes.WALKABLE && type != BlockPathTypes.WALKABLE_DOOR)) {
      return null;
    }

    Node node = getNode(position);
    node.type = type;
    node.costMalus = Math.max(node.costMalus, malus);
    return node;
  }

  private int addNeighbor(Node[] neighbors, int count, Node candidate) {
    if (candidate == null || candidate.closed || count >= neighbors.length) return count;
    for (int index = 0; index < count; index++) {
      if (neighbors[index].equals(candidate)) return count;
    }
    neighbors[count] = candidate;
    return count + 1;
  }

  private boolean isClimbable(BlockPos position) {
    return isClimbable(level, position);
  }

  static boolean isClimbable(BlockGetter level, BlockPos position) {
    return level.getBlockState(position).is(BlockTags.CLIMBABLE);
  }
}
