package me.ogsammaenr.ogsky.gametest.mixin;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.WorldLoader;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.storage.LevelDataAndDimensions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(GameTestServer.class)
public abstract class GameTestServerMixin {
	@ModifyArg(
		method = "lambda$create$0",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/server/WorldLoader;load(Lnet/minecraft/server/WorldLoader$InitConfig;Lnet/minecraft/server/WorldLoader$WorldDataSupplier;Lnet/minecraft/server/WorldLoader$ResultFactory;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"
		),
		index = 1
	)
	private static WorldLoader.WorldDataSupplier<LevelDataAndDimensions.WorldDataAndGenSettings> ogsky$includeDatapackDimensionsInGameTest(
		WorldLoader.WorldDataSupplier<LevelDataAndDimensions.WorldDataAndGenSettings> original
	) {
		return context -> {
			WorldLoader.DataLoadOutput<LevelDataAndDimensions.WorldDataAndGenSettings> base = original.get(context);
			Registry<LevelStem> datapackDimensions = context.datapackDimensions().lookupOrThrow(Registries.LEVEL_STEM);
			if (datapackDimensions.keySet().isEmpty()) {
				return base;
			}
			Registry<LevelStem> currentDimensions = base.finalDimensions().lookupOrThrow(Registries.LEVEL_STEM);
			WorldDimensions.Complete merged = new WorldDimensions(currentDimensions).bake(datapackDimensions);
			return new WorldLoader.DataLoadOutput<>(base.cookie(), merged.dimensionsRegistryAccess());
		};
	}
}
