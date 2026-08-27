package cn.huntmc.hunterai.monitor;

import cn.huntmc.bridge.contract.BridgeProtocol;
import cn.huntmc.hunterai.entity.HunterEntity;
import cn.huntmc.hunterai.registry.HunterRegistry.ServerPressure;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.item.IGun;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Main-thread player snapshots and player/Hunter encounter state for AI-participating matches. */
public final class PlayerMatchMonitor {
  static final int REGULAR_FRAME_INTERVAL = 4;
  static final int PRE_ROLL_TICKS = 100;
  static final int ENCOUNTER_QUIET_TICKS = 200;
  private static final int MAX_LOS_TESTS_PER_TICK = 32;
  private static final double VISUAL_RANGE_SQUARED = 224.0D * 224.0D;
  private static final double HOSTILE_SOUND_RANGE_SQUARED = 160.0D * 160.0D;

  private final HunterMonitorService sink;
  private final Map<UUID, BridgeProtocol.MatchActor> roster = new HashMap<>();
  private final Map<UUID, PlayerState> players = new HashMap<>();
  private final Map<EncounterKey, EncounterState> encounters = new HashMap<>();
  private final Map<UUID, ObjectiveSet> objectives = new HashMap<>();
  private final Map<PendingDamageKey, PendingDamage> pendingDamage = new LinkedHashMap<>();
  private boolean automaticSession;
  private long rosterMissingSince = -1L;

  public PlayerMatchMonitor(HunterMonitorService sink) {
    this.sink = sink;
  }

  public void updateRoster(Collection<BridgeProtocol.MatchActor> actors, long tick) {
    List<BridgeProtocol.MatchActor> normalized =
        actors == null
            ? List.of()
            : actors.stream().filter(java.util.Objects::nonNull).toList();
    if (!hasHunter(normalized)) {
      if (!roster.isEmpty() && rosterMissingSince < 0L) rosterMissingSince = tick;
      return;
    }

    rosterMissingSince = -1L;
    sink.registerMatchActors(normalized);
    if (!sink.isEnabled() || sink.mode() != TelemetryMode.FULL_REPLAY) {
      sink.start(TelemetryMode.FULL_REPLAY);
      automaticSession = sink.isEnabled() && sink.mode() == TelemetryMode.FULL_REPLAY;
    }

    Map<UUID, BridgeProtocol.MatchActor> next = new HashMap<>();
    for (BridgeProtocol.MatchActor actor : normalized) next.put(actor.actorId(), actor);
    closeRemovedEncounters(next, tick);
    emitRosterChanges(next, tick);
    roster.clear();
    roster.putAll(next);
    players.keySet().removeIf(id -> !roster.containsKey(id));
    encounters.entrySet().removeIf(entry -> !rosterContains(entry.getKey()));
  }

  public void updateObjectives(UUID matchId, Vec3 publicObjective, Vec3 extraction) {
    if (matchId == null) return;
    if (publicObjective == null && extraction == null) objectives.remove(matchId);
    else objectives.put(matchId, new ObjectiveSet(publicObjective, extraction));
  }

  public void tick(
      MinecraftServer server,
      Collection<HunterEntity> hunters,
      long tick,
      ServerPressure pressure) {
    flushPendingDamageBefore(tick);
    if (rosterGraceExpired(rosterMissingSince, tick)) {
      finishAutomaticSession(tick, "actor-roster-empty");
      return;
    }
    if (roster.isEmpty()) return;
    if (!sink.isEnabled()) {
      sink.start(TelemetryMode.FULL_REPLAY);
      if (!sink.isEnabled()) return;
      automaticSession = true;
      sink.registerMatchActors(roster.values());
      emitInitialRoster(tick);
    }

    Map<UUID, HunterEntity> matchHunters = matchHunters(hunters);
    Map<UUID, ServerPlayer> onlinePlayers = onlinePlayers(server);
    players.values().forEach(state -> state.player = null);
    int losBudget = pressure == ServerPressure.CRITICAL ? 12 : MAX_LOS_TESTS_PER_TICK;
    List<LosPair> losPairs = new ArrayList<>();

    for (BridgeProtocol.MatchActor actor : roster.values()) {
      if (actor.kind() != BridgeProtocol.MatchActorKind.PLAYER) continue;
      ServerPlayer player = onlinePlayers.get(actor.actorId());
      if (player == null) continue;
      PlayerState state = players.computeIfAbsent(actor.actorId(), ignored -> new PlayerState());
      state.player = player;
      PlayerFrame frame = capture(player, actor, state, tick, pressure);
      state.append(frame);

      boolean inEncounter = hasActiveEncounter(actor.actorId());
      if (inEncounter || shouldRecordRegularFrame(tick)) {
        emitFrame(frame, inEncounter ? "encounter" : "regular", null, null);
      }

      for (HunterEntity hunter : matchHunters.values()) {
        BridgeProtocol.MatchActor hunterActor = roster.get(hunter.matchBotId());
        if (!hostile(actor, hunterActor) || player.level() != hunter.level()) continue;
        if (player.distanceToSqr(hunter) > VISUAL_RANGE_SQUARED) continue;
        losPairs.add(new LosPair(actor, player, hunterActor, hunter));
      }
    }
    if (!losPairs.isEmpty()) {
      int count = Math.min(losBudget, losPairs.size());
      int start = losStartIndex(tick, losPairs.size());
      for (int offset = 0; offset < count; offset++) {
        LosPair pair = losPairs.get((start + offset) % losPairs.size());
        if (pair.player.hasLineOfSight(pair.hunter) || pair.hunter.hasLineOfSight(pair.player)) {
          touchEncounter(pair.playerActor, pair.player, pair.hunterActor, pair.hunter, tick, "legal-los");
        } else {
          refreshPursuit(pair, tick);
        }
      }
    }
    endQuietEncounters(tick);
  }

  public void onGunFire(
      LivingEntity shooter, ItemStack gunStack, long tick, Collection<HunterEntity> hunters) {
    if (hunters != null) lastHunters = List.copyOf(hunters);
    BridgeProtocol.MatchActor actor = actor(shooter);
    if (actor == null || roster.isEmpty()) return;
    String weapon = gunId(gunStack);
    if (actor.kind() == BridgeProtocol.MatchActorKind.PLAYER) {
      FireTarget target = inferFireTarget(actor, shooter, tick);
      Map<String, String> payload = new LinkedHashMap<>();
      payload.put("action", "fire");
      payload.put("weapon", weapon);
      payload.put("targetInference", target == null ? "none" : target.inference);
      payload.put("targetConfidence", decimal(target == null ? 0.0D : target.confidence));
      payload.put("aimErrorDegrees", decimal(target == null ? -1.0D : target.aimErrorDegrees));
      recordPlayerEvent(
          actor,
          tick,
          shooter.level().getGameTime(),
          "PLAYER_ACTION",
          target == null ? null : actorId(target.actor),
          target == null ? null : target.encounter.id,
          payload);
    }
    touchAudibleOpponents(actor, shooter, tick, "hostile-gunfire", weapon);
  }

  public void onReload(LivingEntity entity, ItemStack gunStack, long tick) {
    BridgeProtocol.MatchActor actor = actor(entity);
    if (actor == null || actor.kind() != BridgeProtocol.MatchActorKind.PLAYER) return;
    recordPlayerEvent(actor, tick, entity.level().getGameTime(), "PLAYER_ACTION", null, null,
        Map.of("action", "reload", "weapon", gunId(gunStack)));
  }

  public void onJump(LivingEntity entity, long tick) {
    BridgeProtocol.MatchActor actor = actor(entity);
    if (actor == null || actor.kind() != BridgeProtocol.MatchActorKind.PLAYER) return;
    PlayerState state = players.computeIfAbsent(actor.actorId(), ignored -> new PlayerState());
    state.jumpAt = tick;
    recordPlayerEvent(actor, tick, entity.level().getGameTime(), "PLAYER_ACTION", null, null,
        Map.of("action", "jump"));
  }

  public void onDamage(LivingEntity victim, Entity attacker, float amount, long tick) {
    BridgeProtocol.MatchActor victimActor = actor(victim);
    BridgeProtocol.MatchActor attackerActor = actor(attacker);
    if (!hostile(victimActor, attackerActor)) return;
    EncounterState encounter = touchEncounter(victimActor, victim, attackerActor, attacker, tick, "direct-damage");
    String encounterId = encounter == null ? null : encounter.id;
    String victimId = actorId(victimActor);
    String attackerId = actorId(attackerActor);
    if (victimActor.kind() == BridgeProtocol.MatchActorKind.PLAYER) {
      accumulateDamage(
          victimActor,
          tick,
          victim.level().getGameTime(),
          "PLAYER_DAMAGE",
          attackerId,
          encounterId,
          amount,
          true);
    }
    if (attackerActor.kind() == BridgeProtocol.MatchActorKind.PLAYER) {
      accumulateDamage(
          attackerActor,
          tick,
          victim.level().getGameTime(),
          "PLAYER_HIT",
          victimId,
          encounterId,
          amount,
          false);
    }
  }

  public void onDeath(LivingEntity victim, Entity attacker, long tick) {
    BridgeProtocol.MatchActor victimActor = actor(victim);
    BridgeProtocol.MatchActor attackerActor = actor(attacker);
    if (victimActor == null || victimActor.kind() != BridgeProtocol.MatchActorKind.PLAYER) return;
    flushPendingDamageThrough(tick);
    recordPlayerEvent(victimActor, tick, victim.level().getGameTime(), "PLAYER_LIFECYCLE",
        actorId(attackerActor), encounterId(victimActor, attackerActor), Map.of("phase", "death"));
  }

  public void markManualSession() {
    automaticSession = false;
  }

  public void reset() {
    flushAllPendingDamage();
    roster.clear();
    players.clear();
    encounters.clear();
    objectives.clear();
    rosterMissingSince = -1L;
    automaticSession = false;
  }

  public int monitoredActorCount() {
    return roster.size();
  }

  private PlayerFrame capture(
      ServerPlayer player,
      BridgeProtocol.MatchActor actor,
      PlayerState state,
      long tick,
      ServerPressure pressure) {
    Vec3 position = player.position();
    Vec3 velocity = player.getDeltaMovement();
    boolean teleported = state.lastPosition != null && state.lastPosition.distanceToSqr(position) > 64.0D;
    state.lastPosition = position;
    ItemStack held = player.getMainHandItem();
    IGunOperator operator = IGunOperator.fromLivingEntity(player);
    String flags = movementFlags(player);
    String behavior =
        flags
            + ':' + player.isSprinting()
            + ':' + player.isCrouching()
            + ':' + player.getPose().name()
            + ':' + gunId(held)
            + ':' + operator.getSynIsAiming()
            + ':' + operator.getSynReloadState().getStateType().isReloading();
    boolean environmentDue = tick % 20L == 0L || !behavior.equals(state.lastBehavior);
    boolean sampledEnvironment = environmentDue && pressure != ServerPressure.CRITICAL;
    if (sampledEnvironment) {
      state.lastEnvironment = environment(player, actor);
      state.lastEnvironmentAt = tick;
    }
    Environment environment = state.lastEnvironment;
    state.lastBehavior = behavior;
    Map<String, String> payload = new LinkedHashMap<>();
    payload.put("position", vector(position));
    payload.put("velocity", vector(velocity));
    payload.put("horizontalSpeed", decimal(Math.hypot(velocity.x, velocity.z)));
    payload.put("yaw", decimal(player.getYRot()));
    payload.put("pitch", decimal(player.getXRot()));
    payload.put("pose", player.getPose().name());
    payload.put("sprinting", Boolean.toString(player.isSprinting()));
    payload.put("crouching", Boolean.toString(player.isCrouching()));
    payload.put("airborne", Boolean.toString(!player.onGround()));
    payload.put("jumped", Boolean.toString(tick - state.jumpAt <= 1L));
    payload.put("health", decimal(player.getHealth()));
    payload.put("maxHealth", decimal(player.getMaxHealth()));
    payload.put("weapon", gunId(held));
    payload.put("ads", Boolean.toString(operator.getSynIsAiming()));
    payload.put("aimProgress", decimal(operator.getSynAimingProgress()));
    payload.put("reloading", Boolean.toString(operator.getSynReloadState().getStateType().isReloading()));
    payload.put("shootCooldownMs", Long.toString(Math.max(0L, operator.getSynShootCoolDown())));
    payload.put("movementFlags", flags);
    payload.put("teleported", Boolean.toString(teleported));
    if (environment != null) {
      payload.put("hardCover", Integer.toString(environment.hardCover));
      payload.put("exposure", decimal(environment.exposure));
      payload.put("openSky", Boolean.toString(environment.openSky));
      payload.put("escapeDirections", Integer.toString(environment.escapeDirections));
      payload.put("entranceDirections", environment.entranceDirections);
      payload.put("objectiveDistance", decimal(environment.objectiveDistance));
      payload.put("environmentSampled", Boolean.toString(sampledEnvironment));
      payload.put("environmentSampleAge", Long.toString(Math.max(0L, tick - state.lastEnvironmentAt)));
    }
    payload.put("carryingBounty", Boolean.toString(actor.carryingBounty()));
    payload.put("participantState", actor.state());
    return new PlayerFrame(actor, tick, player.level().getGameTime(), Map.copyOf(payload));
  }

  private Environment environment(ServerPlayer player, BridgeProtocol.MatchActor actor) {
    ServerLevel level = player.serverLevel();
    BlockPos feet = player.blockPosition();
    int hardCover = 0;
    int escapes = 0;
    List<String> entrances = new ArrayList<>();
    int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    String[] names = {"E", "W", "S", "N"};
    for (int index = 0; index < directions.length; index++) {
      int dx = directions[index][0];
      int dz = directions[index][1];
      BlockPos adjacent = feet.offset(dx, 1, dz);
      BlockState body = level.getBlockState(adjacent);
      BlockState head = level.getBlockState(adjacent.above());
      if (body.blocksMotion() || head.blocksMotion()) hardCover++;
      BlockPos step = feet.offset(dx, 0, dz);
      if (level.getBlockState(step).getCollisionShape(level, step).isEmpty()
          && level.getBlockState(step.above()).getCollisionShape(level, step.above()).isEmpty()
          && !level.getBlockState(step.below()).getCollisionShape(level, step.below()).isEmpty()) {
        escapes++;
        entrances.add(names[index]);
      }
    }
    ObjectiveSet set = objectives.get(actor.matchId());
    Vec3 objective =
        set == null ? null : actor.carryingBounty() ? set.extraction : set.publicObjective;
    return new Environment(
        hardCover,
        1.0D - hardCover / 4.0D,
        level.canSeeSky(feet.above()),
        escapes,
        String.join(",", entrances),
        objective == null ? -1.0D : player.position().distanceTo(objective));
  }

  private void touchAudibleOpponents(
      BridgeProtocol.MatchActor sourceActor, LivingEntity source, long tick, String evidence, String weapon) {
    if (sourceActor.kind() == BridgeProtocol.MatchActorKind.PLAYER) {
      for (HunterEntity hunter : currentHunters()) {
        BridgeProtocol.MatchActor hunterActor = roster.get(hunter.matchBotId());
        if (!hostile(sourceActor, hunterActor)
            || source.level() != hunter.level()
            || source.distanceToSqr(hunter) > HOSTILE_SOUND_RANGE_SQUARED) continue;
        EncounterState encounter = touchEncounter(sourceActor, source, hunterActor, hunter, tick, evidence);
        if (encounter != null) encounter.weaponEvidence = weapon;
      }
      return;
    }
    for (PlayerState state : players.values()) {
      if (state.lastFrame == null) continue;
      BridgeProtocol.MatchActor playerActor = state.lastFrame.actor;
      if (!hostile(sourceActor, playerActor)) continue;
      ServerPlayer player = findOnlinePlayer(playerActor.actorId());
      if (player == null || player.level() != source.level()
          || player.distanceToSqr(source) > HOSTILE_SOUND_RANGE_SQUARED) continue;
      EncounterState encounter = touchEncounter(playerActor, player, sourceActor, source, tick, evidence);
      if (encounter != null) encounter.weaponEvidence = weapon;
    }
  }

  private EncounterState touchEncounter(
      BridgeProtocol.MatchActor firstActor,
      Entity first,
      BridgeProtocol.MatchActor secondActor,
      Entity second,
      long tick,
      String evidence) {
    if (!hostile(firstActor, secondActor)) return null;
    BridgeProtocol.MatchActor playerActor =
        firstActor.kind() == BridgeProtocol.MatchActorKind.PLAYER ? firstActor : secondActor;
    BridgeProtocol.MatchActor hunterActor =
        firstActor.kind() == BridgeProtocol.MatchActorKind.HUNTER ? firstActor : secondActor;
    Entity playerEntity = firstActor.kind() == BridgeProtocol.MatchActorKind.PLAYER ? first : second;
    Entity hunterEntity = firstActor.kind() == BridgeProtocol.MatchActorKind.HUNTER ? first : second;
    EncounterKey key = new EncounterKey(playerActor.actorId(), hunterActor.actorId(), playerActor.matchId());
    EncounterState encounter = encounters.get(key);
    if (encounter == null) {
      encounter = new EncounterState("enc-" + UUID.randomUUID().toString().substring(0, 12), tick);
      encounters.put(key, encounter);
      PlayerState state = players.get(playerActor.actorId());
      if (state != null) {
        for (PlayerFrame frame : state.frames) emitFrame(frame, "pre-roll", actorId(hunterActor), encounter.id);
      }
      recordPlayerEvent(
          playerActor,
          tick,
          playerEntity.level().getGameTime(),
          "ENCOUNTER",
          actorId(hunterActor),
          encounter.id,
          Map.of(
              "phase", "start",
              "evidence", evidence,
              "distance", decimal(playerEntity.distanceTo(hunterEntity))));
    }
    encounter.lastEvidenceTick = tick;
    encounter.lastEvidence = evidence;
    encounter.lastDistance = playerEntity.distanceTo(hunterEntity);
    return encounter;
  }

  private void refreshPursuit(LosPair pair, long tick) {
    EncounterKey key =
        new EncounterKey(
            pair.playerActor.actorId(), pair.hunterActor.actorId(), pair.playerActor.matchId());
    EncounterState encounter = encounters.get(key);
    if (encounter == null) return;
    double distance = pair.player.distanceTo(pair.hunter);
    Vec3 playerToHunter = pair.hunter.position()
        .subtract(pair.player.position())
        .multiply(1.0D, 0.0D, 1.0D);
    double playerApproach = 0.0D;
    double hunterApproach = 0.0D;
    if (playerToHunter.horizontalDistanceSqr() >= 1.0E-6D) {
      Vec3 direction = playerToHunter.normalize();
      playerApproach = pair.player.getDeltaMovement()
          .multiply(1.0D, 0.0D, 1.0D)
          .dot(direction);
      hunterApproach = pair.hunter.getDeltaMovement()
          .multiply(1.0D, 0.0D, 1.0D)
          .dot(direction.scale(-1.0D));
    }
    if (isDeliberatePursuit(
        distance,
        encounter.lastDistance - distance,
        playerApproach,
        hunterApproach)) {
      encounter.lastEvidenceTick = tick;
      encounter.lastEvidence = "proximity-pursuit";
    }
    encounter.lastDistance = distance;
  }

  private void endQuietEncounters(long tick) {
    for (Map.Entry<EncounterKey, EncounterState> entry : List.copyOf(encounters.entrySet())) {
      EncounterState encounter = entry.getValue();
      if (!encounterExpired(encounter.lastEvidenceTick, tick)) continue;
      BridgeProtocol.MatchActor playerActor = roster.get(entry.getKey().playerId);
      if (playerActor != null) {
        recordPlayerEvent(
            playerActor,
            tick,
            latestGameTime(entry.getKey().playerId),
            "ENCOUNTER",
            actorId(roster.get(entry.getKey().hunterId)),
            encounter.id,
            Map.of(
                "phase", "end",
                "reason", "quiet-timeout",
                "durationTicks", Long.toString(tick - encounter.startedAt),
                "lastEvidence", encounter.lastEvidence,
                "weaponEvidence", encounter.weaponEvidence));
      }
      encounters.remove(entry.getKey());
    }
  }

  private void emitFrame(
      PlayerFrame frame, String sampleMode, String counterpartActorId, String encounterId) {
    Map<String, String> payload = new LinkedHashMap<>(frame.payload);
    payload.put("sampleMode", sampleMode);
    recordPlayerEvent(
        frame.actor,
        frame.sessionTick,
        frame.gameTime,
        "PLAYER_FRAME",
        counterpartActorId,
        encounterId,
        payload);
  }

  private void recordPlayerEvent(
      BridgeProtocol.MatchActor actor,
      long tick,
      long gameTime,
      String type,
      String counterpart,
      String encounterId,
      Map<String, String> payload) {
    sink.recordPlayer(
        actor.actorId(), actor.matchId(), actor.teamId(), tick, gameTime, type, counterpart,
        encounterId, payload);
  }

  private void emitRosterChanges(Map<UUID, BridgeProtocol.MatchActor> next, long tick) {
    for (BridgeProtocol.MatchActor actor : next.values()) {
      BridgeProtocol.MatchActor previous = roster.get(actor.actorId());
      if (actor.kind() != BridgeProtocol.MatchActorKind.PLAYER || actor.equals(previous)) continue;
      recordPlayerEvent(
          actor,
          tick,
          latestGameTime(actor.actorId()),
          "PLAYER_LIFECYCLE",
          null,
          null,
          Map.of(
              "phase", previous == null ? "joined-monitor" : "state-changed",
              "participantState", actor.state(),
              "carryingBounty", Boolean.toString(actor.carryingBounty())));
    }
    for (BridgeProtocol.MatchActor previous : roster.values()) {
      if (previous.kind() != BridgeProtocol.MatchActorKind.PLAYER || next.containsKey(previous.actorId())) continue;
      recordPlayerEvent(previous, tick, latestGameTime(previous.actorId()), "PLAYER_LIFECYCLE", null,
          null, Map.of("phase", "left-monitor", "participantState", previous.state()));
    }
  }

  private void emitInitialRoster(long tick) {
    for (BridgeProtocol.MatchActor actor : roster.values()) {
      if (actor.kind() != BridgeProtocol.MatchActorKind.PLAYER) continue;
      recordPlayerEvent(
          actor,
          tick,
          latestGameTime(actor.actorId()),
          "PLAYER_LIFECYCLE",
          null,
          null,
          Map.of(
              "phase", "joined-monitor",
              "participantState", actor.state(),
              "carryingBounty", Boolean.toString(actor.carryingBounty())));
    }
  }

  private void closeRemovedEncounters(
      Map<UUID, BridgeProtocol.MatchActor> next, long tick) {
    for (Map.Entry<EncounterKey, EncounterState> entry : List.copyOf(encounters.entrySet())) {
      if (next.containsKey(entry.getKey().playerId) && next.containsKey(entry.getKey().hunterId)) continue;
      BridgeProtocol.MatchActor playerActor = roster.get(entry.getKey().playerId);
      if (playerActor != null) {
        recordPlayerEvent(
            playerActor,
            tick,
            latestGameTime(playerActor.actorId()),
            "ENCOUNTER",
            actorId(roster.get(entry.getKey().hunterId)),
            entry.getValue().id,
            Map.of("phase", "end", "reason", "actor-left-roster"));
      }
      encounters.remove(entry.getKey());
    }
  }

  private void finishAutomaticSession(long tick, String reason) {
    flushPendingDamageThrough(tick);
    for (Map.Entry<EncounterKey, EncounterState> entry : List.copyOf(encounters.entrySet())) {
      BridgeProtocol.MatchActor actor = roster.get(entry.getKey().playerId);
      if (actor != null) {
        recordPlayerEvent(actor, tick, latestGameTime(actor.actorId()), "ENCOUNTER",
            actorId(roster.get(entry.getKey().hunterId)), entry.getValue().id,
            Map.of("phase", "end", "reason", reason));
      }
    }
    if (automaticSession) sink.stop();
    reset();
  }

  private FireTarget inferFireTarget(
      BridgeProtocol.MatchActor playerActor, LivingEntity shooter, long tick) {
    Vec3 eye = shooter.getEyePosition();
    Vec3 view = shooter.getViewVector(1.0F).normalize();
    BridgeProtocol.MatchActor bestActor = null;
    HunterEntity bestHunter = null;
    double bestError = Double.POSITIVE_INFINITY;
    for (HunterEntity hunter : currentHunters()) {
      BridgeProtocol.MatchActor hunterActor = roster.get(hunter.matchBotId());
      if (!hostile(playerActor, hunterActor)
          || shooter.level() != hunter.level()
          || shooter.distanceToSqr(hunter) > VISUAL_RANGE_SQUARED) continue;
      boolean shooterLineOfSight = shooter.hasLineOfSight(hunter);
      Vec3 direction = hunter.getEyePosition().subtract(eye);
      if (direction.lengthSqr() < 1.0E-8D) continue;
      double dot = Math.max(-1.0D, Math.min(1.0D, view.dot(direction.normalize())));
      double error = Math.toDegrees(Math.acos(dot));
      if (!isLegalFireInference(shooterLineOfSight, error) || error >= bestError) continue;
      bestActor = hunterActor;
      bestHunter = hunter;
      bestError = error;
    }
    if (bestActor == null || bestHunter == null) return null;
    EncounterState encounter =
        touchEncounter(playerActor, shooter, bestActor, bestHunter, tick, "aimed-gunfire");
    if (encounter == null) return null;
    double confidence = Math.max(0.55D, Math.min(0.98D, 1.0D - bestError / 30.0D));
    return new FireTarget(
        bestActor, encounter, bestError, confidence, "legal-los-aim-cone");
  }

  private void accumulateDamage(
      BridgeProtocol.MatchActor actor,
      long tick,
      long gameTime,
      String type,
      String counterpart,
      String encounterId,
      float amount,
      boolean directionKnown) {
    PendingDamageKey key =
        new PendingDamageKey(actor.actorId(), counterpart, tick, type);
    PendingDamage pending = pendingDamage.get(key);
    if (pending == null) {
      pending = new PendingDamage(actor, gameTime, encounterId, directionKnown);
      pendingDamage.put(key, pending);
    }
    pending.amount += Math.max(0.0F, amount);
    pending.componentCount++;
  }

  private void flushPendingDamageBefore(long tick) {
    flushPendingDamage(tick, false);
  }

  private void flushPendingDamageThrough(long tick) {
    flushPendingDamage(tick, true);
  }

  private void flushAllPendingDamage() {
    flushPendingDamage(Long.MAX_VALUE, true);
  }

  private void flushPendingDamage(long boundary, boolean inclusive) {
    for (Map.Entry<PendingDamageKey, PendingDamage> entry : List.copyOf(pendingDamage.entrySet())) {
      PendingDamageKey key = entry.getKey();
      if (key.tick > boundary || !inclusive && key.tick == boundary) continue;
      PendingDamage value = entry.getValue();
      Map<String, String> payload = new LinkedHashMap<>();
      payload.put("amount", decimal(value.amount));
      payload.put("amountTotal", decimal(value.amount));
      payload.put("componentCount", Integer.toString(value.componentCount));
      if (value.directionKnown) payload.put("directionConfidence", "1.0");
      recordPlayerEvent(
          value.actor,
          key.tick,
          value.gameTime,
          key.type,
          key.counterpart,
          value.encounterId,
          payload);
      pendingDamage.remove(key);
    }
  }

  private Map<UUID, HunterEntity> matchHunters(Collection<HunterEntity> hunters) {
    Map<UUID, HunterEntity> result = new HashMap<>();
    if (hunters != null) {
      for (HunterEntity hunter : hunters) {
        if (hunter != null && hunter.matchBotId() != null && roster.containsKey(hunter.matchBotId())) {
          result.put(hunter.matchBotId(), hunter);
        }
      }
    }
    lastHunters = List.copyOf(result.values());
    return result;
  }

  private List<HunterEntity> lastHunters = List.of();

  private List<HunterEntity> currentHunters() {
    return lastHunters;
  }

  private Map<UUID, ServerPlayer> onlinePlayers(MinecraftServer server) {
    Map<UUID, ServerPlayer> result = new HashMap<>();
    for (ServerPlayer player : server.getPlayerList().getPlayers()) result.put(player.getUUID(), player);
    return result;
  }

  private ServerPlayer findOnlinePlayer(UUID id) {
    PlayerState state = players.get(id);
    return state == null ? null : state.player;
  }

  private BridgeProtocol.MatchActor actor(Entity entity) {
    if (entity instanceof HunterEntity hunter) return roster.get(hunter.matchBotId());
    return entity == null ? null : roster.get(entity.getUUID());
  }

  private static boolean hostile(
      BridgeProtocol.MatchActor first, BridgeProtocol.MatchActor second) {
    return first != null
        && second != null
        && first.matchId().equals(second.matchId())
        && first.kind() != second.kind()
        && (first.teamId().isBlank() || second.teamId().isBlank() || !first.teamId().equals(second.teamId()));
  }

  private boolean rosterContains(EncounterKey key) {
    return roster.containsKey(key.playerId) && roster.containsKey(key.hunterId);
  }

  private boolean hasActiveEncounter(UUID playerId) {
    return encounters.keySet().stream().anyMatch(key -> key.playerId.equals(playerId));
  }

  private String encounterId(
      BridgeProtocol.MatchActor first, BridgeProtocol.MatchActor second) {
    if (!hostile(first, second)) return null;
    UUID player = first.kind() == BridgeProtocol.MatchActorKind.PLAYER ? first.actorId() : second.actorId();
    UUID hunter = first.kind() == BridgeProtocol.MatchActorKind.HUNTER ? first.actorId() : second.actorId();
    EncounterState state = encounters.get(new EncounterKey(player, hunter, first.matchId()));
    return state == null ? null : state.id;
  }

  private String actorId(BridgeProtocol.MatchActor actor) {
    if (actor == null) return null;
    if (actor.kind() == BridgeProtocol.MatchActorKind.PLAYER) return sink.playerActorId(actor.actorId());
    HunterEntity hunter = lastHunters.stream()
        .filter(value -> actor.actorId().equals(value.matchBotId()))
        .findFirst().orElse(null);
    return hunter == null || hunter.monitorId() <= 0
        ? "hunter-seat-" + Integer.toUnsignedString(actor.actorId().hashCode(), 36)
        : "hunter-" + hunter.monitorId();
  }

  private long latestGameTime(UUID playerId) {
    PlayerState state = players.get(playerId);
    return state == null || state.lastFrame == null ? 0L : state.lastFrame.gameTime;
  }

  private static String gunId(ItemStack stack) {
    IGun gun = stack == null ? null : IGun.getIGunOrNull(stack);
    return gun == null ? "none" : gun.getGunId(stack).toString();
  }

  private static String movementFlags(ServerPlayer player) {
    List<String> flags = new ArrayList<>();
    if (player.isPassenger()) flags.add("vehicle");
    if (player.isInWaterOrBubble()) flags.add("water");
    if (player.onClimbable()) flags.add("ladder");
    if (player.isFallFlying()) flags.add("fall-flying");
    if (player.fallDistance > 1.5F) flags.add("falling");
    if (player.isCreative()) flags.add("creative");
    if (player.isSpectator()) flags.add("spectator");
    if (player.hurtTime > 0) flags.add("knockback-window");
    return flags.isEmpty() ? "normal" : String.join(",", flags);
  }

  private static String vector(Vec3 value) {
    return "(" + decimal(value.x) + "," + decimal(value.y) + "," + decimal(value.z) + ")";
  }

  private static String decimal(double value) {
    return String.format(java.util.Locale.ROOT, "%.3f", value);
  }

  static boolean shouldRecordRegularFrame(long tick) {
    return tick % REGULAR_FRAME_INTERVAL == 0L;
  }

  static boolean encounterExpired(long lastEvidenceTick, long tick) {
    return tick - lastEvidenceTick >= ENCOUNTER_QUIET_TICKS;
  }

  static boolean isDeliberatePursuit(
      double distance, double closingDistance, double playerApproach, double hunterApproach) {
    return distance <= 64.0D
        && closingDistance >= 0.05D
        && Math.max(playerApproach, hunterApproach) >= 0.06D;
  }

  static int losStartIndex(long tick, int pairCount) {
    return pairCount <= 0 ? 0 : (int) Math.floorMod(tick, (long) pairCount);
  }

  static int preRollStartIndex(int frameCount) {
    return Math.max(0, frameCount - PRE_ROLL_TICKS);
  }

  static boolean hasHunter(Collection<BridgeProtocol.MatchActor> actors) {
    return actors != null
        && actors.stream().anyMatch(actor ->
            actor != null && actor.kind() == BridgeProtocol.MatchActorKind.HUNTER);
  }

  static boolean rosterGraceExpired(long missingSince, long tick) {
    return missingSince >= 0L && tick - missingSince >= 40L;
  }

  static boolean isLegalFireInference(boolean shooterLineOfSight, double aimErrorDegrees) {
    return shooterLineOfSight
        && Double.isFinite(aimErrorDegrees)
        && aimErrorDegrees >= 0.0D
        && aimErrorDegrees <= 22.0D;
  }

  private static final class PlayerState {
    private final Deque<PlayerFrame> frames = new ArrayDeque<>();
    private PlayerFrame lastFrame;
    private ServerPlayer player;
    private Vec3 lastPosition;
    private String lastBehavior = "";
    private long jumpAt = Long.MIN_VALUE;
    private Environment lastEnvironment;
    private long lastEnvironmentAt = Long.MIN_VALUE;

    private void append(PlayerFrame frame) {
      lastFrame = frame;
      frames.addLast(frame);
      while (frames.size() > PRE_ROLL_TICKS) {
        frames.removeFirst();
      }
    }
  }

  private record PlayerFrame(
      BridgeProtocol.MatchActor actor, long sessionTick, long gameTime, Map<String, String> payload) {}

  private record EncounterKey(UUID playerId, UUID hunterId, UUID matchId) {}

  private record LosPair(
      BridgeProtocol.MatchActor playerActor,
      ServerPlayer player,
      BridgeProtocol.MatchActor hunterActor,
      HunterEntity hunter) {}

  private record FireTarget(
      BridgeProtocol.MatchActor actor,
      EncounterState encounter,
      double aimErrorDegrees,
      double confidence,
      String inference) {}

  private record PendingDamageKey(UUID playerActorId, String counterpart, long tick, String type) {}

  private static final class PendingDamage {
    private final BridgeProtocol.MatchActor actor;
    private final long gameTime;
    private final String encounterId;
    private final boolean directionKnown;
    private float amount;
    private int componentCount;

    private PendingDamage(
        BridgeProtocol.MatchActor actor,
        long gameTime,
        String encounterId,
        boolean directionKnown) {
      this.actor = actor;
      this.gameTime = gameTime;
      this.encounterId = encounterId;
      this.directionKnown = directionKnown;
    }
  }

  private static final class EncounterState {
    private final String id;
    private final long startedAt;
    private long lastEvidenceTick;
    private double lastDistance = Double.POSITIVE_INFINITY;
    private String lastEvidence = "unknown";
    private String weaponEvidence = "none";

    private EncounterState(String id, long startedAt) {
      this.id = id;
      this.startedAt = startedAt;
      this.lastEvidenceTick = startedAt;
    }
  }

  private record Environment(
      int hardCover,
      double exposure,
      boolean openSky,
      int escapeDirections,
      String entranceDirections,
        double objectiveDistance) {}

  private record ObjectiveSet(Vec3 publicObjective, Vec3 extraction) {}
}
