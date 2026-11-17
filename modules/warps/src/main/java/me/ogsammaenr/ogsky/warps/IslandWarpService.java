package me.ogsammaenr.ogsky.warps;

import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.OgskyCoreApi;
import me.ogsammaenr.ogsky.core.api.flag.FlagMode;
import me.ogsammaenr.ogsky.core.api.flag.IslandFlagDefinition;
import me.ogsammaenr.ogsky.core.api.island.IslandOperationResult;
import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import me.ogsammaenr.ogsky.core.api.island.IslandView;
import me.ogsammaenr.ogsky.core.api.level.IslandLevelProvider;
import me.ogsammaenr.ogsky.core.api.world.OgskyDimensionKeys;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * Core service managing multi-warp per-island state, limits, strict safety checks,
 * warmup/cooldown teleportation, popularity anti-abuse tracking, and personal particle markers.
 */
public final class IslandWarpService {
	public static final Identifier MANAGE_WARPS_FLAG = Identifier.fromNamespaceAndPath("ogsky_warps", "manage_warps");
	public static final Identifier USE_WARP_FLAG = Identifier.fromNamespaceAndPath("ogsky_warps", "use_warp");
	public static final int UNLIMITED_WARPS = 10_000;
	private static final String LIMIT_TIER_PREFIX = "ogsky.warps.limit.";
	private static final String PREFIX = "[Ogsky Warps] ";

	private record PendingWarmup(
		UUID playerId,
		UUID islandId,
		String warpSlug,
		ResourceKey<Level> startDimension,
		double startX,
		double startY,
		double startZ,
		long startTick,
		long finishTick
	) {
	}

	private record MarkerSession(
		UUID viewerId,
		UUID islandId,
		@Nullable String targetSlug,
		long expiresAtTick,
		long nextRenderTick
	) {
	}

	private record VisitCooldownKey(UUID visitorId, UUID islandId, String slug) {
	}

	private final OgskyCoreApi core;
	private final Path configPath;
	private final WarpRepository repository;

	private volatile WarpConfig config;
	private final Map<UUID, LinkedHashMap<String, IslandWarp>> warpsByIsland = new ConcurrentHashMap<>();
	private final Map<UUID, Integer> cachedMaxWarpsByIsland = new ConcurrentHashMap<>();
	private final Map<UUID, Long> warpCooldownExpiryMillis = new ConcurrentHashMap<>();
	private final Map<UUID, PendingWarmup> pendingWarmups = new ConcurrentHashMap<>();
	private final Map<UUID, MarkerSession> activeMarkers = new ConcurrentHashMap<>();
	private final Map<VisitCooldownKey, Long> visitCountCooldowns = new ConcurrentHashMap<>();

	private long currentTick;

	public IslandWarpService(OgskyCoreApi core, Path configPath, WarpRepository repository) {
		this.core = Objects.requireNonNull(core, "core");
		this.configPath = Objects.requireNonNull(configPath, "configPath");
		this.repository = Objects.requireNonNull(repository, "repository");
		this.config = WarpConfigLoader.loadOrCreate(configPath);
	}

	public IslandFlagDefinition createManageWarpsFlagDefinition() {
		return IslandFlagDefinition.protection(
			MANAGE_WARPS_FLAG,
			FlagMode.BASIC,
			"minecraft:ender_pearl",
			190,
			this.config.manageWarpsRankByte(),
			false
		);
	}

	public IslandFlagDefinition createUseWarpFlagDefinition() {
		return IslandFlagDefinition.protection(
			USE_WARP_FLAG,
			FlagMode.BASIC,
			"minecraft:compass",
			195,
			this.config.useWarpRankByte(),
			false
		);
	}

	public WarpConfig config() {
		return this.config;
	}

	public void reload(@Nullable MinecraftServer server) {
		this.config = WarpConfigLoader.loadOrCreate(this.configPath);
		if (server != null) {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				onPlayerJoin(player);
			}
			for (UUID islandId : List.copyOf(this.warpsByIsland.keySet())) {
				this.core.guis().refreshIslandScreens(islandId);
			}
		}
		OgskyCore.LOGGER.info("Reloaded Ogsky warps config ({} categories, default limit {}).",
			this.config.categories.size(), this.config.defaultMaxWarpsPerIsland);
	}

	public void onServerStarted(MinecraftServer server) {
		loadFromDatabaseAndReconcile();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			onPlayerJoin(player);
		}
	}

	/**
	 * Loads persisted warps and owner limit snapshots from {@code warps.db} and removes orphans.
	 */
	public void loadFromDatabaseAndReconcile() {
		this.warpsByIsland.clear();
		this.cachedMaxWarpsByIsland.clear();
		WarpRepository.LoadedState loaded = this.repository.loadAllBlocking();

		Set<UUID> orphans = new HashSet<>();
		for (IslandWarp warp : loaded.warps()) {
			if (this.core.islands().findById(warp.islandId()).isEmpty()) {
				orphans.add(warp.islandId());
				continue;
			}
			this.warpsByIsland
				.computeIfAbsent(warp.islandId(), ignored -> new LinkedHashMap<>())
				.put(warp.slug(), warp);
		}
		for (var entry : loaded.cachedMaxWarps().entrySet()) {
			if (this.core.islands().findById(entry.getKey()).isEmpty()) {
				orphans.add(entry.getKey());
			} else {
				this.cachedMaxWarpsByIsland.put(entry.getKey(), entry.getValue());
			}
		}

		// Guarantee every non-empty island has exactly one default warp.
		for (var entry : this.warpsByIsland.entrySet()) {
			ensureSingleDefault(entry.getValue());
		}

		if (!orphans.isEmpty()) {
			this.repository.deleteIslandsAsync(orphans);
			OgskyCore.LOGGER.info("Cleaned up {} orphan warp island record(s).", orphans.size());
		}
	}

	public void clearRuntimeState() {
		this.warpsByIsland.clear();
		this.cachedMaxWarpsByIsland.clear();
		this.warpCooldownExpiryMillis.clear();
		this.pendingWarmups.clear();
		this.activeMarkers.clear();
		this.visitCountCooldowns.clear();
		this.currentTick = 0L;
	}

	public void onPlayerJoin(ServerPlayer player) {
		Objects.requireNonNull(player, "player");
		this.core.islands().findPrimaryIsland(player.getUUID()).ifPresent(island -> {
			if (island.ownerId().equals(player.getUUID())) {
				resolveAndCacheOwnerLimit(player, island.islandId());
			}
		});
	}

	public void onPlayerDisconnect(UUID playerId) {
		this.pendingWarmups.remove(playerId);
		this.activeMarkers.remove(playerId);
	}

	public void onIslandDeleted(UUID islandId) {
		this.warpsByIsland.remove(islandId);
		this.cachedMaxWarpsByIsland.remove(islandId);
		cancelSessionsForIsland(islandId, null);
		this.repository.deleteIslandAsync(islandId);
	}

	public void onIslandReset(UUID islandId) {
		this.warpsByIsland.remove(islandId);
		cancelSessionsForIsland(islandId, null);
		this.repository.deleteIslandAsync(islandId);
	}

	public void onIslandRangeChanged(UUID islandId) {
		this.core.guis().refreshIslandScreens(islandId);
	}

	public void onTeamOwnerChanged(@Nullable MinecraftServer server, UUID islandId, UUID newOwnerId) {
		this.cachedMaxWarpsByIsland.remove(islandId);
		if (server != null) {
			ServerPlayer onlineOwner = server.getPlayerList().getPlayer(newOwnerId);
			if (onlineOwner != null) {
				resolveAndCacheOwnerLimit(onlineOwner, islandId);
			}
		}
		this.core.guis().refreshIslandScreens(islandId);
	}

	public boolean canManageWarps(IslandView island, ServerPlayer player) {
		return canManageWarps(island, player.getUUID(), hasFlagBypass(player, MANAGE_WARPS_FLAG));
	}

	public boolean canManageWarps(IslandView island, UUID playerId, boolean adminBypass) {
		if (adminBypass) {
			return true;
		}
		if (island.isBanned(playerId)) {
			return false;
		}
		return island.roleOf(playerId) >= island.protectionRank(MANAGE_WARPS_FLAG);
	}

	public boolean canUseWarp(IslandView island, ServerPlayer player) {
		return canUseWarp(island, player.getUUID(), hasFlagBypass(player, USE_WARP_FLAG));
	}

	public boolean canUseWarp(IslandView island, UUID playerId, boolean adminBypass) {
		if (adminBypass) {
			return true;
		}
		if (island.isBanned(playerId)) {
			return false;
		}
		return island.roleOf(playerId) >= island.protectionRank(USE_WARP_FLAG);
	}

	public boolean hasLimitBypass(ServerPlayer player) {
		if (this.core.permissions().isBypassActive(player)) {
			return true;
		}
		return this.core.permissions().checkExplicitPermission(player.getUUID(), this.config.limitBypassPermission)
			.orElseGet(() -> this.core.permissions().hasPermission(player, this.config.limitBypassPermission, 4));
	}

	public boolean isDimensionAllowed(Identifier dimensionId) {
		if (dimensionId.equals(OgskyDimensionKeys.OVERWORLD.identifier())) {
			return this.config.allowOverworld;
		}
		if (dimensionId.equals(OgskyDimensionKeys.NETHER.identifier())) {
			return this.config.allowNether;
		}
		if (dimensionId.equals(OgskyDimensionKeys.END.identifier())) {
			return this.config.allowEnd;
		}
		return false;
	}

	public boolean isWithinIslandBounds(IslandView island, IslandWarp warp) {
		int bx = Mth.floor(warp.worldX(island.centerX()));
		int bz = Mth.floor(warp.worldZ(island.centerZ()));
		return island.contains(bx, bz);
	}

	public int resolveMaxWarps(@Nullable MinecraftServer server, IslandView island) {
		UUID ownerId = island.ownerId();
		if (server != null) {
			ServerPlayer onlineOwner = server.getPlayerList().getPlayer(ownerId);
			if (onlineOwner != null) {
				return resolveAndCacheOwnerLimit(onlineOwner, island.islandId());
			}
		}
		Optional<Boolean> explicitUnlimited = this.core.permissions().checkExplicitPermission(ownerId, this.config.limitBypassPermission);
		if (explicitUnlimited.isPresent() && explicitUnlimited.get()) {
			return UNLIMITED_WARPS;
		}
		int maxTier = -1;
		for (int tier : this.config.permissionLimitTiers) {
			Optional<Boolean> granted = this.core.permissions().checkExplicitPermission(ownerId, LIMIT_TIER_PREFIX + tier);
			if (granted.isPresent() && granted.get() && tier > maxTier) {
				maxTier = tier;
			}
		}
		if (maxTier > 0) {
			cacheOwnerLimit(island.islandId(), maxTier);
			return maxTier;
		}
		return this.cachedMaxWarpsByIsland.getOrDefault(island.islandId(), this.config.defaultMaxWarpsPerIsland);
	}

	public List<IslandWarp> getIslandWarps(UUID islandId) {
		LinkedHashMap<String, IslandWarp> map = this.warpsByIsland.get(islandId);
		if (map == null || map.isEmpty()) {
			return List.of();
		}
		return List.copyOf(map.values());
	}

	public Optional<IslandWarp> findWarp(UUID islandId, String rawName) {
		LinkedHashMap<String, IslandWarp> map = this.warpsByIsland.get(islandId);
		if (map == null || map.isEmpty()) {
			return Optional.empty();
		}
		return Optional.ofNullable(map.get(WarpHelper.normalizeSlug(rawName)));
	}

	public Optional<IslandWarp> findDefaultOrSoleWarp(UUID islandId, boolean enabledOnly) {
		List<IslandWarp> warps = getIslandWarps(islandId);
		List<IslandWarp> candidates = new ArrayList<>();
		for (IslandWarp warp : warps) {
			if (!enabledOnly || warp.enabled()) {
				candidates.add(warp);
			}
		}
		if (candidates.isEmpty()) {
			return Optional.empty();
		}
		for (IslandWarp warp : candidates) {
			if (warp.isDefault()) {
				return Optional.of(warp);
			}
		}
		if (candidates.size() == 1) {
			return Optional.of(candidates.getFirst());
		}
		return Optional.empty();
	}

	public List<WarpHelper.ShowcaseEntry> queryShowcaseEntries(
		MinecraftServer server,
		String categoryFilter,
		String sortMode,
		String searchQuery,
		@Nullable UUID filterIslandId
	) {
		WarpConfig cfg = this.config;
		String normalizedCategory = WarpHelper.normalizeCategoryFilter(categoryFilter, cfg);
		String normalizedSort = WarpHelper.normalizeSort(sortMode);
		String needle = searchQuery == null ? "" : searchQuery.trim().toLowerCase(Locale.ROOT);
		IslandLevelProvider levelProvider = this.core.services().find(IslandLevelProvider.class).orElse(null);
		Map<UUID, Long> levelCache = WarpHelper.newLevelCache();

		Collection<IslandView> islands;
		if (filterIslandId != null) {
			islands = this.core.islands().findById(filterIslandId).map(List::of).orElse(List.of());
		} else {
			islands = this.core.islands().allIslands();
		}

		List<WarpHelper.ShowcaseEntry> entries = new ArrayList<>();
		for (IslandView island : islands) {
			if (island.isPasting() || island.isDeleting()) {
				continue;
			}
			List<IslandWarp> islandWarps = getIslandWarps(island.islandId());
			if (islandWarps.isEmpty()) {
				continue;
			}
			String ownerName = WarpHelper.resolvePlayerName(server, island.ownerId());
			for (IslandWarp warp : islandWarps) {
				if (!warp.enabled() || !isDimensionAllowed(warp.dimensionId()) || !isWithinIslandBounds(island, warp)) {
					continue;
				}
				if (!"all".equals(normalizedCategory) && !warp.categoryId().equalsIgnoreCase(normalizedCategory)) {
					continue;
				}
				if (!needle.isEmpty() && !matchesSearch(warp, ownerName, needle)) {
					continue;
				}
				long levelValue = WarpHelper.resolveIslandLevel(levelProvider, levelCache, island.islandId());
				entries.add(new WarpHelper.ShowcaseEntry(island, warp, ownerName, levelValue));
			}
		}
		entries.sort(WarpHelper.showcaseComparator(normalizedSort));
		return List.copyOf(entries);
	}

	public IslandOperationResult<IslandWarp> createOrUpdateWarp(
		MinecraftServer server,
		ServerPlayer actor,
		UUID islandId,
		@Nullable String rawName,
		@Nullable String rawCategory
	) {
		WarpConfig cfg = this.config;
		if (!cfg.enabled) {
			return IslandOperationResult.fail("disabled", "Island warps are currently disabled by configuration.");
		}
		Optional<IslandView> maybeIsland = this.core.islands().findById(islandId);
		if (maybeIsland.isEmpty()) {
			return IslandOperationResult.fail("no_island", "Island does not exist.");
		}
		IslandView island = maybeIsland.get();
		if (island.isPasting() || island.isDeleting()) {
			return IslandOperationResult.fail("busy", "Island is still being prepared.");
		}
		if (!canManageWarps(island, actor)) {
			return IslandOperationResult.fail("insufficient_rank", "Island protection (manage_warps) prevents creating warps here.");
		}

		String effectiveRaw = (rawName == null || rawName.isBlank()) ? WarpConfig.DEFAULT_WARP_NAME : rawName;
		String displayName = WarpHelper.validateDisplayName(effectiveRaw, cfg.maxWarpNameLength);
		if (displayName == null) {
			return IslandOperationResult.fail(
				"invalid_name",
				"Warp name must use 1-" + cfg.maxWarpNameLength + " alphanumeric, '_' or '-' characters (and not 'all'/'off')."
			);
		}
		String slug = WarpHelper.normalizeSlug(displayName);

		ServerLevel actorLevel = actor.level();
		Identifier dimId = actorLevel.dimension().identifier();
		if (!OgskyDimensionKeys.isSkyblockDimension(actorLevel.dimension()) || !isDimensionAllowed(dimId)) {
			return IslandOperationResult.fail("wrong_dimension", "Warps cannot be set in this dimension.");
		}
		if (!island.contains(actor.getBlockX(), actor.getBlockZ())) {
			return IslandOperationResult.fail("outside_island", "Warp position must be inside the island bounds.");
		}
		if (!this.core.islands().isLocationSafe(actorLevel, actor.getX(), actor.getY(), actor.getZ())) {
			return IslandOperationResult.fail("unsafe_location", "Cannot set warp here: destination block or footing is unsafe.");
		}

		String categoryId = cfg.defaultCategory;
		if (rawCategory != null && !rawCategory.isBlank()) {
			WarpConfig.CategorySpec spec = cfg.findCategory(rawCategory);
			if (spec == null) {
				return IslandOperationResult.fail("invalid_category", "Unknown warp category '" + rawCategory + "'.");
			}
			categoryId = spec.id;
		}

		LinkedHashMap<String, IslandWarp> islandMap = this.warpsByIsland.computeIfAbsent(islandId, ignored -> new LinkedHashMap<>());
		IslandWarp existing = islandMap.get(slug);
		long now = System.currentTimeMillis();

		double relX = actor.getX() - island.centerX();
		double y = actor.getY();
		double relZ = actor.getZ() - island.centerZ();
		float yaw = actor.getYRot();
		float pitch = actor.getXRot();

		if (existing != null) {
			IslandWarp updated = new IslandWarp(
				islandId,
				slug,
				displayName,
				dimId,
				relX,
				y,
				relZ,
				yaw,
				pitch,
				rawCategory != null && !rawCategory.isBlank() ? categoryId : existing.categoryId(),
				existing.iconItemId(),
				existing.description(),
				existing.enabled(),
				existing.isDefault(),
				existing.visitCount(),
				existing.createdBy(),
				existing.createdAt(),
				now
			);
			islandMap.put(slug, updated);
			this.repository.upsertWarpAsync(updated);
			this.core.guis().refreshIslandScreens(islandId);
			return IslandOperationResult.ok("updated", "Updated warp '" + updated.displayName() + "' to your current position.", updated);
		}

		int maxWarps = resolveMaxWarps(server, island);
		if (!hasLimitBypass(actor) && islandMap.size() >= maxWarps) {
			return IslandOperationResult.fail(
				"limit_reached",
				"Island warp limit reached (" + islandMap.size() + "/" + maxWarps + ")."
			);
		}

		boolean makeDefault = islandMap.isEmpty() || islandMap.values().stream().noneMatch(IslandWarp::isDefault);
		IslandWarp created = new IslandWarp(
			islandId,
			slug,
			displayName,
			dimId,
			relX,
			y,
			relZ,
			yaw,
			pitch,
			categoryId,
			null,
			"",
			true,
			makeDefault,
			0L,
			actor.getUUID(),
			now,
			now
		);
		islandMap.put(slug, created);
		this.repository.upsertWarpAsync(created);
		this.core.guis().refreshIslandScreens(islandId);
		return IslandOperationResult.ok("created", "Created warp '" + created.displayName() + "'.", created);
	}

	public IslandOperationResult<Void> deleteWarp(
		UUID islandId,
		@Nullable ServerPlayer actor,
		String rawName,
		boolean adminOverride
	) {
		Optional<IslandView> maybeIsland = this.core.islands().findById(islandId);
		if (maybeIsland.isEmpty()) {
			return IslandOperationResult.fail("no_island", "Island does not exist.");
		}
		IslandView island = maybeIsland.get();
		if (!adminOverride && (actor == null || !canManageWarps(island, actor))) {
			return IslandOperationResult.fail("insufficient_rank", "Island protection (manage_warps) prevents deleting warps.");
		}
		LinkedHashMap<String, IslandWarp> map = this.warpsByIsland.get(islandId);
		String slug = WarpHelper.normalizeSlug(rawName);
		if (map == null || !map.containsKey(slug)) {
			return IslandOperationResult.fail("not_found", "Warp '" + rawName + "' does not exist on that island.");
		}
		IslandWarp removed = map.remove(slug);
		cancelSessionsForIsland(islandId, slug);
		this.repository.deleteWarpAsync(islandId, slug);

		if (removed != null && removed.isDefault() && !map.isEmpty()) {
			String nextKey = map.keySet().iterator().next();
			IslandWarp promoted = map.get(nextKey).withDefault(true, System.currentTimeMillis());
			map.put(nextKey, promoted);
			this.repository.upsertWarpAsync(promoted);
		}
		if (map.isEmpty()) {
			this.warpsByIsland.remove(islandId);
		}
		this.core.guis().refreshIslandScreens(islandId);
		return IslandOperationResult.ok("deleted", "Deleted warp '" + (removed != null ? removed.displayName() : slug) + "'.");
	}

	public IslandOperationResult<IslandWarp> editCategory(UUID islandId, ServerPlayer actor, String rawName, String rawCategory) {
		WarpConfig.CategorySpec spec = this.config.findCategory(rawCategory);
		if (spec == null) {
			return IslandOperationResult.fail("invalid_category", "Unknown category '" + rawCategory + "'.");
		}
		return mutateWarp(islandId, actor, rawName, "Category updated to " + spec.displayName + ".",
			warp -> warp.withCategory(spec.id, System.currentTimeMillis()));
	}

	public IslandOperationResult<IslandWarp> editDescription(UUID islandId, ServerPlayer actor, String rawName, @Nullable String rawDescription) {
		String trimmed = rawDescription == null ? "" : rawDescription.trim();
		if (trimmed.length() > this.config.maxDescriptionLength) {
			trimmed = trimmed.substring(0, this.config.maxDescriptionLength);
		}
		String finalDesc = trimmed;
		String msg = finalDesc.isEmpty() ? "Cleared warp description." : "Updated warp description.";
		return mutateWarp(islandId, actor, rawName, msg,
			warp -> warp.withDescription(finalDesc, System.currentTimeMillis()));
	}

	public IslandOperationResult<IslandWarp> editIcon(UUID islandId, ServerPlayer actor, String rawName, @Nullable String rawIcon) {
		if (rawIcon == null || rawIcon.isBlank()
			|| "head".equalsIgnoreCase(rawIcon.trim())
			|| "default".equalsIgnoreCase(rawIcon.trim())
			|| "player_head".equalsIgnoreCase(rawIcon.trim())) {
			return mutateWarp(islandId, actor, rawName, "Reset warp icon to owner player head.",
				warp -> warp.withIcon(null, System.currentTimeMillis()));
		}
		Optional<String> validated = WarpHelper.validateIconItemId(rawIcon, this.config);
		if (validated.isEmpty()) {
			return IslandOperationResult.fail("invalid_icon", "Invalid or forbidden icon item '" + rawIcon + "'.");
		}
		String iconId = validated.get();
		return mutateWarp(islandId, actor, rawName, "Updated warp icon to " + iconId + ".",
			warp -> warp.withIcon(iconId, System.currentTimeMillis()));
	}

	public IslandOperationResult<IslandWarp> toggleEnabled(
		UUID islandId,
		@Nullable ServerPlayer actor,
		String rawName,
		@Nullable Boolean explicitState,
		boolean adminOverride
	) {
		Optional<IslandView> maybeIsland = this.core.islands().findById(islandId);
		if (maybeIsland.isEmpty()) {
			return IslandOperationResult.fail("no_island", "Island does not exist.");
		}
		IslandView island = maybeIsland.get();
		if (!adminOverride && (actor == null || !canManageWarps(island, actor))) {
			return IslandOperationResult.fail("insufficient_rank", "Island protection (manage_warps) prevents editing warps.");
		}
		LinkedHashMap<String, IslandWarp> map = this.warpsByIsland.get(islandId);
		String slug = WarpHelper.normalizeSlug(rawName);
		IslandWarp existing = map == null ? null : map.get(slug);
		if (existing == null) {
			return IslandOperationResult.fail("not_found", "Warp '" + rawName + "' was not found.");
		}
		boolean target = explicitState != null ? explicitState : !existing.enabled();
		IslandWarp updated = existing.withEnabled(target, System.currentTimeMillis());
		map.put(slug, updated);
		this.repository.upsertWarpAsync(updated);
		if (!target) {
			cancelSessionsForIsland(islandId, slug);
		}
		this.core.guis().refreshIslandScreens(islandId);
		return IslandOperationResult.ok(
			"updated",
			"Warp '" + updated.displayName() + "' is now " + (target ? "enabled" : "disabled") + ".",
			updated
		);
	}

	public IslandOperationResult<IslandWarp> moveWarpHere(UUID islandId, ServerPlayer actor, String rawName) {
		Optional<IslandView> maybeIsland = this.core.islands().findById(islandId);
		if (maybeIsland.isEmpty()) {
			return IslandOperationResult.fail("no_island", "Island does not exist.");
		}
		IslandView island = maybeIsland.get();
		ServerLevel level = actor.level();
		Identifier dimId = level.dimension().identifier();
		if (!OgskyDimensionKeys.isSkyblockDimension(level.dimension()) || !isDimensionAllowed(dimId)) {
			return IslandOperationResult.fail("wrong_dimension", "Warps cannot be moved to this dimension.");
		}
		if (!island.contains(actor.getBlockX(), actor.getBlockZ())) {
			return IslandOperationResult.fail("outside_island", "You must be inside the island bounds to move this warp.");
		}
		if (!this.core.islands().isLocationSafe(level, actor.getX(), actor.getY(), actor.getZ())) {
			return IslandOperationResult.fail("unsafe_location", "Cannot move warp here: current position is unsafe.");
		}
		return mutateWarp(islandId, actor, rawName, "Moved warp to your current position.", warp -> warp.withLocation(
			dimId,
			actor.getX() - island.centerX(),
			actor.getY(),
			actor.getZ() - island.centerZ(),
			actor.getYRot(),
			actor.getXRot(),
			System.currentTimeMillis()
		));
	}

	public IslandOperationResult<IslandWarp> setDefaultWarp(UUID islandId, ServerPlayer actor, String rawName) {
		Optional<IslandView> maybeIsland = this.core.islands().findById(islandId);
		if (maybeIsland.isEmpty()) {
			return IslandOperationResult.fail("no_island", "Island does not exist.");
		}
		IslandView island = maybeIsland.get();
		if (!canManageWarps(island, actor)) {
			return IslandOperationResult.fail("insufficient_rank", "Island protection (manage_warps) prevents editing warps.");
		}
		LinkedHashMap<String, IslandWarp> map = this.warpsByIsland.get(islandId);
		String slug = WarpHelper.normalizeSlug(rawName);
		IslandWarp target = map == null ? null : map.get(slug);
		if (target == null) {
			return IslandOperationResult.fail("not_found", "Warp '" + rawName + "' was not found.");
		}
		long now = System.currentTimeMillis();
		for (var entry : map.entrySet()) {
			if (entry.getValue().isDefault() && !entry.getKey().equals(slug)) {
				IslandWarp cleared = entry.getValue().withDefault(false, now);
				map.put(entry.getKey(), cleared);
				this.repository.upsertWarpAsync(cleared);
			}
		}
		IslandWarp updated = target.withDefault(true, now);
		map.put(slug, updated);
		this.repository.upsertWarpAsync(updated);
		this.core.guis().refreshIslandScreens(islandId);
		return IslandOperationResult.ok("updated", "Set '" + updated.displayName() + "' as the default island warp.", updated);
	}

	public IslandOperationResult<Void> requestTeleport(
		MinecraftServer server,
		ServerPlayer player,
		UUID islandId,
		@Nullable String rawWarpName
	) {
		WarpConfig cfg = this.config;
		if (!cfg.enabled) {
			return IslandOperationResult.fail("disabled", "Island warps are currently disabled.");
		}
		Optional<IslandView> maybeIsland = this.core.islands().findById(islandId);
		if (maybeIsland.isEmpty()) {
			return IslandOperationResult.fail("no_island", "Target island does not exist.");
		}
		IslandView island = maybeIsland.get();

		boolean bypassUse = hasFlagBypass(player, USE_WARP_FLAG);
		IslandWarp targetWarp;
		if (rawWarpName == null || rawWarpName.isBlank()) {
			List<IslandWarp> enabled = getIslandWarps(islandId).stream().filter(IslandWarp::enabled).toList();
			Optional<IslandWarp> def = findDefaultOrSoleWarp(islandId, !bypassUse);
			if (def.isEmpty()) {
				if (enabled.size() > 1) {
					return IslandOperationResult.fail("multiple_warps", "That island has multiple warps; please specify a warp name.");
				}
				return IslandOperationResult.fail("no_warps", "That island has no active warps.");
			}
			targetWarp = def.get();
		} else {
			Optional<IslandWarp> found = findWarp(islandId, rawWarpName);
			if (found.isEmpty()) {
				return IslandOperationResult.fail("not_found", "Warp '" + rawWarpName + "' was not found on that island.");
			}
			targetWarp = found.get();
		}

		IslandOperationResult<Void> accessCheck = validateTeleportAccess(server, player, island, targetWarp, bypassUse);
		if (!accessCheck.success()) {
			return accessCheck;
		}

		boolean bypassCooldown = bypassUse || hasPermissionNode(player, cfg.bypassCooldownPermission, 4);
		if (!bypassCooldown && cfg.cooldownSeconds > 0) {
			Long expiresAt = this.warpCooldownExpiryMillis.get(player.getUUID());
			long now = System.currentTimeMillis();
			if (expiresAt != null && now < expiresAt) {
				long remainingSec = Math.max(1L, (expiresAt - now + 999L) / 1000L);
				return IslandOperationResult.fail("cooldown", "You must wait " + remainingSec + "s before warping again.");
			}
		}

		// Pre-check safety before queuing warmup so players don't wait only to hit a known trap.
		IslandOperationResult<Void> safetyPreCheck = checkWarpSafetyNow(server, island, targetWarp);
		if (!safetyPreCheck.success()) {
			return safetyPreCheck;
		}

		boolean bypassWarmup = bypassUse || hasPermissionNode(player, cfg.bypassWarmupPermission, 4);
		if (bypassWarmup || cfg.warmupSeconds <= 0) {
			return executeFinalTeleport(server, player, island, targetWarp);
		}

		long delayTicks = cfg.warmupSeconds * 20L;
		this.pendingWarmups.put(player.getUUID(), new PendingWarmup(
			player.getUUID(),
			islandId,
			targetWarp.slug(),
			player.level().dimension(),
			player.getX(),
			player.getY(),
			player.getZ(),
			this.currentTick,
			this.currentTick + delayTicks
		));
		return IslandOperationResult.ok(
			"warmup",
			"Warping to '" + targetWarp.displayName() + "' in " + cfg.warmupSeconds + "s. Do not move or take damage."
		);
	}

	public IslandOperationResult<Boolean> toggleMarker(
		MinecraftServer server,
		ServerPlayer viewer,
		UUID islandId,
		@Nullable String rawTarget
	) {
		Optional<IslandView> maybeIsland = this.core.islands().findById(islandId);
		if (maybeIsland.isEmpty()) {
			return IslandOperationResult.fail("no_island", "Island does not exist.");
		}
		IslandView island = maybeIsland.get();
		if (!canManageWarps(island, viewer)) {
			return IslandOperationResult.fail("insufficient_rank", "Island protection (manage_warps) prevents showing warp markers.");
		}

		String trimmed = rawTarget == null ? "" : rawTarget.trim();
		if ("off".equalsIgnoreCase(trimmed)) {
			MarkerSession removed = this.activeMarkers.remove(viewer.getUUID());
			return removed != null
				? IslandOperationResult.ok("disabled", "Warp particle marker hidden.", false)
				: IslandOperationResult.ok("disabled", "No active warp particle marker.", false);
		}

		String slugFilter = null;
		if (!trimmed.isEmpty() && !"all".equalsIgnoreCase(trimmed)) {
			Optional<IslandWarp> found = findWarp(islandId, trimmed);
			if (found.isEmpty()) {
				return IslandOperationResult.fail("not_found", "Warp '" + trimmed + "' does not exist on that island.");
			}
			slugFilter = found.get().slug();
		}

		List<IslandWarp> warps = getIslandWarps(islandId);
		if (warps.isEmpty()) {
			return IslandOperationResult.fail("no_warps", "This island has no warps to display.");
		}

		MarkerSession existing = this.activeMarkers.get(viewer.getUUID());
		if (trimmed.isEmpty() && existing != null && existing.islandId().equals(islandId) && existing.targetSlug() == null) {
			this.activeMarkers.remove(viewer.getUUID());
			return IslandOperationResult.ok("disabled", "Warp particle markers hidden.", false);
		}
		if (existing != null && existing.islandId().equals(islandId) && Objects.equals(existing.targetSlug(), slugFilter)) {
			this.activeMarkers.remove(viewer.getUUID());
			return IslandOperationResult.ok("disabled", "Warp particle marker hidden.", false);
		}

		long durationTicks = this.config.markerDurationSeconds * 20L;
		MarkerSession session = new MarkerSession(
			viewer.getUUID(),
			islandId,
			slugFilter,
			this.currentTick + durationTicks,
			this.currentTick
		);
		this.activeMarkers.put(viewer.getUUID(), session);
		renderMarkerSession(server, viewer, session);
		String targetLabel = slugFilter == null ? "all island warps" : "warp '" + slugFilter + "'";
		return IslandOperationResult.ok(
			"enabled",
			"Showing particles at " + targetLabel + " for " + this.config.markerDurationSeconds + "s.",
			true
		);
	}

	public boolean isWarmupPending(UUID playerId) {
		return this.pendingWarmups.containsKey(playerId);
	}

	public boolean hasActiveMarker(UUID playerId) {
		return this.activeMarkers.containsKey(playerId);
	}

	public long remainingCooldownSeconds(UUID playerId) {
		Long expiry = this.warpCooldownExpiryMillis.get(playerId);
		if (expiry == null) {
			return 0L;
		}
		long diff = expiry - System.currentTimeMillis();
		return diff <= 0L ? 0L : (diff + 999L) / 1000L;
	}

	public void onLivingDamage(LivingEntity entity, DamageSource source, float baseDamage, float damageTaken, boolean blocked) {
		if (!this.config.cancelWarmupOnDamage || !(entity instanceof ServerPlayer player)) {
			return;
		}
		if (damageTaken <= 0.0F && baseDamage <= 0.0F) {
			return;
		}
		PendingWarmup cancelled = this.pendingWarmups.remove(player.getUUID());
		if (cancelled != null) {
			player.sendSystemMessage(
				Component.literal(PREFIX + "Warp cancelled because you took damage.").withStyle(ChatFormatting.RED)
			);
		}
	}

	public void onServerTick(MinecraftServer server) {
		this.currentTick++;
		processWarmups(server);
		processMarkers(server);
		if (this.currentTick % 1200L == 0L) {
			pruneExpiredVisitCooldowns();
		}
	}

	private void processWarmups(MinecraftServer server) {
		if (this.pendingWarmups.isEmpty()) {
			return;
		}
		WarpConfig cfg = this.config;
		double toleranceSq = cfg.warmupMoveToleranceBlocks * cfg.warmupMoveToleranceBlocks;
		for (var entry : List.copyOf(this.pendingWarmups.entrySet())) {
			UUID playerId = entry.getKey();
			PendingWarmup warmup = entry.getValue();
			ServerPlayer player = server.getPlayerList().getPlayer(playerId);
			if (player == null) {
				this.pendingWarmups.remove(playerId);
				continue;
			}
			if (cfg.cancelWarmupOnMove) {
				if (!player.level().dimension().equals(warmup.startDimension())) {
					this.pendingWarmups.remove(playerId);
					player.sendSystemMessage(
						Component.literal(PREFIX + "Warp cancelled because you changed dimensions.").withStyle(ChatFormatting.RED)
					);
					continue;
				}
				double dx = player.getX() - warmup.startX();
				double dy = player.getY() - warmup.startY();
				double dz = player.getZ() - warmup.startZ();
				if (dx * dx + dy * dy + dz * dz > toleranceSq) {
					this.pendingWarmups.remove(playerId);
					player.sendSystemMessage(
						Component.literal(PREFIX + "Warp cancelled because you moved.").withStyle(ChatFormatting.RED)
					);
					continue;
				}
			}
			if (this.currentTick < warmup.finishTick()) {
				continue;
			}
			this.pendingWarmups.remove(playerId);
			Optional<IslandView> maybeIsland = this.core.islands().findById(warmup.islandId());
			Optional<IslandWarp> maybeWarp = findWarp(warmup.islandId(), warmup.warpSlug());
			if (maybeIsland.isEmpty() || maybeWarp.isEmpty()) {
				player.sendSystemMessage(
					Component.literal(PREFIX + "Warp cancelled: destination no longer exists.").withStyle(ChatFormatting.RED)
				);
				continue;
			}
			IslandOperationResult<Void> result = executeFinalTeleport(server, player, maybeIsland.get(), maybeWarp.get());
			player.sendSystemMessage(
				Component.literal(PREFIX + result.message())
					.withStyle(result.success() ? ChatFormatting.GREEN : ChatFormatting.RED)
			);
		}
	}

	private void processMarkers(MinecraftServer server) {
		if (this.activeMarkers.isEmpty()) {
			return;
		}
		for (var entry : List.copyOf(this.activeMarkers.entrySet())) {
			UUID viewerId = entry.getKey();
			MarkerSession session = entry.getValue();
			if (this.currentTick >= session.expiresAtTick()) {
				this.activeMarkers.remove(viewerId);
				continue;
			}
			if (this.currentTick < session.nextRenderTick()) {
				continue;
			}
			ServerPlayer viewer = server.getPlayerList().getPlayer(viewerId);
			if (viewer == null) {
				this.activeMarkers.remove(viewerId);
				continue;
			}
			if (this.core.islands().findById(session.islandId()).isEmpty()) {
				this.activeMarkers.remove(viewerId);
				continue;
			}
			renderMarkerSession(server, viewer, session);
			this.activeMarkers.put(viewerId, new MarkerSession(
				session.viewerId(),
				session.islandId(),
				session.targetSlug(),
				session.expiresAtTick(),
				this.currentTick + this.config.markerIntervalTicks
			));
		}
	}

	private void renderMarkerSession(MinecraftServer server, ServerPlayer viewer, MarkerSession session) {
		Optional<IslandView> maybeIsland = this.core.islands().findById(session.islandId());
		if (maybeIsland.isEmpty()) {
			return;
		}
		IslandView island = maybeIsland.get();
		ResourceKey<Level> viewerDim = viewer.level().dimension();
		if (!OgskyDimensionKeys.isSkyblockDimension(viewerDim)) {
			return;
		}
		ServerLevel level = viewer.level();
		ParticleOptions particle = resolveParticle(this.config.markerParticle);
		List<IslandWarp> warps = getIslandWarps(session.islandId());
		for (IslandWarp warp : warps) {
			if (session.targetSlug() != null && !session.targetSlug().equals(warp.slug())) {
				continue;
			}
			if (!warp.dimensionId().equals(viewerDim.identifier())) {
				continue;
			}
			double wx = warp.worldX(island.centerX());
			double wy = warp.y();
			double wz = warp.worldZ(island.centerZ());
			for (int i = 0; i < 6; i++) {
				level.sendParticles(viewer, particle, true, true, wx, wy + 0.35D + i * 0.5D, wz, 1, 0.02D, 0.02D, 0.02D, 0.0D);
			}
			for (int a = 0; a < 8; a++) {
				double rad = (Math.PI * 2.0D * a) / 8.0D;
				level.sendParticles(
					viewer,
					particle,
					true,
					true,
					wx + Math.cos(rad) * 0.75D,
					wy + 0.15D,
					wz + Math.sin(rad) * 0.75D,
					1,
					0.0D,
					0.0D,
					0.0D,
					0.0D
				);
			}
		}
	}

	private IslandOperationResult<Void> validateTeleportAccess(
		MinecraftServer server,
		ServerPlayer player,
		IslandView island,
		IslandWarp warp,
		boolean bypassUse
	) {
		if (island.isPasting() || island.isDeleting()) {
			return IslandOperationResult.fail("busy", "Target island is currently busy.");
		}
		if (!warp.enabled() && !bypassUse) {
			return IslandOperationResult.fail("disabled", "Warp '" + warp.displayName() + "' is currently disabled.");
		}
		if (!bypassUse && !isDimensionAllowed(warp.dimensionId())) {
			return IslandOperationResult.fail("dimension_disabled", "Warping into that dimension is disabled.");
		}
		if (!isWithinIslandBounds(island, warp)) {
			return IslandOperationResult.fail("outside_island", "Warp '" + warp.displayName() + "' is outside its island boundaries.");
		}
		if (!canUseWarp(island, player)) {
			return IslandOperationResult.fail("insufficient_rank", "Island protection (use_warp) prevents using warps here.");
		}
		if (!bypassUse && !this.core.islands().canEnterIsland(island.islandId(), player)) {
			String code = island.isBanned(player.getUUID()) ? "banned" : "locked";
			String msg = island.isBanned(player.getUUID())
				? "You are banned from that island."
				: "That island is currently locked.";
			return IslandOperationResult.fail(code, msg);
		}
		if (WarpHelper.toDimensionKey(warp.dimensionId()) == null) {
			return IslandOperationResult.fail("invalid_dimension", "Unsupported warp dimension: " + warp.dimensionId());
		}
		return IslandOperationResult.ok("allowed", "Access verified.");
	}

	private IslandOperationResult<Void> checkWarpSafetyNow(MinecraftServer server, IslandView island, IslandWarp warp) {
		ResourceKey<Level> dim = WarpHelper.toDimensionKey(warp.dimensionId());
		if (dim == null) {
			return IslandOperationResult.fail("invalid_dimension", "Unsupported warp dimension: " + warp.dimensionId());
		}
		ServerLevel level = server.getLevel(dim);
		if (level == null) {
			return IslandOperationResult.fail("missing_dimension", "Target dimension is not loaded.");
		}
		// If lazy Nether/End has never been pasted yet, allow teleportToLocation to prepare it first before checking blocks.
		if ((dim.equals(OgskyDimensionKeys.NETHER) && !island.isNetherReady())
			|| (dim.equals(OgskyDimensionKeys.END) && !island.isEndReady())) {
			return IslandOperationResult.ok("pending_lazy", "Lazy dimension will be initialized on arrival.");
		}
		double wx = warp.worldX(island.centerX());
		double wz = warp.worldZ(island.centerZ());
		if (!this.core.islands().isLocationSafe(level, wx, warp.y(), wz)) {
			return IslandOperationResult.fail("unsafe_location", "Warp '" + warp.displayName() + "' is unsafe and was cancelled.");
		}
		return IslandOperationResult.ok("safe", "Safe.");
	}

	private IslandOperationResult<Void> executeFinalTeleport(
		MinecraftServer server,
		ServerPlayer player,
		IslandView island,
		IslandWarp warp
	) {
		boolean bypassUse = hasFlagBypass(player, USE_WARP_FLAG);
		IslandOperationResult<Void> accessCheck = validateTeleportAccess(server, player, island, warp, bypassUse);
		if (!accessCheck.success()) {
			return accessCheck;
		}
		ResourceKey<Level> targetDim = WarpHelper.toDimensionKey(warp.dimensionId());
		if (targetDim == null) {
			return IslandOperationResult.fail("invalid_dimension", "Unsupported warp dimension.");
		}

		double wx = warp.worldX(island.centerX());
		double wz = warp.worldZ(island.centerZ());
		IslandOperationResult<Void> result = this.core.islands().teleportToLocation(
			server,
			player,
			island.islandId(),
			targetDim,
			wx,
			warp.y(),
			wz,
			warp.yaw(),
			warp.pitch(),
			!bypassUse
		);
		if (!result.success()) {
			return result;
		}

		if (this.config.cooldownSeconds > 0
			&& !bypassUse
			&& !hasPermissionNode(player, this.config.bypassCooldownPermission, 4)) {
			this.warpCooldownExpiryMillis.put(
				player.getUUID(),
				System.currentTimeMillis() + (this.config.cooldownSeconds * 1000L)
			);
		}
		recordSuccessfulVisit(island, warp, player.getUUID());
		return IslandOperationResult.ok("teleported", "Teleported to warp '" + warp.displayName() + "'.");
	}

	/**
	 * Increments popularity counter only for allowed visitors and outside the per-visitor anti-spam window.
	 */
	boolean recordSuccessfulVisit(IslandView island, IslandWarp warp, UUID visitorId) {
		if (!this.config.countIslandMembersInVisits && island.roleOf(visitorId) > IslandRole.VISITOR) {
			return false;
		}
		long now = System.currentTimeMillis();
		long cooldownMillis = this.config.visitCountCooldownSeconds * 1000L;
		VisitCooldownKey key = new VisitCooldownKey(visitorId, island.islandId(), warp.slug());
		if (cooldownMillis > 0L) {
			Long lastCounted = this.visitCountCooldowns.get(key);
			if (lastCounted != null && (now - lastCounted) < cooldownMillis) {
				return false;
			}
			this.visitCountCooldowns.put(key, now);
		}

		LinkedHashMap<String, IslandWarp> map = this.warpsByIsland.get(island.islandId());
		if (map == null) {
			return false;
		}
		IslandWarp latest = map.get(warp.slug());
		if (latest == null) {
			return false;
		}
		IslandWarp incremented = latest.withIncrementedVisits();
		map.put(warp.slug(), incremented);
		this.repository.incrementVisitCountAsync(island.islandId(), warp.slug(), incremented.visitCount());
		this.core.guis().refreshIslandScreens(island.islandId());
		return true;
	}

	private IslandOperationResult<IslandWarp> mutateWarp(
		UUID islandId,
		ServerPlayer actor,
		String rawName,
		String successMessage,
		UnaryOperator<IslandWarp> mutator
	) {
		Optional<IslandView> maybeIsland = this.core.islands().findById(islandId);
		if (maybeIsland.isEmpty()) {
			return IslandOperationResult.fail("no_island", "Island does not exist.");
		}
		IslandView island = maybeIsland.get();
		if (!canManageWarps(island, actor)) {
			return IslandOperationResult.fail("insufficient_rank", "Island protection (manage_warps) prevents editing warps.");
		}
		LinkedHashMap<String, IslandWarp> map = this.warpsByIsland.get(islandId);
		String slug = WarpHelper.normalizeSlug(rawName);
		IslandWarp existing = map == null ? null : map.get(slug);
		if (existing == null) {
			return IslandOperationResult.fail("not_found", "Warp '" + rawName + "' was not found.");
		}
		IslandWarp updated = mutator.apply(existing);
		map.put(slug, updated);
		this.repository.upsertWarpAsync(updated);
		this.core.guis().refreshIslandScreens(islandId);
		return IslandOperationResult.ok("updated", successMessage, updated);
	}

	private int resolveAndCacheOwnerLimit(ServerPlayer ownerPlayer, UUID islandId) {
		if (hasPermissionNode(ownerPlayer, this.config.limitBypassPermission, 4)) {
			cacheOwnerLimit(islandId, UNLIMITED_WARPS);
			return UNLIMITED_WARPS;
		}
		int maxTier = -1;
		for (int tier : this.config.permissionLimitTiers) {
			if (hasPermissionNode(ownerPlayer, LIMIT_TIER_PREFIX + tier, 4) && tier > maxTier) {
				maxTier = tier;
			}
		}
		int resolved = maxTier > 0 ? maxTier : this.config.defaultMaxWarpsPerIsland;
		cacheOwnerLimit(islandId, resolved);
		return resolved;
	}

	private void cacheOwnerLimit(UUID islandId, int maxWarps) {
		Integer previous = this.cachedMaxWarpsByIsland.put(islandId, maxWarps);
		if (previous == null || previous != maxWarps) {
			this.repository.upsertCachedMaxWarpsAsync(islandId, maxWarps);
		}
	}

	private boolean hasFlagBypass(ServerPlayer player, Identifier flagId) {
		if (this.core.permissions().isBypassActive(player)) {
			return true;
		}
		return this.core.permissions()
			.checkExplicitPermission(player.getUUID(), "ogsky.admin.bypass." + flagId.getPath())
			.orElse(false);
	}

	private boolean hasPermissionNode(ServerPlayer player, String node, int fallbackOpLevel) {
		Optional<Boolean> explicit = this.core.permissions().checkExplicitPermission(player.getUUID(), node);
		if (explicit.isPresent()) {
			return explicit.get();
		}
		return this.core.permissions().hasPermission(player, node, fallbackOpLevel);
	}

	private void cancelSessionsForIsland(UUID islandId, @Nullable String slugOrNull) {
		for (var entry : List.copyOf(this.pendingWarmups.entrySet())) {
			PendingWarmup warmup = entry.getValue();
			if (warmup.islandId().equals(islandId) && (slugOrNull == null || slugOrNull.equals(warmup.warpSlug()))) {
				this.pendingWarmups.remove(entry.getKey());
			}
		}
		for (var entry : List.copyOf(this.activeMarkers.entrySet())) {
			MarkerSession marker = entry.getValue();
			if (marker.islandId().equals(islandId) && (slugOrNull == null || slugOrNull.equals(marker.targetSlug()))) {
				this.activeMarkers.remove(entry.getKey());
			}
		}
	}

	private void pruneExpiredVisitCooldowns() {
		long cooldownMillis = this.config.visitCountCooldownSeconds * 1000L;
		if (cooldownMillis <= 0L) {
			this.visitCountCooldowns.clear();
			return;
		}
		long now = System.currentTimeMillis();
		Iterator<Map.Entry<VisitCooldownKey, Long>> it = this.visitCountCooldowns.entrySet().iterator();
		while (it.hasNext()) {
			if (now - it.next().getValue() >= cooldownMillis) {
				it.remove();
			}
		}
	}

	private void ensureSingleDefault(LinkedHashMap<String, IslandWarp> map) {
		if (map.isEmpty()) {
			return;
		}
		String firstDefaultKey = null;
		for (var entry : map.entrySet()) {
			if (entry.getValue().isDefault()) {
				if (firstDefaultKey == null) {
					firstDefaultKey = entry.getKey();
				} else {
					IslandWarp cleared = entry.getValue().withDefault(false, entry.getValue().updatedAt());
					map.put(entry.getKey(), cleared);
					this.repository.upsertWarpAsync(cleared);
				}
			}
		}
		if (firstDefaultKey == null) {
			String firstKey = map.keySet().iterator().next();
			IslandWarp promoted = map.get(firstKey).withDefault(true, map.get(firstKey).updatedAt());
			map.put(firstKey, promoted);
			this.repository.upsertWarpAsync(promoted);
		}
	}

	private static boolean matchesSearch(IslandWarp warp, String ownerName, String lowerNeedle) {
		return warp.displayName().toLowerCase(Locale.ROOT).contains(lowerNeedle)
			|| warp.slug().contains(lowerNeedle)
			|| warp.description().toLowerCase(Locale.ROOT).contains(lowerNeedle)
			|| ownerName.toLowerCase(Locale.ROOT).contains(lowerNeedle);
	}

	private static ParticleOptions resolveParticle(String rawParticleId) {
		Identifier id = Identifier.tryParse(rawParticleId);
		if (id != null) {
			Optional<ParticleType<?>> found = BuiltInRegistries.PARTICLE_TYPE.getOptional(id);
			if (found.isPresent() && found.get() instanceof ParticleOptions options) {
				return options;
			}
		}
		return ParticleTypes.END_ROD;
	}
}
