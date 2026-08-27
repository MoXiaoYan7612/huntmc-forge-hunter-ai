package cn.huntmc.hunterai.action;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Resolves every action channel once. Ties are deterministic by owner name. */
public final class ActionArbiter {
  public ArbitrationResult arbitrate(long gameTime, List<ActionRequest> proposals) {
    List<ActionRequest> ordered = new ArrayList<>(proposals == null ? List.of() : proposals);
    ordered.sort(
        Comparator.comparingInt(ActionRequest::priority)
            .reversed()
            .thenComparing(ActionRequest::owner)
            .thenComparing(ActionRequest::reason));
    Map<ActionChannel, ActionRequest> winners = new EnumMap<>(ActionChannel.class);
    List<ArbitrationResult.Decision> decisions = new ArrayList<>();
    for (ActionRequest proposal : ordered) {
      for (ActionChannel channel : proposal.channels()) {
        ActionRequest winner = winners.putIfAbsent(channel, proposal);
        decisions.add(
            new ArbitrationResult.Decision(
                proposal.owner(),
                channel,
                winner == null,
                winner == null
                    ? "highest-priority"
                    : "rejected-by=" + winner.owner() + ":" + winner.priority()));
      }
    }

    ActionRequest movement = winners.get(ActionChannel.MOVEMENT);
    ActionRequest view = winners.get(ActionChannel.VIEW);
    ActionRequest posture = winners.get(ActionChannel.POSTURE);
    ActionRequest weapon = winners.get(ActionChannel.WEAPON);
    ActionRequest interaction = winners.get(ActionChannel.INTERACTION);
    Map<ActionChannel, String> owners = new EnumMap<>(ActionChannel.class);
    Map<ActionChannel, String> reasons = new EnumMap<>(ActionChannel.class);
    winners.forEach(
        (channel, request) -> {
          owners.put(channel, request.owner());
          reasons.put(channel, request.reason());
        });
    PostureCommand resolvedPosture = posture == null ? PostureCommand.STAND : posture.posture();
    MovementMode resolvedMovement = movement == null ? MovementMode.HOLD : movement.movement();
    if (requiresStanding(resolvedMovement) && resolvedPosture == PostureCommand.CROUCH) {
      for (int index = 0; index < decisions.size(); index++) {
        ArbitrationResult.Decision decision = decisions.get(index);
        if (decision.channel() == ActionChannel.POSTURE && decision.accepted()) {
          decisions.set(
              index,
              new ArbitrationResult.Decision(
                  decision.owner(),
                  ActionChannel.POSTURE,
                  false,
                  "cross-channel-conflict=sprint-requires-standing"));
          break;
        }
      }
      resolvedPosture = PostureCommand.STAND;
      owners.put(ActionChannel.POSTURE, "action-arbiter");
      reasons.put(ActionChannel.POSTURE, "sprint-requires-standing");
      decisions.add(
          new ArbitrationResult.Decision(
              "action-arbiter", ActionChannel.POSTURE, true, "sprint-requires-standing"));
    }
    ActionFrame frame =
        new ActionFrame(
            gameTime,
            movement == null ? null : movement.moveTarget(),
            movement == null ? null : movement.routeId(),
            movement == null ? 0.0D : movement.speed(),
            resolvedMovement,
            view == null ? null : view.lookTarget(),
            resolvedPosture,
            weapon == null ? WeaponCommand.HOLD : weapon.weapon(),
            interaction == null ? InteractionCommand.NONE : interaction.interaction(),
            owners,
            reasons);
    return new ArbitrationResult(frame, decisions);
  }

  private static boolean requiresStanding(MovementMode movement) {
    return movement == MovementMode.SPRINT
        || movement == MovementMode.SPRINT_JUMP_BURST
        || movement == MovementMode.COMBAT_DODGE_JUMP;
  }
}
