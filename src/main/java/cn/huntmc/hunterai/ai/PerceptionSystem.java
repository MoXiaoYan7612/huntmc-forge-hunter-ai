package cn.huntmc.hunterai.ai;

import cn.huntmc.hunterai.HuntMcHunterAi;
import cn.huntmc.hunterai.config.HunterAiConfig;
import cn.huntmc.hunterai.entity.HunterEntity;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Legal visual perception; no target position survives a missed line-of-sight check. */
public final class PerceptionSystem {
  private static final double MINIMUM_VIEW_DOT = 0.42D;
  private static final double PERIPHERAL_VIEW_DOT = -0.20D;
  private static final double FACING_SWEEP_VIEW_DOT = 0.0D;
  private static final int DISTANT_AWARENESS_CONFIRMATION_PENALTY = 1;
  private static final long FACING_SWEEP_WINDOW_TICKS = 28L;
  private static final float FACING_SWEEP_ALIGNMENT_DEGREES = 32.0F;
  private static final long DIRECT_THREAT_CONFIRMATION_TICKS = 20L;

  private static final long PENDING_ACQUISITION_VIEW_TICKS = 8L;
  private LivingEntity visibleTarget;
  private Vec3 pendingLegalObservationPoint;
  private long pendingLegalObservationExpiresAt = Long.MIN_VALUE;
  private final VisualContactTracker contacts = new VisualContactTracker();
  private PerceptionSnapshot lastSnapshot = PerceptionSnapshot.empty();
  private Float facingSweepYaw;
  private long facingSweepExpiresAt = Long.MIN_VALUE;
  private UUID directThreatId;
  private long directThreatExpiresAt = Long.MIN_VALUE;

  /**
   * Arms one wider, legal observation pass for a newly selected facing direction. The pass does
   * not run until the Hunter's actual head has reached that direction.
   */
  public void beginFacingSweep(float desiredYaw, long gameTime) {
    facingSweepYaw = Mth.wrapDegrees(desiredYaw);
    facingSweepExpiresAt = gameTime + FACING_SWEEP_WINDOW_TICKS;
  }

  /**
   * Gives the known source of a direct hit one bounded, FOV-independent LOS confirmation window.
   * The attacker UUID comes from the damage event; this stores neither an entity nor a live
   * position, and the normal server-side line-of-sight ray remains mandatory.
   */
  public void beginDirectThreatConfirmation(UUID attackerId, long gameTime) {
    if (attackerId == null) return;
    directThreatId = attackerId;
    directThreatExpiresAt = gameTime + DIRECT_THREAT_CONFIRMATION_TICKS;
  }

  public void tick(HunterEntity hunter, EnemyBelief belief) {
    visibleTarget = null;
    if (!(hunter.level() instanceof ServerLevel level)) {
      contacts.reset();
      pendingLegalObservationPoint = null;
      pendingLegalObservationExpiresAt = Long.MIN_VALUE;
      lastSnapshot = PerceptionSnapshot.empty();
      return;
    }
    if (level.getGameTime() > pendingLegalObservationExpiresAt) {
      pendingLegalObservationPoint = null;
      pendingLegalObservationExpiresAt = Long.MIN_VALUE;
    }
    double visionRange = HunterAiConfig.VISION_RANGE.get();
    double distantVisionRange = Math.max(visionRange, HunterAiConfig.DISTANT_VISION_RANGE.get());
    double visionRangeSquared = visionRange * visionRange;
    double distantVisionRangeSquared = distantVisionRange * distantVisionRange;
    List<LivingEntity> candidates =
        level.getEntitiesOfClass(
            LivingEntity.class,
            hunter.getBoundingBox().inflate(distantVisionRange),
            candidate ->
                eligible(hunter, candidate)
                    && hunter.distanceToSqr(candidate) <= distantVisionRangeSquared);
    int fovRejected = 0;
    int lineOfSightRejected = 0;
    int rayBudgetRejected = 0;
    int distantCandidates = 0;
    boolean pendingRayBudgetRejected = false;
    boolean pendingAttentionTurn = false;
    CandidateDiagnostic diagnostic = CandidateDiagnostic.none();
    ObservedTarget closest = null;
    ObservedTarget confirmed = null;
    ObservedTarget strongerAlternative = null;
    ObservedTarget directThreat = null;
    DistantCandidate distantCandidate = null;
    DistantCandidate fallbackDistantCandidate = null;
    FacingSweepCandidate facingSweepCandidate = null;
    UUID confirmedId = contacts.confirmedTarget();
    Vec3 headView = Vec3.directionFromRotation(hunter.getXRot(), hunter.getYHeadRot());
    boolean facingSweepReady = canUseFacingSweep(hunter, level.getGameTime());
    for (LivingEntity candidate : candidates) {
      double distanceSquared = hunter.distanceToSqr(candidate);
      boolean directThreatCandidate = isDirectThreatCandidate(
          candidate.getUUID(), directThreatId, level.getGameTime(), directThreatExpiresAt);
      if (distanceSquared > visionRangeSquared && !directThreatCandidate) {
        distantCandidates++;
        DistantCandidate candidateForAssist =
            new DistantCandidate(
                candidate,
                distanceSquared,
                inHorizontalFieldOfView(
                    headView, candidate.getEyePosition().subtract(hunter.getEyePosition())));
        if (shouldUseDistantCandidate(candidateForAssist, distantCandidate, confirmedId)) {
          if (distantCandidate != null
              && shouldUseDistantCandidate(
                  distantCandidate, fallbackDistantCandidate, confirmedId)) {
            fallbackDistantCandidate = distantCandidate;
          }
          distantCandidate = candidateForAssist;
        } else if (shouldUseDistantCandidate(
            candidateForAssist, fallbackDistantCandidate, confirmedId)) {
          fallbackDistantCandidate = candidateForAssist;
        }
        continue;
      }
      if (!directThreatCandidate && !canNotice(hunter, candidate)) {
        if (contacts.hasPendingContact()
            && candidate.getUUID().equals(contacts.pendingTarget())) {
          pendingAttentionTurn = true;
        }
        if (facingSweepReady
            && inHorizontalFacingSweepFieldOfView(
                headView, candidate.getEyePosition().subtract(hunter.getEyePosition()))) {
          FacingSweepCandidate candidateForSweep =
              new FacingSweepCandidate(candidate, distanceSquared);
          if (shouldUseFacingSweepCandidate(candidateForSweep, facingSweepCandidate, confirmedId)) {
            facingSweepCandidate = candidateForSweep;
          }
          continue;
        }
        fovRejected++;
        diagnostic =
            diagnostic.prefer(
                CandidateDiagnostic.of(candidate, distanceSquared, "normal-fov-rejected"));
        continue;
      }
      LineOfSightResult lineOfSight = hasLineOfSight(level, hunter, candidate);
      if (!lineOfSight.visible()) {
        lineOfSightRejected++;
        diagnostic =
            diagnostic.prefer(
                CandidateDiagnostic.of(
                    candidate,
                    distanceSquared,
                    lineOfSight.rayBudgetRejected() ? "normal-ray-budget" : "normal-los-blocked"));
        if (lineOfSight.rayBudgetRejected()) rayBudgetRejected++;
        if (lineOfSight.rayBudgetRejected()
            && contacts.hasPendingContact()
            && candidate.getUUID().equals(contacts.pendingTarget())) {
          pendingRayBudgetRejected = true;
        }
        continue;
      }
      ObservedTarget observed =
          observedTarget(
              level,
              candidate,
              distanceSquared,
              lineOfSight.partial(),
              lineOfSight.passedTransparentBlock(),
              false,
              false,
              directThreatCandidate);
      if (directThreatCandidate) directThreat = observed;
      if (candidate.getUUID().equals(confirmedId)) confirmed = observed;
      else if (strongerAlternative == null
          || preferredUnconfirmedTarget(observed, strongerAlternative)) strongerAlternative = observed;
      if (closest == null || preferredUnconfirmedTarget(observed, closest)) closest = observed;
    }

    // Keep a confirmed target by default, but release attention when a clearly more immediate
    // legal threat appears. This prevents a Hunter from staring at a distant fleeing target while
    // ignoring a nearby opponent, without introducing per-tick target oscillation.
    ObservedTarget observed = directThreat != null
        ? directThreat
        : confirmed != null && !shouldSwitchAttention(confirmed, strongerAlternative)
            ? confirmed
            : strongerAlternative != null ? strongerAlternative : closest;
    boolean shouldCheckFacingSweepCandidate =
        facingSweepCandidate != null
            && (observed == null
                || facingSweepCandidate.entity().getUUID().equals(observed.entity().getUUID()));
    if (shouldCheckFacingSweepCandidate) {
      LineOfSightResult lineOfSight = hasLineOfSight(level, hunter, facingSweepCandidate.entity());
      if (lineOfSight.visible()) {
        observed =
            observedTarget(
                level,
                facingSweepCandidate.entity(),
                facingSweepCandidate.distanceSquared(),
                lineOfSight.partial(),
                lineOfSight.passedTransparentBlock(),
                false,
                true,
                false);
      } else if (observed == null) {
        lineOfSightRejected++;
        diagnostic =
            diagnostic.prefer(
                CandidateDiagnostic.of(
                    facingSweepCandidate.entity(),
                    facingSweepCandidate.distanceSquared(),
                    lineOfSight.rayBudgetRejected()
                        ? "facing-sweep-ray-budget"
                        : "facing-sweep-los-blocked"));
        if (lineOfSight.rayBudgetRejected()) {
          rayBudgetRejected++;
          if (contacts.hasPendingContact()
              && facingSweepCandidate.entity().getUUID().equals(contacts.pendingTarget())) {
            pendingRayBudgetRejected = true;
          }
        }
      }
    }
    boolean usedDistantFallback = false;
    boolean shouldCheckDistantCandidate =
        distantCandidate != null
            && (observed == null
                || distantCandidate.entity().getUUID().equals(observed.entity().getUUID()));
    if (shouldCheckDistantCandidate) {
      LineOfSightResult lineOfSight = hasLineOfSight(level, hunter, distantCandidate.entity());
      if (lineOfSight.visible()) {
        observed =
            observedTarget(
                level,
                distantCandidate.entity(),
                distantCandidate.distanceSquared(),
                lineOfSight.partial(),
                lineOfSight.passedTransparentBlock(),
                true,
                false,
                false);
      } else if (observed == null) {
        lineOfSightRejected++;
        diagnostic =
            diagnostic.prefer(
                CandidateDiagnostic.of(
                    distantCandidate.entity(),
                    distantCandidate.distanceSquared(),
                    lineOfSight.rayBudgetRejected()
                        ? "distant-ray-budget"
                        : "distant-los-blocked"));
        if (lineOfSight.rayBudgetRejected()) {
          rayBudgetRejected++;
          if (contacts.hasPendingContact()
              && distantCandidate.entity().getUUID().equals(contacts.pendingTarget())) {
            pendingRayBudgetRejected = true;
          }
        }
      }
    }
    if (observed == null && fallbackDistantCandidate != null) {
      LineOfSightResult lineOfSight = hasLineOfSight(level, hunter, fallbackDistantCandidate.entity());
      if (lineOfSight.visible()) {
        observed =
            observedTarget(
                level,
                fallbackDistantCandidate.entity(),
                fallbackDistantCandidate.distanceSquared(),
                lineOfSight.partial(),
                lineOfSight.passedTransparentBlock(),
                true,
                false,
                false);
        usedDistantFallback = true;
      } else {
        lineOfSightRejected++;
        diagnostic =
            diagnostic.prefer(
                CandidateDiagnostic.of(
                    fallbackDistantCandidate.entity(),
                    fallbackDistantCandidate.distanceSquared(),
                    lineOfSight.rayBudgetRejected()
                        ? "distant-fallback-ray-budget"
                        : "distant-fallback-los-blocked"));
        if (lineOfSight.rayBudgetRejected()) {
          rayBudgetRejected++;
          if (contacts.hasPendingContact()
              && fallbackDistantCandidate.entity().getUUID().equals(contacts.pendingTarget())) {
            pendingRayBudgetRejected = true;
          }
        }
      }
    }
    VisualContactTracker.Contact contact =
        observed == null
            ? contacts.missed(
                level.getGameTime(), pendingRayBudgetRejected, pendingAttentionTurn)
            : contacts.observe(
                observed.entity().getUUID(), observed.requiredAcquisitionTicks(), level.getGameTime());
    if (observed != null && contact.state() == ContactState.CONFIRMED) {
      visibleTarget = observed.entity();
      belief.observe(visibleTarget.getUUID(), visibleTarget.position(), level.getGameTime());
    }
    if (observed != null && contact.state() == ContactState.ACQUIRING) {
      pendingLegalObservationPoint = observed.entity().getEyePosition();
      pendingLegalObservationExpiresAt = level.getGameTime() + PENDING_ACQUISITION_VIEW_TICKS;
    } else if (contact.state() == ContactState.CONFIRMED || contact.state() == ContactState.NONE) {
      pendingLegalObservationPoint = null;
      pendingLegalObservationExpiresAt = Long.MIN_VALUE;
    }
    if (observed != null) {
      diagnostic =
          CandidateDiagnostic.of(
              observed.entity(),
              observed.distanceSquared(),
              (observed.distantAwarenessAssist()
                      ? usedDistantFallback ? "distant-fallback-legal-sight" : "distant-legal-sight"
                      : observed.facingSweepAssist()
                          ? "facing-sweep-legal-sight"
                          : observed.directThreatAssist()
                              ? "direct-threat-legal-sight"
                              : "normal-legal-sight")
                  + (observed.passedTransparentBlock() ? "-through-transparent" : ""));
    } else if (distantCandidate != null && diagnostic.isNone()) {
      diagnostic =
          CandidateDiagnostic.of(
              distantCandidate.entity(),
              distantCandidate.distanceSquared(),
              distantCandidate.inForwardFieldOfView()
                  ? "distant-awaiting-los"
                  : "distant-off-bearing-awaiting-los");
    }
    lastSnapshot =
        new PerceptionSnapshot(
            candidates.size(),
            distantCandidates,
            fovRejected,
            lineOfSightRejected,
            rayBudgetRejected,
            observed != null && observed.distantAwarenessAssist(),
            observed != null && observed.facingSweepAssist(),
            visibleTarget == null ? null : visibleTarget.getUUID(),
            visibleTarget == null ? -1.0D : observed.distance(),
            contact.state(),
            contact.target(),
            contact.observations(),
            contact.requiredObservations(),
            diagnostic.target(),
            diagnostic.distance(),
            diagnostic.eyePosition(),
            diagnostic.result());
  }

  public LivingEntity visibleTarget() {
    return visibleTarget;
  }

  /**
   * Bounded last-legal LOS point used only to finish the physical head turn required for visual
   * identification. It is static, expires after eight ticks, and never authorizes firing or live
   * target tracking.
   */
  public Vec3 pendingLegalObservationPoint() {
    return pendingLegalObservationPoint;
  }

  public boolean hasPendingContact() {
    return contacts.hasPendingContact();
  }

  /** Keeps perception on a one-tick cadence through a brief confirmed-contact visual dropout. */
  public boolean hasActiveContact() {
    return contacts.hasActiveContact();
  }

  /**
   * Indicates a brief loss of a confirmed contact. The target remains non-visible until a new
   * legal line-of-sight observation succeeds, so this must not be used for aiming or firing.
   */
  public boolean hasConfirmedContact() {
    return contacts.hasConfirmedContact();
  }

  public PerceptionSnapshot lastSnapshot() {
    return lastSnapshot;
  }

  private boolean canUseFacingSweep(HunterEntity hunter, long gameTime) {
    return facingSweepYaw != null
        && gameTime <= facingSweepExpiresAt
        && Math.abs(Mth.wrapDegrees(hunter.getYHeadRot() - facingSweepYaw))
            <= FACING_SWEEP_ALIGNMENT_DEGREES;
  }

  static boolean isDirectThreatCandidate(
      UUID candidateId, UUID attackerId, long gameTime, long expiresAt) {
    return candidateId != null
        && candidateId.equals(attackerId)
        && gameTime <= expiresAt;
  }

  private static boolean eligible(HunterEntity hunter, LivingEntity candidate) {
    if (candidate == hunter || !candidate.isAlive()) return false;
    if (hunter.isMatchAlly(candidate)) return false;
    if (candidate instanceof HunterEntity) return true;
    if (candidate instanceof Player player) return !player.isCreative() && !player.isSpectator();
    return false;
  }

  /**
   * A nearby, unobstructed opponent must be able to pull attention even while the Hunter is
   * checking another sector. This is still a visual check: the later LOS ray is mandatory and
   * the full 360-degree exception applies only at intimate distance.
   */
  private static boolean canNotice(HunterEntity hunter, LivingEntity target) {
    Vec3 toTarget = target.getEyePosition().subtract(hunter.getEyePosition());
    Vec3 headView = Vec3.directionFromRotation(hunter.getXRot(), hunter.getYHeadRot());
    // Attention uses yaw so a target on a hill or in a ravine can be noticed once the Hunter
    // faces its horizontal direction. The deliberate scan also sweeps pitch, but perception must
    // not reject a legal high/low target during the transition between vertical scan phases. The
    // mandatory head/torso rays below still prevent through-block or through-floor detection,
    // and combat aiming supplies the precise pitch before firing.
    if (inHorizontalFieldOfView(headView, toTarget)) return true;
    double closeRange = HunterAiConfig.CLOSE_AWARENESS_RANGE.get();
    if (toTarget.lengthSqr() <= closeRange * closeRange) return true;
    return toTarget.lengthSqr() <= (closeRange * 2.0D) * (closeRange * 2.0D)
        && inHorizontalPeripheralFieldOfView(headView, toTarget);
  }

  static boolean inFieldOfView(Vec3 viewDirection, Vec3 toTarget) {
    if (toTarget.lengthSqr() < 1.0E-6D) return true;
    return viewDirection.normalize().dot(toTarget.normalize()) >= MINIMUM_VIEW_DOT;
  }

  static boolean inPeripheralFieldOfView(Vec3 viewDirection, Vec3 toTarget) {
    if (toTarget.lengthSqr() < 1.0E-6D) return true;
    return viewDirection.normalize().dot(toTarget.normalize()) >= PERIPHERAL_VIEW_DOT;
  }

  static boolean inHorizontalFieldOfView(Vec3 viewDirection, Vec3 toTarget) {
    return inFieldOfView(horizontal(viewDirection), horizontal(toTarget));
  }

  static boolean inHorizontalPeripheralFieldOfView(Vec3 viewDirection, Vec3 toTarget) {
    return inPeripheralFieldOfView(horizontal(viewDirection), horizontal(toTarget));
  }

  static boolean inHorizontalFacingSweepFieldOfView(Vec3 viewDirection, Vec3 toTarget) {
    if (toTarget.lengthSqr() < 1.0E-6D) return true;
    return horizontal(viewDirection).normalize().dot(horizontal(toTarget).normalize())
        >= FACING_SWEEP_VIEW_DOT;
  }

  private static Vec3 horizontal(Vec3 vector) {
    return new Vec3(vector.x, 0.0D, vector.z);
  }

  /**
   * Converts legal visual quality into consecutive observations required before a target is known.
   * This is deterministic: it creates explainable hesitation without random false positives.
   */
  static int requiredAcquisitionTicks(
      double distance, boolean crouching, double horizontalSpeed, int brightness, boolean partialExposure) {
    int required = distance <= 12.0D && !crouching && !partialExposure && brightness >= 8 ? 1 : 2;
    required += Math.min(3, (int) Math.ceil(Math.max(0.0D, distance - 10.0D) / 12.0D));
    if (crouching) required += 2;
    if (partialExposure) required += 2;
    if (brightness < 8) required += Math.min(3, (8 - Math.max(0, brightness) + 2) / 3);
    if (horizontalSpeed > 0.18D) required++;
    return Math.max(1, Math.min(10, required));
  }

  private static LineOfSightResult hasLineOfSight(
      ServerLevel level, HunterEntity hunter, LivingEntity target) {
    Vec3 source = hunter.getEyePosition();
    // Match the native Mob visibility endpoint for the primary ray. The former fixed 85%-height
    // point could end inside a slab, ledge, or a crouching target's collision box on steep terrain.
    Vec3 head = target.getEyePosition();
    Vec3 torso = target.position().add(0.0D, target.getBbHeight() * 0.55D, 0.0D);
    RayResult headResult = unobstructed(level, source, head, hunter);
    if (headResult.unobstructed()) {
      return new LineOfSightResult(Visibility.FULL, false, headResult.passedTransparentBlock());
    }
    RayResult torsoResult = unobstructed(level, source, torso, hunter);
    if (torsoResult.unobstructed()) {
      return new LineOfSightResult(Visibility.PARTIAL, false, torsoResult.passedTransparentBlock());
    }
    boolean rayBudgetRejected = headResult.rayBudgetRejected() || torsoResult.rayBudgetRejected();
    if (rayBudgetRejected) return new LineOfSightResult(Visibility.NONE, true, false);

    // Preserve the Hunter's field-of-view gate above, but use the same server-side sight test
    // that vanilla mobs use as a final legal visual fallback. It is still budgeted and still
    // cannot provide a target through blocks, floors, or ceilings.
    if (!tryConsumePerceptionRay()) return new LineOfSightResult(Visibility.NONE, true, false);
    if (hunter.getSensing().hasLineOfSight(target)) {
      return new LineOfSightResult(Visibility.FULL, false, false);
    }
    return new LineOfSightResult(Visibility.NONE, false, false);
  }

  private static boolean shouldUseDistantCandidate(
      DistantCandidate candidate, DistantCandidate current, UUID confirmedId) {
    if (current == null) return true;
    boolean candidateConfirmed = candidate.entity().getUUID().equals(confirmedId);
    boolean currentConfirmed = current.entity().getUUID().equals(confirmedId);
    if (candidateConfirmed != currentConfirmed) return candidateConfirmed;
    boolean candidatePlayer = candidate.entity() instanceof Player;
    boolean currentPlayer = current.entity() instanceof Player;
    if (candidatePlayer != currentPlayer) return candidatePlayer;
    if (candidate.inForwardFieldOfView() != current.inForwardFieldOfView()) {
      return candidate.inForwardFieldOfView();
    }
    return candidate.distanceSquared() < current.distanceSquared();
  }

  private static boolean shouldUseFacingSweepCandidate(
      FacingSweepCandidate candidate, FacingSweepCandidate current, UUID confirmedId) {
    if (current == null) return true;
    boolean candidateConfirmed = candidate.entity().getUUID().equals(confirmedId);
    boolean currentConfirmed = current.entity().getUUID().equals(confirmedId);
    if (candidateConfirmed != currentConfirmed) return candidateConfirmed;
    boolean candidatePlayer = candidate.entity() instanceof Player;
    boolean currentPlayer = current.entity() instanceof Player;
    if (candidatePlayer != currentPlayer) return candidatePlayer;
    return candidate.distanceSquared() < current.distanceSquared();
  }

  private static boolean preferredUnconfirmedTarget(
      ObservedTarget candidate, ObservedTarget current) {
    return targetAttentionCost(
            candidate.entity() instanceof Player,
            candidate.distance(),
            candidate.partialExposure())
        < targetAttentionCost(
            current.entity() instanceof Player,
            current.distance(),
            current.partialExposure());
  }

  static double targetAttentionCost(boolean player, double distance, boolean partialExposure) {
    // Players remain strategically valuable, but class alone cannot make a Hunter ignore an
    // immediate second threat.  Six blocks is a useful bias; it is not an unconditional lock.
    return Math.max(0.0D, distance)
        - (player ? 6.0D : 0.0D)
        + (partialExposure ? 3.0D : 0.0D);
  }

  private static boolean shouldSwitchAttention(ObservedTarget confirmed, ObservedTarget alternative) {
    if (confirmed == null || alternative == null) return false;
    double confirmedDistance = confirmed.distanceSquared();
    double alternativeDistance = alternative.distanceSquared();
    if (!Double.isFinite(confirmedDistance) || !Double.isFinite(alternativeDistance)) return false;
    // A close threat can override a sticky long-range contact; small distance changes are left to
    // the combat attention policy so the perception layer does not flap every tick.
    return alternativeDistance <= confirmedDistance * 0.45D
        || confirmedDistance - alternativeDistance >= 100.0D;
  }

  /** Releases a legally observed contact that the match director asked this Hunter not to duel. */
  public void deferVisibleTarget(UUID targetId) {
    if (targetId == null || !targetId.equals(contacts.confirmedTarget())) return;
    contacts.reset();
    visibleTarget = null;
    pendingLegalObservationPoint = null;
    pendingLegalObservationExpiresAt = Long.MIN_VALUE;
  }

  /** Drops a matching entity reference after death, removal, or match-seat revocation. */
  public void invalidateTarget(UUID targetId) {
    if (targetId == null) return;
    boolean matched = visibleTarget != null && targetId.equals(visibleTarget.getUUID());
    if (matched) visibleTarget = null;
    if (targetId.equals(contacts.confirmedTarget())) {
      contacts.reset();
      matched = true;
    }
    if (targetId.equals(directThreatId)) {
      directThreatId = null;
      directThreatExpiresAt = Long.MIN_VALUE;
      matched = true;
    }
    if (matched) {
      pendingLegalObservationPoint = null;
      pendingLegalObservationExpiresAt = Long.MIN_VALUE;
    }
  }

  private static ObservedTarget observedTarget(
      ServerLevel level,
      LivingEntity candidate,
      double distanceSquared,
      boolean partialExposure,
      boolean passedTransparentBlock,
      boolean distantAwarenessAssist,
      boolean facingSweepAssist,
      boolean directThreatAssist) {
    Vec3 movement = candidate.getDeltaMovement();
    double horizontalSpeed = Math.sqrt(movement.x * movement.x + movement.z * movement.z);
    int brightness = level.getMaxLocalRawBrightness(BlockPos.containing(candidate.getEyePosition()));
    return new ObservedTarget(
        candidate,
        distanceSquared,
        Math.sqrt(distanceSquared),
        partialExposure,
        passedTransparentBlock,
        horizontalSpeed,
        brightness,
        distantAwarenessAssist,
        facingSweepAssist,
        directThreatAssist);
  }

  private static RayResult unobstructed(
      ServerLevel level, Vec3 source, Vec3 target, HunterEntity hunter) {
    if (!tryConsumePerceptionRay()) {
      return new RayResult(false, true, false);
    }
    VisionClipContext context = new VisionClipContext(source, target, hunter);
    boolean unobstructed =
        level.clip(context).getType() == HitResult.Type.MISS;
    return new RayResult(unobstructed, false, context.passedTransparentBlock());
  }

  /**
   * A visual ray intentionally differs from a movement or bullet collision ray. Iron bars,
   * panes, glass, and other properly non-occluding decorative blocks may be visible through;
   * treating their collision shape as a solid wall made Hunters fail a sight check that a player
   * could plainly make. Full/occluding blocks remain mandatory blockers, preserving P1's rule
   * that a Hunter cannot acquire a target through a wall.
   */
  static boolean blocksVision(BlockState state) {
    // These blocks declare no full-cube occlusion shape, but their own panel/gate shape remains
    // a real visual obstruction whenever the ray actually meets it.
    boolean doorLike =
        state.getBlock() instanceof DoorBlock
            || state.getBlock() instanceof TrapDoorBlock
            || state.getBlock() instanceof FenceGateBlock;
    return blocksVision(state.getBlock() instanceof LeavesBlock, doorLike, state.canOcclude());
  }

  static boolean blocksVision(boolean leaves, boolean doorLike, boolean canOcclude) {
    return leaves || doorLike || canOcclude;
  }

  /** CollisionContext supplies the normal precise shape for true visual blockers only. */
  private static final class VisionClipContext extends ClipContext {
    private boolean passedTransparentBlock;

    private VisionClipContext(Vec3 source, Vec3 target, HunterEntity hunter) {
      super(source, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, hunter);
    }

    @Override
    public VoxelShape getBlockShape(BlockState state, BlockGetter level, BlockPos position) {
      if (blocksVision(state)) return super.getBlockShape(state, level, position);
      if (!state.isAir() && state.getFluidState().isEmpty()) passedTransparentBlock = true;
      return Shapes.empty();
    }

    private boolean passedTransparentBlock() {
      return passedTransparentBlock;
    }
  }

  private static boolean tryConsumePerceptionRay() {
    return HuntMcHunterAi.instance() == null
        || HuntMcHunterAi.instance().hunterRegistry().tryConsumePerceptionRay();
  }

  public record PerceptionSnapshot(
      int candidates,
      int distantCandidates,
      int fovRejected,
      int lineOfSightRejected,
      int rayBudgetRejected,
      boolean distantAwarenessAssist,
      boolean facingSweepAssist,
      java.util.UUID visibleTarget,
      double visibleDistance,
      ContactState contactState,
      java.util.UUID contactTarget,
      int contactObservations,
      int contactRequiredObservations,
      java.util.UUID diagnosticTarget,
      double diagnosticDistance,
      Vec3 diagnosticEyePosition,
      String diagnosticResult) {
    private static PerceptionSnapshot empty() {
      return new PerceptionSnapshot(
          0,
          0,
          0,
          0,
          0,
          false,
          false,
          null,
          -1.0D,
          ContactState.NONE,
          null,
          0,
          0,
          null,
          -1.0D,
          null,
          "none");
    }
  }

  public enum ContactState {
    NONE,
    ACQUIRING,
    CONFIRMED
  }

  private record ObservedTarget(
      LivingEntity entity,
      double distanceSquared,
      double distance,
      boolean partialExposure,
      boolean passedTransparentBlock,
      double horizontalSpeed,
      int brightness,
      boolean distantAwarenessAssist,
      boolean facingSweepAssist,
      boolean directThreatAssist) {
    private int requiredAcquisitionTicks() {
      if (directThreatAssist) return 1;
      int required =
          PerceptionSystem.requiredAcquisitionTicks(
              distance, entity.isCrouching(), horizontalSpeed, brightness, partialExposure);
      return distantAwarenessAssist || facingSweepAssist
          ? Math.min(10, required + DISTANT_AWARENESS_CONFIRMATION_PENALTY)
          : required;
    }
  }

  private record DistantCandidate(
      LivingEntity entity, double distanceSquared, boolean inForwardFieldOfView) {}

  /** One nearest legal-perception candidate, retained solely for monitor diagnostics. */
  private record CandidateDiagnostic(UUID target, double distance, Vec3 eyePosition, String result) {
    private static CandidateDiagnostic none() {
      return new CandidateDiagnostic(null, -1.0D, null, "none");
    }

    private static CandidateDiagnostic of(
        LivingEntity candidate, double distanceSquared, String result) {
      boolean legallyVisible = result != null && result.contains("legal-sight");
      return new CandidateDiagnostic(
          candidate.getUUID(),
          Math.sqrt(distanceSquared),
          legallyVisible ? candidate.getEyePosition() : null,
          result);
    }

    private CandidateDiagnostic prefer(CandidateDiagnostic alternative) {
      if (isNone() || alternative.distance() < distance()) return alternative;
      return this;
    }

    private boolean isNone() {
      return target == null;
    }
  }

  private record FacingSweepCandidate(LivingEntity entity, double distanceSquared) {}

  private record LineOfSightResult(
      Visibility visibility, boolean rayBudgetRejected, boolean passedTransparentBlock) {
    private boolean visible() {
      return visibility != Visibility.NONE;
    }

    private boolean partial() {
      return visibility == Visibility.PARTIAL;
    }
  }

  private enum Visibility {
    NONE,
    PARTIAL,
    FULL
  }

  private record RayResult(
      boolean unobstructed, boolean rayBudgetRejected, boolean passedTransparentBlock) {}
}
