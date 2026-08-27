package cn.huntmc.hunterai.director;

import cn.huntmc.hunterai.entity.HunterEntity;
import cn.huntmc.hunterai.match.MatchDirective;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * Match-wide pacing and role director.
 *
 * <p>This layer may inspect every Hunter in a match, but it deliberately publishes no hidden
 * actor coordinates.  A Hunter receives only a coarse role, crowding estimate, and expiry time;
 * legal perception remains the only way to aim at or attack an entity.
 */
public final class GodDirector {
  private static final long REFRESH_TICKS = 20L;
  private final Map<UUID, Observation> observations = new HashMap<>();
  private final Map<UUID, ActorObservation> actors = new HashMap<>();
  private final Map<UUID, Directive> directives = new HashMap<>();
  private final Map<UUID, Long> matchFirstObservedAt = new HashMap<>();
  private long nextRefreshAt = Long.MIN_VALUE;

  public Directive update(
      HunterEntity hunter, MatchDirective match, long now) {
    if (hunter == null || match == null || match.matchId() == null || !match.active())
      return Directive.neutral(now);
    matchFirstObservedAt.putIfAbsent(match.matchId(), now);
    observations.put(
        hunter.getUUID(),
        new Observation(
            hunter.getUUID(),
            match.matchId(),
            match.teamId(),
            hunter.level().dimension().location().toString(),
            hunter.position(),
            match.publicObjective(),
            match.bountyState(),
            match.carryingBounty(),
            now));
    observations.entrySet().removeIf(entry -> now - entry.getValue().observedAt() > 60L);
    actors.entrySet().removeIf(entry -> now - entry.getValue().observedAt() > 60L);
    if (now >= nextRefreshAt || !directives.containsKey(hunter.getUUID())) {
      refresh(now);
      nextRefreshAt = now + REFRESH_TICKS;
    }
    return directives.getOrDefault(hunter.getUUID(), Directive.neutral(now));
  }

  public void forget(UUID hunterId) {
    if (hunterId == null) return;
    observations.remove(hunterId);
    directives.remove(hunterId);
  }

  /** Records exact global actor state inside the director only; directives never expose it. */
  public void observeActor(LivingEntity actor, long now) {
    if (actor == null || !actor.isAlive()) return;
    boolean humanParticipant = actor instanceof Player player && !player.isSpectator();
    actors.put(
        actor.getUUID(),
        new ActorObservation(
            actor.getUUID(),
            actor.level().dimension().location().toString(),
            actor.position(),
            humanParticipant,
            now));
  }

  private void refresh(long now) {
    directives.clear();
    Map<UUID, List<Observation>> byMatch = new HashMap<>();
    observations.values().forEach(
        observation -> byMatch.computeIfAbsent(observation.matchId(), ignored -> new ArrayList<>())
            .add(observation));
    matchFirstObservedAt.keySet().retainAll(byMatch.keySet());
    for (List<Observation> matchHunters : byMatch.values()) assignMatch(matchHunters, now);
  }

  private void assignMatch(List<Observation> hunters, long now) {
    if (hunters.isEmpty()) return;
    hunters.sort(Comparator.comparingLong(item -> stableOrder(item.hunterId())));
    int population = hunters.size();
    Observation reference = hunters.get(0);
    long firstObservedAt = matchFirstObservedAt.getOrDefault(reference.matchId(), now);
    long releaseDelay = objectiveReleaseDelay(reference.matchId());
    boolean objectiveReleased = !"UNBANISHED".equals(reference.bountyState())
        || now - firstObservedAt >= releaseDelay;
    long humansInDimension = actors.values().stream()
        .filter(ActorObservation::humanParticipant)
        .filter(actor -> actor.dimension().equals(reference.dimension()))
        .filter(actor -> now - actor.observedAt() <= 40L)
        .count();

    Map<String, List<Observation>> byTeam = new HashMap<>();
    for (Observation hunter : hunters) {
      String teamKey = hunter.teamId().isBlank()
          ? "unassigned:" + hunter.hunterId()
          : "team:" + hunter.teamId();
      byTeam.computeIfAbsent(teamKey, ignored -> new ArrayList<>()).add(hunter);
    }
    List<Map.Entry<String, List<Observation>>> teams = new ArrayList<>(byTeam.entrySet());
    teams.sort(Comparator.comparingLong(entry -> stableTeamOrder(reference.matchId(), entry.getKey())));
    teams.forEach(entry -> entry.getValue().sort(
        Comparator.comparingLong(item -> stableOrder(item.hunterId()))));

    Map<String, Integer> teamSizes = new HashMap<>();
    teams.forEach(team -> teamSizes.put(team.getKey(), team.getValue().size()));
    Set<String> objectiveTeams = new HashSet<>(
        selectObjectiveTeams(reference.matchId(), teamSizes, population));
    for (Map.Entry<String, List<Observation>> team : teams) {
      if (team.getValue().stream().anyMatch(Observation::carryingBounty))
        objectiveTeams.add(team.getKey());
    }

    int supportLane = 0;
    for (Map.Entry<String, List<Observation>> team : teams) {
      boolean objectiveTeam = objectiveTeams.contains(team.getKey());
      Role supportRole = objectiveTeam ? Role.NEUTRAL : supportRole(supportLane++);
      for (int memberIndex = 0; memberIndex < team.getValue().size(); memberIndex++) {
        Observation hunter = team.getValue().get(memberIndex);
        long nearbyObjective = hunter.objective() == null
            ? 0L
            : hunters.stream()
                .filter(other -> other.objective() != null)
                .filter(other -> other.position().distanceToSqr(hunter.objective()) <= 30.0D * 30.0D)
                .count();
        double crowding = population == 0 ? 0.0D : nearbyObjective / (double) population;
        Role role;
        if (hunter.carryingBounty()) role = Role.OBJECTIVE_RUNNER;
        else if (objectiveTeam)
          role = memberIndex == 0 ? Role.OBJECTIVE_RUNNER : Role.CONTESTER;
        else role = supportRole;
        boolean pursueObjective = (role == Role.OBJECTIVE_RUNNER || role == Role.CONTESTER)
            && objectiveReleased;
        boolean immediateContest = pursueObjective;
        boolean engageHunters = humansInDimension == 0L
            && (pursueObjective || role == Role.DELAYED_CONTESTER);
        directives.put(
            hunter.hunterId(),
            new Directive(
                role,
                pursueObjective,
                immediateContest,
                engageHunters,
                crowding,
                now + REFRESH_TICKS + 5L,
                "population=" + population
                    + ",teams=" + teams.size()
                    + ",objectiveTeams=" + objectiveTeams.size()
                    + ",humansInDimension=" + humansInDimension
                    + ",objectiveReleased=" + objectiveReleased
                    + ",releaseAt=" + (firstObservedAt + releaseDelay)
                    + ",objectiveCrowding=" + decimal(crowding)));
      }
    }
  }

  static Set<String> selectObjectiveTeams(
      UUID matchId, Map<String, Integer> teamSizes, int population) {
    if (teamSizes == null || teamSizes.isEmpty() || population <= 0) return Set.of();
    List<String> orderedTeams = teamSizes.keySet().stream()
        .sorted(Comparator.comparingLong(team -> stableTeamOrder(matchId, team)))
        .toList();
    int desiredObjectiveHunters = Math.max(1, Math.min(4, (population + 3) / 4));
    Set<String> selected = new HashSet<>();
    int assignedObjectiveHunters = 0;
    for (String team : orderedTeams) {
      if (assignedObjectiveHunters >= desiredObjectiveHunters && !selected.isEmpty()) break;
      selected.add(team);
      assignedObjectiveHunters += Math.max(1, teamSizes.getOrDefault(team, 1));
    }
    return Set.copyOf(selected);
  }

  static Role supportRole(int lane) {
    return switch (Math.floorMod(lane, 4)) {
      case 0 -> Role.INTERCEPTOR;
      case 1 -> Role.AMBUSHER;
      case 2 -> Role.OBSERVER;
      default -> Role.DELAYED_CONTESTER;
    };
  }

  static long objectiveReleaseDelay(UUID matchId) {
    long identity = stableOrder(matchId == null ? new UUID(0L, 0L) : matchId);
    return 400L + Math.floorMod(identity, 201L);
  }

  private static long stableTeamOrder(UUID matchId, String teamKey) {
    UUID stableId = UUID.nameUUIDFromBytes(
        (String.valueOf(matchId) + ':' + teamKey).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return stableOrder(stableId);
  }

  private static long stableOrder(UUID id) {
    long value = id.getMostSignificantBits() ^ Long.rotateLeft(id.getLeastSignificantBits(), 27);
    value ^= value >>> 33;
    value *= 0xff51afd7ed558ccdL;
    return value ^ (value >>> 33);
  }

  private static String decimal(double value) {
    return String.format(java.util.Locale.ROOT, "%.2f", value);
  }

  public enum Role {
    OBJECTIVE_RUNNER,
    CONTESTER,
    DELAYED_CONTESTER,
    FLANKER,
    INTERCEPTOR,
    AMBUSHER,
    OBSERVER,
    NEUTRAL
  }

  public record Directive(
      Role role,
      boolean pursueObjective,
      boolean immediateContest,
      boolean engageHunterContacts,
      double objectiveCrowding,
      long validUntil,
      String reason) {
    public Directive {
      role = role == null ? Role.NEUTRAL : role;
      objectiveCrowding = Math.max(0.0D, Math.min(1.0D, objectiveCrowding));
      reason = reason == null ? "" : reason;
    }

    public static Directive neutral(long now) {
      return new Directive(
          Role.NEUTRAL, true, true, true, 0.0D, now + REFRESH_TICKS, "unassigned");
    }
  }

  private record Observation(
      UUID hunterId,
      UUID matchId,
      String teamId,
      String dimension,
      net.minecraft.world.phys.Vec3 position,
      net.minecraft.world.phys.Vec3 objective,
      String bountyState,
      boolean carryingBounty,
      long observedAt) {}

  private record ActorObservation(
      UUID actorId,
      String dimension,
      net.minecraft.world.phys.Vec3 position,
      boolean humanParticipant,
      long observedAt) {}
}
