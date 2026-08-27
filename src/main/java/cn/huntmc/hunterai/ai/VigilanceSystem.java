package cn.huntmc.hunterai.ai;

import cn.huntmc.hunterai.config.HunterAiConfig;
import cn.huntmc.hunterai.entity.HunterEntity;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Schedules unobserved-sector checks without directly fighting the main aim controller. */
public final class VigilanceSystem {
  private static final int SECTOR_COUNT = 4;
  private static final int EVIDENCE_FLANK_DIRECTION_COUNT = 3;
  private static final long EVIDENCE_FOCUS_TICKS = 12L;
  private final long[] lastObserved = new long[SECTOR_COUNT];
  private final long[] lastTraversed = new long[SECTOR_COUNT];
  private final long[] lastNoise = new long[SECTOR_COUNT];
  private final double[] noiseReliability = new double[SECTOR_COUNT];
  private final long[] lastSurprise = new long[SECTOR_COUNT];
  private final double[] accessibility = new double[SECTOR_COUNT];
  private final double[] unknownExposure = new double[SECTOR_COUNT];
  private long nextCheckTime;
  private int scanCycleMask;
  private int scanPitchCycleMask;
  private boolean observationRelocationPending;
  private Float activeScanYaw;
  private float activeScanPitch;
  private int activeScanSector = -1;
  private boolean activeScanObservationConfirmed;
  private long activeScanStartedAt = Long.MIN_VALUE;
  private int activeScanDurationTicks;
  private long nextLostTargetSearchAt;
  private int lostTargetSearchStep;
  private Float activeLostTargetSearchYaw;
  private long processedNoiseTime = Long.MIN_VALUE;
  private long processedSurpriseTime = Long.MIN_VALUE;
  private long evidenceFocusStartedAt = Long.MIN_VALUE;
  private float evidenceFocusYaw;
  private long nextEvidenceFlankCheckAt = Long.MIN_VALUE;
  private int evidenceFlankCycleMask;
  private Float activeEvidenceFlankYaw;
  private float activeEvidenceFlankPitch;
  private long activeEvidenceFlankStartedAt = Long.MIN_VALUE;
  private long activeEvidenceFlankEndsAt = Long.MIN_VALUE;

  public VigilanceSystem() {
    // A freshly spawned Hunter has not inspected any direction yet. Unknown space starts risky
    // and is reduced only after the head actually settles on that sector.
    Arrays.fill(lastObserved, Long.MIN_VALUE);
    Arrays.fill(lastTraversed, Long.MIN_VALUE);
  }

  public void refresh(
      TacticalMap tacticalMap,
      HunterEntity hunter,
      Vec3 noiseSource,
      long noiseTime,
      double heardReliability,
      Vec3 surpriseSource,
      long surpriseTime) {
    long now = hunter.level().getGameTime();
    for (int sector = 0; sector < SECTOR_COUNT; sector++) {
      accessibility[sector] = tacticalMap.sectorAccessibility(sector);
      unknownExposure[sector] = tacticalMap.sectorUnknownExposure(sector);
    }
    if (noiseSource != null && noiseTime > processedNoiseTime) {
      int sector = sectorForDirection(hunter, noiseSource);
      lastNoise[sector] = noiseTime;
      noiseReliability[sector] = Math.max(0.0D, Math.min(1.0D, heardReliability));
      processedNoiseTime = noiseTime;
    }
    if (surpriseSource != null && surpriseTime > processedSurpriseTime) {
      lastSurprise[sectorForDirection(hunter, surpriseSource)] = surpriseTime;
      processedSurpriseTime = surpriseTime;
    }
  }

  public Float nextScanYaw(HunterEntity hunter, boolean targetVisible, boolean vulnerable, Random random) {
    long now = hunter.level().getGameTime();
    if (targetVisible && !vulnerable) {
      confirmObservation(hunter.getYHeadRot(), now);
      activeScanYaw = null;
      activeScanSector = -1;
      activeScanObservationConfirmed = false;
      activeScanStartedAt = Long.MIN_VALUE;
      activeScanDurationTicks = 0;
      scanCycleMask = 0;
      scanPitchCycleMask = 0;
      observationRelocationPending = false;
      return null;
    }
    if (now < nextCheckTime) return activeScanYaw;
    int rearSector = sectorFor(hunter.getYRot() + 180.0F);
    long rearDeadline = 40L + Math.floorMod(hunter.getUUID().hashCode(), 41);
    int sector = unseenTicks(lastObserved[rearSector], now) >= rearDeadline
        ? rearSector
        : highestRiskUnscannedSector(now, random);
    // Keep returning the deliberate look target for the whole interval. This gives the action
    // controller time to turn, dwell, and confirm a contact instead of rotating one step only.
    int baseDuration =
        vulnerable
            ? HunterAiConfig.COVER_SCAN_INTERVAL_TICKS.get()
            : HunterAiConfig.OPEN_SCAN_INTERVAL_TICKS.get();
    activeScanDurationTicks = scanDuration(baseDuration, random);
    nextCheckTime = now + activeScanDurationTicks;
    activeScanYaw = scanYawForSector(sector, random);
    activeScanSector = sector;
    activeScanObservationConfirmed = false;
    int pitchBand = nextScanPitchBand(random);
    activeScanPitch = scanPitchForBand(pitchBand, random);
    activeScanStartedAt = now;
    return activeScanYaw;
  }

  /**
   * Records information only after a selected head direction was physically reached. Scheduling
   * a turn cannot make a blind sector safe: a blocked turn, interruption, or combat event leaves
   * its uncertainty intact.
   */
  public boolean confirmObservation(float actualYaw, long now) {
    int observedSector = sectorFor(actualYaw);
    lastObserved[observedSector] = now;
    if (activeScanSector != observedSector || activeScanObservationConfirmed) return false;
    activeScanObservationConfirmed = true;
    scanCycleMask = markSectorObserved(scanCycleMask, observedSector);
    if (!completedScanCycle(scanCycleMask)) return true;
    observationRelocationPending = true;
    scanCycleMask = 0;
    return true;
  }

  /**
   * A just-traversed direction is temporarily less uncertain than untouched space. This is only
   * a soft information memory: fresh sound or surprise evidence still takes precedence, and the
   * confidence decays back to normal over thirty seconds.
   */
  public void noteTraversal(Vec3 from, Vec3 to, long now) {
    if (from == null || to == null) return;
    double dx = to.x - from.x;
    double dz = to.z - from.z;
    if (dx * dx + dz * dz < 4.0D) return;
    lastTraversed[sectorForDirection(to, from)] = now;
  }

  public boolean scanStartedAt(long now) {
    return activeScanYaw != null && activeScanStartedAt == now;
  }

  /**
   * Keeps an evidence bearing in view briefly, then forces a short back or side check on an
   * independent cadence. Continuous gunfire must not starve these checks by repeatedly refreshing
   * the same belief timestamp.
   */
  public Float nextEvidenceFlankScanYaw(
      HunterEntity hunter, Vec3 evidence, long now, Random random) {
    if (evidence == null) {
      resetEvidenceFlankScan();
      return null;
    }
    float focusYaw = yawToward(hunter, evidence);
    if (evidenceFocusStartedAt == Long.MIN_VALUE
        || Math.abs(Mth.wrapDegrees(focusYaw - evidenceFocusYaw)) >= 70.0F) {
      beginEvidenceFocus(focusYaw, now);
    }
    if (activeEvidenceFlankYaw != null && now < activeEvidenceFlankEndsAt) {
      return activeEvidenceFlankYaw;
    }
    if (now < nextEvidenceFlankCheckAt) return null;
    activeEvidenceFlankYaw =
        evidenceFlankYaw(evidenceFocusYaw, nextEvidenceFlankDirection(random), random);
    activeEvidenceFlankPitch = scanPitchForBand(nextScanPitchBand(random), random);
    activeEvidenceFlankStartedAt = now;
    activeEvidenceFlankEndsAt = now + evidenceFlankDuration(random);
    nextEvidenceFlankCheckAt = activeEvidenceFlankEndsAt + 16L + random.nextInt(13);
    return activeEvidenceFlankYaw;
  }

  public boolean evidenceFlankScanStartedAt(long now) {
    return activeEvidenceFlankYaw != null && activeEvidenceFlankStartedAt == now;
  }

  /** Returns the stable pitch belonging to the current evidence flank dwell. */
  public float activeEvidenceFlankPitch() {
    return activeEvidenceFlankPitch;
  }

  public void resetEvidenceFlankScan() {
    evidenceFocusStartedAt = Long.MIN_VALUE;
    nextEvidenceFlankCheckAt = Long.MIN_VALUE;
    evidenceFlankCycleMask = 0;
    activeEvidenceFlankYaw = null;
    activeEvidenceFlankStartedAt = Long.MIN_VALUE;
    activeEvidenceFlankEndsAt = Long.MIN_VALUE;
  }

  /** Returns one held vertical look target for the current scan dwell. */
  public float activeScanPitch(long now) {
    return activeScanYaw == null || activeScanStartedAt == Long.MIN_VALUE ? 0.0F : activeScanPitch;
  }

  /**
   * A sector stays inside its 90-degree quadrant, but its exact bearing varies. The sector cycle
   * still guarantees all four directions are deliberately checked before the next relocation.
   */
  static float scanYawForSector(int sector, Random random) {
    float center = Math.floorMod(sector, SECTOR_COUNT) * 90.0F - 180.0F;
    float offset = -26.0F + random.nextFloat() * 52.0F;
    return Mth.wrapDegrees(center + offset);
  }

  /** Samples a held dwell time, preventing a metronomic scan without returning to head flicking. */
  static int scanDuration(int baseDuration, Random random) {
    return Math.max(8, baseDuration - 3 + random.nextInt(7));
  }

  /** Gives a travel glance enough ticks to physically reach the selected head bearing. */
  public static int travelGlanceDuration(float currentYaw, float selectedYaw) {
    float boundedTurn = Math.min(110.0F, Math.abs(Mth.wrapDegrees(selectedYaw - currentYaw)));
    return Mth.clamp((int) Math.ceil(boundedTurn / 9.0F) + 2, 4, 15);
  }

  static float evidenceFlankYaw(float evidenceYaw, int direction, Random random) {
    float offset = switch (Math.floorMod(direction, EVIDENCE_FLANK_DIRECTION_COUNT)) {
      case 0 -> 180.0F;
      case 1 -> -105.0F;
      default -> 105.0F;
    };
    return Mth.wrapDegrees(evidenceYaw + offset - 18.0F + random.nextFloat() * 36.0F);
  }

  static int evidenceFlankDuration(Random random) {
    return evidenceFlankDurationLowerBound() + random.nextInt(5);
  }

  static int markEvidenceFlankDirectionChecked(int scanMask, int direction) {
    return scanMask | (1 << Math.floorMod(direction, EVIDENCE_FLANK_DIRECTION_COUNT));
  }

  static boolean completedEvidenceFlankCycle(int scanMask) {
    return scanMask == (1 << EVIDENCE_FLANK_DIRECTION_COUNT) - 1;
  }

  /**
   * Produces a different exact pitch inside the selected vertical band. The band cycle preserves
   * high, low, and eye-level coverage while their ordering remains unpredictable.
   */
  static float scanPitchForBand(int band, Random random) {
    return switch (Math.floorMod(band, 3)) {
      case 0 -> -16.0F - random.nextFloat() * 16.0F;
      case 1 -> -5.0F + random.nextFloat() * 10.0F;
      default -> 10.0F + random.nextFloat() * 13.0F;
    };
  }

  /** Signals that a protected Hunter has deliberately checked every sector once. */
  public boolean consumeObservationRelocation() {
    boolean pending = observationRelocationPending;
    observationRelocationPending = false;
    return pending;
  }

  static int markSectorObserved(int scanMask, int sector) {
    return scanMask | (1 << Math.floorMod(sector, SECTOR_COUNT));
  }

  static boolean completedScanCycle(int scanMask) {
    return scanMask == (1 << SECTOR_COUNT) - 1;
  }

  /**
   * Searches around the last credible direction in a bounded, repeatable sequence. This is not a
   * random sweep: it starts with the last contact, then checks the two likely exits before wider
   * sectors receive attention.
   */
  public Float nextLostTargetSearchYaw(HunterEntity hunter, Vec3 lastKnownPosition, long now) {
    if (lastKnownPosition == null) return null;
    Vec3 offset = lastKnownPosition.subtract(hunter.position());
    if (offset.lengthSqr() < 1.0E-6D) return null;
    if (now < nextLostTargetSearchAt) return activeLostTargetSearchYaw;
    float focusYaw = (float) (Mth.atan2(offset.z, offset.x) * (180.0D / Math.PI)) - 90.0F;
    float[] offsets = {0.0F, -32.0F, 32.0F, -68.0F, 68.0F, -105.0F, 105.0F};
    float result = Mth.wrapDegrees(focusYaw + offsets[lostTargetSearchStep % offsets.length]);
    lostTargetSearchStep++;
    nextLostTargetSearchAt = now + 6L;
    activeLostTargetSearchYaw = result;
    return activeLostTargetSearchYaw;
  }

  public void resetLostTargetSearch() {
    lostTargetSearchStep = 0;
    nextLostTargetSearchAt = 0L;
    activeLostTargetSearchYaw = null;
  }

  private void beginEvidenceFocus(float focusYaw, long now) {
    evidenceFocusStartedAt = now;
    evidenceFocusYaw = focusYaw;
    nextEvidenceFlankCheckAt = now + EVIDENCE_FOCUS_TICKS;
    evidenceFlankCycleMask = 0;
    activeEvidenceFlankYaw = null;
    activeEvidenceFlankStartedAt = Long.MIN_VALUE;
    activeEvidenceFlankEndsAt = Long.MIN_VALUE;
  }

  private int nextEvidenceFlankDirection(Random random) {
    if (completedEvidenceFlankCycle(evidenceFlankCycleMask)) evidenceFlankCycleMask = 0;
    int[] choices = new int[EVIDENCE_FLANK_DIRECTION_COUNT];
    int count = 0;
    for (int direction = 0; direction < EVIDENCE_FLANK_DIRECTION_COUNT; direction++) {
      if ((evidenceFlankCycleMask & (1 << direction)) == 0) choices[count++] = direction;
    }
    int selected = choices[random.nextInt(count)];
    evidenceFlankCycleMask = markEvidenceFlankDirectionChecked(evidenceFlankCycleMask, selected);
    return selected;
  }

  private static int evidenceFlankDurationLowerBound() {
    return 6;
  }

  /** Chooses an unobserved sector because it is risky, completing a full cycle before repeats. */
  private int highestRiskUnscannedSector(long now, Random random) {
    double highestRisk = Double.NEGATIVE_INFINITY;
    int selected = 0;
    for (int sector = 0; sector < SECTOR_COUNT; sector++) {
      if ((scanCycleMask & (1 << sector)) != 0) continue;
      double risk = risk(sector, now);
      if (risk > highestRisk || risk == highestRisk && random.nextBoolean()) {
        highestRisk = risk;
        selected = sector;
      }
    }
    return selected;
  }

  private int nextScanPitchBand(Random random) {
    if (scanPitchCycleMask == 0b111) scanPitchCycleMask = 0;
    int[] choices = new int[3];
    int count = 0;
    for (int band = 0; band < 3; band++) {
      if ((scanPitchCycleMask & (1 << band)) == 0) choices[count++] = band;
    }
    int selected = choices[random.nextInt(count)];
    scanPitchCycleMask |= 1 << selected;
    return selected;
  }

  /**
   * Risk created by unobserved, traversable space. It guides where the Hunter looks next, but it
   * is not evidence that an enemy is attacking and must never by itself trigger a retreat.
   */
  public double unknownRisk(long now) {
    double highest = 0.0D;
    for (int sector = 0; sector < SECTOR_COUNT; sector++) {
      highest = Math.max(highest, traversalAdjustedObservationRisk(sector, now));
    }
    return Math.min(1.0D, highest);
  }

  /**
   * Risk backed by a recent audible event or a known surprise source. Unlike {@link
   * #unknownRisk(long)}, this may increase defensive movement utility.
   */
  public double immediateThreatRisk(long now) {
    double highest = 0.0D;
    for (int sector = 0; sector < SECTOR_COUNT; sector++) {
      highest = Math.max(highest, immediateThreatRisk(sector, now));
    }
    return Math.min(1.0D, highest);
  }

  public String summary(long now) {
    StringBuilder summary = new StringBuilder();
    for (int sector = 0; sector < SECTOR_COUNT; sector++) {
      if (sector > 0) summary.append(',');
      summary
          .append(name(sector))
          .append(" risk=")
          .append(String.format(Locale.ROOT, "%.2f", risk(sector, now)))
          .append(" unknownRisk=")
          .append(String.format(Locale.ROOT, "%.2f", traversalAdjustedObservationRisk(sector, now)))
          .append(" immediateRisk=")
          .append(String.format(Locale.ROOT, "%.2f", immediateThreatRisk(sector, now)))
          .append(" unseen=")
          .append(unseenTicks(lastObserved[sector], now))
          .append(" access=")
          .append(String.format(Locale.ROOT, "%.2f", accessibility[sector]))
          .append(" unknown=")
          .append(String.format(Locale.ROOT, "%.2f", unknownExposure[sector]));
    }
    return summary.toString();
  }

  private double risk(int sector, long now) {
    double immediate = immediateThreatRisk(sector, now);
    // A credible gunshot or surprise invalidates the comfort of having just walked that way.
    return (immediate > 0.0D ? observationRisk(sector, now) : traversalAdjustedObservationRisk(sector, now))
        + immediate;
  }

  private double observationRisk(int sector, long now) {
    return observationRisk(lastObserved[sector], accessibility[sector], unknownExposure[sector], now);
  }

  private double traversalAdjustedObservationRisk(int sector, long now) {
    return Math.max(0.0D, observationRisk(sector, now) - traversalSafety(lastTraversed[sector], now));
  }

  private double immediateThreatRisk(int sector, long now) {
    return immediateThreatRisk(noiseReliability[sector], lastNoise[sector], lastSurprise[sector], now);
  }

  static double observationRisk(
      long observedAt, double sectorAccessibility, double sectorUnknownExposure, long now) {
    double unseen = Math.min(1.0D, unseenTicks(observedAt, now) / 160.0D);
    return unseen * 0.35D
        + clampRiskInput(sectorAccessibility) * 0.25D
        + clampRiskInput(sectorUnknownExposure) * 0.20D;
  }

  static double immediateThreatRisk(
      double reliability, long heardAt, long surprisedAt, long now) {
    return recentNoiseRisk(reliability, heardAt, now) + recentSurpriseRisk(surprisedAt, now);
  }

  private static int sectorForDirection(HunterEntity hunter, Vec3 source) {
    return sectorForDirection(hunter.position(), source);
  }

  private static int sectorForDirection(Vec3 origin, Vec3 source) {
    Vec3 offset = source.subtract(origin);
    float yaw = (float) (Mth.atan2(offset.z, offset.x) * (180.0D / Math.PI)) - 90.0F;
    return sectorFor(yaw);
  }

  private static float yawToward(HunterEntity hunter, Vec3 target) {
    Vec3 offset = target.subtract(hunter.position());
    return (float) (Mth.atan2(offset.z, offset.x) * (180.0D / Math.PI)) - 90.0F;
  }

  static double recentNoiseRisk(double reliability, long heardAt, long now) {
    if (heardAt == Long.MIN_VALUE) return 0.0D;
    double ageFactor = Math.max(0.0D, 1.0D - Math.max(0L, now - heardAt) / 100.0D);
    return ageFactor * clampRiskInput(reliability) * 0.35D;
  }

  static double recentSurpriseRisk(long surprisedAt, long now) {
    if (surprisedAt == Long.MIN_VALUE) return 0.0D;
    double ageFactor = Math.max(0.0D, 1.0D - Math.max(0L, now - surprisedAt) / 160.0D);
    return ageFactor * 0.55D;
  }

  static double traversalSafety(long traversedAt, long now) {
    if (traversedAt == Long.MIN_VALUE) return 0.0D;
    double age = Math.max(0L, now - traversedAt);
    return Math.max(0.0D, 1.0D - age / 600.0D) * 0.35D;
  }

  static long unseenTicks(long observedAt, long now) {
    if (observedAt == Long.MIN_VALUE) return 160L;
    return Math.max(0L, now - observedAt);
  }

  private static double clampRiskInput(double value) {
    return Math.max(0.0D, Math.min(1.0D, value));
  }

  private static String name(int sector) {
    return switch (sector) {
      case 0 -> "north";
      case 1 -> "east";
      case 2 -> "south";
      default -> "west";
    };
  }

  private static int sectorFor(float yaw) {
    return Math.floorMod(Math.round((Mth.wrapDegrees(yaw) + 180.0F) / 90.0F), SECTOR_COUNT);
  }
}
