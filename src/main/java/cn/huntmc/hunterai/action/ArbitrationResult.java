package cn.huntmc.hunterai.action;

import java.util.List;

public record ArbitrationResult(ActionFrame frame, List<Decision> decisions) {
  public ArbitrationResult {
    if (frame == null) throw new IllegalArgumentException("frame is required");
    decisions = List.copyOf(decisions == null ? List.of() : decisions);
  }

  public record Decision(String owner, ActionChannel channel, boolean accepted, String reason) {}
}
