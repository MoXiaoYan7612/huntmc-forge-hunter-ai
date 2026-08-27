package cn.huntmc.hunterai.mixin;

import cn.huntmc.hunterai.config.HunterAiConfig;
import cn.huntmc.hunterai.entity.HunterEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps Arclight's mob tracking range from truncating active Hunters to Spigot's 48-block default. */
@Pseudo
@Mixin(targets = "org.spigotmc.TrackingRange", remap = false)
public abstract class ArclightTrackingRangeMixin {
  @Inject(method = "getEntityTrackingRange", at = @At("HEAD"), cancellable = true, remap = false)
  private static void huntmc$useMatchHunterTrackingRange(
      Entity entity, int defaultRange, CallbackInfoReturnable<Integer> result) {
    if (entity instanceof HunterEntity hunter && hunter.isMatchBot() && hunter.matchId() != null) {
      result.setReturnValue(
          Math.max(defaultRange, HunterAiConfig.MATCH_HUNTER_TRACKING_RANGE.get()));
    }
  }
}
