package cn.huntmc.hunterai.model;

import net.minecraft.world.phys.Vec3;

/** Spatial estimate which expands as information ages. */
public record EvidenceRegion(Vec3 center, double radius, Vec3 motionTrend, double expansionPerTick) {
  public EvidenceRegion {
    if (center == null) throw new IllegalArgumentException("region center is required");
    radius = Math.max(0.0D, radius);
    motionTrend = motionTrend == null ? Vec3.ZERO : motionTrend;
    expansionPerTick = Math.max(0.0D, expansionPerTick);
  }

  public EvidenceRegion atAge(long ticks) {
    long age = Math.max(0L, ticks);
    return new EvidenceRegion(
        center.add(motionTrend.scale(age)), radius + expansionPerTick * age, motionTrend, expansionPerTick);
  }
}
