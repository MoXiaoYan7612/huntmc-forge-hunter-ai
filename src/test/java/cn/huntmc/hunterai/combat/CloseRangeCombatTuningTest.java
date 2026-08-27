package cn.huntmc.hunterai.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.huntmc.hunterai.profile.HunterProfile;
import org.junit.jupiter.api.Test;

class CloseRangeCombatTuningTest {
  @Test
  void effectIsFullThroughTenBlocksAndGoneAtFourteen() {
    assertEquals(1.0D, CloseRangeCombatTuning.influence(4.0D), 0.0001D);
    assertEquals(1.0D, CloseRangeCombatTuning.influence(10.0D), 0.0001D);
    assertEquals(0.5D, CloseRangeCombatTuning.influence(12.0D), 0.0001D);
    assertEquals(0.0D, CloseRangeCombatTuning.influence(14.0D), 0.0001D);
    assertEquals(0.0D, CloseRangeCombatTuning.influence(80.0D), 0.0001D);
  }

  @Test
  void closeRotationIsBoundedWithoutChangingLongRangeBaseline() {
    assertEquals(10.0F, CloseRangeCombatTuning.combatYawStep(true, 6.0D), 0.0001F);
    assertEquals(7.0F, CloseRangeCombatTuning.combatPitchStep(true, 6.0D), 0.0001F);
    assertEquals(13.0F, CloseRangeCombatTuning.combatYawStep(true, 12.0D), 0.0001F);
    assertEquals(9.5F, CloseRangeCombatTuning.combatPitchStep(true, 12.0D), 0.0001F);
    assertEquals(16.0F, CloseRangeCombatTuning.combatYawStep(true, 14.0D), 0.0001F);
    assertEquals(12.0F, CloseRangeCombatTuning.combatPitchStep(true, 40.0D), 0.0001F);
  }

  @Test
  void closeAimStaysOnTheBodyAndErrorDisappearsOutsideTheBlendBand() {
    assertEquals(0.74D, CloseRangeCombatTuning.aimHeight(6.0D, 0.88D), 0.0001D);
    assertEquals(0.58D, CloseRangeCombatTuning.aimHeight(6.0D, 0.56D), 0.0001D);
    assertTrue(CloseRangeCombatTuning.yawErrorAmplitude(
            HunterProfile.SHOTGUN, 6.0D, true, true)
        > CloseRangeCombatTuning.yawErrorAmplitude(
            HunterProfile.RIFLE, 6.0D, false, false));
    assertEquals(0.0D, CloseRangeCombatTuning.yawErrorAmplitude(
        HunterProfile.SHOTGUN, 14.0D, true, true), 0.0001D);
  }
}
