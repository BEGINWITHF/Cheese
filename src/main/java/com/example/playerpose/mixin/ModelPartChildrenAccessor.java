package com.example.playerpose.mixin;

import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** Lets the elytra hook move the wings under a body anchor without re-baking them. */
@Mixin(ModelPart.class)
public interface ModelPartChildrenAccessor {

    @Accessor("children")
    Map<String, ModelPart> playerpose$children();
}
