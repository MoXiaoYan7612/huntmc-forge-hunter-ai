package cn.huntmc.hunterai.ai;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/** Main-thread cache for static terrain facts used by bounded tactical sampling. */
final class TacticalTerrainCache {
  static final long RETENTION_TICKS = 100L;
  private static final int PRUNE_THRESHOLD = 65_536;

  private final Map<String, Map<Long, ChunkBucket>> dimensions = new HashMap<>();
  private int entryCount;

  TerrainSample sample(ServerLevel level, BlockPos feet, long now, TerrainLoader loader) {
    if (!level.getServer().isSameThread()) {
      throw new IllegalStateException("Tactical terrain may only be sampled on the server thread");
    }
    String dimension = level.dimension().location().toString();
    long chunkKey = ChunkPos.asLong(feet.getX() >> 4, feet.getZ() >> 4);
    ChunkBucket chunk =
        dimensions
            .computeIfAbsent(dimension, ignored -> new HashMap<>())
            .computeIfAbsent(chunkKey, ignored -> new ChunkBucket());
    chunk.lastAccessAt = now;
    long blockKey = feet.asLong();
    Entry cached = chunk.entries.get(blockKey);
    if (cached != null && !expired(cached.sampledAt, now)) return cached.sample;

    TerrainSample sampled = loader.load(level, feet);
    if (cached == null) entryCount++;
    chunk.entries.put(blockKey, new Entry(sampled, now));
    if (entryCount > PRUNE_THRESHOLD) prune(now);
    return sampled;
  }

  void invalidateChunk(ServerLevel level, BlockPos changedPosition) {
    Map<Long, ChunkBucket> chunks = dimensions.get(level.dimension().location().toString());
    if (chunks == null) return;
    int centerX = changedPosition.getX() >> 4;
    int centerZ = changedPosition.getZ() >> 4;
    // Cover checks read cardinal neighbors, so a border block can affect a cached node in the
    // adjacent chunk. Invalidating this bounded 3x3 neighborhood also covers sky-column changes.
    for (int x = centerX - 1; x <= centerX + 1; x++) {
      for (int z = centerZ - 1; z <= centerZ + 1; z++) {
        ChunkBucket removed = chunks.remove(ChunkPos.asLong(x, z));
        if (removed != null) entryCount -= removed.entries.size();
      }
    }
    if (chunks.isEmpty()) dimensions.remove(level.dimension().location().toString());
  }

  void invalidateLevel(ServerLevel level) {
    Map<Long, ChunkBucket> removed = dimensions.remove(level.dimension().location().toString());
    if (removed == null) return;
    for (ChunkBucket chunk : removed.values()) entryCount -= chunk.entries.size();
  }

  void clear() {
    dimensions.clear();
    entryCount = 0;
  }

  int entryCount() {
    return entryCount;
  }

  static boolean expired(long sampledAt, long now) {
    return now - sampledAt >= RETENTION_TICKS || now < sampledAt;
  }

  private void prune(long now) {
    Iterator<Map.Entry<String, Map<Long, ChunkBucket>>> dimensionIterator =
        dimensions.entrySet().iterator();
    while (dimensionIterator.hasNext()) {
      Map<Long, ChunkBucket> chunks = dimensionIterator.next().getValue();
      Iterator<Map.Entry<Long, ChunkBucket>> chunkIterator = chunks.entrySet().iterator();
      while (chunkIterator.hasNext()) {
        ChunkBucket chunk = chunkIterator.next().getValue();
        int before = chunk.entries.size();
        chunk.entries.values().removeIf(entry -> expired(entry.sampledAt, now));
        entryCount -= before - chunk.entries.size();
        if (chunk.entries.isEmpty()) chunkIterator.remove();
      }
      if (chunks.isEmpty()) dimensionIterator.remove();
    }
    if (entryCount <= PRUNE_THRESHOLD) return;
    java.util.List<BucketReference> oldest = new java.util.ArrayList<>();
    for (Map.Entry<String, Map<Long, ChunkBucket>> dimension : dimensions.entrySet()) {
      for (Map.Entry<Long, ChunkBucket> chunk : dimension.getValue().entrySet()) {
        oldest.add(
            new BucketReference(
                dimension.getKey(), chunk.getKey(), chunk.getValue().lastAccessAt));
      }
    }
    oldest.sort(java.util.Comparator.comparingLong(BucketReference::lastAccessAt));
    for (BucketReference reference : oldest) {
      if (entryCount <= PRUNE_THRESHOLD) break;
      Map<Long, ChunkBucket> chunks = dimensions.get(reference.dimension());
      if (chunks == null) continue;
      ChunkBucket removed = chunks.remove(reference.chunkKey());
      if (removed != null) entryCount -= removed.entries.size();
      if (chunks.isEmpty()) dimensions.remove(reference.dimension());
    }
  }

  @FunctionalInterface
  interface TerrainLoader {
    TerrainSample load(ServerLevel level, BlockPos feet);
  }

  record TerrainSample(boolean standable, double cover, boolean openToSky, int escapeDirections) {}

  private record Entry(TerrainSample sample, long sampledAt) {}

  private record BucketReference(String dimension, long chunkKey, long lastAccessAt) {}

  private static final class ChunkBucket {
    private final Map<Long, Entry> entries = new HashMap<>();
    private long lastAccessAt;
  }
}
