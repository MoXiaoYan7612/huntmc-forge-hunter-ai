package cn.huntmc.hunterai.config;

import cn.huntmc.hunterai.profile.HunterProfile;
import com.tacz.guns.api.TimelessAPI;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

/** Server-side limits and test loadouts for the standalone Hunter AI module. */
public final class HunterAiConfig {
  private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

  public static final ForgeConfigSpec.IntValue MAX_ACTIVE;
  public static final ForgeConfigSpec.IntValue MATCH_HUNTER_TRACKING_RANGE;
  public static final ForgeConfigSpec.DoubleValue VISION_RANGE;
  public static final ForgeConfigSpec.DoubleValue DISTANT_VISION_RANGE;
  public static final ForgeConfigSpec.DoubleValue CLOSE_AWARENESS_RANGE;
  public static final ForgeConfigSpec.DoubleValue HEARING_RANGE;
  public static final ForgeConfigSpec.IntValue GLOBAL_RAY_BUDGET;
  public static final ForgeConfigSpec.IntValue PERCEPTION_RAY_BUDGET;
  public static final ForgeConfigSpec.IntValue GLOBAL_DELIBERATION_BUDGET_MICROS;
  public static final ForgeConfigSpec.IntValue HUNTER_DELIBERATION_SLICE_MICROS;
  public static final ForgeConfigSpec.BooleanValue AVOID_OPEN_GROUND;
  public static final ForgeConfigSpec.DoubleValue TURN_BEFORE_MOVE_DEGREES;
  public static final ForgeConfigSpec.IntValue MAX_TACTICAL_VERTICAL_DELTA;
  public static final ForgeConfigSpec.IntValue ACTIVE_COMMITMENT_TICKS;
  public static final ForgeConfigSpec.IntValue LISTEN_COMMITMENT_TICKS;
  public static final ForgeConfigSpec.IntValue PURSUIT_COMMITMENT_TICKS;
  public static final ForgeConfigSpec.IntValue DAMAGE_REACTION_TICKS;
  public static final ForgeConfigSpec.IntValue DAMAGE_IDENTIFICATION_TICKS;
  public static final ForgeConfigSpec.DoubleValue EVASION_URGENCY;
  public static final ForgeConfigSpec.DoubleValue MIN_COMBAT_COVER;
  public static final ForgeConfigSpec.IntValue COVER_SCAN_INTERVAL_TICKS;
  public static final ForgeConfigSpec.IntValue OPEN_SCAN_INTERVAL_TICKS;
  public static final ForgeConfigSpec.ConfigValue<String> RIFLE_GUN;
  public static final ForgeConfigSpec.ConfigValue<String> SHOTGUN_GUN;
  public static final ForgeConfigSpec.ConfigValue<String> REVOLVER_GUN;
  public static final ForgeConfigSpec SPEC;

  static {
    BUILDER.push("general");
    MAX_ACTIVE = BUILDER.defineInRange("max-active", 16, 1, 64);
    MATCH_HUNTER_TRACKING_RANGE =
        BUILDER.comment(
                "Client entity tracking range in blocks for active match Hunters on Arclight/Spigot. "
                    + "This is independent from AI ticking and chunk tickets.")
            .defineInRange("match-hunter-tracking-range", 224, 48, 512);
    VISION_RANGE = BUILDER.defineInRange("vision-range", 48.0D, 8.0D, 96.0D);
    DISTANT_VISION_RANGE =
        BUILDER.comment(
                "Maximum range for one line-of-sight-verified distant awareness check per Hunter tick. "
                    + "It never permits firing or tracking through blocks.")
            .defineInRange("distant-vision-range", 72.0D, 48.0D, 96.0D);
    CLOSE_AWARENESS_RANGE =
        BUILDER.comment(
                "Direct line-of-sight awareness radius that prevents nearby threats from being missed while the Hunter is scanning another sector.")
            .defineInRange("close-awareness-range", 4.0D, 2.0D, 8.0D);
    HEARING_RANGE = BUILDER.defineInRange("hearing-range", 48.0D, 8.0D, 96.0D);
    GLOBAL_RAY_BUDGET = BUILDER.defineInRange("global-ray-budget", 96, 16, 512);
    PERCEPTION_RAY_BUDGET =
        BUILDER.comment("Reserved line-of-sight ray budget for detecting a visible Hunter or player.")
            .defineInRange("perception-ray-budget", 64, 16, 512);
    GLOBAL_DELIBERATION_BUDGET_MICROS =
        BUILDER.comment("Maximum abstract rollout CPU reservation shared by all Hunters per server tick.")
            .defineInRange("global-deliberation-budget-micros", 4000, 500, 20000);
    HUNTER_DELIBERATION_SLICE_MICROS =
        BUILDER.comment("Maximum abstract rollout CPU time for one Hunter decision slice.")
            .defineInRange("hunter-deliberation-slice-micros", 800, 100, 2000);
    BUILDER.pop();

    BUILDER.push("tactics");
    AVOID_OPEN_GROUND =
        BUILDER.comment("Prefer indoor or hard-cover nodes. Open ground remains a last-resort fallback.")
            .define("avoid-open-ground", true);
    TURN_BEFORE_MOVE_DEGREES =
        BUILDER.comment("Hunter turns its body toward a route before the first movement step.")
            .defineInRange("turn-before-move-degrees", 35.0D, 10.0D, 90.0D);
    MAX_TACTICAL_VERTICAL_DELTA =
        BUILDER.comment("Do not select tactical nodes more than this many blocks above or below the Hunter.")
            .defineInRange("max-tactical-vertical-delta", 3, 1, 8);
    ACTIVE_COMMITMENT_TICKS =
        BUILDER.comment("Minimum commitment for moving or combat plans unless new contact, damage, or route failure interrupts it.")
            .defineInRange("active-commitment-ticks", 60, 40, 240);
    LISTEN_COMMITMENT_TICKS =
        BUILDER.comment("Minimum crouched listening/ambush duration before a normal replanning pass.")
            .defineInRange("listen-commitment-ticks", 80, 40, 240);
    PURSUIT_COMMITMENT_TICKS =
        BUILDER.comment(
                "Normal commitment for pursuit and search plans. Post-fire pursuit keeps a 120-tick "
                    + "minimum even when an older saved config contains a smaller value.")
            .defineInRange("pursuit-commitment-ticks", 120, 40, 240);
    DAMAGE_REACTION_TICKS =
        BUILDER.comment("Maximum immediate turn-to-threat time after a Hunter is damaged from a known direction.")
            .defineInRange("damage-reaction-ticks", 12, 4, 40);
    DAMAGE_IDENTIFICATION_TICKS =
        BUILDER.comment(
                "Brief confirm-direction window before a damaged Hunter starts its retreat or evasive movement.")
            .defineInRange("damage-identification-ticks", 3, 1, 8);
    EVASION_URGENCY =
        BUILDER.comment("Dodge only at this urgency unless hit, reloading, or otherwise forced under pressure.")
            .defineInRange("evasion-urgency", 0.68D, 0.48D, 1.0D);
    MIN_COMBAT_COVER =
        BUILDER.comment("Minimum structural hard-cover score for an engagement node while a threat is known.")
            .defineInRange("min-combat-cover", 0.35D, 0.0D, 1.0D);
    COVER_SCAN_INTERVAL_TICKS =
        BUILDER.comment(
                "Ticks spent turning and dwelling on each sector while crouched in cover. "
                    + "It must be long enough to confirm a visual contact.")
            .defineInRange("cover-scan-interval-ticks", 20, 16, 80);
    OPEN_SCAN_INTERVAL_TICKS =
        BUILDER.comment("Ticks spent on each sector while moving through an exposed area.")
            .defineInRange("open-scan-interval-ticks", 24, 18, 100);
    BUILDER.pop();

    BUILDER.push("profiles");
    RIFLE_GUN = BUILDER.define("rifle-gun", "hunter:vetterli");
    SHOTGUN_GUN = BUILDER.define("shotgun-gun", "hunter:romero77");
    REVOLVER_GUN = BUILDER.define("revolver-gun", "hunter:scot");
    BUILDER.pop();
    SPEC = BUILDER.build();
  }

  private HunterAiConfig() {}

  public static ResourceLocation gunId(HunterProfile profile) {
    String configured =
        switch (profile) {
          case RIFLE -> RIFLE_GUN.get();
          case SHOTGUN -> SHOTGUN_GUN.get();
          case REVOLVER -> REVOLVER_GUN.get();
        };
    return ResourceLocation.tryParse(configured);
  }

  public static boolean isProfileAvailable(HunterProfile profile) {
    ResourceLocation gunId = gunId(profile);
    return gunId != null && TimelessAPI.getCommonGunIndex(gunId).isPresent();
  }

  public static void logProfileAvailability(Logger logger) {
    for (HunterProfile profile : HunterProfile.values()) {
      ResourceLocation gunId = gunId(profile);
      if (isProfileAvailable(profile)) {
        logger.info("Hunter AI profile {} uses TaCZ gun {}.", profile.commandName(), gunId);
      } else {
        logger.warn(
            "Hunter AI profile {} is disabled because TaCZ gun '{}' is unavailable.",
            profile.commandName(),
            gunId);
      }
    }
  }
}
