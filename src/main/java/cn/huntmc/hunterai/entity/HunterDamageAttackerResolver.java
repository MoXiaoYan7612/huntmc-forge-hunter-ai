package cn.huntmc.hunterai.entity;

import java.util.function.Function;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;

/** Resolves the responsible shooter from a damage source's indirect and direct entities. */
public final class HunterDamageAttackerResolver {
  private HunterDamageAttackerResolver() {}

  /**
   * Prefers a projectile owner over the projectile itself, then falls back to the indirect source
   * and finally to the direct source. This covers both vanilla and TaCZ projectile damage shapes.
   */
  public static Entity resolve(Entity indirectEntity, Entity directEntity) {
    return resolve(indirectEntity, directEntity, HunterDamageAttackerResolver::projectileOwner);
  }

  static <T> T resolve(T indirectEntity, T directEntity, Function<T, T> ownerResolver) {
    T indirectOwner = ownerResolver.apply(indirectEntity);
    if (indirectOwner != null) return indirectOwner;
    T directOwner = ownerResolver.apply(directEntity);
    if (directOwner != null) return directOwner;
    return indirectEntity != null ? indirectEntity : directEntity;
  }

  private static Entity projectileOwner(Entity entity) {
    return entity instanceof Projectile projectile ? projectile.getOwner() : null;
  }
}
