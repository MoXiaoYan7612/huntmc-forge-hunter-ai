package cn.huntmc.hunterai.combat;

import cn.huntmc.hunterai.entity.HunterEntity;
import net.minecraft.world.entity.LivingEntity;

/** Dedicated-server virtual reserve ammunition for Hunter reload scripts only. */
public final class HunterAmmoPolicy {
  private HunterAmmoPolicy() {}

  public static boolean appliesTo(LivingEntity entity) {
    return entity instanceof HunterEntity;
  }

  public static int supply(int requestedAmount) {
    return Math.max(requestedAmount, 0);
  }
}
