package cn.huntmc.hunterai.model;

import java.util.Arrays;
import net.minecraft.util.Mth;

/** Eight local sectors accumulate uncertainty while unobserved and compromise after combat. */
public final class ExposureMemory {
  private static final int SECTORS = 8;
  private final long[] lastConfirmed = new long[SECTORS];
  private double compromise;

  public ExposureMemory() {
    Arrays.fill(lastConfirmed, Long.MIN_VALUE / 2L);
  }

  public void confirmFacing(float yaw, long now) {
    lastConfirmed[sector(yaw)] = now;
  }

  public void fired() {
    compromise = Math.min(1.0D, compromise + 0.20D);
  }

  public void damaged() {
    compromise = Math.min(1.0D, compromise + 0.30D);
  }

  public Snapshot snapshot(long now) {
    double[] unknownRisk = new double[SECTORS];
    for (int index = 0; index < SECTORS; index++) {
      long age = lastConfirmed[index] <= Long.MIN_VALUE / 4L ? 600L : Math.max(0L, now - lastConfirmed[index]);
      unknownRisk[index] = Math.min(1.0D, age / 240.0D);
    }
    compromise = Math.max(0.0D, compromise - 0.0025D);
    return new Snapshot(unknownRisk, compromise);
  }

  public int stalestSector(long now, float avoidYaw) {
    int avoid = sector(avoidYaw);
    int best = -1;
    long oldest = Long.MAX_VALUE;
    for (int index = 0; index < SECTORS; index++) {
      if (index == avoid) continue;
      if (lastConfirmed[index] < oldest) {
        oldest = lastConfirmed[index];
        best = index;
      }
    }
    return best < 0 ? Math.floorMod(avoid + 4, SECTORS) : best;
  }

  public static float sectorYaw(int sector) {
    return Mth.wrapDegrees(Math.floorMod(sector, SECTORS) * (360.0F / SECTORS));
  }

  private static int sector(float yaw) {
    return Math.floorMod(Math.round(Mth.wrapDegrees(yaw) / (360.0F / SECTORS)), SECTORS);
  }

  public record Snapshot(double[] unknownRisk, double compromise) {
    public Snapshot {
      unknownRisk = unknownRisk.clone();
    }

    @Override
    public double[] unknownRisk() {
      return unknownRisk.clone();
    }
  }
}
