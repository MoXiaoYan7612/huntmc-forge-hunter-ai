package cn.huntmc.hunterai;

import cn.huntmc.hunterai.command.HunterCommand;
import cn.huntmc.hunterai.ai.TacticalMap;
import cn.huntmc.hunterai.combat.HunterWeaponAdapter;
import cn.huntmc.hunterai.config.HunterAiConfig;
import cn.huntmc.hunterai.entity.HunterAiEntities;
import cn.huntmc.hunterai.entity.HunterDamageAttackerResolver;
import cn.huntmc.hunterai.entity.HunterEntity;
import cn.huntmc.hunterai.match.HunterMatchService;
import cn.huntmc.hunterai.registry.HunterRegistry;
import com.tacz.guns.api.event.common.GunFireEvent;
import com.tacz.guns.api.event.common.GunReloadEvent;
import com.tacz.guns.entity.EntityKineticBullet;
import com.mojang.logging.LogUtils;
import java.util.Set;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import org.slf4j.Logger;

/** Standalone Forge entry point for the Hunter AI test module. */
@Mod(HuntMcHunterAi.MOD_ID)
public final class HuntMcHunterAi {
  public static final String MOD_ID = "huntmc_hunter_ai";
  private static final Logger LOG = LogUtils.getLogger();
  private static HuntMcHunterAi instance;

  private final HunterRegistry hunterRegistry = new HunterRegistry();
  private final HunterMatchService hunterMatchService = new HunterMatchService(hunterRegistry);

  public HuntMcHunterAi() {
    instance = this;
    ForgeChunkManager.setForcedChunkLoadingCallback(
        MOD_ID,
        (level, tickets) ->
            Set.copyOf(tickets.getEntityTickets().keySet()).forEach(tickets::removeAllTickets));
    ModLoadingContext.get()
        .registerConfig(ModConfig.Type.SERVER, HunterAiConfig.SPEC, "huntmc-hunter-ai-server.toml");
    var modBus = FMLJavaModLoadingContext.get().getModEventBus();
    HunterAiEntities.ENTITY_TYPES.register(modBus);
    modBus.addListener(HunterAiEntities::registerAttributes);
    net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(this);
  }

  public static HuntMcHunterAi instance() {
    return instance;
  }

  public HunterRegistry hunterRegistry() {
    return hunterRegistry;
  }

  public HunterMatchService hunterMatchService() {
    return hunterMatchService;
  }

  @SubscribeEvent
  public void onServerStarting(ServerStartingEvent event) {
    HunterAiConfig.logProfileAvailability(LOG);
  }

  @SubscribeEvent
  public void onRegisterCommands(RegisterCommandsEvent event) {
    HunterCommand.register(event.getDispatcher(), hunterRegistry);
  }

  @SubscribeEvent
  public void onEntityJoin(EntityJoinLevelEvent event) {
    if (event.getLevel().isClientSide()) return;
    if (event.getEntity() instanceof Player player) {
      hunterRegistry.registerPlayerActor(player.getUUID());
    }
    if (event.getEntity() instanceof HunterEntity hunter) hunterRegistry.track(hunter);
    if (event.getLevel() instanceof ServerLevel level
        && event.getEntity() instanceof EntityKineticBullet bullet
        && bullet.getOwner() instanceof HunterEntity hunter) {
      hunterRegistry.recordMonitor(
          hunter,
          level.getGameTime(),
          "BULLET",
          "spawned entityId=" + bullet.getId() + " gun=" + bullet.getGunId());
    }
    if (event.getLevel() instanceof ServerLevel level
        && event.getEntity() instanceof Projectile projectile
        && projectile.getOwner() instanceof LivingEntity owner
        && isNoiseSource(owner)) {
      hunterRegistry.broadcastNoise(level, owner.getUUID(), projectile.position(), 1.0F);
    }
  }

  @SubscribeEvent
  public void onEntityLeave(EntityLeaveLevelEvent event) {
    if (!event.getLevel().isClientSide() && event.getEntity() instanceof HunterEntity hunter) {
      hunterMatchService.forget(hunter);
      hunterRegistry.forget(hunter);
    }
  }

  @SubscribeEvent
  public void onPlayerStartsTracking(PlayerEvent.StartTracking event) {
    if (!(event.getTarget() instanceof HunterEntity hunter)
        || !(hunter.level() instanceof ServerLevel level)) return;
    hunterRegistry.recordMonitor(
        hunter,
        level.getGameTime(),
        "LIFECYCLE",
        "phase=player-tracking-start player="
            + hunterRegistry.playerActorId(event.getEntity().getUUID())
            + " distance=" + decimalDistance(event.getEntity().distanceTo(hunter)));
  }

  @SubscribeEvent
  public void onPlayerStopsTracking(PlayerEvent.StopTracking event) {
    if (!(event.getTarget() instanceof HunterEntity hunter)
        || !(hunter.level() instanceof ServerLevel level)) return;
    hunterRegistry.recordMonitor(
        hunter,
        level.getGameTime(),
        "LIFECYCLE",
        "phase=player-tracking-stop player="
            + hunterRegistry.playerActorId(event.getEntity().getUUID())
            + " distance=" + decimalDistance(event.getEntity().distanceTo(hunter))
            + " alive=" + hunter.isAlive()
            + " removed=" + hunter.isRemoved()
            + " removalReason=" + hunter.removalReason());
  }

  private static String decimalDistance(float value) {
    return String.format(java.util.Locale.ROOT, "%.1f", value);
  }

  @SubscribeEvent
  public void onServerStopping(ServerStoppingEvent event) {
    hunterMatchService.shutdown();
    hunterRegistry.discardAll();
    hunterRegistry.closeMonitor();
    TacticalMap.clearTerrainCache();
  }

  @SubscribeEvent
  public void onServerTick(TickEvent.ServerTickEvent event) {
    if (event.phase == TickEvent.Phase.START) {
      hunterRegistry.beginServerTick();
    } else {
      hunterMatchService.tickServer(hunterRegistry.serverTick());
      hunterRegistry.capturePlayerTelemetry(event.getServer());
      hunterRegistry.endServerTick();
    }
  }

  @SubscribeEvent
  public void onBlockChanged(BlockEvent event) {
    if (event.getLevel() instanceof ServerLevel level) {
      TacticalMap.invalidateTerrain(level, event.getPos());
    }
  }

  @SubscribeEvent
  public void onExplosion(ExplosionEvent.Detonate event) {
    if (event.getLevel() instanceof ServerLevel level) TacticalMap.invalidateTerrain(level);
  }

  @SubscribeEvent
  public void onLevelUnload(LevelEvent.Unload event) {
    if (event.getLevel() instanceof ServerLevel level) TacticalMap.invalidateTerrain(level);
  }

  @SubscribeEvent
  public void onLivingJump(LivingEvent.LivingJumpEvent event) {
    if (!(event.getEntity().level() instanceof ServerLevel level)) return;
    hunterRegistry.recordPlayerJump(event.getEntity());
    if (!isNoiseSource(event.getEntity())) return;
    hunterRegistry.broadcastNoise(
        level, event.getEntity().getUUID(), event.getEntity().position(), 0.50F);
  }

  @SubscribeEvent
  public void onGunFire(GunFireEvent event) {
    if (!event.getLogicalSide().isServer()) return;
    hunterRegistry.recordPlayerGunFire(event.getShooter(), event.getGunItemStack());
  }

  @SubscribeEvent
  public void onGunReload(GunReloadEvent event) {
    if (!event.getLogicalSide().isServer()) return;
    hunterRegistry.recordPlayerReload(event.getEntity(), event.getGunItemStack());
  }

  @SubscribeEvent
  public void onLivingDamage(LivingDamageEvent event) {
    net.minecraft.world.entity.Entity attacker =
        HunterDamageAttackerResolver.resolve(
            event.getSource().getEntity(), event.getSource().getDirectEntity());
    hunterRegistry.recordPlayerDamage(event.getEntity(), attacker, event.getAmount());
  }

  @SubscribeEvent
  public void onLivingDeath(LivingDeathEvent event) {
    net.minecraft.world.entity.Entity attacker =
        HunterDamageAttackerResolver.resolve(
            event.getSource().getEntity(), event.getSource().getDirectEntity());
    hunterRegistry.recordPlayerDeath(event.getEntity(), attacker);
  }

  /** Cancels friendly fire at the final Forge damage boundary, including TaCZ bullet spread. */
  @SubscribeEvent
  public void onLivingAttack(LivingAttackEvent event) {
    net.minecraft.world.entity.Entity attacker =
        HunterDamageAttackerResolver.resolve(
            event.getSource().getEntity(), event.getSource().getDirectEntity());
    if (attacker instanceof HunterEntity hunter && hunter.isMatchAlly(event.getEntity())) {
      event.setCanceled(true);
    }
  }

  @SubscribeEvent
  public void onLivingTick(LivingEvent.LivingTickEvent event) {
    LivingEntity entity = event.getEntity();
    if (!(entity.level() instanceof ServerLevel level)
        || !isNoiseSource(entity)) return;
    hunterRegistry.observeMovement(entity);
  }

  private static boolean isNoiseSource(LivingEntity entity) {
    return entity instanceof HunterEntity
        || entity instanceof Player player && !player.isSpectator() && !player.isCreative();
  }
}
