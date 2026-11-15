package me.ogsammaenr.ogsky.level;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.OgskyCoreApi;
import me.ogsammaenr.ogsky.core.api.db.ModuleDatabase;
import me.ogsammaenr.ogsky.core.api.db.SchemaMigration;
import me.ogsammaenr.ogsky.core.api.event.IslandLevelCalculatedEvent;
import me.ogsammaenr.ogsky.core.api.island.IslandView;
import me.ogsammaenr.ogsky.core.api.level.BlockPointBreakdown;
import me.ogsammaenr.ogsky.core.api.level.IslandLevelProvider;
import me.ogsammaenr.ogsky.core.api.level.IslandLevelSnapshot;
import me.ogsammaenr.ogsky.core.api.scan.IslandScanOptions;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Manages island level calculation, RAM leaderboard cache, and persistence in {@code level.db}.
 */
public final class IslandLevelService implements IslandLevelProvider {
	public static final String MODULE_DB_ID = "level";

	public static final List<SchemaMigration> MIGRATIONS = List.of(
		SchemaMigration.sql(
			1,
			"Initialize island_levels table",
			"""
			CREATE TABLE IF NOT EXISTS island_levels (
				island_id TEXT PRIMARY KEY,
				level_value INTEGER NOT NULL,
				block_points INTEGER NOT NULL,
				updated_at TEXT NOT NULL
			)
			""",
			"CREATE INDEX IF NOT EXISTS idx_island_levels_rank ON island_levels(level_value DESC, block_points DESC)"
		),
		SchemaMigration.sql(
			2,
			"Store serialized top contributing blocks breakdown on island_levels",
			"ALTER TABLE island_levels ADD COLUMN top_blocks_json TEXT NOT NULL DEFAULT '[]'"
		)
	);

	private static final Gson GSON = new Gson();
	private static final Type BREAKDOWN_LIST_TYPE = new TypeToken<List<StoredBreakdown>>() { }.getType();
	private static final Comparator<IslandLevelSnapshot> RANK_ORDER = Comparator
		.comparingLong(IslandLevelSnapshot::levelValue).reversed()
		.thenComparing((a, b) -> Long.compare(b.blockPoints(), a.blockPoints()))
		.thenComparing(IslandLevelSnapshot::updatedAt)
		.thenComparing(IslandLevelSnapshot::islandId);

	public record ScanOutcome(
		boolean success,
		String message,
		@Nullable IslandLevelSnapshot snapshot,
		int cooldownSecondsRemaining
	) {
		static ScanOutcome fail(String message, @Nullable IslandLevelSnapshot cached, int cooldownRemaining) {
			return new ScanOutcome(false, message, cached, cooldownRemaining);
		}

		static ScanOutcome ok(String message, IslandLevelSnapshot snapshot) {
			return new ScanOutcome(true, message, snapshot, 0);
		}
	}

	public record StoredLevelRow(
		UUID islandId,
		long levelValue,
		long blockPoints,
		Instant updatedAt,
		List<BlockPointBreakdown> topBlocks
	) {
	}

	private record StoredBreakdown(String blockId, int rawCount, int countedForPoints, long pointsEarned) {
	}

	private final OgskyCoreApi core;
	private final @Nullable Path configPath;
	private LevelConfig config;
	private LevelValuationTable valuationTable;

	private final Map<UUID, IslandLevelSnapshot> cacheByIslandId = new HashMap<>();
	private final Map<UUID, Integer> rankByIslandId = new HashMap<>();
	private List<IslandLevelSnapshot> rankedIslands = List.of();
	private final Set<UUID> activeScans = new HashSet<>();
	private final Map<UUID, Long> cooldownUntilEpochMillis = new HashMap<>();
	private final ArrayDeque<UUID> scanAllQueue = new ArrayDeque<>();
	private boolean scanAllRunning;
	private boolean serverTagsReady;

	public IslandLevelService(OgskyCoreApi core) {
		this(core, null, LevelConfig.defaults(), false);
	}

	public IslandLevelService(OgskyCoreApi core, @Nullable Path configPath) {
		this(core, configPath, LevelConfigLoader.loadOrCreate(configPath), true);
	}

	private IslandLevelService(
		OgskyCoreApi core,
		@Nullable Path configPath,
		LevelConfig initialConfig,
		boolean compileRuntimeBlocks
	) {
		this.core = core;
		this.configPath = configPath;
		this.config = initialConfig;
		this.config.ensureDefaultsAndValidate();
		// Block tags from datapacks are only bound after server startup; defer tag resolution until onServerStarted.
		this.valuationTable = compileRuntimeBlocks
			? LevelValuationTable.compile(this.config, false)
			: LevelValuationTable.compileForUnitTest(this.config, tag -> List.of());
	}

	public LevelConfig config() {
		return config;
	}

	public LevelValuationTable valuationTable() {
		return valuationTable;
	}

	public List<LevelValuationTable.BlockValueEntry> valuedBlocks() {
		return valuationTable.valuedBlocks();
	}

	public void reloadConfig() {
		if (configPath != null) {
			this.config = LevelConfigLoader.loadOrCreate(configPath);
		} else {
			this.config.ensureDefaultsAndValidate();
		}
		this.valuationTable = LevelValuationTable.compile(this.config, serverTagsReady);
	}

	public void onServerStarted(MinecraftServer server) {
		this.serverTagsReady = true;
		// Recompile after datapack block tags finish binding before running orphan reconciliation.
		reloadConfig();
		loadAllRowsAsync().thenAccept(rows -> server.execute(() -> {
			int removed = reconcileLoadedRows(rows);
			OgskyCore.LOGGER.info(
				"Loaded {} ranked Skyblock island(s) into RAM (pruned {} orphan level record(s)).",
				rankedIslands.size(),
				removed
			);
		})).exceptionally(ex -> {
			OgskyCore.LOGGER.error("Failed loading island levels from level.db", ex);
			return null;
		});
	}

	public void clearRuntimeState() {
		cacheByIslandId.clear();
		rankByIslandId.clear();
		rankedIslands = List.of();
		activeScans.clear();
		cooldownUntilEpochMillis.clear();
		scanAllQueue.clear();
		scanAllRunning = false;
	}

	public int reconcileLoadedRows(List<StoredLevelRow> rows) {
		List<UUID> orphanIds = new ArrayList<>();
		long pointsPerLevel = config.formula.pointsPerLevel;

		for (StoredLevelRow row : rows) {
			Optional<IslandView> maybeIsland = core.islands().findById(row.islandId());
			if (maybeIsland.isEmpty()) {
				orphanIds.add(row.islandId());
				continue;
			}
			// Preserve any scan result already calculated on server startup before async query finished.
			cacheByIslandId.computeIfAbsent(row.islandId(), id -> {
				long remainder = row.blockPoints() % pointsPerLevel;
				long toNext = pointsPerLevel - remainder;
				return new IslandLevelSnapshot(
					id,
					maybeIsland.get().ownerId(),
					row.levelValue(),
					row.blockPoints(),
					pointsPerLevel,
					toNext,
					row.updatedAt(),
					row.topBlocks()
				);
			});
		}

		rebuildRanking();
		if (!orphanIds.isEmpty()) {
			deleteBatchAsync(orphanIds).exceptionally(ex -> {
				OgskyCore.LOGGER.error("Failed cleaning {} orphan record(s) from level.db", orphanIds.size(), ex);
				return null;
			});
		}
		return orphanIds.size();
	}

	public void onIslandReset(UUID islandId) {
		core.scans().cancelScansForIsland(islandId);
		activeScans.remove(islandId);
		cooldownUntilEpochMillis.remove(islandId);
		cacheByIslandId.remove(islandId);
		rebuildRanking();
		saveZeroedOnResetAsync(islandId);
		core.guis().refreshIslandScreens(islandId);
	}

	public CompletableFuture<ScanOutcome> requestScan(
		MinecraftServer server,
		IslandView island,
		boolean bypassCooldown,
		boolean applyCooldown
	) {
		UUID islandId = island.islandId();
		IslandLevelSnapshot cached = cacheByIslandId.get(islandId);
		if (island.isDeleting()) {
			return CompletableFuture.completedFuture(
				ScanOutcome.fail("Island is currently being deleted.", cached, 0)
			);
		}
		if (island.isPasting()) {
			return CompletableFuture.completedFuture(
				ScanOutcome.fail("Wait until starter blueprint placement finishes.", cached, 0)
			);
		}
		if (!activeScans.add(islandId)) {
			return CompletableFuture.completedFuture(
				ScanOutcome.fail("Island level calculation is already in progress.", cached, 0)
			);
		}

		if (!bypassCooldown && config.scan.cooldownSeconds > 0) {
			long nowMs = System.currentTimeMillis();
			Long readyAt = cooldownUntilEpochMillis.get(islandId);
			if (readyAt != null && readyAt > nowMs) {
				activeScans.remove(islandId);
				int remaining = (int) Math.max(1L, (readyAt - nowMs + 999L) / 1000L);
				return CompletableFuture.completedFuture(
					ScanOutcome.fail("Level calculation is on cooldown (" + remaining + "s remaining).", cached, remaining)
				);
			}
		}

		if (applyCooldown && config.scan.cooldownSeconds > 0) {
			cooldownUntilEpochMillis.put(islandId, System.currentTimeMillis() + config.scan.cooldownSeconds * 1000L);
		}

		IdentityHashMap<Block, int[]> countedBlocks = new IdentityHashMap<>();
		IslandScanOptions scanOptions = new IslandScanOptions(
			config.scan.chunksPerTick,
			config.scan.maxNanosPerTick,
			config.scan.includeUnloadedChunks,
			config.scan.maxInFlightDiskReads
		);

		CompletableFuture<ScanOutcome> resultFuture = new CompletableFuture<>();
		core.scans().scanIsland(server, islandId, scanOptions, (state, count) -> {
			Block block = state.getBlock();
			int[] counter = countedBlocks.get(block);
			if (counter == null) {
				countedBlocks.put(block, new int[] { count });
			} else {
				counter[0] += count;
			}
		}).whenComplete((scanResult, ex) -> server.execute(() -> {
			activeScans.remove(islandId);
			if (ex != null) {
				OgskyCore.LOGGER.error("Island level scan failed for {}", islandId, ex);
				resultFuture.complete(ScanOutcome.fail("Island scan failed unexpectedly.", cacheByIslandId.get(islandId), 0));
				return;
			}
			if (scanResult == null || scanResult.aborted()) {
				String reason = scanResult != null && !scanResult.abortReason().isBlank()
					? scanResult.abortReason()
					: "Island scan was aborted.";
				resultFuture.complete(ScanOutcome.fail(reason, cacheByIslandId.get(islandId), 0));
				return;
			}

			Optional<IslandView> latest = core.islands().findById(islandId);
			if (latest.isEmpty() || latest.get().isDeleting()) {
				resultFuture.complete(ScanOutcome.fail("Island no longer exists.", null, 0));
				return;
			}

			LevelValuationTable.EvaluationResult eval = valuationTable.evaluateBlocks(countedBlocks);
			long previousLevel = cached != null ? cached.levelValue() : 0L;
			Instant updatedAt = Instant.now();
			IslandLevelSnapshot snapshot = new IslandLevelSnapshot(
				islandId,
				latest.get().ownerId(),
				eval.levelValue(),
				eval.blockPoints(),
				eval.pointsPerLevel(),
				eval.pointsToNextLevel(),
				updatedAt,
				eval.topBlocks()
			);

			cacheByIslandId.put(islandId, snapshot);
			rebuildRanking();
			persistSnapshotAsync(snapshot).exceptionally(dbEx -> {
				OgskyCore.LOGGER.error("Failed persisting level snapshot for island {}", islandId, dbEx);
				return null;
			});

			core.events().publish(new IslandLevelCalculatedEvent(
				islandId,
				latest.get().ownerId(),
				previousLevel,
				snapshot.levelValue(),
				snapshot.blockPoints()
			));
			core.guis().refreshIslandScreens(islandId);

			resultFuture.complete(ScanOutcome.ok("Island level updated.", snapshot));
		}));

		return resultFuture;
	}

	public int startScanAll(MinecraftServer server) {
		List<IslandView> islands = core.islands().allIslands();
		int queued = 0;
		for (IslandView island : islands) {
			if (!island.isDeleting() && !island.isPasting() && !scanAllQueue.contains(island.islandId())) {
				scanAllQueue.addLast(island.islandId());
				queued++;
			}
		}
		if (!scanAllRunning && !scanAllQueue.isEmpty()) {
			scanAllRunning = true;
			advanceScanAllQueue(server);
		}
		return queued;
	}

	private void advanceScanAllQueue(MinecraftServer server) {
		UUID nextId = scanAllQueue.pollFirst();
		while (nextId != null) {
			Optional<IslandView> island = core.islands().findById(nextId);
			if (island.isPresent() && !island.get().isDeleting() && !island.get().isPasting()) {
				requestScan(server, island.get(), true, false)
					.whenComplete((outcome, ex) -> server.execute(() -> advanceScanAllQueue(server)));
				return;
			}
			nextId = scanAllQueue.pollFirst();
		}
		scanAllRunning = false;
	}

	@Override
	public Optional<IslandLevelSnapshot> findSnapshot(UUID islandId) {
		return Optional.ofNullable(cacheByIslandId.get(islandId));
	}

	@Override
	public OptionalInt rankOf(UUID islandId) {
		Integer rank = rankByIslandId.get(islandId);
		return rank != null ? OptionalInt.of(rank) : OptionalInt.empty();
	}

	@Override
	public List<IslandLevelSnapshot> topIslands(int limit) {
		if (limit <= 0 || rankedIslands.isEmpty()) {
			return List.of();
		}
		int cappedLimit = Math.min(limit, config.top.maxCachedEntries);
		int end = Math.min(cappedLimit, rankedIslands.size());
		return rankedIslands.subList(0, end);
	}

	@Override
	public int rankedIslandCount() {
		return Math.min(rankedIslands.size(), config.top.maxCachedEntries);
	}

	@Override
	public boolean isScanInProgress(UUID islandId) {
		return activeScans.contains(islandId);
	}

	public CompletableFuture<Void> saveLevel(UUID islandId, long levelValue, long blockPoints) {
		Instant now = Instant.now();
		long pointsPerLevel = config.formula.pointsPerLevel;
		long toNext = pointsPerLevel - (blockPoints % pointsPerLevel);
		core.islands().findById(islandId).ifPresent(island -> {
			cacheByIslandId.put(islandId, new IslandLevelSnapshot(
				islandId,
				island.ownerId(),
				levelValue,
				blockPoints,
				pointsPerLevel,
				toNext,
				now,
				List.of()
			));
			rebuildRanking();
		});
		return persistRawAsync(islandId, levelValue, blockPoints, now, List.of());
	}

	public CompletableFuture<Optional<Long>> findLevel(UUID islandId) {
		ModuleDatabase db = core.databases().require(MODULE_DB_ID);
		return db.queryAsync(conn -> {
			try (PreparedStatement ps = conn.prepareStatement(
				"SELECT level_value FROM island_levels WHERE island_id = ?"
			)) {
				ps.setString(1, islandId.toString());
				try (ResultSet rs = ps.executeQuery()) {
					if (rs.next()) {
						return Optional.of(rs.getLong("level_value"));
					}
					return Optional.empty();
				}
			}
		});
	}

	public CompletableFuture<Void> deleteIslandLevel(UUID islandId) {
		core.scans().cancelScansForIsland(islandId);
		activeScans.remove(islandId);
		cooldownUntilEpochMillis.remove(islandId);
		if (cacheByIslandId.remove(islandId) != null) {
			rebuildRanking();
		}
		ModuleDatabase db = core.databases().require(MODULE_DB_ID);
		return db.executeAsync(conn -> {
			try (PreparedStatement ps = conn.prepareStatement(
				"DELETE FROM island_levels WHERE island_id = ?"
			)) {
				ps.setString(1, islandId.toString());
				ps.executeUpdate();
			}
		});
	}

	private CompletableFuture<List<StoredLevelRow>> loadAllRowsAsync() {
		ModuleDatabase db = core.databases().require(MODULE_DB_ID);
		return db.queryAsync(conn -> {
			List<StoredLevelRow> rows = new ArrayList<>();
			try (PreparedStatement ps = conn.prepareStatement(
				"SELECT island_id, level_value, block_points, updated_at, top_blocks_json FROM island_levels"
			);
				 ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					UUID islandId = UUID.fromString(rs.getString("island_id"));
					long levelValue = rs.getLong("level_value");
					long blockPoints = rs.getLong("block_points");
					Instant updatedAt = parseInstantOrEpoch(rs.getString("updated_at"));
					List<BlockPointBreakdown> breakdown = deserializeBreakdown(rs.getString("top_blocks_json"));
					rows.add(new StoredLevelRow(islandId, levelValue, blockPoints, updatedAt, breakdown));
				}
			}
			return rows;
		});
	}

	private CompletableFuture<Void> persistSnapshotAsync(IslandLevelSnapshot snapshot) {
		return persistRawAsync(
			snapshot.islandId(),
			snapshot.levelValue(),
			snapshot.blockPoints(),
			snapshot.updatedAt(),
			snapshot.topBlocks()
		);
	}

	private CompletableFuture<Void> persistRawAsync(
		UUID islandId,
		long levelValue,
		long blockPoints,
		Instant updatedAt,
		List<BlockPointBreakdown> topBlocks
	) {
		ModuleDatabase db = core.databases().require(MODULE_DB_ID);
		String json = serializeBreakdown(topBlocks);
		return db.executeAsync(conn -> {
			try (PreparedStatement ps = conn.prepareStatement(
				"""
				INSERT INTO island_levels (island_id, level_value, block_points, updated_at, top_blocks_json)
				VALUES (?, ?, ?, ?, ?)
				ON CONFLICT(island_id) DO UPDATE SET
					level_value = excluded.level_value,
					block_points = excluded.block_points,
					updated_at = excluded.updated_at,
					top_blocks_json = excluded.top_blocks_json
				"""
			)) {
				ps.setString(1, islandId.toString());
				ps.setLong(2, levelValue);
				ps.setLong(3, blockPoints);
				ps.setString(4, updatedAt.toString());
				ps.setString(5, json);
				ps.executeUpdate();
			}
		});
	}

	private void saveZeroedOnResetAsync(UUID islandId) {
		core.databases().find(MODULE_DB_ID).ifPresent(db -> db.executeAsync(conn -> {
			try (PreparedStatement ps = conn.prepareStatement("DELETE FROM island_levels WHERE island_id = ?")) {
				ps.setString(1, islandId.toString());
				ps.executeUpdate();
			}
		}));
	}

	private CompletableFuture<Void> deleteBatchAsync(List<UUID> orphanIds) {
		ModuleDatabase db = core.databases().require(MODULE_DB_ID);
		return db.executeAsync(conn -> {
			try (PreparedStatement ps = conn.prepareStatement("DELETE FROM island_levels WHERE island_id = ?")) {
				for (UUID id : orphanIds) {
					ps.setString(1, id.toString());
					ps.addBatch();
				}
				ps.executeBatch();
			}
		});
	}

	private void rebuildRanking() {
		if (cacheByIslandId.isEmpty()) {
			rankedIslands = List.of();
			rankByIslandId.clear();
			return;
		}
		List<IslandLevelSnapshot> sorted = new ArrayList<>(cacheByIslandId.values());
		sorted.sort(RANK_ORDER);
		rankedIslands = List.copyOf(sorted);
		rankByIslandId.clear();
		for (int i = 0; i < sorted.size(); i++) {
			rankByIslandId.put(sorted.get(i).islandId(), i + 1);
		}
	}

	static String serializeBreakdown(List<BlockPointBreakdown> topBlocks) {
		if (topBlocks == null || topBlocks.isEmpty()) {
			return "[]";
		}
		List<StoredBreakdown> dto = new ArrayList<>(topBlocks.size());
		for (BlockPointBreakdown b : topBlocks) {
			dto.add(new StoredBreakdown(b.blockId().toString(), b.rawCount(), b.countedForPoints(), b.pointsEarned()));
		}
		return GSON.toJson(dto);
	}

	static List<BlockPointBreakdown> deserializeBreakdown(@Nullable String rawJson) {
		if (rawJson == null || rawJson.isBlank()) {
			return List.of();
		}
		try {
			List<StoredBreakdown> parsed = GSON.fromJson(rawJson, BREAKDOWN_LIST_TYPE);
			if (parsed == null || parsed.isEmpty()) {
				return List.of();
			}
			List<BlockPointBreakdown> result = new ArrayList<>(parsed.size());
			for (StoredBreakdown item : parsed) {
				if (item == null || item.blockId() == null) {
					continue;
				}
				Identifier id = Identifier.tryParse(item.blockId());
				if (id != null && item.rawCount() >= 0 && item.countedForPoints() >= 0 && item.pointsEarned() >= 0) {
					result.add(new BlockPointBreakdown(id, item.rawCount(), item.countedForPoints(), item.pointsEarned()));
				}
			}
			return List.copyOf(result);
		} catch (JsonParseException ex) {
			return List.of();
		}
	}

	private static Instant parseInstantOrEpoch(@Nullable String raw) {
		if (raw == null || raw.isBlank()) {
			return Instant.EPOCH;
		}
		try {
			return Instant.parse(raw);
		} catch (Exception ignored) {
			return Instant.EPOCH;
		}
	}
}
