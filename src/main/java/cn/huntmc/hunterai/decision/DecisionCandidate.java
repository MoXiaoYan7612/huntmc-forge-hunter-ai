package cn.huntmc.hunterai.decision;

import cn.huntmc.hunterai.ai.HunterIntent;
import cn.huntmc.hunterai.ai.TacticalOpportunity;
import cn.huntmc.hunterai.navigation.ValidatedRoute;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;

public record DecisionCandidate(
    UUID id,
    StrategicGoal goal,
    HunterIntent intent,
    TacticalOpportunity opportunity,
    ValidatedRoute route,
    Vec3 target,
    double analyticScore,
    double exposure,
    double objectiveDelay,
    double escapeQuality,
    String source,
    String infeasibleReason) {
  public DecisionCandidate {
    if (id == null || goal == null || intent == null) throw new IllegalArgumentException("candidate identity is required");
    source = source == null ? "" : source;
    infeasibleReason = infeasibleReason == null ? "" : infeasibleReason;
  }

  public boolean feasible() {
    return infeasibleReason.isBlank() && (route == null || route.reachable());
  }
}
