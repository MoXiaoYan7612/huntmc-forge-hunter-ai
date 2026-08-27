package cn.huntmc.hunterai.entity;

import cn.huntmc.hunterai.HuntMcHunterAi;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Entity types owned by the standalone Hunter AI module. */
public final class HunterAiEntities {
  public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
      DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, HuntMcHunterAi.MOD_ID);

  public static final RegistryObject<EntityType<HunterEntity>> HUNTER =
      ENTITY_TYPES.register(
          "hunter",
          () ->
                  EntityType.Builder.of(HunterEntity::new, MobCategory.MONSTER)
                  .sized(0.6F, 1.8F)
                  // Vanilla multiplies this chunk count by 16 and caps it at the server view
                  // distance. 64 chunks therefore covers a 224-block server view without a
                  // fragile ChunkMap mixin.
                  .clientTrackingRange(64)
                  .updateInterval(2)
                  .build(HuntMcHunterAi.MOD_ID + ":hunter"));

  private HunterAiEntities() {}

  public static void registerAttributes(EntityAttributeCreationEvent event) {
    AttributeSupplier attributes = HunterEntity.createAttributes().build();
    event.put(HUNTER.get(), attributes);
  }
}
