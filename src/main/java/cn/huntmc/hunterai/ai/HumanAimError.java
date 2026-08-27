package cn.huntmc.hunterai.ai;

import cn.huntmc.hunterai.combat.CloseRangeCombatTuning;
import cn.huntmc.hunterai.profile.HunterProfile;
import java.util.Random;
import java.util.UUID;

/**
 * Contact-stable close-range aim error. The offset changes slowly instead of rerolling every tick,
 * so the Hunter remains responsive without acquiring perfect mouse input.
 */
final class HumanAimError {
  private static final double YAW_APPROACH_PER_TICK = 0.36D;
  private static final double PITCH_APPROACH_PER_TICK = 0.26D;

  private UUID targetId;
  private long contactSequence = Long.MIN_VALUE;
  private long lastTick = Long.MIN_VALUE;
  private long nextRetargetAt = Long.MIN_VALUE;
  private long retargetSequence;
  private double currentYaw;
  private double currentPitch;
  private double targetYaw;
  private double targetPitch;
  private double lastYawAmplitude;
  private double lastPitchAmplitude;

  Offset tick(
      UUID hunterId,
      UUID currentTargetId,
      long currentContactSequence,
      HunterProfile profile,
      double distance,
      boolean moving,
      boolean recentlyDamaged,
      long now) {
    double influence = CloseRangeCombatTuning.influence(distance);
    if (influence <= 0.0D) {
      reset();
      return Offset.NONE;
    }
    if (!java.util.Objects.equals(targetId, currentTargetId)
        || contactSequence != currentContactSequence
        || lastTick == Long.MIN_VALUE
        || now < lastTick) {
      beginContact(currentTargetId, currentContactSequence, now);
    }

    lastYawAmplitude = CloseRangeCombatTuning.yawErrorAmplitude(
        profile, distance, moving, recentlyDamaged);
    lastPitchAmplitude = CloseRangeCombatTuning.pitchErrorAmplitude(
        profile, distance, moving, recentlyDamaged);
    if (now >= nextRetargetAt) {
      Random random = random(hunterId, currentTargetId, currentContactSequence, retargetSequence++);
      targetYaw = signed(random) * lastYawAmplitude;
      targetPitch = signed(random) * lastPitchAmplitude;
      nextRetargetAt = now + 4L + random.nextInt(4);
    } else {
      targetYaw = clamp(targetYaw, -lastYawAmplitude, lastYawAmplitude);
      targetPitch = clamp(targetPitch, -lastPitchAmplitude, lastPitchAmplitude);
    }

    long elapsed = Math.max(1L, now - lastTick);
    currentYaw = approach(currentYaw, targetYaw, YAW_APPROACH_PER_TICK * elapsed);
    currentPitch = approach(currentPitch, targetPitch, PITCH_APPROACH_PER_TICK * elapsed);
    lastTick = now;
    return new Offset(currentYaw, currentPitch);
  }

  void onShot(UUID hunterId, UUID currentTargetId, long shotSequence) {
    if (!java.util.Objects.equals(targetId, currentTargetId)
        || lastYawAmplitude <= 0.0D
        || lastPitchAmplitude <= 0.0D) return;
    Random random = random(hunterId, currentTargetId, contactSequence, 10_000L + shotSequence);
    double lateralImpulse = (random.nextBoolean() ? 1.0D : -1.0D)
        * Math.min(0.45D, lastYawAmplitude * 0.30D);
    double upwardImpulse = Math.min(0.55D, lastPitchAmplitude * 0.40D);
    currentYaw = clamp(currentYaw + lateralImpulse, -lastYawAmplitude, lastYawAmplitude);
    currentPitch = clamp(currentPitch - upwardImpulse, -lastPitchAmplitude, lastPitchAmplitude);
    targetYaw = clamp(targetYaw + lateralImpulse * 0.55D, -lastYawAmplitude, lastYawAmplitude);
    targetPitch = clamp(targetPitch - upwardImpulse * 0.55D, -lastPitchAmplitude, lastPitchAmplitude);
  }

  void reset() {
    targetId = null;
    contactSequence = Long.MIN_VALUE;
    lastTick = Long.MIN_VALUE;
    nextRetargetAt = Long.MIN_VALUE;
    retargetSequence = 0L;
    currentYaw = 0.0D;
    currentPitch = 0.0D;
    targetYaw = 0.0D;
    targetPitch = 0.0D;
    lastYawAmplitude = 0.0D;
    lastPitchAmplitude = 0.0D;
  }

  private void beginContact(UUID currentTargetId, long currentContactSequence, long now) {
    reset();
    targetId = currentTargetId;
    contactSequence = currentContactSequence;
    lastTick = now;
    nextRetargetAt = now;
  }

  private static Random random(
      UUID hunterId, UUID targetId, long contactSequence, long sequence) {
    long hunter = hunterId == null
        ? 0L
        : hunterId.getMostSignificantBits() ^ Long.rotateLeft(hunterId.getLeastSignificantBits(), 17);
    long target = targetId == null
        ? 0L
        : targetId.getMostSignificantBits() ^ Long.rotateLeft(targetId.getLeastSignificantBits(), 31);
    return new Random(hunter ^ target ^ contactSequence * 0x9E3779B97F4A7C15L
        ^ sequence * 0xD1B54A32D192ED03L);
  }

  private static double signed(Random random) {
    return random.nextDouble() * 2.0D - 1.0D;
  }

  private static double approach(double current, double target, double maximumStep) {
    if (current < target) return Math.min(current + maximumStep, target);
    return Math.max(current - maximumStep, target);
  }

  private static double clamp(double value, double minimum, double maximum) {
    return Math.max(minimum, Math.min(maximum, value));
  }

  record Offset(double yawDegrees, double pitchDegrees) {
    private static final Offset NONE = new Offset(0.0D, 0.0D);
  }
}
