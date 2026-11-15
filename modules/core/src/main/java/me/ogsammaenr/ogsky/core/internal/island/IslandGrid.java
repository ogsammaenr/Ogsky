package me.ogsammaenr.ogsky.core.internal.island;

import me.ogsammaenr.ogsky.core.api.island.IslandMemberEntry;
import me.ogsammaenr.ogsky.core.api.island.IslandRole;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory spatial and membership index for all active Skyblock islands.
 * Performs {@code O(1)} {@code Math.floorDiv} spatial lookups without coordinate boxing.
 */
public final class IslandGrid {
	private final int cellSize;
	private final int halfCellOffset;
	private final int maxCoop;
	private final int maxTrusted;

	private final PackedGridMap<Island> byCell = new PackedGridMap<>();
	private final Map<UUID, Island> byId = new HashMap<>();
	private final Map<UUID, Island> primaryByPlayer = new HashMap<>();
	private final Map<UUID, List<UUID>> coopByPlayer = new HashMap<>();
	private final Map<UUID, List<UUID>> trustedByPlayer = new HashMap<>();
	private final Map<Long, GridCleanupEntry> cleanupQueue = new HashMap<>();

	public IslandGrid(int cellSize, int maxCoop, int maxTrusted) {
		if (cellSize < 32 || (cellSize % 16) != 0) {
			throw new IllegalArgumentException("Invalid cellSize: " + cellSize);
		}
		this.cellSize = cellSize;
		this.halfCellOffset = cellSize / 2;
		this.maxCoop = maxCoop;
		this.maxTrusted = maxTrusted;
	}

	public int cellSize() {
		return cellSize;
	}

	public int centerCoordinate(int gridCoord) {
		return gridCoord * cellSize + halfCellOffset;
	}

	public int gridCoordinate(int blockCoord) {
		return Math.floorDiv(blockCoord, cellSize);
	}

	public Island findAt(int x, int z) {
		long packed = PackedGridMap.pack(gridCoordinate(x), gridCoordinate(z));
		Island island = byCell.get(packed);
		if (island != null && island.contains(x, z)) {
			return island;
		}
		return null;
	}

	public Optional<Island> findById(UUID islandId) {
		Objects.requireNonNull(islandId, "islandId");
		return Optional.ofNullable(byId.get(islandId));
	}

	public Optional<Island> findByGridCell(int gridX, int gridZ) {
		return Optional.ofNullable(byCell.get(PackedGridMap.pack(gridX, gridZ)));
	}

	public Optional<Island> findPrimaryIsland(UUID playerUuid) {
		Objects.requireNonNull(playerUuid, "playerUuid");
		return Optional.ofNullable(primaryByPlayer.get(playerUuid));
	}

	public List<Island> findSecondaryIslands(UUID playerUuid) {
		Objects.requireNonNull(playerUuid, "playerUuid");
		List<Island> result = new ArrayList<>();
		addMatchingIslands(coopByPlayer.get(playerUuid), result);
		addMatchingIslands(trustedByPlayer.get(playerUuid), result);
		return List.copyOf(result);
	}

	public Collection<Island> allIslands() {
		return List.copyOf(byId.values());
	}

	public int size() {
		return byId.size();
	}

	public long allocateNextCell() {
		return SpiralGridAllocator.nextAvailableCell(
			packed -> byCell.containsKey(packed) || cleanupQueue.containsKey(packed)
		);
	}

	public void register(Island island) {
		Objects.requireNonNull(island, "island");
		long packed = PackedGridMap.pack(island.gridX(), island.gridZ());
		if (byId.containsKey(island.islandId())) {
			throw new IllegalStateException("Duplicate islandId in IslandGrid: " + island.islandId());
		}
		if (byCell.containsKey(packed)) {
			throw new IllegalStateException("Grid cell already occupied: (" + island.gridX() + ", " + island.gridZ() + ")");
		}
		if (cleanupQueue.containsKey(packed)) {
			throw new IllegalStateException("Grid cell pending cleanup: (" + island.gridX() + ", " + island.gridZ() + ")");
		}

		// Validate all member invariants before mutating reverse indices
		Set<UUID> seenMembers = new HashSet<>();
		for (IslandMemberEntry member : island.members()) {
			if (!seenMembers.add(member.playerUuid())) {
				throw new IllegalStateException("Duplicate member on island " + island.islandId() + ": " + member.playerUuid());
			}
			validateMembershipAddition(island, member.playerUuid(), member.role());
		}

		byId.put(island.islandId(), island);
		byCell.put(packed, island);
		for (IslandMemberEntry member : island.members()) {
			indexMember(island, member.playerUuid(), member.role());
		}
	}

	public Optional<Island> unregister(UUID islandId) {
		Objects.requireNonNull(islandId, "islandId");
		Island removed = byId.remove(islandId);
		if (removed == null) {
			return Optional.empty();
		}
		byCell.remove(PackedGridMap.pack(removed.gridX(), removed.gridZ()));
		for (IslandMemberEntry member : removed.members()) {
			deindexMember(removed, member.playerUuid(), member.role());
		}
		return Optional.of(removed);
	}

	public boolean canJoinOrSetRole(Island island, UUID playerUuid, byte newRole) {
		Objects.requireNonNull(island, "island");
		Objects.requireNonNull(playerUuid, "playerUuid");
		if (!IslandRole.isValidMemberRole(newRole)) {
			return false;
		}
		byte currentRole = island.roleOf(playerUuid);
		if (currentRole == newRole) {
			return true;
		}
		if (IslandRole.isPrimary(newRole)) {
			Island existingPrimary = primaryByPlayer.get(playerUuid);
			return existingPrimary == null || existingPrimary == island;
		}
		if (newRole == IslandRole.COOP) {
			List<UUID> current = coopByPlayer.get(playerUuid);
			return current == null || current.size() < maxCoop;
		}
		if (newRole == IslandRole.TRUSTED) {
			List<UUID> current = trustedByPlayer.get(playerUuid);
			return current == null || current.size() < maxTrusted;
		}
		return false;
	}

	public void assignMemberRole(Island island, UUID playerUuid, byte newRole) {
		Objects.requireNonNull(island, "island");
		Objects.requireNonNull(playerUuid, "playerUuid");
		if (!canJoinOrSetRole(island, playerUuid, newRole)) {
			throw new IllegalStateException(
				"Membership rule violation assigning role " + newRole + " to " + playerUuid + " on " + island.islandId()
			);
		}
		byte oldRole = island.roleOf(playerUuid);
		if (oldRole == newRole) {
			return;
		}
		if (oldRole == IslandRole.OWNER && newRole != IslandRole.OWNER && island.ownerId().equals(playerUuid)) {
			throw new IllegalStateException("Cannot demote current island OWNER before transferring ownership to another player.");
		}
		if (oldRole != IslandRole.VISITOR) {
			deindexMember(island, playerUuid, oldRole);
		}
		island.setMemberRole(playerUuid, newRole);
		indexMember(island, playerUuid, newRole);
	}

	public void transferOwnership(Island island, UUID newOwnerId) {
		Objects.requireNonNull(island, "island");
		Objects.requireNonNull(newOwnerId, "newOwnerId");
		UUID oldOwnerId = island.ownerId();
		if (oldOwnerId.equals(newOwnerId)) {
			return;
		}
		assignMemberRole(island, newOwnerId, IslandRole.OWNER);
		assignMemberRole(island, oldOwnerId, IslandRole.ADMIN);
	}

	public record CoopRemoval(UUID islandId, UUID playerUuid) {
	}

	/**
	 * Clears session-only COOP grants when either an island owner logs out or a coop helper logs out.
	 */
	public List<CoopRemoval> clearCoopForDisconnect(UUID disconnectedUuid) {
		Objects.requireNonNull(disconnectedUuid, "disconnectedUuid");
		List<CoopRemoval> removals = new ArrayList<>();
		Island ownedOrPrimary = primaryByPlayer.get(disconnectedUuid);
		if (ownedOrPrimary != null && ownedOrPrimary.ownerId().equals(disconnectedUuid)) {
			for (var member : ownedOrPrimary.members()) {
				if (member.role() == IslandRole.COOP) {
					if (removeMemberRole(ownedOrPrimary, member.playerUuid())) {
						removals.add(new CoopRemoval(ownedOrPrimary.islandId(), member.playerUuid()));
					}
				}
			}
		}
		List<UUID> coopIslands = coopByPlayer.get(disconnectedUuid);
		if (coopIslands != null && !coopIslands.isEmpty()) {
			for (UUID islandId : List.copyOf(coopIslands)) {
				Island island = byId.get(islandId);
				if (island != null && removeMemberRole(island, disconnectedUuid)) {
					removals.add(new CoopRemoval(islandId, disconnectedUuid));
				}
			}
		}
		return removals;
	}

	public boolean removeMemberRole(Island island, UUID playerUuid) {
		Objects.requireNonNull(island, "island");
		Objects.requireNonNull(playerUuid, "playerUuid");
		byte oldRole = island.roleOf(playerUuid);
		if (oldRole == IslandRole.VISITOR) {
			return false;
		}
		if (oldRole == IslandRole.OWNER && island.ownerId().equals(playerUuid)) {
			throw new IllegalStateException("Cannot remove current island OWNER without transferring ownership or deleting island.");
		}
		island.removeMember(playerUuid);
		deindexMember(island, playerUuid, oldRole);
		return true;
	}

	public void addCleanupEntry(GridCleanupEntry entry) {
		Objects.requireNonNull(entry, "entry");
		cleanupQueue.put(entry.packedCell(), entry);
	}

	public void completeCleanupCell(int gridX, int gridZ) {
		cleanupQueue.remove(PackedGridMap.pack(gridX, gridZ));
	}

	public Collection<GridCleanupEntry> pendingCleanups() {
		return List.copyOf(cleanupQueue.values());
	}

	public void clear() {
		byCell.clear();
		byId.clear();
		primaryByPlayer.clear();
		coopByPlayer.clear();
		trustedByPlayer.clear();
		cleanupQueue.clear();
	}

	private void validateMembershipAddition(Island targetIsland, UUID playerUuid, byte role) {
		if (!IslandRole.isValidMemberRole(role)) {
			throw new IllegalStateException("Invalid member role: " + role);
		}
		if (IslandRole.isPrimary(role)) {
			Island existing = primaryByPlayer.get(playerUuid);
			if (existing != null && existing != targetIsland) {
				throw new IllegalStateException(
					"Player " + playerUuid + " already belongs to primary island " + existing.islandId()
				);
			}
			return;
		}
		if (role == IslandRole.COOP) {
			int count = coopByPlayer.getOrDefault(playerUuid, List.of()).size();
			if (count >= maxCoop) {
				throw new IllegalStateException("Player " + playerUuid + " exceeded max COOP cap (" + maxCoop + ").");
			}
			return;
		}
		if (role == IslandRole.TRUSTED) {
			int count = trustedByPlayer.getOrDefault(playerUuid, List.of()).size();
			if (count >= maxTrusted) {
				throw new IllegalStateException("Player " + playerUuid + " exceeded max TRUSTED cap (" + maxTrusted + ").");
			}
		}
	}

	private void indexMember(Island island, UUID playerUuid, byte role) {
		if (IslandRole.isPrimary(role)) {
			primaryByPlayer.put(playerUuid, island);
		} else if (role == IslandRole.COOP) {
			coopByPlayer.computeIfAbsent(playerUuid, ignored -> new ArrayList<>(maxCoop)).add(island.islandId());
		} else if (role == IslandRole.TRUSTED) {
			trustedByPlayer.computeIfAbsent(playerUuid, ignored -> new ArrayList<>(maxTrusted)).add(island.islandId());
		}
	}

	private void deindexMember(Island island, UUID playerUuid, byte role) {
		if (IslandRole.isPrimary(role)) {
			primaryByPlayer.remove(playerUuid, island);
		} else if (role == IslandRole.COOP) {
			removeSecondaryEntry(coopByPlayer, playerUuid, island.islandId());
		} else if (role == IslandRole.TRUSTED) {
			removeSecondaryEntry(trustedByPlayer, playerUuid, island.islandId());
		}
	}

	private static void removeSecondaryEntry(Map<UUID, List<UUID>> map, UUID playerUuid, UUID islandId) {
		List<UUID> list = map.get(playerUuid);
		if (list == null) {
			return;
		}
		list.remove(islandId);
		if (list.isEmpty()) {
			map.remove(playerUuid);
		}
	}

	private void addMatchingIslands(List<UUID> ids, List<Island> target) {
		if (ids == null) {
			return;
		}
		for (UUID id : ids) {
			Island island = byId.get(id);
			if (island != null && !target.contains(island)) {
				target.add(island);
			}
		}
	}
}
