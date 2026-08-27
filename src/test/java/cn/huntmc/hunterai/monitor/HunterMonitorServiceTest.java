package cn.huntmc.hunterai.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.huntmc.bridge.contract.BridgeProtocol;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HunterMonitorServiceTest {
  @Test
  void structuredPayloadParserKeepsGroupedOwnersAndVectorsIntact() {
    Map<String, String> fields = HunterMonitorService.parseFields(
        "planId=abc target=(1.0,2.0,3.0) owners={MOVEMENT=strategic-plan, VIEW=visible-combat} result=ok");

    assertEquals("abc", fields.get("planId"));
    assertEquals("(1.0,2.0,3.0)", fields.get("target"));
    assertEquals("{MOVEMENT=strategic-plan, VIEW=visible-combat}", fields.get("owners"));
    assertEquals("ok", fields.get("result"));
  }

  @Test
  void writerDrainCannotResubmitDuringFinalization() {
    assertTrue(HunterMonitorService.canScheduleDrain(false, false));
    assertFalse(HunterMonitorService.canScheduleDrain(false, true));
    assertFalse(HunterMonitorService.canScheduleDrain(true, false));
  }

  @Test
  void sanitizesPlayerUuidFromAiPayloadFields() {
    byte[] key = new byte[32];
    java.util.Arrays.fill(key, (byte) 0x33);
    HunterMonitorService monitor = new HunterMonitorService(new PlayerAliasService(key));
    UUID player = UUID.fromString("12345678-1234-5678-9abc-def012345678");
    UUID match = UUID.randomUUID();
    monitor.registerMatchActors(
        List.of(
            new BridgeProtocol.MatchActor(
                player,
                match,
                BridgeProtocol.MatchActorKind.PLAYER,
                "team-a",
                "world",
                "ALIVE",
                false)));

    Map<String, String> fields =
        monitor.sanitizeFields(Map.of("target", player.toString(), "detail", "seen=" + player));

    assertFalse(fields.toString().contains(player.toString()));
    assertTrue(fields.get("target").matches("player-[0-9a-f]{24}"));
  }
}
