package cn.huntmc.hunterai.ai;

public record TacticalEdge(
    int from,
    int to,
    double distance,
    double exposureCost,
    double heightCost,
    double unknownCost,
    boolean traversable) {}
