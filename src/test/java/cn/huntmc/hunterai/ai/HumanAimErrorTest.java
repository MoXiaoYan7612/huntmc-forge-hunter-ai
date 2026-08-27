package cn.huntmc.hunterai.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.huntmc.hunterai.profile.HunterProfile;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HumanAimErrorTest {
  private static final UUID HUNTER =
      UUID.fromString("57cb8b41-ddf7-4aba-b80a-5fb2cc1da763");
  private static final UUID TARGET =
      UUID.fromString("09ab4045-b9b0-447f-b8b1-4e74e3f175d6");

  @Test
  void closeErrorMovesSmoothlyAndRemainsBounded() {
    HumanAimError error = new HumanAimError();
    HumanAimError.Offset previous = error.tick(
        HUNTER, TARGET, 1L, HunterProfile.SHOTGUN, 6.0D, false, false, 100L);
    for (long now = 101L; now <= 120L; now++) {
      HumanAimError.Offset current = error.tick(
          HUNTER, TARGET, 1L, HunterProfile.SHOTGUN, 6.0D, false, false, now);
      assertTrue(Math.abs(current.yawDegrees()) <= 2.20D + 0.0001D);
      assertTrue(Math.abs(current.pitchDegrees()) <= 1.50D + 0.0001D);
      assertTrue(Math.abs(current.yawDegrees() - previous.yawDegrees()) <= 0.36D + 0.0001D);
      assertTrue(Math.abs(current.pitchDegrees() - previous.pitchDegrees()) <= 0.26D + 0.0001D);
      previous = current;
    }
  }

  @Test
  void mediumAndLongRangeReturnExactZeroAndClearCloseState() {
    HumanAimError error = new HumanAimError();
    error.tick(HUNTER, TARGET, 1L, HunterProfile.SHOTGUN, 6.0D, true, true, 100L);
    HumanAimError.Offset distant = error.tick(
        HUNTER, TARGET, 1L, HunterProfile.SHOTGUN, 18.0D, true, true, 101L);
    assertEquals(0.0D, distant.yawDegrees(), 0.0001D);
    assertEquals(0.0D, distant.pitchDegrees(), 0.0001D);
  }

  @Test
  void shotImpulseDoesNotEscapeTheCurrentCloseRangeEnvelope() {
    HumanAimError error = new HumanAimError();
    error.tick(HUNTER, TARGET, 1L, HunterProfile.RIFLE, 6.0D, false, false, 100L);
    error.onShot(HUNTER, TARGET, 1L);
    HumanAimError.Offset after = error.tick(
        HUNTER, TARGET, 1L, HunterProfile.RIFLE, 6.0D, false, false, 101L);
    assertTrue(Math.abs(after.yawDegrees()) <= 1.70D + 0.0001D);
    assertTrue(Math.abs(after.pitchDegrees()) <= 1.20D + 0.0001D);
  }
}
