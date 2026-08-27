package cn.huntmc.hunterai.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class TacticalNodeTest {
  @Test
  void identifiesOpenGroundAsExposed() {
    TacticalNode node =
        new TacticalNode(new Vec3(0.5D, 64.0D, 0.5D), 0.25D, 0.0D, true, false, 3, 0.0D, 0.2D);

    assertTrue(node.isExposed());
    assertFalse(node.isConcealed());
  }

  @Test
  void identifiesIndoorAndHardCoverNodesAsConcealed() {
    TacticalNode indoor =
        new TacticalNode(new Vec3(0.5D, 64.0D, 0.5D), 0.0D, 1.0D, false, false, 2, 0.0D, 0.5D);
    TacticalNode wallCover =
        new TacticalNode(new Vec3(0.5D, 64.0D, 0.5D), 0.50D, 0.0D, true, false, 2, 0.0D, 0.4D);

    assertTrue(indoor.isConcealed());
    assertTrue(wallCover.isConcealed());
  }
}
