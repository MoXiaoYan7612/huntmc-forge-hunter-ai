package cn.huntmc.hunterai.action;

import cn.huntmc.hunterai.entity.HunterEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Shared, server-thread fire-line check used by policy and the final actuator safety check. */
public final class FireSafety {
  private FireSafety() {}

  public static boolean allyBlocksShot(HunterEntity hunter, Vec3 target) {
    if (target == null || !(hunter.level() instanceof ServerLevel level)) return false;
    Vec3 source = hunter.getEyePosition();
    double range = source.distanceTo(target);
    if (range < 0.5D) return false;
    var search = hunter.getBoundingBox().expandTowards(target.subtract(source)).inflate(1.5D);
    return level.getEntitiesOfClass(
            LivingEntity.class,
            search,
            candidate -> candidate != hunter && candidate.isAlive() && hunter.isMatchAlly(candidate))
        .stream()
        .anyMatch(
            ally -> distanceToSegmentSquared(ally.getEyePosition(), source, target)
                <= Math.pow(Math.max(0.45D, ally.getBbWidth() * 0.65D), 2.0D));
  }

  static double distanceToSegmentSquared(Vec3 point, Vec3 start, Vec3 end) {
    Vec3 segment = end.subtract(start);
    double lengthSquared = segment.lengthSqr();
    if (lengthSquared < 1.0E-9D) return point.distanceToSqr(start);
    double projection = Mth.clamp(point.subtract(start).dot(segment) / lengthSquared, 0.0D, 1.0D);
    return point.distanceToSqr(start.add(segment.scale(projection)));
  }
}
