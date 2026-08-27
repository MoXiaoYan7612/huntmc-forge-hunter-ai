package cn.huntmc.hunterai.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class TacticalLayoutTest {
  @Test
  void retainsSpatialAlternativesInsteadOfOnlyThePrimaryNode() {
    TacticalNode approach = node(0.0D, false);
    TacticalNode waiting = node(3.0D, false);
    TacticalNode firing = node(6.0D, true);
    TacticalNode secondary = node(10.0D, true);
    TacticalNode retreat = node(-5.0D, false);
    TacticalLayout layout =
        new TacticalLayout(approach, waiting, firing, secondary, retreat, approach, 0.92D, 0.18D);

    assertTrue(layout.hasDistinctRetreat());
    assertEquals(secondary, layout.fallbackFor(HunterIntent.OVERWATCH));
    assertEquals(firing, layout.fallbackFor(HunterIntent.FLANK));
  }

  private static TacticalNode node(double x, boolean observesThreat) {
    return new TacticalNode(
        new Vec3(x, 64.0D, 0.0D), 0.60D, 1.0D, false, observesThreat, 2, 0.0D, 0.80D);
  }
}
