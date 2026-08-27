package cn.huntmc.hunterai.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class EnemyBeliefTest {
  private static final Vec3 POSITION = new Vec3(8.0D, 64.0D, -3.0D);

  @Test
  void weakFootstepIsLessCertainThanVisualContact() {
    EnemyBelief belief = new EnemyBelief();

    belief.hear(POSITION, 0.45F, 100L);

    assertTrue(belief.confidence(100L) < 1.0D);
    assertEquals(0.5025D, belief.confidence(100L), 0.0001D);
  }

  @Test
  void laterWeakSoundDoesNotDowngradeRecentVisualContact() {
    EnemyBelief belief = new EnemyBelief();
    belief.observe(new UUID(5L, 6L), POSITION, 100L);
    double visualConfidence = belief.confidence(101L);

    belief.hear(POSITION.add(4.0D, 0.0D, 0.0D), 0.45F, 101L);

    assertEquals(visualConfidence, belief.confidence(101L), 0.0001D);
  }

  @Test
  void hearingExpiresBeforeVisualMemory() {
    EnemyBelief belief = new EnemyBelief();
    belief.hear(POSITION, 1.0F, 100L);

    assertTrue(belief.confidence(199L) > 0.0D);
    assertEquals(0.0D, belief.confidence(200L), 0.0001D);
  }

  @Test
  void damageKeepsImmediateThreatResponseConfirmed() {
    EnemyBelief belief = new EnemyBelief();
    belief.confirmDamage(POSITION, 100L);

    assertEquals(1.0D, belief.confidence(100L), 0.0001D);
  }
}
