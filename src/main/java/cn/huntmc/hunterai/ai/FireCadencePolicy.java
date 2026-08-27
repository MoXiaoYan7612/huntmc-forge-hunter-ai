package cn.huntmc.hunterai.ai;

import java.util.Random;

/** Chooses a small human timing variance around the actual TaCZ weapon cooldown. */
public final class FireCadencePolicy {
  private static final long TICKS_PER_MINUTE = 1_200L;

  public Selection select(
      int weaponRpm,
      long weaponMinimumIntervalTicks,
      CombatEvaluation combat,
      HunterPersonality personality,
      int shotsAtCurrentTarget,
      Random random) {
    int safeRpm = Math.max(1, weaponRpm);
    long minimumInterval = Math.max(1L, weaponMinimumIntervalTicks);
    // TaCZ owns the physical rate of fire. The first exchange must not add a second range,
    // pressure, or personality cooldown on top of that native interval.
    if (shotsAtCurrentTarget <= 3) {
      return new Selection(
          minimumInterval + random.nextInt(2),
          safeRpm,
          minimumInterval,
          "rapid",
          "native-exchange");
    }
    long measuredDelay = random.nextInt(3);
    return new Selection(
        minimumInterval + measuredDelay,
        safeRpm,
        minimumInterval,
        "measured",
        "bounded-human-variance");
  }

  public Selection select(
      int weaponRpm,
      CombatEvaluation combat,
      HunterPersonality personality,
      int shotsAtCurrentTarget,
      Random random) {
    return select(
        weaponRpm,
        minimumIntervalTicks(weaponRpm),
        combat,
        personality,
        shotsAtCurrentTarget,
        random);
  }

  public static long minimumIntervalTicks(int weaponRpm) {
    int safeRpm = Math.max(1, weaponRpm);
    return Math.max(1L, (TICKS_PER_MINUTE + safeRpm - 1L) / safeRpm);
  }

  public record Selection(
      long intervalTicks,
      int weaponRpm,
      long weaponMinimumIntervalTicks,
      String mode,
      String reason) {}
}
