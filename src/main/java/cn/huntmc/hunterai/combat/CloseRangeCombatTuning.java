package cn.huntmc.hunterai.combat;

import cn.huntmc.hunterai.profile.HunterProfile;

/**
 * Bounded mechanical fairness tuning for close combat. Perception, attention, weapon cadence and
 * every engagement outside the blend band remain unchanged.
 */
public final class CloseRangeCombatTuning {
  public static final double FULL_EFFECT_DISTANCE = 10.0D;
  public static final double EFFECT_END_DISTANCE = 14.0D;

  private CloseRangeCombatTuning() {}

  public static double influence(double distance) {
    if (!Double.isFinite(distance) || distance >= EFFECT_END_DISTANCE) return 0.0D;
    if (distance <= FULL_EFFECT_DISTANCE) return 1.0D;
    return (EFFECT_END_DISTANCE - distance)
        / (EFFECT_END_DISTANCE - FULL_EFFECT_DISTANCE);
  }

  public static float combatYawStep(boolean firing, double distance) {
    float normal = firing ? 16.0F : 9.0F;
    float close = firing ? 10.0F : 8.0F;
    return blend(normal, close, influence(distance));
  }

  public static float combatPitchStep(boolean firing, double distance) {
    float normal = firing ? 12.0F : 7.0F;
    float close = firing ? 7.0F : 6.0F;
    return blend(normal, close, influence(distance));
  }

  public static double aimHeight(double distance, double requestedHeight) {
    double bounded = Math.max(0.45D, Math.min(0.90D, requestedHeight));
    double closeBody = Math.max(0.58D, Math.min(0.74D, bounded));
    return blend(bounded, closeBody, influence(distance));
  }

  public static double yawErrorAmplitude(
      HunterProfile profile, double distance, boolean moving, boolean recentlyDamaged) {
    double influence = influence(distance);
    if (influence <= 0.0D) return 0.0D;
    double base = profile == HunterProfile.SHOTGUN ? 2.20D : 1.70D;
    return base * conditionScale(moving, recentlyDamaged) * influence;
  }

  public static double pitchErrorAmplitude(
      HunterProfile profile, double distance, boolean moving, boolean recentlyDamaged) {
    double influence = influence(distance);
    if (influence <= 0.0D) return 0.0D;
    double base = profile == HunterProfile.SHOTGUN ? 1.50D : 1.20D;
    return base * conditionScale(moving, recentlyDamaged) * influence;
  }

  private static double conditionScale(boolean moving, boolean recentlyDamaged) {
    double scale = moving ? 1.18D : 1.0D;
    if (recentlyDamaged) scale *= 1.12D;
    return scale;
  }

  private static float blend(float normal, float close, double influence) {
    return (float) (normal + (close - normal) * influence);
  }

  private static double blend(double normal, double close, double influence) {
    return normal + (close - normal) * influence;
  }
}
