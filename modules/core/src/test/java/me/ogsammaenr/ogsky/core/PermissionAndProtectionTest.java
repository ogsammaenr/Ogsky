package me.ogsammaenr.ogsky.core;

import me.ogsammaenr.ogsky.core.api.flag.FlagType;
import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import me.ogsammaenr.ogsky.core.internal.OgskyCoreApiImpl;
import me.ogsammaenr.ogsky.core.internal.config.CoreConfig;
import me.ogsammaenr.ogsky.core.internal.flag.BuiltinFlags;
import me.ogsammaenr.ogsky.core.internal.island.Island;
import me.ogsammaenr.ogsky.core.internal.permission.LuckPermsPermissionBridge;
import me.ogsammaenr.ogsky.core.internal.protection.ProtectionResolver;
import me.ogsammaenr.ogsky.core.internal.world.OgskyDimensions;
import net.minecraft.server.permissions.PermissionLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionAndProtectionTest {
	@Test
	void luckPermsOverridesAndFallbackOpLevelsWorkCorrectly() {
		CoreConfig config = CoreConfig.defaults();
		config.admin.fallbackOpLevel = 2;

		UUID adminByPerm = UUID.randomUUID();
		UUID normalPlayer = UUID.randomUUID();
		UUID blockedOp = UUID.randomUUID();

		Map<String, Boolean> perms = new HashMap<>();
		perms.put(adminByPerm + ":ogsky.admin", Boolean.TRUE);
		perms.put(adminByPerm + ":ogsky.admin.bypass.break_blocks", Boolean.TRUE);
		perms.put(normalPlayer + ":ogsky.blueprint.vip", Boolean.FALSE);
		perms.put(blockedOp + ":ogsky.admin", Boolean.FALSE);

		LuckPermsPermissionBridge bridge = new LuckPermsPermissionBridge(
			config,
			(uuid, node) -> perms.get(uuid + ":" + node)
		);

		assertTrue(bridge.hasPermission(adminByPerm, PermissionLevel.ALL, LuckPermsPermissionBridge.ADMIN_NODE, config.admin.fallbackOpLevel));
		assertFalse(bridge.hasPermission(normalPlayer, PermissionLevel.ALL, LuckPermsPermissionBridge.ADMIN_NODE, config.admin.fallbackOpLevel));
		assertTrue(bridge.hasPermission(normalPlayer, PermissionLevel.GAMEMASTERS, LuckPermsPermissionBridge.ADMIN_NODE, config.admin.fallbackOpLevel));
		assertFalse(bridge.hasPermission(blockedOp, PermissionLevel.OWNERS, LuckPermsPermissionBridge.ADMIN_NODE, config.admin.fallbackOpLevel));

		assertTrue(bridge.hasPermission(adminByPerm, PermissionLevel.ALL, "ogsky.blueprint.vip", 0));
		assertFalse(bridge.hasPermission(normalPlayer, PermissionLevel.ALL, "ogsky.blueprint.vip", 0));

		assertTrue(bridge.hasBypass(adminByPerm, BuiltinFlags.BREAK_BLOCKS));
		assertFalse(bridge.hasBypass(adminByPerm, BuiltinFlags.CONTAINER));

		assertFalse(bridge.hasBypass(normalPlayer, BuiltinFlags.BREAK_BLOCKS));
		assertTrue(bridge.toggleBypass(normalPlayer));
		assertTrue(bridge.hasBypass(normalPlayer, BuiltinFlags.BREAK_BLOCKS));
		assertTrue(bridge.hasBypass(normalPlayer, BuiltinFlags.CONTAINER));
		assertFalse(bridge.toggleBypass(normalPlayer));
		assertFalse(bridge.hasBypass(normalPlayer, BuiltinFlags.BREAK_BLOCKS));
	}

	@Test
	void islandProtectionRanksBansAndWildernessRulesEnforcedAcrossDimensions() {
		CoreConfig config = CoreConfig.defaults();
		config.flags.wildernessAllow.put(BuiltinFlags.PICKUP_ITEM.toString(), Boolean.TRUE);
		OgskyCoreApiImpl api = new OgskyCoreApiImpl(config);
		api.freezeRegistries();

		UUID ownerId = UUID.randomUUID();
		UUID coopId = UUID.randomUUID();
		UUID trustedId = UUID.randomUUID();
		UUID visitorId = UUID.randomUUID();
		UUID bannedId = UUID.randomUUID();

		Island island = new Island(
			UUID.randomUUID(),
			ownerId,
			0,
			0,
			512,
			512,
			(short) 50,
			"default",
			Instant.now(),
			api.flags(),
			config
		);
		api.coreIslands().grid().register(island);
		api.coreIslands().grid().assignMemberRole(island, coopId, IslandRole.COOP);
		api.coreIslands().grid().assignMemberRole(island, trustedId, IslandRole.TRUSTED);
		island.addBan(bannedId);

		// Member-ranked protection denies visitor/coop/trusted while owner/bypass passes
		assertFalse(api.islands().isProtectionAllowed(OgskyDimensions.OVERWORLD, 512, 512, visitorId, BuiltinFlags.BREAK_BLOCKS, false));
		assertFalse(api.islands().isProtectionAllowed(OgskyDimensions.OVERWORLD, 512, 512, coopId, BuiltinFlags.BREAK_BLOCKS, false));
		assertFalse(api.islands().isProtectionAllowed(OgskyDimensions.OVERWORLD, 512, 512, trustedId, BuiltinFlags.BREAK_BLOCKS, false));
		assertTrue(api.islands().isProtectionAllowed(OgskyDimensions.OVERWORLD, 512, 512, ownerId, BuiltinFlags.BREAK_BLOCKS, false));
		assertTrue(api.islands().isProtectionAllowed(OgskyDimensions.OVERWORLD, 512, 512, visitorId, BuiltinFlags.BREAK_BLOCKS, true));

		// COOP-ranked flag (harvest/door/button) allows COOP & TRUSTED while denying VISITOR
		assertFalse(api.islands().isProtectionAllowed(OgskyDimensions.NETHER, 512, 512, visitorId, BuiltinFlags.HARVEST, false));
		assertTrue(api.islands().isProtectionAllowed(OgskyDimensions.NETHER, 512, 512, coopId, BuiltinFlags.HARVEST, false));
		assertTrue(api.islands().isProtectionAllowed(OgskyDimensions.END, 512, 512, trustedId, BuiltinFlags.HARVEST, false));

		// Banned player denied even for VISITOR (rank 0) flags like PICKUP_ITEM unless adminBypass is true
		assertTrue(api.islands().isProtectionAllowed(OgskyDimensions.OVERWORLD, 512, 512, visitorId, BuiltinFlags.PICKUP_ITEM, false));
		assertFalse(api.islands().isProtectionAllowed(OgskyDimensions.OVERWORLD, 512, 512, bannedId, BuiltinFlags.PICKUP_ITEM, false));
		assertTrue(api.islands().isProtectionAllowed(OgskyDimensions.OVERWORLD, 512, 512, bannedId, BuiltinFlags.PICKUP_ITEM, true));

		// Outside island perimeter (wilderness buffer at x = 600): default denies BREAK_BLOCKS, custom config allows PICKUP_ITEM
		assertFalse(api.islands().isProtectionAllowed(OgskyDimensions.OVERWORLD, 600, 600, visitorId, BuiltinFlags.BREAK_BLOCKS, false));
		assertTrue(api.islands().isProtectionAllowed(OgskyDimensions.OVERWORLD, 600, 600, visitorId, BuiltinFlags.PICKUP_ITEM, false));

		assertEquals(BuiltinFlags.PVP_OVERWORLD, ProtectionResolver.pvpFlagFor(OgskyDimensions.OVERWORLD));
		assertEquals(BuiltinFlags.PVP_NETHER, ProtectionResolver.pvpFlagFor(OgskyDimensions.NETHER));
		assertEquals(BuiltinFlags.PVP_END, ProtectionResolver.pvpFlagFor(OgskyDimensions.END));
		assertEquals(9, api.flags().count(FlagType.WORLD_SETTING));
	}
}
