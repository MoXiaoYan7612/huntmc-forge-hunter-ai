package cn.huntmc.hunterai.ai;

import cn.huntmc.hunterai.entity.HunterEntity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Sparse, decaying evidence about a target rather than a persistent live coordinate. */
public final class EnemyBelief {
  private static final long VISUAL_EXPIRY_TICKS = 200L;
  private static final long HEARING_EXPIRY_TICKS = 100L;
  private static final double MIN_HEARING_RELIABILITY = 0.30D;
  private static final double MAX_HEARING_RELIABILITY = 0.75D;
  private static final double HEARING_EVIDENCE_WEIGHT = 0.65D;
  private final Map<BlockPos, Double> occupancy = new LinkedHashMap<>();
  private UUID lastSeenTarget;
  private Vec3 lastSeenPosition;
  private Vec3 lastHeardPosition;
  private Vec3 lastMotion = Vec3.ZERO;
  private long lastSeenTime = Long.MIN_VALUE;
  private long lastHeardTime = Long.MIN_VALUE;
  private double lastHeardReliability;

  public void observe(UUID targetId, Vec3 position, long gameTime) {
    if (lastSeenPosition != null && lastSeenTime != Long.MIN_VALUE) {
      long elapsed = Math.max(1L, gameTime - lastSeenTime);
      Vec3 sampledVelocity = position.subtract(lastSeenPosition).scale(1.0D / elapsed);
      lastMotion = sampledVelocity.lengthSqr() > 0.0625D ? sampledVelocity.normalize().scale(0.25D) : sampledVelocity;
    }
    lastSeenTarget = targetId;
    lastSeenPosition = position;
    lastSeenTime = gameTime;
    occupancy.clear();
    occupancy.put(BlockPos.containing(position), 1.0D);
  }

  /**
   * Records a noisy area, not a confirmed target. Louder sounds are more credible, but even a
   * gunshot remains less certain than direct visual contact because it has no identity or exact
   * position.
   */
  public void hear(Vec3 position, float strength, long gameTime) {
    lastHeardPosition = position;
    lastHeardTime = gameTime;
    lastHeardReliability = hearingReliability(strength);
    addEvidence(position, HEARING_EVIDENCE_WEIGHT * lastHeardReliability);
  }

  /** A successful hit confirms an immediate hostile threat even when its location is approximate. */
  public void confirmDamage(Vec3 position, long gameTime) {
    lastHeardPosition = position;
    lastHeardTime = gameTime;
    lastHeardReliability = 1.0D;
    addEvidence(position, HEARING_EVIDENCE_WEIGHT);
  }

  /**
   * Diffuses only through locally occupiable cells. This is a small, bounded approximation of an
   * enemy flow field; it does not let a remembered enemy probability pass through a solid wall.
   */
  public void tick(HunterEntity hunter, long gameTime) {
    ServerLevel level = hunter.level() instanceof ServerLevel serverLevel ? serverLevel : null;
    Map<BlockPos, Double> diffused = new LinkedHashMap<>();
    for (Map.Entry<BlockPos, Double> entry : occupancy.entrySet().stream()
        .sorted(Map.Entry.<BlockPos, Double>comparingByValue().reversed())
        .limit(32)
        .toList()) {
      double retained = entry.getValue() * 0.66D;
      diffused.merge(entry.getKey(), retained, Double::sum);
      double spread = entry.getValue() * 0.06D;
      spreadIfReachable(diffused, level, entry.getKey().north(), spread);
      spreadIfReachable(diffused, level, entry.getKey().south(), spread);
      spreadIfReachable(diffused, level, entry.getKey().east(), spread);
      spreadIfReachable(diffused, level, entry.getKey().west(), spread);
    }
    occupancy.clear();
    diffused.entrySet().stream()
        .sorted(Map.Entry.<BlockPos, Double>comparingByValue().reversed())
        .limit(128)
        .forEach(entry -> occupancy.put(entry.getKey(), entry.getValue()));
    occupancy.replaceAll((ignored, value) -> value * 0.90D);
    occupancy.entrySet().removeIf(entry -> entry.getValue() < 0.03D);
    if (isExpired(lastSeenTime, VISUAL_EXPIRY_TICKS, gameTime)
        && isExpired(lastHeardTime, HEARING_EXPIRY_TICKS, gameTime)) {
      clear();
    }
  }

  public boolean hasEvidence() {
    return lastSeenPosition != null || lastHeardPosition != null;
  }

  public Vec3 predictedPosition(long gameTime) {
    if (!occupancy.isEmpty()) {
      return occupancy.entrySet().stream()
          .max(Map.Entry.comparingByValue())
          .map(entry -> Vec3.atCenterOf(entry.getKey()))
          .orElse(lastHeardPosition);
    }
    return lastHeardPosition;
  }

  public double confidence(long gameTime) {
    return Math.max(
        decayedConfidence(1.0D, lastSeenTime, VISUAL_EXPIRY_TICKS, gameTime),
        decayedConfidence(lastHeardReliability, lastHeardTime, HEARING_EXPIRY_TICKS, gameTime));
  }

  /** Top probability cells are diagnostics and tactical hints, never a live target reference. */
  public List<BeliefCell> topCells(int limit) {
    return occupancy.entrySet().stream()
        .sorted(Map.Entry.<BlockPos, Double>comparingByValue().reversed())
        .limit(Math.max(0, limit))
        .map(entry -> new BeliefCell(Vec3.atCenterOf(entry.getKey()), entry.getValue()))
        .toList();
  }

  public double uncertaintyRadius() {
    if (occupancy.isEmpty()) return 0.0D;
    Vec3 center =
        occupancy.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(entry -> Vec3.atCenterOf(entry.getKey()))
            .orElse(Vec3.ZERO);
    double totalWeight = occupancy.values().stream().mapToDouble(Double::doubleValue).sum();
    if (totalWeight <= 0.0D) return 0.0D;
    double weightedDistance =
        occupancy.entrySet().stream()
            .mapToDouble(
                entry -> Vec3.atCenterOf(entry.getKey()).distanceTo(center) * entry.getValue())
            .sum();
    return weightedDistance / totalWeight;
  }

  /** Direction inferred only from prior observations; it is deliberately low-confidence. */
  public Vec3 expectedFlow() {
    return lastMotion;
  }

  public UUID lastSeenTarget() {
    return lastSeenTarget;
  }

  /** Clears only a matching visual identity while retaining any independent sound evidence. */
  public void forgetTarget(UUID targetId, long gameTime) {
    if (targetId == null || !targetId.equals(lastSeenTarget)) return;
    lastSeenTarget = null;
    lastSeenPosition = null;
    lastSeenTime = Long.MIN_VALUE;
    lastMotion = Vec3.ZERO;
    occupancy.clear();
    if (lastHeardPosition != null
        && !isExpired(lastHeardTime, HEARING_EXPIRY_TICKS, gameTime))
      addEvidence(lastHeardPosition, HEARING_EVIDENCE_WEIGHT * lastHeardReliability);
  }

  public long lastHeardTime() {
    return lastHeardTime;
  }

  public double lastHeardReliability() {
    return lastHeardReliability;
  }

  static double hearingReliability(float strength) {
    double clampedStrength =
        Float.isFinite(strength) ? Math.max(0.0D, Math.min(1.0D, strength)) : 0.0D;
    return MIN_HEARING_RELIABILITY
        + (MAX_HEARING_RELIABILITY - MIN_HEARING_RELIABILITY) * clampedStrength;
  }

  private void addEvidence(Vec3 position, double weight) {
    BlockPos center = BlockPos.containing(position);
    occupancy.merge(center, weight, Double::sum);
    occupancy.merge(center.north(), weight * 0.35D, Double::sum);
    occupancy.merge(center.south(), weight * 0.35D, Double::sum);
    occupancy.merge(center.east(), weight * 0.35D, Double::sum);
    occupancy.merge(center.west(), weight * 0.35D, Double::sum);
  }

  private static void spreadIfReachable(
      Map<BlockPos, Double> diffused, ServerLevel level, BlockPos to, double spread) {
    if (level != null && !canOccupy(level, to)) return;
    diffused.merge(to, spread, Double::sum);
  }

  private static boolean canOccupy(ServerLevel level, BlockPos feet) {
    BlockState foot = level.getBlockState(feet);
    BlockState head = level.getBlockState(feet.above());
    return foot.isAir() && head.isAir() && !level.getBlockState(feet.below()).isAir();
  }

  private static double decayedConfidence(
      double reliability, long recordedAt, long retentionTicks, long gameTime) {
    if (recordedAt == Long.MIN_VALUE) return 0.0D;
    long elapsed = Math.max(0L, gameTime - recordedAt);
    if (elapsed >= retentionTicks) return 0.0D;
    return reliability * (1.0D - elapsed / (double) retentionTicks);
  }

  private static boolean isExpired(long recordedAt, long retentionTicks, long gameTime) {
    return recordedAt == Long.MIN_VALUE || gameTime - recordedAt >= retentionTicks;
  }

  private void clear() {
    occupancy.clear();
    lastSeenTarget = null;
    lastSeenPosition = null;
    lastHeardPosition = null;
    lastMotion = Vec3.ZERO;
    lastSeenTime = Long.MIN_VALUE;
    lastHeardTime = Long.MIN_VALUE;
    lastHeardReliability = 0.0D;
  }

  public record BeliefCell(Vec3 position, double probability) {}
}
