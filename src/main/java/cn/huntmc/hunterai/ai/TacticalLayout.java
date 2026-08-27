package cn.huntmc.hunterai.ai;

import java.util.Locale;
import net.minecraft.world.phys.Vec3;

/**
 * A connected local combat layout. A plan selects a role inside this layout rather than treating
 * one scored block as a complete tactic.
 *
 * <p>The Hunter still validates the route to the selected primary node with vanilla navigation at
 * execution time. The remaining nodes are spatial alternatives: they make a planned observation
 * point accountable for a nearby waiting point, firing point, second firing angle, and retreat
 * position without pretending that an unverified path is usable.
 */
public record TacticalLayout(
    TacticalNode approach,
    TacticalNode waiting,
    TacticalNode firing,
    TacticalNode secondaryFiring,
    TacticalNode retreat,
    TacticalNode primary,
    double score,
    double repeatPenalty) {
  public TacticalLayout {
    if (primary == null) throw new IllegalArgumentException("A tactical layout needs a primary node");
  }

  public Vec3 primaryPosition() {
    return primary.position();
  }

  /** A precomputed alternative is used only after its own vanilla route validation succeeds. */
  public TacticalNode fallbackFor(HunterIntent intent) {
    return switch (intent) {
      case HOLD_ANGLE, OVERWATCH -> secondaryFiring != null ? secondaryFiring : retreat;
      case PUSH, FLANK, AMBUSH, INTERCEPT, SEARCH -> firing != null ? firing : retreat;
      case REPOSITION, LISTEN, DISENGAGE -> retreat != null ? retreat : waiting;
    };
  }

  public boolean hasDistinctRetreat() {
    return retreat != null && retreat.position().distanceToSqr(primary.position()) > 9.0D;
  }

  public String summary() {
    return "primary="
        + compact(primary)
        + " approach="
        + compact(approach)
        + " wait="
        + compact(waiting)
        + " fire="
        + compact(firing)
        + " fire2="
        + compact(secondaryFiring)
        + " retreat="
        + compact(retreat)
        + " score="
        + String.format(Locale.ROOT, "%.2f", score)
        + " repeat="
        + String.format(Locale.ROOT, "%.2f", repeatPenalty);
  }

  private static String compact(TacticalNode node) {
    if (node == null) return "none";
    Vec3 position = node.position();
    return String.format(Locale.ROOT, "%.0f/%.0f/%.0f", position.x, position.y, position.z);
  }
}
