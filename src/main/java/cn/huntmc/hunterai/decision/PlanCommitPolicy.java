package cn.huntmc.hunterai.decision;

/** Keeps normal plan switches stable while allowing factual invalidation to preempt immediately. */
public final class PlanCommitPolicy {
  public static final double NORMAL_SWITCH_MARGIN = 0.12D;

  private PlanCommitPolicy() {}

  public static boolean shouldReplace(double currentScore, double candidateScore, boolean forced) {
    return forced || candidateScore >= currentScore + NORMAL_SWITCH_MARGIN;
  }
}
