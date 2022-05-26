package io.github.opencubicchunks.cubicchunks.mixin.core.common;

import java.net.Proxy;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.mojang.authlib.GameProfileRepository;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.datafixers.DataFixer;
import io.github.opencubicchunks.cc_core.api.CubePos;
import io.github.opencubicchunks.cc_core.world.CubicLevelHeightAccessor;
import io.github.opencubicchunks.cubicchunks.config.ServerConfig;
import io.github.opencubicchunks.cubicchunks.levelgen.feature.CubicFeatures;
import io.github.opencubicchunks.cubicchunks.mixin.access.common.BiomeGenerationSettingsAccess;
import io.github.opencubicchunks.cubicchunks.server.level.ServerCubeCache;
import io.github.opencubicchunks.cubicchunks.server.level.progress.CubeProgressListener;
import io.github.opencubicchunks.cubicchunks.world.ForcedCubesSaveData;
import io.github.opencubicchunks.cubicchunks.world.level.chunk.CubeAccess;
import io.github.opencubicchunks.cubicchunks.world.server.CubicMinecraftServer;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.WorldStem;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.server.level.progress.ChunkProgressListenerFactory;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.Unit;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ForcedChunksSavedData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.WorldData;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class MixinMinecraftServer implements CubicMinecraftServer {
    @Shadow protected long nextTickTime;
    @Shadow @Final private Map<ResourceKey<Level>, ServerLevel> levels;

    @Nullable private ServerConfig cubicChunksServerConfig;

    @Shadow protected abstract void waitUntilNextTick();
    @Shadow public abstract ServerLevel overworld();
    @Shadow public abstract boolean isRunning();

    @Shadow public abstract RegistryAccess.Frozen registryAccess();

    @Shadow @Final private static Logger LOGGER;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void injectFeatures(Thread thread, LevelStorageSource.LevelStorageAccess levelStorageAccess, PackRepository packRepository, WorldStem worldStem, Proxy proxy, DataFixer dataFixer,
                                MinecraftSessionService minecraftSessionService, GameProfileRepository gameProfileRepository, GameProfileCache gameProfileCache,
                                ChunkProgressListenerFactory chunkProgressListenerFactory, CallbackInfo ci) {
        cubicChunksServerConfig = ServerConfig.getConfig(levelStorageAccess);
        Registry<Biome> biomeRegistry = this.registryAccess().registry(Registry.BIOME_REGISTRY).get();

        for (Holder<Biome> holder : biomeRegistry.getTag(BiomeTags.IS_NETHER).get()) {
            addFeatureToBiome(holder.value(), GenerationStep.Decoration.RAW_GENERATION, CubicFeatures.LAVA_LEAK_FIX);
        }
    }

    //Use this to add our features to vanilla's biomes.
    private static void addFeatureToBiome(Biome biome, GenerationStep.Decoration stage, Holder<PlacedFeature> feature) {
        convertImmutableFeatures(biome);
        List<HolderSet<PlacedFeature>> features = ((BiomeGenerationSettingsAccess) biome.getGenerationSettings()).getFeatures();
        while (features.size() <= stage.ordinal()) {
            features.add(new MutableHolderSet<>());
        }
        ((MutableHolderSet) features.get(stage.ordinal())).add(feature);
    }

    private static void convertImmutableFeatures(Biome biome) {
        BiomeGenerationSettingsAccess access = (BiomeGenerationSettingsAccess) biome.getGenerationSettings();

        if (access.getFeatures() instanceof ImmutableList) {
            access.setFeatures(access
                .getFeatures()
                .stream()
                .map(MutableHolderSet::new)
                .collect(Collectors.toList())
            );
        }
    }

    /**
     * @author NotStirred
     * @reason Additional CC functionality and logging.
     */
    @Inject(method = "prepareLevels", at = @At("HEAD"), cancellable = true)
    private void prepareLevels(ChunkProgressListener statusListener, CallbackInfo ci) {
        System.out.println("PREPARELEVELS HERE " + MinecraftServer.class.getClassLoader().getClass().getName());

        ServerLevel serverLevel = this.overworld();
        if (!((CubicLevelHeightAccessor) serverLevel).isCubic()) {
            return;
        }
        ci.cancel();

        LOGGER.info("Preparing start region for dimension {}", serverLevel.dimension().location());
        BlockPos spawnPos = serverLevel.getSharedSpawnPos();
        CubePos spawnPosCube = CubePos.from(spawnPos);

        statusListener.updateSpawnPos(new ChunkPos(spawnPos));
        ((CubeProgressListener) statusListener).startCubes(spawnPosCube);

        ServerChunkCache serverChunkCache = serverLevel.getChunkSource();
        serverChunkCache.getLightEngine().setTaskPerBatch(500);
        this.nextTickTime = Util.getMillis();
        int radius = (int) Math.ceil(10 * (16 / (float) CubeAccess.DIAMETER_IN_BLOCKS)); //vanilla is 10, 32: 5, 64: 3
        int d = radius * 2 + 1;
        ((ServerCubeCache) serverChunkCache).addCubeRegionTicket(TicketType.START, spawnPosCube, radius + 1, Unit.INSTANCE);

        while (this.isRunning() && ((ServerCubeCache) serverChunkCache).getTickingGeneratedCubes() < d * d * d) {
            this.nextTickTime = Util.getMillis() + 10L;
            this.waitUntilNextTick();
        }
        LOGGER.info("Current loaded chunks: " + serverChunkCache.getTickingGenerated() + " | " + ((ServerCubeCache) serverChunkCache).getTickingGeneratedCubes());
        this.nextTickTime = Util.getMillis() + 10L;
        this.waitUntilNextTick();

        for (ServerLevel level : this.levels.values()) {
            ForcedChunksSavedData forcedChunksData = level.getDataStorage().get(ForcedChunksSavedData::load, "chunks");
            ForcedCubesSaveData forcedCubesData = level.getDataStorage().get(ForcedCubesSaveData::load, "cubes");
            if (forcedChunksData != null) {
                LongIterator forcedColumns = forcedChunksData.getChunks().iterator();
                LongIterator forcedCubes = forcedCubesData.getCubes().iterator();

                while (forcedColumns.hasNext()) {
                    long i = forcedColumns.nextLong();
                    ChunkPos chunkPos = new ChunkPos(i);
                    level.getChunkSource().updateChunkForced(chunkPos, true);
                }
                while (forcedCubes.hasNext()) {
                    long i = forcedCubes.nextLong();
                    CubePos cubePos = CubePos.from(i);
                    ((ServerCubeCache) level).forceCube(cubePos, true);
                }
            }
        }
        this.nextTickTime = Util.getMillis() + 10L;
        this.waitUntilNextTick();
        statusListener.stop();
        serverChunkCache.getLightEngine().setTaskPerBatch(5);
    }

    @Override
    @Nullable public ServerConfig getServerConfig() {
        return cubicChunksServerConfig;
    }
}