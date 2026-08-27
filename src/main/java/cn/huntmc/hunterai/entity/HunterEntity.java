package cn.huntmc.hunterai.entity;

import cn.huntmc.bridgeforge.HuntMcBukkitBridge;
import cn.huntmc.hunterai.HuntMcHunterAi;
import cn.huntmc.hunterai.coordinator.HunterCoordinator;
import cn.huntmc.hunterai.navigation.HunterLadderMoveControl;
import cn.huntmc.hunterai.navigation.HunterPathNavigation;
import cn.huntmc.hunterai.profile.HunterProfile;
import java.util.Set;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Player-sized server-controlled Hunter carrying a real TaCZ gun. */
public final class HunterEntity extends PathfinderMob {
  private final HunterCoordinator coordinator = new HunterCoordinator();
  private HunterProfile profile = HunterProfile.RIFLE;
  private int monitorId;
  private String removalReason = "unknown";
  private UUID matchBotId;
  private UUID matchId;
  private String matchTeamId = "";
  private boolean matchActive;
  private Set<UUID> matchAllyIds = Set.of();
  private List<ResourceLocation> matchWeaponIds = List.of();
  private boolean matchLoadoutAssigned;
  private Vec3 lastFootstepSample;
  private double fallbackFootstepDistance;
  private long lastNativeFootstepAt = Long.MIN_VALUE;

  public HunterEntity(EntityType<? extends HunterEntity> entityType, Level level) {
    super(entityType, level);
    moveControl = new HunterLadderMoveControl(this);
    setPersistenceRequired();
    setDropChance(EquipmentSlot.MAINHAND, 0.0F);
    setCustomNameVisible(false);
  }

  @Override
  protected PathNavigation createNavigation(Level level) {
    return new HunterPathNavigation(this, level);
  }

  /** True only while an active route is being followed from inside a tagged climbable block. */
  public boolean isClimbingLadder() {
    return getNavigation() instanceof HunterPathNavigation navigation && navigation.isClimbingLadder();
  }

  public HunterPathNavigation.ClimbDirection ladderClimbDirection() {
    return getNavigation() instanceof HunterPathNavigation navigation
        ? navigation.climbDirection()
        : HunterPathNavigation.ClimbDirection.NONE;
  }

  public static AttributeSupplier.Builder createAttributes() {
    return Mob.createMobAttributes()
        .add(Attributes.MAX_HEALTH, 150.0D)
        // Keep native pathing close to a player's walking pace. Sprinting is applied only by the
        // brain for a short, urgent route; starting from 0.27 made the sprint modifier visibly
        // outrun a player-sized opponent.
        .add(Attributes.MOVEMENT_SPEED, 0.23D)
        .add(Attributes.FOLLOW_RANGE, 48.0D)
        .add(Attributes.KNOCKBACK_RESISTANCE, 0.15D);
  }

  /**
   * PathfinderMob does not assign a shorter collision box for {@link Pose#CROUCHING}. Hunter
   * uses player-sized dimensions, so make the visual crouch a real 1.5-block posture with a
   * correspondingly lower eye position for cover and line-of-sight calculations.
   */
  @Override
  public EntityDimensions getDimensions(Pose pose) {
    EntityDimensions standing = super.getDimensions(pose);
    return pose == Pose.CROUCHING ? standing.scale(1.0F, 5.0F / 6.0F) : standing;
  }

  @Override
  protected void registerGoals() {}

  @Override
  public void aiStep() {
    super.aiStep();
    if (!level().isClientSide && !isDeadOrDying()) {
      coordinator.tick(this);
      emitPlayerLikeFootstep();
    }
  }

  /** Classify native Hunter sounds with player footsteps for client sound/occlusion mods. */
  @Override
  public SoundSource getSoundSource() {
    return SoundSource.PLAYERS;
  }

  /** Use the same death cue as a player so HuntMC/TaCZ player sound replacements also apply. */
  @Override
  protected SoundEvent getDeathSound() {
    return SoundEvents.PLAYER_DEATH;
  }

  /** Keep the normal Forge block sound context and remember when vanilla already emitted a step. */
  @Override
  protected void playStepSound(BlockPos position, BlockState state) {
    super.playStepSound(position, state);
    lastNativeFootstepAt = level().getGameTime();
    fallbackFootstepDistance = 0.0D;
  }

  /**
   * Feeds actuator movement back through the vanilla step hook. Native mob travel normally invokes
   * that hook itself; the distance fallback only runs when a custom movement controller did not.
   */
  private void emitPlayerLikeFootstep() {
    long now = level().getGameTime();
    Vec3 current = position();
    if (lastFootstepSample == null) {
      lastFootstepSample = current;
      return;
    }
    double dx = current.x - lastFootstepSample.x;
    double dz = current.z - lastFootstepSample.z;
    double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
    lastFootstepSample = current;
    if (!onGround() || isInWater() || isClimbingLadder() || horizontalDistance > 1.0D) {
      fallbackFootstepDistance = 0.0D;
      return;
    }
    if (lastNativeFootstepAt == now || horizontalDistance < 0.001D) return;
    fallbackFootstepDistance += horizontalDistance;
    double stride = isCrouching() ? 1.10D : isSprinting() ? 0.68D : 0.82D;
    if (fallbackFootstepDistance < stride) return;
    BlockPos support = getOnPos();
    playStepSound(support, level().getBlockState(support));
  }

  /** Combat on stairs needs a brief stable footing before aim can converge. */
  public boolean hasStairFooting() {
    BlockPos feet = blockPosition();
    return level().getBlockState(feet).getBlock() instanceof StairBlock
        || level().getBlockState(feet.below()).getBlock() instanceof StairBlock
        || getBlockStateOn().getBlock() instanceof StairBlock;
  }

  @Override
  public boolean removeWhenFarAway(double distanceToClosestPlayer) {
    return false;
  }

  /**
   * Match Hunters must keep their server AI and bridge state alive when no human player is close
   * enough to activate their chunk. The assignment is cleared as soon as the seat is revoked, so
   * ordinary manually spawned Hunters retain vanilla activation behavior.
   */
  @Override
  public boolean isAlwaysTicking() {
    return isMatchBot() && matchId != null;
  }

  /** Called only by EntityActuator so a sprint jump starts in the current server tick. */
  public boolean performActuatorJump() {
    if (!onGround() || isInWater() || isClimbingLadder()) return false;
    jumpFromGround();
    return true;
  }

  /** Called only by EntityActuator after validating the immediate combat movement lane. */
  public void performActuatorStrafe(float forward, float strafe, double speed) {
    if (getMoveControl() instanceof HunterLadderMoveControl control)
      control.strafePlayerLike(forward, strafe, speed);
  }

  @Override
  public void die(DamageSource source) {
    removalReason = "death:" + source.getMsgId();
    net.minecraft.world.entity.Entity attacker =
        HunterDamageAttackerResolver.resolve(source.getEntity(), source.getDirectEntity());
    HunterEntity killer = attacker instanceof HunterEntity hunter ? hunter : null;
    if (!level().isClientSide && killer != null)
      killer.coordinator().onConfirmedKill(killer, getUUID(), position(), level().getGameTime());
    UUID killerParticipantId = killer != null
        ? killer.matchBotId()
        : attacker == null ? null : attacker.getUUID();
    String deathResult = "NOT_MATCH_BOT";
    if (!level().isClientSide && HuntMcHunterAi.instance() != null)
      deathResult = HuntMcHunterAi.instance().hunterMatchService().reportDeath(
          this, killerParticipantId);
    // Bukkit creates the authoritative, tagged corpse weapons from the HuntMC armory loadout.
    // A second vanilla Forge drop would be untagged, non-interactable and could duplicate a gun.
    setDropChance(EquipmentSlot.MAINHAND, 0.0F);
    if ("DOWNED".equals(deathResult))
      setItemSlot(EquipmentSlot.MAINHAND, net.minecraft.world.item.ItemStack.EMPTY);
    super.die(source);
  }

  @Override
  public boolean hurt(DamageSource source, float amount) {
    net.minecraft.world.entity.Entity attacker =
        HunterDamageAttackerResolver.resolve(source.getEntity(), source.getDirectEntity());
    if (attacker instanceof net.minecraft.world.entity.LivingEntity living && isMatchAlly(living)) return false;
    boolean accepted = super.hurt(source, amount);
    if (accepted && !level().isClientSide) {
      if (isMatchBot() && attacker != null)
        HuntMcBukkitBridge.reportDamage(
            matchBotId(),
            attacker.getUUID(),
            source.getDirectEntity() instanceof com.tacz.guns.entity.EntityKineticBullet bullet
                    && bullet.getGunId() != null
                ? bullet.getGunId().toString()
                : "");
      coordinator.onDamaged(
          this,
          source.getMsgId(),
          attacker == null ? null : attacker.getUUID(),
          attacker == null ? null : attacker.position(),
          amount,
          level().getGameTime());
    }
    return accepted;
  }

  public HunterCoordinator coordinator() {
    return coordinator;
  }

  public HunterProfile profile() {
    return profile;
  }

  public int monitorId() {
    return monitorId;
  }

  public void setMonitorId(int monitorId) {
    if (monitorId <= 0) throw new IllegalArgumentException("Hunter monitor ID must be positive.");
    this.monitorId = monitorId;
  }

  public void setProfile(HunterProfile profile) {
    this.profile = profile;
    setCustomName(Component.literal("Hunter " + profile.commandName()));
  }

  public void configureMatchLoadout(List<String> weaponIds, boolean assigned) {
    List<ResourceLocation> parsed =
        weaponIds == null
            ? List.of()
            : weaponIds.stream()
                .map(ResourceLocation::tryParse)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .limit(2)
                .toList();
    boolean loadoutChanged = matchLoadoutAssigned != assigned || !matchWeaponIds.equals(parsed);
    matchWeaponIds = parsed;
    matchLoadoutAssigned = assigned;
    if (loadoutChanged && assigned && !parsed.isEmpty())
      setProfile(HunterProfile.fromGunId(parsed.get(0)));
  }

  public List<ResourceLocation> matchWeaponIds() {
    return matchWeaponIds;
  }

  public boolean matchLoadoutAssigned() {
    return matchLoadoutAssigned;
  }

  public void markRemovalReason(String reason) {
    if (reason != null && !reason.isBlank()) removalReason = reason;
  }

  public String removalReason() {
    return removalReason;
  }

  public boolean isMatchBot() {
    return matchBotId != null;
  }

  public UUID matchBotId() {
    return matchBotId;
  }

  public UUID matchId() {
    return matchId;
  }

  public String matchTeamId() {
    return matchTeamId;
  }

  /** Same-team match Hunters must neither acquire nor hurt one another. */
  public boolean isMatchAlly(HunterEntity other) {
    return other != null
        && HunterMatchAffiliation.sameTeam(matchId, matchTeamId, other.matchId, other.matchTeamId);
  }

  public boolean isMatchAlly(net.minecraft.world.entity.LivingEntity other) {
    if (other == null || other == this) return false;
    if (other instanceof HunterEntity hunter && isMatchAlly(hunter)) return true;
    return matchId != null && matchAllyIds.contains(other.getUUID());
  }

  public void setMatchAllyIds(Set<UUID> allyIds) {
    Set<UUID> normalized = matchId == null || allyIds == null ? Set.of() : Set.copyOf(allyIds);
    if (!matchAllyIds.equals(normalized)) coordinator.resetAssignment();
    matchAllyIds = normalized;
  }

  public void assignMatchBot(UUID botId, UUID assignedMatchId) {
    assignMatchBot(botId, assignedMatchId, "");
  }

  public void assignMatchBot(UUID botId, UUID assignedMatchId, String assignedTeamId) {
    if (botId == null || assignedMatchId == null)
      throw new IllegalArgumentException("match bot ids are required");
    String normalizedTeamId = assignedTeamId == null ? "" : assignedTeamId;
    boolean assignmentChanged =
        !botId.equals(matchBotId)
            || !assignedMatchId.equals(matchId)
            || !normalizedTeamId.equals(matchTeamId);
    matchBotId = botId;
    matchId = assignedMatchId;
    matchTeamId = normalizedTeamId;
    if (assignmentChanged) {
      coordinator.resetAssignment();
      matchActive = false;
      matchAllyIds = Set.of();
      matchWeaponIds = List.of();
      matchLoadoutAssigned = false;
    }
  }

  public void setMatchActive(boolean value) {
    matchActive = value;
  }

  public void clearMatchBot() {
    if (matchBotId != null || matchId != null) coordinator.resetAssignment();
    matchBotId = null;
    matchId = null;
    matchTeamId = "";
    matchActive = false;
    matchAllyIds = Set.of();
    matchWeaponIds = List.of();
    matchLoadoutAssigned = false;
  }

  @Override
  public void addAdditionalSaveData(CompoundTag tag) {
    super.addAdditionalSaveData(tag);
    tag.putString("HunterProfile", profile.commandName());
    tag.putInt("HunterMonitorId", monitorId);
    if (matchBotId != null) tag.putUUID("HunterMatchBot", matchBotId);
    if (matchId != null) tag.putUUID("HunterMatchId", matchId);
    if (!matchTeamId.isBlank()) tag.putString("HunterMatchTeam", matchTeamId);
  }

  @Override
  public void readAdditionalSaveData(CompoundTag tag) {
    super.readAdditionalSaveData(tag);
    HunterProfile.fromCommand(tag.getString("HunterProfile")).ifPresent(this::setProfile);
    if (tag.contains("HunterMonitorId")) {
      int savedMonitorId = tag.getInt("HunterMonitorId");
      if (savedMonitorId > 0) monitorId = savedMonitorId;
    }
    if (tag.hasUUID("HunterMatchBot") && tag.hasUUID("HunterMatchId"))
      assignMatchBot(
          tag.getUUID("HunterMatchBot"),
          tag.getUUID("HunterMatchId"),
          tag.contains("HunterMatchTeam") ? tag.getString("HunterMatchTeam") : "");
  }
}
