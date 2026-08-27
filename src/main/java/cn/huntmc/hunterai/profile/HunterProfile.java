package cn.huntmc.hunterai.profile;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/** Test loadout and range preferences. */
public enum HunterProfile {
  RIFLE("rifle", 22.0D, 48.0D, 0.35F, 0.70F),
  SHOTGUN("shotgun", 8.0D, 24.0D, 0.80F, 0.35F),
  REVOLVER("revolver", 14.0D, 36.0D, 0.55F, 0.55F);

  private final String commandName;
  private final double preferredRange;
  private final double maximumRange;
  private final float aggression;
  private final float patience;

  HunterProfile(
      String commandName,
      double preferredRange,
      double maximumRange,
      float aggression,
      float patience) {
    this.commandName = commandName;
    this.preferredRange = preferredRange;
    this.maximumRange = maximumRange;
    this.aggression = aggression;
    this.patience = patience;
  }

  public String commandName() {
    return commandName;
  }

  public double preferredRange() {
    return preferredRange;
  }

  public double maximumRange() {
    return maximumRange;
  }

  public float aggression() {
    return aggression;
  }

  public float patience() {
    return patience;
  }

  public static Optional<HunterProfile> fromCommand(String value) {
    return Arrays.stream(values()).filter(profile -> profile.commandName.equals(value)).findFirst();
  }

  /** Selects combat ranges for the actual HuntMC armory gun instead of the old rotating bot label. */
  public static HunterProfile fromGunId(ResourceLocation gunId) {
    if (gunId == null) return RIFLE;
    String path = gunId.getPath().toLowerCase(Locale.ROOT);
    if (containsAny(
        path,
        "auto5",
        "m1887",
        "one_barrel",
        "romero",
        "m1897",
        "db_long",
        "db_short",
        "nitro")) return SHOTGUN;
    if (containsAny(
        path,
        "colt1873",
        "coltm1892",
        "luger",
        "revolver",
        "nagantm1895",
        "uppercut",
        "webley",
        "lemat",
        "scot")) return REVOLVER;
    return RIFLE;
  }

  private static boolean containsAny(String value, String... candidates) {
    return Arrays.stream(candidates).anyMatch(value::contains);
  }
}
