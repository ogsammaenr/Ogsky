package me.ogsammaenr.ogsky.core.internal.island;

import me.ogsammaenr.ogsky.core.api.flag.FlagRegistry;
import me.ogsammaenr.ogsky.core.api.flag.FlagType;
import me.ogsammaenr.ogsky.core.api.flag.IslandFlagDefinition;
import me.ogsammaenr.ogsky.core.api.island.IslandMemberEntry;
import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import me.ogsammaenr.ogsky.core.api.island.IslandView;
import me.ogsammaenr.ogsky.core.api.island.RelativeSpawn;
import me.ogsammaenr.ogsky.core.internal.config.CoreConfig;
import net.minecraft.resources.Identifier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Compact RAM-first island entity shared across {@code ogsky:overworld}, {@code ogsky:nether}, and {@code ogsky:end}.
 * Uses primitive arrays and a 64-bit {@code SETTING} bitmask with zero {@code Byte} boxing on hot paths.
 */
public final class Island implements IslandView {
	public static final byte STATE_OVERWORLD_READY = 1 << 0;
	public static final byte STATE_NETHER_READY = 1 << 1;
	public static final byte STATE_END_READY = 1 << 2;
	public static final byte STATE_PASTING = 1 << 3;
	public static final byte STATE_DELETING = 1 << 4;

	private final UUID islandId;
	private final int gridX;
	private final int gridZ;
	private final int centerX;
	private final int centerZ;
	private final Instant createdAt;
	private final FlagRegistry flagRegistry;

	private UUID ownerId;
	private short halfSize;
	private String bundleId;
	private byte lifecycleMask;
	private long settingsMask;
	private final byte[] protectionRanks;

	private RelativeSpawn overworldSpawn;
	private RelativeSpawn netherSpawn;
	private RelativeSpawn endSpawn;

	private long[] memberUuidHi;
	private long[] memberUuidLo;
	private byte[] memberRoles;

	private long[] bannedUuidHi;
	private long[] bannedUuidLo;

	public Island(
		UUID islandId,
		UUID ownerId,
		int gridX,
		int gridZ,
		int centerX,
		int centerZ,
		short halfSize,
		String bundleId,
		Instant createdAt,
		FlagRegistry flagRegistry,
		CoreConfig config
	) {
		this.islandId = Objects.requireNonNull(islandId, "islandId");
		this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
		this.gridX = gridX;
		this.gridZ = gridZ;
		this.centerX = centerX;
		this.centerZ = centerZ;
		this.halfSize = halfSize;
		this.bundleId = Objects.requireNonNull(bundleId, "bundleId");
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
		this.flagRegistry = Objects.requireNonNull(flagRegistry, "flagRegistry");

		if (!flagRegistry.isFrozen()) {
			throw new IllegalStateException("FlagRegistry must be frozen before constructing Island instances.");
		}

		List<IslandFlagDefinition> protectionDefs = flagRegistry.listByType(FlagType.PROTECTION);
		this.protectionRanks = new byte[protectionDefs.size()];
		for (int i = 0; i < protectionDefs.size(); i++) {
			IslandFlagDefinition def = protectionDefs.get(i);
			this.protectionRanks[i] = config.flags.resolveProtectionDefault(def.id().toString(), def.defaultMinRank());
		}

		long initialSettings = 0L;
		List<IslandFlagDefinition> settingDefs = flagRegistry.listByType(FlagType.SETTING);
		for (int i = 0; i < settingDefs.size(); i++) {
			IslandFlagDefinition def = settingDefs.get(i);
			if (config.flags.resolveSettingDefault(def.id().toString(), def.defaultBoolValue())) {
				initialSettings |= (1L << i);
			}
		}
		this.settingsMask = initialSettings;

		RelativeSpawn defaultSpawn = RelativeSpawn.atCenter(config.island.defaultSpawnY);
		this.overworldSpawn = defaultSpawn;
		this.netherSpawn = defaultSpawn;
		this.endSpawn = defaultSpawn;

		this.memberUuidHi = new long[] { ownerId.getMostSignificantBits() };
		this.memberUuidLo = new long[] { ownerId.getLeastSignificantBits() };
		this.memberRoles = new byte[] { IslandRole.OWNER };

		this.bannedUuidHi = new long[0];
		this.bannedUuidLo = new long[0];
	}

	@Override
	public UUID islandId() {
		return islandId;
	}

	@Override
	public UUID ownerId() {
		return ownerId;
	}

	@Override
	public int gridX() {
		return gridX;
	}

	@Override
	public int gridZ() {
		return gridZ;
	}

	@Override
	public int centerX() {
		return centerX;
	}

	@Override
	public int centerZ() {
		return centerZ;
	}

	@Override
	public short halfSize() {
		return halfSize;
	}

	public void setHalfSize(short halfSize) {
		this.halfSize = halfSize;
	}

	@Override
	public String bundleId() {
		return bundleId;
	}

	public void setBundleId(String bundleId) {
		this.bundleId = Objects.requireNonNull(bundleId, "bundleId");
	}

	public Instant createdAt() {
		return createdAt;
	}

	public byte lifecycleMask() {
		return lifecycleMask;
	}

	public void setLifecycleMask(byte lifecycleMask) {
		this.lifecycleMask = lifecycleMask;
	}

	public void setStateBit(byte bit, boolean value) {
		if (value) {
			this.lifecycleMask |= bit;
		} else {
			this.lifecycleMask &= (byte) ~bit;
		}
	}

	@Override
	public boolean isOverworldReady() {
		return (lifecycleMask & STATE_OVERWORLD_READY) != 0;
	}

	@Override
	public boolean isNetherReady() {
		return (lifecycleMask & STATE_NETHER_READY) != 0;
	}

	@Override
	public boolean isEndReady() {
		return (lifecycleMask & STATE_END_READY) != 0;
	}

	@Override
	public boolean isPasting() {
		return (lifecycleMask & STATE_PASTING) != 0;
	}

	@Override
	public boolean isDeleting() {
		return (lifecycleMask & STATE_DELETING) != 0;
	}

	@Override
	public RelativeSpawn overworldSpawn() {
		return overworldSpawn;
	}

	public void setOverworldSpawn(RelativeSpawn spawn) {
		this.overworldSpawn = Objects.requireNonNull(spawn, "spawn");
	}

	@Override
	public RelativeSpawn netherSpawn() {
		return netherSpawn;
	}

	public void setNetherSpawn(RelativeSpawn spawn) {
		this.netherSpawn = Objects.requireNonNull(spawn, "spawn");
	}

	@Override
	public RelativeSpawn endSpawn() {
		return endSpawn;
	}

	public void setEndSpawn(RelativeSpawn spawn) {
		this.endSpawn = Objects.requireNonNull(spawn, "spawn");
	}

	@Override
	public boolean contains(int x, int z) {
		return Math.abs(x - centerX) <= halfSize && Math.abs(z - centerZ) <= halfSize;
	}

	@Override
	public byte roleOf(UUID playerUuid) {
		Objects.requireNonNull(playerUuid, "playerUuid");
		long hi = playerUuid.getMostSignificantBits();
		long lo = playerUuid.getLeastSignificantBits();
		for (int i = 0; i < memberUuidHi.length; i++) {
			if (memberUuidHi[i] == hi && memberUuidLo[i] == lo) {
				return memberRoles[i];
			}
		}
		return IslandRole.VISITOR;
	}

	public void setMemberRole(UUID playerUuid, byte role) {
		Objects.requireNonNull(playerUuid, "playerUuid");
		if (!IslandRole.isValidMemberRole(role)) {
			throw new IllegalArgumentException("Invalid member role: " + role);
		}
		long hi = playerUuid.getMostSignificantBits();
		long lo = playerUuid.getLeastSignificantBits();
		for (int i = 0; i < memberUuidHi.length; i++) {
			if (memberUuidHi[i] == hi && memberUuidLo[i] == lo) {
				memberRoles[i] = role;
				if (role == IslandRole.OWNER) {
					this.ownerId = playerUuid;
				}
				return;
			}
		}
		int len = memberUuidHi.length;
		memberUuidHi = Arrays.copyOf(memberUuidHi, len + 1);
		memberUuidLo = Arrays.copyOf(memberUuidLo, len + 1);
		memberRoles = Arrays.copyOf(memberRoles, len + 1);
		memberUuidHi[len] = hi;
		memberUuidLo[len] = lo;
		memberRoles[len] = role;
		if (role == IslandRole.OWNER) {
			this.ownerId = playerUuid;
		}
	}

	public boolean removeMember(UUID playerUuid) {
		Objects.requireNonNull(playerUuid, "playerUuid");
		long hi = playerUuid.getMostSignificantBits();
		long lo = playerUuid.getLeastSignificantBits();
		for (int i = 0; i < memberUuidHi.length; i++) {
			if (memberUuidHi[i] == hi && memberUuidLo[i] == lo) {
				int newLen = memberUuidHi.length - 1;
				long[] nextHi = new long[newLen];
				long[] nextLo = new long[newLen];
				byte[] nextRoles = new byte[newLen];
				if (i > 0) {
					System.arraycopy(memberUuidHi, 0, nextHi, 0, i);
					System.arraycopy(memberUuidLo, 0, nextLo, 0, i);
					System.arraycopy(memberRoles, 0, nextRoles, 0, i);
				}
				if (i < newLen) {
					System.arraycopy(memberUuidHi, i + 1, nextHi, i, newLen - i);
					System.arraycopy(memberUuidLo, i + 1, nextLo, i, newLen - i);
					System.arraycopy(memberRoles, i + 1, nextRoles, i, newLen - i);
				}
				memberUuidHi = nextHi;
				memberUuidLo = nextLo;
				memberRoles = nextRoles;
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean isBanned(UUID playerUuid) {
		Objects.requireNonNull(playerUuid, "playerUuid");
		long hi = playerUuid.getMostSignificantBits();
		long lo = playerUuid.getLeastSignificantBits();
		for (int i = 0; i < bannedUuidHi.length; i++) {
			if (bannedUuidHi[i] == hi && bannedUuidLo[i] == lo) {
				return true;
			}
		}
		return false;
	}

	public boolean addBan(UUID playerUuid) {
		if (isBanned(playerUuid)) {
			return false;
		}
		int len = bannedUuidHi.length;
		bannedUuidHi = Arrays.copyOf(bannedUuidHi, len + 1);
		bannedUuidLo = Arrays.copyOf(bannedUuidLo, len + 1);
		bannedUuidHi[len] = playerUuid.getMostSignificantBits();
		bannedUuidLo[len] = playerUuid.getLeastSignificantBits();
		return true;
	}

	public boolean removeBan(UUID playerUuid) {
		Objects.requireNonNull(playerUuid, "playerUuid");
		long hi = playerUuid.getMostSignificantBits();
		long lo = playerUuid.getLeastSignificantBits();
		for (int i = 0; i < bannedUuidHi.length; i++) {
			if (bannedUuidHi[i] == hi && bannedUuidLo[i] == lo) {
				int newLen = bannedUuidHi.length - 1;
				long[] nextHi = new long[newLen];
				long[] nextLo = new long[newLen];
				if (i > 0) {
					System.arraycopy(bannedUuidHi, 0, nextHi, 0, i);
					System.arraycopy(bannedUuidLo, 0, nextLo, 0, i);
				}
				if (i < newLen) {
					System.arraycopy(bannedUuidHi, i + 1, nextHi, i, newLen - i);
					System.arraycopy(bannedUuidLo, i + 1, nextLo, i, newLen - i);
				}
				bannedUuidHi = nextHi;
				bannedUuidLo = nextLo;
				return true;
			}
		}
		return false;
	}

	@Override
	public List<IslandMemberEntry> members() {
		List<IslandMemberEntry> result = new ArrayList<>(memberUuidHi.length);
		for (int i = 0; i < memberUuidHi.length; i++) {
			result.add(new IslandMemberEntry(new UUID(memberUuidHi[i], memberUuidLo[i]), memberRoles[i]));
		}
		return List.copyOf(result);
	}

	public boolean hasOnlineMember(java.util.function.Predicate<UUID> onlineCheck) {
		for (int i = 0; i < memberUuidHi.length; i++) {
			if (memberRoles[i] >= IslandRole.COOP && onlineCheck.test(new UUID(memberUuidHi[i], memberUuidLo[i]))) {
				return true;
			}
		}
		return false;
	}

	@Override
	public List<UUID> bannedPlayers() {
		List<UUID> result = new ArrayList<>(bannedUuidHi.length);
		for (int i = 0; i < bannedUuidHi.length; i++) {
			result.add(new UUID(bannedUuidHi[i], bannedUuidLo[i]));
		}
		return List.copyOf(result);
	}

	public byte protectionRankBySlot(int slot) {
		return protectionRanks[slot];
	}

	public void setProtectionRankBySlot(int slot, byte minRank) {
		if (!IslandRole.isValidThreshold(minRank)) {
			throw new IllegalArgumentException("Invalid protection threshold rank: " + minRank);
		}
		protectionRanks[slot] = minRank;
	}

	@Override
	public byte protectionRank(Identifier flagId) {
		return protectionRanks[flagRegistry.slotOf(flagId)];
	}

	public boolean isSettingEnabledBySlot(int slot) {
		return (settingsMask & (1L << slot)) != 0L;
	}

	public void setSettingEnabledBySlot(int slot, boolean enabled) {
		long bit = 1L << slot;
		if (enabled) {
			settingsMask |= bit;
		} else {
			settingsMask &= ~bit;
		}
	}

	@Override
	public boolean isSettingEnabled(Identifier flagId) {
		return isSettingEnabledBySlot(flagRegistry.slotOf(flagId));
	}
}
