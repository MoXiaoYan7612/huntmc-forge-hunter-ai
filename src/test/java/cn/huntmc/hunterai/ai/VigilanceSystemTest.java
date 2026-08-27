package cn.huntmc.hunterai.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import net.minecraft.util.Mth;
import org.junit.jupiter.api.Test;

class VigilanceSystemTest {
  @Test
  void louderRecentNoiseCreatesMoreSideThreatThanAWeakFootstep() {
    double weakFootstep = VigilanceSystem.recentNoiseRisk(0.48D, 100L, 100L);
    double gunshot = VigilanceSystem.recentNoiseRisk(0.75D, 100L, 100L);

    assertTrue(gunshot > weakFootstep);
    assertEquals(0.0D, VigilanceSystem.recentNoiseRisk(0.75D, 100L, 200L), 0.0001D);
  }

  @Test
  void unknownSpaceAndImmediateEvidenceRemainSeparateRisks() {
    double unknown = VigilanceSystem.observationRisk(Long.MIN_VALUE, 1.0D, 1.0D, 100L);
    double noEvidence = VigilanceSystem.immediateThreatRisk(0.0D, 0L, Long.MIN_VALUE, 100L);
    double gunfire = VigilanceSystem.immediateThreatRisk(1.0D, 100L, Long.MIN_VALUE, 100L);
    double surprise = VigilanceSystem.immediateThreatRisk(0.0D, 0L, 100L, 100L);

    assertTrue(unknown > 0.0D);
    assertEquals(0.0D, noEvidence, 0.0001D);
    assertTrue(gunfire > 0.0D);
    assertTrue(surprise > 0.0D);
  }

  @Test
  void traversedRouteSafetyIsTemporary() {
    assertEquals(0.35D, VigilanceSystem.traversalSafety(100L, 100L), 0.0001D);
    assertTrue(VigilanceSystem.traversalSafety(100L, 300L) > 0.0D);
    assertEquals(0.0D, VigilanceSystem.traversalSafety(100L, 700L), 0.0001D);
  }

  @Test
  void cautiousScanRequiresEverySectorBeforeRequestingAnotherObservationPosition() {
    int scanMask = 0;
    for (int sector : new int[] {2, 0, 3}) {
      scanMask = VigilanceSystem.markSectorObserved(scanMask, sector);
      assertFalse(VigilanceSystem.completedScanCycle(scanMask));
    }

    scanMask = VigilanceSystem.markSectorObserved(scanMask, 1);

    assertTrue(VigilanceSystem.completedScanCycle(scanMask));
  }

  @Test
  void eachScanKeepsAStableButNonMetronomicBearingAndVerticalBand() {
    Random random = new Random(42L);

    float northYaw = VigilanceSystem.scanYawForSector(0, random);
    int duration = VigilanceSystem.scanDuration(20, random);
    float highPitch = VigilanceSystem.scanPitchForBand(0, random);
    float eyePitch = VigilanceSystem.scanPitchForBand(1, random);
    float lowPitch = VigilanceSystem.scanPitchForBand(2, random);

    assertTrue(northYaw >= 154.0F || northYaw <= -154.0F);
    assertTrue(duration >= 17 && duration <= 23);
    assertTrue(highPitch >= -32.0F && highPitch <= -16.0F);
    assertTrue(eyePitch >= -5.0F && eyePitch <= 5.0F);
    assertTrue(lowPitch >= 10.0F && lowPitch <= 23.0F);
  }

  @Test
  void evidenceFlankCycleCoversRearAndBothSidesBeforeRepeating() {
    int scanMask = 0;
    for (int direction : new int[] {2, 0}) {
      scanMask = VigilanceSystem.markEvidenceFlankDirectionChecked(scanMask, direction);
      assertFalse(VigilanceSystem.completedEvidenceFlankCycle(scanMask));
    }
    scanMask = VigilanceSystem.markEvidenceFlankDirectionChecked(scanMask, 1);

    assertTrue(VigilanceSystem.completedEvidenceFlankCycle(scanMask));
  }

  @Test
  void evidenceFlankBearingsStayAwayFromTheEvidenceDirection() {
    Random random = new Random(24L);
    float focusYaw = 15.0F;
    float rear = VigilanceSystem.evidenceFlankYaw(focusYaw, 0, random);
    float left = VigilanceSystem.evidenceFlankYaw(focusYaw, 1, random);
    float right = VigilanceSystem.evidenceFlankYaw(focusYaw, 2, random);

    assertTrue(Math.abs(Mth.wrapDegrees(rear - (focusYaw + 180.0F))) <= 18.0F);
    assertTrue(Math.abs(Mth.wrapDegrees(left - (focusYaw - 105.0F))) <= 18.0F);
    assertTrue(Math.abs(Mth.wrapDegrees(right - (focusYaw + 105.0F))) <= 18.0F);
    assertTrue(VigilanceSystem.evidenceFlankDuration(new Random(7L)) >= 6);
    assertTrue(VigilanceSystem.evidenceFlankDuration(new Random(7L)) <= 10);
  }

  @Test
  void travelGlanceDurationAccountsForPhysicalTurnWithoutTreatingYawWrapAsAFullTurn() {
    assertEquals(4, VigilanceSystem.travelGlanceDuration(0.0F, 0.0F));
    assertEquals(12, VigilanceSystem.travelGlanceDuration(0.0F, 90.0F));
    assertEquals(15, VigilanceSystem.travelGlanceDuration(0.0F, 180.0F));
    assertEquals(4, VigilanceSystem.travelGlanceDuration(179.0F, -179.0F));
  }
}
