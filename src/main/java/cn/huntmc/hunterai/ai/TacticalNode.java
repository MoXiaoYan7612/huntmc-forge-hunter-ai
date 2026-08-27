package cn.huntmc.hunterai.ai;

import net.minecraft.world.phys.Vec3;

/** One locally sampled, standable tactical position. */
public record TacticalNode(
    Vec3 position,
    double cover,
    double shelter,
    boolean openToSky,
    boolean canObserveThreat,
    int escapeDirections,
    double verticalDelta,
    double score,
    double standingExposure,
    double crouchedExposure,
    double hardCover,
    double rearSafety,
    double flankRisk,
    double entranceControl,
    double unknownRisk) {
  public TacticalNode(
      Vec3 position,
      double cover,
      double shelter,
      boolean openToSky,
      boolean canObserveThreat,
      int escapeDirections,
      double verticalDelta,
      double score) {
    this(
        position,
        cover,
        shelter,
        openToSky,
        canObserveThreat,
        escapeDirections,
        verticalDelta,
        score,
        clamp(1.0D - cover),
        clamp(1.0D - Math.max(cover, shelter + 0.15D)),
        clamp(cover),
        clamp(escapeDirections / 4.0D),
        clamp(1.0D - escapeDirections / 4.0D),
        canObserveThreat ? clamp(cover + 0.25D) : clamp(cover * 0.5D),
        openToSky ? 0.65D : 0.30D);
  }

  public TacticalNode withSpatialRisk(
      double nextRearSafety, double nextFlankRisk, double nextEntranceControl, double nextUnknownRisk) {
    return new TacticalNode(
        position,
        cover,
        shelter,
        openToSky,
        canObserveThreat,
        escapeDirections,
        verticalDelta,
        score,
        standingExposure,
        crouchedExposure,
        hardCover,
        clamp(nextRearSafety),
        clamp(nextFlankRisk),
        clamp(nextEntranceControl),
        clamp(nextUnknownRisk));
  }
  public boolean isExposed() {
    return openToSky && cover < 0.35D;
  }

  public boolean isConcealed() {
    return shelter >= 0.50D || cover >= 0.50D;
  }

  private static double clamp(double value) {
    return Math.max(0.0D, Math.min(1.0D, value));
  }
}
