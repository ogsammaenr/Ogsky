package me.ogsammaenr.ogsky.limits;

import net.minecraft.SharedConstants;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LimitRuleTableTest {
	@BeforeAll
	static void bootstrapMinecraftRegistries() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void mapsBlocksAndEntitiesIntoMultipleMatchingRulesSimultaneously() {
		LimitConfig config = LimitConfig.defaults();
		config.blockLimits.put("minecraft:red_shulker_box", 4);

		LimitRuleTable table = LimitRuleTable.compile(
			config,
			tagId -> {
				if (tagId.equals(Identifier.parse("minecraft:shulker_boxes"))) {
					return List.of(Blocks.DYED_SHULKER_BOX.red(), Blocks.DYED_SHULKER_BOX.blue(), Blocks.SHULKER_BOX);
				}
				if (tagId.equals(Identifier.parse("minecraft:beds"))) {
					return List.of(Blocks.BED.red(), Blocks.BED.white());
				}
				return List.of();
			},
			tagId -> {
				if (tagId.equals(Identifier.parse("minecraft:boat"))) {
					return List.of(EntityTypes.OAK_BOAT, EntityTypes.BIRCH_BOAT);
				}
				return List.of();
			}
		);

		int[] redShulkerIndices = table.ruleIndicesFor(Blocks.DYED_SHULKER_BOX.red().defaultBlockState());
		assertEquals(2, redShulkerIndices.length);
		int[] blueShulkerIndices = table.ruleIndicesFor(Blocks.DYED_SHULKER_BOX.blue().defaultBlockState());
		assertEquals(1, blueShulkerIndices.length);
		assertEquals("#minecraft:shulker_boxes", table.blockRule(blueShulkerIndices[0]).key());

		int[] pistonIndices = table.ruleIndicesFor(Blocks.PISTON.defaultBlockState());
		assertEquals(2, pistonIndices.length);
		assertEquals(0, table.ruleIndicesFor(Blocks.PISTON_HEAD.defaultBlockState()).length);
		assertEquals(0, table.ruleIndicesFor(Blocks.MOVING_PISTON.defaultBlockState()).length);

		int[] cowIndices = table.ruleIndicesFor(EntityTypes.COW);
		assertEquals(1, cowIndices.length);
		assertEquals("group:farm_animals", table.entityRule(cowIndices[0]).key());

		int[] boatIndices = table.ruleIndicesFor(EntityTypes.OAK_BOAT);
		assertEquals(1, boatIndices.length);
		assertEquals("#minecraft:boat", table.entityRule(boatIndices[0]).key());
	}

	@Test
	void filtersNonBasePartsOfBedsDoorsAndTallPlantsWhenConfigured() {
		LimitConfig config = LimitConfig.defaults();
		config.blockLimits.clear();
		config.blockGroups.clear();
		config.blockLimits.put("#minecraft:beds", 10);
		config.blockLimits.put("minecraft:oak_door", 10);
		config.blockLimits.put("minecraft:sunflower", 10);

		LimitRuleTable.TagResolver<net.minecraft.world.level.block.Block> resolver = tagId ->
			tagId.equals(Identifier.parse("minecraft:beds")) ? List.of(Blocks.BED.red()) : List.of();

		LimitRuleTable baseOnly = LimitRuleTable.compile(config, resolver, tag -> List.of());
		assertEquals(1, baseOnly.ruleIndicesFor(Blocks.BED.red().defaultBlockState().setValue(BedBlock.PART, BedPart.FOOT)).length);
		assertEquals(0, baseOnly.ruleIndicesFor(Blocks.BED.red().defaultBlockState().setValue(BedBlock.PART, BedPart.HEAD)).length);

		assertEquals(1, baseOnly.ruleIndicesFor(Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)).length);
		assertEquals(0, baseOnly.ruleIndicesFor(Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER)).length);

		assertEquals(1, baseOnly.ruleIndicesFor(Blocks.SUNFLOWER.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER)).length);
		assertEquals(0, baseOnly.ruleIndicesFor(Blocks.SUNFLOWER.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER)).length);

		config.countBaseMultiBlockPartsOnly = false;
		LimitRuleTable allParts = LimitRuleTable.compile(config, resolver, tag -> List.of());
		assertEquals(1, allParts.ruleIndicesFor(Blocks.BED.red().defaultBlockState().setValue(BedBlock.PART, BedPart.HEAD)).length);
		assertEquals(1, allParts.ruleIndicesFor(Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER)).length);
		assertNotEquals(baseOnly.rulesHash(), allParts.rulesHash());
	}

	@Test
	void rulesHashTracksMembershipChangesButIgnoresNumericLimitTweaks() {
		LimitConfig base = LimitConfig.defaults();
		LimitRuleTable t1 = LimitRuleTable.compile(base, tag -> List.of(), tag -> List.of());

		LimitConfig numericOnly = LimitConfig.defaults();
		numericOnly.blockLimits.put("minecraft:hopper", 256);
		LimitRuleTable t2 = LimitRuleTable.compile(numericOnly, tag -> List.of(), tag -> List.of());
		assertEquals(t1.rulesHash(), t2.rulesHash());

		LimitConfig addedRule = LimitConfig.defaults();
		addedRule.blockLimits.put("minecraft:anvil", 12);
		LimitRuleTable t3 = LimitRuleTable.compile(addedRule, tag -> List.of(), tag -> List.of());
		assertNotEquals(t1.rulesHash(), t3.rulesHash());
	}
}
