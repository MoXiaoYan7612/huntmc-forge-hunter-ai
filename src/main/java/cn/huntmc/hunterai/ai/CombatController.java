package cn.huntmc.hunterai.ai;

import cn.huntmc.hunterai.action.ActionRequest;
import cn.huntmc.hunterai.action.ActuatorResult;
import cn.huntmc.hunterai.action.FireSafety;
import cn.huntmc.hunterai.action.MovementMode;
import cn.huntmc.hunterai.action.PostureCommand;
import cn.huntmc.hunterai.action.WeaponCommand;
import cn.huntmc.hunterai.combat.CloseRangeCombatTuning;
import cn.huntmc.hunterai.combat.HunterWeaponAdapter;
import cn.huntmc.hunterai.config.HunterAiConfig;
import cn.huntmc.hunterai.decision.HunterPlan;
import cn.huntmc.hunterai.entity.HunterEntity;
import cn.huntmc.hunterai.model.HunterInputSnapshot;
import cn.huntmc.hunterai.profile.HunterProfile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Persistent combat state that owns target attention without mutating the entity directly. */
public final class CombatController {
  private static final long LOST_TARGET_AIM_TICKS = 40L;
  private static final long COMBAT_SEARCH_MEMORY_TICKS = 120L;
  private static final long DAMAGE_BEARING_CONFIRM_TICKS = 16L;
  private static final long DIFFERENT_ATTACKER_CONFIRM_TICKS = 5L;
  private static final long STABILIZED_RETURN_FIRE_TICKS = 12L;
  private static final long FINISH_PRESSURE_TICKS = 24L;
  private static final double UNKNOWN_ENEMY_HEALTH_ESTIMATE = 0.65D;
  private final CombatAimLock aimLock = new CombatAimLock(LOST_TARGET_AIM_TICKS);
  private final FireCadencePolicy cadence = new FireCadencePolicy();
  private final EvasionSystem evasion = new EvasionSystem();
  private final SelfModel selfModel = new SelfModel();
  private final RecentBehavior recent = new RecentBehavior();
  private final SightFocusController sightFocus = new SightFocusController();
  private final HumanAimError humanAimError = new HumanAimError();

  private EngagementState state = EngagementState.IDLE;
  private UUID targetId;
  private long stateSince = Long.MIN_VALUE;
  private long targetObservedSince = Long.MIN_VALUE;
  private long targetLastSeenAt = Long.MIN_VALUE;
  private long stableSince = Long.MIN_VALUE;
  private long nextFireAt;
  private long firstTriggerEligibleAt = Long.MIN_VALUE;
  private long contactSequence;
  private long visualEpisodeSequence;
  private long visualEpisodeStartedAt = Long.MIN_VALUE;
  private long episodeFirstShotLatency = -1L;
  private long firstShotLatency = -1L;
  private long stabilizedReturnFireUntil = Long.MIN_VALUE;
  private long lastDamageRecordedAt = Long.MIN_VALUE;
  private long finishPressureUntil = Long.MIN_VALUE;
  private boolean initialRangeSelectionAttempted;
  private int shotsAtTarget;
  /** Stable body-zone selection for this target, chosen once per contact rather than per tick. */
  private double aimHeightFactor = 0.70D;
  private int lastRepositionedShotCount;
  private boolean initialReturnFireWindowUsed;
  private CombatEvaluation evaluation = CombatEvaluation.unavailable();
  private double currentAimError = Double.NaN;
  private double currentAimThreshold = Double.NaN;
  private double currentPlayerAimRisk;
  private double currentBodyExposure = 1.0D;
  private double currentAimHeight = 0.70D;
  private double currentHumanAimYaw;
  private double currentHumanAimPitch;
  private double currentCloseRangeInfluence;
  private double currentCombatDistance = Double.NaN;
  private String currentReactionContext = "none";
  private boolean readingPlayerAim;
  private boolean currentCombatPressure;
  private Vec3 activeEvasionTarget;
  private EvasionSystem.Action activeEvasionAction = EvasionSystem.Action.NONE;
  private Vec3 committedCoverTarget;
  private Vec3 lostContactMoveTarget;
  private boolean alternateLostContactSide;
  private Vec3 failedCoverTarget;
  private Vec3 concealedTransferTarget;
  private Vec3 followupFiringTarget;
  private boolean positionCompromised;
  private long coverCommittedUntil = Long.MIN_VALUE;
  private long failedCoverUntil = Long.MIN_VALUE;
  private String reason = "idle";
  private EngagementPermissionPolicy.Permission permission = EngagementPermissionPolicy.Permission.ENGAGE;
  private FireGate.Decision fireDecision =
      FireGate.Decision.blocked(FireGate.BlockReason.NO_LEGAL_VISUAL);
  private HunterWeaponAdapter.WeaponReadiness weaponReadiness =
      new HunterWeaponAdapter.WeaponReadiness(
          0L, false, HunterWeaponAdapter.ManualActionState.UNAVAILABLE);
  private String rangeAdvisory = "none";

  public Output tick(
      HunterEntity hunter,
      LivingEntity visible,
      Vec3 damageThreat,
      UUID damageAttackerId,
      long damageAt,
      float damageAmount,
      HunterInputSnapshot input,
      HunterPlan plan,
      HunterPersonality personality,
      TacticalSpace space,
      HunterWeaponAdapter.FireRate fireRate,
      HunterWeaponAdapter.WeaponReadiness currentWeaponReadiness,
      ActuatorResult previousActuator,
      long now) {
    weaponReadiness = currentWeaponReadiness == null
        ? new HunterWeaponAdapter.WeaponReadiness(
            0L, false, HunterWeaponAdapter.ManualActionState.UNAVAILABLE)
        : currentWeaponReadiness;
    observePreviousResult(hunter, fireRate, personality, previousActuator, now);
    selfModel.tick(now, space == null ? 0.5D : space.localExposure());
    if (damageAt != Long.MIN_VALUE && damageAt != lastDamageRecordedAt && damageAt <= now) {
      selfModel.recordDamage(now, damageAmount, input.self().maxHealth());
      lastDamageRecordedAt = damageAt;
    }

    boolean directDamagePressure = damageThreat != null
        && damageAt != Long.MIN_VALUE
        && now >= damageAt
        && now - damageAt <= 12L;
    if (visible == null || !visible.isAlive() || hunter.isMatchAlly(visible)) {
      return lostContact(hunter, damageThreat, damageAt, input, plan, space, now);
    }
    if (freshDamageBearing(damageThreat, damageAt, now)
        && damageAttackerId != null
        && !damageAttackerId.equals(visible.getUUID())
        && now - damageAt <= DIFFERENT_ATTACKER_CONFIRM_TICKS) {
      return confirmDamageBearing(damageThreat, now, "fresh-different-attacker-bearing");
    }
    if (directDamagePressure
        && damageAttackerId != null
        && damageAttackerId.equals(visible.getUUID())) {
      long returnFireAt = FirstTriggerGate.eligibleAt(
          damageAt,
          hunter.getEyePosition().distanceTo(visible.getEyePosition()),
          true,
          hunter.getUUID(),
          visible.getUUID(),
          contactSequence);
      firstTriggerEligibleAt = firstTriggerEligibleAt == Long.MIN_VALUE
          ? returnFireAt
          : Math.min(firstTriggerEligibleAt, returnFireAt);
    }

    boolean newTarget = !visible.getUUID().equals(targetId);
    long previousTargetLastSeenAt = targetLastSeenAt;
    boolean longCloseReacquire = !newTarget
        && previousTargetLastSeenAt != Long.MIN_VALUE
        && now >= previousTargetLastSeenAt
        && now - previousTargetLastSeenAt >= 12L;
    if (newTarget) {
      targetId = visible.getUUID();
      targetObservedSince = now;
      contactSequence++;
      visualEpisodeSequence++;
      visualEpisodeStartedAt = now;
      episodeFirstShotLatency = -1L;
      shotsAtTarget = 0;
      lastRepositionedShotCount = 0;
      nextFireAt = now;
      stabilizedReturnFireUntil = Long.MIN_VALUE;
      finishPressureUntil = Long.MIN_VALUE;
      initialRangeSelectionAttempted = false;
      initialReturnFireWindowUsed = false;
      firstShotLatency = -1L;
      positionCompromised = false;
      concealedTransferTarget = null;
      followupFiringTarget = null;
      evasion.reset();
      clearLocalCombatMovement();
      sightFocus.reset();
      aimHeightFactor = selectAimHeight(hunter.getUUID(), targetId);
      transition(EngagementState.ACQUIRE, now, "new-legal-visual");
    }
    targetLastSeenAt = now;
    lostContactMoveTarget = null;

    double distance = hunter.getEyePosition().distanceTo(visible.getEyePosition());
    currentCombatDistance = distance;
    Vec3 baseAimPoint = predictedAimPoint(visible, distance, aimHeightFactor, hunter.profile());
    double baseError = angularError(hunter, baseAimPoint);
    boolean preAimedAmbush = plan != null
        && plan.intent() == HunterIntent.AMBUSH
        && baseError <= 3.0D
        && !directDamagePressure;
    if (newTarget) {
      currentReactionContext = directDamagePressure
          ? "direct-damage"
          : preAimedAmbush ? "preaimed-ambush" : distance <= 10.0D ? "ordinary-close" : "ordinary";
      firstTriggerEligibleAt = FirstTriggerGate.eligibleAt(
          now,
          distance,
          directDamagePressure,
          preAimedAmbush,
          hunter.getUUID(),
          targetId,
          contactSequence);
      nextFireAt = now;
    } else if (longCloseReacquire && CloseRangeCombatTuning.influence(distance) > 0.0D) {
      currentReactionContext = "close-reacquire";
      visualEpisodeSequence++;
      visualEpisodeStartedAt = now;
      episodeFirstShotLatency = -1L;
      firstTriggerEligibleAt = Math.max(
          firstTriggerEligibleAt,
          FirstTriggerGate.reacquireEligibleAt(
              now, distance, hunter.getUUID(), targetId, visualEpisodeSequence));
    }
    SelfModel.Snapshot self = selfModel.snapshot(
        (float) input.self().health(), (float) input.self().maxHealth(), input.self().reloading(), now);
    PlayerPreAimModel.Assessment playerAim = visible instanceof ServerPlayer player
        ? PlayerPreAimModel.assess(
            player.getEyePosition(),
            player.getViewVector(1.0F),
            hunter.getEyePosition(),
            hunter.getDeltaMovement(),
            Math.max(12.0D, Math.min(64.0D, hunter.profile().maximumRange())))
        : PlayerPreAimModel.Assessment.none();
    long targetAge = targetObservedSince == Long.MIN_VALUE
        ? 0L
        : Math.max(0L, now - targetObservedSince);
    boolean matureConfirmedThreat = targetAge >= 8L
        && distance <= hunter.profile().maximumRange() * 1.22D;
    // Player pre-aim is movement information, not a separate permission to delay or suppress
    // firing.  Base engagement pressure must be identical for a ServerPlayer and a Hunter in the
    // same geometry; only evasion and cover scoring may consume the pre-aim assessment.
    boolean combatPressure = hasImmediateCombatPressure(
        directDamagePressure, matureConfirmedThreat, shotsAtTarget);
    currentCombatPressure = combatPressure;
    boolean establishedPlayerDuel = visible instanceof ServerPlayer
        && (combatPressure || now - stateSince >= 4L);
    readingPlayerAim = establishedPlayerDuel
        && playerAim.threatening()
        && PlayerPreAimModel.shouldReact(
            hunter.getUUID(), visible.getUUID(), now / 8L, playerAim.risk(), personality.vigilance());
    currentPlayerAimRisk = playerAim.risk();
    double minimumCover = HunterAiConfig.MIN_COMBAT_COVER.get();
    double localHardCover = localHardCover(space, hunter.position());
    currentBodyExposure = bodyExposure(hunter, visible);
    TacticalNode coverNode = selectCombatCover(
        space,
        hunter.position(),
        visible.position(),
        hunter.profile().preferredRange(),
        hunter.profile().maximumRange(),
        failedCoverTarget,
        failedCoverUntil,
        now);
    boolean coverAvailable = coverNode != null;
    boolean concealedForFocus = visible instanceof ServerPlayer
        && currentBodyExposure <= 0.66D
        && localHardCover >= minimumCover
        && (space == null || space.localExposure() < 0.50D);
    boolean movingForFocus = hunter.getDeltaMovement().multiply(1.0D, 0.0D, 1.0D).lengthSqr()
        > 0.035D * 0.035D;
    SightFocusController.Decision focus = sightFocus.tick(
        hunter.getUUID(),
        visible.getUUID(),
        distance,
        baseError,
        concealedForFocus,
        movingForFocus,
        playerAim.threatening(),
        directDamagePressure,
        now);
    currentAimHeight = weaponAdjustedAimHeight(
        hunter.profile(),
        distance,
        SightFocusController.focusedAimHeight(
            hunter.getUUID(), targetId, aimHeightFactor, focus.quality()),
        hunter.getUUID(),
        targetId);
    Vec3 rawAimPoint = predictedAimPoint(visible, distance, currentAimHeight, hunter.profile());
    currentCloseRangeInfluence = CloseRangeCombatTuning.influence(distance);
    HumanAimError.Offset humanError = humanAimError.tick(
        hunter.getUUID(),
        targetId,
        contactSequence,
        hunter.profile(),
        distance,
        movingForFocus,
        directDamagePressure,
        now);
    currentHumanAimYaw = humanError.yawDegrees();
    currentHumanAimPitch = humanError.pitchDegrees();
    Vec3 aimPoint = angularlyOffsetAimPoint(
        hunter.getEyePosition(), rawAimPoint, currentHumanAimYaw, currentHumanAimPitch);
    double error = angularError(hunter, aimPoint);
    currentAimError = error;
    aimLock.observe(aimPoint, now);
    double preAimRisk = directDamagePressure
        ? 0.85D
        : combatPressure
            ? 0.78D
            : 0.20D;
    evaluation = CombatEvaluation.assess(
        self,
        UNKNOWN_ENEMY_HEALTH_ESTIMATE,
        input.self().ammoRatio(),
        distance,
        hunter.profile().preferredRange(),
        hunter.profile().maximumRange(),
        preAimRisk,
        coverAvailable);
    HunterIntent intent = plan == null ? HunterIntent.HOLD_ANGLE : plan.intent();
    List<ActionRequest> proposals = new ArrayList<>();
    proposals.add(ActionRequest.view(
        directDamagePressure ? "damage-reaction" : "combat-controller",
        directDamagePressure ? 101 : 100,
        aimPoint,
        directDamagePressure ? "direct-attacker-lock" : "legal-target-lock"));

    rangeAdvisory = distance <= hunter.profile().maximumRange()
        ? "inside-profile"
        : "beyond-profile:" + decimal(distance / Math.max(1.0D, hunter.profile().maximumRange()));
    if (positionCompromised
        && concealedTransferTarget == null
        && followupFiringTarget != null
        && hunter.position().distanceToSqr(followupFiringTarget) <= 1.6D * 1.6D) {
      positionCompromised = false;
      lastRepositionedShotCount = shotsAtTarget;
      followupFiringTarget = null;
    }
    int shotsSinceReposition = shotsAtTarget - lastRepositionedShotCount;
    int compromiseThreshold = repositionShotThreshold(
        hunter.getUUID(), targetId, hunter.profile(), personality.patience());
    boolean finishPressure = shouldMaintainFinishPressure(
        now,
        finishPressureUntil,
        distance,
        hunter.profile().preferredRange(),
        input.self().healthRatio(),
        input.self().ammunition(),
        input.self().reloading(),
        shotsSinceReposition);
    boolean finishWindow = protectedFinishWindow(
        now,
        finishPressureUntil,
        input.self().healthRatio(),
        input.self().ammunition(),
        input.self().reloading(),
        shotsSinceReposition);
    boolean opportunityAds = shouldUseAds(
        hunter.profile(), distance, hunter.isClimbingLadder());
    double fireAimThreshold = fireAimThreshold(hunter.profile(), distance, opportunityAds);
    boolean currentLegalFireOpportunity = input.self().ammunition() > 0
        && !input.self().reloading()
        && error <= fireAimThreshold;
    CombatSituation situation = new CombatSituation(
        directDamagePressure,
        distance <= closeThreatRange(hunter.profile()),
        finishWindow,
        currentLegalFireOpportunity,
        input.self().ammunition() > 0,
        input.self().reloading(),
        input.self().healthRatio() < 0.28D,
        targetAge,
        readingPlayerAim);
    CombatDecision combatDecision = decideCombat(situation);
    boolean exposedAfterEnoughShots = shotsSinceReposition >= compromiseThreshold
        && (!finishPressure || shotsSinceReposition >= 2);
    boolean forcedByPressure = shotsSinceReposition > 0
        && (directDamagePressure || focus.discovered())
        && !finishPressure;
    if (exposedAfterEnoughShots || forcedByPressure) {
      positionCompromised = true;
      prepareCovertReposition(hunter, visible, plan, space, now);
    }
    if (positionCompromised && concealedTransferTarget != null) {
      if (hunter.position().distanceToSqr(concealedTransferTarget) <= 1.6D * 1.6D) {
        concealedTransferTarget = null;
      } else {
        proposals.add(ActionRequest.movement(
            "combat-concealment", 99, concealedTransferTarget, 1.0D, MovementMode.EVADE,
            "reach-concealment-before-second-firing-angle"));
        if (combatDecision.repositionMovementUrgent()) {
          transition(EngagementState.REPOSITION, now, "break-visual-before-angle-transfer");
        }
      }
    }
    if (positionCompromised
        && concealedTransferTarget == null
        && followupFiringTarget != null
        && hunter.position().distanceToSqr(followupFiringTarget) > 1.6D * 1.6D) {
      proposals.add(ActionRequest.movement(
          "combat-reposition", 97, followupFiringTarget, 1.0D, MovementMode.EVADE,
          "leave-predictable-firing-angle"));
      if (combatDecision.repositionMovementUrgent()) {
        transition(EngagementState.REPOSITION, now, "alternate-angle-without-hidden-connector");
      }
    }
    permission = EngagementPermissionPolicy.decide(
        intent,
        distance,
        hunter.profile().preferredRange(),
        input.self().healthRatio(),
        space == null ? 0.5D : space.localExposure(),
        coverAvailable,
        localHardCover >= minimumCover && currentBodyExposure < CombatExposurePolicy.FULL_BODY_EXPOSURE,
        combatPressure,
        combatPressure,
        shotsAtTarget,
        targetAge,
        personality,
        evaluation);
    if (permission == EngagementPermissionPolicy.Permission.BREAK_CONTACT) {
      transition(EngagementState.REPOSITION, now, "low-health-break-contact");
      if (coverNode != null && hunter.position().distanceToSqr(coverNode.position()) > 2.56D) {
        proposals.add(ActionRequest.movement(
            "combat-survival", 103, coverNode.position(), 1.05D, MovementMode.EVADE,
            "low-health-reach-hard-cover"));
      } else {
        proposals.add(ActionRequest.posture(
            "combat-survival", 103, PostureCommand.CROUCH, "low-health-recover-behind-cover"));
      }
    }
    boolean ambushFocusHold = focus.holdFire()
        && intent == HunterIntent.AMBUSH
        && concealedForFocus
        && !directDamagePressure
        && !focus.discovered()
        && distance > 10.0D;

    CombatExposurePolicy.Decision exposureDecision = CombatExposurePolicy.decide(
        currentBodyExposure, coverAvailable, combatPressure, shotsSinceReposition);
    boolean sustainedExposedExchange = permission == EngagementPermissionPolicy.Permission.REPOSITION
        || space != null
        && space.localExposure() >= 0.60D
        && shotsSinceReposition >= 2;
    boolean needsHardCover = HunterEngagementPolicy.requiresHardCoverMovement(
        localHardCover, minimumCover, readingPlayerAim || sustainedExposedExchange)
        || exposureDecision != CombatExposurePolicy.Decision.HOLD_POSITION;
    if (!needsHardCover
        || localHardCover >= minimumCover
            && currentBodyExposure < CombatExposurePolicy.FULL_BODY_EXPOSURE) {
      committedCoverTarget = null;
      coverCommittedUntil = Long.MIN_VALUE;
    } else if (committedCoverTarget == null || now > coverCommittedUntil) {
      committedCoverTarget = coverNode == null ? null : coverNode.position();
      coverCommittedUntil = committedCoverTarget == null ? Long.MIN_VALUE : now + 50L;
    }
    if (committedCoverTarget != null) {
      double coverDistance = hunter.position().distanceTo(committedCoverTarget);
      if (coverDistance > 1.6D) {
        boolean movingSuppression = exposureDecision
                == CombatExposurePolicy.Decision.RELOCATE_WITH_SUPPRESSION
            || !combatDecision.coverMovementUrgent();
        if (!movingSuppression)
          transition(EngagementState.REPOSITION, now, "relocate-from-open-ground");
        activeEvasionTarget = null;
        activeEvasionAction = EvasionSystem.Action.NONE;
        proposals.add(ActionRequest.movement(
            "combat-cover", 97, committedCoverTarget, 1.05D, MovementMode.SPRINT,
            movingSuppression
                ? "move-to-hard-cover-with-bounded-suppression"
                : "commit-to-hard-cover-before-firing"));
        // Cover is a movement preference.  It never owns the weapon channel: the stable-fire
        // checks below still decide whether this tick may fire while the Hunter relocates.
      } else {
        committedCoverTarget = null;
        coverCommittedUntil = Long.MIN_VALUE;
        lastRepositionedShotCount = shotsAtTarget;
      }
    }

    if (hunter.hasStairFooting())
      proposals.add(ActionRequest.movement(
          "combat-footing", 89, null, 0.0D, MovementMode.HOLD,
          "stabilize-on-stairs-before-fire"));

    boolean ads = shouldUseAds(hunter.profile(), distance, hunter.isClimbingLadder());
    if (combatPressure
        && !initialReturnFireWindowUsed
        && shotsAtTarget == 0
        && input.self().ammunition() > 0
        && !input.self().reloading()) {
      initialReturnFireWindowUsed = true;
      stabilizedReturnFireUntil = now + STABILIZED_RETURN_FIRE_TICKS;
    }
    boolean stabilizedReturnFire = now <= stabilizedReturnFireUntil
        && error <= (ads ? 4.5D : 7.0D);
    if (stabilizedReturnFire) {
      // Players briefly stop, finish the ADS transition, fire, and then move again. This bounded
      // request beats cover navigation for at most twelve ticks and is permanently consumed for
      // the contact, so it cannot turn into exposed stand-and-shoot behavior.
      proposals.add(ActionRequest.movement(
          "combat-stability", 99, null, 0.0D, MovementMode.HOLD,
          "brief-stabilized-return-fire-before-reposition"));
    }
    double stableError = fireAimThreshold(hunter.profile(), distance, ads);
    currentAimThreshold = stableError;
    if (error > stableError) {
      stableSince = Long.MIN_VALUE;
    } else if (stableSince == Long.MIN_VALUE) {
      stableSince = now;
    }
    long stableTicks = stableSince == Long.MIN_VALUE ? 0L : Math.max(0L, now - stableSince);
    long requiredStableTicks = ads || distance <= 10.0D ? 2L : 1L;
    fireDecision = FireGate.evaluate(
        new FireGate.Input(
            true,
            visible.isAlive() && !hunter.isMatchAlly(visible),
            FireSafety.allyBlocksShot(hunter, aimPoint),
            input.self().ammunition(),
            input.self().reloading(),
            weaponReadiness,
            ads,
            now,
            firstTriggerEligibleAt,
            nextFireAt,
            ambushFocusHold,
            error,
            stableError,
            stableTicks,
            requiredStableTicks));
    if (fireDecision.mayFire()) {
      transition(EngagementState.BURST, now, "fire-gate-open");
    } else if (fireDecision.reason() == FireGate.BlockReason.AIM_ALIGNING) {
      transition(EngagementState.TRACK, now, "fire-gate-" + fireDecision.reason());
    } else if (shotsAtTarget > 0
        && (fireDecision.reason() == FireGate.BlockReason.NATIVE_COOLDOWN
            || fireDecision.reason() == FireGate.BlockReason.NEED_BOLT
            || fireDecision.reason() == FireGate.BlockReason.IS_BOLTING
            || fireDecision.reason() == FireGate.BlockReason.CADENCE)) {
      transition(EngagementState.RECOVER, now, "fire-gate-" + fireDecision.reason());
    } else {
      transition(EngagementState.STABILIZE, now, "fire-gate-" + fireDecision.reason());
    }
    EvasionSystem.Step dodge = evasion.tick(
        now,
        evaluation.dodgeUrgency(),
        localHardCover >= minimumCover && currentBodyExposure < CombatExposurePolicy.FULL_BODY_EXPOSURE,
        readingPlayerAim,
        random(hunter.getUUID(), targetId, now / 8L));
    if (dodge.active()) addEvasion(proposals, hunter, visible, dodge);
    else {
      activeEvasionTarget = null;
      activeEvasionAction = EvasionSystem.Action.NONE;
    }

    boolean mayFire = fireDecision.mayFire();
    boolean selectWeaponThisTick = shouldSelectRangeAdvisory(
        initialRangeSelectionAttempted,
        shotsAtTarget,
        directDamagePressure,
        input.self().ammunition(),
        input.self().reloading(),
        now,
        firstTriggerEligibleAt,
        nextFireAt,
        weaponReadiness,
        mayFire);
    if (selectWeaponThisTick) initialRangeSelectionAttempted = true;
    WeaponCommand weaponCommand = selectWeaponThisTick && !mayFire
        ? WeaponCommand.SELECT_BEST_RANGE
        : mayFire
            ? ads ? WeaponCommand.ADS_FIRE : WeaponCommand.HIP_FIRE
            : ads ? WeaponCommand.ADS : WeaponCommand.HOLD;
    if (!fireDecision.resourceRecoveryOwnsWeapon()) {
      proposals.add(ActionRequest.weapon(
          "combat-controller", 100, weaponCommand,
          mayFire
              ? "fire-gate-open"
              : selectWeaponThisTick
                  ? "range-advisory-safe-window"
                  : "fire-gate-" + fireDecision.reason()));
    }
    if (state == EngagementState.REPOSITION) {
      Vec3 reposition = followupFiringTarget != null
          ? followupFiringTarget
          : plan == null || plan.opportunity() == null
              || plan.opportunity().secondFiringPoint() == null
                  ? null
                  : plan.opportunity().secondFiringPoint().position();
      if (reposition != null) proposals.add(ActionRequest.movement(
          "combat-controller", 91, reposition, 0.98D, MovementMode.EVADE,
          "move-to-second-firing-position"));
    }
    return output(proposals, aimPoint, mayFire);
  }

  public EngagementState state() {
    return state;
  }

  public UUID targetId() {
    return targetId;
  }

  /** True while combat search, rather than the strategic route, must own movement and view. */
  public boolean hasAttention(long now) {
    return targetId != null
        && targetLastSeenAt != Long.MIN_VALUE
        && now >= targetLastSeenAt
        && now - targetLastSeenAt <= COMBAT_SEARCH_MEMORY_TICKS;
  }

  public String summary(long now) {
    return "state=" + state
        + " stateSince=" + stateSince
        + " target=" + targetId
        + " shots=" + shotsAtTarget
        + " nextFireAt=" + nextFireAt
        + " visualConfirmedAt=" + targetObservedSince
        + " firstTriggerEligibleAt=" + firstTriggerEligibleAt
        + " firstShotLatency=" + firstShotLatency
        + " visualEpisode=" + visualEpisodeSequence
        + " visualEpisodeStartedAt=" + visualEpisodeStartedAt
        + " episodeFirstShotLatency=" + episodeFirstShotLatency
        + " fireDecision=" + fireDecision.status()
        + " fireBlockReason=" + fireDecision.reason()
        + " nativeCooldownTicks=" + weaponReadiness.nativeCooldownTicks()
        + " manualActionState=" + weaponReadiness.manualActionState()
        + " adsReady=" + weaponReadiness.adsReady()
        + " rangeAdvisory=" + rangeAdvisory
        + " aimThreshold="
        + (Double.isFinite(currentAimThreshold) ? decimal(currentAimThreshold) : "none")
        + " aimLockTicks=" + aimLock.remainingTicks(now)
        + " duel=" + decimal(evaluation.duelChance())
        + " escape=" + decimal(evaluation.escapeChance())
        + " dodge=" + decimal(evaluation.dodgeUrgency())
        + " playerAimRisk=" + decimal(currentPlayerAimRisk)
        + " playerAimRead=" + readingPlayerAim
        + " combatPressure=" + currentCombatPressure
        + " bodyExposure=" + decimal(currentBodyExposure)
        + " aimHeight=" + decimal(currentAimHeight)
        + " aimZone=" + aimZone(currentAimHeight)
        + " closeRangeInfluence=" + decimal(currentCloseRangeInfluence)
        + " combatDistance=" + decimal(currentCombatDistance)
        + " reactionContext=" + currentReactionContext
        + " humanAimYaw=" + decimal(currentHumanAimYaw)
        + " humanAimPitch=" + decimal(currentHumanAimPitch)
        + " focusState=" + sightFocus.state()
        + " focusTicks=" + sightFocus.focusTicks()
        + " focusQuality=" + decimal(sightFocus.quality())
        + " focusReason=" + sightFocus.reason()
        + " permission=" + permission
        + " targetAge=" + (targetObservedSince == Long.MIN_VALUE ? "none" : Math.max(0L, now - targetObservedSince))
        + " coverTarget=" + (committedCoverTarget == null ? "none" : committedCoverTarget)
        + " positionCompromised=" + positionCompromised
        + " concealmentTarget=" + (concealedTransferTarget == null ? "none" : concealedTransferTarget)
        + " followupFiringTarget=" + (followupFiringTarget == null ? "none" : followupFiringTarget)
        + " finishPressureTicks="
        + (finishPressureUntil == Long.MIN_VALUE ? 0L : Math.max(0L, finishPressureUntil - now))
        + " aimError=" + (Double.isFinite(currentAimError) ? decimal(currentAimError) : "none")
        + " reason=" + reason;
  }

  static String aimZone(double aimHeight) {
    if (aimHeight >= 0.86D) return "HEAD";
    if (aimHeight >= 0.78D) return "UPPER_BODY";
    return "BODY";
  }

  public void reset() {
    state = EngagementState.IDLE;
    targetId = null;
    targetObservedSince = Long.MIN_VALUE;
    targetLastSeenAt = Long.MIN_VALUE;
    stateSince = Long.MIN_VALUE;
    stableSince = Long.MIN_VALUE;
    nextFireAt = 0L;
    firstTriggerEligibleAt = Long.MIN_VALUE;
    contactSequence = 0L;
    visualEpisodeSequence = 0L;
    visualEpisodeStartedAt = Long.MIN_VALUE;
    episodeFirstShotLatency = -1L;
    firstShotLatency = -1L;
    stabilizedReturnFireUntil = Long.MIN_VALUE;
    finishPressureUntil = Long.MIN_VALUE;
    initialRangeSelectionAttempted = false;
    shotsAtTarget = 0;
    initialReturnFireWindowUsed = false;
    aimHeightFactor = 0.70D;
    lastRepositionedShotCount = 0;
    evaluation = CombatEvaluation.unavailable();
    currentAimError = Double.NaN;
    currentAimThreshold = Double.NaN;
    currentPlayerAimRisk = 0.0D;
    currentBodyExposure = 1.0D;
    currentAimHeight = 0.70D;
    currentHumanAimYaw = 0.0D;
    currentHumanAimPitch = 0.0D;
    currentCloseRangeInfluence = 0.0D;
    currentCombatDistance = Double.NaN;
    currentReactionContext = "none";
    readingPlayerAim = false;
    currentCombatPressure = false;
    clearLocalCombatMovement();
    alternateLostContactSide = false;
    evasion.reset();
    sightFocus.reset();
    humanAimError.reset();
    reason = "reset";
    permission = EngagementPermissionPolicy.Permission.ENGAGE;
    fireDecision = FireGate.Decision.blocked(FireGate.BlockReason.NO_LEGAL_VISUAL);
    weaponReadiness = new HunterWeaponAdapter.WeaponReadiness(
        0L, false, HunterWeaponAdapter.ManualActionState.UNAVAILABLE);
    rangeAdvisory = "none";
    aimLock.clear();
  }

  public void confirmTargetDeath(UUID victimId, long now) {
    if (victimId == null || !victimId.equals(targetId)) return;
    aimLock.clear();
    targetId = null;
    targetObservedSince = Long.MIN_VALUE;
    targetLastSeenAt = Long.MIN_VALUE;
    firstTriggerEligibleAt = Long.MIN_VALUE;
    firstShotLatency = -1L;
    visualEpisodeStartedAt = Long.MIN_VALUE;
    episodeFirstShotLatency = -1L;
    stabilizedReturnFireUntil = Long.MIN_VALUE;
    finishPressureUntil = Long.MIN_VALUE;
    initialRangeSelectionAttempted = false;
    stableSince = Long.MIN_VALUE;
    currentAimError = Double.NaN;
    currentAimThreshold = Double.NaN;
    currentPlayerAimRisk = 0.0D;
    currentBodyExposure = 1.0D;
    currentAimHeight = aimHeightFactor;
    currentHumanAimYaw = 0.0D;
    currentHumanAimPitch = 0.0D;
    currentCloseRangeInfluence = 0.0D;
    currentCombatDistance = Double.NaN;
    currentReactionContext = "none";
    readingPlayerAim = false;
    currentCombatPressure = false;
    fireDecision = FireGate.Decision.blocked(FireGate.BlockReason.TARGET_INVALID);
    initialReturnFireWindowUsed = false;
    sightFocus.reset();
    humanAimError.reset();
    clearLocalCombatMovement();
    evasion.reset();
    transition(EngagementState.IDLE, now, "confirmed-target-death");
  }

  private Output lostContact(
      HunterEntity hunter,
      Vec3 damageThreat,
      long damageAt,
      HunterInputSnapshot input,
      HunterPlan plan,
      TacticalSpace space,
      long now) {
    currentAimError = Double.NaN;
    currentAimThreshold = Double.NaN;
    currentPlayerAimRisk = 0.0D;
    currentBodyExposure = 1.0D;
    currentAimHeight = aimHeightFactor;
    readingPlayerAim = false;
    currentCombatPressure = false;
    sightFocus.loseVisual();
    List<ActionRequest> proposals = new ArrayList<>();
    if (freshDamageBearing(damageThreat, damageAt, now)) {
      return confirmDamageBearing(damageThreat, now, "fresh-damage-bearing");
    }
    Vec3 retained = aimLock.point(now);
    if (positionCompromised) {
      if (followupFiringTarget == null)
        followupFiringTarget = selectAlternateFiringPosition(
            space,
            hunter.position(),
            retained,
            plan,
            failedCoverTarget,
            failedCoverUntil,
            now);
      Vec3 covertTarget = concealedTransferTarget != null
          && hunter.position().distanceToSqr(concealedTransferTarget) > 1.6D * 1.6D
              ? concealedTransferTarget
              : followupFiringTarget;
      concealedTransferTarget = covertTarget == concealedTransferTarget
          ? concealedTransferTarget
          : null;
      if (covertTarget != null && hunter.position().distanceToSqr(covertTarget) > 1.6D * 1.6D) {
        transition(EngagementState.REPOSITION, now, "concealed-angle-transfer");
        proposals.add(ActionRequest.movement(
            "combat-concealment", 99, covertTarget, 1.0D, MovementMode.EVADE,
            concealedTransferTarget != null
                ? "finish-breaking-last-legal-angle"
                : "move-unseen-to-alternate-firing-angle"));
        if (retained != null) {
          proposals.add(ActionRequest.view(
              "combat-controller", 98, retained, "retain-threat-bearing-during-covert-transfer"));
          proposals.add(ActionRequest.weapon(
              "combat-controller", 96, WeaponCommand.ADS, "ready-after-covert-transfer"));
        }
        return output(proposals, retained, false);
      }
      positionCompromised = false;
      lastRepositionedShotCount = shotsAtTarget;
      concealedTransferTarget = null;
      followupFiringTarget = null;
    }
    if (retained != null) {
      transition(EngagementState.TRACK, now, "bounded-last-seen-confirmation");
      if (shouldMaintainFinishPressure(
          now,
          finishPressureUntil,
          hunter.position().distanceTo(retained),
          hunter.profile().preferredRange(),
          input.self().healthRatio(),
          input.self().ammunition(),
          input.self().reloading(),
          shotsAtTarget - lastRepositionedShotCount)) {
        Vec3 pressureTarget = boundedClosingTarget(
            hunter.position(), retained, Math.min(3.0D, hunter.profile().preferredRange()));
        proposals.add(ActionRequest.movement(
            "combat-finish-pressure", 96, pressureTarget, 1.03D, MovementMode.SPRINT,
            "advance-on-recent-last-legal-position"));
      } else {
        addLostContactMovement(proposals, hunter, retained, now);
      }
      proposals.add(ActionRequest.view("combat-controller", 98, retained, "last-legal-aim-point"));
      proposals.add(ActionRequest.weapon("combat-controller", 96, WeaponCommand.ADS, "do-not-fire-without-current-los"));
      return output(proposals, retained, false);
    }
    if (hasAttention(now)) {
      transition(EngagementState.SEARCH, now, "search-last-legal-contact-region");
      addLostContactMovement(
          proposals,
          hunter,
          hunter.getEyePosition().add(hunter.getViewVector(1.0F).scale(12.0D)),
          now);
      proposals.add(ActionRequest.weapon(
          "combat-controller", 94, WeaponCommand.ADS, "weapon-ready-during-contact-search"));
      return output(proposals, null, false);
    }
    transition(EngagementState.IDLE, now, "contact-expired");
    targetId = null;
    targetLastSeenAt = Long.MIN_VALUE;
    firstTriggerEligibleAt = Long.MIN_VALUE;
    fireDecision = FireGate.Decision.blocked(FireGate.BlockReason.NO_LEGAL_VISUAL);
    stabilizedReturnFireUntil = Long.MIN_VALUE;
    initialReturnFireWindowUsed = false;
    lostContactMoveTarget = null;
    return output(proposals, null, false);
  }

  private Output confirmDamageBearing(Vec3 damageThreat, long now, String transitionReason) {
    currentCombatPressure = true;
    transition(EngagementState.ACQUIRE, now, transitionReason);
    Vec3 look = damageThreat.add(0.0D, 1.3D, 0.0D);
    List<ActionRequest> proposals = new ArrayList<>();
    proposals.add(ActionRequest.view(
        "combat-controller", 101, look, "confirm-direct-attack-bearing"));
    proposals.add(ActionRequest.weapon(
        "combat-controller", 95, WeaponCommand.ADS, "prepare-counterfire"));
    return output(proposals, look, false);
  }

  static boolean freshDamageBearing(Vec3 damageThreat, long damageAt, long now) {
    return damageThreat != null
        && damageAt != Long.MIN_VALUE
        && now >= damageAt
        && now - damageAt <= DAMAGE_BEARING_CONFIRM_TICKS;
  }

  static boolean hasImmediateCombatPressure(
      boolean directDamagePressure, boolean acknowledgedPlayerThreat, int shotsAtTarget) {
    return directDamagePressure || acknowledgedPlayerThreat || shotsAtTarget > 0;
  }

  static boolean shouldMaintainFinishPressure(
      long now,
      long finishPressureUntil,
      double distance,
      double preferredRange,
      double healthRatio,
      int ammunition,
      boolean reloading,
      int shotsSinceReposition) {
    double pressureRange = Math.max(7.0D, Math.min(14.0D, preferredRange * 0.90D));
    return now <= finishPressureUntil
        && shotsSinceReposition > 0
        && Double.isFinite(distance)
        && distance <= pressureRange
        && healthRatio >= 0.42D
        && ammunition > 0
        && !reloading;
  }

  /**
   * A cadence window is a weapon opportunity, not a promise to stand in the open.  It survives
   * cover and pre-aim movement while the current legal visual remains in the engagement branch.
   */
  static boolean protectedFinishWindow(
      long now,
      long finishPressureUntil,
      double healthRatio,
      int ammunition,
      boolean reloading,
      int shotsSinceReposition) {
    return now <= finishPressureUntil
        && shotsSinceReposition > 0
        && healthRatio >= 0.28D
        && ammunition > 0
        && !reloading;
  }

  static double closeThreatRange(HunterProfile profile) {
    HunterProfile effective = profile == null ? HunterProfile.RIFLE : profile;
    return Math.max(8.0D, effective.preferredRange() * 0.65D);
  }

  static CombatDecision decideCombat(CombatSituation situation) {
    boolean hasWeaponOpportunity = situation.ammunitionAvailable() && !situation.reloading();
    boolean immediate = situation.directDamage()
        || situation.closeThreat()
        || situation.finishWindow();
    boolean immediateFireOpportunity = hasWeaponOpportunity
        && (immediate || situation.currentLegalFireOpportunity())
        && (!situation.criticalHealth() || situation.directDamage());
    return new CombatDecision(
        immediateFireOpportunity,
        !immediateFireOpportunity,
        situation.playerPreAim() ? "preaim-movement-only" : immediate ? "immediate-fire-opportunity" : "ordinary-duel");
  }

  static boolean shouldUseAds(HunterProfile profile, double distance, boolean climbing) {
    if (climbing || !Double.isFinite(distance)) return false;
    HunterProfile effectiveProfile = profile == null ? HunterProfile.RIFLE : profile;
    double minimumDistance = switch (effectiveProfile) {
      case SHOTGUN -> 7.0D;
      case REVOLVER -> 5.5D;
      case RIFLE -> 5.0D;
    };
    return distance >= minimumDistance;
  }

  static boolean shouldSelectRangeAdvisory(
      boolean alreadyAttempted,
      int shotsAtTarget,
      boolean directDamagePressure,
      int ammunition,
      boolean reloading,
      long now,
      long firstTriggerEligibleAt,
      long nextFireAt,
      HunterWeaponAdapter.WeaponReadiness readiness,
      boolean mayFire) {
    if (alreadyAttempted
        || directDamagePressure
        || ammunition <= 0
        || reloading
        || mayFire) return false;
    if (shotsAtTarget == 0) return firstTriggerEligibleAt - now >= 2L;
    return shotsAtTarget >= 3
        && (now < nextFireAt || readiness != null && readiness.nativeCooldownTicks() > 0L);
  }

  private void observePreviousResult(
      HunterEntity hunter,
      HunterWeaponAdapter.FireRate fireRate,
      HunterPersonality personality,
      ActuatorResult previous,
      long now) {
    if (previous == null) return;
    if (!previous.navigationAccepted()) {
      if (activeEvasionAction != EvasionSystem.Action.NONE) {
        evasion.cancel(now);
        activeEvasionTarget = null;
        activeEvasionAction = EvasionSystem.Action.NONE;
      }
      if (committedCoverTarget != null) {
        failedCoverTarget = committedCoverTarget;
        failedCoverUntil = now + 30L;
        committedCoverTarget = null;
        coverCommittedUntil = Long.MIN_VALUE;
      }
      if (lostContactMoveTarget != null) {
        lostContactMoveTarget = null;
        alternateLostContactSide = !alternateLostContactSide;
      }
      if (concealedTransferTarget != null) {
        failedCoverTarget = concealedTransferTarget;
        failedCoverUntil = now + 40L;
        concealedTransferTarget = null;
      } else if (followupFiringTarget != null) {
        failedCoverTarget = followupFiringTarget;
        failedCoverUntil = now + 40L;
        followupFiringTarget = null;
      }
    }
    String weaponResult = previous.weaponResult();
    if (weaponResult == null || !weaponResult.startsWith("fired")) return;
    long firedAt = Math.max(0L, now - 1L);
    if (shotsAtTarget == 0) {
      firstShotLatency = targetObservedSince == Long.MIN_VALUE
          ? -1L
          : Math.max(0L, firedAt - targetObservedSince);
    }
    if (episodeFirstShotLatency < 0L && visualEpisodeStartedAt != Long.MIN_VALUE)
      episodeFirstShotLatency = Math.max(0L, firedAt - visualEpisodeStartedAt);
    shotsAtTarget++;
    humanAimError.onShot(hunter.getUUID(), targetId, shotsAtTarget);
    stabilizedReturnFireUntil = Long.MIN_VALUE;
    finishPressureUntil = now + FINISH_PRESSURE_TICKS;
    selfModel.recordShot(now, recent.fireAnglePenalty(hunter.getYRot(), now));
    recent.recordFireAngle(hunter.getYRot(), now);
    FireCadencePolicy.Selection selected = cadence.select(
        fireRate.available() ? fireRate.roundsPerMinute() : 120,
        fireRate.available() ? fireRate.minimumIntervalTicks() : 10L,
        evaluation,
        personality,
        shotsAtTarget,
        random(hunter.getUUID(), targetId, shotsAtTarget));
    nextFireAt = firedAt + selected.intervalTicks();
    transition(EngagementState.RECOVER, now, selected.mode() + ":" + selected.reason());
  }

  private void addEvasion(
      List<ActionRequest> proposals,
      HunterEntity hunter,
      LivingEntity visible,
      EvasionSystem.Step dodge) {
    EvasionSystem.Action action = dodge.action();
    if (action.crouches()) {
      activeEvasionTarget = null;
      activeEvasionAction = action;
      proposals.add(ActionRequest.posture(
          "combat-evasion", 94, PostureCommand.CROUCH, "short-random-crouch-drop"));
      return;
    }
    if (!action.moves()) return;
    Vec3 toward = visible.position().subtract(hunter.position()).multiply(1.0D, 0.0D, 1.0D);
    if (toward.lengthSqr() < 1.0E-6D) return;
    toward = toward.normalize();
    Vec3 side = new Vec3(-toward.z, 0.0D, toward.x);
    if (!action.movesLeft()) side = side.scale(-1.0D);
    Vec3 direction = action.movesBackward() ? side.subtract(toward.scale(0.55D)).normalize() : side;
    if (dodge.started() || activeEvasionTarget == null || activeEvasionAction != action) {
      activeEvasionTarget = hunter.position().add(direction.scale(dodge.distance()));
      activeEvasionAction = action;
    }
    proposals.add(ActionRequest.movement(
        "combat-evasion", 92, activeEvasionTarget, dodge.speed(),
        action.jumps() ? MovementMode.COMBAT_DODGE_JUMP : MovementMode.EVADE,
        "random-evasion-" + action.name().toLowerCase(Locale.ROOT)
            + "-until=" + dodge.activeUntil() + "-" + dodge.reason()));
  }

  static double fireAimThreshold(HunterProfile profile, double distance, boolean ads) {
    double safeDistance = Double.isFinite(distance) ? Math.max(0.0D, distance) : 0.0D;
    if (!ads) {
      double ordinary = safeDistance >= 20.0D
          ? 2.75D
          : safeDistance >= 10.0D ? 3.50D : 4.50D;
      double close = profile == HunterProfile.SHOTGUN ? 3.25D : 3.50D;
      double influence = CloseRangeCombatTuning.influence(safeDistance);
      return ordinary + (close - ordinary) * influence;
    }
    if (safeDistance >= 48.0D) return 1.25D;
    if (safeDistance >= 24.0D) return 1.75D;
    return profile == HunterProfile.SHOTGUN ? 2.50D : 2.25D;
  }

  private static double localHardCover(TacticalSpace space, Vec3 position) {
    if (space == null || position == null) return 0.0D;
    return space.nodes().stream()
        .filter(node -> node.position().distanceToSqr(position) <= 2.25D * 2.25D)
        .min(Comparator.comparingDouble(node -> node.position().distanceToSqr(position)))
        .map(TacticalNode::hardCover)
        .orElse(0.0D);
  }

  static double bodyExposure(HunterEntity hunter, LivingEntity threat) {
    if (hunter == null || threat == null || !(hunter.level() instanceof ServerLevel level)) return 1.0D;
    Vec3 source = threat.getEyePosition();
    double[] heights = {0.35D, 0.95D, Math.max(1.20D, hunter.getBbHeight() * 0.88D)};
    int visibleSamples = 0;
    for (double height : heights) {
      HitResult result = level.clip(
          new ClipContext(
              source,
              hunter.position().add(0.0D, Math.min(height, hunter.getBbHeight() - 0.05D), 0.0D),
              ClipContext.Block.COLLIDER,
              ClipContext.Fluid.NONE,
              threat));
      if (result.getType() == HitResult.Type.MISS) visibleSamples++;
    }
    return visibleSamples / (double) heights.length;
  }

  private static TacticalNode selectCombatCover(
      TacticalSpace space,
      Vec3 position,
      Vec3 threat,
      double preferredRange,
      double maximumRange,
      Vec3 failedTarget,
      long failedUntil,
      long now) {
    if (space == null || position == null) return null;
    double minimumCover = HunterAiConfig.MIN_COMBAT_COVER.get();
    List<TacticalNode> candidates = space.nodes().stream()
        .filter(node -> node.hardCover() >= minimumCover)
        .filter(node -> node.position().distanceToSqr(position) > 2.0D * 2.0D)
        .filter(node -> node.position().distanceToSqr(position) <= 16.0D * 16.0D)
        .filter(node -> now >= failedUntil || failedTarget == null
            || node.position().distanceToSqr(failedTarget) > 2.0D * 2.0D)
        .toList();
    boolean observableCoverAvailable = candidates.stream().anyMatch(TacticalNode::canObserveThreat);
    return candidates.stream()
        .filter(node -> !observableCoverAvailable || node.canObserveThreat())
        .max(Comparator.comparingDouble(
            node -> combatCoverScore(
                node, position, threat, preferredRange, maximumRange)))
        .orElse(null);
  }

  /**
   * When no assigned weapon has a clean range advantage, prefer a short, covered route into a
   * doorway, reverse slope, or other constrained fighting space. This does not inspect unseen
   * entities; it scores only the tactical nodes already sampled on the server thread.
   */
  static TacticalNode selectRangeDisadvantagePosition(
      TacticalSpace space, Vec3 position, Vec3 threat, HunterProfile profile) {
    return selectRangeDisadvantagePosition(
        space, position, threat, profile, null, Long.MIN_VALUE, Long.MIN_VALUE);
  }

  static TacticalNode selectRangeDisadvantagePosition(
      TacticalSpace space,
      Vec3 position,
      Vec3 threat,
      HunterProfile profile,
      Vec3 failedTarget,
      long failedUntil,
      long now) {
    if (space == null || position == null || threat == null) return null;
    HunterProfile effective = profile == null ? HunterProfile.RIFLE : profile;
    List<TacticalNode> candidates = space.nodes().stream()
        .filter(node -> node.position().distanceToSqr(position) > 2.0D * 2.0D)
        .filter(node -> node.position().distanceToSqr(position) <= 18.0D * 18.0D)
        .filter(node -> node.hardCover() >= 0.40D || node.shelter() >= 0.50D)
        .filter(node -> node.escapeDirections() >= 1)
        .filter(node -> !recentlyFailed(node.position(), failedTarget, failedUntil, now))
        .toList();
    if (candidates.isEmpty()) return null;
    boolean constrainedAvailable = candidates.stream().anyMatch(
        node -> !node.openToSky() && node.standingExposure() <= 0.55D);
    return candidates.stream()
        .filter(node -> !constrainedAvailable
            || !node.openToSky() && node.standingExposure() <= 0.55D)
        .max(Comparator.comparingDouble(
            node -> rangeDisadvantagePositionScore(node, position, threat, effective)))
        .orElse(null);
  }

  static double rangeDisadvantagePositionScore(
      TacticalNode node, Vec3 position, Vec3 threat, HunterProfile profile) {
    double currentRange = position.distanceTo(threat);
    double candidateRange = node.position().distanceTo(threat);
    double desired = Math.max(3.0D, profile.preferredRange());
    double rangeFit = Math.max(
        0.0D,
        1.0D - Math.abs(candidateRange - desired) / Math.max(desired, profile.maximumRange()));
    double closing = currentRange > profile.maximumRange()
        ? Math.max(-1.0D, Math.min(1.0D, (currentRange - candidateRange) / 10.0D))
        : 0.0D;
    double travel = Math.min(1.0D, position.distanceTo(node.position()) / 18.0D);
    return node.hardCover() * 0.24D
        + node.shelter() * 0.14D
        + (node.openToSky() ? 0.0D : 0.18D)
        + (1.0D - node.standingExposure()) * 0.15D
        + node.rearSafety() * 0.08D
        + Math.min(1.0D, node.escapeDirections() / 3.0D) * 0.07D
        + node.entranceControl() * (profile == HunterProfile.SHOTGUN ? 0.16D : 0.08D)
        + rangeFit * 0.13D
        + closing * (profile == HunterProfile.SHOTGUN ? 0.16D : 0.08D)
        - node.flankRisk() * 0.08D
        - travel * 0.10D;
  }

  static Vec3 boundedClosingTarget(Vec3 position, Vec3 threat, double preferredRange) {
    if (position == null || threat == null) return position;
    Vec3 horizontal = threat.subtract(position).multiply(1.0D, 0.0D, 1.0D);
    if (horizontal.lengthSqr() < 1.0E-6D) return position;
    double currentDistance = Math.sqrt(horizontal.lengthSqr());
    double closingDistance = Math.max(
        3.0D,
        Math.min(10.0D, currentDistance - Math.max(3.0D, preferredRange)));
    return position.add(horizontal.normalize().scale(closingDistance));
  }

  /** Commits a short intercept segment instead of chasing a moving entity's exact feet. */
  static Vec3 segmentedClosingTarget(
      Vec3 position, Vec3 threat, HunterProfile profile, UUID hunterId, long now) {
    HunterProfile effective = profile == null ? HunterProfile.RIFLE : profile;
    Vec3 closing = boundedClosingTarget(position, threat, effective.preferredRange());
    if (position == null || threat == null || effective != HunterProfile.SHOTGUN) return closing;
    Vec3 forward = threat.subtract(position).multiply(1.0D, 0.0D, 1.0D);
    if (forward.lengthSqr() < 1.0E-6D) return closing;
    forward = forward.normalize();
    Vec3 side = new Vec3(-forward.z, 0.0D, forward.x);
    long identity = (hunterId == null ? 0L : hunterId.getMostSignificantBits()
        ^ hunterId.getLeastSignificantBits()) ^ now / 20L;
    if ((identity & 1L) != 0L) side = side.scale(-1.0D);
    Vec3 forwardSegment = closing.subtract(position).multiply(1.0D, 0.0D, 1.0D);
    double maximumForward = Math.sqrt(10.0D * 10.0D - 2.5D * 2.5D);
    if (forwardSegment.lengthSqr() > maximumForward * maximumForward) {
      forwardSegment = forwardSegment.normalize().scale(maximumForward);
    }
    return position.add(forwardSegment).add(side.scale(2.5D));
  }

  static int repositionShotThreshold(
      UUID hunterId, UUID targetId, HunterProfile profile, double patience) {
    HunterProfile effective = profile == null ? HunterProfile.RIFLE : profile;
    int base = switch (effective) {
      case SHOTGUN -> 1;
      case REVOLVER -> 2;
      case RIFLE -> 3;
    };
    long identity = (hunterId == null ? 0L : hunterId.getLeastSignificantBits())
        ^ (targetId == null ? 0L : Long.rotateLeft(targetId.getMostSignificantBits(), 13));
    int variation = Math.floorMod(identity, 2L) == 0L ? 0 : 1;
    int patientBonus = patience >= 0.72D && effective != HunterProfile.SHOTGUN ? 1 : 0;
    return base + variation + patientBonus;
  }

  private void prepareCovertReposition(
      HunterEntity hunter,
      LivingEntity visible,
      HunterPlan plan,
      TacticalSpace space,
      long now) {
    if (followupFiringTarget == null)
      followupFiringTarget = selectAlternateFiringPosition(
          space,
          hunter.position(),
          visible.position(),
          plan,
          failedCoverTarget,
          failedCoverUntil,
          now);
    if (concealedTransferTarget == null)
      concealedTransferTarget = selectConcealmentTransfer(
          space,
          hunter.position(),
          visible.position(),
          followupFiringTarget,
          failedCoverTarget,
          failedCoverUntil,
          now);
  }

  private static Vec3 selectConcealmentTransfer(
      TacticalSpace space,
      Vec3 position,
      Vec3 threat,
      Vec3 followup,
      Vec3 failedTarget,
      long failedUntil,
      long now) {
    if (space == null || position == null || threat == null) return null;
    return space.nodes().stream()
        .filter(node -> node.position().distanceToSqr(position) > 2.0D * 2.0D)
        .filter(node -> node.position().distanceToSqr(position) <= 14.0D * 14.0D)
        .filter(node -> !node.canObserveThreat())
        .filter(node -> node.hardCover() >= 0.40D || node.shelter() >= 0.55D)
        .filter(node -> node.standingExposure() <= 0.58D)
        .filter(node -> node.escapeDirections() >= 1)
        .filter(node -> !recentlyFailed(node.position(), failedTarget, failedUntil, now))
        .max(Comparator.comparingDouble(node -> {
          double followupDistance = followup == null
              ? 0.0D
              : Math.min(1.0D, node.position().distanceTo(followup) / 14.0D);
          double travel = Math.min(1.0D, node.position().distanceTo(position) / 14.0D);
          return node.hardCover() * 0.30D
              + node.shelter() * 0.18D
              + (1.0D - node.standingExposure()) * 0.18D
              + node.rearSafety() * 0.12D
              + Math.min(1.0D, node.escapeDirections() / 3.0D) * 0.10D
              + (node.openToSky() ? 0.0D : 0.10D)
              - node.flankRisk() * 0.08D
              - travel * 0.08D
              - followupDistance * 0.04D;
        }))
        .map(TacticalNode::position)
        .orElse(null);
  }

  private static Vec3 selectAlternateFiringPosition(
      TacticalSpace space,
      Vec3 position,
      Vec3 threat,
      HunterPlan plan,
      Vec3 failedTarget,
      long failedUntil,
      long now) {
    Vec3 planned = plan == null || plan.opportunity() == null
        || plan.opportunity().secondFiringPoint() == null
            ? null
            : plan.opportunity().secondFiringPoint().position();
    if (planned != null
        && planned.distanceToSqr(position) > 3.5D * 3.5D
        && !recentlyFailed(planned, failedTarget, failedUntil, now)) return planned;
    if (space == null || position == null || threat == null) return null;
    Vec3 currentBearing = position.subtract(threat).multiply(1.0D, 0.0D, 1.0D);
    return space.nodes().stream()
        .filter(TacticalNode::canObserveThreat)
        .filter(node -> node.position().distanceToSqr(position) > 4.0D * 4.0D)
        .filter(node -> node.position().distanceToSqr(position) <= 18.0D * 18.0D)
        .filter(node -> node.hardCover() >= 0.38D)
        .filter(node -> node.standingExposure() <= 0.65D)
        .filter(node -> !recentlyFailed(node.position(), failedTarget, failedUntil, now))
        .max(Comparator.comparingDouble(node -> {
          Vec3 nextBearing = node.position().subtract(threat).multiply(1.0D, 0.0D, 1.0D);
          double angleChange = currentBearing.lengthSqr() < 1.0E-6D
                  || nextBearing.lengthSqr() < 1.0E-6D
              ? 0.0D
              : (1.0D - Mth.clamp(
                  currentBearing.normalize().dot(nextBearing.normalize()), -1.0D, 1.0D)) * 0.5D;
          return node.hardCover() * 0.24D
              + (1.0D - node.standingExposure()) * 0.17D
              + node.rearSafety() * 0.10D
              + node.entranceControl() * 0.10D
              + angleChange * 0.27D
              + Math.min(1.0D, node.escapeDirections() / 3.0D) * 0.08D
              - node.flankRisk() * 0.10D
              - (node.openToSky() ? 0.05D : 0.0D);
        }))
        .map(TacticalNode::position)
        .orElse(null);
  }

  private static boolean recentlyFailed(
      Vec3 candidate, Vec3 failedTarget, long failedUntil, long now) {
    return candidate != null
        && failedTarget != null
        && now < failedUntil
        && candidate.distanceToSqr(failedTarget) <= 2.0D * 2.0D;
  }

  static double combatCoverScore(TacticalNode node, Vec3 position) {
    return combatCoverScore(node, position, null, 0.0D, 0.0D);
  }

  static double combatCoverScore(
      TacticalNode node,
      Vec3 position,
      Vec3 threat,
      double preferredRange,
      double maximumRange) {
    double distance = Math.min(1.0D, Math.sqrt(node.position().distanceToSqr(position)) / 12.0D);
    double rangeFit = 0.0D;
    double closingValue = 0.0D;
    if (threat != null && preferredRange > 0.0D && maximumRange > 0.0D) {
      double currentRange = position.distanceTo(threat);
      double candidateRange = node.position().distanceTo(threat);
      rangeFit = Math.max(
          0.0D,
          1.0D - Math.abs(candidateRange - preferredRange) / Math.max(preferredRange, maximumRange));
      if (currentRange > preferredRange * 1.35D) {
        closingValue = Math.max(-1.0D, Math.min(1.0D, (currentRange - candidateRange) / 8.0D));
      } else if (currentRange < preferredRange * 0.70D) {
        closingValue = Math.max(-1.0D, Math.min(1.0D, (candidateRange - currentRange) / 6.0D));
      }
    }
    return node.hardCover() * 0.31D
        + (1.0D - node.standingExposure()) * 0.18D
        + node.rearSafety() * 0.14D
        + Math.min(1.0D, node.escapeDirections() / 4.0D) * 0.12D
        + (node.canObserveThreat() ? 0.12D : 0.02D)
        + rangeFit * 0.16D
        + closingValue * 0.18D
        - node.flankRisk() * 0.10D
        - distance * 0.14D
        - (node.openToSky() ? 0.06D : 0.0D);
  }

  private void clearLocalCombatMovement() {
    activeEvasionTarget = null;
    activeEvasionAction = EvasionSystem.Action.NONE;
    committedCoverTarget = null;
    lostContactMoveTarget = null;
    failedCoverTarget = null;
    concealedTransferTarget = null;
    followupFiringTarget = null;
    positionCompromised = false;
    coverCommittedUntil = Long.MIN_VALUE;
    failedCoverUntil = Long.MIN_VALUE;
  }

  private void addLostContactMovement(
      List<ActionRequest> proposals, HunterEntity hunter, Vec3 lastLegalBearing, long now) {
    if (lostContactMoveTarget == null
        || hunter.position().distanceToSqr(lostContactMoveTarget) <= 1.0D) {
      Vec3 forward = lastLegalBearing.subtract(hunter.getEyePosition()).multiply(1.0D, 0.0D, 1.0D);
      if (forward.lengthSqr() < 1.0E-6D)
        forward = hunter.getViewVector(1.0F).multiply(1.0D, 0.0D, 1.0D);
      if (forward.lengthSqr() < 1.0E-6D) forward = new Vec3(0.0D, 0.0D, 1.0D);
      forward = forward.normalize();
      Vec3 side = new Vec3(-forward.z, 0.0D, forward.x);
      long identity = hunter.getUUID().getMostSignificantBits()
          ^ hunter.getUUID().getLeastSignificantBits()
          ^ (targetId == null ? 0L : targetId.getLeastSignificantBits());
      boolean left = (identity & 1L) == 0L;
      if (alternateLostContactSide) left = !left;
      if (!left) side = side.scale(-1.0D);
      lostContactMoveTarget = hunter.position().add(side.scale(3.0D)).add(forward.scale(0.65D));
    }
    proposals.add(ActionRequest.movement(
        "combat-search", 91, lostContactMoveTarget, 0.90D, MovementMode.WALK,
        "clear-last-legal-angle-with-bounded-side-step-at=" + now));
  }

  private void transition(EngagementState next, long now, String transitionReason) {
    if (state != next) {
      state = next;
      stateSince = now;
    }
    reason = transitionReason;
  }

  private Output output(List<ActionRequest> proposals, Vec3 aimPoint, boolean fireRequested) {
    return new Output(
        List.copyOf(proposals),
        state,
        aimPoint,
        fireRequested,
        currentAimError,
        reason,
        evaluation);
  }

  private static Vec3 predictedAimPoint(
      LivingEntity target, double distance, double heightFactor, HunterProfile profile) {
    double leadTicks = leadTicks(profile, distance);
    Vec3 velocity = target.getDeltaMovement();
    double clampedHeight = Math.max(0.45D, Math.min(0.90D, heightFactor));
    Vec3 body = new Vec3(
        target.getX(),
        target.getY() + target.getBbHeight() * clampedHeight,
        target.getZ());
    return body.add(velocity.x * leadTicks, velocity.y * leadTicks * 0.35D, velocity.z * leadTicks);
  }

  static double leadTicks(HunterProfile profile, double distance) {
    double positiveDistance = Math.max(0.0D, Double.isFinite(distance) ? distance : 0.0D);
    // TaCZ projectiles in the supported 1.1.8 build need the older three-tick velocity lead at
    // medium/long range.  Acquisition delay and physical turn limits still prevent close-range
    // zero-frame snapping; this only restores where the aim point is placed after acquisition.
    return Math.min(3.0D, positiveDistance / 18.0D);
  }

  static double selectAimHeight(UUID hunterId, UUID targetId) {
    int bucket = stableAimBucket(hunterId, targetId);
    // Keep most rounds in the torso.  Head shots remain possible but are uncommon and
    // deterministic for a contact, so players cannot exploit per-tick random snapping.
    if (bucket < 7) return 0.88D;
    if (bucket < 20) return 0.56D;
    return 0.70D;
  }

  static double weaponAdjustedAimHeight(
      HunterProfile profile,
      double distance,
      double baseHeight,
      UUID hunterId,
      UUID targetId) {
    if (CloseRangeCombatTuning.influence(distance) > 0.0D)
      return CloseRangeCombatTuning.aimHeight(distance, baseHeight);
    if (profile != HunterProfile.REVOLVER
        || !Double.isFinite(distance)
        || distance < 10.0D
        || distance > 28.0D) return baseHeight;
    int bucket = stableAimBucket(hunterId, targetId);
    // Pistols reward a deliberate medium-range sight picture. This is still contact-stable and
    // probabilistic: roughly one quarter aim at the head, another third at upper chest, and the
    // rest preserve the ordinary body aim instead of snapping upward every tick.
    if (bucket < 28) return Math.max(baseHeight, 0.88D);
    if (bucket < 60) return Math.max(baseHeight, 0.80D);
    return baseHeight;
  }

  static Vec3 angularlyOffsetAimPoint(
      Vec3 eye, Vec3 aimPoint, double yawOffsetDegrees, double pitchOffsetDegrees) {
    if (eye == null || aimPoint == null) return aimPoint;
    Vec3 offset = aimPoint.subtract(eye);
    double length = offset.length();
    if (length < 1.0E-6D) return aimPoint;
    double horizontal = Math.sqrt(offset.x * offset.x + offset.z * offset.z);
    double yaw = Math.atan2(offset.z, offset.x) * (180.0D / Math.PI) - 90.0D;
    double pitch = -Math.atan2(offset.y, horizontal) * (180.0D / Math.PI);
    double yawRadians = Math.toRadians(yaw + yawOffsetDegrees);
    double pitchRadians = Math.toRadians(pitch + pitchOffsetDegrees);
    double horizontalScale = Math.cos(pitchRadians);
    Vec3 direction = new Vec3(
        -Math.sin(yawRadians) * horizontalScale,
        -Math.sin(pitchRadians),
        Math.cos(yawRadians) * horizontalScale);
    return eye.add(direction.scale(length));
  }

  private static int stableAimBucket(UUID hunterId, UUID targetId) {
    long mixed = hunterId.getMostSignificantBits()
        ^ Long.rotateLeft(hunterId.getLeastSignificantBits(), 17)
        ^ targetId.getMostSignificantBits()
        ^ Long.rotateLeft(targetId.getLeastSignificantBits(), 31);
    return Math.floorMod((int) (mixed ^ (mixed >>> 32)), 100);
  }

  static double angularError(HunterEntity hunter, Vec3 target) {
    Vec3 offset = target.subtract(hunter.getEyePosition());
    double horizontal = Math.sqrt(offset.x * offset.x + offset.z * offset.z);
    float yaw = (float) (Mth.atan2(offset.z, offset.x) * (180.0D / Math.PI)) - 90.0F;
    float pitch = (float) -(Mth.atan2(offset.y, horizontal) * (180.0D / Math.PI));
    return Math.max(
        Math.abs(Mth.wrapDegrees(yaw - hunter.getYRot())),
        Math.abs(Mth.wrapDegrees(pitch - hunter.getXRot())));
  }

  private static Random random(UUID hunterId, UUID targetId, long salt) {
    long target = targetId == null ? 0L : targetId.getMostSignificantBits() ^ targetId.getLeastSignificantBits();
    return new Random(hunterId.getMostSignificantBits() ^ Long.rotateLeft(hunterId.getLeastSignificantBits(), 19) ^ target ^ salt);
  }

  private static String decimal(double value) {
    return String.format(Locale.ROOT, "%.2f", value);
  }

  public record Output(
      List<ActionRequest> proposals,
      EngagementState state,
      Vec3 aimPoint,
      boolean fireRequested,
      double aimError,
      String reason,
      CombatEvaluation evaluation) {}

  record CombatSituation(
      boolean directDamage,
      boolean closeThreat,
      boolean finishWindow,
      boolean currentLegalFireOpportunity,
      boolean ammunitionAvailable,
      boolean reloading,
      boolean criticalHealth,
      long targetAge,
      boolean playerPreAim) {}

  record CombatDecision(
      boolean immediateFireOpportunity,
      boolean repositionMovementUrgent,
      String reason) {
    boolean coverMovementUrgent() {
      return !immediateFireOpportunity;
    }
  }
}
