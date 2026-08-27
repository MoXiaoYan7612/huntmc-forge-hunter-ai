package cn.huntmc.hunterai.ai;

/** Bounded rules for leaving a fully exposed firing position without suppressing self-defense. */
public final class CombatExposurePolicy {
  static final double FULL_BODY_EXPOSURE = 0.80D;
  static final int MOVING_SUPPRESSION_SHOTS = 2;

  private CombatExposurePolicy() {}

  public static Decision decide(
      double bodyExposure,
      boolean reachableCover,
      boolean pressured,
      int shotsSinceReposition) {
    double exposure = Math.max(0.0D, Math.min(1.0D, bodyExposure));
    int shots = Math.max(0, shotsSinceReposition);
    if (exposure < FULL_BODY_EXPOSURE || !reachableCover) return Decision.HOLD_POSITION;
    if (!pressured || shots >= MOVING_SUPPRESSION_SHOTS) return Decision.RELOCATE_WITHOUT_FIRE;
    return Decision.RELOCATE_WITH_SUPPRESSION;
  }

  public enum Decision {
    HOLD_POSITION,
    RELOCATE_WITH_SUPPRESSION,
    RELOCATE_WITHOUT_FIRE
  }
}
