# HuntMC Hunter AI Test Mod

`huntmc_hunter_ai` is a standalone Forge test module for Minecraft 1.20.1, Forge 47.4.18,
Java 17, and TaCZ 1.1.8-hotfix. It never links to Bukkit or MatchSession classes. On a hybrid
server it uses the encoded HuntMC bridge for its match seats and to publish a bounded standalone
Hunter snapshot used only by an active Bounty Dark Sight scan.

Install the generated JAR on both the Forge server and every test client, alongside TaCZ and the
same TaCZ gun pack. Administrators can use the Hunter commands in any dimension.

Administrator commands:

```text
/hunter spawn <rifle|shotgun|revolver> [count]
/hunter remove <all|nearby>
/hunter status
/hunter inspect [monitorId]
/hunter debug <on|off>
/hunter debug trace [lines]
/hunter debug clear
/hunter monitor <on|off|status>
/hunter monitor list
/hunter monitor inspect <monitorId>
/hunter monitor trace [lines]
```

`/hunter debug true|false` remains accepted for compatibility. Debug mode shows a short overhead
state label and keeps a bounded, per-Hunter server-thread trace. `inspect` prints the current
intent, target evidence, orientation, navigation/sprint state, and TaCZ weapon state. `trace`
prints the recent perception, belief, decision, movement, look, reload, firing, and damage events
for the nearest Hunter without writing every tick to the server console.

Hunter movement is cover-first: tactical nodes are sampled around the Hunter's current floor rather
than from the terrain heightmap, then checked with Minecraft navigation before use. This prevents a
building interior from being replaced by a roof or a distant slope. A Hunter avoids open sky ground by
default, turns toward a visible target or sound estimate before sprinting, blacklists failed routes
briefly, and records the requested target, path end, and replacement when it has to recover.

When no enemy is visible, a Hunter can choose `LISTEN`: it reaches a concealed position, crouches,
checks the least-recently-observed compass sectors, and waits for movement, jump, or gunfire evidence.
Recent tactical positions are remembered briefly so it does not mechanically reuse the same cover point.
The per-Hunter monitor additionally records `SELF`, `ENEMY_MODEL`, `DUEL`, `EVASION`, `VIGILANCE`,
`OPPORTUNITY`, `UTILITY`, `PLAN_STAGE`, `POSTURE`, `PATH`, indoor/sky/exposure node data, and the server's
head/body/pitch rotations used for aiming. This is an ordered explanation chain: current danger and
uncertainty → every viable action score → selected plan/stage → movement, weapon or posture execution
→ outcome and exact blocker. Use `/hunter monitor list` to obtain saved Hunter numbers, then
`/hunter monitor inspect <monitorId>` (or `/hunter inspect <monitorId>`) to inspect one exact active
Hunter rather than relying on nearest-entity selection.

`DUEL` records the separate estimated chance of winning the exchange and safely breaking contact,
plus ammunition readiness, weapon-range fit, available cover, and dodge urgency. A weak duel score
does not alone trigger a retreat. While a visible exchange is dangerous, `EVASION` logs the selected
short burst (`STRAFE`, `BACKSTEP`, `JUMP`, or `CROUCH_DROP`), its random duration, its destination,
and any navigation blocker.

The server config is saved per world at `<world>/serverconfig/huntmc-hunter-ai-server.toml`.
`distant-vision-range` allows one extra line-of-sight-verified distant awareness check per Hunter
tick, but never grants tracking, firing, or wall vision. The `[tactics]` section exposes
`avoid-open-ground`, `turn-before-move-degrees`, and `listen-commitment-ticks`; restart the server
after changing them.

`/hunter monitor on` starts a separate persistent monitor session. Every Hunter has a saved monitor
number and writes to its own file under `logs/huntmc-hunter-ai-monitor/session-.../`, so dead and
removed Hunters remain available for later review. The monitor records danger assessment, estimated
danger position, recommended and selected action, action outcome, and a concrete blocker when the
action could not complete. Disk writes are asynchronous and monitoring is disabled by default.

The default gun profiles are `hunter:vetterli`, `hunter:romero77`, and `hunter:scot`. A profile is
disabled with a clear server log message when its TaCZ gun pack entry is unavailable. The module
never alters player health, player ammunition, or Bukkit match data.
