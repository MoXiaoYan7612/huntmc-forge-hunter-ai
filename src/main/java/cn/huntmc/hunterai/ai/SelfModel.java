package cn.huntmc.hunterai.ai;

/** Tracks the Hunter's vulnerability and how compromised its current combat position is. */
public final class SelfModel {
  private double compromise;
  private double exposure;
  private long lastShotTime = Long.MIN_VALUE;
  private long lastDamageTime = Long.MIN_VALUE;

  public void tick(long now, double localExposure) {
    exposure = clamp(localExposure);
    compromise = Math.max(0.0D, compromise - 0.012D);
    if (!occurredWithin(lastShotTime, now, 40L)) compromise = Math.max(0.0D, compromise - 0.010D);
  }

  public void recordShot(long now, double repeatPenalty) {
    lastShotTime = now;
    compromise = clamp(compromise + 0.14D + repeatPenalty * 0.20D);
  }

  public void recordDamage(long now, double amount, double maximumHealth) {
    lastDamageTime = now;
    compromise = clamp(compromise + 0.20D + amount / Math.max(1.0D, maximumHealth) * 0.50D);
  }

  public Snapshot snapshot(float health, float maximumHealth, boolean reloading, long now) {
    return new Snapshot(
        health / Math.max(1.0F, maximumHealth),
        compromise,
        exposure,
        reloading,
        occurredWithin(lastShotTime, now, 40L),
        occurredWithin(lastDamageTime, now, 60L));
  }

  private static boolean occurredWithin(long eventAt, long now, long windowTicks) {
    return eventAt != Long.MIN_VALUE && now >= eventAt && now - eventAt <= windowTicks;
  }

  private static double clamp(double value) {
    return Math.max(0.0D, Math.min(1.0D, value));
  }

  public record Snapshot(
      double healthRatio,
      double compromise,
      double exposure,
      boolean reloading,
      boolean recentlyFired,
      boolean recentlyDamaged) {}
}
