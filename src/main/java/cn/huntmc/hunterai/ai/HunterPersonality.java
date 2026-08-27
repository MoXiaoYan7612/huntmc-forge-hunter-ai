package cn.huntmc.hunterai.ai;

import java.util.SplittableRandom;
import java.util.UUID;

/** Stable individual decision tendencies derived from the Hunter UUID. */
public record HunterPersonality(
    double aggression,
    double patience,
    double riskTolerance,
    double ambushPreference,
    double flankPreference,
    double highGroundPreference,
    double coverPreference,
    double chasePersistence,
    double vigilance,
    double aimStability) {
  public static HunterPersonality fromUuid(UUID hunterId) {
    long seed = hunterId.getMostSignificantBits() ^ Long.rotateLeft(hunterId.getLeastSignificantBits(), 23);
    SplittableRandom random = new SplittableRandom(seed);
    return new HunterPersonality(
        tendency(random),
        tendency(random),
        tendency(random),
        tendency(random),
        tendency(random),
        tendency(random),
        tendency(random),
        tendency(random),
        tendency(random),
        tendency(random));
  }

  private static double tendency(SplittableRandom random) {
    return 0.20D + random.nextDouble() * 0.70D;
  }
}
