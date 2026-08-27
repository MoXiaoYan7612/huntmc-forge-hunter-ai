package cn.huntmc.hunterai.action;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;

/** Immutable proposal. Producers describe intent and never mutate the entity. */
public record ActionRequest(
    String owner,
    int priority,
    Set<ActionChannel> channels,
    Vec3 moveTarget,
    UUID routeId,
    double speed,
    MovementMode movement,
    Vec3 lookTarget,
    PostureCommand posture,
    WeaponCommand weapon,
    InteractionCommand interaction,
    String reason) {
  public ActionRequest {
    owner = owner == null || owner.isBlank() ? "unknown" : owner;
    channels = Set.copyOf(channels == null ? Set.of() : channels);
    movement = movement == null ? MovementMode.HOLD : movement;
    posture = posture == null ? PostureCommand.STAND : posture;
    weapon = weapon == null ? WeaponCommand.HOLD : weapon;
    interaction = interaction == null ? InteractionCommand.NONE : interaction;
    reason = reason == null ? "" : reason;
  }

  public static ActionRequest movement(
      String owner, int priority, Vec3 target, double speed, MovementMode mode, String reason) {
    return movement(owner, priority, target, null, speed, mode, reason);
  }

  public static ActionRequest movement(
      String owner,
      int priority,
      Vec3 target,
      UUID routeId,
      double speed,
      MovementMode mode,
      String reason) {
    return new ActionRequest(
        owner, priority, EnumSet.of(ActionChannel.MOVEMENT), target, routeId, speed, mode, null,
        PostureCommand.STAND, WeaponCommand.HOLD, InteractionCommand.NONE, reason);
  }

  public static ActionRequest view(String owner, int priority, Vec3 target, String reason) {
    return new ActionRequest(
        owner, priority, EnumSet.of(ActionChannel.VIEW), null, null, 0.0D, MovementMode.HOLD, target,
        PostureCommand.STAND, WeaponCommand.HOLD, InteractionCommand.NONE, reason);
  }

  public static ActionRequest posture(
      String owner, int priority, PostureCommand posture, String reason) {
    return new ActionRequest(
        owner, priority, EnumSet.of(ActionChannel.POSTURE), null, null, 0.0D, MovementMode.HOLD, null,
        posture, WeaponCommand.HOLD, InteractionCommand.NONE, reason);
  }

  public static ActionRequest weapon(
      String owner, int priority, WeaponCommand weapon, String reason) {
    return new ActionRequest(
        owner, priority, EnumSet.of(ActionChannel.WEAPON), null, null, 0.0D, MovementMode.HOLD, null,
        PostureCommand.STAND, weapon, InteractionCommand.NONE, reason);
  }

  public static ActionRequest interaction(
      String owner, int priority, InteractionCommand interaction, String reason) {
    return new ActionRequest(
        owner, priority, EnumSet.of(ActionChannel.INTERACTION), null, null, 0.0D, MovementMode.HOLD, null,
        PostureCommand.STAND, WeaponCommand.HOLD, interaction, reason);
  }

  public static ActionRequest emergency(
      String owner,
      int priority,
      Vec3 moveTarget,
      MovementMode movement,
      Vec3 lookTarget,
      PostureCommand posture,
      WeaponCommand weapon,
      String reason) {
    return new ActionRequest(
        owner,
        priority,
        EnumSet.of(ActionChannel.MOVEMENT, ActionChannel.VIEW, ActionChannel.POSTURE, ActionChannel.WEAPON),
        moveTarget,
        null,
        1.0D,
        movement,
        lookTarget,
        posture,
        weapon,
        InteractionCommand.NONE,
        reason);
  }

  public ActionRequest withValidatedMovement(Vec3 validatedTarget, UUID validatedRouteId) {
    if (!channels.contains(ActionChannel.MOVEMENT)) return this;
    return new ActionRequest(
        owner,
        priority,
        channels,
        validatedTarget,
        validatedRouteId,
        speed,
        movement,
        lookTarget,
        posture,
        weapon,
        interaction,
        reason);
  }
}
