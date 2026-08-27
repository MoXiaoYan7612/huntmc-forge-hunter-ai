package cn.huntmc.hunterai.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class HunterPersonalityTest {
  @Test
  void uuidProducesStableButIndividualPersonality() {
    UUID first = new UUID(1L, 2L);
    assertEquals(HunterPersonality.fromUuid(first), HunterPersonality.fromUuid(first));
    assertNotEquals(HunterPersonality.fromUuid(first), HunterPersonality.fromUuid(new UUID(3L, 4L)));
  }
}
