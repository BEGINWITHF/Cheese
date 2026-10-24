package com.example.playerpose.mixin;

import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** Gives the limb splitter access to the baked cubes so it can correct their UVs. */
@Mixin(ModelPart.class)
public interface ModelPartAccessor {

    @Accessor("cubes")
    List<ModelPart.Cube> playerpose$cubes();
}
