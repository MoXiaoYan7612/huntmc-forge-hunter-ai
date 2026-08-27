package cn.huntmc.hunterai.decision;

import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.huntmc.hunterai.ai.ThreatCorridor;
import cn.huntmc.hunterai.navigation.ValidatedRoute;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ExtractionRouteRiskTest {
  @Test
  void routeThroughGunfireCorridorScoresWorseThanAlternateExitRoute() {
    ThreatCorridor gunfire = new ThreatCorridor(
        "gunfire", Vec3.ZERO, new Vec3(1.0D, 0.0D, 0.0D), 3.0D, 10.0D, 0.90D, "GUNSHOT");
    ValidatedRoute dangerous = route(List.of(
        new Vec3(2.0D, 0.0D, 0.5D), new Vec3(12.0D, 0.0D, 0.5D)));
    ValidatedRoute safe = route(List.of(
        new Vec3(0.0D, 0.0D, 18.0D), new Vec3(15.0D, 0.0D, 20.0D)));

    double dangerousRisk = TacticalDecisionPlanner.routeThreatRisk(dangerous, List.of(gunfire));
    double safeRisk = TacticalDecisionPlanner.routeThreatRisk(safe, List.of(gunfire));
    assertTrue(dangerousRisk > safeRisk + 0.35D);
  }

  @Test
  void damageCorridorHasMoreAuthorityThanEqualGunshotCorridor() {
    ValidatedRoute route = route(List.of(new Vec3(4.0D, 0.0D, 0.0D)));
    ThreatCorridor damage = new ThreatCorridor(
        "damage", Vec3.ZERO, new Vec3(1.0D, 0.0D, 0.0D), 2.0D, 10.0D, 0.8D, "DAMAGE");
    ThreatCorridor gunshot = new ThreatCorridor(
        "shot", Vec3.ZERO, new Vec3(1.0D, 0.0D, 0.0D), 2.0D, 10.0D, 0.8D, "GUNSHOT");
    assertTrue(
        TacticalDecisionPlanner.routeThreatRisk(route, List.of(damage))
            > TacticalDecisionPlanner.routeThreatRisk(route, List.of(gunshot)));
  }

  private static ValidatedRoute route(List<Vec3> points) {
    Vec3 end = points.get(points.size() - 1);
    return new ValidatedRoute(
        UUID.randomUUID(), end, points, end, true, 20.0D, 0.0D, 0.0D, "");
  }
}
