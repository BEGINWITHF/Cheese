package com.example.playerpose.mixin;

import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Lets the armor splitter read a part before it is baked: the cubes it owns (for the
 * texture offset and box size) and the pivot it sits on.
 */
@Mixin(PartDefinition.class)
public interface PartDefinitionAccessor {

    @Accessor("cubes")
    List<CubeDefinition> playerpose$cubes();

    @Accessor("partPose")
    PartPose playerpose$partPose();
}
