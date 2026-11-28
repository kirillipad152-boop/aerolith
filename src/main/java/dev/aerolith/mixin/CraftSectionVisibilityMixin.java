package dev.aerolith.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.aerolith.CraftEntity;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelExtractor.class)
abstract class CraftSectionVisibilityMixin {
    @WrapOperation(method = "isEntityVisible", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;isSectionCompiledAndVisible(Lnet/minecraft/core/BlockPos;J)Z"))
    private boolean aerolith$terrainSectionVisible(LevelRenderer renderer, BlockPos pos, long fadeDuration,
            Operation<Boolean> original, @Local(argsOnly = true) Entity entity) {
        // The raft draws its own plot mesh; distance and frustum checks have already run.
        return entity instanceof CraftEntity || original.call(renderer, pos, fadeDuration);
    }
}
