package cn.huntmc.hunterai.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class TacticalSpaceGraphTest {
  @Test
  void nearbyThreeDimensionalNodesReceiveBidirectionalEdges() {
    TacticalNode first = new TacticalNode(new Vec3(0.0D, 64.0D, 0.0D), 0.8D, 0.6D, false, true, 3, 0.0D, 0.8D);
    TacticalNode second = new TacticalNode(new Vec3(4.0D, 66.0D, 0.0D), 0.6D, 0.5D, false, true, 2, 2.0D, 0.7D);
    TacticalNode distant = new TacticalNode(new Vec3(30.0D, 64.0D, 0.0D), 0.4D, 0.2D, true, false, 1, 0.0D, 0.3D);

    TacticalSpace space = new TacticalSpace(Vec3.ZERO, null, List.of(first, second, distant), 0.4D, 0.0D);

    assertEquals(2, space.edges().size());
    assertTrue(space.edges().stream().allMatch(TacticalEdge::traversable));
  }
}
