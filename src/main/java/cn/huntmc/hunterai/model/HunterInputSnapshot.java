package cn.huntmc.hunterai.model;

import cn.huntmc.hunterai.match.MatchDirective;
import java.util.UUID;

/** Complete immutable input boundary for one coordinator tick. */
public record HunterInputSnapshot(
    UUID hunterId, int monitorId, long gameTime, SelfSnapshot self, MatchDirective match) {
  public HunterInputSnapshot {
    if (hunterId == null || self == null || match == null)
      throw new IllegalArgumentException("input snapshot fields are required");
  }
}
