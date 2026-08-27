package cn.huntmc.hunterai.ai;

import cn.huntmc.hunterai.navigation.ValidatedRoute;
import java.util.List;
import java.util.UUID;

/** A complete spatial chain. Every route is native-navigation validated before selection. */
public record TacticalOpportunity(
    UUID id,
    HunterIntent intent,
    ValidatedRoute approachRoute,
    TacticalNode waitingPoint,
    TacticalNode observationPoint,
    TacticalNode firstFiringPoint,
    TacticalNode secondFiringPoint,
    ValidatedRoute retreatRoute,
    List<ThreatCorridor> controlledCorridors,
    double score,
    double positionCompromise,
    String reason) {
  public TacticalOpportunity {
    if (id == null || intent == null) throw new IllegalArgumentException("opportunity identity is required");
    controlledCorridors = List.copyOf(controlledCorridors == null ? List.of() : controlledCorridors);
    reason = reason == null ? "" : reason;
  }

  public TacticalOpportunity(HunterIntent intent, TacticalNode node, double score, String reason) {
    this(
        UUID.nameUUIDFromBytes((intent + ":" + (node == null ? "none" : node.position())).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        intent,
        null,
        node,
        node,
        node,
        null,
        null,
        List.of(),
        score,
        0.0D,
        reason);
  }

  public TacticalNode primaryPosition() {
    if (firstFiringPoint != null) return firstFiringPoint;
    if (observationPoint != null) return observationPoint;
    return waitingPoint;
  }

  /** Compatibility for existing utility code during the coordinator migration. */
  public TacticalNode node() {
    return primaryPosition();
  }

  public TacticalOpportunity withApproachRoute(ValidatedRoute route) {
    return new TacticalOpportunity(
        id,
        intent,
        route,
        waitingPoint,
        observationPoint,
        firstFiringPoint,
        secondFiringPoint,
        retreatRoute,
        controlledCorridors,
        score,
        positionCompromise,
        reason);
  }
}
