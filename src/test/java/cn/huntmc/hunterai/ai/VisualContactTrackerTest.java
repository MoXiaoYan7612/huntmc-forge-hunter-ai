package cn.huntmc.hunterai.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class VisualContactTrackerTest {
  private static final UUID TARGET = new UUID(11L, 12L);
  private static final UUID SECOND_TARGET = new UUID(13L, 14L);

  @Test
  void confirmsOnlyAfterRequiredConsecutiveLegalObservations() {
    VisualContactTracker tracker = new VisualContactTracker();

    assertEquals(
        PerceptionSystem.ContactState.ACQUIRING, tracker.observe(TARGET, 3, 100L).state());
    assertEquals(
        PerceptionSystem.ContactState.ACQUIRING, tracker.observe(TARGET, 3, 101L).state());
    assertEquals(
        PerceptionSystem.ContactState.CONFIRMED, tracker.observe(TARGET, 3, 102L).state());
    assertEquals(TARGET, tracker.confirmedTarget());
  }

  @Test
  void scheduledLegalObservationsKeepAcquisitionProgress() {
    VisualContactTracker tracker = new VisualContactTracker();

    assertEquals(
        PerceptionSystem.ContactState.ACQUIRING, tracker.observe(TARGET, 3, 100L).state());
    assertEquals(
        PerceptionSystem.ContactState.ACQUIRING, tracker.observe(TARGET, 3, 103L).state());
    assertEquals(
        PerceptionSystem.ContactState.CONFIRMED, tracker.observe(TARGET, 3, 104L).state());
  }

  @Test
  void aLongGapStillRequiresFreshVisualConfirmation() {
    VisualContactTracker tracker = new VisualContactTracker();
    tracker.observe(TARGET, 3, 100L);

    VisualContactTracker.Contact contact = tracker.observe(TARGET, 3, 105L);

    assertEquals(PerceptionSystem.ContactState.ACQUIRING, contact.state());
    assertEquals(1, contact.observations());
  }

  @Test
  void brokenLineOfSightDiscardsPartialContactAndCoordinateAccess() {
    VisualContactTracker tracker = new VisualContactTracker();
    tracker.observe(TARGET, 3, 100L);

    VisualContactTracker.Contact contact = tracker.missed(101L, false);

    assertEquals(PerceptionSystem.ContactState.NONE, contact.state());
    assertFalse(tracker.hasPendingContact());
    assertNull(tracker.confirmedTarget());
  }

  @Test
  void rayBudgetRejectionDoesNotPretendTheTargetVanished() {
    VisualContactTracker tracker = new VisualContactTracker();
    tracker.observe(TARGET, 3, 100L);

    VisualContactTracker.Contact contact = tracker.missed(101L, true);

    assertEquals(PerceptionSystem.ContactState.ACQUIRING, contact.state());
    assertTrue(tracker.hasPendingContact());
    assertEquals(
        PerceptionSystem.ContactState.ACQUIRING, tracker.observe(TARGET, 3, 102L).state());
  }

  @Test
  void aLegalPendingContactSurvivesOnlyTheBriefHeadTurnGap() {
    VisualContactTracker tracker = new VisualContactTracker();
    tracker.observe(TARGET, 3, 100L);

    VisualContactTracker.Contact turning = tracker.missed(101L, false, true);

    assertEquals(PerceptionSystem.ContactState.ACQUIRING, turning.state());
    assertTrue(tracker.hasPendingContact());
    assertEquals(
        PerceptionSystem.ContactState.ACQUIRING, tracker.observe(TARGET, 3, 102L).state());

    VisualContactTracker.Contact expired = tracker.missed(109L, false, true);
    assertEquals(PerceptionSystem.ContactState.NONE, expired.state());
    assertFalse(tracker.hasPendingContact());
  }

  @Test
  void confirmedContactSurvivesOnlyABriefVisualDropoutWithoutRestoringTargetAccess() {
    VisualContactTracker tracker = new VisualContactTracker();
    tracker.observe(TARGET, 2, 100L);
    tracker.observe(TARGET, 2, 101L);

    VisualContactTracker.Contact briefMiss = tracker.missed(102L, false);

    assertEquals(PerceptionSystem.ContactState.CONFIRMED, briefMiss.state());
    assertEquals(TARGET, briefMiss.target());
    assertTrue(tracker.hasActiveContact());
    assertTrue(tracker.hasConfirmedContact());
    assertEquals(
        PerceptionSystem.ContactState.CONFIRMED, tracker.observe(TARGET, 2, 103L).state());

    VisualContactTracker.Contact stillBrief = tracker.missed(106L, false);
    assertEquals(PerceptionSystem.ContactState.CONFIRMED, stillBrief.state());

    VisualContactTracker.Contact expiredMiss = tracker.missed(109L, false);

    assertEquals(PerceptionSystem.ContactState.NONE, expiredMiss.state());
    assertNull(tracker.confirmedTarget());
    assertFalse(tracker.hasConfirmedContact());
  }

  @Test
  void aDifferentVisibleEntityMustCompleteItsOwnConfirmation() {
    VisualContactTracker tracker = new VisualContactTracker();
    tracker.observe(TARGET, 2, 100L);

    VisualContactTracker.Contact switched = tracker.observe(SECOND_TARGET, 2, 101L);

    assertEquals(PerceptionSystem.ContactState.ACQUIRING, switched.state());
    assertEquals(SECOND_TARGET, switched.target());
    assertEquals(1, switched.observations());
    assertEquals(
        PerceptionSystem.ContactState.CONFIRMED, tracker.observe(SECOND_TARGET, 2, 102L).state());
  }
}
