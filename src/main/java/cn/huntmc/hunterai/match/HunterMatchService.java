package cn.huntmc.hunterai.match;

import cn.huntmc.bridge.contract.BridgeProtocol;
import cn.huntmc.bridgeforge.HuntMcBukkitBridge;
import cn.huntmc.hunterai.HuntMcHunterAi;
import cn.huntmc.hunterai.action.InteractionCommand;
import cn.huntmc.hunterai.entity.HunterAiEntities;
import cn.huntmc.hunterai.entity.HunterEntity;
import cn.huntmc.hunterai.profile.HunterProfile;
import cn.huntmc.hunterai.registry.HunterRegistry;
import com.mojang.logging.LogUtils;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.world.ForgeChunkManager;
import org.slf4j.Logger;

/**
 * Supervises Bukkit-owned match seats and publishes immutable public facts to HunterCoordinator.
 * This service deliberately contains no navigation, view, posture, weapon, or movement control.
 */
public final class HunterMatchService {
  private static final Logger LOG = LogUtils.getLogger();
  private static final long SLOT_POLL_INTERVAL_TICKS = 20L;
  private static final long STATE_POLL_INTERVAL_TICKS = 12L;
  private static final long ACTION_RETRY_TICKS = 10L;
  private static final long INTERACTION_PENDING_TIMEOUT_TICKS = 100L;
  private static final long FAILURE_LOG_INTERVAL_TICKS = 100L;
  private static final int MATCH_CHUNK_TICKET_RADIUS = 1;

  private final HunterRegistry registry;
  private final Map<UUID, HunterEntity> huntersByBot = new HashMap<>();
  private final Map<UUID, Runtime> runtimes = new HashMap<>();
  private final Map<UUID, UUID> observerTargets = new HashMap<>();
  private long nextSlotPollAt;
  private long nextFailureLogAt;

  public HunterMatchService(HunterRegistry registry) {
    this.registry = registry;
  }

  /** Runs on the Forge server thread and reconciles physical entities with Bukkit seats. */
  public void tickServer(long gameTime) {
    if (gameTime < nextSlotPollAt) return;
    nextSlotPollAt = gameTime + SLOT_POLL_INTERVAL_TICKS;
    BridgeProtocol.Response response;
    try {
      response = HuntMcBukkitBridge.hunterMatchBots(standaloneHunterScanTargets());
    } catch (LinkageError | RuntimeException exception) {
      reportFailure(gameTime, "MATCH_BOTS request threw " + describe(exception), exception);
      return;
    }
    if (!response.accepted() || response.state() == null) {
      reportFailure(
          gameTime,
          response.state() == null
              ? "MATCH_BOTS response did not include state: " + response.reason()
              : "MATCH_BOTS rejected: " + response.reason(),
          null);
      return;
    }

    BridgeProtocol.State state = response.state();
    registry.updateMatchActors(response.matchActors(), gameTime);
    Set<UUID> expected = new HashSet<>();
    for (BridgeProtocol.MatchBot slot : state.matchBots()) {
      expected.add(slot.botId());
      Runtime runtime = runtimes.computeIfAbsent(slot.botId(), ignored -> new Runtime(slot));
      runtime.slot = slot;
      HunterEntity hunter = resolveHunter(slot);
      if (hunter == null) hunter = spawn(slot, gameTime);
      if (hunter == null) continue;
      hunter.assignMatchBot(slot.botId(), slot.matchId(), slot.teamId());
      hunter.configureMatchLoadout(slot.weaponIds(), slot.loadoutAssigned());
      huntersByBot.put(slot.botId(), hunter);
      maintainMatchChunkTickets(hunter, runtime, gameTime);
      bindIfNeeded(hunter, runtime, gameTime);
      if (runtime.bound && gameTime >= runtime.nextStatePollAt) refreshState(hunter, runtime, gameTime);
      updateAllies(hunter, runtime);
      runtime.directive = buildDirective(hunter, runtime, gameTime);
      registry.updateMatchObjectives(
          slot.matchId(), runtime.directive.publicObjective(), runtime.directive.extraction());
      traceLifecycleIfChanged(hunter, runtime, gameTime);
    }
    syncObserverCameras(state.matchObservers());
    for (UUID botId : Set.copyOf(huntersByBot.keySet())) {
      if (expected.contains(botId)) continue;
      HunterEntity hunter = huntersByBot.remove(botId);
      Runtime runtime = runtimes.remove(botId);
      releaseMatchChunkTickets(runtime);
      if (hunter != null && !hunter.isRemoved()) {
        registry.recordMonitor(hunter, gameTime, "LIFECYCLE", "phase=seat-revoked");
        registry.invalidateKnownTarget(hunter, "match-seat-revoked");
        hunter.markRemovalReason("match-seat-revoked");
        hunter.clearMatchBot();
        hunter.discard();
      }
    }
  }

  /** Returns the only match input available to the coordinator. */
  public MatchDirective directiveFor(HunterEntity hunter) {
    long now = registry.serverTick();
    if (!hunter.isMatchBot()) return MatchDirective.standalone(now);
    Runtime runtime = runtimes.get(hunter.matchBotId());
    if (runtime == null || huntersByBot.get(hunter.matchBotId()) != hunter) {
      hunter.setMatchActive(false);
      return MatchDirective.unavailable(hunter.matchBotId(), hunter.matchId(), hunter.matchTeamId(), now);
    }
    maintainMatchChunkTickets(hunter, runtime, now);
    if (runtime.bound && now >= runtime.nextStatePollAt) refreshState(hunter, runtime, now);
    updateAllies(hunter, runtime);
    runtime.directive = buildDirective(hunter, runtime, now);
    hunter.setMatchActive(runtime.directive.active());
    return runtime.directive;
  }

  /** EntityActuator is the sole caller; Bukkit remains authoritative for range and phase checks. */
  public String submitInteraction(HunterEntity hunter, InteractionCommand command) {
    if (!hunter.isMatchBot() || command == null || command == InteractionCommand.NONE) return "not-applicable";
    Runtime runtime = runtimes.get(hunter.matchBotId());
    long now = registry.serverTick();
    if (runtime != null
        && command == InteractionCommand.EXTRACT
        && "EXTRACT:accepted".equals(runtime.lastInteraction)) return "in-progress";
    if (runtime == null || !runtime.bound || now < runtime.nextActionAt) return "cooldown";
    if (runtime.directive == null || !runtime.directive.active()) {
      clearPendingInteraction(runtime);
      runtime.lastInteraction = command + ":rejected:match-not-active";
      return "rejected:match-not-active";
    }
    if (runtime.pendingInteractionCommand != null
        && runtime.pendingInteractionCommand != command) clearPendingInteraction(runtime);
    if (interactionTimedOut(runtime.pendingInteractionStartedAt, now)) {
      runtime.nextStatePollAt = now;
      refreshState(hunter, runtime, now);
      runtime.directive = buildDirective(hunter, runtime, now);
      if (interactionSatisfied(command, runtime)) {
        clearPendingInteraction(runtime);
        runtime.lastInteraction = command + ":accepted:state-confirmed";
        return "accepted";
      }
      clearPendingInteraction(runtime);
      runtime.lastInteraction = command + ":rejected:interaction-timeout";
      return "rejected:interaction-timeout";
    }
    UUID interactionTarget = command == InteractionCommand.REVIVE
        ? nearestReviveTarget(hunter, runtime.directive)
        : null;
    if (command == InteractionCommand.REVIVE && interactionTarget == null) {
      runtime.lastInteraction = "REVIVE:target-unavailable";
      return "target-unavailable";
    }
    runtime.nextActionAt = now + ACTION_RETRY_TICKS;
    try {
      BridgeProtocol.Response response =
          HuntMcBukkitBridge.hunterAction(
              hunter.matchBotId(),
              command == InteractionCommand.REVIVE
                  ? "huntmc_hunter_ai:revive"
                  : "huntmc_hunter_ai:interact",
              hunter.getUUID(),
              interactionTarget,
              position(hunter, runtime));
      if (!response.accepted()) {
        runtime.lastInteraction = command + ":rejected:" + response.reason();
        if (invalidatesBinding(response.reason())) {
          clearPendingInteraction(runtime);
          runtime.bound = false;
          runtime.state = null;
          runtime.nextStatePollAt = now;
          runtime.nextActionAt = now;
          runtime.directive = MatchDirective.unavailable(
              hunter.matchBotId(), hunter.matchId(), hunter.matchTeamId(), now);
          registry.recordMonitor(
              hunter,
              now,
              "LIFECYCLE",
              "phase=seat-desynchronized interaction=" + command + " reason=" + response.reason());
        } else if (requiresStateRefreshAfterRejection(command, response.reason())) {
          runtime.nextStatePollAt = now;
          refreshState(hunter, runtime, now);
          runtime.directive = buildDirective(hunter, runtime, now);
        }
        return "rejected:" + response.reason();
      }
      boolean completed = "COMPLETED".equals(response.reason());
      if (completed && command == InteractionCommand.CLAIM_BOUNTY) runtime.carrying = true;
      if (response.state() != null) {
        runtime.state = response.state();
        runtime.carrying = response.state().carryingBounty();
      }
      runtime.lastInteraction = command + (completed ? ":accepted" : ":in-progress");
      if (!completed) {
        if (runtime.pendingInteractionCommand != command) {
          runtime.pendingInteractionCommand = command;
          runtime.pendingInteractionStartedAt = now;
          runtime.pendingInteractionAttempt++;
        }
        runtime.pendingInteractionLastProgressAt = now;
        runtime.nextStatePollAt = now;
        refreshState(hunter, runtime, now);
        runtime.directive = buildDirective(hunter, runtime, now);
        if (interactionSatisfied(command, runtime)) {
          clearPendingInteraction(runtime);
          runtime.lastInteraction = command + ":accepted:state-confirmed";
          return "accepted";
        }
        return "in-progress:attempt=" + runtime.pendingInteractionAttempt
            + ";pendingTicks=" + Math.max(0L, now - runtime.pendingInteractionStartedAt)
            + ";lastProgressAt=" + runtime.pendingInteractionLastProgressAt;
      }
      clearPendingInteraction(runtime);
      // The action response deliberately carries no mutable match state. Poll it immediately after
      // completion so the coordinator cannot continue acting on an UNBANISHED/AVAILABLE snapshot.
      runtime.nextStatePollAt = now;
      refreshState(hunter, runtime, now);
      runtime.directive = buildDirective(hunter, runtime, now);
      registry.recordMonitor(
          hunter, now, "LIFECYCLE", "phase=interaction command=" + command + " result=accepted");
      return "accepted";
    } catch (LinkageError | RuntimeException exception) {
      runtime.lastInteraction = command + ":error:" + describe(exception);
      reportFailure(now, "Hunter interaction threw for bot=" + shortId(hunter.matchBotId()), exception);
      return "error:" + describe(exception);
    }
  }

  /** Bounded death report; Bukkit decides whether the seat was active and valid. */
  public String reportDeath(HunterEntity hunter, UUID killerParticipantId) {
    if (!hunter.isMatchBot()) return "NOT_MATCH_BOT";
    try {
      BridgeProtocol.Response response = HuntMcBukkitBridge.hunterAction(
          hunter.matchBotId(),
          "huntmc_hunter_ai:eliminated",
          hunter.getUUID(),
          killerParticipantId,
          position(hunter, runtimes.get(hunter.matchBotId())));
      String result = response.accepted() ? response.reason() : "REJECTED";
      registry.recordMonitor(
          hunter,
          registry.serverTick(),
          "LIFECYCLE",
          "phase=death result=" + result + " killer=" + killerParticipantId);
      return result;
    } catch (LinkageError | RuntimeException exception) {
      LOG.warn("Unable to report Hunter death for bot={}: {}", shortId(hunter.matchBotId()), describe(exception));
      return "ERROR";
    }
  }

  public void reportDeath(HunterEntity hunter) {
    reportDeath(hunter, null);
  }

  public void forget(HunterEntity hunter) {
    if (!hunter.isMatchBot()) return;
    UUID botId = hunter.matchBotId();
    releaseMatchChunkTickets(runtimes.get(botId));
    huntersByBot.remove(botId, hunter);
  }

  public void shutdown() {
    for (Runtime runtime : runtimes.values()) releaseMatchChunkTickets(runtime);
    restoreObserverCameras();
    huntersByBot.clear();
    runtimes.clear();
    observerTargets.clear();
  }

  public List<String> reviewLines() {
    if (runtimes.isEmpty()) return List.of("No active HuntMC Hunter match seats.");
    return runtimes.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(
            entry -> {
              Runtime runtime = entry.getValue();
              HunterEntity hunter = huntersByBot.get(entry.getKey());
              MatchDirective directive = runtime.directive;
              return (hunter == null ? "unbound" : "#" + hunter.monitorId())
                  + " bot=" + shortId(entry.getKey())
                  + " match=" + shortId(runtime.slot.matchId())
                  + " phase=" + (directive == null ? "unknown" : directive.phase())
                  + " bounty=" + (directive == null ? "unknown" : directive.bountyState())
                  + " carrying=" + runtime.carrying
                  + " interaction=" + runtime.lastInteraction;
            })
        .toList();
  }

  private void bindIfNeeded(HunterEntity hunter, Runtime runtime, long now) {
    boolean slotBound = runtime.slot.entityId() != null && runtime.slot.entityId().equals(hunter.getUUID());
    if (runtime.bound && slotBound) return;
    try {
      BridgeProtocol.Response bind =
          HuntMcBukkitBridge.hunterAction(
              runtime.slot.botId(),
              "huntmc_hunter_ai:bind",
              hunter.getUUID(),
              null,
              position(hunter, runtime));
      runtime.bound = bind.accepted();
      if (!bind.accepted())
        reportFailure(now, "Hunter bind rejected for bot=" + shortId(runtime.slot.botId()) + ": " + bind.reason(), null);
    } catch (LinkageError | RuntimeException exception) {
      runtime.bound = false;
      reportFailure(now, "Hunter bind threw for bot=" + shortId(runtime.slot.botId()), exception);
    }
  }

  private boolean refreshState(HunterEntity hunter, Runtime runtime, long now) {
    runtime.nextStatePollAt = now + STATE_POLL_INTERVAL_TICKS;
    try {
      BridgeProtocol.Response response =
          HuntMcBukkitBridge.hunterState(hunter.matchBotId(), hunter.getUUID(), position(hunter, runtime));
      if (!response.accepted() || response.state() == null) {
        runtime.state = null;
        runtime.bound = false;
        reportFailure(
            now,
            "Hunter state rejected for bot=" + shortId(hunter.matchBotId()) + ": " + response.reason(),
            null);
        return false;
      }
      if (!hunter.matchId().equals(response.state().matchId())) {
        runtime.state = null;
        return false;
      }
      runtime.state = response.state();
      runtime.carrying = response.state().carryingBounty();
      return true;
    } catch (LinkageError | RuntimeException exception) {
      runtime.state = null;
      reportFailure(now, "Hunter state threw for bot=" + shortId(hunter.matchBotId()), exception);
      return false;
    }
  }

  private static MatchDirective buildDirective(HunterEntity hunter, Runtime runtime, long now) {
    BridgeProtocol.State state = runtime.state;
    if (!runtime.bound || state == null || hunter.matchId() == null || !hunter.matchId().equals(state.matchId()))
      return MatchDirective.unavailable(hunter.matchBotId(), hunter.matchId(), hunter.matchTeamId(), now);
    boolean active = "ACTIVE".equals(state.phase());
    Vec3 objective = runtime.carrying ? nearestExtraction(hunter, state) : objective(state);
    Vec3 extraction = nearestExtraction(hunter, state);
    List<Vec3> extractions = state.extractions().stream()
        .filter(point -> point != null)
        .map(point -> new Vec3(point.x(), point.y(), point.z()))
        .toList();
    return new MatchDirective(
        hunter.matchBotId(), hunter.matchId(), hunter.matchTeamId(), true, active,
        state.phase(), state.bountyState(), objective, extraction, extractions, runtime.carrying, now,
        state.matchRemainingMillis() < 0 ? -1L : state.matchRemainingMillis() / 50L,
        state.bountyRemainingMillis() < 0 ? -1L : state.bountyRemainingMillis() / 50L,
        state.bountySignalUncertaintyRadius(),
        state.banishingTeamId(),
        state.corpses().stream()
            .filter(BridgeProtocol.Corpse::teammate)
            .filter(BridgeProtocol.Corpse::downed)
            .filter(corpse -> corpse.world().equals(runtime.slot.world()))
            .map(
                corpse ->
                    new ReviveTarget(
                        corpse.markerId(),
                        corpse.ownerId(),
                        new Vec3(corpse.x(), corpse.y(), corpse.z())))
            .toList(),
        state.bossInteractionDistance());
  }

  private static UUID nearestReviveTarget(HunterEntity hunter, MatchDirective directive) {
    if (directive == null) return null;
    return directive.reviveTargets().stream()
        .min(java.util.Comparator.comparingDouble(target -> target.position().distanceToSqr(hunter.position())))
        .map(ReviveTarget::markerId)
        .orElse(null);
  }

  private static void updateAllies(HunterEntity hunter, Runtime runtime) {
    if (runtime.state == null) {
      hunter.setMatchAllyIds(Set.of());
      return;
    }
    hunter.setMatchAllyIds(
        runtime.state.teammates().stream()
            .map(BridgeProtocol.Teammate::id)
            .collect(java.util.stream.Collectors.toSet()));
  }

  private HunterEntity resolveHunter(BridgeProtocol.MatchBot slot) {
    HunterEntity known = huntersByBot.get(slot.botId());
    if (known != null && !known.isRemoved() && slot.matchId().equals(known.matchId())) return known;
    if (slot.entityId() == null) return null;
    return registry.snapshot().stream()
        .filter(candidate -> candidate.getUUID().equals(slot.entityId()))
        .filter(candidate -> !candidate.isRemoved())
        .findFirst()
        .orElse(null);
  }

  private HunterEntity spawn(BridgeProtocol.MatchBot slot, long gameTime) {
    ServerLevel level;
    try {
      level = HuntMcBukkitBridge.hunterWorld(slot.world());
    } catch (LinkageError | RuntimeException exception) {
      reportFailure(gameTime, "Could not resolve Hunter world=" + slot.world(), exception);
      return null;
    }
    try {
      HunterEntity hunter = HunterAiEntities.HUNTER.get().create(level);
      if (hunter == null) {
        reportFailure(gameTime, "Hunter entity factory returned null for bot=" + shortId(slot.botId()), null);
        return null;
      }
      HunterProfile profile = HunterProfile.fromCommand(slot.profile()).orElse(HunterProfile.RIFLE);
      hunter.setProfile(profile);
      hunter.assignMatchBot(slot.botId(), slot.matchId(), slot.teamId());
      hunter.configureMatchLoadout(slot.weaponIds(), slot.loadoutAssigned());
      net.minecraft.world.entity.ai.attributes.AttributeInstance maximumHealth =
          hunter.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
      if (maximumHealth != null) maximumHealth.setBaseValue(slot.maximumHealth());
      hunter.setHealth((float) slot.maximumHealth());
      hunter.moveTo(slot.x(), slot.y(), slot.z(), slot.yaw(), 0.0F);
      if (!level.addFreshEntity(hunter)) {
        hunter.discard();
        reportFailure(gameTime, "Forge rejected Hunter insertion for bot=" + shortId(slot.botId()), null);
        return null;
      }
      registry.recordMonitor(hunter, gameTime, "LIFECYCLE", "phase=spawn bot=" + shortId(slot.botId()));
      return hunter;
    } catch (RuntimeException exception) {
      reportFailure(gameTime, "Could not create Hunter for bot=" + shortId(slot.botId()), exception);
      return null;
    }
  }

  private void maintainMatchChunkTickets(HunterEntity hunter, Runtime runtime, long now) {
    if (!(hunter.level() instanceof ServerLevel level)) return;
    UUID owner = hunter.getUUID();
    if (runtime.forcedChunkLevel != level || !owner.equals(runtime.forcedChunkOwner)) {
      releaseMatchChunkTickets(runtime);
      runtime.forcedChunkLevel = level;
      runtime.forcedChunkOwner = owner;
    }
    Set<ChunkPos> desired = matchChunkSquare(new ChunkPos(hunter.blockPosition()));
    for (ChunkPos chunk : Set.copyOf(runtime.forcedChunkPositions)) {
      if (desired.contains(chunk)) continue;
      try {
        ForgeChunkManager.forceChunk(level, HuntMcHunterAi.MOD_ID, owner, chunk.x, chunk.z, false, true);
        runtime.forcedChunkPositions.remove(chunk);
      } catch (RuntimeException exception) {
        reportFailure(now, "Could not release Hunter chunk " + chunk, exception);
      }
    }
    for (ChunkPos chunk : desired) {
      if (runtime.forcedChunkPositions.contains(chunk)) continue;
      try {
        ForgeChunkManager.forceChunk(level, HuntMcHunterAi.MOD_ID, owner, chunk.x, chunk.z, true, true);
        runtime.forcedChunkPositions.add(chunk);
      } catch (RuntimeException exception) {
        reportFailure(now, "Could not force Hunter chunk " + chunk, exception);
      }
    }
  }

  static Set<ChunkPos> matchChunkSquare(ChunkPos center) {
    Set<ChunkPos> chunks = new HashSet<>();
    for (int x = -MATCH_CHUNK_TICKET_RADIUS; x <= MATCH_CHUNK_TICKET_RADIUS; x++)
      for (int z = -MATCH_CHUNK_TICKET_RADIUS; z <= MATCH_CHUNK_TICKET_RADIUS; z++)
        chunks.add(new ChunkPos(center.x + x, center.z + z));
    return Set.copyOf(chunks);
  }

  private void releaseMatchChunkTickets(Runtime runtime) {
    if (runtime == null || runtime.forcedChunkLevel == null || runtime.forcedChunkOwner == null) return;
    for (ChunkPos chunk : Set.copyOf(runtime.forcedChunkPositions)) {
      try {
        ForgeChunkManager.forceChunk(
            runtime.forcedChunkLevel, HuntMcHunterAi.MOD_ID, runtime.forcedChunkOwner,
            chunk.x, chunk.z, false, true);
        runtime.forcedChunkPositions.remove(chunk);
      } catch (RuntimeException exception) {
        LOG.warn("Could not release Hunter chunk {}: {}", chunk, describe(exception));
      }
    }
    runtime.forcedChunkLevel = null;
    runtime.forcedChunkOwner = null;
  }

  private static BridgeProtocol.HunterPosition position(HunterEntity hunter, Runtime runtime) {
    if (runtime == null || runtime.slot == null || runtime.slot.world().isBlank()) return null;
    return new BridgeProtocol.HunterPosition(runtime.slot.world(), hunter.getX(), hunter.getY(), hunter.getZ());
  }

  private List<BridgeProtocol.HunterScanTarget> standaloneHunterScanTargets() {
    return registry.snapshot().stream()
        .filter(hunter -> !hunter.isMatchBot() && !hunter.isDeadOrDying())
        .filter(hunter -> hunter.level() instanceof ServerLevel)
        .map(
            hunter -> new BridgeProtocol.HunterScanTarget(
                hunter.getUUID(),
                new BridgeProtocol.HunterPosition(
                    ((ServerLevel) hunter.level()).dimension().location().toString(),
                    hunter.getX(), hunter.getY(), hunter.getZ())))
        .toList();
  }

  private void syncObserverCameras(List<BridgeProtocol.MatchObserver> observers) {
    Map<UUID, UUID> expected = new HashMap<>();
    MinecraftServer server = server();
    if (server == null) return;
    for (BridgeProtocol.MatchObserver observer : observers) {
      HunterEntity hunter = huntersByBot.get(observer.botId());
      if (hunter == null || hunter.isRemoved() || !hunter.getUUID().equals(observer.entityId())) continue;
      ServerPlayer player = server.getPlayerList().getPlayer(observer.observerId());
      if (player == null) continue;
      expected.put(observer.observerId(), hunter.getUUID());
      // Spectator Shift intentionally restores the player's own camera. Keep that free-camera
      // choice until Bukkit selects a different Hunter; otherwise every bridge poll traps the
      // observer on the same entity again.
      if (shouldAttachObserver(observerTargets.get(observer.observerId()), hunter.getUUID())) {
        player.setCamera(hunter);
        LOG.info(
            "Attached full-AI observer {} to Hunter {}",
            shortId(observer.observerId()),
            shortId(hunter.getUUID()));
      }
    }
    for (UUID observerId : Set.copyOf(observerTargets.keySet())) {
      if (expected.containsKey(observerId)) continue;
      ServerPlayer player = server.getPlayerList().getPlayer(observerId);
      if (player != null) player.setCamera(player);
    }
    observerTargets.clear();
    observerTargets.putAll(expected);
  }

  static boolean shouldAttachObserver(UUID previousTarget, UUID requestedTarget) {
    return requestedTarget != null && !requestedTarget.equals(previousTarget);
  }

  static boolean invalidatesBinding(String reason) {
    if (reason == null || reason.isBlank()) return false;
    String normalized = reason.toLowerCase(java.util.Locale.ROOT);
    return normalized.contains("seat is unavailable")
        || normalized.contains("does not own this seat")
        || normalized.contains("seat has no bound entity");
  }

  static boolean requiresStateRefreshAfterRejection(
      InteractionCommand command, String reason) {
    if (command != InteractionCommand.REVIVE || reason == null || reason.isBlank()) return false;
    String normalized = reason.toLowerCase(java.util.Locale.ROOT);
    return normalized.contains("revive state is invalid")
        || normalized.contains("downed teammate is offline");
  }

  static boolean interactionTimedOut(long startedAt, long now) {
    return startedAt != Long.MIN_VALUE
        && now >= startedAt
        && now - startedAt >= INTERACTION_PENDING_TIMEOUT_TICKS;
  }

  private static boolean interactionSatisfied(InteractionCommand command, Runtime runtime) {
    if (runtime == null || runtime.state == null || command == null) return false;
    return switch (command) {
      case START_BANISH -> "BANISHING".equals(runtime.state.bountyState())
          || "AVAILABLE".equals(runtime.state.bountyState())
          || "CARRIED".equals(runtime.state.bountyState())
          || "DROPPED".equals(runtime.state.bountyState());
      case CLAIM_BOUNTY -> runtime.carrying || runtime.state.carryingBounty();
      case REVIVE -> runtime.directive != null && runtime.directive.reviveTargets().isEmpty();
      case EXTRACT, RECOVER_HEALTH, NONE -> false;
    };
  }

  private static void clearPendingInteraction(Runtime runtime) {
    if (runtime == null) return;
    runtime.pendingInteractionCommand = null;
    runtime.pendingInteractionStartedAt = Long.MIN_VALUE;
    runtime.pendingInteractionLastProgressAt = Long.MIN_VALUE;
  }

  private void restoreObserverCameras() {
    MinecraftServer server = server();
    if (server == null) return;
    for (UUID observerId : observerTargets.keySet()) {
      ServerPlayer player = server.getPlayerList().getPlayer(observerId);
      if (player != null) player.setCamera(player);
    }
  }

  private MinecraftServer server() {
    for (HunterEntity hunter : huntersByBot.values())
      if (hunter.level() instanceof ServerLevel level) return level.getServer();
    return null;
  }

  /** Only boss auras and deliberately uncertain public bounty signals are legal objectives. */
  static Vec3 objective(BridgeProtocol.State state) {
    if (state.bossAura() != null)
      return new Vec3(state.bossAura().x(), state.bossAura().y(), state.bossAura().z());
    if (!state.bountySignals().isEmpty()) {
      BridgeProtocol.BountySignal signal = state.bountySignals().get(0);
      return new Vec3(signal.x(), signal.y(), signal.z());
    }
    return null;
  }

  private static Vec3 nearestExtraction(HunterEntity hunter, BridgeProtocol.State state) {
    return state.extractions().stream()
        .map(point -> new Vec3(point.x(), point.y(), point.z()))
        .min(java.util.Comparator.comparingDouble(point -> hunter.position().distanceToSqr(point)))
        .orElse(null);
  }

  private void traceLifecycleIfChanged(HunterEntity hunter, Runtime runtime, long now) {
    String next = "match=" + shortId(runtime.slot.matchId()) + " team=" + runtime.slot.teamId()
        + " bound=" + runtime.bound + " active=" + (runtime.directive != null && runtime.directive.active());
    if (next.equals(runtime.lastSeatTrace)) return;
    runtime.lastSeatTrace = next;
    registry.recordMonitor(hunter, now, "LIFECYCLE", "phase=seat " + next);
  }

  private void reportFailure(long gameTime, String detail, Throwable exception) {
    if (gameTime < nextFailureLogAt) return;
    nextFailureLogAt = gameTime + FAILURE_LOG_INTERVAL_TICKS;
    if (exception == null) LOG.warn("Hunter match synchronization failed: {}", detail);
    else LOG.error("Hunter match synchronization failed: {}", detail, exception);
  }

  private static String describe(Throwable exception) {
    String message = exception.getMessage();
    return exception.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
  }

  private static String shortId(UUID value) {
    return value == null ? "none" : value.toString().substring(0, 8);
  }

  private static final class Runtime {
    private BridgeProtocol.MatchBot slot;
    private BridgeProtocol.State state;
    private MatchDirective directive;
    private long nextStatePollAt;
    private long nextActionAt;
    private boolean bound;
    private boolean carrying;
    private String lastSeatTrace = "";
    private String lastInteraction = "none";
    private InteractionCommand pendingInteractionCommand;
    private long pendingInteractionStartedAt = Long.MIN_VALUE;
    private long pendingInteractionLastProgressAt = Long.MIN_VALUE;
    private long pendingInteractionAttempt;
    private ServerLevel forcedChunkLevel;
    private UUID forcedChunkOwner;
    private final Set<ChunkPos> forcedChunkPositions = new HashSet<>();

    private Runtime(BridgeProtocol.MatchBot slot) {
      this.slot = slot;
    }
  }
}
