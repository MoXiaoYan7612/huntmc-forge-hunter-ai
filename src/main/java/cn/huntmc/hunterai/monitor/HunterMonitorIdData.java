package cn.huntmc.hunterai.monitor;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** World-persistent sequence source so a Hunter monitor number is never reused after a restart. */
public final class HunterMonitorIdData extends SavedData {
  private static final String DATA_NAME = "huntmc_hunter_ai_monitor_ids";
  private int nextId = 1;

  public static HunterMonitorIdData forLevel(ServerLevel level) {
    ServerLevel primaryLevel = level.getServer().overworld();
    return primaryLevel
        .getDataStorage()
        .computeIfAbsent(HunterMonitorIdData::load, HunterMonitorIdData::new, DATA_NAME);
  }

  public static HunterMonitorIdData load(CompoundTag tag) {
    HunterMonitorIdData data = new HunterMonitorIdData();
    data.nextId = Math.max(1, tag.getInt("NextHunterMonitorId"));
    return data;
  }

  public int reserveId() {
    int result = nextId;
    nextId++;
    setDirty();
    return result;
  }

  public void observeExistingId(int existingId) {
    if (existingId < nextId) return;
    nextId = existingId + 1;
    setDirty();
  }

  @Override
  public CompoundTag save(CompoundTag tag) {
    tag.putInt("NextHunterMonitorId", nextId);
    return tag;
  }
}
