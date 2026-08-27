package cn.huntmc.hunterai.action;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class PlayerMotionControllerTest {
  @Test
  void jumpBurstWaitsForARealSprintRunUp() {
    assertFalse(PlayerMotionController.sprintRunUpReady(103L, 100L, 0.20D * 0.20D));
    assertFalse(PlayerMotionController.sprintRunUpReady(104L, 100L, 0.05D * 0.05D));
    assertTrue(PlayerMotionController.sprintRunUpReady(104L, 100L, 0.10D * 0.10D));
  }

  @Test
  void sprintJumpUsesBoundedBurstsInsteadOfPermanentHopping() {
    UUID hunter = UUID.fromString("12345678-1234-5678-1234-567812345678");
    for (long tick = 0; tick < 100L; tick++) {
      int count = PlayerMotionController.plannedJumpCount(hunter, tick);
      assertTrue(count >= 2 && count <= 4);
    }
  }

  @Test
  void combatEvasionProducesLateralInputInsteadOfForwardOnlyNavigation() {
    PlayerMotionController.RelativeInput left = PlayerMotionController.relativeInput(
        new Vec3(1.0D, 0.0D, 0.0D), new Vec3(0.0D, 0.0D, 1.0D));
    assertEquals(0.0F, left.forward(), 0.0001F);
    assertEquals(1.0F, left.strafe(), 0.0001F);

    PlayerMotionController.RelativeInput backRight = PlayerMotionController.relativeInput(
        new Vec3(-1.0D, 0.0D, -1.0D), new Vec3(0.0D, 0.0D, 1.0D));
    assertTrue(backRight.forward() < -0.70F);
    assertTrue(backRight.strafe() < -0.70F);
  }

  @Test
  void localCombatEvasionDoesNotTurnEverySideStepIntoAFullSprint() {
    assertFalse(PlayerMotionController.shouldSprint(MovementMode.EVADE, "combat-evasion"));
    assertFalse(PlayerMotionController.shouldSprint(
        MovementMode.COMBAT_DODGE_JUMP, "combat-evasion"));
    assertTrue(PlayerMotionController.shouldSprint(MovementMode.SPRINT_JUMP_BURST, "damage-reaction"));
    assertTrue(PlayerMotionController.shouldSprint(MovementMode.EVADE, "safe-reset"));
    assertTrue(PlayerMotionController.shouldSprint(MovementMode.SPRINT, "strategic-plan"));
    assertFalse(PlayerMotionController.shouldSprint(MovementMode.WALK, "combat-evasion"));
  }
}
