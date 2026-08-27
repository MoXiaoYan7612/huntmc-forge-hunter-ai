package cn.huntmc.hunterai.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.huntmc.hunterai.profile.HunterProfile;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CombatAimZoneTest {
  @Test
  void torsoIsTheDominantStableAimZone() {
    UUID hunter = UUID.fromString("57cb8b41-ddf7-4aba-b80a-5fb2cc1da763");
    int torso = 0;
    int head = 0;
    for (int index = 0; index < 1_000; index++) {
      double height = CombatController.selectAimHeight(hunter, new UUID(0L, index + 1L));
      if (height == 0.70D) torso++;
      if (height >= 0.85D) head++;
    }

    assertTrue(torso >= 750, "most contacts must aim at the torso");
    assertTrue(head <= 100, "head aim must remain uncommon");
  }

  @Test
  void replaySummaryNamesTheActualAimBand() {
    assertEquals("BODY", CombatController.aimZone(0.70D));
    assertEquals("UPPER_BODY", CombatController.aimZone(0.84D));
    assertEquals("HEAD", CombatController.aimZone(0.88D));
  }

  @Test
  void revolverPrefersUpperBodyAtMediumRangeWithoutLockingEveryHead() {
    UUID hunter = UUID.fromString("57cb8b41-ddf7-4aba-b80a-5fb2cc1da763");
    int head = 0;
    int upperBody = 0;
    for (int index = 0; index < 1_000; index++) {
      UUID target = new UUID(0L, index + 1L);
      double base = CombatController.selectAimHeight(hunter, target);
      double height = CombatController.weaponAdjustedAimHeight(
          HunterProfile.REVOLVER, 18.0D, base, hunter, target);
      if (height >= 0.86D) head++;
      else if (height >= 0.78D) upperBody++;
    }

    assertTrue(head >= 200 && head <= 350, "medium-range pistol head aim stays probabilistic");
    assertTrue(head + upperBody >= 500, "medium-range pistol should favor upper body");
  }
}
