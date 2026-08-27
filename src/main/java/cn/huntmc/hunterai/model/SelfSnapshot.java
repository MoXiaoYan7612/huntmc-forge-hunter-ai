package cn.huntmc.hunterai.model;

import net.minecraft.world.phys.Vec3;

public record SelfSnapshot(
    Vec3 position,
    Vec3 velocity,
    float yaw,
    float pitch,
    double health,
    double maxHealth,
    int ammunition,
    int magazineCapacity,
    boolean reloading,
    boolean onGround,
    boolean crouching,
    boolean sprinting,
    boolean climbing,
    boolean inWater) {
  public double healthRatio() {
    return maxHealth <= 0.0D ? 0.0D : Math.max(0.0D, Math.min(1.0D, health / maxHealth));
  }

  public double ammoRatio() {
    return magazineCapacity <= 0 ? 0.0D : Math.max(0.0D, Math.min(1.0D, ammunition / (double) magazineCapacity));
  }
}
