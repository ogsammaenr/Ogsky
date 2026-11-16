package me.ogsammaenr.ogsky.limits;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import me.ogsammaenr.ogsky.core.api.OgskyCore;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compiled multi-bucket rule table mapping vanilla {@link Block} and {@link EntityType}
 * instances to zero or more integer rule indices for O(1) hot-path lookups.
 */
public final class LimitRuleTable {
	public enum RuleKind {
		BLOCK,
		ENTITY
	}

	public record LimitRule(
		int index,
		RuleKind kind,
		String key,
		String displayName,
		String iconItemId,
		int maxLimit,
		boolean groupRule,
		List<String> sourceEntries
	) {
	}

	@FunctionalInterface
	public interface TagResolver<T> {
		List<T> resolveTag(Identifier tagId);
	}

	private static final int[] EMPTY_INDICES = new int[0];

	private final List<LimitRule> blockRules;
	private final List<LimitRule> entityRules;
	private final List<LimitRule> allRules;
	private final Map<String, Integer> blockKeyToIndex;
	private final Map<String, Integer> entityKeyToIndex;
	private final Reference2ObjectOpenHashMap<Block, int[]> blockToRuleIndices;
	private final Reference2ObjectOpenHashMap<EntityType<?>, int[]> entityToRuleIndices;
	private final boolean countBaseMultiBlockPartsOnly;
	private final String rulesHash;

	private LimitRuleTable(
		List<LimitRule> blockRules,
		List<LimitRule> entityRules,
		Map<String, Integer> blockKeyToIndex,
		Map<String, Integer> entityKeyToIndex,
		Reference2ObjectOpenHashMap<Block, int[]> blockToRuleIndices,
		Reference2ObjectOpenHashMap<EntityType<?>, int[]> entityToRuleIndices,
		boolean countBaseMultiBlockPartsOnly,
		String rulesHash
	) {
		this.blockRules = List.copyOf(blockRules);
		this.entityRules = List.copyOf(entityRules);
		List<LimitRule> combined = new ArrayList<>(blockRules.size() + entityRules.size());
		combined.addAll(blockRules);
		combined.addAll(entityRules);
		this.allRules = List.copyOf(combined);
		this.blockKeyToIndex = Map.copyOf(blockKeyToIndex);
		this.entityKeyToIndex = Map.copyOf(entityKeyToIndex);
		this.blockToRuleIndices = blockToRuleIndices;
		this.entityToRuleIndices = entityToRuleIndices;
		this.countBaseMultiBlockPartsOnly = countBaseMultiBlockPartsOnly;
		this.rulesHash = rulesHash;
	}

	public static LimitRuleTable compile(LimitConfig config) {
		return compile(config, true);
	}

	public static LimitRuleTable compile(LimitConfig config, boolean resolveTags) {
		if (!resolveTags) {
			return compile(config, tagId -> List.of(), tagId -> List.of());
		}
		return compile(
			config,
			tagId -> {
				TagKey<Block> key = TagKey.create(Registries.BLOCK, tagId);
				List<Block> result = new ArrayList<>();
				for (Holder<Block> holder : BuiltInRegistries.BLOCK.getTagOrEmpty(key)) {
					result.add(holder.value());
				}
				return result;
			},
			tagId -> {
				TagKey<EntityType<?>> key = TagKey.create(Registries.ENTITY_TYPE, tagId);
				List<EntityType<?>> result = new ArrayList<>();
				for (Holder<EntityType<?>> holder : BuiltInRegistries.ENTITY_TYPE.getTagOrEmpty(key)) {
					result.add(holder.value());
				}
				return result;
			}
		);
	}

	public static LimitRuleTable compile(
		LimitConfig config,
		TagResolver<Block> blockTagResolver,
		TagResolver<EntityType<?>> entityTagResolver
	) {
		List<LimitRule> blockRules = new ArrayList<>();
		Map<String, Integer> blockKeyToIndex = new java.util.HashMap<>();
		Reference2ObjectOpenHashMap<Block, IntArrayList> blockBuckets = new Reference2ObjectOpenHashMap<>();
		List<String> signatureParts = new ArrayList<>();

		if (config.blockLimits != null) {
			for (Map.Entry<String, Integer> entry : config.blockLimits.entrySet()) {
				Integer limit = entry.getValue();
				if (limit == null || limit < 0) {
					continue;
				}
				String normalizedKey = normalizeEntryKey(entry.getKey());
				if (normalizedKey == null || blockKeyToIndex.containsKey(normalizedKey)) {
					continue;
				}
				Set<Block> members = resolveBlockMembers(normalizedKey, blockTagResolver);
				String icon = pickBlockIcon(normalizedKey, members);
				int ruleIdx = blockRules.size();
				LimitRule rule = new LimitRule(
					ruleIdx,
					RuleKind.BLOCK,
					normalizedKey,
					formatRuleName(normalizedKey, null),
					icon,
					limit,
					normalizedKey.startsWith("#"),
					List.of(normalizedKey)
				);
				blockRules.add(rule);
				blockKeyToIndex.put(normalizedKey, ruleIdx);
				for (Block block : members) {
					if (block == Blocks.PISTON_HEAD || block == Blocks.MOVING_PISTON) {
						continue;
					}
					blockBuckets.computeIfAbsent(block, ignored -> new IntArrayList(2)).add(ruleIdx);
				}
				signatureParts.add(ruleSignature("B", normalizedKey, members.stream().map(b -> BuiltInRegistries.BLOCK.getKey(b).toString()).toList()));
			}
		}

		if (config.blockGroups != null) {
			for (Map.Entry<String, LimitConfig.GroupLimitConfig> entry : config.blockGroups.entrySet()) {
				LimitConfig.GroupLimitConfig group = entry.getValue();
				if (group == null || group.limit < 0 || group.entries == null || group.entries.isEmpty()) {
					continue;
				}
				String groupId = normalizeGroupId(entry.getKey());
				if (groupId == null) {
					continue;
				}
				String ruleKey = "group:" + groupId;
				if (blockKeyToIndex.containsKey(ruleKey)) {
					continue;
				}
				Set<Block> members = new LinkedHashSet<>();
				List<String> normalizedEntries = new ArrayList<>();
				for (String rawMember : group.entries) {
					String normMember = normalizeEntryKey(rawMember);
					if (normMember != null) {
						normalizedEntries.add(normMember);
						members.addAll(resolveBlockMembers(normMember, blockTagResolver));
					}
				}
				String icon = resolveIconItemId(group.icon, pickBlockIcon(ruleKey, members));
				int ruleIdx = blockRules.size();
				LimitRule rule = new LimitRule(
					ruleIdx,
					RuleKind.BLOCK,
					ruleKey,
					formatRuleName(ruleKey, group.displayName),
					icon,
					group.limit,
					true,
					normalizedEntries
				);
				blockRules.add(rule);
				blockKeyToIndex.put(ruleKey, ruleIdx);
				for (Block block : members) {
					if (block == Blocks.PISTON_HEAD || block == Blocks.MOVING_PISTON) {
						continue;
					}
					blockBuckets.computeIfAbsent(block, ignored -> new IntArrayList(2)).add(ruleIdx);
				}
				signatureParts.add(ruleSignature("B", ruleKey, members.stream().map(b -> BuiltInRegistries.BLOCK.getKey(b).toString()).toList()));
			}
		}

		List<LimitRule> entityRules = new ArrayList<>();
		Map<String, Integer> entityKeyToIndex = new java.util.HashMap<>();
		Reference2ObjectOpenHashMap<EntityType<?>, IntArrayList> entityBuckets = new Reference2ObjectOpenHashMap<>();

		if (config.entityLimits != null) {
			for (Map.Entry<String, Integer> entry : config.entityLimits.entrySet()) {
				Integer limit = entry.getValue();
				if (limit == null || limit < 0) {
					continue;
				}
				String normalizedKey = normalizeEntryKey(entry.getKey());
				if (normalizedKey == null || entityKeyToIndex.containsKey(normalizedKey)) {
					continue;
				}
				Set<EntityType<?>> members = resolveEntityMembers(normalizedKey, entityTagResolver);
				String icon = pickEntityIcon(normalizedKey, members);
				int ruleIdx = entityRules.size();
				LimitRule rule = new LimitRule(
					ruleIdx,
					RuleKind.ENTITY,
					normalizedKey,
					formatRuleName(normalizedKey, null),
					icon,
					limit,
					normalizedKey.startsWith("#"),
					List.of(normalizedKey)
				);
				entityRules.add(rule);
				entityKeyToIndex.put(normalizedKey, ruleIdx);
				for (EntityType<?> type : members) {
					entityBuckets.computeIfAbsent(type, ignored -> new IntArrayList(2)).add(ruleIdx);
				}
				signatureParts.add(ruleSignature("E", normalizedKey, members.stream().map(t -> BuiltInRegistries.ENTITY_TYPE.getKey(t).toString()).toList()));
			}
		}

		if (config.entityGroups != null) {
			for (Map.Entry<String, LimitConfig.GroupLimitConfig> entry : config.entityGroups.entrySet()) {
				LimitConfig.GroupLimitConfig group = entry.getValue();
				if (group == null || group.limit < 0 || group.entries == null || group.entries.isEmpty()) {
					continue;
				}
				String groupId = normalizeGroupId(entry.getKey());
				if (groupId == null) {
					continue;
				}
				String ruleKey = "group:" + groupId;
				if (entityKeyToIndex.containsKey(ruleKey)) {
					continue;
				}
				Set<EntityType<?>> members = new LinkedHashSet<>();
				List<String> normalizedEntries = new ArrayList<>();
				for (String rawMember : group.entries) {
					String normMember = normalizeEntryKey(rawMember);
					if (normMember != null) {
						normalizedEntries.add(normMember);
						members.addAll(resolveEntityMembers(normMember, entityTagResolver));
					}
				}
				String icon = resolveIconItemId(group.icon, pickEntityIcon(ruleKey, members));
				int ruleIdx = entityRules.size();
				LimitRule rule = new LimitRule(
					ruleIdx,
					RuleKind.ENTITY,
					ruleKey,
					formatRuleName(ruleKey, group.displayName),
					icon,
					group.limit,
					true,
					normalizedEntries
				);
				entityRules.add(rule);
				entityKeyToIndex.put(ruleKey, ruleIdx);
				for (EntityType<?> type : members) {
					entityBuckets.computeIfAbsent(type, ignored -> new IntArrayList(2)).add(ruleIdx);
				}
				signatureParts.add(ruleSignature("E", ruleKey, members.stream().map(t -> BuiltInRegistries.ENTITY_TYPE.getKey(t).toString()).toList()));
			}
		}

		Reference2ObjectOpenHashMap<Block, int[]> compiledBlockMap = new Reference2ObjectOpenHashMap<>(blockBuckets.size());
		for (var e : blockBuckets.reference2ObjectEntrySet()) {
			compiledBlockMap.put(e.getKey(), e.getValue().toIntArray());
		}
		Reference2ObjectOpenHashMap<EntityType<?>, int[]> compiledEntityMap = new Reference2ObjectOpenHashMap<>(entityBuckets.size());
		for (var e : entityBuckets.reference2ObjectEntrySet()) {
			compiledEntityMap.put(e.getKey(), e.getValue().toIntArray());
		}

		String hash = computeRulesHash(config.countBaseMultiBlockPartsOnly, signatureParts);
		return new LimitRuleTable(
			blockRules,
			entityRules,
			blockKeyToIndex,
			entityKeyToIndex,
			compiledBlockMap,
			compiledEntityMap,
			config.countBaseMultiBlockPartsOnly,
			hash
		);
	}

	public int blockRuleCount() {
		return blockRules.size();
	}

	public int entityRuleCount() {
		return entityRules.size();
	}

	public List<LimitRule> blockRules() {
		return blockRules;
	}

	public List<LimitRule> entityRules() {
		return entityRules;
	}

	public List<LimitRule> allRules() {
		return allRules;
	}

	public LimitRule blockRule(int index) {
		return blockRules.get(index);
	}

	public LimitRule entityRule(int index) {
		return entityRules.get(index);
	}

	public @Nullable Integer findBlockRuleIndex(String key) {
		return blockKeyToIndex.get(key);
	}

	public @Nullable Integer findEntityRuleIndex(String key) {
		return entityKeyToIndex.get(key);
	}

	public String rulesHash() {
		return rulesHash;
	}

	public int[] ruleIndicesFor(BlockState state) {
		if (state == null || state.isAir()) {
			return EMPTY_INDICES;
		}
		int[] indices = blockToRuleIndices.get(state.getBlock());
		if (indices == null || indices.length == 0) {
			return EMPTY_INDICES;
		}
		return shouldCountBlockState(state, countBaseMultiBlockPartsOnly) ? indices : EMPTY_INDICES;
	}

	public int[] ruleIndicesFor(EntityType<?> entityType) {
		if (entityType == null) {
			return EMPTY_INDICES;
		}
		int[] indices = entityToRuleIndices.get(entityType);
		return indices != null ? indices : EMPTY_INDICES;
	}

	public static boolean shouldCountBlockState(BlockState state, boolean countBaseOnly) {
		if (state == null || state.isAir()) {
			return false;
		}
		Block block = state.getBlock();
		if (block == Blocks.PISTON_HEAD || block == Blocks.MOVING_PISTON) {
			return false;
		}
		if (!countBaseOnly) {
			return true;
		}
		if (block instanceof AbstractBedBlock || state.hasProperty(BlockStateProperties.BED_PART)) {
			return state.getOptionalValue(BlockStateProperties.BED_PART).orElse(BedPart.FOOT) == BedPart.FOOT;
		}
		if (block instanceof DoorBlock
			|| block instanceof DoublePlantBlock
			|| state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
			return state.getOptionalValue(BlockStateProperties.DOUBLE_BLOCK_HALF).orElse(DoubleBlockHalf.LOWER) == DoubleBlockHalf.LOWER;
		}
		return true;
	}

	private static Set<Block> resolveBlockMembers(String entry, TagResolver<Block> tagResolver) {
		Set<Block> result = new LinkedHashSet<>();
		if (entry.startsWith("#")) {
			Identifier tagId = Identifier.tryParse(entry.substring(1));
			if (tagId == null) {
				OgskyCore.LOGGER.warn("Ignoring invalid block tag limit entry '{}'.", entry);
				return result;
			}
			for (Block block : tagResolver.resolveTag(tagId)) {
				if (block != null && block != Blocks.AIR) {
					result.add(block);
				}
			}
			return result;
		}
		Identifier blockId = Identifier.tryParse(entry);
		if (blockId == null) {
			OgskyCore.LOGGER.warn("Ignoring invalid block limit entry '{}'.", entry);
			return result;
		}
		Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(blockId);
		if (block.isEmpty() || block.get() == Blocks.AIR) {
			OgskyCore.LOGGER.warn("Unknown block ID '{}' in limits config.", entry);
			return result;
		}
		result.add(block.get());
		return result;
	}

	private static Set<EntityType<?>> resolveEntityMembers(String entry, TagResolver<EntityType<?>> tagResolver) {
		Set<EntityType<?>> result = new LinkedHashSet<>();
		if (entry.startsWith("#")) {
			Identifier tagId = Identifier.tryParse(entry.substring(1));
			if (tagId == null) {
				OgskyCore.LOGGER.warn("Ignoring invalid entity tag limit entry '{}'.", entry);
				return result;
			}
			for (EntityType<?> type : tagResolver.resolveTag(tagId)) {
				if (type != null) {
					result.add(type);
				}
			}
			return result;
		}
		Identifier entityId = Identifier.tryParse(entry);
		if (entityId == null) {
			OgskyCore.LOGGER.warn("Ignoring invalid entity limit entry '{}'.", entry);
			return result;
		}
		Optional<EntityType<?>> entity = BuiltInRegistries.ENTITY_TYPE.getOptional(entityId);
		if (entity.isEmpty()) {
			OgskyCore.LOGGER.warn("Unknown entity ID '{}' in limits config.", entry);
			return result;
		}
		result.add(entity.get());
		return result;
	}

	private static @Nullable String normalizeEntryKey(@Nullable String raw) {
		if (raw == null) {
			return null;
		}
		String trimmed = raw.trim().toLowerCase(Locale.ROOT);
		return trimmed.isEmpty() ? null : trimmed;
	}

	private static @Nullable String normalizeGroupId(@Nullable String raw) {
		if (raw == null) {
			return null;
		}
		String trimmed = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
		return trimmed.isEmpty() ? null : trimmed;
	}

	private static String formatRuleName(String key, @Nullable String explicit) {
		if (explicit != null && !explicit.isBlank()) {
			return explicit.trim();
		}
		if (key.startsWith("#")) {
			return key;
		}
		String raw = key.startsWith("group:") ? key.substring("group:".length()) : key;
		int colon = raw.indexOf(':');
		if (colon >= 0 && colon + 1 < raw.length()) {
			raw = raw.substring(colon + 1);
		}
		String[] tokens = raw.split("_");
		StringBuilder sb = new StringBuilder();
		for (String token : tokens) {
			if (token.isEmpty()) {
				continue;
			}
			if (!sb.isEmpty()) {
				sb.append(' ');
			}
			sb.append(Character.toUpperCase(token.charAt(0)));
			if (token.length() > 1) {
				sb.append(token.substring(1));
			}
		}
		return sb.isEmpty() ? key : sb.toString();
	}

	private static String pickBlockIcon(String key, Set<Block> members) {
		if (!key.startsWith("#") && !key.startsWith("group:") && !members.isEmpty()) {
			Identifier itemKey = BuiltInRegistries.ITEM.getKey(members.iterator().next().asItem());
			if (!"minecraft:air".equals(itemKey.toString())) {
				return itemKey.toString();
			}
		}
		for (Block block : members) {
			Identifier itemKey = BuiltInRegistries.ITEM.getKey(block.asItem());
			if (!"minecraft:air".equals(itemKey.toString())) {
				return itemKey.toString();
			}
		}
		return "minecraft:barrier";
	}

	private static String pickEntityIcon(String key, Set<EntityType<?>> members) {
		String raw = key.startsWith("#") || key.startsWith("group:")
			? (!members.isEmpty() ? BuiltInRegistries.ENTITY_TYPE.getKey(members.iterator().next()).toString() : "minecraft:egg")
			: key;
		return switch (raw) {
			case "minecraft:armor_stand" -> "minecraft:armor_stand";
			case "minecraft:item_frame" -> "minecraft:item_frame";
			case "minecraft:glow_item_frame" -> "minecraft:glow_item_frame";
			case "minecraft:painting" -> "minecraft:painting";
			case "minecraft:end_crystal" -> "minecraft:end_crystal";
			case "minecraft:minecart" -> "minecraft:minecart";
			case "minecraft:chest_minecart" -> "minecraft:chest_minecart";
			case "minecraft:hopper_minecart" -> "minecraft:hopper_minecart";
			case "minecraft:furnace_minecart" -> "minecraft:furnace_minecart";
			case "minecraft:tnt_minecart" -> "minecraft:tnt_minecart";
			case "minecraft:oak_boat" -> "minecraft:oak_boat";
			case "minecraft:iron_golem" -> "minecraft:iron_block";
			case "minecraft:snow_golem" -> "minecraft:carved_pumpkin";
			case "minecraft:copper_golem" -> "minecraft:copper_block";
			case "minecraft:villager" -> "minecraft:emerald";
			default -> {
				if (!members.isEmpty()) {
					EntityType<?> first = members.iterator().next();
					Identifier entityId = BuiltInRegistries.ENTITY_TYPE.getKey(first);
					Identifier eggId = Identifier.fromNamespaceAndPath(entityId.getNamespace(), entityId.getPath() + "_spawn_egg");
					if (BuiltInRegistries.ITEM.getOptional(eggId).isPresent()) {
						yield eggId.toString();
					}
				}
				yield "minecraft:egg";
			}
		};
	}

	private static String resolveIconItemId(@Nullable String explicit, String fallback) {
		if (explicit != null && !explicit.isBlank()) {
			Identifier parsed = Identifier.tryParse(explicit.trim().toLowerCase(Locale.ROOT));
			if (parsed != null && BuiltInRegistries.ITEM.getOptional(parsed).isPresent()) {
				return parsed.toString();
			}
		}
		return fallback;
	}

	private static String ruleSignature(String prefix, String key, List<String> resolvedMemberIds) {
		TreeSet<String> sortedMembers = new TreeSet<>(resolvedMemberIds);
		return prefix + ":" + key + "=" + String.join(",", sortedMembers);
	}

	private static String computeRulesHash(boolean countBaseOnly, List<String> parts) {
		List<String> sorted = new ArrayList<>(parts);
		sorted.sort(String::compareTo);
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update((countBaseOnly ? "base=1;" : "base=0;").getBytes(StandardCharsets.UTF_8));
			for (String part : sorted) {
				digest.update(part.getBytes(StandardCharsets.UTF_8));
				digest.update((byte) '\n');
			}
			return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 missing", e);
		}
	}
}
