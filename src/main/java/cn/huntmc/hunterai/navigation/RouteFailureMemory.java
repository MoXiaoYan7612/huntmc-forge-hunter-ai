package cn.huntmc.hunterai.navigation;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Bounded backoff for failed routes and target cells so replanning cannot retry the same failure. */
public final class RouteFailureMemory {
  private static final long MIN_BACKOFF_TICKS = 100L;
  private static final long MAX_BACKOFF_TICKS = 400L;
  private static final int MAX_ENTRIES = 64;
  // Back off the failed endpoint, not an entire room-sized area. Recovery probes are deliberately
  // 4-6 blocks apart; the former eight-block radius rejected every one of them as the same route.
  private static final double REGION_HORIZONTAL_RADIUS_SQUARED = 2.25D * 2.25D;
  private static final double REGION_VERTICAL_RADIUS = 2.0D;
  private static final double ESCALATED_REGION_HORIZONTAL_RADIUS_SQUARED = 8.0D * 8.0D;
  private static final double ESCALATED_REGION_VERTICAL_RADIUS = 3.5D;
  private static final double CORRIDOR_ORIGIN_RADIUS_SQUARED = 6.0D * 6.0D;
  private static final double CORRIDOR_DIRECTION_DOT = 0.86D;
  private final Map<String, Failure> failures = new HashMap<>();

  public void record(UUID routeId, Vec3 target, String reason, long now) {
    record(routeId, null, target, reason, now);
  }

  public void record(UUID routeId, Vec3 origin, Vec3 target, String reason, long now) {
    prune(now);
    String cell = cellKey(target);
    Map.Entry<String, Failure> nearby = relatedFailureForRecord(origin, target, now);
    Failure previous = nearby == null ? null : nearby.getValue();
    int attempts = previous == null ? 1 : Math.min(4, previous.attempts() + 1);
    long backoff = Math.min(MAX_BACKOFF_TICKS, MIN_BACKOFF_TICKS * attempts);
    if (nearby != null && !nearby.getKey().equals(cell)) failures.remove(nearby.getKey());
    failures.put(
        cell,
        new Failure(
            routeId,
            origin,
            target,
            reason == null ? "unknown" : reason,
            attempts,
            now + backoff));
    if (failures.size() > MAX_ENTRIES) {
      failures.entrySet().stream()
          .min(Map.Entry.comparingByValue((first, second) -> Long.compare(first.expiresAt(), second.expiresAt())))
          .map(Map.Entry::getKey)
          .ifPresent(failures::remove);
    }
  }

  public boolean blocked(Vec3 target, long now) {
    return blocked(null, target, now);
  }

  public boolean blocked(Vec3 origin, Vec3 target, long now) {
    prune(now);
    Map.Entry<String, Failure> nearby = nearbyFailure(origin, target, now);
    Failure failure = nearby == null ? null : nearby.getValue();
    return failure != null && now < failure.expiresAt();
  }

  public double penalty(Vec3 target, long now) {
    return penalty(null, target, now);
  }

  public double penalty(Vec3 origin, Vec3 target, long now) {
    prune(now);
    Map.Entry<String, Failure> nearby = nearbyFailure(origin, target, now);
    Failure failure = nearby == null ? null : nearby.getValue();
    return failure == null ? 0.0D : Math.min(0.80D, 0.20D * failure.attempts());
  }

  public String reason(Vec3 target, long now) {
    return reason(null, target, now);
  }

  public String reason(Vec3 origin, Vec3 target, long now) {
    prune(now);
    Map.Entry<String, Failure> nearby = nearbyFailure(origin, target, now);
    Failure failure = nearby == null ? null : nearby.getValue();
    return failure == null ? "" : failure.reason();
  }

  public void clear() {
    failures.clear();
  }

  private void prune(long now) {
    Iterator<Failure> iterator = failures.values().iterator();
    while (iterator.hasNext()) if (iterator.next().expiresAt() <= now) iterator.remove();
  }

  private Map.Entry<String, Failure> nearbyFailure(Vec3 target, long now) {
    return nearbyFailure(null, target, now);
  }

  private Map.Entry<String, Failure> nearbyFailure(Vec3 origin, Vec3 target, long now) {
    if (target == null) return failures.entrySet().stream()
        .filter(entry -> entry.getValue().target() == null && now < entry.getValue().expiresAt())
        .findFirst()
        .orElse(null);
    return failures.entrySet().stream()
        .filter(entry -> now < entry.getValue().expiresAt())
        .filter(
            entry ->
                sameFailureRegion(target, entry.getValue().target())
                    || sameCorridor(
                        origin, target, entry.getValue().origin(), entry.getValue().target())
                    || (entry.getValue().attempts() >= 2
                        && sameEscalatedFailureRegion(target, entry.getValue().target())))
        .max(Map.Entry.comparingByValue(
            (first, second) -> Integer.compare(first.attempts(), second.attempts())))
        .orElse(null);
  }

  private Map.Entry<String, Failure> relatedFailureForRecord(
      Vec3 origin, Vec3 target, long now) {
    Map.Entry<String, Failure> blocking = nearbyFailure(origin, target, now);
    if (blocking != null) return blocking;
    if (target == null) return null;
    // The first failure only backs off its endpoint/corridor so 4-6 block recovery probes remain
    // usable. If a second nearby probe also fails, the failure is promoted to an eight-block
    // region and subsequent replans must choose another corridor instead of circling the obstacle.
    return failures.entrySet().stream()
        .filter(entry -> now < entry.getValue().expiresAt())
        .filter(entry -> sameEscalatedFailureRegion(target, entry.getValue().target()))
        .max(
            Map.Entry.comparingByValue(
                (first, second) -> Integer.compare(first.attempts(), second.attempts())))
        .orElse(null);
  }

  static boolean sameFailureRegion(Vec3 first, Vec3 second) {
    if (first == null || second == null) return first == second;
    double x = first.x - second.x;
    double z = first.z - second.z;
    return x * x + z * z <= REGION_HORIZONTAL_RADIUS_SQUARED
        && Math.abs(first.y - second.y) <= REGION_VERTICAL_RADIUS;
  }

  static boolean sameEscalatedFailureRegion(Vec3 first, Vec3 second) {
    if (first == null || second == null) return first == second;
    double x = first.x - second.x;
    double z = first.z - second.z;
    return x * x + z * z <= ESCALATED_REGION_HORIZONTAL_RADIUS_SQUARED
        && Math.abs(first.y - second.y) <= ESCALATED_REGION_VERTICAL_RADIUS;
  }

  static boolean sameCorridor(
      Vec3 firstOrigin, Vec3 firstTarget, Vec3 secondOrigin, Vec3 secondTarget) {
    if (firstOrigin == null || firstTarget == null || secondOrigin == null || secondTarget == null)
      return false;
    if (firstOrigin.distanceToSqr(secondOrigin) > CORRIDOR_ORIGIN_RADIUS_SQUARED) return false;
    Vec3 firstDirection = firstTarget.subtract(firstOrigin).multiply(1.0D, 0.0D, 1.0D);
    Vec3 secondDirection = secondTarget.subtract(secondOrigin).multiply(1.0D, 0.0D, 1.0D);
    if (firstDirection.lengthSqr() < 1.0E-6D || secondDirection.lengthSqr() < 1.0E-6D)
      return false;
    return firstDirection.normalize().dot(secondDirection.normalize()) >= CORRIDOR_DIRECTION_DOT;
  }

  private static String cellKey(Vec3 target) {
    if (target == null) return "none";
    BlockPos pos = BlockPos.containing(target);
    return (pos.getX() >> 2) + ":" + (pos.getY() >> 2) + ":" + (pos.getZ() >> 2);
  }

  public record Failure(
      UUID routeId, Vec3 origin, Vec3 target, String reason, int attempts, long expiresAt) {}
}
