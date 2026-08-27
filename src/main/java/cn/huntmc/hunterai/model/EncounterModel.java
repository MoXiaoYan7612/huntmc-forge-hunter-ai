package cn.huntmc.hunterai.model;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/** Infers a multi-party fight only from recent legal evidence. */
public final class EncounterModel {
  private static final long WINDOW_TICKS = 100L;
  private final Deque<Evidence> combatEvidence = new ArrayDeque<>();

  public void accept(Evidence evidence) {
    if (evidence.type() == Evidence.Type.GUNSHOT
        || evidence.type() == Evidence.Type.DAMAGE
        || evidence.type() == Evidence.Type.DEATH
        || evidence.type() == Evidence.Type.VISUAL) combatEvidence.addLast(evidence);
  }

  public Snapshot snapshot(long now) {
    while (!combatEvidence.isEmpty() && now - combatEvidence.peekFirst().observedAt() > WINDOW_TICKS)
      combatEvidence.removeFirst();
    Set<String> independentSources = new HashSet<>();
    Set<String> confirmedCombatSources = new HashSet<>();
    int shots = 0;
    int deaths = 0;
    for (Evidence evidence : combatEvidence) {
      independentSources.add(evidence.independenceKey());
      if (evidence.type() == Evidence.Type.GUNSHOT) shots++;
      if (evidence.type() == Evidence.Type.DEATH) deaths++;
      // A sound identifies a possible direction, not a fight.  Only visual contact,
      // damage, or a confirmed death can confirm that the sound represents combat.
      if (evidence.type() == Evidence.Type.VISUAL
          || evidence.type() == Evidence.Type.DAMAGE
          || evidence.type() == Evidence.Type.DEATH) {
        confirmedCombatSources.add(evidence.independenceKey());
      }
    }
    boolean multiParty =
        (confirmedCombatSources.size() >= 2 && shots >= 1)
            || (deaths > 0 && confirmedCombatSources.size() >= 2);
    double confidence = Math.min(1.0D, independentSources.size() * 0.22D + shots * 0.08D + deaths * 0.20D);
    return new Snapshot(multiParty, confidence, independentSources.size(), shots, deaths);
  }

  public record Snapshot(boolean multiPartyFight, double confidence, int independentSources, int shots, int deaths) {}
}
