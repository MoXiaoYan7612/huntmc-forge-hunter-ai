package cn.huntmc.hunterai.decision;

import cn.huntmc.hunterai.ai.HunterIntent;
import cn.huntmc.hunterai.ai.TacticalOpportunity;
import java.util.List;
import java.util.UUID;

/** Immutable plan committed by the coordinator and consumed by action proposal modules. */
public record HunterPlan(
    UUID id,
    StrategicGoal goal,
    HunterIntent intent,
    TacticalOpportunity opportunity,
    PlanPhase phase,
    List<ContingencyBranch> contingencies,
    long committedUntil,
    long deadline,
    PlanValidity validity,
    FallbackPlan fallback,
    double score,
    String reason) {
  public HunterPlan {
    if (id == null || goal == null || intent == null || phase == null || validity == null || fallback == null)
      throw new IllegalArgumentException("plan identity and state are required");
    contingencies = List.copyOf(contingencies == null ? List.of() : contingencies);
    reason = reason == null ? "" : reason;
  }

  public boolean committed(long now) {
    return validity == PlanValidity.VALID && now < committedUntil;
  }

  public HunterPlan withPhase(PlanPhase next, String nextReason) {
    return new HunterPlan(
        id, goal, intent, opportunity, next, contingencies, committedUntil, deadline, validity,
        fallback, score, nextReason);
  }

  public HunterPlan withOpportunity(TacticalOpportunity next, String nextReason) {
    return new HunterPlan(
        id,
        goal,
        intent,
        next,
        phase,
        contingencies,
        committedUntil,
        deadline,
        validity,
        fallback,
        score,
        nextReason);
  }

  public HunterPlan activate(ContingencyBranch branch, String nextReason) {
    if (branch == null || !contingencies.contains(branch))
      throw new IllegalArgumentException("contingency branch must belong to the plan");
    return new HunterPlan(
        id,
        goal,
        branch.intent(),
        opportunity,
        branch.nextPhase(),
        contingencies,
        committedUntil,
        deadline,
        validity,
        fallback,
        score,
        nextReason);
  }

  public HunterPlan invalidated(String nextReason) {
    return new HunterPlan(
        id, goal, intent, opportunity, phase, contingencies, committedUntil, deadline,
        PlanValidity.INVALID, fallback, score, nextReason);
  }
}
