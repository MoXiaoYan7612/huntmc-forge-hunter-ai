package cn.huntmc.hunterai.ai;

import java.util.UUID;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Bounded reaction to the current aim ray of a visible, actively engaged real player. */
public final class PlayerPreAimModel {
  private static final double MAX_REACTION_MISS_DISTANCE = 3.0D;

  private PlayerPreAimModel() {}

  public static Assessment assess(
      Vec3 shooterEye,
      Vec3 shooterLook,
      Vec3 hunterEye,
      Vec3 hunterVelocity,
      double maximumDistance) {
    if (shooterEye == null || shooterLook == null || hunterEye == null || hunterVelocity == null
        || maximumDistance <= 0.0D || shooterLook.lengthSqr() < 1.0E-8D) return Assessment.none();
    Vec3 ray = shooterLook.normalize();
    Vec3 predictedHunter = hunterEye.add(
        hunterVelocity.x * 2.5D,
        hunterVelocity.y * 0.5D,
        hunterVelocity.z * 2.5D);
    double alongRay = predictedHunter.subtract(shooterEye).dot(ray);
    if (alongRay <= 0.0D || alongRay > maximumDistance) return Assessment.none();
    Vec3 closest = shooterEye.add(ray.scale(alongRay));
    double missDistance = predictedHunter.distanceTo(closest);
    if (missDistance >= MAX_REACTION_MISS_DISTANCE)
      return new Assessment(0.0D, missDistance, alongRay, false);
    double alignment = 1.0D - missDistance / MAX_REACTION_MISS_DISTANCE;
    double distanceFactor = 1.0D - 0.22D * Mth.clamp(alongRay / maximumDistance, 0.0D, 1.0D);
    double risk = Mth.clamp(alignment * distanceFactor, 0.0D, 1.0D);
    return new Assessment(risk, missDistance, alongRay, risk >= 0.42D);
  }

  /** Deliberately leaves a bounded false-negative rate so the reaction does not look omniscient. */
  public static boolean shouldReact(
      UUID hunterId,
      UUID playerId,
      long decisionWindow,
      double risk,
      double vigilance) {
    if (hunterId == null || playerId == null || risk < 0.42D) return false;
    long mixed = hunterId.getMostSignificantBits()
        ^ Long.rotateLeft(hunterId.getLeastSignificantBits(), 17)
        ^ Long.rotateLeft(playerId.getMostSignificantBits(), 31)
        ^ playerId.getLeastSignificantBits()
        ^ decisionWindow * 0x9E3779B97F4A7C15L;
    mixed ^= mixed >>> 33;
    mixed *= 0xff51afd7ed558ccdL;
    mixed ^= mixed >>> 33;
    double sample = (mixed >>> 11) * 0x1.0p-53;
    double chance = Mth.clamp(0.38D + risk * 0.24D + vigilance * 0.22D, 0.42D, 0.84D);
    return sample < chance;
  }

  public record Assessment(
      double risk, double missDistance, double distanceAlongRay, boolean threatening) {
    static Assessment none() {
      return new Assessment(0.0D, Double.POSITIVE_INFINITY, 0.0D, false);
    }
  }
}
