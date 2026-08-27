package cn.huntmc.hunterai.decision;

import cn.huntmc.hunterai.ai.HunterIntent;

/** A pre-selected response to a material change, not a per-tick action script. */
public record ContingencyBranch(String condition, HunterIntent intent, PlanPhase nextPhase) {
  public ContingencyBranch {
    condition = condition == null ? "" : condition;
    if (intent == null || nextPhase == null) throw new IllegalArgumentException("branch fields are required");
  }
}
