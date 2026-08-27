package cn.huntmc.hunterai.ai;

import cn.huntmc.hunterai.HuntMcHunterAi;
import cn.huntmc.hunterai.config.HunterAiConfig;
import cn.huntmc.hunterai.entity.HunterEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Bounded local terrain sampling backed by a short-lived per-chunk terrain cache. */
public final class TacticalMap {
  private static final int MAX_NODES = 128;
  private static final int VERTICAL_SEARCH_ABOVE = 4;
  private static final int VERTICAL_SEARCH_BELOW = 8;
  private static final double AVOIDED_NODE_DISTANCE_SQUARED = 9.0D;
  private static final TacticalTerrainCache TERRAIN_CACHE = new TacticalTerrainCache();
  private List<TacticalNode> nodes = List.of();
  private final double[] sectorAccessibility = new double[4];
  private final double[] sectorUnknownExposure = new double[4];

  public void refresh(HunterEntity hunter, Vec3 threat) {
    if (!(hunter.level() instanceof ServerLevel level)) return;
    List<TacticalNode> sampled = new ArrayList<>();
    addColumn(level, hunter, threat, hunter.getX(), hunter.getZ(), sampled);
    int[] radii =
        HuntMcHunterAi.instance() == null
            ? new int[] {2, 5, 9, 13, 16}
            : HuntMcHunterAi.instance().hunterRegistry().tacticalSamplingRadii();
    for (int radius : radii) {
      for (int index = 0; index < 8 && sampled.size() < MAX_NODES; index++) {
        double angle = Math.PI * 2.0D * index / 8.0D;
        addColumn(
            level,
            hunter,
            threat,
            hunter.getX() + Math.cos(angle) * radius,
            hunter.getZ() + Math.sin(angle) * radius,
            sampled);
      }
    }
    nodes = sampled.stream().sorted(Comparator.comparingDouble(TacticalNode::score).reversed()).toList();
    refreshSectorRisk(level, hunter);
  }

  public static void invalidateTerrain(ServerLevel level, BlockPos changedPosition) {
    if (level != null && changedPosition != null) TERRAIN_CACHE.invalidateChunk(level, changedPosition);
  }

  public static void invalidateTerrain(ServerLevel level) {
    if (level != null) TERRAIN_CACHE.invalidateLevel(level);
  }

  public static void clearTerrainCache() {
    TERRAIN_CACHE.clear();
  }

  public TacticalNode bestCover(HunterEntity hunter, List<Vec3> avoidedPositions) {
    TacticalNode hardCover =
        firstCandidate(
            nodes.stream()
                .filter(node -> node.escapeDirections() > 0)
                .filter(TacticalMap::hasCombatCover)
                .filter(node -> !isAvoided(node, avoidedPositions)));
    if (hardCover != null) return hardCover;
    TacticalNode concealed =
        firstCandidate(
            nodes.stream()
                .filter(node -> node.escapeDirections() > 0)
                .filter(TacticalNode::isConcealed)
                .filter(node -> !isAvoided(node, avoidedPositions)));
    if (concealed != null) return concealed;
    return firstCandidate(
        nodes.stream()
            .filter(node -> node.escapeDirections() > 0)
            .filter(TacticalMap::acceptsExposure)
            .filter(node -> !isAvoided(node, avoidedPositions)));
  }

  /** Chooses cover that is also blocked from the known threat, for listening and disengaging. */
  public TacticalNode bestConcealedFrom(
      HunterEntity hunter, Vec3 threat, List<Vec3> avoidedPositions) {
    if (threat == null) return bestCover(hunter, avoidedPositions);
    TacticalNode hidden =
        firstThreatCovered(
            hunter,
            threat,
            nodes.stream()
                .filter(TacticalNode::isConcealed)
                .filter(TacticalMap::hasCombatCover)
                .filter(node -> node.escapeDirections() > 0)
                .filter(node -> !isAvoided(node, avoidedPositions)));
    return hidden != null ? hidden : bestCover(hunter, avoidedPositions);
  }

  public TacticalNode bestEngagement(
      HunterEntity hunter, double preferredRange, Vec3 threat, List<Vec3> avoidedPositions) {
    if (threat == null) return null;
    TacticalNode engagement =
        firstCandidate(
            nodes.stream()
                .filter(TacticalNode::canObserveThreat)
                .filter(TacticalMap::hasCombatCover)
                .filter(node -> Math.abs(node.position().distanceTo(threat) - preferredRange) < preferredRange)
                .filter(TacticalMap::acceptsExposure)
                .filter(node -> !isAvoided(node, avoidedPositions))
                .sorted(
                    Comparator.comparingDouble(
                            (TacticalNode node) ->
                                Math.abs(node.position().distanceTo(threat) - preferredRange))
                        .thenComparing(Comparator.comparingDouble(TacticalNode::score).reversed())));
    return engagement != null ? engagement : bestCover(hunter, avoidedPositions);
  }

  /** Chooses a covered observation perch, favouring an elevation gain for vigilant personalities. */
  public TacticalNode bestOverwatch(
      HunterEntity hunter, double highGroundPreference, List<Vec3> avoidedPositions) {
    double preference = Math.max(0.0D, Math.min(1.0D, highGroundPreference));
    TacticalNode sheltered =
        firstCandidate(
            overwatchCandidates(hunter, avoidedPositions)
                .filter(node -> !node.openToSky())
                .sorted(overwatchComparator(hunter, preference)));
    if (sheltered != null) return sheltered;
    return firstCandidate(
        overwatchCandidates(hunter, avoidedPositions).sorted(overwatchComparator(hunter, preference)));
  }

  public TacticalNode bestFlank(HunterEntity hunter, Vec3 threat, List<Vec3> avoidedPositions) {
    if (threat == null) return bestCover(hunter, avoidedPositions);
    TacticalNode flank =
        firstCandidate(
            nodes.stream()
                .filter(node -> !node.canObserveThreat())
                .filter(TacticalNode::isConcealed)
                .filter(node -> !isAvoided(node, avoidedPositions)));
    return flank != null ? flank : bestCover(hunter, avoidedPositions);
  }

  /**
   * Selects a tactical role from a small local layout, not from an isolated score. The primary
   * node remains the only node whose navigation path is accepted up front; companion nodes are
   * retained as spatially related firing and retreat alternatives and are revalidated before use.
   */
  public TacticalLayout bestLayout(
      HunterEntity hunter,
      HunterIntent intent,
      Vec3 threat,
      double preferredRange,
      List<Vec3> avoidedPositions,
      RecentBehavior history,
      long now) {
    List<TacticalNode> candidates =
        baseCandidates(intent, threat, preferredRange, avoidedPositions).limit(24).toList();
    if (candidates.isEmpty()) {
      // Sparse outdoor terrain does not always contain an ideal hidden flank or indoor overwatch
      // node. Keep the layout model active with the safest local fallback instead of converting
      // an unavailable specialty role into an indefinitely stalled plan.
      candidates =
          nodes.stream()
              .filter(node -> node.escapeDirections() > 0)
              .filter(node -> !isAvoided(node, avoidedPositions))
              // This is the true last resort. Rejecting exposed nodes here when the normal
              // preference is enabled leaves a stranded Hunter with no candidate at all, even
              // when a nearby, navigable cell would let it leave the bad spawn position.
              .sorted(Comparator.comparingDouble(TacticalNode::score).reversed())
              .limit(24)
              .toList();
    }
    return candidates.stream()
        .map(anchor -> layoutFor(intent, anchor, threat, avoidedPositions, history, now))
        .max(Comparator.comparingDouble(TacticalLayout::score))
        .orElse(null);
  }

  /**
   * Selects a bounty staging position that is actually defensible from several approaches. A roof
   * or a single adjacent wall is insufficient: the waiting point needs hard cover, reduced
   * crouched exposure, rear safety, and more than one escape direction.
   */
  public TacticalLayout bestBountyDefenseLayout(
      HunterEntity hunter,
      HunterIntent intent,
      Vec3 bounty,
      List<Vec3> avoidedPositions,
      RecentBehavior history,
      long now) {
    if (bounty == null) return null;
    List<TacticalNode> preferred =
        nodes.stream()
            .filter(node -> isDefensibleBountyNode(node, bounty))
            .filter(node -> !isAvoided(node, avoidedPositions))
            .toList();
    TacticalLayout strict =
        bestBountyLayout(preferred, intent, bounty, avoidedPositions, history, now, true, true);
    if (strict != null) return strict;
    boolean requireOuterRing = true;
    List<TacticalNode> fallback =
        nodes.stream()
            .filter(node -> node.escapeDirections() >= 2)
            .filter(node -> node.position().distanceTo(bounty) >= 8.0D)
            .filter(node -> node.position().distanceTo(bounty) <= 30.0D)
            .filter(node -> node.isConcealed() || hasCombatCover(node))
            .filter(TacticalMap::acceptsExposure)
            .filter(node -> !isAvoided(node, avoidedPositions))
            .toList();
    if (fallback.isEmpty()) {
      requireOuterRing = false;
      boolean leaveCurrentExposure = localExposure(hunter) >= 0.55D;
      fallback =
          nodes.stream()
              .filter(node -> node.escapeDirections() >= 2)
              .filter(node -> node.isConcealed() || hasCombatCover(node))
              .filter(TacticalMap::acceptsExposure)
              .filter(node -> !isAvoided(node, avoidedPositions))
              .filter(
                  node ->
                      !leaveCurrentExposure
                          || node.position().distanceToSqr(hunter.position()) > 9.0D)
              .toList();
    }
    return bestBountyLayout(
        fallback, intent, bounty, avoidedPositions, history, now, false, requireOuterRing);
  }

  static boolean isDefensibleBountyNode(TacticalNode node, Vec3 bounty) {
    if (node == null || bounty == null) return false;
    double distance = node.position().distanceTo(bounty);
    return distance >= 10.0D
        && distance <= 28.0D
        && node.isConcealed()
        && node.hardCover() >= 0.35D
        && node.crouchedExposure() <= 0.55D
        && node.escapeDirections() >= 2
        && node.rearSafety() >= 0.45D
        && node.flankRisk() <= 0.65D
        && node.unknownRisk() <= 0.70D;
  }

  private TacticalLayout bestBountyLayout(
      List<TacticalNode> anchors,
      HunterIntent intent,
      Vec3 bounty,
      List<Vec3> avoidedPositions,
      RecentBehavior history,
      long now,
      boolean requireDefensiblePrimary,
      boolean requireOuterRing) {
    return anchors.stream()
        .map(anchor -> layoutFor(intent, anchor, bounty, avoidedPositions, history, now))
        .filter(
            layout ->
                requireDefensiblePrimary
                    ? isDefensibleBountyNode(layout.primary(), bounty)
                    : isAcceptableBountyFallback(
                        layout.primary(), bounty, requireOuterRing))
        .max(
            Comparator.comparingDouble(
                layout -> layout.score() + bountyDefenseScore(layout.primary(), bounty)))
        .orElse(null);
  }

  static boolean isAcceptableBountyFallback(
      TacticalNode node, Vec3 bounty, boolean requireOuterRing) {
    if (node == null || bounty == null) return false;
    double distance = node.position().distanceTo(bounty);
    return (!requireOuterRing || distance >= 8.0D && distance <= 30.0D)
        && node.escapeDirections() >= 2
        && (node.isConcealed() || node.hardCover() >= 0.25D)
        && !node.isExposed();
  }

  static double bountyDefenseScore(TacticalNode node, Vec3 bounty) {
    if (node == null || bounty == null) return Double.NEGATIVE_INFINITY;
    double ringFit = 1.0D - Math.min(1.0D, Math.abs(node.position().distanceTo(bounty) - 18.0D) / 10.0D);
    return node.hardCover() * 0.30D
        + (1.0D - node.crouchedExposure()) * 0.18D
        + node.rearSafety() * 0.16D
        + (1.0D - node.flankRisk()) * 0.12D
        + node.entranceControl() * 0.10D
        + node.escapeDirections() / 4.0D * 0.08D
        + ringFit * 0.06D;
  }

  /** Chooses a concealed, navigable node that approaches evidence without running into open ground. */
  public TacticalNode bestSearch(HunterEntity hunter, Vec3 threat, List<Vec3> avoidedPositions) {
    if (threat == null) return bestCover(hunter, avoidedPositions);
    TacticalNode search =
        firstCandidate(
            nodes.stream()
                .filter(TacticalMap::acceptsExposure)
                .filter(node -> !isAvoided(node, avoidedPositions))
                .sorted(
                    Comparator.comparingDouble(
                            (TacticalNode node) ->
                                node.position().distanceToSqr(threat) - node.score() * 18.0D)
                        .thenComparing(Comparator.comparingDouble(TacticalNode::score).reversed())));
    return search != null ? search : bestCover(hunter, avoidedPositions);
  }

  /**
   * Returns local, navigable approach cells ordered toward a remembered enemy area. This is used
   * only for the short post-fire pursuit window; it does not read a live target position.
   */
  public List<TacticalNode> pursuitApproaches(Vec3 threat, List<Vec3> avoidedPositions) {
    if (threat == null) return List.of();
    return nodes.stream()
        .filter(node -> node.escapeDirections() > 0)
        .filter(TacticalMap::acceptsExposure)
        .filter(node -> !isAvoided(node, avoidedPositions))
        .sorted(
            Comparator.comparingDouble(
                    (TacticalNode node) -> node.position().distanceToSqr(threat))
                .thenComparing(Comparator.comparingDouble(TacticalNode::score).reversed()))
        .limit(12)
        .toList();
  }

  /**
   * Returns safe local cells that make real progress toward a currently visible target without
   * requiring the Hunter to path directly to the target's exact, potentially elevated position.
   * The visual-contact caller is responsible for proving the target is visible; this method only
   * ranks sampled standing cells and never expands the Hunter's information model.
   */
  public List<TacticalNode> visiblePursuitApproaches(
      HunterEntity hunter,
      Vec3 target,
      double preferredStandoff,
      List<Vec3> avoidedPositions) {
    if (hunter == null || target == null) return List.of();
    double currentDistance = hunter.position().distanceTo(target);
    if (!Double.isFinite(currentDistance) || currentDistance <= 0.0D) return List.of();
    double desired = Math.max(2.0D, preferredStandoff);
    return nodes.stream()
        .filter(node -> node.escapeDirections() > 0)
        .filter(TacticalMap::acceptsExposure)
        .filter(node -> !isAvoided(node, avoidedPositions))
        // A side-step is useful for evasion, but not for closing a confirmed range gap.
        .filter(node -> node.position().distanceTo(target) + 0.75D < currentDistance)
        .sorted(
            Comparator.comparingDouble(
                    (TacticalNode node) ->
                        visiblePursuitScore(
                            currentDistance, node.position().distanceTo(target), desired, node.score()))
                .reversed())
        .limit(12)
        .toList();
  }

  private static double visiblePursuitScore(
      double currentDistance, double candidateDistance, double preferredStandoff, double nodeScore) {
    double progress = Math.max(0.0D, currentDistance - candidateDistance);
    // Do not run past a usable firing range merely because the direct shortest cell is exposed.
    double tooClose = Math.max(0.0D, preferredStandoff - candidateDistance);
    return progress * 1.20D + nodeScore * 2.0D - tooClose * 0.85D;
  }

  public int nodeCount() {
    return nodes.size();
  }

  public List<TacticalNode> nodes() {
    return nodes;
  }

  public double sectorAccessibility(int sector) {
    return sectorAccessibility[Math.floorMod(sector, sectorAccessibility.length)];
  }

  public double sectorUnknownExposure(int sector) {
    return sectorUnknownExposure[Math.floorMod(sector, sectorUnknownExposure.length)];
  }

  /**
   * Estimates the Hunter's actual local exposure. This must use the entity position, rather than
   * the first sampled node: sampled-node ordering is score based and is unrelated to where the
   * Hunter is standing.
   */
  public double localExposure(HunterEntity hunter) {
    TacticalNode current =
        nodes.stream()
            .min(Comparator.comparingDouble(node -> node.position().distanceToSqr(hunter.position())))
            .orElse(null);
    if (current == null) return 0.50D;
    return Math.max(0.0D, Math.min(1.0D, (1.0D - current.cover()) * (current.openToSky() ? 1.0D : 0.20D)));
  }

  /** Returns the highest-scored local cover candidate for diagnostics without doing a path search. */
  public TacticalNode bestLocalCover() {
    return nodes.stream()
        .filter(node -> node.escapeDirections() > 0)
        .filter(TacticalNode::isConcealed)
        .findFirst()
        .orElse(null);
  }

  /** Returns the closest sampled node to a position without performing a path search. */
  public TacticalNode nearestNode(Vec3 position) {
    if (position == null) return null;
    return nodes.stream()
        .min(Comparator.comparingDouble(node -> node.position().distanceToSqr(position)))
        .orElse(null);
  }

  /** A nearby hard-cover alternative makes random-looking open-ground jumping unjustified. */
  public boolean hasHardCoverNear(Vec3 position, double radius) {
    if (position == null) return false;
    double radiusSquared = radius * radius;
    return nodes.stream()
        .anyMatch(node -> hasCombatCover(node) && node.position().distanceToSqr(position) <= radiusSquared);
  }

  /** Checks a retained retreat node against the current threat before it is ever reused. */
  public boolean isCoveredFrom(HunterEntity hunter, TacticalNode node, Vec3 threat) {
    return node != null
        && threat != null
        && node.isConcealed()
        && blocksThreatHeadAndChest(hunter, node.position(), threat);
  }

  private java.util.stream.Stream<TacticalNode> baseCandidates(
      HunterIntent intent, Vec3 threat, double preferredRange, List<Vec3> avoidedPositions) {
    java.util.stream.Stream<TacticalNode> candidates =
        nodes.stream()
            .filter(node -> node.escapeDirections() > 0)
            .filter(node -> !isAvoided(node, avoidedPositions))
            .filter(TacticalMap::acceptsExposure);
    candidates =
        switch (intent) {
          case REPOSITION, DISENGAGE, AMBUSH, LISTEN ->
              candidates.filter(TacticalNode::isConcealed);
          case FLANK -> candidates.filter(node -> node.isConcealed() && !node.canObserveThreat());
          case HOLD_ANGLE -> candidates.filter(
              node -> node.canObserveThreat() && hasCombatCover(node));
          case OVERWATCH -> candidates.filter(TacticalMap::isViableOverwatchNode);
          case PUSH, INTERCEPT, SEARCH -> candidates;
        };
    if ((intent == HunterIntent.PUSH || intent == HunterIntent.INTERCEPT || intent == HunterIntent.SEARCH)
        && threat != null) {
      return candidates.sorted(
          Comparator.comparingDouble(
              node ->
                  node.position().distanceToSqr(threat)
                      - Math.min(preferredRange, 24.0D) * node.score()));
    }
    return candidates.sorted(Comparator.comparingDouble(TacticalNode::score).reversed());
  }

  private TacticalLayout layoutFor(
      HunterIntent intent,
      TacticalNode primary,
      Vec3 threat,
      List<Vec3> avoidedPositions,
      RecentBehavior history,
      long now) {
    TacticalNode approach =
        relatedNode(
            primary,
            0.0D,
            12.0D,
            node -> node.escapeDirections() > 0 && !node.isExposed(),
            avoidedPositions,
            history,
            now);
    if (approach == null) approach = primary;
    TacticalNode waiting =
        relatedNode(
            primary,
            0.0D,
            10.0D,
            TacticalNode::isConcealed,
            avoidedPositions,
            history,
            now);
    if (waiting == null && primary.isConcealed()) waiting = primary;
    TacticalNode firing =
        threat == null
            ? waiting
            : relatedNode(
                primary,
                0.0D,
                16.0D,
                node -> node.canObserveThreat() && hasCombatCover(node),
                avoidedPositions,
                history,
                now);
    if (firing == null && threat != null && primary.canObserveThreat() && hasCombatCover(primary)) {
      firing = primary;
    }
    TacticalNode firingNode = firing;
    TacticalNode secondaryFiring =
        threat == null || firingNode == null
            ? null
            : relatedNode(
                firingNode,
                3.0D,
                18.0D,
                node ->
                    node.canObserveThreat()
                        && hasCombatCover(node)
                        && differentFiringAngle(firingNode, node, threat),
                avoidedPositions,
                history,
                now);
    TacticalNode retreat =
        relatedNode(
            primary,
            3.0D,
            18.0D,
            node -> node.isConcealed() && (threat == null || !node.canObserveThreat()),
            avoidedPositions,
            history,
            now);
    TacticalNode selectedPrimary = primaryFor(intent, primary, approach, waiting, firing);
    double repeatPenalty =
        history == null ? 0.0D : history.positionPenalty(selectedPrimary.position(), now);
    double layoutScore =
        primary.score()
            - repeatPenalty * 0.65D
            + roleValue(approach, primary, 12.0D, 0.07D)
            + roleValue(waiting, primary, 10.0D, 0.16D)
            + roleValue(firing, primary, 16.0D, threat == null ? 0.04D : 0.18D)
            + roleValue(secondaryFiring, firing, 18.0D, 0.13D)
            + roleValue(retreat, primary, 18.0D, 0.24D);
    return new TacticalLayout(
        approach, waiting, firing, secondaryFiring, retreat, selectedPrimary, layoutScore, repeatPenalty);
  }

  /** Selects the role that this intent travels to while retaining the other roles for adaptation. */
  private static TacticalNode primaryFor(
      HunterIntent intent,
      TacticalNode anchor,
      TacticalNode approach,
      TacticalNode waiting,
      TacticalNode firing) {
    return switch (intent) {
      case HOLD_ANGLE, OVERWATCH -> firing != null ? firing : waiting != null ? waiting : anchor;
      case AMBUSH, LISTEN, REPOSITION -> waiting != null ? waiting : anchor;
      case PUSH, INTERCEPT, SEARCH -> approach != null ? approach : anchor;
      case FLANK, DISENGAGE -> anchor;
    };
  }

  private TacticalNode relatedNode(
      TacticalNode anchor,
      double minimumDistance,
      double maximumDistance,
      java.util.function.Predicate<TacticalNode> predicate,
      List<Vec3> avoidedPositions,
      RecentBehavior history,
      long now) {
    if (anchor == null) return null;
    double minimumDistanceSquared = minimumDistance * minimumDistance;
    double maximumDistanceSquared = maximumDistance * maximumDistance;
    return nodes.stream()
        .filter(predicate)
        .filter(node -> !isAvoided(node, avoidedPositions))
        .filter(node -> {
          double distanceSquared = node.position().distanceToSqr(anchor.position());
          return distanceSquared >= minimumDistanceSquared && distanceSquared <= maximumDistanceSquared;
        })
        .max(
            Comparator.comparingDouble(
                node ->
                    node.score()
                        - Math.sqrt(node.position().distanceToSqr(anchor.position())) / maximumDistance * 0.16D
                        - (history == null ? 0.0D : history.positionPenalty(node.position(), now)) * 0.45D))
        .orElse(null);
  }

  private static boolean differentFiringAngle(TacticalNode first, TacticalNode second, Vec3 threat) {
    Vec3 firstOffset = first.position().subtract(threat).multiply(1.0D, 0.0D, 1.0D);
    Vec3 secondOffset = second.position().subtract(threat).multiply(1.0D, 0.0D, 1.0D);
    if (firstOffset.lengthSqr() < 1.0E-6D || secondOffset.lengthSqr() < 1.0E-6D) return false;
    // A 25-degree separation is enough to stop a repeated peek while preserving the same fight.
    return firstOffset.normalize().dot(secondOffset.normalize()) < 0.91D;
  }

  private static double roleValue(
      TacticalNode node, TacticalNode anchor, double maximumDistance, double maximumValue) {
    if (node == null || anchor == null) return 0.0D;
    double distance = Math.sqrt(node.position().distanceToSqr(anchor.position()));
    return Math.max(0.0D, 1.0D - distance / maximumDistance) * maximumValue;
  }

  private static void addColumn(
      ServerLevel level,
      HunterEntity hunter,
      Vec3 threat,
      double x,
      double z,
      List<TacticalNode> nodes) {
    if (nodes.size() >= MAX_NODES) return;
    TacticalNode candidate = findBestLocalNode(level, hunter, threat, x, z);
    if (candidate != null) nodes.add(candidate);
  }

  /**
   * Heightmap sampling selects roofs and mountain tops. Search around the Hunter's actual floor
   * instead, so an interior floor remains a valid tactical destination.
   */
  private static TacticalNode findBestLocalNode(
      ServerLevel level, HunterEntity hunter, Vec3 threat, double x, double z) {
    int baseY = (int) Math.floor(hunter.getY());
    int verticalLimit = HunterAiConfig.MAX_TACTICAL_VERTICAL_DELTA.get();
    TacticalNode best = null;
    for (int y = baseY + Math.min(VERTICAL_SEARCH_ABOVE, verticalLimit);
        y >= baseY - Math.min(VERTICAL_SEARCH_BELOW, verticalLimit);
        y--) {
      Vec3 candidate = new Vec3(Math.floor(x) + 0.5D, y, Math.floor(z) + 0.5D);
      BlockPos feet = BlockPos.containing(candidate);
      TacticalTerrainCache.TerrainSample terrain =
          TERRAIN_CACHE.sample(level, feet, level.getGameTime(), TacticalMap::sampleTerrain);
      if (!terrain.standable()) continue;
      TacticalNode evaluated = evaluate(level, hunter, threat, candidate, terrain);
      if (best == null || evaluated.score() > best.score()) best = evaluated;
    }
    return best;
  }

  private static TacticalNode evaluate(
      ServerLevel level,
      HunterEntity hunter,
      Vec3 threat,
      Vec3 candidate,
      TacticalTerrainCache.TerrainSample terrain) {
    double cover = terrain.cover();
    boolean openToSky = terrain.openToSky();
    double shelter = openToSky ? 0.0D : 1.0D;
    boolean observesThreat = threat != null && !blocked(level, candidate.add(0.0D, 1.55D, 0.0D), threat, hunter);
    int escapes = terrain.escapeDirections();
    double rangeFit = threat == null ? 0.5D : 1.0D / (1.0D + candidate.distanceTo(threat) / 16.0D);
    double verticalDelta = Math.abs(candidate.y - hunter.getY());
    double exposure = openToSky && cover < HunterAiConfig.MIN_COMBAT_COVER.get() ? 0.32D : 0.0D;
    double score =
        cover * 0.52D
            + shelter * 0.12D
            + escapes / 4.0D * 0.16D
            + (observesThreat ? 0.10D : 0.0D)
            + rangeFit * 0.06D
            - Math.min(verticalDelta / 8.0D, 1.0D) * 0.16D
            - exposure;
    return new TacticalNode(
        candidate, cover, shelter, openToSky, observesThreat, escapes, verticalDelta, score);
  }

  private static TacticalTerrainCache.TerrainSample sampleTerrain(
      ServerLevel level, BlockPos feet) {
    boolean standable = level.getBlockState(feet).isAir()
        && level.getBlockState(feet.above()).isAir()
        && !level.getBlockState(feet.below()).isAir();
    return new TacticalTerrainCache.TerrainSample(
        standable,
        localCover(level, feet),
        level.canSeeSky(feet.above()),
        escapeDirections(level, Vec3.atBottomCenterOf(feet)));
  }

  private static double localCover(ServerLevel level, BlockPos feet) {
    int blockedSides = 0;
    for (BlockPos neighbor :
        new BlockPos[] {
          feet.north(), feet.south(), feet.east(), feet.west(),
          feet.above().north(), feet.above().south(), feet.above().east(), feet.above().west()
        }) {
      if (!level.getBlockState(neighbor).isAir()) blockedSides++;
    }
    return blockedSides / 8.0D;
  }

  private static int escapeDirections(ServerLevel level, Vec3 position) {
    int count = 0;
    BlockPos origin = BlockPos.containing(position);
    for (BlockPos neighbor :
        new BlockPos[] {origin.north(), origin.south(), origin.east(), origin.west()}) {
      if (level.getBlockState(neighbor).isAir()
          && level.getBlockState(neighbor.above()).isAir()
          && !level.getBlockState(neighbor.below()).isAir()) count++;
    }
    return count;
  }

  private static boolean blocked(ServerLevel level, Vec3 source, Vec3 target, HunterEntity hunter) {
    if (HuntMcHunterAi.instance() != null
        && !HuntMcHunterAi.instance().hunterRegistry().tryConsumeRay()) return true;
    return level.clip(
                new ClipContext(
                    source, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, hunter))
            .getType()
        != HitResult.Type.MISS;
  }

  /**
   * Candidate ordering must stay purely tactical. HunterCoordinator creates and retains the one
   * navigation Path used for execution, preventing a second path calculation from disagreeing
   * with the reachability check made during selection.
   */
  private static TacticalNode firstCandidate(java.util.stream.Stream<TacticalNode> candidates) {
    return candidates.findFirst().orElse(null);
  }

  private java.util.stream.Stream<TacticalNode> overwatchCandidates(
      HunterEntity hunter, List<Vec3> avoidedPositions) {
    return nodes.stream()
        .filter(node -> node.escapeDirections() > 0)
        .filter(node -> node.isConcealed() || hasCombatCover(node))
        .filter(TacticalMap::acceptsExposure)
        .filter(node -> !isAvoided(node, avoidedPositions));
  }

  static boolean isViableOverwatchNode(TacticalNode node) {
    if (node == null || (!node.isConcealed() && node.hardCover() < 0.30D)) return false;
    if (!node.openToSky()) return true;
    return node.verticalDelta() >= 1.5D
        && node.hardCover() >= 0.30D
        && node.escapeDirections() >= 2;
  }

  private static Comparator<TacticalNode> overwatchComparator(
      HunterEntity hunter, double highGroundPreference) {
    return Comparator.comparingDouble(
            (TacticalNode node) ->
                node.score()
                    + Math.min(2.0D, Math.max(0.0D, node.position().y - hunter.getY()))
                        * highGroundPreference
                        * 0.035D)
        .reversed();
  }

  /**
   * A roof or nearby wall is not enough during a firefight. A retreat node must block both the
   * threat-to-head and threat-to-chest rays, otherwise it is merely sheltered open ground.
   */
  private static TacticalNode firstThreatCovered(
      HunterEntity hunter, Vec3 threat, java.util.stream.Stream<TacticalNode> candidates) {
    return candidates
        .filter(node -> blocksThreatHeadAndChest(hunter, node.position(), threat))
        .findFirst()
        .orElse(null);
  }

  private static boolean blocksThreatHeadAndChest(HunterEntity hunter, Vec3 candidate, Vec3 threat) {
    if (!(hunter.level() instanceof ServerLevel level)) return false;
    Vec3 threatChest = threat.add(0.0D, 1.10D, 0.0D);
    Vec3 threatHead = threat.add(0.0D, 1.55D, 0.0D);
    Vec3 candidateChest = candidate.add(0.0D, 1.10D, 0.0D);
    Vec3 candidateHead = candidate.add(0.0D, 1.55D, 0.0D);
    return blockedForCover(level, threatHead, candidateHead, hunter)
        && blockedForCover(level, threatChest, candidateChest, hunter);
  }

  /** Budget exhaustion must never manufacture a hard-cover result. */
  private static boolean blockedForCover(
      ServerLevel level, Vec3 source, Vec3 target, HunterEntity hunter) {
    if (HuntMcHunterAi.instance() != null
        && !HuntMcHunterAi.instance().hunterRegistry().tryConsumeRay()) return false;
    return level.clip(
                new ClipContext(
                    source, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, hunter))
            .getType()
        != HitResult.Type.MISS;
  }

  private static boolean isAvoided(TacticalNode node, List<Vec3> avoidedPositions) {
    return avoidedPositions.stream()
        .anyMatch(position -> node.position().distanceToSqr(position) <= AVOIDED_NODE_DISTANCE_SQUARED);
  }

  private static boolean acceptsExposure(TacticalNode node) {
    return !HunterAiConfig.AVOID_OPEN_GROUND.get() || !node.isExposed();
  }

  private static boolean hasCombatCover(TacticalNode node) {
    return node.cover() >= HunterAiConfig.MIN_COMBAT_COVER.get();
  }

  private void refreshSectorRisk(ServerLevel level, HunterEntity hunter) {
    BlockPos feet = BlockPos.containing(hunter.position());
    BlockPos[] neighbors = {feet.north(), feet.east(), feet.south(), feet.west()};
    for (int index = 0; index < neighbors.length; index++) {
      BlockPos neighbor = neighbors[index];
      boolean blocked =
          !level.getBlockState(neighbor).isAir() || !level.getBlockState(neighbor.above()).isAir();
      sectorAccessibility[index] = blocked ? 0.10D : 1.0D;
      sectorUnknownExposure[index] = blocked ? 0.10D : level.canSeeSky(neighbor.above()) ? 1.0D : 0.65D;
    }
  }
}
