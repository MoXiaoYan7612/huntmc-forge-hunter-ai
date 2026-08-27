package cn.huntmc.hunterai.decision;

import net.minecraft.world.phys.Vec3;

public record FallbackPlan(SafeResetPhase phase, Vec3 immediateCover, Vec3 deepCover, long expiresAt) {
  public FallbackPlan {
    if (phase == null) throw new IllegalArgumentException("fallback phase is required");
  }

  public static FallbackPlan inactive() {
    return new FallbackPlan(SafeResetPhase.REASSESS, null, null, Long.MIN_VALUE);
  }
}
