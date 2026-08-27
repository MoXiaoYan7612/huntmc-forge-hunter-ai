package cn.huntmc.hunterai.action;

import java.util.UUID;
import net.minecraft.world.phys.Vec3;

public record ActuatorResult(
    boolean navigationAccepted,
    boolean viewApplied,
    boolean postureApplied,
    boolean weaponApplied,
    boolean interactionAccepted,
    boolean jumped,
    boolean sprintRequested,
    boolean sprinting,
    double horizontalSpeed,
    float forwardInput,
    float strafeInput,
    UUID routeId,
    Vec3 pathEnd,
    Vec3 failureTarget,
    int consecutiveRouteFailures,
    MovementProgressState movementProgress,
    String movementResult,
    String weaponResult,
    String interactionResult) {}
