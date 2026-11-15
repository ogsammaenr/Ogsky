package me.ogsammaenr.ogsky.core.internal.flag;

import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.flag.FlagMode;
import me.ogsammaenr.ogsky.core.api.flag.FlagRegistry;
import me.ogsammaenr.ogsky.core.api.flag.IslandFlagDefinition;
import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import net.minecraft.resources.Identifier;

import static me.ogsammaenr.ogsky.core.api.flag.FlagMode.ADVANCED;
import static me.ogsammaenr.ogsky.core.api.flag.FlagMode.BASIC;
import static me.ogsammaenr.ogsky.core.api.island.IslandRole.ADMIN;
import static me.ogsammaenr.ogsky.core.api.island.IslandRole.COOP;
import static me.ogsammaenr.ogsky.core.api.island.IslandRole.MEMBER;
import static me.ogsammaenr.ogsky.core.api.island.IslandRole.VISITOR;

/**
 * Canonical BentoBox-aligned built-in flag IDs and registrations for {@code ogsky-core}.
 */
public final class BuiltinFlags {
	// PROTECTION flags
	public static final Identifier BREAK_BLOCKS = OgskyCore.id("break_blocks");
	public static final Identifier PLACE_BLOCKS = OgskyCore.id("place_blocks");
	public static final Identifier CONTAINER = OgskyCore.id("container");
	public static final Identifier FURNACE = OgskyCore.id("furnace");
	public static final Identifier HOPPER_USE = OgskyCore.id("hopper_use");
	public static final Identifier BREAK_HOPPERS = OgskyCore.id("break_hoppers");
	public static final Identifier BREAK_SPAWNERS = OgskyCore.id("break_spawners");
	public static final Identifier DOOR = OgskyCore.id("door");
	public static final Identifier TRAPDOOR = OgskyCore.id("trapdoor");
	public static final Identifier BUTTON_LEVER = OgskyCore.id("button_lever");
	public static final Identifier PRESSURE_PLATE = OgskyCore.id("pressure_plate");
	public static final Identifier REDSTONE = OgskyCore.id("redstone");
	public static final Identifier BED = OgskyCore.id("bed");
	public static final Identifier ANVIL = OgskyCore.id("anvil");
	public static final Identifier BEACON = OgskyCore.id("beacon");
	public static final Identifier BREWING = OgskyCore.id("brewing");
	public static final Identifier ENCHANTING = OgskyCore.id("enchanting");
	public static final Identifier ARMOR_STAND = OgskyCore.id("armor_stand");
	public static final Identifier ITEM_FRAME = OgskyCore.id("item_frame");
	public static final Identifier BUCKET = OgskyCore.id("bucket");
	public static final Identifier CROP_TRAMPLE = OgskyCore.id("crop_trample");
	public static final Identifier HARVEST = OgskyCore.id("harvest");
	public static final Identifier BREEDING = OgskyCore.id("breeding");
	public static final Identifier SHEAR_ENTITY = OgskyCore.id("shear_entity");
	public static final Identifier HURT_ANIMALS = OgskyCore.id("hurt_animals");
	public static final Identifier HURT_VILLAGERS = OgskyCore.id("hurt_villagers");
	public static final Identifier HURT_MONSTERS = OgskyCore.id("hurt_monsters");
	public static final Identifier TRADING = OgskyCore.id("trading");
	public static final Identifier MOUNT_ENTITY = OgskyCore.id("mount_entity");
	public static final Identifier ENDER_PEARL = OgskyCore.id("ender_pearl");
	public static final Identifier DROP_ITEM = OgskyCore.id("drop_item");
	public static final Identifier PICKUP_ITEM = OgskyCore.id("pickup_item");
	public static final Identifier PORTAL_USE = OgskyCore.id("portal_use");
	public static final Identifier FLINT_AND_STEEL = OgskyCore.id("flint_and_steel");

	// SETTING flags
	public static final Identifier ISLAND_LOCK = OgskyCore.id("island_lock");
	public static final Identifier PVP_OVERWORLD = OgskyCore.id("pvp_overworld");
	public static final Identifier PVP_NETHER = OgskyCore.id("pvp_nether");
	public static final Identifier PVP_END = OgskyCore.id("pvp_end");
	public static final Identifier ANIMAL_SPAWN = OgskyCore.id("animal_spawn");
	public static final Identifier MONSTER_SPAWN = OgskyCore.id("monster_spawn");
	public static final Identifier FIRE_SPREAD = OgskyCore.id("fire_spread");
	public static final Identifier LEAF_DECAY = OgskyCore.id("leaf_decay");
	public static final Identifier ENTER_EXIT_MESSAGES = OgskyCore.id("enter_exit_messages");
	public static final Identifier OFFLINE_REDSTONE = OgskyCore.id("offline_redstone");

	// WORLD_SETTING flags
	public static final Identifier CREEPER_DAMAGE = OgskyCore.id("creeper_damage");
	public static final Identifier CREEPER_GRIEFING = OgskyCore.id("creeper_griefing");
	public static final Identifier TNT_DAMAGE = OgskyCore.id("tnt_damage");
	public static final Identifier WITHER_DAMAGE = OgskyCore.id("wither_damage");
	public static final Identifier CHEST_DAMAGE = OgskyCore.id("chest_damage");
	public static final Identifier PISTON_OUTSIDE_BOUNDS = OgskyCore.id("piston_outside_bounds");
	public static final Identifier LIQUID_FLOW_OUTSIDE_BOUNDS = OgskyCore.id("liquid_flow_outside_bounds");
	public static final Identifier VOID_TELEPORT = OgskyCore.id("void_teleport");
	public static final Identifier END_DRAGON_RESPAWN = OgskyCore.id("end_dragon_respawn");

	private BuiltinFlags() {
	}

	public static void registerAll(FlagRegistry registry) {
		int order = 10;
		// Protection flags
		regProt(registry, BREAK_BLOCKS, BASIC, "minecraft:iron_pickaxe", order += 10, MEMBER, false);
		regProt(registry, PLACE_BLOCKS, BASIC, "minecraft:oak_planks", order += 10, MEMBER, false);
		regProt(registry, CONTAINER, BASIC, "minecraft:chest", order += 10, MEMBER, false);
		regProt(registry, FURNACE, BASIC, "minecraft:furnace", order += 10, MEMBER, false);
		regProt(registry, HOPPER_USE, ADVANCED, "minecraft:hopper", order += 10, MEMBER, false);
		regProt(registry, BREAK_HOPPERS, ADVANCED, "minecraft:hopper", order += 10, MEMBER, false);
		regProt(registry, BREAK_SPAWNERS, ADVANCED, "minecraft:spawner", order += 10, ADMIN, false);
		regProt(registry, DOOR, BASIC, "minecraft:oak_door", order += 10, COOP, false);
		regProt(registry, TRAPDOOR, BASIC, "minecraft:oak_trapdoor", order += 10, COOP, false);
		regProt(registry, BUTTON_LEVER, BASIC, "minecraft:lever", order += 10, COOP, false);
		regProt(registry, PRESSURE_PLATE, BASIC, "minecraft:stone_pressure_plate", order += 10, VISITOR, false);
		regProt(registry, REDSTONE, ADVANCED, "minecraft:repeater", order += 10, MEMBER, false);
		regProt(registry, BED, BASIC, "minecraft:red_bed", order += 10, MEMBER, false);
		regProt(registry, ANVIL, BASIC, "minecraft:anvil", order += 10, COOP, false);
		regProt(registry, BEACON, ADVANCED, "minecraft:beacon", order += 10, MEMBER, false);
		regProt(registry, BREWING, ADVANCED, "minecraft:brewing_stand", order += 10, MEMBER, false);
		regProt(registry, ENCHANTING, BASIC, "minecraft:enchanting_table", order += 10, VISITOR, false);
		regProt(registry, ARMOR_STAND, ADVANCED, "minecraft:armor_stand", order += 10, MEMBER, false);
		regProt(registry, ITEM_FRAME, ADVANCED, "minecraft:item_frame", order += 10, MEMBER, false);
		regProt(registry, BUCKET, BASIC, "minecraft:water_bucket", order += 10, MEMBER, false);
		regProt(registry, CROP_TRAMPLE, BASIC, "minecraft:farmland", order += 10, MEMBER, false);
		regProt(registry, HARVEST, BASIC, "minecraft:wheat", order += 10, COOP, false);
		regProt(registry, BREEDING, BASIC, "minecraft:golden_carrot", order += 10, MEMBER, false);
		regProt(registry, SHEAR_ENTITY, BASIC, "minecraft:shears", order += 10, COOP, false);
		regProt(registry, HURT_ANIMALS, BASIC, "minecraft:beef", order += 10, MEMBER, false);
		regProt(registry, HURT_VILLAGERS, ADVANCED, "minecraft:emerald", order += 10, ADMIN, false);
		regProt(registry, HURT_MONSTERS, BASIC, "minecraft:iron_sword", order += 10, VISITOR, true);
		regProt(registry, TRADING, BASIC, "minecraft:emerald", order += 10, VISITOR, true);
		regProt(registry, MOUNT_ENTITY, BASIC, "minecraft:saddle", order += 10, COOP, false);
		regProt(registry, ENDER_PEARL, ADVANCED, "minecraft:ender_pearl", order += 10, COOP, false);
		regProt(registry, DROP_ITEM, BASIC, "minecraft:dropper", order += 10, VISITOR, true);
		regProt(registry, PICKUP_ITEM, BASIC, "minecraft:hopper_minecart", order += 10, VISITOR, true);
		regProt(registry, PORTAL_USE, BASIC, "minecraft:obsidian", order += 10, VISITOR, true);
		regProt(registry, FLINT_AND_STEEL, ADVANCED, "minecraft:flint_and_steel", order += 10, MEMBER, false);

		// Setting flags
		regSet(registry, ISLAND_LOCK, BASIC, "minecraft:iron_bars", order += 10, false);
		regSet(registry, PVP_OVERWORLD, BASIC, "minecraft:diamond_sword", order += 10, false);
		regSet(registry, PVP_NETHER, ADVANCED, "minecraft:netherite_sword", order += 10, false);
		regSet(registry, PVP_END, ADVANCED, "minecraft:end_crystal", order += 10, false);
		regSet(registry, ANIMAL_SPAWN, BASIC, "minecraft:cow_spawn_egg", order += 10, true);
		regSet(registry, MONSTER_SPAWN, BASIC, "minecraft:zombie_spawn_egg", order += 10, true);
		regSet(registry, FIRE_SPREAD, ADVANCED, "minecraft:blaze_powder", order += 10, false);
		regSet(registry, LEAF_DECAY, ADVANCED, "minecraft:oak_leaves", order += 10, true);
		regSet(registry, ENTER_EXIT_MESSAGES, BASIC, "minecraft:oak_sign", order += 10, true);
		regSet(registry, OFFLINE_REDSTONE, ADVANCED, "minecraft:redstone", order += 10, true);

		// World setting flags
		regWorld(registry, CREEPER_DAMAGE, BASIC, "minecraft:creeper_head", order += 10, false);
		regWorld(registry, CREEPER_GRIEFING, BASIC, "minecraft:creeper_spawn_egg", order += 10, false);
		regWorld(registry, TNT_DAMAGE, BASIC, "minecraft:tnt", order += 10, false);
		regWorld(registry, WITHER_DAMAGE, ADVANCED, "minecraft:wither_skeleton_skull", order += 10, false);
		regWorld(registry, CHEST_DAMAGE, ADVANCED, "minecraft:trapped_chest", order += 10, false);
		regWorld(registry, PISTON_OUTSIDE_BOUNDS, ADVANCED, "minecraft:piston", order += 10, false);
		regWorld(registry, LIQUID_FLOW_OUTSIDE_BOUNDS, ADVANCED, "minecraft:lava_bucket", order += 10, false);
		regWorld(registry, VOID_TELEPORT, BASIC, "minecraft:feather", order += 10, true);
		regWorld(registry, END_DRAGON_RESPAWN, ADVANCED, "minecraft:dragon_head", order += 10, false);
	}

	private static void regProt(
		FlagRegistry registry,
		Identifier id,
		FlagMode mode,
		String icon,
		int order,
		byte minRank,
		boolean wildernessAllowed
	) {
		registry.register(IslandFlagDefinition.protection(id, mode, icon, order, minRank, wildernessAllowed));
	}

	private static void regSet(
		FlagRegistry registry,
		Identifier id,
		FlagMode mode,
		String icon,
		int order,
		boolean defaultValue
	) {
		registry.register(IslandFlagDefinition.setting(id, mode, icon, order, defaultValue));
	}

	private static void regWorld(
		FlagRegistry registry,
		Identifier id,
		FlagMode mode,
		String icon,
		int order,
		boolean defaultValue
	) {
		registry.register(IslandFlagDefinition.worldSetting(id, mode, icon, order, defaultValue));
	}
}
