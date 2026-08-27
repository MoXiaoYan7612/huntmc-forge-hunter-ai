package cn.huntmc.hunterai.client;

import cn.huntmc.hunterai.entity.HunterEntity;
import com.tacz.guns.api.entity.IGunOperator;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;

/** Standard player silhouette plus the actual TaCZ ItemStack held by the Hunter. */
public final class HunterRenderer
    extends HumanoidMobRenderer<HunterEntity, PlayerModel<HunterEntity>> {
  private static final ResourceLocation TEXTURE =
      new ResourceLocation("minecraft", "textures/entity/player/wide/steve.png");

  public HunterRenderer(EntityRendererProvider.Context context) {
    super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
  }

  @Override
  public ResourceLocation getTextureLocation(HunterEntity entity) {
    return TEXTURE;
  }

  /**
   * PlayerRenderer normally transfers this flag to PlayerModel. HumanoidMobRenderer does not,
   * so without this bridge the server pose and monitor logs said "crouching" while the Hunter
   * model remained visibly upright for clients.
   */
  @Override
  public void render(
      HunterEntity entity,
      float entityYaw,
      float partialTick,
      PoseStack poseStack,
      MultiBufferSource buffer,
      int packedLight) {
    model.crouching = entity.isCrouching();
    IGunOperator operator = IGunOperator.fromLivingEntity(entity);
    boolean aiming = operator.getSynIsAiming() && operator.getSynAimingProgress() > 0.10F;
    model.rightArmPose = aiming ? HumanoidModel.ArmPose.CROSSBOW_HOLD : HumanoidModel.ArmPose.EMPTY;
    model.leftArmPose = aiming ? HumanoidModel.ArmPose.CROSSBOW_HOLD : HumanoidModel.ArmPose.EMPTY;
    super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
  }
}
