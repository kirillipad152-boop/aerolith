package dev.aerolith.mixin;

import java.util.concurrent.CompletableFuture;

import dev.aerolith.PlotChunks;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Chunks of the plot grid are kept by {@link PlotChunks}. Applied late: other mods replace these lookups altogether. */
@Mixin(value = ServerChunkCache.class, priority = 2000)
abstract class ServerChunkCachePlotMixin {
    @Shadow
    @Final
    ServerLevel level;
    @Shadow
    @Final
    Thread mainThread;

    @Inject(method = "getChunk", at = @At("HEAD"), cancellable = true)
    private void aerolith$getChunk(int x, int z, ChunkStatus status, boolean load, CallbackInfoReturnable<ChunkAccess> callback) {
        if (!PlotChunks.covers(level, x, z)) return;
        LevelChunk chunk = PlotChunks.find(level, x, z);
        // Off the server thread a missing chunk is left to vanilla, which asks again on the server thread.
        if (chunk != null || Thread.currentThread() == mainThread) {
            callback.setReturnValue(chunk != null || !load && !PlotChunks.available(level, x, z) ? chunk : PlotChunks.get(level, x, z));
        }
    }

    @Inject(method = "getChunkFuture", at = @At("HEAD"), cancellable = true)
    private void aerolith$getChunkFuture(int x, int z, ChunkStatus status, boolean load,
            CallbackInfoReturnable<CompletableFuture<ChunkResult<ChunkAccess>>> callback) {
        if (!PlotChunks.covers(level, x, z)) return;
        LevelChunk chunk = PlotChunks.find(level, x, z);
        if (chunk == null && load && Thread.currentThread() == mainThread) chunk = PlotChunks.get(level, x, z);
        callback.setReturnValue(CompletableFuture.completedFuture(chunk != null ? ChunkResult.of(chunk) : ChunkResult.error("No such plot chunk")));
    }

    @Inject(method = "getChunkNow", at = @At("HEAD"), cancellable = true)
    private void aerolith$getChunkNow(int x, int z, CallbackInfoReturnable<LevelChunk> callback) {
        if (PlotChunks.covers(level, x, z)) callback.setReturnValue(Thread.currentThread() == mainThread ? PlotChunks.find(level, x, z) : null);
    }

    @Inject(method = "hasChunk", at = @At("HEAD"), cancellable = true)
    private void aerolith$hasChunk(int x, int z, CallbackInfoReturnable<Boolean> callback) {
        if (PlotChunks.covers(level, x, z)) callback.setReturnValue(PlotChunks.available(level, x, z));
    }

    @Inject(method = "getChunkForLighting", at = @At("HEAD"), cancellable = true)
    private void aerolith$getChunkForLighting(int x, int z, CallbackInfoReturnable<LightChunk> callback) {
        if (PlotChunks.covers(level, x, z)) callback.setReturnValue(PlotChunks.find(level, x, z));
    }

    @Inject(method = "isPositionTicking", at = @At("HEAD"), cancellable = true)
    private void aerolith$isPositionTicking(long key, CallbackInfoReturnable<Boolean> callback) {
        if (PlotChunks.covers(level, ChunkPos.getX(key), ChunkPos.getZ(key))) {
            callback.setReturnValue(PlotChunks.ticks(level.getChunkSource().chunkMap.getDistanceManager(), key));
        }
    }

    @Inject(method = "save", at = @At("HEAD"))
    private void aerolith$save(boolean flush, CallbackInfo callback) {
        PlotChunks.save(level, flush);
    }
}
