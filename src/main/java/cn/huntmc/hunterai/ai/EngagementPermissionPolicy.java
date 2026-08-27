package cn.huntmc.hunterai.ai;

/**
 * Keeps visual contact from becoming an unconditional fire command.  This is deliberately a
 * small, deterministic gate: the tactical planner still owns the intent and the combat
 * controller still owns the final aim/fire timing.
 */
public final class EngagementPermissionPolicy {
  public enum Permission {
    HOLD_FIRE,
    ENGAGE,
    REPOSITION,
    BREAK_CONTACT
  }

  private EngagementPermissionPolicy() {}

  public static Permission decide(
      HunterIntent intent,
      double distance,
      double preferredRange,
      double healthRatio,
      double localExposure,
      boolean hardCoverAvailable,
      boolean currentlyInHardCover,
      boolean pressured,
      boolean playerAimThreat,
      int shotsAtTarget,
      long targetAgeTicks,
      HunterPersonality personality,
      CombatEvaluation evaluation) {
    if (healthRatio <= 0.30D
        && hardCoverAvailable
        && evaluation != null
        && evaluation.escapeFavorable()) return Permission.BREAK_CONTACT;
    if (pressured || playerAimThreat) return Permission.ENGAGE;
    if (healthRatio <= 0.42D && hardCoverAvailable && evaluation != null
        && evaluation.escapeChance() >= evaluation.duelChance() + 0.06D)
      return Permission.BREAK_CONTACT;
    if (localExposure >= 0.60D && shotsAtTarget >= 2 && hardCoverAvailable)
      return Permission.REPOSITION;

    boolean patientIntent = intent == HunterIntent.AMBUSH
        || intent == HunterIntent.OVERWATCH
        || intent == HunterIntent.LISTEN;
    long observationWindow = 10L + Math.round((personality == null ? 0.5D : personality.patience()) * 14.0D);
    if (patientIntent
        && shotsAtTarget == 0
        && targetAgeTicks >= 0L
        && targetAgeTicks < observationWindow
        && healthRatio >= 0.48D
        && currentlyInHardCover
        && localExposure < 0.36D
        && distance > Math.max(10.0D, preferredRange * 0.90D))
      return Permission.HOLD_FIRE;
    return Permission.ENGAGE;
  }
}
