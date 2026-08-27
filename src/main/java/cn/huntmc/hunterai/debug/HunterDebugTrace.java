package cn.huntmc.hunterai.debug;

import java.util.ArrayList;
import java.util.List;

/** Bounded server-thread trace for explaining one Hunter's recent decisions without log spam. */
public final class HunterDebugTrace {
  private static final int CAPACITY = 384;

  private final TraceEntry[] entries = new TraceEntry[CAPACITY];
  private int cursor;
  private int size;

  public void record(long gameTime, String category, String detail) {
    entries[cursor] = new TraceEntry(gameTime, category, detail);
    cursor = (cursor + 1) % CAPACITY;
    size = Math.min(size + 1, CAPACITY);
  }

  public void clear() {
    for (int index = 0; index < entries.length; index++) entries[index] = null;
    cursor = 0;
    size = 0;
  }

  public List<String> latest(int requestedLines) {
    int count = Math.min(Math.max(requestedLines, 0), size);
    List<String> result = new ArrayList<>(count);
    for (int offset = count - 1; offset >= 0; offset--) {
      int index = Math.floorMod(cursor - 1 - offset, CAPACITY);
      TraceEntry entry = entries[index];
      if (entry != null) result.add(entry.format());
    }
    return result;
  }

  public String latestOr(String fallback) {
    if (size == 0) return fallback;
    TraceEntry entry = entries[Math.floorMod(cursor - 1, CAPACITY)];
    return entry == null ? fallback : entry.format();
  }

  public int size() {
    return size;
  }

  private record TraceEntry(long gameTime, String category, String detail) {
    private String format() {
      return "t=" + gameTime + " " + category + " " + detail;
    }
  }
}
