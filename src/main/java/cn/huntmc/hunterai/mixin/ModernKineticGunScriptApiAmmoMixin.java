package cn.huntmc.hunterai.mixin;

import cn.huntmc.hunterai.combat.HunterAmmoPolicy;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Supplies only Hunter reload scripts, leaving player and HuntMC match ammo behavior untouched. */
@Mixin(targets = "com.tacz.guns.item.ModernKineticGunScriptAPI", remap = false)
public abstract class ModernKineticGunScriptApiAmmoMixin {
  @Shadow private LivingEntity shooter;

  @Inject(method = "consumeAmmoFromPlayer", at = @At("HEAD"), cancellable = true, remap = false)
  private void huntmc$supplyHunterAmmo(
      int requestedAmount, CallbackInfoReturnable<Integer> result) {
    if (HunterAmmoPolicy.appliesTo(shooter)) {
      result.setReturnValue(HunterAmmoPolicy.supply(requestedAmount));
    }
  }
}
