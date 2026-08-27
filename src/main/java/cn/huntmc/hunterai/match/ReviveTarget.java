package cn.huntmc.hunterai.match;

import java.util.UUID;
import net.minecraft.world.phys.Vec3;

/** Public teammate corpse fact supplied by the authoritative match service. */
public record ReviveTarget(UUID markerId, UUID ownerId, Vec3 position) {
  public ReviveTarget {
    if (markerId == null || ownerId == null || position == null)
      throw new IllegalArgumentException("revive target is incomplete");
  }
}
