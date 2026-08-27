package cn.huntmc.hunterai.model;

import cn.huntmc.hunterai.decision.DeliberationMode;

public record IntelAssessment(
    double sourceReliability,
    double recency,
    double spatialConsistency,
    double independentEvidence,
    double localizationQuality,
    double relevance,
    double quality,
    DeliberationMode mode,
    String reason) {
  public IntelAssessment {
    if (mode == null) throw new IllegalArgumentException("mode is required");
    reason = reason == null ? "" : reason;
  }
}
