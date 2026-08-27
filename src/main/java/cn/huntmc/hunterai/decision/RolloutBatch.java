package cn.huntmc.hunterai.decision;

import java.util.List;

public record RolloutBatch(
    List<PredictionResult> predictions,
    boolean budgetExhausted,
    long elapsedMicros,
    int samplesCompleted,
    double confidence) {
  public RolloutBatch {
    predictions = List.copyOf(predictions == null ? List.of() : predictions);
  }
}
