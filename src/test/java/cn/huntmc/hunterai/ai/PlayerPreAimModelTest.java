package cn.huntmc.hunterai.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class PlayerPreAimModelTest {
  @Test
  void detectsOnlyAForwardAimLaneNearTheHunter() {
    PlayerPreAimModel.Assessment centered = PlayerPreAimModel.assess(
        Vec3.ZERO,
        new Vec3(0.0D, 0.0D, 1.0D),
        new Vec3(0.2D, 0.0D, 18.0D),
        Vec3.ZERO,
        48.0D);
    assertTrue(centered.threatening());
    assertTrue(centered.risk() > 0.80D);

    assertFalse(PlayerPreAimModel.assess(
        Vec3.ZERO,
        new Vec3(0.0D, 0.0D, 1.0D),
        new Vec3(5.0D, 0.0D, 18.0D),
        Vec3.ZERO,
        48.0D).threatening());
    assertFalse(PlayerPreAimModel.assess(
        Vec3.ZERO,
        new Vec3(0.0D, 0.0D, -1.0D),
        new Vec3(0.0D, 0.0D, 18.0D),
        Vec3.ZERO,
        48.0D).threatening());
  }

  @Test
  void reactionKeepsDeterministicFalseNegativesInsteadOfPerfectDodging() {
    UUID hunter = UUID.fromString("12345678-1234-5678-1234-567812345678");
    UUID player = UUID.fromString("87654321-4321-8765-4321-876543218765");
    int reacted = 0;
    for (long window = 0L; window < 128L; window++) {
      if (PlayerPreAimModel.shouldReact(hunter, player, window, 0.90D, 0.70D)) reacted++;
    }
    assertTrue(reacted > 50);
    assertTrue(reacted < 120);
  }
}
