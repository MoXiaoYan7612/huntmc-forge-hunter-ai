package cn.huntmc.hunterai.ai;

import java.util.Random;

/** Short, randomized evasive bursts; no fixed left-right cadence is kept between exchanges. */
public final class EvasionSystem {
  private static final double MINIMUM_URGENCY = 0.48D;
  private Action activeAction = Action.NONE;
  private long activeUntil;
  private long nextDecisionAt;
  private double activeDistance;
  private double activeSpeed;
  private String activeReason = "not-needed";

  public Step tick(long now, double urgency, boolean canUseCover, Random random) {
    return tick(now, urgency, canUseCover, false, random);
  }

  public Step tick(
      long now,
      double urgency,
      boolean canUseCover,
      boolean playerAimLaneThreat,
      Random random) {
    if (activeAction != Action.NONE && now < activeUntil) {
      return new Step(activeAction, false, activeUntil, activeDistance, activeSpeed, activeReason);
    }
    activeAction = Action.NONE;
    activeDistance = 0.0D;
    activeSpeed = 0.0D;
    activeReason = "not-needed";
    if (now < nextDecisionAt || urgency < MINIMUM_URGENCY) return Step.none();

    Action[] choices = playerAimLaneThreat
        ? Action.valuesForAimLaneBreak()
        : canUseCover ? Action.valuesForCoveredFight() : Action.valuesForOpenFight();
    activeAction = choices[random.nextInt(choices.length)];
    int minimumTicks = playerAimLaneThreat ? 7 : canUseCover ? 4 : 6;
    int additionalTicks = playerAimLaneThreat ? 4 : canUseCover ? 4 : 4;
    activeUntil = now + minimumTicks + random.nextInt(additionalTicks);
    double normalizedUrgency = Math.max(
        0.0D, Math.min(1.0D, (urgency - MINIMUM_URGENCY) / (1.0D - MINIMUM_URGENCY)));
    if (playerAimLaneThreat) {
      activeDistance = 2.20D + random.nextDouble() * 0.60D + normalizedUrgency * 0.20D;
      activeSpeed = 0.86D + normalizedUrgency * 0.06D;
    } else if (canUseCover) {
      activeDistance = 1.35D + random.nextDouble() * 0.45D + normalizedUrgency * 0.10D;
      activeSpeed = 0.77D + normalizedUrgency * 0.07D;
    } else {
      activeDistance = 1.75D + random.nextDouble() * 0.55D + normalizedUrgency * 0.15D;
      activeSpeed = 0.81D + normalizedUrgency * 0.08D;
    }
    activeReason = (playerAimLaneThreat ? "player-preaim" : canUseCover ? "covered-fight" : "open-ground")
        + ":urgency=" + String.format(java.util.Locale.ROOT, "%.2f", urgency);
    // A short pause prevents an immediate opposite-side decision from looking like a metronome.
    nextDecisionAt = activeUntil + 5L + random.nextInt(7);
    return new Step(activeAction, true, activeUntil, activeDistance, activeSpeed, activeReason);
  }

  /** Cancels an unreachable dodge so the combat controller can immediately use its fallback. */
  public void cancel(long now) {
    activeAction = Action.NONE;
    activeUntil = now;
    nextDecisionAt = now + 6L;
    activeDistance = 0.0D;
    activeSpeed = 0.0D;
    activeReason = "cancelled";
  }

  public void reset() {
    activeAction = Action.NONE;
    activeUntil = 0L;
    nextDecisionAt = 0L;
    activeDistance = 0.0D;
    activeSpeed = 0.0D;
    activeReason = "not-needed";
  }

  public enum Action {
    NONE,
    STRAFE_LEFT,
    STRAFE_RIGHT,
    BACKSTEP_LEFT,
    BACKSTEP_RIGHT,
    CROUCH_DROP,
    JUMP_LEFT,
    JUMP_RIGHT;

    private static Action[] valuesForCoveredFight() {
      return new Action[] {
        STRAFE_LEFT, STRAFE_RIGHT, BACKSTEP_LEFT, BACKSTEP_RIGHT, CROUCH_DROP, JUMP_LEFT, JUMP_RIGHT
      };
    }

    private static Action[] valuesForOpenFight() {
      return new Action[] {
        STRAFE_LEFT, STRAFE_RIGHT, STRAFE_LEFT, STRAFE_RIGHT,
        STRAFE_LEFT, STRAFE_RIGHT, JUMP_LEFT, JUMP_RIGHT
      };
    }

    private static Action[] valuesForAimLaneBreak() {
      return new Action[] {
        STRAFE_LEFT, STRAFE_RIGHT, STRAFE_LEFT, STRAFE_RIGHT,
        STRAFE_LEFT, STRAFE_RIGHT, JUMP_LEFT, JUMP_RIGHT
      };
    }

    public boolean moves() {
      return this != NONE && this != CROUCH_DROP;
    }

    public boolean jumps() {
      return this == JUMP_LEFT || this == JUMP_RIGHT;
    }

    public boolean crouches() {
      return this == CROUCH_DROP;
    }

    public boolean movesLeft() {
      return this == STRAFE_LEFT || this == BACKSTEP_LEFT || this == JUMP_LEFT;
    }

    public boolean movesBackward() {
      return this == BACKSTEP_LEFT || this == BACKSTEP_RIGHT;
    }
  }

  public record Step(
      Action action,
      boolean started,
      long activeUntil,
      double distance,
      double speed,
      String reason) {
    public static Step none() {
      return new Step(Action.NONE, false, 0L, 0.0D, 0.0D, "not-needed");
    }

    public boolean active() {
      return action != Action.NONE;
    }
  }
}
