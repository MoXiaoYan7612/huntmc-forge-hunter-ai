package cn.huntmc.hunterai.command;

import cn.huntmc.hunterai.HuntMcHunterAi;
import cn.huntmc.hunterai.config.HunterAiConfig;
import cn.huntmc.hunterai.entity.HunterAiEntities;
import cn.huntmc.hunterai.entity.HunterEntity;
import cn.huntmc.hunterai.profile.HunterProfile;
import cn.huntmc.hunterai.registry.HunterRegistry;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.Arrays;
import java.util.Comparator;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Administrator-only controls for the standalone test population. */
public final class HunterCommand {
  private static final double NEARBY_REMOVE_RADIUS = 96.0D;

  private HunterCommand() {}

  public static void register(CommandDispatcher<CommandSourceStack> dispatcher, HunterRegistry registry) {
    dispatcher.register(
        Commands.literal("hunter")
            .requires(source -> source.hasPermission(2))
            .then(
                Commands.literal("spawn")
                    .then(
                        Commands.argument("profile", StringArgumentType.word())
                            .suggests(
                                (context, builder) ->
                                    SharedSuggestionProvider.suggest(
                                        Arrays.stream(HunterProfile.values())
                                            .map(HunterProfile::commandName)
                                            .toList(),
                                        builder))
                            .executes(context -> spawn(context, registry, 1))
                            .then(
                                Commands.argument("count", IntegerArgumentType.integer(1, 16))
                                    .executes(
                                        context ->
                                            spawn(
                                                context,
                                                registry,
                                                IntegerArgumentType.getInteger(context, "count"))))))
            .then(
                Commands.literal("remove")
                    .then(Commands.literal("all").executes(context -> removeAll(context, registry)))
                    .then(
                        Commands.literal("nearby")
                            .executes(context -> removeNearby(context, registry))))
            .then(Commands.literal("status").executes(context -> status(context, registry)))
            .then(
                Commands.literal("inspect")
                    .executes(context -> inspect(context, registry, null))
                    .then(
                        Commands.argument("monitorId", IntegerArgumentType.integer(1))
                            .executes(
                                context ->
                                    inspect(
                                        context,
                                        registry,
                                        IntegerArgumentType.getInteger(context, "monitorId")))))
            .then(
                Commands.literal("monitor")
                    .then(Commands.literal("on").executes(context -> setMonitor(context, registry, true)))
                    .then(Commands.literal("off").executes(context -> setMonitor(context, registry, false)))
                    .then(Commands.literal("status").executes(context -> monitorStatus(context, registry)))
                    .then(Commands.literal("list").executes(context -> monitorList(context, registry)))
                    .then(
                        Commands.literal("inspect")
                            .then(
                                Commands.argument("monitorId", IntegerArgumentType.integer(1))
                                    .executes(
                                        context ->
                                            inspect(
                                                context,
                                                registry,
                                                IntegerArgumentType.getInteger(context, "monitorId")))))
                    .then(
                        Commands.literal("trace")
                            .executes(context -> monitorTrace(context, registry, 32))
                            .then(
                                Commands.argument("lines", IntegerArgumentType.integer(1, 128))
                                    .executes(
                                        context ->
                                    monitorTrace(
                                                context,
                                                registry,
                                                IntegerArgumentType.getInteger(context, "lines"))))))
            .then(
                Commands.literal("match")
                    .then(Commands.literal("status").executes(HunterCommand::matchReview))
                    .then(Commands.literal("review").executes(HunterCommand::matchReview)))
            .then(
                Commands.literal("debug")
                    .then(Commands.literal("on").executes(context -> setDebug(context, registry, true)))
                    .then(Commands.literal("off").executes(context -> setDebug(context, registry, false)))
                    .then(
                        Commands.literal("trace")
                            .executes(context -> trace(context, registry, 32))
                            .then(
                                Commands.argument("lines", IntegerArgumentType.integer(1, 64))
                                    .executes(
                                        context ->
                                            trace(
                                                context,
                                                registry,
                                                IntegerArgumentType.getInteger(context, "lines")))))
                    .then(Commands.literal("clear").executes(context -> clearTrace(context, registry)))
                    .then(
                        Commands.argument("enabled", BoolArgumentType.bool())
                            .executes(context -> setDebugArgument(context, registry)))));
  }

  private static int spawn(CommandContext<CommandSourceStack> context, HunterRegistry registry, int requested) {
    ServerPlayer player = requirePlayer(context);
    if (player == null) return 0;
    ServerLevel level = player.serverLevel();
    String profileName = StringArgumentType.getString(context, "profile");
    HunterProfile profile = HunterProfile.fromCommand(profileName).orElse(null);
    if (profile == null) {
      context.getSource().sendFailure(Component.literal("Unknown Hunter profile: " + profileName));
      return 0;
    }
    if (!HunterAiConfig.isProfileAvailable(profile)) {
      context
          .getSource()
          .sendFailure(Component.literal("TaCZ gun is unavailable for profile " + profileName + "."));
      return 0;
    }
    int available = HunterAiConfig.MAX_ACTIVE.get() - registry.activeCount();
    int count = Math.min(requested, Math.max(available, 0));
    if (count == 0) {
      context.getSource().sendFailure(Component.literal("Hunter global limit has been reached."));
      return 0;
    }
    Vec3 forward = player.getLookAngle().multiply(1.0D, 0.0D, 1.0D).normalize();
    if (forward.lengthSqr() < 1.0E-6D) forward = new Vec3(0.0D, 0.0D, 1.0D);
    for (int index = 0; index < count; index++) {
      HunterEntity hunter = HunterAiEntities.HUNTER.get().create(level);
      if (hunter == null) continue;
      double lateral = (index - (count - 1) / 2.0D) * 1.5D;
      Vec3 position =
          player.position().add(forward.scale(4.0D)).add(-forward.z * lateral, 0.0D, forward.x * lateral);
      int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(position.x), Mth.floor(position.z));
      hunter.setProfile(profile);
      hunter.moveTo(position.x, y, position.z, player.getYRot() + 180.0F, 0.0F);
      level.addFreshEntity(hunter);
    }
    int spawned = count;
    context
        .getSource()
        .sendSuccess(
            () -> Component.literal("Spawned " + spawned + " " + profile.commandName() + " Hunter(s)."),
            false);
    return spawned;
  }

  private static int removeAll(CommandContext<CommandSourceStack> context, HunterRegistry registry) {
    int removed = registry.discardAll();
    context.getSource().sendSuccess(() -> Component.literal("Removed " + removed + " Hunter(s)."), true);
    return removed;
  }

  private static int removeNearby(CommandContext<CommandSourceStack> context, HunterRegistry registry) {
    ServerPlayer player = requirePlayer(context);
    if (player == null) return 0;
    int removed =
        registry.removeNearby(
            player.serverLevel(), player.getBoundingBox().inflate(NEARBY_REMOVE_RADIUS));
    context.getSource().sendSuccess(() -> Component.literal("Removed " + removed + " nearby Hunter(s)."), true);
    return removed;
  }

  private static int status(CommandContext<CommandSourceStack> context, HunterRegistry registry) {
    context
        .getSource()
        .sendSuccess(
            () ->
                Component.literal(
                    "Hunter count: "
                        + registry.activeCount()
                        + "/"
                        + HunterAiConfig.MAX_ACTIVE.get()
                        + ", debug="
                        + registry.debugEnabled()
                        + ", monitor="
                        + registry.monitorEnabled()
                        + ", "
                        + registry.performanceSummary()),
            false);
    return registry.activeCount();
  }

  private static int inspect(
      CommandContext<CommandSourceStack> context, HunterRegistry registry, Integer monitorId) {
    HunterEntity hunter;
    if (monitorId == null) {
      ServerPlayer player = requirePlayer(context);
      if (player == null) return 0;
      hunter = nearestHunter(player, registry);
      if (hunter == null) {
        context.getSource().sendFailure(Component.literal("No Hunter exists in this dimension."));
        return 0;
      }
    } else {
      hunter = registry.findByMonitorId(monitorId);
      if (hunter == null) {
        context.getSource().sendFailure(Component.literal("No active Hunter has monitor ID #" + monitorId + "."));
        return 0;
      }
    }
    for (String line : hunter.coordinator().inspectLines(hunter)) {
      context.getSource().sendSuccess(() -> Component.literal(line), false);
    }
    return 1;
  }

  private static int monitorList(CommandContext<CommandSourceStack> context, HunterRegistry registry) {
    java.util.List<HunterEntity> hunters =
        registry.snapshot().stream().sorted(Comparator.comparingInt(HunterEntity::monitorId)).toList();
    if (hunters.isEmpty()) {
      context.getSource().sendFailure(Component.literal("No active Hunter is being tracked."));
      return 0;
    }
    context.getSource().sendSuccess(() -> Component.literal("Active Hunter monitor IDs:"), false);
    for (HunterEntity hunter : hunters) {
      context
          .getSource()
          .sendSuccess(
              () ->
                  Component.literal(
                      "#"
                          + hunter.monitorId()
                          + " profile="
                          + hunter.profile().commandName()
                          + " pos="
                          + Math.round(hunter.getX())
                          + ","
                          + Math.round(hunter.getY())
                          + ","
                          + Math.round(hunter.getZ())
                          + " intent="
                          + hunter.coordinator().intent()
                          + " goal="
                          + hunter.coordinator().strategicGoal()),
              false);
    }
    return hunters.size();
  }

  private static int setDebugArgument(CommandContext<CommandSourceStack> context, HunterRegistry registry) {
    boolean enabled = BoolArgumentType.getBool(context, "enabled");
    return setDebug(context, registry, enabled);
  }

  private static int matchReview(CommandContext<CommandSourceStack> context) {
    if (HuntMcHunterAi.instance() == null) {
      context.getSource().sendFailure(Component.literal("Hunter match service is unavailable."));
      return 0;
    }
    java.util.List<String> lines = HuntMcHunterAi.instance().hunterMatchService().reviewLines();
    for (String line : lines) context.getSource().sendSuccess(() -> Component.literal(line), false);
    return lines.size();
  }

  private static int setDebug(
      CommandContext<CommandSourceStack> context, HunterRegistry registry, boolean enabled) {
    registry.setDebugEnabled(enabled);
    context
        .getSource()
        .sendSuccess(
            () ->
                Component.literal(
                    "Hunter debug="
                        + enabled
                        + "; use /hunter inspect and /hunter debug trace [lines]."),
            true);
    return 1;
  }

  private static int trace(CommandContext<CommandSourceStack> context, HunterRegistry registry, int lines) {
    ServerPlayer player = requirePlayer(context);
    if (player == null) return 0;
    HunterEntity nearest = nearestHunter(player, registry);
    if (nearest == null) {
      context.getSource().sendFailure(Component.literal("No Hunter exists in this dimension."));
      return 0;
    }
    java.util.List<String> trace = nearest.coordinator().recentTrace(lines);
    if (trace.isEmpty()) {
      context
          .getSource()
          .sendFailure(Component.literal("No trace entries. Enable debug and let the Hunter run first."));
      return 0;
    }
    context
        .getSource()
        .sendSuccess(
            () ->
                Component.literal(
                    "Hunter " + nearest.getUUID().toString().substring(0, 8) + " recent trace:"),
            false);
    for (String line : trace) context.getSource().sendSuccess(() -> Component.literal(line), false);
    return trace.size();
  }

  private static int setMonitor(
      CommandContext<CommandSourceStack> context, HunterRegistry registry, boolean enabled) {
    if (enabled) registry.startMonitor();
    else registry.stopMonitor();
    context
        .getSource()
        .sendSuccess(() -> Component.literal("Hunter monitor " + registry.monitorStatus()), true);
    return 1;
  }

  private static int monitorStatus(CommandContext<CommandSourceStack> context, HunterRegistry registry) {
    context.getSource().sendSuccess(() -> Component.literal("Hunter monitor " + registry.monitorStatus()), false);
    return registry.monitorEnabled() ? 1 : 0;
  }

  private static int monitorTrace(
      CommandContext<CommandSourceStack> context, HunterRegistry registry, int lines) {
    ServerPlayer player = requirePlayer(context);
    if (player == null) return 0;
    HunterEntity nearest = nearestHunter(player, registry);
    if (nearest == null) {
      context.getSource().sendFailure(Component.literal("No Hunter exists in this dimension."));
      return 0;
    }
    java.util.List<String> trace = registry.recentMonitor(nearest, lines);
    if (trace.isEmpty()) {
      context
          .getSource()
          .sendFailure(Component.literal("No monitor entries. Enable /hunter monitor on and let the Hunter run."));
      return 0;
    }
    context
        .getSource()
        .sendSuccess(
            () -> Component.literal("Hunter #" + nearest.monitorId() + " recent monitor events:"), false);
    for (String line : trace) context.getSource().sendSuccess(() -> Component.literal(line), false);
    return trace.size();
  }

  private static int clearTrace(CommandContext<CommandSourceStack> context, HunterRegistry registry) {
    registry.clearDebugTraces();
    context.getSource().sendSuccess(() -> Component.literal("Cleared Hunter debug traces."), true);
    return 1;
  }

  private static HunterEntity nearestHunter(ServerPlayer player, HunterRegistry registry) {
    return registry.snapshot().stream()
        .filter(hunter -> hunter.level() == player.level())
        .min(Comparator.comparingDouble(player::distanceToSqr))
        .orElse(null);
  }

  private static ServerPlayer requirePlayer(CommandContext<CommandSourceStack> context) {
    if (context.getSource().getEntity() instanceof ServerPlayer player) return player;
    context.getSource().sendFailure(Component.literal("This command requires an in-game administrator."));
    return null;
  }
}
