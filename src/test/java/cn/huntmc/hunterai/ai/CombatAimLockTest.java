package cn.huntmc.hunterai.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class CombatAimLockTest {
  @Test
  void retainsOnlyTheLastLegallyObservedPointForItsBoundedWindow() {
    CombatAimLock lock = new CombatAimLock(18L);
    Vec3 observed = new Vec3(12.0D, 65.6D, -4.0D);

    lock.observe(observed, 100L);

    assertEquals(observed, lock.point(100L));
    assertEquals(observed, lock.point(117L));
    assertEquals(1L, lock.remainingTicks(117L));
    assertNull(lock.point(118L));
    assertEquals(0L, lock.remainingTicks(118L));
  }

  @Test
  void aNewLegalObservationReplacesRatherThanPredictsTheAimPoint() {
    CombatAimLock lock = new CombatAimLock(18L);
    Vec3 first = new Vec3(2.0D, 64.0D, 2.0D);
    Vec3 second = new Vec3(8.0D, 65.0D, -1.0D);

    lock.observe(first, 100L);
    lock.observe(second, 109L);

    assertEquals(second, lock.point(109L));
    assertEquals(second, lock.point(126L));
    assertNull(lock.point(127L));
  }

  @Test
  void hunterCombatLockIsOnlyAShortLastSightConfirmation() {
    CombatAimLock lock = new CombatAimLock(5L);
    lock.observe(new Vec3(8.0D, 65.0D, -1.0D), 100L);

    assertEquals(1L, lock.remainingTicks(104L));
    assertNull(lock.point(105L));
  }

  @Test
  void confirmedDeathClearsTheRetainedAimImmediately() {
    CombatAimLock lock = new CombatAimLock(18L);
    lock.observe(new Vec3(8.0D, 65.0D, -1.0D), 100L);

    lock.clear();

    assertNull(lock.point(100L));
    assertEquals(0L, lock.remainingTicks(100L));
  }
}
