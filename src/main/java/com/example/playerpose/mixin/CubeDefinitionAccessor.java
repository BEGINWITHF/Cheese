package com.example.playerpose.mixin;

import net.minecraft.client.model.geom.builders.CubeDefinition;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.UVPair;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only view of a cube definition, so sliced armor can reuse its box and UVs. */
@Mixin(CubeDefinition.class)
public interface CubeDefinitionAccessor {

    @Accessor("origin")
    org.joml.Vector3f playerpose$origin();

    @Accessor("dimensions")
    org.joml.Vector3f playerpose$dimensions();

    @Accessor("grow")
    CubeDeformation playerpose$grow();

    @Accessor("mirror")
    boolean playerpose$mirror();

    @Accessor("texCoord")
    UVPair playerpose$texCoord();
}
