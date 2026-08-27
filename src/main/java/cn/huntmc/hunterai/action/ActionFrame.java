package cn.huntmc.hunterai.action;

import java.util.Map;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;

/** The sole action set executed for one Hunter tick. */
public record ActionFrame(
    long gameTime,
    Vec3 moveTarget,
    UUID routeId,
    double speed,
    MovementMode movement,
    Vec3 lookTarget,
    PostureCommand posture,
    WeaponCommand weapon,
    InteractionCommand interaction,
    Map<ActionChannel, String> owners,
    Map<ActionChannel, String> reasons) {
  public ActionFrame {
    if (movement == null || posture == null || weapon == null || interaction == null)
      throw new IllegalArgumentException("action frame commands are required");
    owners = Map.copyOf(owners == null ? Map.of() : owners);
    reasons = Map.copyOf(reasons == null ? Map.of() : reasons);
  }
}
