package cn.huntmc.hunterai.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlayerAliasServiceTest {
  @Test
  void aliasesAreStableDistinctAndDoNotExposeUuid() {
    byte[] key = new byte[32];
    java.util.Arrays.fill(key, (byte) 0x5a);
    PlayerAliasService aliases = new PlayerAliasService(key);
    UUID first = UUID.fromString("12345678-1234-5678-9abc-def012345678");
    UUID second = UUID.fromString("12345678-1234-5678-9abc-def012345679");

    String firstAlias = aliases.alias(first);

    assertEquals(firstAlias, aliases.alias(first));
    assertNotEquals(firstAlias, aliases.alias(second));
    assertTrue(firstAlias.matches("player-[0-9a-f]{24}"));
    assertFalse(firstAlias.contains(first.toString()));
  }
}
