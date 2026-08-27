package cn.huntmc.hunterai.ai;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.huntmc.hunterai.profile.HunterProfile;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class CombatCoverPolicyTest {
  @Test
  void hardCoveredObservablePositionBeatsAnOpenNearbyPosition() {
    TacticalNode covered = node(new Vec3(6.0D, 0.0D, 0.0D), 0.75D, false, true, 0.20D);
    TacticalNode open = node(new Vec3(3.0D, 0.0D, 0.0D), 0.35D, true, true, 0.70D);

    assertTrue(
        CombatController.combatCoverScore(covered, Vec3.ZERO)
            > CombatController.combatCoverScore(open, Vec3.ZERO));
  }

  @Test
  void shotgunCoverThatClosesIntoItsUsefulRangeBeatsBackwardCover() {
    Vec3 threat = new Vec3(22.0D, 0.0D, 0.0D);
    TacticalNode forward = node(new Vec3(8.0D, 0.0D, 0.0D), 0.55D, false, true, 0.30D);
    TacticalNode backward = node(new Vec3(-6.0D, 0.0D, 0.0D), 0.60D, false, true, 0.28D);

    assertTrue(
        CombatController.combatCoverScore(forward, Vec3.ZERO, threat, 8.0D, 24.0D)
            > CombatController.combatCoverScore(backward, Vec3.ZERO, threat, 8.0D, 24.0D));
  }

  @Test
  void rangeDisadvantagePrefersConstrictedCoverOverOpenGround() {
    TacticalNode constricted = node(new Vec3(7.0D, 0.0D, 2.0D), 0.65D, false, true, 0.30D);
    TacticalNode open = node(new Vec3(6.0D, 0.0D, 0.0D), 0.45D, true, true, 0.62D);
    TacticalSpace space = new TacticalSpace(
        Vec3.ZERO, new Vec3(40.0D, 0.0D, 0.0D), List.of(open, constricted), 0.7D, 0.0D);

    assertEquals(
        constricted,
        CombatController.selectRangeDisadvantagePosition(
            space, Vec3.ZERO, new Vec3(40.0D, 0.0D, 0.0D), HunterProfile.SHOTGUN));
  }

  @Test
  void covertRepositionThresholdVariesByWeaponWithoutBecomingPerTickRandom() {
    UUID hunter = UUID.fromString("00000000-0000-0000-0000-000000000101");
    UUID target = UUID.fromString("00000000-0000-0000-0000-000000000202");
    int shotgun = CombatController.repositionShotThreshold(
        hunter, target, HunterProfile.SHOTGUN, 0.5D);
    int rifle = CombatController.repositionShotThreshold(
        hunter, target, HunterProfile.RIFLE, 0.5D);

    assertTrue(shotgun >= 1 && shotgun <= 2);
    assertTrue(rifle >= 3 && rifle <= 4);
    assertEquals(
        shotgun,
        CombatController.repositionShotThreshold(
            hunter, target, HunterProfile.SHOTGUN, 0.5D));
  }

  private static TacticalNode node(
      Vec3 position,
      double hardCover,
      boolean openToSky,
      boolean observesThreat,
      double standingExposure) {
    return new TacticalNode(
        position,
        hardCover,
        hardCover,
        openToSky,
        observesThreat,
        3,
        0.0D,
        0.5D,
        standingExposure,
        Math.max(0.0D, standingExposure - 0.20D),
        hardCover,
        0.75D,
        0.20D,
        observesThreat ? 0.70D : 0.20D,
        openToSky ? 0.60D : 0.20D);
  }
}
