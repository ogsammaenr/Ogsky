package me.ogsammaenr.ogsky.gametest;

import io.netty.channel.embedded.EmbeddedChannel;
import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.gui.GuiService;
import me.ogsammaenr.ogsky.core.internal.OgskyCoreApiImpl;
import me.ogsammaenr.ogsky.core.internal.flag.BuiltinFlags;
import me.ogsammaenr.ogsky.core.internal.island.CoreIslandService;
import me.ogsammaenr.ogsky.core.internal.island.Island;
import me.ogsammaenr.ogsky.core.internal.world.OgskyDimensions;
import me.ogsammaenr.ogui.api.Ogui;
import me.ogsammaenr.ogui.api.OguiAssetKind;
import me.ogsammaenr.ogui.api.OguiAssetRef;
import me.ogsammaenr.ogui.api.OguiClickAction;
import me.ogsammaenr.ogui.api.OguiStructureShell;
import me.ogsammaenr.ogui.network.C2SClientHelloPayload;
import me.ogsammaenr.ogui.network.C2SCloseNotifyPayload;
import me.ogsammaenr.ogui.network.C2SRequestAssetPayload;
import me.ogsammaenr.ogui.network.C2SShortcutTriggerPayload;
import me.ogsammaenr.ogui.network.C2SWidgetInteractPayload;
import me.ogsammaenr.ogui.network.OguiWidgetDto;
import me.ogsammaenr.ogui.network.S2CAssetChunkPayload;
import me.ogsammaenr.ogui.network.S2CAssetUnavailablePayload;
import me.ogsammaenr.ogui.network.S2CCloseScreenPayload;
import me.ogsammaenr.ogui.network.S2CFeedbackPayload;
import me.ogsammaenr.ogui.network.S2COpenScreenPayload;
import me.ogsammaenr.ogui.network.S2CServerHelloPayload;
import me.ogsammaenr.ogui.network.S2CUpdateScreenPayload;
import me.ogsammaenr.ogui.runtime.OguiSession;
import me.ogsammaenr.ogui.runtime.OguiSessionManager;
import me.ogsammaenr.ogui.vanilla.FakeSignInputManager;
import me.ogsammaenr.ogui.vanilla.OguiChestMenu;
import net.fabricmc.fabric.impl.networking.RegistrationPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Separated helper class so {@code -Pmodules=core,level} runs without loading {@code ogui-api} classes.
 */
final class OguiPanelGameTestBridge {
	private OguiPanelGameTestBridge() {
	}

	static void runHybridAndFakeSignTest(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel ogOverworld = server.getLevel(OgskyDimensions.OVERWORLD);
		helper.assertTrue(ogOverworld != null, "Expected ogsky:overworld dimension");

		OgskyCoreApiImpl coreApi = (OgskyCoreApiImpl) OgskyCore.api();
		CoreIslandService islands = coreApi.coreIslands();
		Path bundleDir = coreApi.blueprints().registry().blueprintsDir().resolve("ogui_hybrid_test");

		UUID modernId = UUID.randomUUID();
		UUID vanillaId = UUID.randomUUID();
		AtomicReference<EmbeddedChannel> modernChRef = new AtomicReference<>();
		AtomicReference<EmbeddedChannel> vanillaChRef = new AtomicReference<>();

		ServerPlayer modern = GameTestSupport.createOnlineMockPlayer(server, ogOverworld, modernId, "OguiModern", GameType.SURVIVAL, modernChRef);
		ServerPlayer vanilla = GameTestSupport.createOnlineMockPlayer(server, ogOverworld, vanillaId, "OguiFallback", GameType.SURVIVAL, vanillaChRef);

		AtomicReference<UUID> islandIdRef = new AtomicReference<>();
		Runnable cleanup = () -> {
			GameTestSupport.closeContainersQuietly(modern, vanilla);
			Ogui.service().closePlayerScreen(modern);
			Ogui.service().closePlayerScreen(vanilla);
			GameTestSupport.deleteAndCleanupIsland(server, coreApi, islands, islandIdRef.get(), modernId);
			GameTestSupport.deleteDirectoryRecursively(bundleDir);
			coreApi.blueprints().registry().reload();
			server.getPlayerList().remove(modern);
			server.getPlayerList().remove(vanilla);
		};

		GameTestSupport.runWithCleanup(cleanup, () -> {
			GameTestSupport.synthesizeSimpleBlueprintBundle(ogOverworld, bundleDir, "ogui_hybrid_test", "Hybrid Test", 1);
			coreApi.blueprints().registry().reload();

			var createdRes = coreApi.lifecycle().createIsland(server, modern, modernId, "ogui_hybrid_test");
			helper.assertTrue(createdRes.success() && createdRes.value() != null, "Expected test island creation");
			Island island = createdRes.value();
			islandIdRef.set(island.islandId());
			for (int t = 0; t < 20 && island.isPasting(); t++) {
				coreApi.blueprints().tick(server);
			}

			// Invite vanilla player as ADMIN on same island so both test concurrently
			helper.assertTrue(coreApi.team().inviteMember(island, modernId, vanillaId).success(), "Invite vanilla");
			helper.assertTrue(coreApi.team().acceptInvite(vanillaId).success(), "Accept invite");
			helper.assertTrue(coreApi.team().promoteMember(island, modernId, vanillaId, false).success(), "Promote vanilla to ADMIN");

			// 1. Compatible ogui-client handshake -> receives ogsky:panel shortcut & opens S2COpenScreenPayload
			registerOguiClientChannels(modern);
			GameTestSupport.drainOutboundPackets(modernChRef.get());
			modern.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(new C2SClientHelloPayload(Ogui.PROTOCOL_VERSION, "1.0.0")));

			S2CServerHelloPayload helloAck = findLatestPayload(GameTestSupport.drainOutboundPackets(modernChRef.get()), S2CServerHelloPayload.class);
			helper.assertTrue(helloAck != null && helloAck.shortcuts().stream().anyMatch(s -> s.id().equals(OgskyCore.id("panel"))),
				"Expected S2CServerHelloPayload to advertise ogsky:panel shortcut");

			// Trigger /is panel via G-key shortcut packet
			modern.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(new C2SShortcutTriggerPayload(OgskyCore.id("panel"))));

			// 2. Also open /is settings on vanilla fallback player & test Fake Sign search filter
			server.getCommands().performPrefixedCommand(vanilla.createCommandSourceStack(), "is settings");

			helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(
					OguiSessionManager.instance().activeSession(modernId) != null
						&& vanilla.containerMenu instanceof OguiChestMenu,
					"Expected client session and vanilla OguiChestMenu fallback to both open"
				))
				.thenExecute(GameTestSupport.guarded(cleanup, () -> {
					S2COpenScreenPayload openPayload = findLatestPayload(GameTestSupport.drainOutboundPackets(modernChRef.get()), S2COpenScreenPayload.class);
					helper.assertTrue(openPayload != null && openPayload.snapshot().screenId().equals(GuiService.MAIN_PANEL_SCREEN_ID),
						"Expected S2COpenScreenPayload for ogsky:main_panel");
					helper.assertTrue(openPayload.snapshot().tabs().size() == 5, "Expected all 5 panel tabs on client DTO");

					UUID sid = openPayload.sessionId();
					int rev = openPayload.revision();

					// Toggle quick_lock via C2SWidgetInteractPayload from ogui-client
					modern.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(
						new C2SWidgetInteractPayload(sid, rev, "quick_lock", OguiClickAction.LEFT, "", 0)
					));
					helper.assertTrue(island.isSettingEnabled(BuiltinFlags.ISLAND_LOCK),
						"Expected quick_lock C2S interaction to lock island");

					List<Packet<?>> afterLock = GameTestSupport.drainOutboundPackets(modernChRef.get());
					S2CFeedbackPayload fb = findLatestPayload(afterLock, S2CFeedbackPayload.class);
					S2CUpdateScreenPayload up = findLatestPayload(afterLock, S2CUpdateScreenPayload.class);
					helper.assertTrue(fb != null && fb.success() && up != null && up.revision() > rev,
						"Expected S2CFeedbackPayload and updated S2CUpdateScreenPayload after C2S lock toggle");

					// Switch to protection_flags tab via OguiSession.NAV_TAB_PREFIX using client's latest revision
					modern.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(
						new C2SWidgetInteractPayload(sid, up.revision(), OguiSession.NAV_TAB_PREFIX + "protection_flags", OguiClickAction.LEFT, "", 0)
					));
					S2CUpdateScreenPayload tabUpdate = findLatestPayload(GameTestSupport.drainOutboundPackets(modernChRef.get()), S2CUpdateScreenPayload.class);
					helper.assertTrue(tabUpdate != null && "protection_flags".equals(tabUpdate.snapshot().activeTabId()),
						"Expected tab switch to protection_flags on client DTO");

					// Vanilla player tests Fake Sign search filter on Protection Flags (slot 46)
					OguiChestMenu vanillaMenu = (OguiChestMenu) vanilla.containerMenu;
					helper.assertTrue(GameTestSupport.findContentSlotByName(vanillaMenu, "Break Blocks") >= 0,
						"Expected Break Blocks initially");
					helper.assertTrue(GameTestSupport.findContentSlotByName(vanillaMenu, "Place Blocks") >= 0,
						"Expected Place Blocks initially");

					vanillaMenu.clicked(46, 0, ContainerInput.PICKUP, vanilla);
					var pendingSign = FakeSignInputManager.instance().pendingFor(vanillaId);
					helper.assertTrue(pendingSign != null, "Expected FakeSignInputManager pending for vanilla slot 46 Search");
					vanilla.connection.handleSignUpdate(new ServerboundSignUpdatePacket(
						pendingSign.fakePos(),
						List.of("break", "", "▲ Type above ▲", "Search"),
						SignTextSlot.FRONT
					));

					helper.assertTrue(vanilla.containerMenu instanceof OguiChestMenu, "Expected OguiChestMenu reopened after Fake Sign");
					OguiChestMenu filteredMenu = (OguiChestMenu) vanilla.containerMenu;
					helper.assertTrue(GameTestSupport.findContentSlotByName(filteredMenu, "Break Blocks") >= 0,
						"Expected Break Blocks retained by Fake Sign search 'break'");
					helper.assertTrue(GameTestSupport.findContentSlotByName(filteredMenu, "Place Blocks") < 0,
						"Expected Place Blocks filtered out by Fake Sign search 'break'");

					// Right-click slot 46 clears search
					filteredMenu.clicked(46, 1, ContainerInput.PICKUP, vanilla);
					helper.assertTrue(GameTestSupport.findContentSlotByName((OguiChestMenu) vanilla.containerMenu, "Place Blocks") >= 0,
						"Expected right-click Search slot 46 to clear filter");

					// Clean close notify for modern client
					modern.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(new C2SCloseNotifyPayload(sid)));
					helper.assertTrue(OguiSessionManager.instance().activeSession(modernId) == null,
						"Expected C2SCloseNotifyPayload to release client session");

					cleanup.run();
				}))
				.thenSucceed();
		});
	}

	static void runBlueprintGalleryAnd3DPreviewTest(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel ogOverworld = server.getLevel(OgskyDimensions.OVERWORLD);
		helper.assertTrue(ogOverworld != null, "Expected ogsky:overworld dimension");

		OgskyCoreApiImpl coreApi = (OgskyCoreApiImpl) OgskyCore.api();
		CoreIslandService islands = coreApi.coreIslands();
		Path blueprintsDir = coreApi.blueprints().registry().blueprintsDir();
		Path classicDir = blueprintsDir.resolve("gallery_classic");
		Path iconOnlyDir = blueprintsDir.resolve("gallery_no_png");
		Path reloadedDir = blueprintsDir.resolve("gallery_reloaded");

		UUID modernId = UUID.randomUUID();
		UUID vanillaId = UUID.randomUUID();
		AtomicReference<EmbeddedChannel> modernChRef = new AtomicReference<>();
		AtomicReference<EmbeddedChannel> vanillaChRef = new AtomicReference<>();

		ServerPlayer modern = GameTestSupport.createOnlineMockPlayer(server, ogOverworld, modernId, "GalleryModern", GameType.SURVIVAL, modernChRef);
		ServerPlayer vanilla = GameTestSupport.createOnlineMockPlayer(server, ogOverworld, vanillaId, "GalleryVanilla", GameType.SURVIVAL, vanillaChRef);

		AtomicReference<UUID> modernIslandRef = new AtomicReference<>();
		AtomicReference<UUID> vanillaIslandRef = new AtomicReference<>();
		Runnable cleanup = () -> {
			GameTestSupport.closeContainersQuietly(modern, vanilla);
			Ogui.service().closePlayerScreen(modern);
			Ogui.service().closePlayerScreen(vanilla);
			GameTestSupport.deleteAndCleanupIsland(server, coreApi, islands, modernIslandRef.get(), modernId);
			GameTestSupport.deleteAndCleanupIsland(server, coreApi, islands, vanillaIslandRef.get(), vanillaId);
			GameTestSupport.deleteDirectoryRecursively(classicDir);
			GameTestSupport.deleteDirectoryRecursively(iconOnlyDir);
			GameTestSupport.deleteDirectoryRecursively(reloadedDir);
			coreApi.reloadConfigAndBlueprints(server);
			server.getPlayerList().remove(modern);
			server.getPlayerList().remove(vanilla);
		};

		GameTestSupport.runWithCleanup(cleanup, () -> {
			byte[] classicPng = GameTestSupport.createTestPng(0xFF38BDF8);
			byte[] reloadedPng = GameTestSupport.createTestPng(0xFF22C55E);
			synthesizeMultiDimPreviewBundle(ogOverworld, classicDir, "gallery_classic", "Gallery Classic", 1, classicPng);
			GameTestSupport.synthesizeSimpleBlueprintBundle(ogOverworld, iconOnlyDir, "gallery_no_png", "Gallery Fallback", 2);
			coreApi.reloadConfigAndBlueprints(server);

			registerOguiClientChannels(modern);
			modern.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(new C2SClientHelloPayload(Ogui.PROTOCOL_VERSION, "1.0.0")));
			GameTestSupport.drainOutboundPackets(modernChRef.get());

			// 1. /is create (no args) opens ogsky:island_create PNG Gallery for modded player & vanilla ChestMenu for vanilla player
			server.getCommands().performPrefixedCommand(modern.createCommandSourceStack(), "is create");
			server.getCommands().performPrefixedCommand(vanilla.createCommandSourceStack(), "is create");

			helper.startSequence()
				.thenWaitUntil(() -> helper.assertTrue(
					OguiSessionManager.instance().activeSession(modernId) != null
						&& vanilla.containerMenu instanceof OguiChestMenu,
					"Expected both modded gallery session and vanilla ChestMenu on /is create"
				))
				.thenExecute(GameTestSupport.guarded(cleanup, () -> {
					S2COpenScreenPayload openGallery = findLatestPayload(GameTestSupport.drainOutboundPackets(modernChRef.get()), S2COpenScreenPayload.class);
					helper.assertTrue(openGallery != null && GuiService.CREATE_GALLERY_SCREEN_ID.equals(openGallery.snapshot().screenId()),
						"Expected /is create to open ogsky:island_create");

					OguiWidgetDto.ButtonDto classicCard = openGallery.snapshot().entries().stream()
						.filter(OguiWidgetDto.ButtonDto.class::isInstance)
						.map(OguiWidgetDto.ButtonDto.class::cast)
						.filter(b -> "Gallery Classic".equals(b.label().getString()))
						.findFirst()
						.orElse(null);
					OguiWidgetDto.ButtonDto fallbackCard = openGallery.snapshot().entries().stream()
						.filter(OguiWidgetDto.ButtonDto.class::isInstance)
						.map(OguiWidgetDto.ButtonDto.class::cast)
						.filter(b -> "Gallery Fallback".equals(b.label().getString()))
						.findFirst()
						.orElse(null);
					helper.assertTrue(classicCard != null && classicCard.previewImage() != null
							&& classicCard.previewImage().kind() == OguiAssetKind.PNG,
						"Expected Gallery Classic card to carry PNG OguiAssetRef");
					helper.assertTrue(fallbackCard != null && fallbackCard.previewImage() == null,
						"Expected Gallery Fallback without preview.png to fall back to icon item (previewImage=null)");

					// 2. Request & verify PNG asset chunks over C2SRequestAssetPayload -> S2CAssetChunkPayload
					OguiAssetRef pngRef = classicCard.previewImage();
					modern.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(new C2SRequestAssetPayload(pngRef.sha256())));
					byte[] receivedPng = reassembleAssetChunks(GameTestSupport.drainOutboundPackets(modernChRef.get()), pngRef.sha256());
					helper.assertTrue(Arrays.equals(classicPng, receivedPng), "Expected downloaded PNG bytes to match preview.png");

					// 3. Click Gallery Classic card -> opens ogsky:blueprint_preview (DetailForm + 3D StructurePreviewDto)
					UUID sid = openGallery.sessionId();
					modern.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(
						new C2SWidgetInteractPayload(sid, openGallery.revision(), classicCard.widgetId(), OguiClickAction.LEFT, "", 0)
					));
					S2COpenScreenPayload openPreview = findLatestPayload(GameTestSupport.drainOutboundPackets(modernChRef.get()), S2COpenScreenPayload.class);
					helper.assertTrue(openPreview != null && GuiService.BLUEPRINT_PREVIEW_SCREEN_ID.equals(openPreview.snapshot().screenId()),
						"Expected clicking PNG card to open ogsky:blueprint_preview");
					helper.assertTrue(openPreview.snapshot().hasBack(), "Expected 3D preview to retain back-navigation to gallery");
					helper.assertTrue(openPreview.snapshot().heroCard() instanceof OguiWidgetDto.StructurePreviewDto,
						"Expected StructurePreviewDto hero card on 3D preview screen");

					OguiWidgetDto.StructurePreviewDto heroOw = (OguiWidgetDto.StructurePreviewDto) openPreview.snapshot().heroCard();
					OguiAssetRef owShellRef = heroOw.structureAsset();
					helper.assertTrue(owShellRef != null && owShellRef.kind() == OguiAssetKind.STRUCTURE_SHELL,
						"Expected Overworld STRUCTURE_SHELL OguiAssetRef");

					modern.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(new C2SRequestAssetPayload(owShellRef.sha256())));
					byte[] owShellBytes = reassembleAssetChunks(GameTestSupport.drainOutboundPackets(modernChRef.get()), owShellRef.sha256());
					OguiStructureShell owShell = decodeShellUnchecked(owShellBytes);
					helper.assertTrue(!owShell.blocks().isEmpty(), "Expected non-empty Overworld 3D shell blocks");
					helper.assertTrue(
						owShell.palette().stream().noneMatch(p -> p.contains("oak_sign")),
						"Expected [SPAWN] anchor sign to be filtered out of 3D preview shell"
					);

					// 4. Cycle preview_dim -> switches to Nether shell
					UUID previewSid = openPreview.sessionId();
					modern.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(
						new C2SWidgetInteractPayload(previewSid, openPreview.revision(), "preview_dim", OguiClickAction.LEFT, "", 0)
					));
					S2CUpdateScreenPayload netherUpdate = findLatestPayload(GameTestSupport.drainOutboundPackets(modernChRef.get()), S2CUpdateScreenPayload.class);
					helper.assertTrue(netherUpdate != null && netherUpdate.snapshot().heroCard() instanceof OguiWidgetDto.StructurePreviewDto,
						"Expected preview_dim cycle to update 3D preview snapshot");
					OguiWidgetDto.StructurePreviewDto heroNether = (OguiWidgetDto.StructurePreviewDto) netherUpdate.snapshot().heroCard();
					helper.assertTrue(heroNether.structureAsset() != null && !heroNether.structureAsset().sha256().equals(owShellRef.sha256()),
						"Expected Nether shell hash to differ from Overworld shell hash");

					// 5. Two-click Create Island confirmation
					modern.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(
						new C2SWidgetInteractPayload(previewSid, netherUpdate.revision(), "create_island_confirm", OguiClickAction.LEFT, "", 0)
					));
					helper.assertTrue(islands.grid().findPrimaryIsland(modernId).isEmpty(),
						"First click on Create Island must require confirmation");
					S2CUpdateScreenPayload armedCreate = findLatestPayload(GameTestSupport.drainOutboundPackets(modernChRef.get()), S2CUpdateScreenPayload.class);
					helper.assertTrue(armedCreate != null && ("confirm_create_gallery_classic").equals(armedCreate.snapshot().pendingConfirmKey()),
						"Expected pendingConfirmKey set after first click");

					modern.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(
						new C2SWidgetInteractPayload(previewSid, armedCreate.revision(), "create_island_confirm", OguiClickAction.LEFT, "", 0)
					));
					Island createdModern = islands.grid().findPrimaryIsland(modernId).orElse(null);
					helper.assertTrue(createdModern != null && "gallery_classic".equals(createdModern.bundleId()),
						"Second click must create island with gallery_classic");
					modernIslandRef.set(createdModern.islandId());
					for (int t = 0; t < 20 && createdModern.isPasting(); t++) {
						coreApi.blueprints().tick(server);
					}
					helper.assertTrue(OguiSessionManager.instance().activeSession(modernId) == null,
						"Expected session to close after creation");

					// 6. /is create while already owning an island must reject cleanly without opening gallery
					server.getCommands().performPrefixedCommand(modern.createCommandSourceStack(), "is create");
					helper.assertTrue(OguiSessionManager.instance().activeSession(modernId) == null,
						"Expected /is create to reject when player already has an island without opening gallery");

					// 7. /is reset (no args) opens blueprint gallery in reset mode & /isadmin reload updates live
					server.getCommands().performPrefixedCommand(modern.createCommandSourceStack(), "is reset");
					S2COpenScreenPayload openResetGallery = findLatestPayload(GameTestSupport.drainOutboundPackets(modernChRef.get()), S2COpenScreenPayload.class);
					helper.assertTrue(openResetGallery != null && GuiService.CREATE_GALLERY_SCREEN_ID.equals(openResetGallery.snapshot().screenId()),
						"Expected /is reset (no args) to open blueprint gallery menu");

					synthesizeMultiDimPreviewBundle(ogOverworld, reloadedDir, "gallery_reloaded", "Gallery Reloaded", 3, reloadedPng);
					server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "isadmin reload");
					S2CUpdateScreenPayload reloadedUpdate = findLatestPayload(GameTestSupport.drainOutboundPackets(modernChRef.get()), S2CUpdateScreenPayload.class);
					helper.assertTrue(
						reloadedUpdate != null && reloadedUpdate.snapshot().entries().stream()
							.filter(OguiWidgetDto.ButtonDto.class::isInstance)
							.map(OguiWidgetDto.ButtonDto.class::cast)
							.anyMatch(b -> "Gallery Reloaded".equals(b.label().getString()) && b.previewImage() != null),
						"Expected /isadmin reload to register new PNG asset and live-refresh open gallery"
					);

					modern.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(new C2SCloseNotifyPayload(openResetGallery.sessionId())));

					// 8. Vanilla player fallback: click Gallery Classic in ChestMenu -> Create Island 2-click confirm -> /is reset opens ChestMenu
					ChestMenu vGallery = (ChestMenu) vanilla.containerMenu;
					int vCardSlot = GameTestSupport.findContentSlotByName(vGallery, "Gallery Classic");
					helper.assertTrue(vCardSlot >= 0, "Expected Gallery Classic inside vanilla /is create ChestMenu");
					vGallery.clicked(vCardSlot, 0, ContainerInput.PICKUP, vanilla);
					helper.assertTrue(vanilla.containerMenu instanceof ChestMenu, "Expected 3D preview vanilla ChestMenu fallback");
					ChestMenu vPreview = (ChestMenu) vanilla.containerMenu;
					int vCreateSlot = GameTestSupport.findContainerSlotByName(vPreview, "Create Island");
					helper.assertTrue(vCreateSlot >= 0, "Expected Create Island slot in vanilla preview menu");
					vPreview.clicked(vCreateSlot, 0, ContainerInput.PICKUP, vanilla);
					helper.assertTrue(islands.grid().findPrimaryIsland(vanillaId).isEmpty(), "Expected vanilla first click to arm confirmation");
					vPreview.clicked(vCreateSlot, 0, ContainerInput.PICKUP, vanilla);
					Island createdVanilla = islands.grid().findPrimaryIsland(vanillaId).orElse(null);
					helper.assertTrue(createdVanilla != null, "Expected vanilla second click to create island");
					vanillaIslandRef.set(createdVanilla.islandId());
					for (int t = 0; t < 20 && createdVanilla.isPasting(); t++) {
						coreApi.blueprints().tick(server);
					}

					server.getCommands().performPrefixedCommand(vanilla.createCommandSourceStack(), "is reset");
					helper.assertTrue(vanilla.containerMenu instanceof OguiChestMenu, "Expected vanilla /is reset to open OguiChestMenu");

					cleanup.run();
				}))
				.thenSucceed();
		});
	}

	private static OguiStructureShell decodeShellUnchecked(byte[] bytes) {
		try {
			return OguiStructureShell.fromCompressedBytes(bytes);
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	private static void synthesizeMultiDimPreviewBundle(
		ServerLevel level,
		Path bundleDir,
		String id,
		String displayName,
		int order,
		byte[] pngBytes
	) {
		try {
			Files.createDirectories(bundleDir);
			Files.write(bundleDir.resolve("preview.png"), pngBytes);

			BlockPos scratch = new BlockPos(99_300 + order * 16, 120, 99_300);
			try {
				level.setBlock(scratch, Blocks.STONE.defaultBlockState(), 18);
				level.setBlock(scratch.above(), Blocks.OAK_SIGN.defaultBlockState(), 18);
				if (level.getBlockEntity(scratch.above()) instanceof SignBlockEntity sign) {
					SignText front = sign.getText(SignTextSlot.FRONT).asMutable()
						.setLine(0, net.minecraft.network.chat.Component.literal("[SPAWN]"))
						.asImmutable();
					sign.setText(front, SignTextSlot.FRONT);
				}
				StructureTemplate owTemplate = new StructureTemplate();
				owTemplate.fillFromWorld(level, scratch, new Vec3i(1, 2, 1), false, List.of());
				NbtIo.writeCompressed(owTemplate.save(new CompoundTag()), bundleDir.resolve("overworld.nbt"));

				level.removeBlockEntity(scratch.above());
				level.setBlock(scratch.above(), Blocks.AIR.defaultBlockState(), 18);
				level.setBlock(scratch, Blocks.NETHERRACK.defaultBlockState(), 18);
				StructureTemplate netherTemplate = new StructureTemplate();
				netherTemplate.fillFromWorld(level, scratch, new Vec3i(1, 1, 1), false, List.of());
				NbtIo.writeCompressed(netherTemplate.save(new CompoundTag()), bundleDir.resolve("nether.nbt"));
			} finally {
				level.removeBlockEntity(scratch.above());
				level.setBlock(scratch.above(), Blocks.AIR.defaultBlockState(), 818);
				level.setBlock(scratch, Blocks.AIR.defaultBlockState(), 818);
			}

			Files.writeString(bundleDir.resolve("bundle.json"), """
				{
				  "id": "%s",
				  "displayName": "%s",
				  "description": "3D Preview GameTest Starter",
				  "iconItem": "minecraft:grass_block",
				  "previewImage": "preview.png",
				  "order": %d,
				  "overworld": { "file": "overworld.nbt", "offsetY": 0 },
				  "nether": { "file": "nether.nbt", "offsetY": 0 }
				}
				""".formatted(id, displayName, order));
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	private static byte[] reassembleAssetChunks(List<Packet<?>> packets, String expectedSha256) {
		try {
			List<S2CAssetChunkPayload> chunks = new ArrayList<>();
			for (Packet<?> packet : packets) {
				if (packet instanceof ClientboundCustomPayloadPacket custom
					&& custom.payload() instanceof S2CAssetChunkPayload chunk
					&& expectedSha256.equals(chunk.sha256())) {
					chunks.add(chunk);
				}
			}
			if (chunks.isEmpty()) {
				return new byte[0];
			}
			int totalChunks = chunks.getFirst().totalChunks();
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			for (int i = 0; i < totalChunks; i++) {
				int idx = i;
				S2CAssetChunkPayload part = chunks.stream().filter(c -> c.chunkIndex() == idx).findFirst().orElse(null);
				if (part == null) {
					return new byte[0];
				}
				out.write(part.data());
			}
			byte[] result = out.toByteArray();
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(result);
			if (!expectedSha256.equals(HexFormat.of().formatHex(digest))) {
				return new byte[0];
			}
			return result;
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}

	private static void registerOguiClientChannels(ServerPlayer player) {
		player.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(new RegistrationPayload(
			RegistrationPayload.REGISTER,
			List.of(
				S2CServerHelloPayload.TYPE.id(),
				S2COpenScreenPayload.TYPE.id(),
				S2CUpdateScreenPayload.TYPE.id(),
				S2CCloseScreenPayload.TYPE.id(),
				S2CFeedbackPayload.TYPE.id(),
				S2CAssetChunkPayload.TYPE.id(),
				S2CAssetUnavailablePayload.TYPE.id()
			)
		)));
	}

	private static <T> T findLatestPayload(List<Packet<?>> packets, Class<T> type) {
		T last = null;
		for (Packet<?> packet : packets) {
			if (packet instanceof ClientboundCustomPayloadPacket custom && type.isInstance(custom.payload())) {
				last = type.cast(custom.payload());
			}
		}
		return last;
	}
}
