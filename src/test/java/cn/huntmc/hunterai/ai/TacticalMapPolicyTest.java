package cn.huntmc.hunterai.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class TacticalMapPolicyTest {
  @Test
  void bountyDefenseRejectsOpenOrSingleExitWaitingPoints() {
    Vec3 bounty = Vec3.ZERO;
    TacticalNode defended =
        node(new Vec3(18.0D, 0.0D, 0.0D), 0.60D, 0.20D, 3, 0.75D, 0.25D, 0.30D);
    TacticalNode exposed =
        node(new Vec3(18.0D, 0.0D, 0.0D), 0.20D, 0.75D, 3, 0.75D, 0.25D, 0.80D);
    TacticalNode trapped =
        node(new Vec3(18.0D, 0.0D, 0.0D), 0.60D, 0.20D, 1, 0.25D, 0.75D, 0.30D);

    assertTrue(TacticalMap.isDefensibleBountyNode(defended, bounty));
    assertFalse(TacticalMap.isDefensibleBountyNode(exposed, bounty));
    assertFalse(TacticalMap.isDefensibleBountyNode(trapped, bounty));
  }

  @Test
  void bountyDefenseStaysInTheOuterStagingRing() {
    Vec3 bounty = Vec3.ZERO;
    assertFalse(
        TacticalMap.isDefensibleBountyNode(
            node(new Vec3(4.0D, 0.0D, 0.0D), 0.60D, 0.20D, 3, 0.75D, 0.25D, 0.30D),
            bounty));
    assertFalse(
        TacticalMap.isDefensibleBountyNode(
            node(new Vec3(35.0D, 0.0D, 0.0D), 0.60D, 0.20D, 3, 0.75D, 0.25D, 0.30D),
            bounty));
  }

  @Test
  void bountyFallbackValidatesTheActualPrimaryPosition() {
    Vec3 bounty = Vec3.ZERO;
    TacticalNode safeOuterPosition =
        node(new Vec3(20.0D, 0.0D, 0.0D), 0.50D, 0.35D, 2, 0.50D, 0.50D, 0.45D);
    TacticalNode openPosition =
        node(new Vec3(20.0D, 0.0D, 0.0D), 0.10D, 0.80D, 3, 0.75D, 0.25D, 0.80D);
    TacticalNode tooClose =
        node(new Vec3(4.0D, 0.0D, 0.0D), 0.50D, 0.35D, 2, 0.50D, 0.50D, 0.45D);

    assertTrue(TacticalMap.isAcceptableBountyFallback(safeOuterPosition, bounty, true));
    assertFalse(TacticalMap.isAcceptableBountyFallback(openPosition, bounty, true));
    assertFalse(TacticalMap.isAcceptableBountyFallback(tooClose, bounty, true));
    assertTrue(TacticalMap.isAcceptableBountyFallback(tooClose, bounty, false));
  }

  @Test
  void overwatchAcceptsBuildingsAndDefensibleHighGroundButRejectsBareSkyline() {
    TacticalNode building = overwatchNode(false, 0.0D, 0.45D, 2);
    TacticalNode defendedHill = overwatchNode(true, 2.0D, 0.40D, 3);
    TacticalNode bareHill = overwatchNode(true, 2.0D, 0.10D, 3);

    assertTrue(TacticalMap.isViableOverwatchNode(building));
    assertTrue(TacticalMap.isViableOverwatchNode(defendedHill));
    assertFalse(TacticalMap.isViableOverwatchNode(bareHill));
  }

  private static TacticalNode overwatchNode(
      boolean openToSky, double verticalDelta, double hardCover, int escapes) {
    return new TacticalNode(
        Vec3.ZERO,
        hardCover,
        openToSky ? 0.0D : 1.0D,
        openToSky,
        true,
        escapes,
        verticalDelta,
        0.7D,
        1.0D - hardCover,
        1.0D - hardCover,
        hardCover,
        0.7D,
        0.3D,
        0.7D,
        0.3D);
  }

  private static TacticalNode node(
      Vec3 position,
      double hardCover,
      double crouchedExposure,
      int escapes,
      double rearSafety,
      double flankRisk,
      double unknownRisk) {
    return new TacticalNode(
        position,
        hardCover,
        hardCover,
        unknownRisk > 0.70D,
        true,
        escapes,
        0.0D,
        hardCover,
        1.0D - hardCover,
        crouchedExposure,
        hardCover,
        rearSafety,
        flankRisk,
        0.70D,
        unknownRisk);
  }
}
