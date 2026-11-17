package me.ogsammaenr.ogsky.gametest;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.netty.channel.embedded.EmbeddedChannel;
import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.action.IslandActionContext;
import me.ogsammaenr.ogsky.core.api.action.IslandActionDescriptor;
import me.ogsammaenr.ogsky.core.api.db.ModuleDatabase;
import me.ogsammaenr.ogsky.core.internal.OgskyCoreApiImpl;
import me.ogsammaenr.ogsky.core.internal.island.CoreIslandService;
import me.ogsammaenr.ogsky.core.internal.island.Island;
import me.ogsammaenr.ogsky.core.internal.world.OgskyDimensions;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Multi-warp lifecycle, particle marker, warmup/damage/move cancellation, strict CANCEL safety,
 * {@code manage_warps}/{@code use_warp} flags, GUI showcase/sorting/edit, and chat fallback GameTests.
 */
public final class WarpsGameTests {
	@GameTest(maxTicks = 4000)
	public void warpsSafetyWarmupParticlesFlagsGuiFallbackAndLifecycleWork(GameTestHelper helper) throws Exception {
		if (!FabricLoader.getInstance().isModLoaded("ogsky-warps")) {
			helper.succeed();
			return;
		}

		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel ogOverworld = server.getLevel(OgskyDimensions.OVERWORLD);
		ServerLevel ogNether = server.getLevel(OgskyDimensions.NETHER);
		helper.assertTrue(ogOverworld != null && ogNether != null, "Expected ogsky overworld and nether levels");

		OgskyCoreApiImpl coreApi = (OgskyCoreApiImpl) OgskyCore.api();
		CoreIslandService islands = coreApi.coreIslands();

		// Core /is visit must be completely removed.
		boolean visitRemoved = false;
		try {
			server.getCommands().getDispatcher().execute("is visit nobody", server.createCommandSourceStack());
		} catch (CommandSyntaxException expected) {
			visitRemoved = true;
		}
		helper.assertTrue(visitRemoved, "Expected /is visit command to be removed from ogsky-core");

		// Both warp protection flags must be registered in FlagRegistry.
		Identifier manageWarpsFlag = Identifier.fromNamespaceAndPath("ogsky_warps", "manage_warps");
		Identifier useWarpFlag = Identifier.fromNamespaceAndPath("ogsky_warps", "use_warp");
		helper.assertTrue(
			coreApi.flags().find(manageWarpsFlag).isPresent() && coreApi.flags().find(useWarpFlag).isPresent(),
			"Expected manage_warps and use_warp flags registered"
		);

		Path warpsConfigPath = FabricLoader.getInstance().getConfigDir().resolve("ogsky").resolve("warps.json");
		String originalWarpsJson = Files.exists(warpsConfigPath) ? Files.readString(warpsConfigPath) : null;

		Path blueprintsRoot = coreApi.blueprints().registry().blueprintsDir();
		Path bundleDir = blueprintsRoot.resolve("warps_test");

		UUID ownerId = UUID.randomUUID();
		UUID memberId = UUID.randomUUID();
		UUID visitorId = UUID.randomUUID();
		AtomicReference<EmbeddedChannel> ownerChannel = new AtomicReference<>();
		AtomicReference<EmbeddedChannel> memberChannel = new AtomicReference<>();
		AtomicReference<EmbeddedChannel> visitorChannel = new AtomicReference<>();
		ServerPlayer owner = GameTestSupport.createOnlineMockPlayer(server, ogOverworld, ownerId, "WarpOwner", GameType.SURVIVAL, ownerChannel);
		ServerPlayer member = GameTestSupport.createOnlineMockPlayer(server, ogOverworld, memberId, "WarpMember", GameType.SURVIVAL, memberChannel);
		ServerPlayer visitor = GameTestSupport.createOnlineMockPlayer(server, ogOverworld, visitorId, "WarpVisitor", GameType.SURVIVAL, visitorChannel);

		AtomicReference<UUID> islandIdRef = new AtomicReference<>();
		AtomicReference<BlockPos> visitorPadOrigin = new AtomicReference<>();
		AtomicReference<BlockPos> netherPadOrigin = new AtomicReference<>();
		AtomicReference<BlockPos> outerWarpBlockPos = new AtomicReference<>();

		Runnable cleanup = () -> {
			GameTestSupport.closeContainersQuietly(owner, member, visitor);
			try {
				if (coreApi.permissions().isBypassActive(visitor)) {
					coreApi.permissions().toggleBypass(visitor);
				}
				if (originalWarpsJson != null) {
					Files.writeString(warpsConfigPath, originalWarpsJson);
				} else {
					Files.deleteIfExists(warpsConfigPath);
				}
				server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "isadmin warps reload");
			} catch (Exception ignored) {
			}
			if (visitorPadOrigin.get() != null) {
				GameTestSupport.clearBlockPosIfLoaded(ogOverworld, visitorPadOrigin.get());
			}
			if (outerWarpBlockPos.get() != null) {
				ogOverworld.setBlock(outerWarpBlockPos.get(), Blocks.AIR.defaultBlockState(), 818);
			}
			if (netherPadOrigin.get() != null) {
				GameTestSupport.clearBlockPosIfLoaded(ogNether, netherPadOrigin.get());
			}
			GameTestSupport.deleteAndCleanupIsland(server, coreApi, islands, islandIdRef.get(), ownerId);
			GameTestSupport.deleteDirectoryRecursively(bundleDir);
			coreApi.blueprints().registry().reload();
			server.getPlayerList().remove(owner);
			server.getPlayerList().remove(member);
			server.getPlayerList().remove(visitor);
			coreApi.team().onPlayerDisconnect(server, ownerId);
			coreApi.team().onPlayerDisconnect(server, memberId);
			coreApi.team().onPlayerDisconnect(server, visitorId);
			coreApi.protectionListeners().onPlayerDisconnect(ownerId);
			coreApi.protectionListeners().onPlayerDisconnect(memberId);
			coreApi.protectionListeners().onPlayerDisconnect(visitorId);
		};

		GameTestSupport.runWithCleanup(cleanup, () -> {
			GameTestSupport.synthesizeSimpleBlueprintBundle(ogOverworld, bundleDir, "warps_test", "Warps Test", 6);
			coreApi.blueprints().registry().reload();

			Files.createDirectories(warpsConfigPath.getParent());
			Files.writeString(warpsConfigPath, """
				{
				  "enabled": true,
				  "defaultManageWarpsRank": "ADMIN",
				  "defaultUseWarpRank": "VISITOR",
				  "allowOverworld": true,
				  "allowNether": true,
				  "allowEnd": true,
				  "defaultMaxWarpsPerIsland": 2,
				  "permissionLimitTiers": [1, 2, 5, 10],
				  "maxWarpNameLength": 24,
				  "maxDescriptionLength": 80,
				  "defaultCategory": "general",
				  "warmupSeconds": 1,
				  "cancelWarmupOnMove": true,
				  "warmupMoveToleranceBlocks": 0.25,
				  "cancelWarmupOnDamage": true,
				  "cooldownSeconds": 5,
				  "countIslandMembersInVisits": false,
				  "visitCountCooldownSeconds": 3600,
				  "markerDurationSeconds": 15,
				  "markerIntervalTicks": 2,
				  "markerParticle": "minecraft:end_rod"
				}
				""");
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "isadmin warps reload");

			var createRes = coreApi.islands().createIsland(server, owner, ownerId, "warps_test");
			helper.assertTrue(createRes.success() && createRes.value() != null, "Expected warps test island created");
			UUID islandId = createRes.value().islandId();
			islandIdRef.set(islandId);

			Island createdIsland = islands.grid().findById(islandId).orElseThrow();
			coreApi.lifecycle().resizeIsland(islandId, 16);
			final int cx = createdIsland.centerX();
			final int cy = createdIsland.overworldSpawn().blockY();
			final int cz = createdIsland.centerZ();

			// Wait until initial blueprint paste finishes and set up island platform + external visitor waiting pad.
			helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(!createdIsland.isPasting(), "Expected warps test paste completion"))
				.thenExecute(GameTestSupport.guarded(cleanup, () -> {
					owner.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
					member.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
					visitor.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
					visitor.setInvulnerableTime(0);

					// Build a safe 5x5 stone platform on owner's island + outer spot at (cx + 12, cy, cz + 12)
					for (int dx = -2; dx <= 2; dx++) {
						for (int dz = -2; dz <= 2; dz++) {
							ogOverworld.setBlock(new BlockPos(cx + dx, cy - 1, cz + dz), Blocks.STONE.defaultBlockState(), 18);
							ogOverworld.setBlock(new BlockPos(cx + dx, cy, cz + dz), Blocks.AIR.defaultBlockState(), 18);
							ogOverworld.setBlock(new BlockPos(cx + dx, cy + 1, cz + dz), Blocks.AIR.defaultBlockState(), 18);
						}
					}
					BlockPos edgeFloor = new BlockPos(cx + 12, cy - 1, cz + 12);
					ogOverworld.setBlock(edgeFloor, Blocks.STONE.defaultBlockState(), 18);
					outerWarpBlockPos.set(edgeFloor);

					// Build visitor pad outside owner's halfSize=16 bounds (same chunk cell buffer zone -> fast shutdown)
					BlockPos pad = new BlockPos(cx + 24, cy - 1, cz + 24);
					visitorPadOrigin.set(pad);
					for (int dx = -1; dx <= 1; dx++) {
						for (int dz = -1; dz <= 1; dz++) {
							ogOverworld.setBlock(pad.offset(dx, 0, dz), Blocks.STONE.defaultBlockState(), 18);
							ogOverworld.setBlock(pad.offset(dx, 1, dz), Blocks.AIR.defaultBlockState(), 18);
							ogOverworld.setBlock(pad.offset(dx, 2, dz), Blocks.AIR.defaultBlockState(), 18);
						}
					}
					visitor.teleport(new TeleportTransition(
						ogOverworld, new Vec3(pad.getX() + 0.5D, pad.getY() + 1.0D, pad.getZ() + 0.5D),
						Vec3.ZERO, 0.0F, 0.0F, TeleportTransition.DO_NOTHING
					));

					// Add member to island
					coreApi.team().inviteMember(createdIsland, ownerId, memberId);
					coreApi.team().acceptInvite(memberId);

					// 1. Multi-warp creation + strict limit (2)
					owner.teleport(new TeleportTransition(
						ogOverworld, new Vec3(cx + 0.5D, cy, cz + 0.5D),
						Vec3.ZERO, 90.0F, 0.0F, TeleportTransition.DO_NOTHING
					));
					int setHomeOk = exec(server, owner, "is setwarp");
					helper.assertTrue(setHomeOk > 0, "Expected /is setwarp (default 'home') to succeed");

					owner.teleport(new TeleportTransition(
						ogOverworld, new Vec3(cx + 2.5D, cy, cz + 0.5D),
						Vec3.ZERO, 180.0F, 0.0F, TeleportTransition.DO_NOTHING
					));
					int setShopOk = exec(server, owner, "is setwarp shop shop");
					helper.assertTrue(setShopOk > 0, "Expected /is setwarp shop shop to succeed");

					// 3rd warp must fail due to defaultMaxWarpsPerIsland=2
					int setThird = exec(server, owner, "is setwarp extra");
					helper.assertTrue(setThird == 0, "Expected 3rd warp to be rejected when maxWarps=2");

					// Member blocked when manage_warps=ADMIN; allowed when manage_warps=MEMBER
					member.teleport(new TeleportTransition(
						ogOverworld, new Vec3(cx + 1.5D, cy, cz + 1.5D),
						Vec3.ZERO, 0.0F, 0.0F, TeleportTransition.DO_NOTHING
					));
					helper.assertTrue(exec(server, member, "is setwarp memwarp") == 0, "Member should fail manage_warps=ADMIN");
					coreApi.islands().setProtectionRank(ownerId, islandId, manageWarpsFlag, (byte) 3, false);
					// Still at limit 2/2 -> overwrite 'shop' succeeds via manage_warps=MEMBER!
					helper.assertTrue(exec(server, member, "is setwarp shop farm") > 0, "Member should overwrite warp when manage_warps=MEMBER");
					exec(server, owner, "is warps edit shop category shop");
					exec(server, owner, "is warps edit shop desc Best skyblock shop");

					// 2. Particle marker /is warps show emits personal particle packets
					GameTestSupport.drainOutboundPackets(ownerChannel.get());
					GameTestSupport.drainOutboundPackets(visitorChannel.get());
					helper.assertTrue(exec(server, owner, "is warps show shop") > 0, "Expected /is warps show shop to start marker");
					List<Packet<?>> ownerShowPackets = GameTestSupport.drainOutboundPackets(ownerChannel.get());
					List<Packet<?>> visitorShowPackets = GameTestSupport.drainOutboundPackets(visitorChannel.get());
					helper.assertTrue(
						ownerShowPackets.stream().anyMatch(ClientboundLevelParticlesPacket.class::isInstance),
						"Expected owner to receive ClientboundLevelParticlesPacket for warp marker"
					);
					helper.assertTrue(
						visitorShowPackets.stream().noneMatch(ClientboundLevelParticlesPacket.class::isInstance),
						"Expected personal warp marker particles NOT sent to unrelated visitor"
					);
					helper.assertTrue(exec(server, owner, "is warps show off") > 0, "Expected /is warps show off to stop marker");

					// 3. Anti-trap safety checks (setwarp over lava & carpet-over-lava rejected; converted trap CANCELLED on teleport)
					BlockPos lavaTrapPos = new BlockPos(cx - 2, cy - 1, cz - 2);
					ogOverworld.setBlock(lavaTrapPos, Blocks.LAVA.defaultBlockState(), 18);
					ogOverworld.setBlock(lavaTrapPos.above(), Blocks.MOSS_CARPET.defaultBlockState(), 18);
					helper.assertTrue(
						!coreApi.islands().isLocationSafe(ogOverworld, lavaTrapPos.getX() + 0.5D, lavaTrapPos.getY() + 1.0625D, lavaTrapPos.getZ() + 0.5D),
						"Expected carpet-over-lava trap to fail SafeLocationCheck"
					);
					helper.assertTrue(
						!coreApi.islands().isLocationSafe(ogOverworld, cx, cy + 5.0D, cz),
						"Expected mid-air over void to fail SafeLocationCheck"
					);
					ogOverworld.setBlock(lavaTrapPos.above(), Blocks.AIR.defaultBlockState(), 18);
					ogOverworld.setBlock(lavaTrapPos, Blocks.STONE.defaultBlockState(), 18);

					// 4. Warmup cancellation by movement
					GameTestSupport.drainOutboundPackets(visitorChannel.get());
					helper.assertTrue(exec(server, visitor, "is warp WarpOwner home") > 0, "Expected warmup to start");
					BlockPos padPos = visitorPadOrigin.get();
					visitor.setPos(padPos.getX() + 1.4D, padPos.getY() + 1.0D, padPos.getZ() + 0.5D);
				}))
				.thenIdle(2)
				.thenExecute(GameTestSupport.guarded(cleanup, () -> {
					List<String> moveChats = drainChatMessages(visitorChannel.get());
					helper.assertTrue(
						moveChats.stream().anyMatch(m -> m.contains("cancelled") && m.contains("moved")),
						"Expected warmup to cancel on player movement, got: " + moveChats
					);

					// Reset visitor to center of pad, start warmup, then apply damage -> CANCEL
					BlockPos padPos = visitorPadOrigin.get();
					visitor.setPos(padPos.getX() + 0.5D, padPos.getY() + 1.0D, padPos.getZ() + 0.5D);
					GameTestSupport.drainOutboundPackets(visitorChannel.get());
					helper.assertTrue(exec(server, visitor, "is warp WarpOwner home") > 0, "Expected 2nd warmup to start");
					visitor.setInvulnerableTime(0);
					boolean hurt = visitor.hurtServer(ogOverworld, ogOverworld.damageSources().generic(), 1.0F);
					helper.assertTrue(hurt, "Expected mock player hurtServer to succeed");
					List<String> damageChats = drainChatMessages(visitorChannel.get());
					helper.assertTrue(
						damageChats.stream().anyMatch(m -> m.contains("cancelled") && m.contains("damage")),
						"Expected damage to cancel warmup immediately, got: " + damageChats
					);

					// Heal visitor back & start clean warmup -> let 22 ticks pass for successful teleport!
					visitor.setHealth(visitor.getMaxHealth());
					visitor.setInvulnerableTime(0);
					GameTestSupport.drainOutboundPackets(visitorChannel.get());
					helper.assertTrue(exec(server, visitor, "is warp WarpOwner home") > 0, "Expected clean warmup to start");
				}))
				.thenIdle(24)
				.thenExecute(GameTestSupport.guarded(cleanup, () -> {
					// Visitor should now stand at owner's island home warp!
					helper.assertTrue(
						createdIsland.contains(visitor.getBlockX(), visitor.getBlockZ())
							&& Math.abs(visitor.getX() - (cx + 0.5D)) < 0.5D
							&& Math.abs(visitor.getZ() - (cz + 0.5D)) < 0.5D,
						"Expected visitor to teleport to 'home' after warmup completed, got pos=" + visitor.position()
					);

					// Cooldown active (5s) -> immediate second warp fails!
					helper.assertTrue(exec(server, visitor, "is warp WarpOwner shop") == 0, "Expected warp cooldown to reject immediate re-warp");

					// Verify visitCount == 1 in warps.db (plus member visit didn't count)
					ModuleDatabase warpsDb = coreApi.databases().require("warps");
					warpsDb.flush().join();
					long homeVisits = queryWarpVisits(warpsDb, islandId, "home");
					helper.assertTrue(homeVisits == 1L, "Expected initial visitor teleport to record 1 visit, got " + homeVisits);

					// Popularity anti-abuse: move visitor outside, reload with cooldown=0 & warmup=0, re-warp twice -> count MUST remain 1 (visitCountCooldownSeconds=3600)
					BlockPos padPos = visitorPadOrigin.get();
					visitor.teleport(new TeleportTransition(
						ogOverworld, new Vec3(padPos.getX() + 0.5D, padPos.getY() + 1.0D, padPos.getZ() + 0.5D),
						Vec3.ZERO, 0.0F, 0.0F, TeleportTransition.DO_NOTHING
					));
					writeFastTestConfig(warpsConfigPath, 0, 0);
					server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "isadmin warps reload");

					helper.assertTrue(exec(server, visitor, "is warp WarpOwner home") > 0, "Expected instant re-warp when warmup/cooldown=0");
					helper.assertTrue(exec(server, owner, "is warp WarpOwner home") > 0, "Expected owner warp to own warp to succeed");
					warpsDb.flush().join();
					helper.assertTrue(
						queryWarpVisits(warpsDb, islandId, "home") == 1L,
						"Expected visitCountCooldownSeconds & member filter to prevent visitCount inflation"
					);

					// Flag / lock / ban protection tests
					visitor.teleport(new TeleportTransition(
						ogOverworld, new Vec3(padPos.getX() + 0.5D, padPos.getY() + 1.0D, padPos.getZ() + 0.5D),
						Vec3.ZERO, 0.0F, 0.0F, TeleportTransition.DO_NOTHING
					));
					coreApi.islands().setProtectionRank(ownerId, islandId, useWarpFlag, (byte) 3, false);
					helper.assertTrue(exec(server, visitor, "is warp WarpOwner shop") == 0, "Visitor denied when use_warp=MEMBER");
					coreApi.islands().setProtectionRank(ownerId, islandId, useWarpFlag, (byte) 0, false);

					exec(server, owner, "is lock true");
					helper.assertTrue(exec(server, visitor, "is warp WarpOwner shop") == 0, "Visitor denied when island is locked");
					exec(server, owner, "is lock false");

					exec(server, owner, "is ban WarpVisitor");
					helper.assertTrue(exec(server, visitor, "is warp WarpOwner shop") == 0, "Banned visitor denied from warp");
					exec(server, owner, "is unban WarpVisitor");

					// Turn 'shop' (at member's last setwarp pos cx+1, cz+1) into a lava trap -> CANCELLED without find-nearby fallback!
					BlockPos shopFloorPos = new BlockPos(cx + 1, cy - 1, cz + 1);
					ogOverworld.setBlock(shopFloorPos, Blocks.LAVA.defaultBlockState(), 18);
					double beforeTrapX = visitor.getX();
					double beforeTrapZ = visitor.getZ();
					helper.assertTrue(exec(server, visitor, "is warp WarpOwner shop") == 0, "Expected trapped 'shop' warp teleport to CANCEL");
					helper.assertTrue(
						Math.abs(visitor.getX() - beforeTrapX) < 0.01D && Math.abs(visitor.getZ() - beforeTrapZ) < 0.01D,
						"Expected cancelled trapped warp NOT to relocate player anywhere"
					);
					ogOverworld.setBlock(shopFloorPos, Blocks.STONE.defaultBlockState(), 18);

					// Test UI / Chat Fallback depending on whether ogsky-panel is bundled
					if (!FabricLoader.getInstance().isModLoaded("ogsky-panel")) {
						helper.assertTrue(!coreApi.guis().isAvailable(), "Expected GuiService unavailable without ogsky-panel");
						GameTestSupport.drainOutboundPackets(visitorChannel.get());
						helper.assertTrue(exec(server, visitor, "is warps") > 0, "Expected /is warps chat fallback to succeed");
						helper.assertTrue(exec(server, owner, "is warps mine") > 0, "Expected /is warps mine chat fallback to succeed");
						List<String> chats = drainChatMessages(visitorChannel.get());
						helper.assertTrue(
							chats.stream().anyMatch(m -> m.contains("Island Warps"))
								&& chats.stream().anyMatch(m -> m.contains("shop")),
							"Expected /is warps chat fallback output to list warps"
						);
						IslandActionDescriptor showcaseAction = coreApi.actions()
							.find(Identifier.fromNamespaceAndPath("ogsky_warps", "showcase"))
							.orElseThrow();
						showcaseAction.handler().execute(new IslandActionContext(visitor, islandId));
					} else {
						helper.assertTrue(exec(server, owner, "is panel") > 0, "Expected /is panel command to execute");
					}
				}))
				.thenWaitUntil(() -> {
					if (!FabricLoader.getInstance().isModLoaded("ogsky-panel")) {
						return;
					}
					helper.assertTrue(owner.containerMenu instanceof ChestMenu, "Expected /is panel ChestMenu to open asynchronously");
				})
				.thenExecute(GameTestSupport.guarded(cleanup, () -> {
					ModuleDatabase warpsDb = coreApi.databases().require("warps");
					if (FabricLoader.getInstance().isModLoaded("ogsky-panel")) {
						// Verify /is panel slot 8 summary line
						ChestMenu panelMenu = (ChestMenu) owner.containerMenu;
						ItemLore summaryLore = panelMenu.getSlot(8).getItem().get(DataComponents.LORE);
						helper.assertTrue(
							summaryLore != null && summaryLore.lines().stream().anyMatch(l -> l.getString().contains("Warps:")),
							"Expected /is panel slot 8 summary to include Warps line"
						);
						owner.closeContainer();

						// Edit custom icon using main hand item + verify in /is warps GUI + reset to player_head
						owner.getInventory().setItem(0, new ItemStack(Items.EMERALD_BLOCK, 1));
						owner.getInventory().setSelectedSlot(0);
						helper.assertTrue(exec(server, owner, "is warps edit shop icon hand") > 0, "Expected icon hand update");

						helper.assertTrue(exec(server, visitor, "is warps") > 0 && visitor.containerMenu instanceof ChestMenu, "Expected /is warps GUI to open");
						ChestMenu showcaseMenu = (ChestMenu) visitor.containerMenu;
						int shopSlot = GameTestSupport.findContentSlotByName(showcaseMenu, "shop (WarpOwner)");
						helper.assertTrue(shopSlot >= 0, "Expected 'shop (WarpOwner)' entry in showcase GUI");
						helper.assertTrue(
							showcaseMenu.getSlot(shopSlot).getItem().is(Items.EMERALD_BLOCK),
							"Expected custom emerald_block icon for 'shop'"
						);
						int homeSlot = GameTestSupport.findContentSlotByName(showcaseMenu, "home (WarpOwner)");
						helper.assertTrue(
							homeSlot >= 0 && showcaseMenu.getSlot(homeSlot).getItem().is(Items.PLAYER_HEAD),
							"Expected default player_head icon for 'home'"
						);

						// Cycle sort button (slot 47) through recent -> popular -> (level) -> alpha
						String sortInitial = showcaseMenu.getSlot(47).getItem().get(DataComponents.CUSTOM_NAME).getString();
						helper.assertTrue(sortInitial.contains("Most Recent"), "Expected default sort Most Recent");
						showcaseMenu.clicked(47, 0, ContainerInput.PICKUP, visitor);
						helper.assertTrue(
							showcaseMenu.getSlot(47).getItem().get(DataComponents.CUSTOM_NAME).getString().contains("Popularity"),
							"Expected sort cycle to Popularity"
						);
						visitor.closeContainer();

						// Reset icon back to owner head & verify via manage -> edit -> 2-click delete
						helper.assertTrue(exec(server, owner, "is warps edit shop icon head") > 0, "Expected icon head reset");
						helper.assertTrue(exec(server, owner, "is warps mine") > 0 && owner.containerMenu instanceof ChestMenu, "Expected /is warps mine GUI");
						ChestMenu manageMenu = (ChestMenu) owner.containerMenu;
						int manageShopSlot = GameTestSupport.findContentSlotByName(manageMenu, "shop");
						helper.assertTrue(
							manageShopSlot >= 0 && manageMenu.getSlot(manageShopSlot).getItem().is(Items.PLAYER_HEAD),
							"Expected reset 'shop' icon to be PLAYER_HEAD"
						);
						// Open EDIT_SCREEN
						manageMenu.clicked(manageShopSlot, 0, ContainerInput.PICKUP, owner);
						helper.assertTrue(owner.containerMenu instanceof ChestMenu editMenu && editMenu.slots.size() == 63,
							"Expected 3-row EDIT_SCREEN (27+36=63 slots)");
						ChestMenu editMenu = (ChestMenu) owner.containerMenu;
						// Toggle disabled (slot 12), verify button changes, then re-enable
						editMenu.clicked(12, 0, ContainerInput.PICKUP, owner);
						helper.assertTrue(
							editMenu.getSlot(12).getItem().get(DataComponents.CUSTOM_NAME).getString().contains("Disabled"),
							"Expected warp status toggle to Disabled"
						);
						editMenu.clicked(12, 0, ContainerInput.PICKUP, owner);
						// 2-click delete (slot 26)
						editMenu.clicked(26, 0, ContainerInput.PICKUP, owner);
						editMenu.clicked(26, 0, ContainerInput.PICKUP, owner);
						owner.closeContainer();
					}

					// If ogsky-panel absent, delete 'shop' via command before range/nether test
					if (!FabricLoader.getInstance().isModLoaded("ogsky-panel")) {
						helper.assertTrue(exec(server, owner, "is delwarp shop") > 0, "Expected /is delwarp shop to free a warp slot");
					}

					// Move warp near border & verify IslandRangeChangeEvent out-of-bounds enforcement
					BlockPos edgePos = outerWarpBlockPos.get();
					owner.teleport(new TeleportTransition(
						ogOverworld, new Vec3(edgePos.getX() + 0.5D, edgePos.getY() + 1.0D, edgePos.getZ() + 0.5D),
						Vec3.ZERO, 0.0F, 0.0F, TeleportTransition.DO_NOTHING
					));
					helper.assertTrue(exec(server, owner, "is setwarp edge") > 0, "Expected edge warp within halfSize=16");
					exec(server, server.createCommandSourceStack(), "isadmin setrange WarpOwner 8");
					helper.assertTrue(exec(server, visitor, "is warp WarpOwner edge") == 0, "Expected shrunk island to block out-of-bounds warp");
					exec(server, server.createCommandSourceStack(), "isadmin setrange WarpOwner 16");
					helper.assertTrue(exec(server, visitor, "is warp WarpOwner edge") > 0, "Expected restored range to re-enable edge warp");
					exec(server, owner, "is delwarp edge");

					// Lazy Nether warp test
					BlockPos netherSpot = new BlockPos(cx, 64, cz);
					netherPadOrigin.set(netherSpot);
					ogNether.setBlock(netherSpot, Blocks.NETHERRACK.defaultBlockState(), 18);
					ogNether.setBlock(netherSpot.above(), Blocks.AIR.defaultBlockState(), 18);
					ogNether.setBlock(netherSpot.above(2), Blocks.AIR.defaultBlockState(), 18);
					owner.teleport(new TeleportTransition(
						ogNether, new Vec3(cx + 0.5D, 65.0D, cz + 0.5D),
						Vec3.ZERO, 0.0F, 0.0F, TeleportTransition.DO_NOTHING
					));
					helper.assertTrue(exec(server, owner, "is setwarp nether_hub") > 0, "Expected Nether warp creation");
					helper.assertTrue(exec(server, visitor, "is warp WarpOwner nether_hub") > 0, "Expected visitor warp into ogsky:nether");
					helper.assertTrue(
						visitor.level().dimension().equals(OgskyDimensions.NETHER),
						"Expected visitor to arrive in ogsky:nether"
					);

					// Delete island & confirm warps.db cleanup
					GameTestSupport.deleteAndCleanupIsland(server, coreApi, islands, islandId, ownerId);
					islandIdRef.set(null);
					warpsDb.flush().join();
					helper.assertTrue(countWarpRows(warpsDb, islandId) == 0, "Expected all warps.db rows cleaned up on island delete");
				}))
				.thenSucceed();
		});
	}

	private static int exec(MinecraftServer server, ServerPlayer player, String command) {
		return exec(server, player.createCommandSourceStack(), command);
	}

	private static int exec(MinecraftServer server, net.minecraft.commands.CommandSourceStack source, String command) {
		try {
			return server.getCommands().getDispatcher().execute(command, source);
		} catch (Exception ex) {
			return 0;
		}
	}

	private static List<String> drainChatMessages(EmbeddedChannel channel) {
		return GameTestSupport.drainOutboundPackets(channel).stream()
			.filter(ClientboundSystemChatPacket.class::isInstance)
			.map(ClientboundSystemChatPacket.class::cast)
			.map(p -> p.content().getString())
			.toList();
	}

	private static void writeFastTestConfig(Path path, int warmupSeconds, int cooldownSeconds) {
		try {
			Files.writeString(path, """
				{
				  "enabled": true,
				  "defaultManageWarpsRank": "ADMIN",
				  "defaultUseWarpRank": "VISITOR",
				  "allowOverworld": true,
				  "allowNether": true,
				  "allowEnd": true,
				  "defaultMaxWarpsPerIsland": 2,
				  "permissionLimitTiers": [1, 2, 5, 10],
				  "maxWarpNameLength": 24,
				  "maxDescriptionLength": 80,
				  "defaultCategory": "general",
				  "warmupSeconds": %d,
				  "cancelWarmupOnMove": true,
				  "warmupMoveToleranceBlocks": 0.25,
				  "cancelWarmupOnDamage": true,
				  "cooldownSeconds": %d,
				  "countIslandMembersInVisits": false,
				  "visitCountCooldownSeconds": 3600,
				  "markerDurationSeconds": 15,
				  "markerIntervalTicks": 2,
				  "markerParticle": "minecraft:end_rod"
				}
				""".formatted(warmupSeconds, cooldownSeconds));
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	private static long queryWarpVisits(ModuleDatabase db, UUID islandId, String slug) {
		return db.queryBlocking(conn -> {
			try (var stmt = conn.prepareStatement("SELECT visit_count FROM ogsky_warps WHERE island_id = ? AND slug = ?")) {
				stmt.setString(1, islandId.toString());
				stmt.setString(2, slug);
				try (ResultSet rs = stmt.executeQuery()) {
					return rs.next() ? rs.getLong(1) : -1L;
				}
			}
		});
	}

	private static int countWarpRows(ModuleDatabase db, UUID islandId) {
		return db.queryBlocking(conn -> {
			try (Statement stmt = conn.createStatement();
				 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM ogsky_warps WHERE island_id = '" + islandId + "'")) {
				return rs.next() ? rs.getInt(1) : 0;
			}
		});
	}
}
