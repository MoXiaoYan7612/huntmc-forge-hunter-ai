package cn.huntmc.hunterai.match;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.huntmc.hunterai.action.InteractionCommand;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HunterMatchServiceObserverTest {
  private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");

  @Test
  void attachesInitiallyAndWhenBukkitChangesTheTarget() {
    assertTrue(HunterMatchService.shouldAttachObserver(null, FIRST));
    assertTrue(HunterMatchService.shouldAttachObserver(FIRST, SECOND));
  }

  @Test
  void playerCanRemainInFreeCameraAfterLeavingTheSameTarget() {
    assertFalse(HunterMatchService.shouldAttachObserver(FIRST, FIRST));
    assertFalse(HunterMatchService.shouldAttachObserver(FIRST, null));
  }

  @Test
  void onlyAuthoritativeSeatRejectionsInvalidateTheForgeBinding() {
    assertTrue(
        HunterMatchService.invalidatesBinding(
            "Hunter interaction rejected: Hunter bot seat is unavailable"));
    assertTrue(HunterMatchService.invalidatesBinding("Hunter entity does not own this seat"));
    assertTrue(HunterMatchService.invalidatesBinding("Hunter seat has no bound entity"));
    assertFalse(HunterMatchService.invalidatesBinding("Hunter left the objective interaction range"));
    assertFalse(HunterMatchService.invalidatesBinding("boss has blocked line of sight"));
  }

  @Test
  void staleReviveRejectionsRefreshStateWithoutInvalidatingTheSeat() {
    String stale = "Hunter revive rejected: Hunter revive state is invalid";

    assertTrue(
        HunterMatchService.requiresStateRefreshAfterRejection(
            InteractionCommand.REVIVE, stale));
    assertFalse(HunterMatchService.invalidatesBinding(stale));
    assertFalse(
        HunterMatchService.requiresStateRefreshAfterRejection(
            InteractionCommand.START_BANISH, stale));
    assertFalse(
        HunterMatchService.requiresStateRefreshAfterRejection(
            InteractionCommand.REVIVE, "Hunter is outside revive range or line of sight"));
  }

  @Test
  void pendingObjectiveInteractionHasABoundedTimeout() {
    assertFalse(HunterMatchService.interactionTimedOut(Long.MIN_VALUE, 500L));
    assertFalse(HunterMatchService.interactionTimedOut(100L, 199L));
    assertTrue(HunterMatchService.interactionTimedOut(100L, 200L));
  }
}
