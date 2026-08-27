package cn.huntmc.hunterai.ai;

import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Immutable local tactical snapshot. It contains only the bounded nodes already sampled this pass. */
public record TacticalSpace(
    Vec3 origin,
    Vec3 threat,
    List<TacticalNode> nodes,
    List<TacticalEdge> edges,
    List<ThreatCorridor> threatCorridors,
    double localExposure,
    double uncertaintyRadius) {
  public TacticalSpace {
    nodes = List.copyOf(nodes == null ? List.of() : nodes);
    edges = List.copyOf(edges == null ? List.of() : edges);
    threatCorridors = List.copyOf(threatCorridors == null ? List.of() : threatCorridors);
  }

  public TacticalSpace(
      Vec3 origin, Vec3 threat, List<TacticalNode> nodes, double localExposure, double uncertaintyRadius) {
    this(origin, threat, nodes, buildEdges(nodes), List.of(), localExposure, uncertaintyRadius);
  }
  public static TacticalSpace from(
      Vec3 origin,
      Vec3 threat,
      TacticalMap tacticalMap,
      double localExposure,
      EnemyBelief belief) {
    return new TacticalSpace(
        origin,
        threat,
        List.copyOf(tacticalMap.nodes()),
        buildEdges(tacticalMap.nodes()),
        List.of(),
        localExposure,
        belief.uncertaintyRadius());
  }

  public TacticalSpace withCorridors(List<ThreatCorridor> corridors) {
    return new TacticalSpace(origin, threat, nodes, edges, corridors, localExposure, uncertaintyRadius);
  }

  public long concealedNodeCount() {
    return nodes.stream().filter(TacticalNode::isConcealed).count();
  }

  public long exposedNodeCount() {
    return nodes.stream().filter(TacticalNode::isExposed).count();
  }

  private static List<TacticalEdge> buildEdges(List<TacticalNode> nodes) {
    if (nodes == null || nodes.isEmpty()) return List.of();
    java.util.ArrayList<TacticalEdge> result = new java.util.ArrayList<>();
    for (int from = 0; from < nodes.size(); from++) {
      for (int to = from + 1; to < nodes.size(); to++) {
        TacticalNode first = nodes.get(from);
        TacticalNode second = nodes.get(to);
        double distance = first.position().distanceTo(second.position());
        if (distance > 7.5D || Math.abs(first.position().y - second.position().y) > 3.5D) continue;
        double exposure = (first.standingExposure() + second.standingExposure()) * 0.5D;
        double height = Math.abs(first.position().y - second.position().y) / 3.5D;
        double unknown = (first.unknownRisk() + second.unknownRisk()) * 0.5D;
        result.add(new TacticalEdge(from, to, distance, exposure, height, unknown, true));
        result.add(new TacticalEdge(to, from, distance, exposure, height, unknown, true));
      }
    }
    return List.copyOf(result);
  }
}
