package cn.huntmc.hunterai.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HunterDamageAttackerResolverTest {
  @Test
  void keepsAnIndirectLivingAttacker() {
    assertEquals(
        "shooter",
        HunterDamageAttackerResolver.resolve("shooter", "bullet", ignored -> null));
  }

  @Test
  void resolvesAnIndirectProjectileToItsOwner() {
    assertEquals(
        "shooter",
        HunterDamageAttackerResolver.resolve(
            "bullet", "bullet", entity -> "bullet".equals(entity) ? "shooter" : null));
  }

  @Test
  void resolvesTheDirectProjectileWhenDamageSourceHasNoIndirectAttacker() {
    assertEquals(
        "shooter",
        HunterDamageAttackerResolver.resolve(
            null, "bullet", entity -> "bullet".equals(entity) ? "shooter" : null));
  }

  @Test
  void retainsAnUnownedDirectEntityForEnvironmentalDamageDiagnostics() {
    assertEquals(
        "falling-block",
        HunterDamageAttackerResolver.resolve(null, "falling-block", ignored -> null));
  }
}
