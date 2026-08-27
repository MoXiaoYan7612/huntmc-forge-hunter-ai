package cn.huntmc.hunterai.ai;

import net.minecraft.world.phys.Vec3;

/**
 * Retains one legally observed aim point during a brief combat visual dropout. The point is never
 * refreshed without a new visual observation and is not target tracking: callers may hold their
 * aim on it, but must not fire without a current visible target.
 */
final class CombatAimLock {
  private final long durationTicks;
  private Vec3 lastObservedPoint;
  private long observedAt = Long.MIN_VALUE;

  CombatAimLock(long durationTicks) {
    this.durationTicks = Math.max(1L, durationTicks);
  }

  void observe(Vec3 point, long now) {
    if (point == null) return;
    lastObservedPoint = point;
    observedAt = now;
  }

  Vec3 point(long now) {
    return active(now) ? lastObservedPoint : null;
  }

  long remainingTicks(long now) {
    if (!active(now)) return 0L;
    return durationTicks - (now - observedAt);
  }

  long observedAt() {
    return observedAt;
  }

  void clear() {
    lastObservedPoint = null;
    observedAt = Long.MIN_VALUE;
  }

  private boolean active(long now) {
    return lastObservedPoint != null
        && observedAt != Long.MIN_VALUE
        && now >= observedAt
        && now - observedAt < durationTicks;
  }
}
