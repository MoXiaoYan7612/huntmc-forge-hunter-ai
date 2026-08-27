package cn.huntmc.hunterai.action;

/** Authoritative progress of the movement request applied during the current server tick. */
public enum MovementProgressState {
  IDLE,
  PATH_ACCEPTED,
  PROGRESSING,
  STALLED,
  RECOVERING,
  FAILED,
  ARRIVED
}
