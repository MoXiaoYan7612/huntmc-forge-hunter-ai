package cn.huntmc.hunterai.match;

import java.util.Set;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;

/**
 * Chooses a stable but match-specific approach to a public objective.
 *
 * <p>The planner deliberately knows only the objective published by the Bukkit match service and
 * Hunter/match identities. It never receives player positions, headings, or visibility state.
 */
final class ObjectiveRoutePlanner {
  static final int APPROACH_SECTORS = 16;
  static final double TRANSIT_RADIUS = 42.0D;
  static final double APPROACH_RADIUS = 18.0D;
  static final double INTERACTION_RADIUS = 2.25D;

  private ObjectiveRoutePlanner() {}

  /**
   * Spreads Hunters across free approach sectors. The first preference is deterministic for a
   * Hunter in a match; probing the remaining sectors prevents a whole full-AI round from choosing
   * the same ingress line.
   */
  static int reserveSector(UUID matchId, UUID botId, Set<Integer> occupied) {
    if (matchId == null || botId == null) return 0;
    long mixed = mix(matchId.getMostSignificantBits() ^ matchId.getLeastSignificantBits());
    mixed ^= mix(botId.getMostSignificantBits() ^ botId.getLeastSignificantBits());
    int first = Math.floorMod((int) (mixed ^ (mixed >>> 32)), APPROACH_SECTORS);
    for (int offset = 0; offset < APPROACH_SECTORS; offset++) {
      int sector = (first + offset) % APPROACH_SECTORS;
      if (!occupied.contains(sector)) return sector;
    }
    // More than sixteen Hunters may share an approach as a soft fallback, never as an error.
    return first;
  }

  static Vec3 approachPoint(Vec3 objective, int sector) {
    return approachPoint(objective, sector, objective == null ? 0.0D : objective.y);
  }

  /**
   * The outer approach is an ingress waypoint, not an interaction point. Keeping it on the
   * Hunter's current elevation avoids asking ground navigation to stand in the air at an elevated
   * boss marker before it has reached the entrance around that marker.
   */
  static Vec3 approachPoint(Vec3 objective, int sector, double elevation) {
    return pointAtRadius(objective, sector, APPROACH_RADIUS, elevation);
  }

  /** A wider route corridor prevents all Hunters from collapsing onto the same shortest path. */
  static Vec3 transitPoint(Vec3 objective, int sector, double elevation) {
    return pointAtRadius(objective, sector, TRANSIT_RADIUS, elevation);
  }

  /**
   * A final standing preference keeps successful Hunters entering the Boss from different angles.
   * Bukkit independently validates the actual range and line of sight before it starts a banish.
   */
  static Vec3 interactionPoint(Vec3 objective, int sector) {
    return pointAtRadius(objective, sector, INTERACTION_RADIUS, objective == null ? 0.0D : objective.y);
  }

  private static Vec3 pointAtRadius(Vec3 objective, int sector, double radius, double elevation) {
    if (objective == null) return null;
    int normalized = Math.floorMod(sector, APPROACH_SECTORS);
    double angle = (Math.PI * 2.0D * normalized) / APPROACH_SECTORS;
    return new Vec3(
        objective.x + Math.cos(angle) * radius,
        elevation,
        objective.z + Math.sin(angle) * radius);
  }

  /**
   * A ground route may end a few blocks above or below the authored outer ring. The ring is only
   * there to vary ingress, so horizontal arrival is the relevant condition before the Hunter
   * starts its final route to the authoritative boss position.
   */
  static boolean reachedApproach(Vec3 position, Vec3 approach, double horizontalTolerance) {
    if (position == null || approach == null || horizontalTolerance < 0.0D) return false;
    double dx = position.x - approach.x;
    double dz = position.z - approach.z;
    return dx * dx + dz * dz <= horizontalTolerance * horizontalTolerance;
  }

  /** Picks the next unoccupied ingress after a route has genuinely failed, without reusing one. */
  static int nextAvailableSector(int current, Set<Integer> occupied, Set<Integer> rejected) {
    for (int offset = 1; offset <= APPROACH_SECTORS; offset++) {
      int candidate = Math.floorMod(current + offset, APPROACH_SECTORS);
      if (!occupied.contains(candidate) && !rejected.contains(candidate)) return candidate;
    }
    // A full 16-seat round may exhaust every sector. Reuse remains a soft fallback, never a
    // deadlock; the caller has already recorded the failed choices for diagnostics.
    for (int offset = 1; offset <= APPROACH_SECTORS; offset++) {
      int candidate = Math.floorMod(current + offset, APPROACH_SECTORS);
      if (!occupied.contains(candidate)) return candidate;
    }
    return Math.floorMod(current, APPROACH_SECTORS);
  }

  private static long mix(long value) {
    value ^= value >>> 33;
    value *= 0xff51afd7ed558ccdL;
    value ^= value >>> 33;
    value *= 0xc4ceb9fe1a85ec53L;
    return value ^ (value >>> 33);
  }
}
