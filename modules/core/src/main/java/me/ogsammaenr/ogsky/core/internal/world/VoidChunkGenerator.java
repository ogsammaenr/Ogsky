package me.ogsammaenr.ogsky.core.internal.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Pure void {@link ChunkGenerator} used for {@code ogsky:overworld}, {@code ogsky:nether}, and {@code ogsky:end}.
 * Generates empty air chunks while preserving the configured {@link BiomeSource} and skipping all vanilla
 * structures, features, and initial mob spawns.
 */
public final class VoidChunkGenerator extends ChunkGenerator {
	private static final BlockState[] EMPTY_COLUMN = new BlockState[0];

	public static final MapCodec<VoidChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(
		instance -> instance.group(
			BiomeSource.CODEC.fieldOf("biome_source").forGetter(ChunkGenerator::getBiomeSource),
			Codec.INT.optionalFieldOf("min_y", -64).forGetter(VoidChunkGenerator::getMinY),
			Codec.INT.optionalFieldOf("height", 384).forGetter(VoidChunkGenerator::getGenDepth),
			Codec.INT.optionalFieldOf("sea_level", 63).forGetter(VoidChunkGenerator::getSeaLevel)
		).apply(instance, instance.stable(VoidChunkGenerator::new))
	);

	private final int minY;
	private final int genDepth;
	private final int seaLevel;

	public VoidChunkGenerator(BiomeSource biomeSource, int minY, int genDepth, int seaLevel) {
		super(biomeSource, biome -> BiomeGenerationSettings.EMPTY);
		this.minY = minY;
		this.genDepth = genDepth;
		this.seaLevel = seaLevel;
	}

	@Override
	protected MapCodec<? extends ChunkGenerator> codec() {
		return CODEC;
	}

	@Override
	public ChunkGeneratorStructureState createState(
		HolderLookup<StructureSet> structureSets,
		RandomState randomState,
		long legacyLevelSeed
	) {
		// Suppress all structure placement and concentric stronghold ring calculations in void Skyblock dimensions.
		return ChunkGeneratorStructureState.createForFlat(
			randomState,
			legacyLevelSeed,
			this.getOrigin(randomState),
			this.biomeSource,
			Stream.empty()
		);
	}

	@Override
	public void createStructures(
		RegistryAccess registryAccess,
		ChunkGeneratorStructureState state,
		StructureManager structureManager,
		ChunkAccess centerChunk,
		StructureTemplateManager structureTemplateManager,
		ResourceKey<Level> level
	) {
	}

	@Override
	public void createReferences(WorldGenLevel level, StructureManager structureManager, ChunkAccess centerChunk) {
	}

	@Override
	public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager structureManager) {
	}

	@Override
	public CompletableFuture<ChunkAccess> buildTerrain(
		ChunkAccess chunk,
		Blender blender,
		RandomState randomState,
		StructureManager structureManager,
		BiomeManager biomeManager,
		@Nullable WorldGenRegion carverBiomeRegion,
		Set<Holder<Biome>> possibleBiomes
	) {
		return CompletableFuture.completedFuture(chunk);
	}

	@Override
	public void spawnOriginalMobs(WorldGenRegion worldGenRegion) {
	}

	@Override
	public int getSpawnHeight(LevelHeightAccessor heightAccessor) {
		return this.seaLevel;
	}

	@Override
	public int getBaseHeight(
		int x,
		int z,
		Heightmap.Types type,
		LevelHeightAccessor heightAccessor,
		RandomState randomState
	) {
		return heightAccessor.getMinY();
	}

	@Override
	public NoiseColumn getBaseColumn(
		int x,
		int z,
		LevelHeightAccessor heightAccessor,
		RandomState randomState
	) {
		return new NoiseColumn(heightAccessor.getMinY(), EMPTY_COLUMN);
	}

	@Override
	public int getMinY() {
		return this.minY;
	}

	@Override
	public int getGenDepth() {
		return this.genDepth;
	}

	@Override
	public int getSeaLevel() {
		return this.seaLevel;
	}

	@Override
	public void addDebugScreenInfo(
		List<String> result,
		RandomState randomState,
		BlockPos feetPos,
		SamplerContext samplerContext
	) {
	}
}
