package cn.huntmc.hunterai.match;

import java.util.List;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;

/** Immutable public match facts. It never contains a hidden opponent's exact state. */
public record MatchDirective(
    UUID botId,
    UUID matchId,
    String teamId,
    boolean assigned,
    boolean active,
    String phase,
    String bountyState,
    Vec3 publicObjective,
    Vec3 extraction,
    List<Vec3> extractions,
    boolean carryingBounty,
    long refreshedAt,
    long matchRemainingTicks,
    long bountyRemainingTicks,
    double bountySignalUncertaintyRadius,
    String banishingTeamId,
    List<ReviveTarget> reviveTargets,
    double bossInteractionDistance) {
  public MatchDirective {
    teamId = teamId == null ? "" : teamId;
    phase = phase == null ? "WAITING" : phase;
    bountyState = bountyState == null ? "" : bountyState;
    extractions = (extractions == null ? java.util.stream.Stream.<Vec3>empty() : extractions.stream())
        .filter(java.util.Objects::nonNull)
        .distinct()
        .toList();
    if (extractions.isEmpty() && extraction != null) extractions = List.of(extraction);
    if (extraction == null && !extractions.isEmpty()) extraction = extractions.get(0);
    banishingTeamId = banishingTeamId == null ? "" : banishingTeamId;
    reviveTargets = List.copyOf(reviveTargets == null ? List.of() : reviveTargets);
    matchRemainingTicks = Math.max(-1L, matchRemainingTicks);
    bountyRemainingTicks = Math.max(-1L, bountyRemainingTicks);
    bountySignalUncertaintyRadius = Math.max(0.0D, bountySignalUncertaintyRadius);
    if (!Double.isFinite(bossInteractionDistance) || bossInteractionDistance <= 0.0D)
      throw new IllegalArgumentException("boss interaction distance is invalid");
  }

  /** Source-compatible directive shape before all active extraction choices were retained. */
  public MatchDirective(
      UUID botId,
      UUID matchId,
      String teamId,
      boolean assigned,
      boolean active,
      String phase,
      String bountyState,
      Vec3 publicObjective,
      Vec3 extraction,
      boolean carryingBounty,
      long refreshedAt,
      long matchRemainingTicks,
      long bountyRemainingTicks,
      double bountySignalUncertaintyRadius,
      String banishingTeamId,
      List<ReviveTarget> reviveTargets,
      double bossInteractionDistance) {
    this(
        botId,
        matchId,
        teamId,
        assigned,
        active,
        phase,
        bountyState,
        publicObjective,
        extraction,
        extraction == null ? List.of() : List.of(extraction),
        carryingBounty,
        refreshedAt,
        matchRemainingTicks,
        bountyRemainingTicks,
        bountySignalUncertaintyRadius,
        banishingTeamId,
        reviveTargets,
        bossInteractionDistance);
  }

  /** Source-compatible directive shape before Bukkit published its Boss interaction range. */
  public MatchDirective(
      UUID botId,
      UUID matchId,
      String teamId,
      boolean assigned,
      boolean active,
      String phase,
      String bountyState,
      Vec3 publicObjective,
      Vec3 extraction,
      boolean carryingBounty,
      long refreshedAt,
      long matchRemainingTicks,
      long bountyRemainingTicks,
      double bountySignalUncertaintyRadius,
      String banishingTeamId,
      List<ReviveTarget> reviveTargets) {
    this(
        botId,
        matchId,
        teamId,
        assigned,
        active,
        phase,
        bountyState,
        publicObjective,
        extraction,
        carryingBounty,
        refreshedAt,
        matchRemainingTicks,
        bountyRemainingTicks,
        bountySignalUncertaintyRadius,
        banishingTeamId,
        reviveTargets,
        cn.huntmc.bridge.contract.BridgeProtocol.DEFAULT_BOSS_INTERACTION_DISTANCE);
  }

  public MatchDirective(
      UUID botId,
      UUID matchId,
      String teamId,
      boolean assigned,
      boolean active,
      String phase,
      String bountyState,
      Vec3 publicObjective,
      Vec3 extraction,
      boolean carryingBounty,
      long refreshedAt,
      long matchRemainingTicks,
      long bountyRemainingTicks,
      double bountySignalUncertaintyRadius) {
    this(
        botId,
        matchId,
        teamId,
        assigned,
        active,
        phase,
        bountyState,
        publicObjective,
        extraction,
        carryingBounty,
        refreshedAt,
        matchRemainingTicks,
        bountyRemainingTicks,
        bountySignalUncertaintyRadius,
        "",
        List.of());
  }

  public MatchDirective(
      UUID botId,
      UUID matchId,
      String teamId,
      boolean assigned,
      boolean active,
      String phase,
      String bountyState,
      Vec3 publicObjective,
      Vec3 extraction,
      boolean carryingBounty,
      long refreshedAt,
      long matchRemainingTicks,
      long bountyRemainingTicks,
      double bountySignalUncertaintyRadius,
      String banishingTeamId) {
    this(
        botId,
        matchId,
        teamId,
        assigned,
        active,
        phase,
        bountyState,
        publicObjective,
        extraction,
        carryingBounty,
        refreshedAt,
        matchRemainingTicks,
        bountyRemainingTicks,
        bountySignalUncertaintyRadius,
        banishingTeamId,
        List.of());
  }

  public static MatchDirective standalone(long now) {
    return new MatchDirective(
        null, null, "", false, true, "STANDALONE", "", null, null, false, now, -1L, -1L, 0.0D, "", List.of());
  }

  public static MatchDirective unavailable(UUID botId, UUID matchId, String teamId, long now) {
    return new MatchDirective(
        botId, matchId, teamId, true, false, "WAITING", "", null, null, false, now, -1L, -1L, 0.0D, "", List.of());
  }

  public boolean bountyAvailable() {
    return "AVAILABLE".equals(bountyState) || "DROPPED".equals(bountyState);
  }

  public boolean bossNeedsBanish() {
    return "UNBANISHED".equals(bountyState);
  }

  public boolean banishing() {
    return "BANISHING".equals(bountyState);
  }

  public boolean ownTeamBanishing() {
    return banishing()
        && !teamId.isBlank()
        && teamId.equals(banishingTeamId);
  }

  public boolean enemyTeamBanishing() {
    return banishing() && !ownTeamBanishing();
  }
}
