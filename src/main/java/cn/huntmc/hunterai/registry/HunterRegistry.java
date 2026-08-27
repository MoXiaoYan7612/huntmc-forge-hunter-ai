package cn.huntmc.hunterai.registry;

import cn.huntmc.bridge.contract.BridgeProtocol;
import cn.huntmc.hunterai.entity.HunterEntity;
import cn.huntmc.hunterai.config.HunterAiConfig;
import cn.huntmc.hunterai.monitor.HunterMonitorIdData;
import cn.huntmc.hunterai.monitor.HunterMonitorService;
import cn.huntmc.hunterai.monitor.PlayerMatchMonitor;
import cn.huntmc.hunterai.monitor.TelemetryMode;
import cn.huntmc.hunterai.director.GodDirector;
import cn.huntmc.hunterai.match.MatchDirective;
import java.util.Collection;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Tracks only Hunter AI entities so test commands never scan unrelated world entities. */
public final class HunterRegistry {
  private static final long MOVEMENT_SAMPLE_RETENTION_TICKS = 200L;
  private static final long MOVEMENT_SAMPLE_PRUNE_INTERVAL_TICKS = 20L;
  private static final long MOVEMENT_SOUND_SAMPLE_TICKS = 6L;
  private static final double MOVEMENT_SOUND_MINIMUM_DISTANCE = 0.22D;
  private static final long ELEVATED_TICK_NANOS = 45_000_000L;
  private static final long CRITICAL_TICK_NANOS = 50_000_000L;
  private static final int TICK_SAMPLE_CAPACITY = 40;

  private final Map<UUID, HunterEntity> hunters = new LinkedHashMap<>();
  private final Map<UUID, MovementSample> movementSamples = new LinkedHashMap<>();
  private final Map<UUID, PerformanceHistory> performanceHistory = new LinkedHashMap<>();
  private final HunterMonitorService monitorService = new HunterMonitorService();
  private final PlayerMatchMonitor playerMatchMonitor = new PlayerMatchMonitor(monitorService);
  private final GodDirector godDirector = new GodDirector();
  private int remainingRayBudget;
  private int remainingPerceptionRayBudget;
  private int remainingDeliberationBudgetMicros;
  private boolean debugEnabled;
  private final long[] recentBrainNanos = new long[256];
  private int brainSampleCount;
  private int brainSampleCursor;
  private long totalBrainNanos;
  private long totalBrainSamples;
  private long nextMovementSamplePruneAt;
  private final long[] recentServerTickNanos = new long[TICK_SAMPLE_CAPACITY];
  private final long[] recentTelemetryNanos = new long[100];
  private int telemetrySampleCount;
  private int telemetrySampleCursor;
  private int serverTickSampleCount;
  private int serverTickSampleCursor;
  private long serverTickStartedNanos;
  private ServerPressure serverPressure = ServerPressure.NORMAL;
  private long serverTick;

  public void track(HunterEntity hunter) {
    // Keep a standard session alive whenever a Hunter exists.  Full replay can still
    // upgrade this session through /hunter monitor on or an all-AI match command.
    if (!monitorService.isEnabled()) monitorService.start(TelemetryMode.STANDARD);
    if (hunter.level() instanceof ServerLevel level) {
      HunterMonitorIdData idData = HunterMonitorIdData.forLevel(level);
      if (hunter.monitorId() <= 0) hunter.setMonitorId(idData.reserveId());
      else idData.observeExistingId(hunter.monitorId());
    }
    hunters.put(hunter.getUUID(), hunter);
    if (hunter.level() instanceof ServerLevel level) {
      monitorService.markTracked(hunter, level.getGameTime());
    }
  }

  public void forget(HunterEntity hunter) {
    if (hunter.level() instanceof ServerLevel level) {
      monitorService.markRemoved(hunter, level.getGameTime(), hunter.removalReason());
    }
    hunters.remove(hunter.getUUID());
    godDirector.forget(hunter.getUUID());
    movementSamples.remove(hunter.getUUID());
    performanceHistory.remove(hunter.getUUID());
    invalidateKnownTarget(hunter, "entity-left-level");
    if (hunters.isEmpty()) movementSamples.clear();
  }

  public int activeCount() {
    pruneRemoved();
    return hunters.size();
  }

  public int removeNearby(ServerLevel level, AABB area) {
    int removed = 0;
    for (HunterEntity hunter : snapshot()) {
      if (hunter.level() == level && hunter.getBoundingBox().intersects(area)) {
        hunter.markRemovalReason("nearby-cleanup");
        hunter.discard();
        removed++;
      }
    }
    return removed;
  }

  public int discardAll() {
    int removed = 0;
    for (HunterEntity hunter : snapshot()) {
      if (!hunter.isRemoved()) {
        if (hunter.level() instanceof ServerLevel level) {
          monitorService.record(
              hunter, level.getGameTime(), "LIFECYCLE", "phase=remove-requested result=cleanup");
        }
        hunter.markRemovalReason("all-cleanup");
        hunter.discard();
        removed++;
      }
    }
    hunters.clear();
    movementSamples.clear();
    performanceHistory.clear();
    return removed;
  }

  public Collection<HunterEntity> snapshot() {
    pruneRemoved();
    return java.util.List.copyOf(hunters.values());
  }

  /** Resolves the persistent monitor number without falling back to an unrelated nearby Hunter. */
  public HunterEntity findByMonitorId(int monitorId) {
    if (monitorId <= 0) return null;
    pruneRemoved();
    return hunters.values().stream()
        .filter(hunter -> hunter.monitorId() == monitorId)
        .findFirst()
        .orElse(null);
  }

  public void resetRayBudget() {
    remainingRayBudget = HunterAiConfig.GLOBAL_RAY_BUDGET.get();
    remainingPerceptionRayBudget = HunterAiConfig.PERCEPTION_RAY_BUDGET.get();
    remainingDeliberationBudgetMicros =
        (int)
            Math.round(
                HunterAiConfig.GLOBAL_DELIBERATION_BUDGET_MICROS.get()
                    * serverPressure.deliberationBudgetFactor());
  }

  public void beginServerTick() {
    serverTick++;
    serverTickStartedNanos = System.nanoTime();
    resetRayBudget();
  }

  public long serverTick() {
    return serverTick;
  }

  public void endServerTick() {
    if (serverTickStartedNanos == 0L) return;
    recordServerTickNanos(Math.max(0L, System.nanoTime() - serverTickStartedNanos));
    serverTickStartedNanos = 0L;
  }

  public int reserveDeliberationMicros() {
    int requested = HunterAiConfig.HUNTER_DELIBERATION_SLICE_MICROS.get();
    int granted = Math.min(requested, Math.max(0, remainingDeliberationBudgetMicros));
    remainingDeliberationBudgetMicros -= granted;
    return granted;
  }

  /** Static terrain sampling degrades before perception or emergency reactions are touched. */
  public int[] tacticalSamplingRadii() {
    return switch (serverPressure) {
      case NORMAL -> new int[] {2, 5, 9, 13, 16};
      case ELEVATED -> new int[] {2, 5, 9, 13};
      case CRITICAL -> new int[] {2, 5, 9};
    };
  }

  public ServerPressure serverPressure() {
    return serverPressure;
  }

  public boolean tryConsumeRay() {
    if (remainingRayBudget <= 0) return false;
    remainingRayBudget--;
    return true;
  }

  /** Keeps direct target detection available when tactical cover sampling exhausts its own budget. */
  public boolean tryConsumePerceptionRay() {
    if (remainingPerceptionRayBudget <= 0) return false;
    remainingPerceptionRayBudget--;
    return true;
  }

  public boolean debugEnabled() {
    return debugEnabled;
  }

  public void setDebugEnabled(boolean debugEnabled) {
    this.debugEnabled = debugEnabled;
    for (HunterEntity hunter : snapshot()) hunter.setCustomNameVisible(debugEnabled);
  }

  public void clearDebugTraces() {
    for (HunterEntity hunter : snapshot()) hunter.coordinator().clearTrace();
  }

  public boolean monitorEnabled() {
    return monitorService.isEnabled();
  }

  public void startMonitor() {
    playerMatchMonitor.markManualSession();
    startFullReplayMonitor();
  }

  /** Administrator and all-AI observation sessions always require complete event sourcing. */
  public void startFullReplayMonitor() {
    boolean alreadyFullReplay =
        monitorService.isEnabled() && monitorService.mode() == TelemetryMode.FULL_REPLAY;
    monitorService.start(TelemetryMode.FULL_REPLAY);
    if (alreadyFullReplay
        || !monitorService.isEnabled()
        || monitorService.mode() != TelemetryMode.FULL_REPLAY) return;
    for (HunterEntity hunter : snapshot()) {
      if (hunter.level() instanceof ServerLevel level) {
        monitorService.markTracked(hunter, level.getGameTime());
      }
    }
  }

  public void startStandardMonitor() {
    monitorService.start(TelemetryMode.STANDARD);
  }

  public void stopMonitor() {
    playerMatchMonitor.reset();
    monitorService.stop();
  }

  public void closeMonitor() {
    playerMatchMonitor.reset();
    monitorService.close();
  }

  public void recordMonitor(HunterEntity hunter, long gameTime, String category, String detail) {
    monitorService.record(hunter, gameTime, category, detail);
  }

  /** Applies the Bukkit-owned roster and automatically manages mixed-match replay sessions. */
  public void updateMatchActors(
      Collection<BridgeProtocol.MatchActor> actors, long gameTime) {
    playerMatchMonitor.updateRoster(actors, gameTime);
  }

  public void updateMatchObjectives(UUID matchId, Vec3 publicObjective, Vec3 extraction) {
    playerMatchMonitor.updateObjectives(matchId, publicObjective, extraction);
  }

  /** Captures all live player/world state on the Forge server thread. */
  public void capturePlayerTelemetry(MinecraftServer server) {
    if (server == null) return;
    long started = System.nanoTime();
    playerMatchMonitor.tick(server, snapshot(), serverTick, serverPressure);
    long elapsed = Math.max(0L, System.nanoTime() - started);
    recentTelemetryNanos[telemetrySampleCursor] = elapsed;
    telemetrySampleCursor = (telemetrySampleCursor + 1) % recentTelemetryNanos.length;
    telemetrySampleCount = Math.min(telemetrySampleCount + 1, recentTelemetryNanos.length);
    if (monitorService.isEnabled() && serverTick % 20L == 0L) {
      long[] sorted = Arrays.copyOf(recentTelemetryNanos, telemetrySampleCount);
      Arrays.sort(sorted);
      double average = Arrays.stream(sorted).average().orElse(0.0D);
      long p95 = sorted.length == 0
          ? 0L
          : sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * 0.95D) - 1)];
      monitorService.recordSystem(
          serverTick,
          server.overworld().getGameTime(),
          "PERFORMANCE",
          Map.of(
              "playerTelemetryMicros", String.format(java.util.Locale.ROOT, "%.1f", elapsed / 1_000.0D),
              "playerTelemetryAverageMicros",
                  String.format(
                      java.util.Locale.ROOT,
                      "%.1f",
                      average / 1_000.0D),
              "playerTelemetryP95Micros", String.format(java.util.Locale.ROOT, "%.1f", p95 / 1_000.0D),
              "actors", Integer.toString(playerMatchMonitor.monitoredActorCount()),
              "pressure", serverPressure.name()));
    }
  }

  public void recordPlayerGunFire(LivingEntity shooter, ItemStack gunStack) {
    playerMatchMonitor.onGunFire(shooter, gunStack, serverTick, snapshot());
  }

  public void recordPlayerReload(LivingEntity entity, ItemStack gunStack) {
    playerMatchMonitor.onReload(entity, gunStack, serverTick);
  }

  public void recordPlayerJump(LivingEntity entity) {
    playerMatchMonitor.onJump(entity, serverTick);
  }

  public void recordPlayerDamage(LivingEntity victim, Entity attacker, float amount) {
    playerMatchMonitor.onDamage(victim, attacker, amount, serverTick);
  }

  public void recordPlayerDeath(LivingEntity victim, Entity attacker) {
    playerMatchMonitor.onDeath(victim, attacker, serverTick);
    invalidateKnownTarget(victim, "entity-death");
  }

  /** Invalidates only Hunters which already reference this entity; no new death evidence is added. */
  public void invalidateKnownTarget(LivingEntity victim, String reason) {
    if (victim == null || !(victim.level() instanceof ServerLevel level)) return;
    UUID targetId = victim.getUUID();
    long now = level.getGameTime();
    for (HunterEntity hunter : snapshot()) {
      if (hunter != victim
          && hunter.level() == level
          && hunter.coordinator().referencesTarget(targetId))
        hunter.coordinator().onTargetLifecycleInvalidated(hunter, targetId, now, reason);
    }
  }

  /** Never writes a real player UUID into a Hunter lifecycle event. */
  public String playerActorId(UUID playerId) {
    return monitorService.playerActorId(playerId);
  }

  public void registerPlayerActor(UUID playerId) {
    if (playerId != null) monitorService.playerActorId(playerId);
  }

  public GodDirector.Directive directorDirective(
      HunterEntity hunter, MatchDirective match, long gameTime) {
    return godDirector.update(hunter, match, gameTime);
  }

  public java.util.List<String> recentMonitor(HunterEntity hunter, int lines) {
    return monitorService.recent(hunter, lines);
  }

  public String monitorStatus() {
    return monitorService.status();
  }

  public void recordBrainNanos(HunterEntity hunter, long elapsedNanos) {
    if (elapsedNanos < 0L) return;
    recentBrainNanos[brainSampleCursor] = elapsedNanos;
    brainSampleCursor = (brainSampleCursor + 1) % recentBrainNanos.length;
    brainSampleCount = Math.min(brainSampleCount + 1, recentBrainNanos.length);
    totalBrainNanos += elapsedNanos;
    totalBrainSamples++;
    PerformanceHistory history = performanceHistory.computeIfAbsent(hunter.getUUID(), ignored -> new PerformanceHistory());
    history.record(elapsedNanos);
    if (monitorService.isEnabled()
        && hunter.level() instanceof ServerLevel level
        && level.getGameTime() % 20L == 0L) {
      monitorService.record(
          hunter,
          level.getGameTime(),
          "PERF",
          String.format(
              java.util.Locale.ROOT,
              "brainMicros=%.1f hunter=%s global=%s raysUsed=%d perceptionRaysUsed=%d "
                  + "deliberationMicrosUsed=%d pressure=%s telemetry={%s}",
              elapsedNanos / 1_000.0D,
              history.summary(),
              performanceSummary(),
              Math.max(0, HunterAiConfig.GLOBAL_RAY_BUDGET.get() - remainingRayBudget),
              Math.max(
                  0,
                  HunterAiConfig.PERCEPTION_RAY_BUDGET.get()
                      - remainingPerceptionRayBudget),
              Math.max(
                  0,
                  (int)
                          Math.round(
                              HunterAiConfig.GLOBAL_DELIBERATION_BUDGET_MICROS.get()
                                  * serverPressure.deliberationBudgetFactor())
                      - remainingDeliberationBudgetMicros),
              serverPressure,
              monitorService.status()));
    }
  }

  public String performanceSummary() {
    if (brainSampleCount == 0) return "no samples";
    long[] sorted = Arrays.copyOf(recentBrainNanos, brainSampleCount);
    Arrays.sort(sorted);
    double averageMicros = totalBrainNanos / (double) totalBrainSamples / 1_000.0D;
    long p95 = sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * 0.95D) - 1)];
    return String.format(
        java.util.Locale.ROOT,
        "brain avg %.1fus p95 %.1fus pressure=%s",
        averageMicros,
        p95 / 1_000.0D,
        serverPressure);
  }

  private void recordServerTickNanos(long elapsedNanos) {
    recentServerTickNanos[serverTickSampleCursor] = elapsedNanos;
    serverTickSampleCursor = (serverTickSampleCursor + 1) % recentServerTickNanos.length;
    serverTickSampleCount = Math.min(serverTickSampleCount + 1, recentServerTickNanos.length);
    long[] sorted = Arrays.copyOf(recentServerTickNanos, serverTickSampleCount);
    Arrays.sort(sorted);
    long p95 = sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * 0.95D) - 1)];
    serverPressure = classifyServerPressure(p95);
  }

  static ServerPressure classifyServerPressure(long tickNanos) {
    if (tickNanos >= CRITICAL_TICK_NANOS) return ServerPressure.CRITICAL;
    if (tickNanos >= ELEVATED_TICK_NANOS) return ServerPressure.ELEVATED;
    return ServerPressure.NORMAL;
  }

  /** Broadcasts a sound to nearby Hunters other than the entity that produced it. */
  public void broadcastNoise(ServerLevel level, UUID emitterId, Vec3 source, float strength) {
    if (source == null) return;
    net.minecraft.world.entity.Entity emitter = emitterId == null ? null : level.getEntity(emitterId);
    double radius = HunterAiConfig.HEARING_RANGE.get() * strength;
    double radiusSquared = radius * radius;
    for (HunterEntity hunter : snapshot()) {
      if (hunter.level() == level
          && shouldReceiveNoise(hunter.getUUID(), emitterId)
          && (!(emitter instanceof LivingEntity living) || !hunter.isMatchAlly(living))
          && hunter.position().distanceToSqr(source) <= radiusSquared) {
        hunter.coordinator().hear(hunter, emitterId, source, strength, level.getGameTime());
      }
    }
  }

  static boolean shouldReceiveNoise(UUID receiverId, UUID emitterId) {
    return emitterId == null || !emitterId.equals(receiverId);
  }

  public void observeMovement(LivingEntity entity) {
    if (!(entity.level() instanceof ServerLevel level)) return;
    long now = level.getGameTime();
    godDirector.observeActor(entity, now);
    // Player movement is observed every tick. Once the last Hunter is gone no sound can be
    // consumed, so retaining player UUIDs would grow this cache for the rest of the server run.
    if (hunters.isEmpty()) {
      movementSamples.clear();
      return;
    }
    if (now >= nextMovementSamplePruneAt) {
      pruneMovementSamples(now);
      nextMovementSamplePruneAt = now + MOVEMENT_SAMPLE_PRUNE_INTERVAL_TICKS;
    }
    MovementSample previous = movementSamples.get(entity.getUUID());
    if (previous == null) {
      movementSamples.put(entity.getUUID(), new MovementSample(entity.position(), now));
      return;
    }
    if (now - previous.time() < MOVEMENT_SOUND_SAMPLE_TICKS) return;
    movementSamples.put(entity.getUUID(), new MovementSample(entity.position(), now));

    double deltaX = entity.getX() - previous.position().x();
    double deltaZ = entity.getZ() - previous.position().z();
    double horizontalDistance = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
    if (horizontalDistance < MOVEMENT_SOUND_MINIMUM_DISTANCE) return;
    boolean crouched = entity.isCrouching();
    boolean sprinting = entity.isSprinting();
    boolean airborne = !entity.onGround();
    // Footsteps are intentionally weaker than gunfire but are now emitted for both
    // walking and sprinting Hunters.  The previous 0.85-block threshold meant a
    // player-paced walk produced no hearing evidence at all.
    float strength = crouched ? 0.22F : sprinting ? 0.68F : 0.38F;
    if (airborne) strength = Math.min(0.82F, strength + 0.12F);
    broadcastNoise(level, entity.getUUID(), entity.position(), strength);
  }

  private void pruneRemoved() {
    hunters.entrySet().removeIf(entry -> entry.getValue().isRemoved());
    if (hunters.isEmpty()) {
      movementSamples.clear();
    } else {
      long now = currentTime();
      if (now > 0L) pruneMovementSamples(now);
    }
    performanceHistory.keySet().retainAll(hunters.keySet());
  }

  private void pruneMovementSamples(long now) {
    movementSamples
        .entrySet()
        .removeIf(entry -> isMovementSampleExpired(entry.getValue().time(), now));
  }

  static boolean isMovementSampleExpired(long sampleTime, long currentTime) {
    return currentTime - sampleTime > MOVEMENT_SAMPLE_RETENTION_TICKS;
  }

  private long currentTime() {
    return hunters.values().stream().findFirst().map(hunter -> hunter.level().getGameTime()).orElse(0L);
  }

  private record MovementSample(Vec3 position, long time) {}

  public enum ServerPressure {
    NORMAL(1.0D),
    ELEVATED(0.5D),
    CRITICAL(0.0D);

    private final double deliberationBudgetFactor;

    ServerPressure(double deliberationBudgetFactor) {
      this.deliberationBudgetFactor = deliberationBudgetFactor;
    }

    double deliberationBudgetFactor() {
      return deliberationBudgetFactor;
    }
  }

  /** Per-Hunter rolling samples keep a spike attributable to the entity that caused it. */
  private static final class PerformanceHistory {
    private static final int CAPACITY = 128;
    private final long[] samples = new long[CAPACITY];
    private int count;
    private int cursor;

    private void record(long nanos) {
      samples[cursor] = nanos;
      cursor = (cursor + 1) % CAPACITY;
      count = Math.min(count + 1, CAPACITY);
    }

    private String summary() {
      if (count == 0) return "avg=0.0us,p95=0.0us";
      long[] sorted = Arrays.copyOf(samples, count);
      Arrays.sort(sorted);
      long total = 0L;
      for (long sample : sorted) total += sample;
      long p95 = sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * 0.95D) - 1)];
      return String.format(
          java.util.Locale.ROOT,
          "avg=%.1fus,p95=%.1fus",
          total / (double) count / 1_000.0D,
          p95 / 1_000.0D);
    }
  }
}
