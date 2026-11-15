package me.ogsammaenr.ogsky.level;

import me.ogsammaenr.ogsky.core.api.level.BlockPointBreakdown;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compiled block-value & scoring-cap evaluator built from {@link LevelConfig}.
 * Precedence for both values and caps is: exact block ID &gt; block tag ({@code #...}) &gt; default.
 */
public final class LevelValuationTable {
	public static final int UNLIMITED = -1;

	@FunctionalInterface
	public interface TagResolver {
		Collection<Identifier> resolveBlockIds(Identifier tagId);
	}

	public record BlockValueEntry(Identifier blockId, long points, int maxCountForPoints) {
		public boolean hasScoringCap() {
			return maxCountForPoints != UNLIMITED;
		}
	}

	public record EvaluationResult(
		long levelValue,
		long blockPoints,
		long pointsPerLevel,
		long pointsToNextLevel,
		List<BlockPointBreakdown> topBlocks
	) {
	}

	private record BlockScoreRule(long points, int maxCountForPoints) {
		private int applyCap(int rawCount) {
			if (rawCount <= 0) {
				return 0;
			}
			return maxCountForPoints == UNLIMITED ? rawCount : Math.min(rawCount, maxCountForPoints);
		}
	}

	private final long pointsPerLevel;
	private final long defaultBlockValue;
	private final int topBlocksLimit;
	private final Map<Identifier, BlockScoreRule> rulesByBlockId;
	private final List<BlockValueEntry> valuedBlocks;
	private final IdentityHashMap<Block, BlockScoreRule> rulesByBlock;
	private final IdentityHashMap<Block, Identifier> idsByBlock;

	private LevelValuationTable(
		long pointsPerLevel,
		long defaultBlockValue,
		int topBlocksLimit,
		Map<Identifier, BlockScoreRule> rulesByBlockId,
		boolean compileRuntimeBlocks
	) {
		this.pointsPerLevel = pointsPerLevel;
		this.defaultBlockValue = defaultBlockValue;
		this.topBlocksLimit = topBlocksLimit;
		this.rulesByBlockId = Map.copyOf(rulesByBlockId);
		this.valuedBlocks = this.rulesByBlockId.entrySet().stream()
			.filter(entry -> entry.getValue().points() > 0L)
			.map(entry -> new BlockValueEntry(
				entry.getKey(),
				entry.getValue().points(),
				entry.getValue().maxCountForPoints()
			))
			.sorted(
				Comparator.comparingLong(BlockValueEntry::points).reversed()
					.thenComparing(entry -> entry.blockId().toString())
			)
			.toList();
		this.rulesByBlock = new IdentityHashMap<>();
		this.idsByBlock = new IdentityHashMap<>();
		if (compileRuntimeBlocks) {
			compileLiveBlockLookup();
		}
	}

	public static LevelValuationTable compile(LevelConfig config, boolean resolveTagsFromRegistry) {
		return compile(
			config,
			resolveTagsFromRegistry ? LevelValuationTable::resolveTagFromRegistry : tagId -> List.of(),
			true
		);
	}

	public static LevelValuationTable compile(LevelConfig config) {
		return compile(config, true);
	}

	public static LevelValuationTable compileForUnitTest(LevelConfig config, TagResolver tagResolver) {
		return compile(config, tagResolver, false);
	}

	private static LevelValuationTable compile(
		LevelConfig config,
		TagResolver tagResolver,
		boolean compileRuntimeBlocks
	) {
		Objects.requireNonNull(config, "config");
		Objects.requireNonNull(tagResolver, "tagResolver");
		config.ensureDefaultsAndValidate();

		Map<Identifier, Long> exactValues = new HashMap<>();
		Map<Identifier, Long> tagDerivedValues = new HashMap<>();
		for (Map.Entry<String, Long> entry : config.blockValues.entrySet()) {
			String key = entry.getKey();
			long value = entry.getValue();
			if (key.startsWith("#")) {
				Identifier tagId = Identifier.tryParse(key.substring(1));
				if (tagId != null) {
					for (Identifier blockId : tagResolver.resolveBlockIds(tagId)) {
						// Deterministically take highest configured tag value when tags overlap.
						tagDerivedValues.merge(blockId, value, Math::max);
					}
				}
			} else {
				Identifier blockId = Identifier.tryParse(key);
				if (blockId != null) {
					exactValues.put(blockId, value);
				}
			}
		}

		Map<Identifier, Integer> exactLimits = new HashMap<>();
		Map<Identifier, Integer> tagDerivedLimits = new HashMap<>();
		for (Map.Entry<String, Integer> entry : config.blockPointLimits.entrySet()) {
			String key = entry.getKey();
			int limit = entry.getValue();
			if (key.startsWith("#")) {
				Identifier tagId = Identifier.tryParse(key.substring(1));
				if (tagId != null) {
					for (Identifier blockId : tagResolver.resolveBlockIds(tagId)) {
						// Deterministically apply tightest cap when multiple tags match.
						tagDerivedLimits.merge(blockId, limit, Math::min);
					}
				}
			} else {
				Identifier blockId = Identifier.tryParse(key);
				if (blockId != null) {
					exactLimits.put(blockId, limit);
				}
			}
		}

		Map<Identifier, Long> mergedValues = new HashMap<>(tagDerivedValues);
		mergedValues.putAll(exactValues);

		Map<Identifier, Integer> mergedLimits = new HashMap<>(tagDerivedLimits);
		mergedLimits.putAll(exactLimits);

		Map<Identifier, BlockScoreRule> compiled = new HashMap<>();
		Map<Identifier, Boolean> allKeys = new HashMap<>();
		mergedValues.keySet().forEach(id -> allKeys.put(id, Boolean.TRUE));
		mergedLimits.keySet().forEach(id -> allKeys.put(id, Boolean.TRUE));

		for (Identifier blockId : allKeys.keySet()) {
			long points = mergedValues.getOrDefault(blockId, config.formula.defaultBlockValue);
			int limit = mergedLimits.getOrDefault(blockId, UNLIMITED);
			compiled.put(blockId, new BlockScoreRule(points, limit));
		}

		return new LevelValuationTable(
			config.formula.pointsPerLevel,
			config.formula.defaultBlockValue,
			config.top.detailTopBlocksLimit,
			compiled,
			compileRuntimeBlocks
		);
	}

	private void compileLiveBlockLookup() {
		for (Map.Entry<Identifier, BlockScoreRule> entry : rulesByBlockId.entrySet()) {
			BuiltInRegistries.BLOCK.get(entry.getKey()).ifPresent(ref -> {
				Block block = ref.value();
				rulesByBlock.put(block, entry.getValue());
				idsByBlock.put(block, entry.getKey());
			});
		}
	}

	private static Collection<Identifier> resolveTagFromRegistry(Identifier tagId) {
		TagKey<Block> tagKey = TagKey.create(Registries.BLOCK, tagId);
		List<Identifier> ids = new ArrayList<>();
		for (Holder<Block> holder : BuiltInRegistries.BLOCK.getTagOrEmpty(tagKey)) {
			ids.add(BuiltInRegistries.BLOCK.getKey(holder.value()));
		}
		return ids;
	}

	public long pointsPerLevel() {
		return pointsPerLevel;
	}

	public long defaultBlockValue() {
		return defaultBlockValue;
	}

	/**
	 * Returns all compiled positive-point block rules sorted by points descending, then ID ascending.
	 */
	public List<BlockValueEntry> valuedBlocks() {
		return valuedBlocks;
	}

	/**
	 * Evaluates live server scan counts without allocating per-block identifiers for unvalued blocks
	 * when {@link #defaultBlockValue} is {@code 0}.
	 */
	public EvaluationResult evaluateBlocks(IdentityHashMap<Block, int[]> countedBlocks) {
		Objects.requireNonNull(countedBlocks, "countedBlocks");
		long totalPoints = 0L;
		List<BlockPointBreakdown> contributions = new ArrayList<>();

		for (Map.Entry<Block, int[]> entry : countedBlocks.entrySet()) {
			int rawCount = entry.getValue()[0];
			if (rawCount <= 0) {
				continue;
			}
			Block block = entry.getKey();
			BlockScoreRule rule = rulesByBlock.get(block);
			BlockPointBreakdown contribution = scoreRule(
				() -> idsByBlock.computeIfAbsent(block, BuiltInRegistries.BLOCK::getKey),
				rule,
				rawCount
			);
			if (contribution != null) {
				totalPoints += contribution.pointsEarned();
				contributions.add(contribution);
			}
		}

		return finalizeResult(totalPoints, contributions);
	}

	/**
	 * Evaluates block ID counts (pure unit-test friendly).
	 */
	public EvaluationResult evaluateBlockIds(Map<Identifier, Integer> countedIds) {
		Objects.requireNonNull(countedIds, "countedIds");
		long totalPoints = 0L;
		List<BlockPointBreakdown> contributions = new ArrayList<>();

		for (Map.Entry<Identifier, Integer> entry : countedIds.entrySet()) {
			int rawCount = entry.getValue() == null ? 0 : entry.getValue();
			if (rawCount <= 0) {
				continue;
			}
			Identifier blockId = entry.getKey();
			BlockScoreRule rule = rulesByBlockId.get(blockId);
			BlockPointBreakdown contribution = scoreRule(() -> blockId, rule, rawCount);
			if (contribution != null) {
				totalPoints += contribution.pointsEarned();
				contributions.add(contribution);
			}
		}

		return finalizeResult(totalPoints, contributions);
	}

	private BlockPointBreakdown scoreRule(
		java.util.function.Supplier<Identifier> blockIdSupplier,
		@org.jspecify.annotations.Nullable BlockScoreRule rule,
		int rawCount
	) {
		long unitPoints = rule != null ? rule.points() : defaultBlockValue;
		int effectiveCount = rule != null ? rule.applyCap(rawCount) : rawCount;
		if (unitPoints <= 0L || effectiveCount <= 0) {
			return null;
		}
		long pointsEarned = (long) effectiveCount * unitPoints;
		return new BlockPointBreakdown(blockIdSupplier.get(), rawCount, effectiveCount, pointsEarned);
	}

	private EvaluationResult finalizeResult(long totalPoints, List<BlockPointBreakdown> contributions) {
		contributions.sort(
			Comparator.comparingLong(BlockPointBreakdown::pointsEarned).reversed()
				.thenComparing((a, b) -> Integer.compare(b.countedForPoints(), a.countedForPoints()))
				.thenComparing(entry -> entry.blockId().toString())
		);
		List<BlockPointBreakdown> top = contributions.size() > topBlocksLimit
			? List.copyOf(contributions.subList(0, topBlocksLimit))
			: List.copyOf(contributions);

		long levelValue = totalPoints / pointsPerLevel;
		long remainder = totalPoints % pointsPerLevel;
		long pointsToNext = pointsPerLevel - remainder;
		return new EvaluationResult(levelValue, totalPoints, pointsPerLevel, pointsToNext, top);
	}
}
