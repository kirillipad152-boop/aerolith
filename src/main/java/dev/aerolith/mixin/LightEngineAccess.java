package dev.aerolith.mixin;

import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ThreadedLevelLightEngine.class)
public interface LightEngineAccess {
    @Invoker("updateChunkStatus")
    void aerolith$updateChunkStatus(ChunkPos pos);
}
