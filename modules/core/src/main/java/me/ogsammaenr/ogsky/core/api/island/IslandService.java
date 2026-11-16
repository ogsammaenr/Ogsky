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
 *
 * <p>All read methods query the in-memory island state with zero blocking SQLite I/O.
 * Mutation methods apply validated changes on the server thread, enqueue asynchronous SQLite
 * writes, dispatch corresponding {@link me.ogsammaenr.ogsky.core.api.event.OgskyEventBus} domain
 * events, and return structured {@link IslandOperationResult} outcomes rather than throwing on
 * gameplay rule rejections.</p>
 */
public interface IslandService {
	/** Looks up an island by its unique UUID from in-memory state. */
	Optional<IslandView> findById(UUID islandId);

	/** Finds the player's primary (home/owned/member) island, if any. */
	Optional<IslandView> findPrimaryIsland(UUID playerUuid);

	/** Lists secondary (co-op/trusted) islands associated with the player. */
	List<IslandView> findSecondaryIslands(UUID playerUuid);

	/** Finds the island whose protection bounds contain world coordinates {@code (x, z)} in {@code dimension}. */
	Optional<IslandView> findAt(ResourceKey<Level> dimension, int x, int z);

	/** Looks up the island assigned to Skyblock grid cell {@code (gridX, gridZ)}. */
	Optional<IslandView> findByGridCell(int gridX, int gridZ);

	/** Returns the total number of loaded islands currently managed by {@code ogsky-core}. */
	int islandCount();

	/** Returns the immutable cell pitch (grid spacing in blocks) configured for this world. */
	int lockedCellSize();

	/** Returns the configured maximum number of co-op members allowed per island. */
	int maxCoop();

	/** Returns the configured maximum number of trusted members allowed per island. */
	int maxTrusted();

	/** Returns the configured default blueprint bundle ID used when none is requested. */
	String defaultBlueprintId();

	/** Lists all discovered and loaded blueprint bundles available for island creation/reset. */
	List<BlueprintBundleView> availableBlueprints();

	/** Returns whether global {@code WORLD_SETTING} flag {@code flagId} is currently enabled. */
	boolean isWorldSettingEnabled(Identifier flagId);

	/**
	 * Evaluates whether {@code playerUuid} satisfies protection flag {@code flagId} at {@code (x, z)}
	 * in {@code dimension}, honoring {@code adminBypass} and visitor/member/wilderness rules.
	 */
	boolean isProtectionAllowed(
		ResourceKey<Level> dimension,
		int x,
		int z,
		UUID playerUuid,
		Identifier flagId,
		boolean adminBypass
	);

	/** Returns whether island {@code SETTING} flag {@code flagId} is enabled at {@code (x, z)} in {@code dimension}. */
	boolean isSettingEnabledAt(ResourceKey<Level> dimension, int x, int z, Identifier flagId);

	/** Returns the human-readable display name for an {@link IslandRole} constant byte value. */
	String roleDisplayName(byte role);

	/**
	 * Updates minimum required {@link IslandRole} rank for {@code PROTECTION} flag {@code flagId} on {@code islandId}.
	 * Set {@code bypassRole} to {@code true} for administrative/console overrides.
	 */
	IslandOperationResult<Byte> setProtectionRank(
		@Nullable UUID actorId,
		UUID islandId,
		Identifier flagId,
		byte newMinRank,
		boolean bypassRole
	);

	/**
	 * Toggles island {@code SETTING} flag {@code flagId} on {@code islandId}.
	 * Passing {@code server} enables world/weather side effects and event notifications when applicable.
	 */
	IslandOperationResult<Boolean> setSettingFlag(
		@Nullable MinecraftServer server,
		@Nullable UUID actorId,
		UUID islandId,
		Identifier flagId,
		boolean enabled,
		boolean bypassRole
	);

	/** Updates global {@code WORLD_SETTING} flag {@code flagId} and persists the change asynchronously. */
	IslandOperationResult<Boolean> setWorldSetting(Identifier flagId, boolean enabled);

	/** Kicks {@code targetId} from {@code islandId}; {@code bypassRole} skips actor rank checks. */
	IslandOperationResult<Void> kickMember(
		@Nullable MinecraftServer server,
		UUID islandId,
		UUID actorId,
		UUID targetId,
		boolean bypassRole
	);

	/** Promotes {@code targetId} within {@code islandId}; {@code bypassOwnership} overrides owner-only checks. */
	IslandOperationResult<Byte> promoteMember(
		UUID islandId,
		UUID actorId,
		UUID targetId,
		boolean bypassOwnership
	);

	/** Demotes {@code targetId} within {@code islandId}; {@code bypassOwnership} overrides owner-only checks. */
	IslandOperationResult<Byte> demoteMember(
		UUID islandId,
		UUID actorId,
		UUID targetId,
		boolean bypassOwnership
	);

	/** Transfers island ownership to {@code newOwnerId}; {@code bypassOwnership} allows admin transfer. */
	IslandOperationResult<Void> transferOwnership(
		UUID islandId,
		UUID actorId,
		UUID newOwnerId,
		boolean bypassOwnership
	);

	/** Revokes temporary co-op status for {@code targetId} on {@code islandId}. */
	IslandOperationResult<Void> removeCoop(
		@Nullable MinecraftServer server,
		UUID islandId,
		UUID actorId,
		UUID targetId,
		boolean bypassRole
	);

	/** Revokes trusted status for {@code targetId} on {@code islandId}. */
	IslandOperationResult<Void> removeTrust(
		@Nullable MinecraftServer server,
		UUID islandId,
		UUID actorId,
		UUID targetId,
		boolean bypassRole
	);

	/** Bans {@code targetId} from {@code islandId} and relocates them if currently inside the island. */
	IslandOperationResult<Void> banPlayer(
		@Nullable MinecraftServer server,
		UUID islandId,
		UUID actorId,
		UUID targetId,
		boolean bypassRole
	);

	/** Lifts an island ban on {@code targetId} for {@code islandId}. */
	IslandOperationResult<Void> unbanPlayer(
		UUID islandId,
		UUID actorId,
		UUID targetId,
		boolean bypassRole
	);

	/** Removes non-owner member {@code playerId} from {@code islandId} (or handles member departure). */
	IslandOperationResult<Void> leaveIsland(
		@Nullable MinecraftServer server,
		UUID playerId,
		UUID islandId
	);

	/**
	 * Allocates the next grid cell, initializes the island record, pastes {@code requestedBundleId}
	 * (or {@link #defaultBlueprintId()}), and optionally teleports {@code actor} on completion.
	 */
	IslandOperationResult<IslandView> createIsland(
		@Nullable MinecraftServer server,
		@Nullable ServerPlayer actor,
		UUID ownerId,
		@Nullable String requestedBundleId
	);

	/**
	 * Resets {@code islandId} back to {@code requestedBundleId} (or default), regenerating structures
	 * and firing {@link me.ogsammaenr.ogsky.core.api.event.IslandResetEvent}.
	 */
	IslandOperationResult<IslandView> resetIsland(
		@Nullable MinecraftServer server,
		UUID actorId,
		UUID islandId,
		@Nullable String requestedBundleId,
		boolean bypassOwnership
	);

	/** Teleports {@code player} to {@code islandId}'s home spawn in {@code targetDimension} (or Overworld when {@code null}). */
	IslandOperationResult<Void> teleportHome(
		MinecraftServer server,
		ServerPlayer player,
		UUID islandId,
		@Nullable ResourceKey<Level> targetDimension
	);

	/** Updates {@code islandId}'s home spawn relative to {@code player}'s current position inside the island. */
	IslandOperationResult<Void> setHome(
		ServerPlayer player,
		UUID islandId,
		boolean bypassRole
	);

	/** Returns an immutable snapshot list of all currently loaded islands. */
	List<IslandView> allIslands();
}
