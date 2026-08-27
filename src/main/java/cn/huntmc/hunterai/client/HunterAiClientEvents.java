package cn.huntmc.hunterai.client;

import cn.huntmc.hunterai.HuntMcHunterAi;
import cn.huntmc.hunterai.entity.HunterAiEntities;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Client-only entity renderer registration. Dedicated servers never load this class. */
@Mod.EventBusSubscriber(
    modid = HuntMcHunterAi.MOD_ID,
    bus = Mod.EventBusSubscriber.Bus.MOD,
    value = Dist.CLIENT)
public final class HunterAiClientEvents {
  private HunterAiClientEvents() {}

  @SubscribeEvent
  public static void registerEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
    event.registerEntityRenderer(HunterAiEntities.HUNTER.get(), HunterRenderer::new);
  }
}
