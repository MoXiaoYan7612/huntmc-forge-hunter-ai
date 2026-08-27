package cn.huntmc.hunterai.ai;

import net.minecraft.world.phys.Vec3;

/** A short commitment with observable stages, rather than a one-tick intent label. */
public final class TacticalPlan {
  private HunterIntent intent = HunterIntent.LISTEN;
  private Vec3 position;
  private Stage stage = Stage.APPROACH;
  private long startedAt;
  private String reason = "initial";

  public void begin(HunterIntent nextIntent, Vec3 nextPosition, long now, String nextReason) {
    intent = nextIntent;
    position = nextPosition;
    startedAt = now;
    reason = nextReason;
    stage = nextPosition == null ? Stage.OBSERVE : Stage.APPROACH;
  }

  public Stage update(boolean atPosition, boolean engageNow, boolean pressured, long now) {
    if (engageNow) stage = Stage.ENGAGE;
    else if (pressured && (intent == HunterIntent.REPOSITION || intent == HunterIntent.DISENGAGE)) {
      stage = Stage.ESCAPE;
    } else if (stage == Stage.ENGAGE) {
      stage = atPosition ? Stage.SETTLE : Stage.APPROACH;
    }
    else if (atPosition && stage == Stage.APPROACH) stage = Stage.SETTLE;
    else if (stage == Stage.SETTLE && now - startedAt >= 10L) stage = Stage.OBSERVE;
    return stage;
  }

  public HunterIntent intent() {
    return intent;
  }

  public Vec3 position() {
    return position;
  }

  public Stage stage() {
    return stage;
  }

  public String summary(long now) {
    return "intent=" + intent + " stage=" + stage + " age=" + Math.max(0L, now - startedAt) + " reason=" + reason;
  }

  public enum Stage {
    APPROACH,
    SETTLE,
    OBSERVE,
    ENGAGE,
    ESCAPE
  }
}
