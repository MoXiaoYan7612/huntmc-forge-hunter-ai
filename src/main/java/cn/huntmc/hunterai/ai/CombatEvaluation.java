package cn.huntmc.hunterai.ai;

import java.util.Locale;

/**
 * A conservative combat comparison. It does not turn low duel odds into an automatic retreat:
 * both the chance of winning the exchange and the chance of safely breaking contact are scored.
 */
public record CombatEvaluation(
    double duelChance,
    double escapeChance,
    double dodgeUrgency,
    double ammunitionReadiness,
    double rangeFit,
    boolean escapeFavorable,
    String reason) {
  public static CombatEvaluation assess(
      SelfModel.Snapshot self,
      double enemyHealthRatio,
      double ammunitionReadiness,
      double distance,
      double preferredRange,
      double maximumRange,
      double preAimRisk,
      boolean coverAvailable) {
    double clampedAmmo = clamp(ammunitionReadiness);
    double rangeFit =
        distance <= preferredRange
            ? 1.0D
            : clamp(1.0D - (distance - preferredRange) / Math.max(1.0D, maximumRange - preferredRange));
    double cover = coverAvailable ? 1.0D : 0.0D;
    double duel =
        clamp(
            0.08D
                + self.healthRatio() * 0.32D
                + (1.0D - clamp(enemyHealthRatio)) * 0.14D
                + clampedAmmo * 0.17D
                + rangeFit * 0.12D
                + (1.0D - self.exposure()) * 0.10D
                - preAimRisk * 0.14D
                - (self.reloading() ? 0.18D : 0.0D));
    double escape =
        clamp(
            0.08D
                + self.healthRatio() * 0.22D
                + cover * 0.34D
                + (1.0D - self.exposure()) * 0.16D
                + (distance > preferredRange ? 0.08D : 0.0D)
                - preAimRisk * 0.18D
                - (self.reloading() ? 0.10D : 0.0D));
    double urgency =
        clamp(
            preAimRisk * 0.44D
                + self.exposure() * 0.22D
                + (self.recentlyDamaged() ? 0.28D : 0.0D)
                + (self.reloading() ? 0.18D : 0.0D)
                + (clampedAmmo < 0.25D ? 0.12D : 0.0D));
    boolean escapeFavorable = escape > duel + 0.12D;
    String reason =
        "duel="
            + decimal(duel)
            + " escape="
            + decimal(escape)
            + " dodge="
            + decimal(urgency)
            + " ammo="
            + decimal(clampedAmmo)
            + " range="
            + decimal(rangeFit)
            + " cover="
            + coverAvailable
            + " escapeFavorable="
            + escapeFavorable;
    return new CombatEvaluation(duel, escape, urgency, clampedAmmo, rangeFit, escapeFavorable, reason);
  }

  public static CombatEvaluation unavailable() {
    return new CombatEvaluation(0.0D, 0.0D, 0.0D, 0.0D, 0.0D, false, "no-visible-combat");
  }

  private static double clamp(double value) {
    return Math.max(0.0D, Math.min(1.0D, value));
  }

  private static String decimal(double value) {
    return String.format(Locale.ROOT, "%.2f", value);
  }
}
