package cn.huntmc.hunterai.navigation;

import java.util.UUID;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/** Server-thread handle that binds route validation to the exact native path later executed. */
public record RouteHandle(
    UUID id,
    Path path,
    Vec3 requestedTarget,
    Vec3 executionTarget,
    Vec3 validatedFrom,
    long validatedAt) {
  public RouteHandle {
    if (id == null || path == null || requestedTarget == null || executionTarget == null || validatedFrom == null)
      throw new IllegalArgumentException("complete route handle is required");
  }

  public boolean matches(Vec3 target, long now) {
    return target != null
        && executionTarget.distanceToSqr(target) <= 2.25D
        && now >= validatedAt
        && now - validatedAt <= 2400L;
  }
}
