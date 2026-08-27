package cn.huntmc.hunterai.ai;

import net.minecraft.world.phys.Vec3;

public record ThreatCorridor(
    String id, Vec3 origin, Vec3 direction, double width, double arrivalTicks, double probability, String source) {
  public ThreatCorridor {
    id = id == null ? "" : id;
    source = source == null ? "" : source;
    probability = Math.max(0.0D, Math.min(1.0D, probability));
  }
}
