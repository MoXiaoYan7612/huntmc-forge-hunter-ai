package cn.huntmc.hunterai.ai;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import net.minecraft.world.phys.Vec3;

/** Short-lived history that makes repeated plans, routes, and shooting angles less attractive. */
public final class RecentBehavior {
  private static final long HISTORY_TICKS = 240L;
  private static final int MAX_ENTRIES_PER_KIND = 24;
  private final Deque<Entry> entries = new ArrayDeque<>();

  public void recordIntent(HunterIntent intent, long now) {
    add(new Entry(Kind.INTENT, intent.name(), null, 0.0F, now + HISTORY_TICKS));
  }

  public void recordPosition(Vec3 position, long now) {
    if (position == null) return;
    add(new Entry(Kind.POSITION, "position", position, 0.0F, now + HISTORY_TICKS));
  }

  public void recordFireAngle(float yaw, long now) {
    add(new Entry(Kind.FIRE_ANGLE, "fire", null, yaw, now + HISTORY_TICKS));
  }

  public double intentPenalty(HunterIntent intent, long now) {
    prune(now);
    return Math.min(0.55D, entries.stream().filter(entry -> entry.kind == Kind.INTENT && entry.key.equals(intent.name())).count() * 0.16D);
  }

  public double positionPenalty(Vec3 position, long now) {
    if (position == null) return 0.0D;
    prune(now);
    return Math.min(
        0.60D,
        entries.stream()
                .filter(entry -> entry.kind == Kind.POSITION && entry.position.distanceToSqr(position) <= 16.0D)
                .count()
            * 0.18D);
  }

  public double fireAnglePenalty(float yaw, long now) {
    prune(now);
    return Math.min(
        0.50D,
        entries.stream()
                .filter(entry -> entry.kind == Kind.FIRE_ANGLE && Math.abs(wrapDegrees(yaw - entry.yaw)) <= 20.0F)
                .count()
            * 0.15D);
  }

  public int size(long now) {
    prune(now);
    return entries.size();
  }

  public void clear() {
    entries.clear();
  }

  private void add(Entry entry) {
    entries.addLast(entry);
    int entriesOfKind = 0;
    for (Entry candidate : entries) {
      if (candidate.kind == entry.kind) entriesOfKind++;
    }
    if (entriesOfKind <= MAX_ENTRIES_PER_KIND) return;
    Iterator<Entry> iterator = entries.iterator();
    while (iterator.hasNext()) {
      if (iterator.next().kind == entry.kind) {
        iterator.remove();
        return;
      }
    }
  }

  private void prune(long now) {
    while (!entries.isEmpty() && entries.peekFirst().expiresAt <= now) entries.removeFirst();
  }

  private static float wrapDegrees(float value) {
    float wrapped = value % 360.0F;
    if (wrapped >= 180.0F) return wrapped - 360.0F;
    if (wrapped < -180.0F) return wrapped + 360.0F;
    return wrapped;
  }

  private enum Kind {
    INTENT,
    POSITION,
    FIRE_ANGLE
  }

  private record Entry(Kind kind, String key, Vec3 position, float yaw, long expiresAt) {}
}
