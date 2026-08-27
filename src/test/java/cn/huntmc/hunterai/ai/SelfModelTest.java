package cn.huntmc.hunterai.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SelfModelTest {
  @Test
  void freshHunterIsNotTreatedAsRecentlyShotOrDamaged() {
    SelfModel model = new SelfModel();
    SelfModel.Snapshot fresh = model.snapshot(20.0F, 20.0F, false, 413_415_750L);

    assertFalse(fresh.recentlyFired());
    assertFalse(fresh.recentlyDamaged());
  }

  @Test
  void recentEventsExpireWithoutTimestampOverflow() {
    SelfModel model = new SelfModel();
    model.recordShot(100L, 0.0D);
    model.recordDamage(100L, 1.0D, 20.0D);

    SelfModel.Snapshot recent = model.snapshot(20.0F, 20.0F, false, 140L);
    SelfModel.Snapshot expired = model.snapshot(20.0F, 20.0F, false, 161L);

    assertTrue(recent.recentlyFired());
    assertTrue(recent.recentlyDamaged());
    assertFalse(expired.recentlyFired());
    assertFalse(expired.recentlyDamaged());
  }
}
