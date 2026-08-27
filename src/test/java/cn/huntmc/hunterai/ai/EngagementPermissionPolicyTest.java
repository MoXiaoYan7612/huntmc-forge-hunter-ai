package cn.huntmc.hunterai.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class EngagementPermissionPolicyTest {
  private static final CombatEvaluation FIGHT =
      new CombatEvaluation(0.70D, 0.25D, 0.20D, 0.8D, 0.8D, false, "fight");
  private static final CombatEvaluation ESCAPE =
      new CombatEvaluation(0.25D, 0.72D, 0.70D, 0.8D, 0.6D, true, "escape");
  private static final HunterPersonality PATIENT = HunterPersonality.fromUuid(new UUID(1L, 2L));

  @Test
  void coveredOverwatchGetsAChanceToShadowBeforeOpeningFire() {
    assertEquals(
        EngagementPermissionPolicy.Permission.HOLD_FIRE,
        EngagementPermissionPolicy.decide(
            HunterIntent.OVERWATCH, 28.0D, 24.0D, 0.90D, 0.20D, true,
            true, false, false, 0, 8L, PATIENT, FIGHT));
  }

  @Test
  void directDamageCancelsPatientHold() {
    assertEquals(
        EngagementPermissionPolicy.Permission.ENGAGE,
        EngagementPermissionPolicy.decide(
            HunterIntent.AMBUSH, 28.0D, 24.0D, 0.90D, 0.20D, true,
            true, true, false, 0, 4L, PATIENT, FIGHT));
  }

  @Test
  void patientIntentDoesNotStareFromAnUncoveredPosition() {
    assertEquals(
        EngagementPermissionPolicy.Permission.ENGAGE,
        EngagementPermissionPolicy.decide(
            HunterIntent.OVERWATCH, 28.0D, 24.0D, 0.90D, 0.45D, true,
            false, false, false, 0, 8L, PATIENT, FIGHT));
  }

  @Test
  void openGroundAfterTwoShotsRequestsReposition() {
    assertEquals(
        EngagementPermissionPolicy.Permission.REPOSITION,
        EngagementPermissionPolicy.decide(
            HunterIntent.HOLD_ANGLE, 20.0D, 24.0D, 0.80D, 0.80D, true,
            false, false, false, 2, 60L, PATIENT, FIGHT));
  }

  @Test
  void lowHealthWithAWinningEscapeUsesCover() {
    assertEquals(
        EngagementPermissionPolicy.Permission.BREAK_CONTACT,
        EngagementPermissionPolicy.decide(
            HunterIntent.PUSH, 18.0D, 24.0D, 0.28D, 0.40D, true,
            false, false, false, 1, 60L, PATIENT, ESCAPE));
  }

  @Test
  void patienceExpiresQuicklyAndCloseContactNeverWaits() {
    assertEquals(
        EngagementPermissionPolicy.Permission.ENGAGE,
        EngagementPermissionPolicy.decide(
            HunterIntent.AMBUSH, 20.0D, 24.0D, 0.90D, 0.20D, true,
            true, false, false, 0, 30L, PATIENT, FIGHT));
    assertEquals(
        EngagementPermissionPolicy.Permission.ENGAGE,
        EngagementPermissionPolicy.decide(
            HunterIntent.AMBUSH, 12.0D, 24.0D, 0.90D, 0.20D, true,
            true, false, false, 0, 4L, PATIENT, FIGHT));
  }
}
