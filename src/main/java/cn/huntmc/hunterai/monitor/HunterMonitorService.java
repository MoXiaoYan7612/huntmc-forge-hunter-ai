package cn.huntmc.hunterai.monitor;

import cn.huntmc.hunterai.HuntMcHunterAi;
import cn.huntmc.hunterai.debug.HunterDebugTrace;
import cn.huntmc.hunterai.entity.HunterEntity;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

/** Asynchronous structured event store for standard diagnostics and deterministic full replay. */
public final class HunterMonitorService {
  public static final int SCHEMA_VERSION = 4;
  private static final Logger LOG = LogUtils.getLogger();
  private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
  private static final DateTimeFormatter SESSION_TIME = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss");
  private static final int MAX_PENDING_EVENTS = 65_536;
  private static final int STANDARD_RING_EVENTS = 240;
  private static final long ROTATE_BYTES = 128L * 1024L * 1024L;
  private static final int RETAIN_COMPLETE_SESSIONS = 20;
  private static final long RETAIN_TOTAL_BYTES = 10L * 1024L * 1024L * 1024L;
  private static final Set<String> FRAME_TYPES = Set.of("INPUT", "ACTION_FRAME", "ACTUATOR_RESULT", "OUTCOME");
  private static final Set<String> DEDUPLICATED_TYPES =
      Set.of("COMBAT", "VIGILANCE", "PERCEPTION", "SIMULATION_GATE", "FALLBACK");

  private final Queue<PendingEvent> queue = new ConcurrentLinkedQueue<>();
  private final Map<UUID, HunterDebugTrace> recentEvents = new HashMap<>();
  private final Map<UUID, Deque<EventDraft>> standardRings = new HashMap<>();
  private final Map<String, RotationState> rotations = new HashMap<>();
  private final Map<String, EmittedState> lastEmitted = new HashMap<>();
  private final Map<UUID, String> playerAliases = new HashMap<>();
  private final PlayerAliasService aliasService;
  private final AtomicBoolean drainScheduled = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicBoolean incomplete = new AtomicBoolean();
  private final AtomicBoolean finalized = new AtomicBoolean();
  private final AtomicBoolean finalizing = new AtomicBoolean();
  private final AtomicInteger pendingCount = new AtomicInteger();
  private final AtomicInteger queuePeak = new AtomicInteger();
  private final AtomicLong sequence = new AtomicLong();
  private final AtomicLong droppedEvents = new AtomicLong();
  private final AtomicLong lastPersistedSequence = new AtomicLong();
  private final ExecutorService writer =
      Executors.newSingleThreadExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "HuntMC-Hunter-Replay");
            thread.setDaemon(true);
            return thread;
          });
  private final ExecutorService analyzer =
      Executors.newSingleThreadExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "HuntMC-Hunter-Replay-Analyzer");
            thread.setDaemon(true);
            return thread;
          });

  private volatile boolean enabled;
  private volatile TelemetryMode mode = TelemetryMode.STANDARD;
  private volatile Path sessionDirectory;
  private volatile Instant startedAt;

  public HunterMonitorService() {
    this(PlayerAliasService.loadDefault());
  }

  HunterMonitorService(PlayerAliasService aliasService) {
    this.aliasService = java.util.Objects.requireNonNull(aliasService, "aliasService");
  }

  public boolean isEnabled() {
    return enabled;
  }

  public TelemetryMode mode() {
    return mode;
  }

  public synchronized void start(TelemetryMode requestedMode) {
    if (closed.get()) return;
    TelemetryMode nextMode = requestedMode == null ? TelemetryMode.STANDARD : requestedMode;
    if (enabled) {
      if (nextMode == TelemetryMode.FULL_REPLAY && mode != TelemetryMode.FULL_REPLAY) {
        mode = TelemetryMode.FULL_REPLAY;
        enqueueDraft(
            new EventDraft(
                0L,
                0L,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "LIFECYCLE",
                Map.of("phase", "telemetry-upgraded", "mode", mode.name())));
      }
      return;
    }
    if (finalizing.get()) {
      LOG.warn("Hunter replay session is still finalizing; a new {} session was not started.", nextMode);
      return;
    }
    Path base = FMLPaths.GAMEDIR.get().resolve("logs").resolve("huntmc-hunter-ai-monitor");
    sessionDirectory = base.resolve(
        "session-" + SESSION_TIME.format(LocalDateTime.now()) + "-" + UUID.randomUUID().toString().substring(0, 8));
    mode = nextMode;
    startedAt = Instant.now();
    enabled = true;
    finalized.set(false);
    incomplete.set(false);
    sequence.set(0L);
    droppedEvents.set(0L);
    lastPersistedSequence.set(0L);
    pendingCount.set(0);
    queuePeak.set(0);
    queue.clear();
    rotations.clear();
    lastEmitted.clear();
    recentEvents.clear();
    standardRings.clear();
    Path current = sessionDirectory;
    LOG.info("Hunter AI monitor started: mode={} session={}", mode, current);
    writer.execute(
        () -> {
          try {
            Files.createDirectories(current);
            writeSessionMetadata(false, "RUNNING");
            retainSessions(base, current);
          } catch (IOException exception) {
            markIncomplete("Unable to initialize replay session " + current, exception);
          }
        });
  }

  public synchronized void stop() {
    if (!enabled || finalized.get()) return;
    enabled = false;
    finalizing.set(true);
    enqueueDraft(
        new EventDraft(
            0L,
            0L,
            0,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "LIFECYCLE",
            Map.of("phase", "monitor-stopped", "mode", mode.name())));
    scheduleDrain();
    try {
      writer.execute(this::finalizeSession);
    } catch (RuntimeException exception) {
      markIncomplete("Replay finalizer was rejected", exception);
    }
  }

  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    stop();
    writer.shutdown();
    try {
      if (!writer.awaitTermination(10L, TimeUnit.SECONDS)) {
        incomplete.set(true);
        writer.shutdownNow();
        persistForcedIncomplete("FORCED_TIMEOUT");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      incomplete.set(true);
      writer.shutdownNow();
      persistForcedIncomplete("INTERRUPTED");
    }
    analyzer.shutdown();
    try {
      if (!analyzer.awaitTermination(30L, TimeUnit.SECONDS)) {
        analyzer.shutdownNow();
        LOG.warn("Hunter replay analyzer did not finish before shutdown.");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      analyzer.shutdownNow();
      LOG.warn("Hunter replay analyzer shutdown was interrupted.");
    }
  }

  public void record(HunterEntity hunter, long gameTime, String type, String detail) {
    if (!enabled || hunter == null || hunter.monitorId() <= 0) return;
    String normalizedType = type == null || type.isBlank() ? "UNKNOWN" : type;
    Map<String, String> fields = sanitizeFields(parseFields(detail));
    EventDraft draft =
        new EventDraft(
            sessionTick(),
            gameTime,
            hunter.monitorId(),
            hunter.getUUID(),
            "hunter-" + hunter.monitorId(),
            "HUNTER",
            hunter.matchId(),
            hunter.matchTeamId(),
            counterpart(fields),
            fields.get("encounterId"),
            fields.get("decisionId"),
            fields.get("planId"),
            fields.get("evidenceId"),
            fields.get("routeId"),
            normalizedType,
            fields);
    if (redundant(draft)) return;
    recentEvents.computeIfAbsent(hunter.getUUID(), ignored -> new HunterDebugTrace())
        .record(gameTime, normalizedType, fields.toString());
    if (mode == TelemetryMode.FULL_REPLAY) {
      enqueueDraft(draft);
      return;
    }

    Deque<EventDraft> ring = standardRings.computeIfAbsent(hunter.getUUID(), ignored -> new ArrayDeque<>());
    if (isAnomaly(normalizedType, detail)) {
      while (!ring.isEmpty()) enqueueDraft(ring.removeFirst());
      enqueueDraft(draft);
    } else if (isStandardPersistent(normalizedType, detail)) {
      enqueueDraft(draft);
    } else {
      ring.addLast(draft);
      while (ring.size() > STANDARD_RING_EVENTS) ring.removeFirst();
    }
  }

  public void registerMatchActors(
      Collection<cn.huntmc.bridge.contract.BridgeProtocol.MatchActor> actors) {
    if (actors == null) return;
    for (cn.huntmc.bridge.contract.BridgeProtocol.MatchActor actor : actors) {
      if (actor != null
          && actor.kind()
              == cn.huntmc.bridge.contract.BridgeProtocol.MatchActorKind.PLAYER) {
        playerAliases.computeIfAbsent(actor.actorId(), aliasService::alias);
      }
    }
  }

  public String playerActorId(UUID playerId) {
    return playerAliases.computeIfAbsent(playerId, aliasService::alias);
  }

  public void recordPlayer(
      UUID playerId,
      UUID matchId,
      String teamId,
      long eventSessionTick,
      long gameTime,
      String type,
      String counterpartActorId,
      String encounterId,
      Map<String, String> payload) {
    if (!enabled || playerId == null || matchId == null) return;
    Map<String, String> fields = sanitizeFields(payload == null ? Map.of() : Map.copyOf(payload));
    enqueueDraft(
        new EventDraft(
            Math.max(0L, eventSessionTick),
            gameTime,
            0,
            null,
            playerActorId(playerId),
            "PLAYER",
            matchId,
            teamId == null ? "" : teamId,
            counterpartActorId,
            encounterId,
            fields.get("decisionId"),
            fields.get("planId"),
            fields.get("evidenceId"),
            fields.get("routeId"),
            type == null || type.isBlank() ? "UNKNOWN" : type,
            fields));
  }

  public void recordSystem(
      long eventSessionTick, long gameTime, String type, Map<String, String> payload) {
    if (!enabled) return;
    Map<String, String> fields = sanitizeFields(payload == null ? Map.of() : Map.copyOf(payload));
    enqueueDraft(
        new EventDraft(
            Math.max(0L, eventSessionTick),
            gameTime,
            0,
            null,
            "monitor",
            "SYSTEM",
            null,
            "",
            null,
            null,
            fields.get("decisionId"),
            fields.get("planId"),
            fields.get("evidenceId"),
            fields.get("routeId"),
            type == null || type.isBlank() ? "UNKNOWN" : type,
            fields));
  }

  private boolean redundant(EventDraft draft) {
    if (!DEDUPLICATED_TYPES.contains(draft.type())) return false;
    String key = draft.hunterId() + ":" + draft.type();
    String fingerprint = draft.payload().toString();
    EmittedState previous = lastEmitted.get(key);
    if (previous != null
        && previous.fingerprint().equals(fingerprint)
        && draft.sessionTick() - previous.sessionTick() < 20L) return true;
    lastEmitted.put(key, new EmittedState(fingerprint, draft.sessionTick()));
    return false;
  }

  private record EmittedState(String fingerprint, long sessionTick) {}

  public List<String> recent(HunterEntity hunter, int lines) {
    HunterDebugTrace trace = recentEvents.get(hunter.getUUID());
    return trace == null ? List.of() : trace.latest(lines);
  }

  public void markTracked(HunterEntity hunter, long gameTime) {
    if (!enabled) return;
    record(
        hunter,
        gameTime,
        "LIFECYCLE",
        "phase=tracked result=ready profile=" + hunter.profile().commandName()
            + " botId=" + hunter.matchBotId() + " matchId=" + hunter.matchId());
  }

  public void markRemoved(HunterEntity hunter, long gameTime, String reason) {
    if (!enabled) return;
    record(
        hunter,
        gameTime,
        "LIFECYCLE",
        "phase=removed reason=" + (reason == null || reason.isBlank() ? "unknown" : reason));
    recentEvents.remove(hunter.getUUID());
    standardRings.remove(hunter.getUUID());
  }

  public String status() {
    Path directory = sessionDirectory;
    return "enabled=" + enabled
        + " mode=" + mode
        + " session=" + (directory == null ? "none" : directory)
        + " pending=" + pendingCount.get()
        + " peak=" + queuePeak.get()
        + " dropped=" + droppedEvents.get()
        + " incomplete=" + incomplete.get();
  }

  public Path sessionDirectory() {
    return sessionDirectory;
  }

  private void enqueueDraft(EventDraft draft) {
    if (sessionDirectory == null || finalized.get()) return;
    long nextSequence = sequence.incrementAndGet();
    String group = groupFor(draft.type());
    PendingEvent pending = new PendingEvent(nextSequence, group, draft);
    int count = pendingCount.incrementAndGet();
    queuePeak.accumulateAndGet(count, Math::max);
    if (count > MAX_PENDING_EVENTS) {
      pendingCount.decrementAndGet();
      droppedEvents.incrementAndGet();
      incomplete.set(true);
      LOG.warn(
          "Hunter replay queue overflow: session={} sequence={} droppedEvents={}. Session marked INCOMPLETE.",
          sessionDirectory,
          nextSequence,
          droppedEvents.get());
      return;
    }
    queue.offer(pending);
    scheduleDrain();
  }

  private void scheduleDrain() {
    if (!canScheduleDrain(finalized.get(), finalizing.get())) return;
    if (!drainScheduled.compareAndSet(false, true)) return;
    try {
      writer.execute(this::drain);
    } catch (RuntimeException exception) {
      drainScheduled.set(false);
      markIncomplete("Replay writer rejected queued events", exception);
    }
  }

  private void drain() {
    Map<Path, StringBuilder> batches = new LinkedHashMap<>();
    Map<Path, Long> lastSequences = new HashMap<>();
    PendingEvent event;
    while ((event = queue.poll()) != null) {
      pendingCount.decrementAndGet();
      String line = encode(event);
      Path file = fileFor(event.group(), line.getBytes(StandardCharsets.UTF_8).length);
      batches.computeIfAbsent(file, ignored -> new StringBuilder()).append(line);
      lastSequences.merge(file, event.sequence(), Math::max);
    }
    for (Map.Entry<Path, StringBuilder> batch : batches.entrySet()) {
      try {
        Files.createDirectories(batch.getKey().getParent());
        byte[] bytes = batch.getValue().toString().getBytes(StandardCharsets.UTF_8);
        Files.write(
            batch.getKey(), bytes, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        lastPersistedSequence.accumulateAndGet(lastSequences.get(batch.getKey()), Math::max);
      } catch (IOException exception) {
        markIncomplete("Unable to write Hunter replay file " + batch.getKey(), exception);
      }
    }
    drainScheduled.set(false);
    if (!queue.isEmpty() && canScheduleDrain(finalized.get(), finalizing.get())) scheduleDrain();
  }

  static boolean canScheduleDrain(boolean finalized, boolean finalizing) {
    return !finalized && !finalizing;
  }

  private static String encode(PendingEvent pending) {
    EventDraft draft = pending.draft();
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("schemaVersion", SCHEMA_VERSION);
    event.put("sequence", pending.sequence());
    event.put("sessionTick", draft.sessionTick());
    event.put("gameTime", draft.gameTime());
    event.put("monitorId", draft.monitorId());
    event.put("hunterId", draft.hunterId() == null ? null : draft.hunterId().toString());
    event.put("actorId", draft.actorId());
    event.put("actorKind", draft.actorKind());
    event.put("counterpartActorId", draft.counterpartActorId());
    event.put("encounterId", draft.encounterId());
    event.put("matchId", draft.matchId() == null ? null : draft.matchId().toString());
    event.put("teamId", draft.teamId());
    event.put("decisionId", draft.decisionId());
    event.put("planId", draft.planId());
    event.put("evidenceId", draft.evidenceId());
    event.put("routeId", draft.routeId());
    event.put("type", draft.type());
    event.put("payload", draft.payload());
    return GSON.toJson(event) + "\n";
  }

  private Path fileFor(String group, int nextBytes) {
    RotationState rotation = rotations.computeIfAbsent(group, ignored -> new RotationState(group, 1, 0L, path(group, 1)));
    if (rotation.bytes() > 0L && rotation.bytes() + nextBytes > ROTATE_BYTES) {
      rotation = new RotationState(group, rotation.index() + 1, 0L, path(group, rotation.index() + 1));
      rotations.put(group, rotation);
    }
    rotation.add(nextBytes);
    return rotation.path();
  }

  private Path path(String group, int index) {
    return sessionDirectory.resolve(String.format("%s-%04d.ndjson", group, index));
  }

  private void finalizeSession() {
    if (!finalized.compareAndSet(false, true)) return;
    Path completedSession = sessionDirectory;
    try {
      drainScheduled.set(true);
      drain();
      boolean complete = !incomplete.get()
          && droppedEvents.get() == 0L
          && pendingCount.get() == 0
          && lastPersistedSequence.get() == sequence.get();
      if (!complete) incomplete.set(true);
      try {
        writeSessionMetadata(complete, complete ? "CLOSED" : "INCOMPLETE");
      } catch (IOException | RuntimeException exception) {
        markIncomplete("Unable to finalize Hunter replay session " + sessionDirectory, exception);
        try {
          writeSessionMetadata(false, "INCOMPLETE");
        } catch (IOException metadataException) {
          LOG.error("Unable to persist incomplete replay metadata {}.", sessionDirectory, metadataException);
        }
      }
      scheduleAnalysis(completedSession);
    } finally {
      finalizing.set(false);
    }
  }

  private void scheduleAnalysis(Path completedSession) {
    if (completedSession == null) return;
    try {
      analyzer.execute(
          () -> {
            try {
              HunterReplayAnalyzer.analyze(completedSession);
            } catch (IOException | RuntimeException exception) {
              LOG.error("Unable to analyze Hunter replay session {}.", completedSession, exception);
            }
          });
    } catch (RuntimeException exception) {
      LOG.error("Hunter replay analyzer rejected session {}.", completedSession, exception);
    }
  }

  private synchronized void writeSessionMetadata(boolean complete, String shutdownState) throws IOException {
    if (sessionDirectory == null) return;
    if (complete && incomplete.get()) {
      complete = false;
      shutdownState = "INCOMPLETE";
    }
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("schemaVersion", SCHEMA_VERSION);
    metadata.put("modId", HuntMcHunterAi.MOD_ID);
    metadata.put("mode", mode.name());
    metadata.put("startedAt", startedAt == null ? null : startedAt.toString());
    metadata.put("updatedAt", Instant.now().toString());
    metadata.put("complete", complete);
    metadata.put("shutdownState", shutdownState);
    metadata.put("lastSequence", sequence.get());
    metadata.put("lastPersistedSequence", lastPersistedSequence.get());
    metadata.put("droppedEvents", droppedEvents.get());
    metadata.put("queuePeak", queuePeak.get());
    Files.createDirectories(sessionDirectory);
    Files.writeString(
        sessionDirectory.resolve("session.json"),
        GSON.toJson(metadata) + "\n",
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);
  }

  private void persistForcedIncomplete(String shutdownState) {
    try {
      writeSessionMetadata(false, shutdownState);
    } catch (IOException exception) {
      LOG.error("Unable to persist forced incomplete replay metadata {}.", sessionDirectory, exception);
    }
  }

  private void markIncomplete(String message, Throwable exception) {
    incomplete.set(true);
    if (exception == null) LOG.warn("{} Session marked INCOMPLETE.", message);
    else LOG.error(message + " Session marked INCOMPLETE.", exception);
  }

  private static String groupFor(String type) {
    if (FRAME_TYPES.contains(type)) return "frames";
    if ("PLAYER_FRAME".equals(type)) return "frames";
    if ("ENCOUNTER".equals(type)) return "encounters";
    if ("WORLD_MODEL".equals(type)) return "snapshots";
    if ("PREDICTION".equals(type)) return "predictions";
    if ("PERFORMANCE".equals(type) || "PERF".equals(type)) return "performance";
    return "events";
  }

  private static boolean isStandardPersistent(String type, String detail) {
    return "PLAN".equals(type)
        || "DIRECTOR".equals(type)
        || "SIMULATION_GATE".equals(type)
        || "PREDICTION".equals(type)
        || "FALLBACK".equals(type)
        || "LIFECYCLE".equals(type)
        || "PERFORMANCE".equals(type)
        || isAnomaly(type, detail);
  }

  private static boolean isAnomaly(String type, String detail) {
    String value = detail == null ? "" : detail.toLowerCase(java.util.Locale.ROOT);
    return "FALLBACK".equals(type)
        || value.contains("failed")
        || value.contains("rejected")
        || value.contains("unavailable")
        || value.contains("incomplete")
        || value.contains("stalled")
        || value.contains("oscillation");
  }

  /** Parses the coordinator's stable key=value wire text into a JSON payload object. */
  static Map<String, String> parseFields(String detail) {
    if (detail == null || detail.isBlank()) return Map.of();
    Map<String, String> fields = new LinkedHashMap<>();
    List<String> tokens = splitTopLevel(detail);
    int anonymous = 0;
    for (String token : tokens) {
      int separator = token.indexOf('=');
      if (separator <= 0) fields.put("text" + anonymous++, token);
      else fields.put(token.substring(0, separator), token.substring(separator + 1));
    }
    return Map.copyOf(fields);
  }

  Map<String, String> sanitizeFields(Map<String, String> source) {
    if (source == null || source.isEmpty() || playerAliases.isEmpty())
      return source == null ? Map.of() : Map.copyOf(source);
    Map<String, String> sanitized = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : source.entrySet()) {
      String value = entry.getValue();
      if (value != null) {
        for (Map.Entry<UUID, String> alias : playerAliases.entrySet()) {
          value = value.replace(alias.getKey().toString(), alias.getValue());
        }
      }
      sanitized.put(entry.getKey(), value);
    }
    return Map.copyOf(sanitized);
  }

  private static String counterpart(Map<String, String> fields) {
    for (String key : List.of("counterpartActorId", "target", "attacker", "source", "visible")) {
      String value = fields.get(key);
      if (value != null && value.startsWith("player-")) return value;
    }
    return null;
  }

  private static List<String> splitTopLevel(String value) {
    List<String> tokens = new ArrayList<>();
    int start = 0;
    int depth = 0;
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character == '(' || character == '[' || character == '{') depth++;
      else if (character == ')' || character == ']' || character == '}') depth = Math.max(0, depth - 1);
      else if (Character.isWhitespace(character) && depth == 0) {
        if (index > start) tokens.add(value.substring(start, index));
        start = index + 1;
      }
    }
    if (start < value.length()) tokens.add(value.substring(start));
    return tokens;
  }

  private static void retainSessions(Path base, Path current) throws IOException {
    if (!Files.isDirectory(base)) return;
    List<Path> complete;
    try (var paths = Files.list(base)) {
      complete = paths
          .filter(Files::isDirectory)
          .filter(path -> !path.equals(current))
          .filter(HunterMonitorService::isCompleteSession)
          .sorted(Comparator.comparingLong(HunterMonitorService::lastModified))
          .toList();
    }
    long total = 0L;
    for (Path path : complete) total += directorySize(path);
    int retained = complete.size();
    for (Path path : complete) {
      if (retained <= RETAIN_COMPLETE_SESSIONS && total <= RETAIN_TOTAL_BYTES) break;
      long size = directorySize(path);
      deleteSession(path, base);
      retained--;
      total -= size;
    }
  }

  private static boolean isCompleteSession(Path directory) {
    Path metadata = directory.resolve("session.json");
    if (!Files.isRegularFile(metadata)) return false;
    try {
      String text = Files.readString(metadata, StandardCharsets.UTF_8);
      return text.contains("\"complete\":true");
    } catch (IOException exception) {
      return false;
    }
  }

  private static long directorySize(Path directory) {
    try (var files = Files.walk(directory)) {
      return files.filter(Files::isRegularFile).mapToLong(HunterMonitorService::fileSize).sum();
    } catch (IOException exception) {
      return 0L;
    }
  }

  private static long fileSize(Path file) {
    try {
      return Files.size(file);
    } catch (IOException exception) {
      return 0L;
    }
  }

  private static long lastModified(Path path) {
    try {
      return Files.getLastModifiedTime(path).toMillis();
    } catch (IOException exception) {
      return Long.MAX_VALUE;
    }
  }

  private static void deleteSession(Path directory, Path base) throws IOException {
    Path resolvedBase = base.toAbsolutePath().normalize();
    Path resolved = directory.toAbsolutePath().normalize();
    if (!resolved.startsWith(resolvedBase) || resolved.equals(resolvedBase))
      throw new IOException("Refusing to delete replay path outside the monitor base: " + resolved);
    try (var paths = Files.walk(resolved)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
    }
  }

  private record PendingEvent(long sequence, String group, EventDraft draft) {}

  private record EventDraft(
      long sessionTick,
      long gameTime,
      int monitorId,
      UUID hunterId,
      String actorId,
      String actorKind,
      UUID matchId,
      String teamId,
      String counterpartActorId,
      String encounterId,
      String decisionId,
      String planId,
      String evidenceId,
      String routeId,
      String type,
      Map<String, String> payload) {}

  private static long sessionTick() {
    HuntMcHunterAi mod = HuntMcHunterAi.instance();
    return mod == null ? 0L : mod.hunterRegistry().serverTick();
  }

  private static final class RotationState {
    private final String group;
    private final int index;
    private long bytes;
    private final Path path;

    private RotationState(String group, int index, long bytes, Path path) {
      this.group = group;
      this.index = index;
      this.bytes = bytes;
      this.path = path;
    }

    private int index() {
      return index;
    }

    private long bytes() {
      return bytes;
    }

    private Path path() {
      return path;
    }

    private void add(long amount) {
      bytes += amount;
    }
  }
}
