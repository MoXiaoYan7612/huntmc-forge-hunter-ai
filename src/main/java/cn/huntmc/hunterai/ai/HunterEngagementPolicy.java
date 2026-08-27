package cn.huntmc.hunterai.ai;

/**
 * Decides whether a confirmed target takes priority over a movement plan.
 *
 * <p>Distance and strategic intent may influence movement, but never grant or revoke permission
 * to fire a legally visible target. That invariant belongs exclusively to {@link FireGate}.
 */
public final class HunterEngagementPolicy {
  private HunterEngagementPolicy() {}

  /**
   * A confirmed target still deserves attention, but a pressured Hunter must not convert an open
   * position into a standing gunfight. This requests movement toward verified hard cover while
   * {@link FireGate} independently preserves legal counterfire.
   */
  static boolean requiresHardCoverMovement(
      double localCover, double minimumCover, boolean underPressure) {
    return underPressure && localCover < minimumCover;
  }
}
