package cn.huntmc.hunterai.ai;

import net.minecraft.world.phys.Vec3;

/** Conservative model of what an enemy may know about the Hunter; it never reads hidden enemy state. */
public final class EnemyModel {
  private Vec3 expectedHunterPosition;
  private Vec3 recentSurpriseSource;
  private long expectedPositionUntil;
  private long surpriseUntil;

  public void markExposure(Vec3 position, long now) {
    expectedHunterPosition = position;
    expectedPositionUntil = now + 120L;
  }

  public void markSurprise(Vec3 source, long now) {
    recentSurpriseSource = source;
    surpriseUntil = now + 160L;
  }

  public double preAimRisk(Vec3 position, long now) {
    if (position == null || expectedHunterPosition == null || now >= expectedPositionUntil) return 0.0D;
    return Math.max(0.0D, 1.0D - Math.sqrt(position.distanceToSqr(expectedHunterPosition)) / 12.0D)
        * (expectedPositionUntil - now)
        / 120.0D;
  }

  public Vec3 recentSurpriseSource(long now) {
    return now < surpriseUntil ? recentSurpriseSource : null;
  }

  public long recentSurpriseTime(long now) {
    return now < surpriseUntil ? surpriseUntil - 160L : Long.MIN_VALUE;
  }
}
