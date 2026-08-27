package cn.huntmc.hunterai.match;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class MatchDirectiveExtractionTest {
  @Test
  void retainsEveryPublicExtractionInsteadOfOnlyTheNearestOne() {
    Vec3 north = new Vec3(0.0D, 64.0D, -100.0D);
    Vec3 south = new Vec3(0.0D, 64.0D, 100.0D);
    MatchDirective directive = new MatchDirective(
        null,
        null,
        "team",
        true,
        true,
        "ACTIVE",
        "CARRIED",
        north,
        north,
        List.of(north, south),
        true,
        1L,
        2_000L,
        -1L,
        0.0D,
        "",
        List.of(),
        5.0D);

    assertEquals(List.of(north, south), directive.extractions());
  }
}
