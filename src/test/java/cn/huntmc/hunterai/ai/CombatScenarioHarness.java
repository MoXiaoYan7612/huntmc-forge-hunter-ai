package cn.huntmc.hunterai.ai;

import cn.huntmc.hunterai.action.ActionArbiter;
import cn.huntmc.hunterai.action.ActionFrame;
import cn.huntmc.hunterai.action.ActionRequest;
import cn.huntmc.hunterai.action.ActuatorResult;
import cn.huntmc.hunterai.action.MovementMode;
import cn.huntmc.hunterai.action.MovementProgressState;
import cn.huntmc.hunterai.action.WeaponCommand;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;

/**
 * Deterministic cross-module contract harness for the headless test runtime. It connects the
 * combat situation, real action arbitration, actuator feedback, and the following tick without
 * constructing a client or replacing TaCZ behavior.
 */
final class CombatScenarioHarness {
  private static final UUID HUNTER_ID =
      UUID.fromString("57cb8b41-ddf7-4aba-b80a-5fb2cc1da763");
  private static final UUID TARGET_ID =
      UUID.fromString("09ab4045-b9b0-447f-b8b1-4e74e3f175d6");
  private final ActionArbiter arbiter = new ActionArbiter();
  private ActuatorResult previousResult;

  Step tick(long now, Scenario scenario, MovementProgressState movementResult) {
    List<ActionRequest> proposals = new ArrayList<>();
    if (scenario.legalVisual()) {
      proposals.add(ActionRequest.view(
          "combat-controller", 100, new Vec3(0.0D, 1.4D, scenario.distance()), "legal-target-lock"));
    }

    long contactAt = now - scenario.targetAge();
    long firstTriggerEligibleAt = FirstTriggerGate.eligibleAt(
        contactAt,
        scenario.distance(),
        scenario.directDamage(),
        HUNTER_ID,
        TARGET_ID,
        1L);
    CombatController.CombatDecision decision = CombatController.decideCombat(
        new CombatController.CombatSituation(
            scenario.directDamage(),
            scenario.closeThreat(),
            scenario.finishWindow(),
            scenario.currentLegalFireOpportunity(),
            scenario.ammunitionAvailable(),
            scenario.reloading(),
            scenario.criticalHealth(),
            scenario.targetAge(),
            scenario.playerPreAim()));

    FireGate.Decision fireDecision = FireGate.evaluate(new FireGate.Input(
        scenario.legalVisual(),
        true,
        false,
        scenario.ammunitionAvailable() ? 5 : 0,
        scenario.reloading(),
        new cn.huntmc.hunterai.combat.HunterWeaponAdapter.WeaponReadiness(
            0L,
            true,
            scenario.reloading()
                ? cn.huntmc.hunterai.combat.HunterWeaponAdapter.ManualActionState.RELOADING
                : cn.huntmc.hunterai.combat.HunterWeaponAdapter.ManualActionState.READY),
        true,
        now,
        firstTriggerEligibleAt,
        0L,
        false,
        scenario.currentLegalFireOpportunity() ? 0.0D : 20.0D,
        3.5D,
        scenario.targetAge(),
        3L));

    if (scenario.coverRequested()) {
      proposals.add(ActionRequest.movement(
          "combat-cover", 97, new Vec3(4.0D, 0.0D, 2.0D), 1.05D, MovementMode.SPRINT,
          "move-to-hard-cover-with-bounded-suppression"));
    }
    boolean fire = fireDecision.mayFire();
    if (!fireDecision.resourceRecoveryOwnsWeapon()) {
      proposals.add(ActionRequest.weapon(
          "combat-controller",
          100,
          fire ? WeaponCommand.ADS_FIRE : WeaponCommand.ADS,
          fire ? "stable-fire-window" : "track-without-premature-fire"));
    }
    if (!scenario.ammunitionAvailable() || scenario.reloading()) {
      proposals.add(ActionRequest.weapon(
          "resource", 88, WeaponCommand.RELOAD, "test-resource-recovery"));
    }

    ActionFrame frame = arbiter.arbitrate(now, proposals).frame();
    MovementProgressState appliedMovement = frame.moveTarget() == null
        ? MovementProgressState.IDLE
        : movementResult;
    previousResult = new ActuatorResult(
        appliedMovement != MovementProgressState.FAILED
            && appliedMovement != MovementProgressState.STALLED,
        frame.lookTarget() != null,
        true,
        true,
        false,
        false,
        frame.movement() == MovementMode.SPRINT,
        frame.movement() == MovementMode.SPRINT,
        appliedMovement == MovementProgressState.PROGRESSING ? 0.36D : 0.0D,
        frame.moveTarget() == null ? 0.0F : 1.0F,
        0.0F,
        frame.routeId(),
        frame.moveTarget(),
        appliedMovement == MovementProgressState.FAILED
                || appliedMovement == MovementProgressState.STALLED
            ? frame.moveTarget()
            : null,
        appliedMovement == MovementProgressState.FAILED
                || appliedMovement == MovementProgressState.STALLED
            ? 1
            : 0,
        appliedMovement,
        appliedMovement.name().toLowerCase(java.util.Locale.ROOT),
        fire ? "fired-ads:harness" : "ads",
        "none");
    return new Step(frame, previousResult, decision, fireDecision);
  }

  ActuatorResult previousResult() {
    return previousResult;
  }

  record Scenario(
      boolean legalVisual,
      boolean directDamage,
      boolean closeThreat,
      boolean finishWindow,
      boolean currentLegalFireOpportunity,
      boolean ammunitionAvailable,
      boolean reloading,
      boolean criticalHealth,
      boolean playerPreAim,
      boolean coverRequested,
      long targetAge,
      double distance) {}

  record Step(
      ActionFrame frame,
      ActuatorResult actuatorResult,
      CombatController.CombatDecision decision,
      FireGate.Decision fireDecision) {}
}
