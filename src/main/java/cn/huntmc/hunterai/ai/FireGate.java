package cn.huntmc.hunterai.ai;

import cn.huntmc.hunterai.combat.HunterWeaponAdapter;

/** The single combat permission gate. Tactical systems may move the Hunter but cannot bypass it. */
final class FireGate {
  private FireGate() {}

  static Decision evaluate(Input input) {
    if (!input.legalVisual()) return Decision.blocked(BlockReason.NO_LEGAL_VISUAL);
    if (!input.hostileAlive()) return Decision.blocked(BlockReason.TARGET_INVALID);
    if (input.friendlyLineBlocked()) return Decision.blocked(BlockReason.FRIENDLY_LINE);
    if (input.ammunition() <= 0) return Decision.blocked(BlockReason.NO_AMMUNITION);
    if (input.reloading()) return Decision.blocked(BlockReason.RELOADING);
    if (input.readiness() == null
        || input.readiness().manualActionState() == HunterWeaponAdapter.ManualActionState.UNAVAILABLE)
      return Decision.blocked(BlockReason.WEAPON_UNAVAILABLE);
    if (input.readiness().manualActionState() == HunterWeaponAdapter.ManualActionState.RELOADING)
      return Decision.blocked(BlockReason.RELOADING);
    if (input.readiness().nativeCooldownTicks() > 0L)
      return Decision.blocked(BlockReason.NATIVE_COOLDOWN);
    if (input.readiness().manualActionState() == HunterWeaponAdapter.ManualActionState.NEED_BOLT)
      return Decision.blocked(BlockReason.NEED_BOLT);
    if (input.readiness().manualActionState() == HunterWeaponAdapter.ManualActionState.BOLTING)
      return Decision.blocked(BlockReason.IS_BOLTING);
    if (input.ads() && !input.readiness().adsReady())
      return Decision.blocked(BlockReason.ADS_TRANSITION);
    if (input.now() < input.firstTriggerEligibleAt())
      return Decision.blocked(BlockReason.FIRST_TRIGGER_DELAY);
    if (input.now() < input.nextCadenceAt())
      return Decision.blocked(BlockReason.CADENCE);
    if (input.ambushFocusHold()) return Decision.blocked(BlockReason.AMBUSH_FOCUS);
    if (!Double.isFinite(input.aimError()) || input.aimError() > input.aimThreshold())
      return Decision.blocked(BlockReason.AIM_ALIGNING);
    if (input.stableTicks() < input.requiredStableTicks())
      return Decision.ready(BlockReason.AIM_STABILIZING);
    return Decision.fire();
  }

  enum Status {
    FIRE,
    READY,
    BLOCKED
  }

  enum BlockReason {
    NONE,
    NO_LEGAL_VISUAL,
    TARGET_INVALID,
    FRIENDLY_LINE,
    NO_AMMUNITION,
    RELOADING,
    WEAPON_UNAVAILABLE,
    NATIVE_COOLDOWN,
    NEED_BOLT,
    IS_BOLTING,
    ADS_TRANSITION,
    FIRST_TRIGGER_DELAY,
    CADENCE,
    AMBUSH_FOCUS,
    AIM_ALIGNING,
    AIM_STABILIZING
  }

  record Input(
      boolean legalVisual,
      boolean hostileAlive,
      boolean friendlyLineBlocked,
      int ammunition,
      boolean reloading,
      HunterWeaponAdapter.WeaponReadiness readiness,
      boolean ads,
      long now,
      long firstTriggerEligibleAt,
      long nextCadenceAt,
      boolean ambushFocusHold,
      double aimError,
      double aimThreshold,
      long stableTicks,
      long requiredStableTicks) {}

  record Decision(Status status, BlockReason reason) {
    static Decision fire() {
      return new Decision(Status.FIRE, BlockReason.NONE);
    }

    static Decision ready(BlockReason reason) {
      return new Decision(Status.READY, reason);
    }

    static Decision blocked(BlockReason reason) {
      return new Decision(Status.BLOCKED, reason);
    }

    boolean mayFire() {
      return status == Status.FIRE;
    }

    boolean resourceRecoveryOwnsWeapon() {
      return reason == BlockReason.NO_AMMUNITION
          || reason == BlockReason.RELOADING
          || reason == BlockReason.WEAPON_UNAVAILABLE;
    }
  }
}
