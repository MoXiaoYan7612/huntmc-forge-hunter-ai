package cn.huntmc.hunterai.mixin;

import cn.huntmc.hunterai.entity.HunterEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps active match Hunters out of Arclight's Spigot entity-activation throttle. */
@Pseudo
@Mixin(targets = "org.spigotmc.ActivationRange", remap = false)
public abstract class ArclightActivationRangeMixin {
  @Inject(method = "checkIfActive", at = @At("HEAD"), cancellable = true, remap = false)
  private static void huntmc$keepMatchHunterActive(
      Entity entity, CallbackInfoReturnable<Boolean> result) {
    if (entity instanceof HunterEntity hunter && hunter.isMatchBot() && hunter.matchId() != null) {
      result.setReturnValue(true);
    }
  }
}
