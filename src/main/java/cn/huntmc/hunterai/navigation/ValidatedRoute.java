package cn.huntmc.hunterai.navigation;

import java.util.List;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;

/** Immutable result of native navigation validation. */
public record ValidatedRoute(
    UUID id,
    Vec3 requestedTarget,
    List<Vec3> waypoints,
    Vec3 pathEnd,
    boolean reachable,
    double length,
    double exposureCost,
    double repetitionCost,
    String failureReason) {
  public ValidatedRoute {
    if (id == null) throw new IllegalArgumentException("route id is required");
    waypoints = List.copyOf(waypoints == null ? List.of() : waypoints);
    failureReason = failureReason == null ? "" : failureReason;
  }

  public Vec3 destination() {
    return waypoints.isEmpty() ? pathEnd : waypoints.get(waypoints.size() - 1);
  }

  /** A partial native path is an executable checkpoint, not completion of the requested route. */
  public boolean reachesRequestedTarget() {
    Vec3 destination = destination();
    return requestedTarget != null
        && destination != null
        && Math.abs(requestedTarget.y - destination.y) <= 2.0D
        && horizontalDistanceSquared(requestedTarget, destination) <= 1.5D * 1.5D;
  }

  private static double horizontalDistanceSquared(Vec3 first, Vec3 second) {
    double x = first.x - second.x;
    double z = first.z - second.z;
    return x * x + z * z;
  }
}
