package cn.huntmc.hunterai.mixin;

import cn.huntmc.hunterai.combat.HunterAmmoPolicy;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** TaCZ normally requires an item handler; Hunter reloads use the module's virtual reserve. */
@Mixin(targets = "com.tacz.guns.entity.shooter.LivingEntityAmmoCheck", remap = false)
public abstract class LivingEntityAmmoCheckMixin {
  @Shadow @Final private LivingEntity shooter;

  @Inject(method = "needCheckAmmo", at = @At("HEAD"), cancellable = true, remap = false)
  private void huntmc$skipHunterInventoryCheck(CallbackInfoReturnable<Boolean> result) {
    if (HunterAmmoPolicy.appliesTo(shooter)) result.setReturnValue(false);
  }
}
