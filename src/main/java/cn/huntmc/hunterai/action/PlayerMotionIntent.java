package cn.huntmc.hunterai.action;

import net.minecraft.world.phys.Vec3;

/** Player-shaped local movement input derived from the winning movement action. */
public record PlayerMotionIntent(
    Vec3 target,
    float forwardInput,
    float strafeInput,
    boolean sprint,
    boolean jumpRequested,
    boolean crouch,
    Vec3 lookTarget,
    long validUntil,
    StopCondition stopCondition) {
  public PlayerMotionIntent {
    forwardInput = Math.max(-1.0F, Math.min(1.0F, forwardInput));
    strafeInput = Math.max(-1.0F, Math.min(1.0F, strafeInput));
    stopCondition = stopCondition == null ? StopCondition.ARRIVAL_OR_BLOCKED : stopCondition;
  }

  public enum StopCondition {
    ARRIVAL_OR_BLOCKED,
    COMBAT_STABILITY,
    IMMEDIATE_SAFETY
  }
}
