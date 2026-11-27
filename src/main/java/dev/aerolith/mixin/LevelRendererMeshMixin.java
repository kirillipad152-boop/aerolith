package dev.aerolith.mixin;

import dev.aerolith.CraftMesh;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Whatever makes the game build its chunks again, options, resources or shaders, goes for the meshes of crafts. */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMeshMixin {
    @Inject(method = "allChanged", at = @At("HEAD"))
    private void aerolith$allChanged(CallbackInfo callback) {
        CraftMesh.invalidateAll();
    }
}
