package me.ogsammaenr.ogsky.core.api.island;

import me.ogsammaenr.ogsky.core.api.blueprint.BlueprintBundleView;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read and mutation API for Skyblock islands exposed by {@code ogsky-core}.
 */
public interface IslandService {
	Optional<IslandView> findById(UUID islandId);

	Optional<IslandView> findPrimaryIsland(UUID playerUuid);

	List<IslandView> findSecondaryIslands(UUID playerUuid);

	Optional<IslandView> findAt(ResourceKey<Level> dimension, int x, int z);

	Optional<IslandView> findByGridCell(int gridX, int gridZ);

	int islandCount();

	int lockedCellSize();

	int maxCoop();

	int maxTrusted();

	String defaultBlueprintId();

	List<BlueprintBundleView> availableBlueprints();

	boolean isWorldSettingEnabled(Identifier flagId);

	boolean isProtectionAllowed(
		ResourceKey<Level> dimension,
		int x,
		int z,
		UUID playerUuid,
		Identifier flagId,
		boolean adminBypass
	);

	boolean isSettingEnabledAt(ResourceKey<Level> dimension, int x, int z, Identifier flagId);

	String roleDisplayName(byte role);

	IslandOperationResult<Byte> setProtectionRank(
		@Nullable UUID actorId,
		UUID islandId,
		Identifier flagId,
		byte newMinRank,
		boolean bypassRole
	);

	IslandOperationResult<Boolean> setSettingFlag(
		@Nullable MinecraftServer server,
		@Nullable UUID actorId,
		UUID islandId,
		Identifier flagId,
		boolean enabled,
		boolean bypassRole
	);

	IslandOperationResult<Boolean> setWorldSetting(Identifier flagId, boolean enabled);

	IslandOperationResult<Void> kickMember(
		@Nullable MinecraftServer server,
		UUID islandId,
		UUID actorId,
		UUID targetId,
		boolean bypassRole
	);

	IslandOperationResult<Byte> promoteMember(
		UUID islandId,
		UUID actorId,
		UUID targetId,
		boolean bypassOwnership
	);

	IslandOperationResult<Byte> demoteMember(
		UUID islandId,
		UUID actorId,
		UUID targetId,
		boolean bypassOwnership
	);

	IslandOperationResult<Void> transferOwnership(
		UUID islandId,
		UUID actorId,
		UUID newOwnerId,
		boolean bypassOwnership
	);

	IslandOperationResult<Void> removeCoop(
		@Nullable MinecraftServer server,
		UUID islandId,
		UUID actorId,
		UUID targetId,
		boolean bypassRole
	);

	IslandOperationResult<Void> removeTrust(
		@Nullable MinecraftServer server,
		UUID islandId,
		UUID actorId,
		UUID targetId,
		boolean bypassRole
	);

	IslandOperationResult<Void> banPlayer(
		@Nullable MinecraftServer server,
		UUID islandId,
		UUID actorId,
		UUID targetId,
		boolean bypassRole
	);

	IslandOperationResult<Void> unbanPlayer(
		UUID islandId,
		UUID actorId,
		UUID targetId,
		boolean bypassRole
	);

	IslandOperationResult<Void> leaveIsland(
		@Nullable MinecraftServer server,
		UUID playerId,
		UUID islandId
	);

	IslandOperationResult<IslandView> createIsland(
		@Nullable MinecraftServer server,
		@Nullable ServerPlayer actor,
		UUID ownerId,
		@Nullable String requestedBundleId
	);

	IslandOperationResult<IslandView> resetIsland(
		@Nullable MinecraftServer server,
		UUID actorId,
		UUID islandId,
		@Nullable String requestedBundleId,
		boolean bypassOwnership
	);

	IslandOperationResult<Void> teleportHome(
		MinecraftServer server,
		ServerPlayer player,
		UUID islandId,
		@Nullable ResourceKey<Level> targetDimension
	);

	IslandOperationResult<Void> setHome(
		ServerPlayer player,
		UUID islandId,
		boolean bypassRole
	);
}
