package cn.huntmc.hunterai.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HunterEngagementPolicyTest {

  @Test
  void pressureRequestsHardCoverWithoutOwningTheWeaponChannel() {
    assertTrue(HunterEngagementPolicy.requiresHardCoverMovement(0.20D, 0.35D, true));
    assertFalse(HunterEngagementPolicy.requiresHardCoverMovement(0.35D, 0.35D, true));
    assertFalse(HunterEngagementPolicy.requiresHardCoverMovement(0.0D, 0.35D, false));
    assertTrue(CombatController.decideCombat(
        new CombatController.CombatSituation(true, false, false, true, true, false, false, 3L, false))
        .immediateFireOpportunity());
  }
}
