package cn.huntmc.hunterai.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class HunterMonitorIdDataTest {
  @Test
  void persistsAndAdvancesTheHunterMonitorSequence() {
    HunterMonitorIdData original = new HunterMonitorIdData();
    assertEquals(1, original.reserveId());
    assertEquals(2, original.reserveId());
    original.observeExistingId(40);

    CompoundTag saved = original.save(new CompoundTag());
    HunterMonitorIdData restored = HunterMonitorIdData.load(saved);

    assertEquals(41, restored.reserveId());
  }
}
