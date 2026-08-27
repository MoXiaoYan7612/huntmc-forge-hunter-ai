package cn.huntmc.hunterai.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class RecentBehaviorTest {
  @Test
  void appliesAndExpiresSoftRepetitionPenalty() {
    RecentBehavior history = new RecentBehavior();
    history.recordIntent(HunterIntent.FLANK, 100L);
    history.recordPosition(new Vec3(1.0D, 64.0D, 1.0D), 100L);

    assertTrue(history.intentPenalty(HunterIntent.FLANK, 120L) > 0.0D);
    assertTrue(history.positionPenalty(new Vec3(2.0D, 64.0D, 1.0D), 120L) > 0.0D);
    assertEquals(0.0D, history.intentPenalty(HunterIntent.FLANK, 340L));
  }

  @Test
  void frequentShotsDoNotEvictRouteOrIntentHistory() {
    RecentBehavior history = new RecentBehavior();
    history.recordIntent(HunterIntent.FLANK, 100L);
    history.recordPosition(new Vec3(1.0D, 64.0D, 1.0D), 100L);
    for (int shot = 0; shot < 30; shot++) history.recordFireAngle(45.0F, 101L + shot);

    assertTrue(history.intentPenalty(HunterIntent.FLANK, 140L) > 0.0D);
    assertTrue(history.positionPenalty(new Vec3(1.0D, 64.0D, 1.0D), 140L) > 0.0D);
  }
}
