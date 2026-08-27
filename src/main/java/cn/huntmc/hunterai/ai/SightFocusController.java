package cn.huntmc.hunterai.ai;

import java.util.UUID;

/**
 * Accumulates a bounded ambush aim advantage only while a legally visible player has not aimed
 * back. It never reads client state or an unseen target position.
 */
public final class SightFocusController {
  private static final double MIN_DISTANCE = 10.0D;
  private static final long MIN_FOCUS_TICKS = 10L;
  private static final long MAX_FOCUS_TICKS = 28L;
  private static final double MAX_FOCUSED_HEAD_CHANCE = 0.32D;

  private UUID targetId;
  private State state = State.NONE;
  private long focusedSince = Long.MIN_VALUE;
  private long focusTicks;
  private double quality;
  private String reason = "none";

  public Decision tick(
      UUID hunterId,
      UUID visibleTargetId,
      double distance,
      double aimError,
      boolean concealed,
      boolean moving,
      boolean playerAimingAtHunter,
      boolean pressured,
      long now) {
    if (hunterId == null || visibleTargetId == null) return clear("no-legal-visual");
    if (!visibleTargetId.equals(targetId)) {
      targetId = visibleTargetId;
      focusedSince = Long.MIN_VALUE;
      focusTicks = 0L;
      quality = 0.0D;
      state = State.NONE;
    }
    if (pressured) return discovered("direct-damage");
    if (playerAimingAtHunter) return discovered("player-aim-confirmed");
    // Once the player has acknowledged this Hunter, the ambush window is over for the entire
    // legal contact. Do not resume holding fire merely because the player flicked their view away
    // on the following tick.
    if (state == State.DISCOVERED) return discovered(reason);
    boolean eligible = distance >= MIN_DISTANCE
        && concealed
        && !moving
        && Double.isFinite(aimError)
        && aimError <= 7.0D;
    if (!eligible) {
      focusTicks = Math.max(0L, focusTicks - 2L);
      quality = requiredTicks(distance) == 0L ? 0.0D : clamp(focusTicks / (double) requiredTicks(distance));
      state = State.NONE;
      reason = distance < MIN_DISTANCE
          ? "target-too-close"
          : !concealed ? "position-exposed" : moving ? "hunter-moving" : "aim-not-stable";
      return decision(false);
    }
    if (focusedSince == Long.MIN_VALUE) focusedSince = now;
    focusTicks++;
    long required = requiredTicks(distance);
    quality = clamp(focusTicks / (double) required);
    state = quality >= 1.0D ? State.COMMITTED : State.FOCUSING;
    reason = state == State.COMMITTED ? "focus-complete" : "focus-building";
    return decision(state == State.FOCUSING);
  }

  public void reset() {
    clear("reset");
  }

  /**
   * No unseen aim advantage survives a broken line of sight. A confirmed discovery does survive
   * the short combat-search window, because a player does not become unaware merely by stepping
   * behind a doorway for one tick.
   */
  public void loseVisual() {
    focusTicks = 0L;
    quality = 0.0D;
    focusedSince = Long.MIN_VALUE;
    if (state != State.DISCOVERED) clear("lost-visual");
  }

  public State state() {
    return state;
  }

  public long focusTicks() {
    return focusTicks;
  }

  public double quality() {
    return quality;
  }

  public String reason() {
    return reason;
  }

  static long requiredTicks(double distance) {
    double normalized = clamp((distance - MIN_DISTANCE) / 38.0D);
    return MIN_FOCUS_TICKS + Math.round(normalized * (MAX_FOCUS_TICKS - MIN_FOCUS_TICKS));
  }

  static double focusedAimHeight(
      UUID hunterId, UUID targetId, double baseHeight, double focusQuality) {
    double base = clampHeight(baseHeight);
    double quality = clamp(focusQuality);
    if (hunterId == null || targetId == null || quality <= 0.0D) return base;
    if (base >= 0.86D) return base;
    double sample = stableSample(hunterId, targetId);
    if (sample >= MAX_FOCUSED_HEAD_CHANCE) return base;
    double headPotential = 1.0D - sample / MAX_FOCUSED_HEAD_CHANCE;
    return base + (0.88D - base) * quality * headPotential;
  }

  private Decision discovered(String discoveredReason) {
    state = State.DISCOVERED;
    reason = discoveredReason;
    return new Decision(state, false, true, quality, reason);
  }

  private Decision clear(String clearReason) {
    targetId = null;
    state = State.NONE;
    focusedSince = Long.MIN_VALUE;
    focusTicks = 0L;
    quality = 0.0D;
    reason = clearReason;
    return new Decision(state, false, false, 0.0D, reason);
  }

  private Decision decision(boolean holdFire) {
    return new Decision(state, holdFire, false, quality, reason);
  }

  private static double stableSample(UUID hunterId, UUID targetId) {
    long mixed = hunterId.getMostSignificantBits()
        ^ Long.rotateLeft(hunterId.getLeastSignificantBits(), 17)
        ^ Long.rotateLeft(targetId.getMostSignificantBits(), 31)
        ^ targetId.getLeastSignificantBits();
    mixed ^= mixed >>> 33;
    mixed *= 0xff51afd7ed558ccdL;
    mixed ^= mixed >>> 33;
    return (mixed >>> 11) * 0x1.0p-53;
  }

  private static double clamp(double value) {
    return Math.max(0.0D, Math.min(1.0D, value));
  }

  private static double clampHeight(double value) {
    return Math.max(0.45D, Math.min(0.90D, value));
  }

  public enum State {
    NONE,
    FOCUSING,
    DISCOVERED,
    COMMITTED
  }

  public record Decision(
      State state,
      boolean holdFire,
      boolean discovered,
      double quality,
      String reason) {}
}
