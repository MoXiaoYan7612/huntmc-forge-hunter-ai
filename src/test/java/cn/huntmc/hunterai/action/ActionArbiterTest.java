package cn.huntmc.hunterai.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ActionArbiterTest {
  @Test
  void combatOwnsViewWhileStrategicMovementContinues() {
    Vec3 objective = new Vec3(30.0D, 64.0D, 0.0D);
    Vec3 enemy = new Vec3(4.0D, 66.0D, 0.0D);

    ArbitrationResult result = new ActionArbiter().arbitrate(
        100L,
        List.of(
            ActionRequest.movement("strategic", 70, objective, 1.0D, MovementMode.SPRINT, "objective"),
            ActionRequest.view("vigilance", 50, objective, "route"),
            ActionRequest.view("visible-combat", 100, enemy, "legal-visual"),
            ActionRequest.weapon("visible-combat", 100, WeaponCommand.ADS_FIRE, "combat")));

    assertEquals(objective, result.frame().moveTarget());
    assertEquals(enemy, result.frame().lookTarget());
    assertEquals("visible-combat", result.frame().owners().get(ActionChannel.VIEW));
    assertEquals(WeaponCommand.ADS_FIRE, result.frame().weapon());
    assertTrue(result.decisions().stream().anyMatch(decision ->
        decision.channel() == ActionChannel.VIEW && !decision.accepted() && decision.owner().equals("vigilance")));
  }

  @Test
  void combatOwnsViewAndWeaponWhileSafeResetOwnsEscapeMovement() {
    Vec3 cover = new Vec3(-8.0D, 64.0D, 0.0D);
    Vec3 enemy = new Vec3(8.0D, 65.5D, 0.0D);

    ArbitrationResult result = new ActionArbiter().arbitrate(
        200L,
        List.of(
            ActionRequest.movement("safe-reset", 100, cover, 1.0D, MovementMode.EVADE, "escape"),
            ActionRequest.view("safe-reset", 96, enemy, "retain-bearing"),
            ActionRequest.weapon("safe-reset", 94, WeaponCommand.HOLD, "survival"),
            ActionRequest.view("visible-combat", 100, enemy, "legal-visual"),
            ActionRequest.weapon("visible-combat", 100, WeaponCommand.HIP_FIRE, "return-fire")));

    assertEquals("safe-reset", result.frame().owners().get(ActionChannel.MOVEMENT));
    assertEquals("visible-combat", result.frame().owners().get(ActionChannel.VIEW));
    assertEquals("visible-combat", result.frame().owners().get(ActionChannel.WEAPON));
    assertEquals(MovementMode.EVADE, result.frame().movement());
    assertEquals(WeaponCommand.HIP_FIRE, result.frame().weapon());
  }

  @Test
  void winningMovementCarriesTheValidatedRouteIdentityIntoTheFrame() {
    UUID routeId = UUID.randomUUID();
    Vec3 target = new Vec3(32.0D, 64.0D, 8.0D);

    ArbitrationResult result = new ActionArbiter().arbitrate(
        300L,
        List.of(ActionRequest.movement(
            "strategic", 70, target, routeId, 1.0D, MovementMode.SPRINT, "validated-route")));

    assertEquals(routeId, result.frame().routeId());
    assertEquals(target, result.frame().moveTarget());
  }

  @Test
  void sprintOverridesAConflictingCrouchRequest() {
    Vec3 target = new Vec3(20.0D, 64.0D, 0.0D);

    ArbitrationResult result = new ActionArbiter().arbitrate(
        400L,
        List.of(
            ActionRequest.movement(
                "strategic", 80, target, 1.0D, MovementMode.SPRINT, "urgent-transit"),
            ActionRequest.posture(
                "vigilance", 90, PostureCommand.CROUCH, "observe-from-cover")));

    assertEquals(PostureCommand.STAND, result.frame().posture());
    assertEquals("action-arbiter", result.frame().owners().get(ActionChannel.POSTURE));
    assertEquals("sprint-requires-standing", result.frame().reasons().get(ActionChannel.POSTURE));
    assertTrue(result.decisions().stream().anyMatch(decision ->
        decision.channel() == ActionChannel.POSTURE
            && decision.owner().equals("vigilance")
            && !decision.accepted()));
  }
}
