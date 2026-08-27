package cn.huntmc.hunterai.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class HunterProfileTest {
  @Test
  void resolvesAllAdministratorProfileNames() {
    assertEquals(HunterProfile.RIFLE, HunterProfile.fromCommand("rifle").orElseThrow());
    assertEquals(HunterProfile.SHOTGUN, HunterProfile.fromCommand("shotgun").orElseThrow());
    assertEquals(HunterProfile.REVOLVER, HunterProfile.fromCommand("revolver").orElseThrow());
  }

  @Test
  void rejectsUnknownProfileNames() {
    assertTrue(HunterProfile.fromCommand("sniper").isEmpty());
  }

  @Test
  void derivesCombatRangeFromTheActualArmoryGun() {
    assertEquals(
        HunterProfile.SHOTGUN,
        HunterProfile.fromGunId(ResourceLocation.tryParse("hunter:romero77")));
    assertEquals(
        HunterProfile.SHOTGUN,
        HunterProfile.fromGunId(ResourceLocation.tryParse("tacz:db_short")));
    assertEquals(
        HunterProfile.REVOLVER,
        HunterProfile.fromGunId(ResourceLocation.tryParse("hamster:uppercut")));
    assertEquals(
        HunterProfile.RIFLE,
        HunterProfile.fromGunId(ResourceLocation.tryParse("hamster:lebel1886")));
    assertEquals(
        HunterProfile.RIFLE,
        HunterProfile.fromGunId(ResourceLocation.tryParse("jccrossbow:compound_crossbow")));
  }
}
