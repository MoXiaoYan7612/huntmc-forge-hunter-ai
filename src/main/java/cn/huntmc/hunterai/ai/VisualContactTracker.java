package cn.huntmc.hunterai.ai;

import java.util.UUID;

/**
 * Keeps only visual confirmation state. It deliberately stores no entity reference or position,
 * so a missed line-of-sight check cannot become hidden target tracking.
 */
final class VisualContactTracker {
  private static final long CONFIRMED_CONTACT_MISS_GRACE_TICKS = 5L;
  private static final long PENDING_ATTENTION_MISS_GRACE_TICKS = 6L;
  // Idle perception is intentionally staggered at a three-tick cadence. A legal observation on
  // each scheduled pass is still continuous visual attention; requiring adjacent game ticks made
  // distant acquisition impossible unless combat had already started.
  private static final long MAX_LEGAL_OBSERVATION_GAP_TICKS = 4L;
  private UUID pendingTarget;
  private UUID confirmedTarget;
  private int pendingObservations;
  private int requiredObservations;
  private long lastObservationTime = Long.MIN_VALUE;
  private long lastConfirmedObservationTime = Long.MIN_VALUE;

  Contact observe(UUID target, int requiredTicks, long gameTime) {
    int required = Math.max(1, requiredTicks);
    if (target.equals(confirmedTarget)) {
      lastConfirmedObservationTime = gameTime;
      return new Contact(PerceptionSystem.ContactState.CONFIRMED, target, 0, 0);
    }

    confirmedTarget = null;
    if (!target.equals(pendingTarget) || hasObservationGap(gameTime)) {
      pendingTarget = target;
      pendingObservations = 1;
      requiredObservations = required;
    } else {
      pendingObservations++;
      // A target that becomes brighter, closer, or more exposed can be identified sooner, but a
      // worse frame cannot erase visual detail already obtained from the prior legal observation.
      requiredObservations = Math.min(requiredObservations, required);
    }
    lastObservationTime = gameTime;
    if (pendingObservations < requiredObservations) return pendingContact();

    confirmedTarget = pendingTarget;
    lastConfirmedObservationTime = gameTime;
    clearPending();
    return new Contact(PerceptionSystem.ContactState.CONFIRMED, confirmedTarget, 0, 0);
  }

  Contact missed(long gameTime, boolean rayBudgetRejected) {
    return missed(gameTime, rayBudgetRejected, false);
  }

  Contact missed(long gameTime, boolean rayBudgetRejected, boolean attentionTurnInProgress) {
    if (confirmedTarget != null
        && gameTime - lastConfirmedObservationTime <= CONFIRMED_CONTACT_MISS_GRACE_TICKS) {
      // A moving navigator can momentarily turn the head away before this tick's combat aim is
      // applied. Retain confirmation long enough to avoid restarting acquisition, but callers
      // still receive no visible entity and therefore cannot aim or fire without a fresh legal
      // line-of-sight observation.
      return new Contact(PerceptionSystem.ContactState.CONFIRMED, confirmedTarget, 0, 0);
    }
    confirmedTarget = null;
    lastConfirmedObservationTime = Long.MIN_VALUE;
    if (rayBudgetRejected && pendingTarget != null) {
      // A budget rejection means the server did not inspect the ray at all. Preserve the partial
      // observation without making any claim about the target's current position.
      lastObservationTime = gameTime;
      return pendingContact();
    }
    if (attentionTurnInProgress
        && pendingTarget != null
        && lastObservationTime != Long.MIN_VALUE
        && gameTime - lastObservationTime <= PENDING_ATTENTION_MISS_GRACE_TICKS) {
      // The previous tick proved legal LOS. Keep only the UUID/count while the actuator finishes
      // the requested head turn; no entity reference or position survives this miss.
      return pendingContact();
    }
    clearPending();
    return Contact.none();
  }

  Contact reset() {
    confirmedTarget = null;
    clearPending();
    return Contact.none();
  }

  UUID confirmedTarget() {
    return confirmedTarget;
  }

  boolean hasPendingContact() {
    return pendingTarget != null;
  }

  boolean hasActiveContact() {
    return pendingTarget != null || confirmedTarget != null;
  }

  /**
   * True while a previously confirmed target is inside its short visual-dropout grace period.
   * This is confirmation state only: it never grants callers an entity or a position to aim at.
   */
  boolean hasConfirmedContact() {
    return confirmedTarget != null;
  }

  UUID pendingTarget() {
    return pendingTarget;
  }

  private Contact pendingContact() {
    return new Contact(
        PerceptionSystem.ContactState.ACQUIRING,
        pendingTarget,
        pendingObservations,
        requiredObservations);
  }

  private void clearPending() {
    pendingTarget = null;
    pendingObservations = 0;
    requiredObservations = 0;
    lastObservationTime = Long.MIN_VALUE;
  }

  private boolean hasObservationGap(long gameTime) {
    return lastObservationTime == Long.MIN_VALUE
        || gameTime - lastObservationTime > MAX_LEGAL_OBSERVATION_GAP_TICKS;
  }

  record Contact(
      PerceptionSystem.ContactState state, UUID target, int observations, int requiredObservations) {
    static Contact none() {
      return new Contact(PerceptionSystem.ContactState.NONE, null, 0, 0);
    }
  }
}
