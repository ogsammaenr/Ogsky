package me.ogsammaenr.ogsky.panel;

import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.action.IslandActionDescriptor;
import me.ogsammaenr.ogsky.core.api.db.ModuleDatabase;
import me.ogsammaenr.ogsky.core.api.flag.FlagMode;
import me.ogsammaenr.ogsky.core.api.island.IslandOperationResult;
import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import me.ogsammaenr.ogsky.core.api.island.IslandView;
import me.ogsammaenr.ogsky.core.internal.OgskyCoreApiImpl;
import me.ogsammaenr.ogsky.core.internal.config.CoreConfig;
import me.ogsammaenr.ogsky.core.internal.db.SqliteDatabaseService;
import me.ogsammaenr.ogsky.core.internal.island.CoreIslandRepository;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelViewModelServiceTest {
	@TempDir
	Path tempDir;

	private OgskyCoreApiImpl core;
	private Path blueprintsDir;
	private PanelPreferenceRepository preferences;
	private PanelViewModelService viewModels;

	@BeforeEach
	void setUp() {
		CoreConfig config = CoreConfig.defaults();
		this.blueprintsDir = tempDir.resolve("blueprints");
		this.core = new OgskyCoreApiImpl(config, blueprintsDir);
		this.core.sqliteDatabases().registerModule(CoreIslandRepository.MODULE_ID, CoreIslandRepository.MIGRATIONS);
		this.core.databases().registerModule(PanelPreferenceRepository.MODULE_ID, PanelPreferenceRepository.MIGRATIONS);
		this.core.freezeRegistries();
		this.core.sqliteDatabases().openAllForRoot(tempDir.resolve("data"));
		this.core.coreIslands().loadFromDatabase();
		this.core.blueprints().onServerStarting();

		this.preferences = new PanelPreferenceRepository(core.databases());
		this.viewModels = new PanelViewModelService(core);
	}

	@AfterEach
	void tearDown() {
		core.sqliteDatabases().closeAll();
	}

	private void createDummyBundle(String id, boolean withNether) throws Exception {
		Path bundleDir = blueprintsDir.resolve(id);
		Files.createDirectories(bundleDir);
		NbtIo.writeCompressed(minimalStructureTag(), bundleDir.resolve("overworld.nbt"));
		if (withNether) {
			NbtIo.writeCompressed(minimalStructureTag(), bundleDir.resolve("nether.nbt"));
		}
		core.blueprints().registry().reload();
	}

	private static CompoundTag minimalStructureTag() {
		CompoundTag tag = new CompoundTag();
		ListTag size = new ListTag();
		size.add(IntTag.valueOf(1));
		size.add(IntTag.valueOf(1));
		size.add(IntTag.valueOf(1));
		tag.put("size", size);
		tag.put("palette", new ListTag());
		tag.put("blocks", new ListTag());
		tag.put("entities", new ListTag());
		return tag;
	}

	private IslandView createIsland(UUID ownerId, String bundleId) {
		IslandOperationResult<IslandView> res = core.islands().createIsland(null, null, ownerId, bundleId);
		assertTrue(res.success(), res.message());
		assertNotNull(res.value());
		return res.value();
	}

	@Test
	void snapshotReflectsDynamicallyRegisteredModuleActionsAndNullIslandBehavior() throws Exception {
		createDummyBundle("default", false);
		UUID ownerId = UUID.randomUUID();
		IslandView island = createIsland(ownerId, "default");

		assertEquals(List.of(), viewModels.buildSnapshot(island.islandId()).entries());

		core.actions().register(new IslandActionDescriptor(
			OgskyCore.id("island_level"),
			"Island Level",
			"Check level",
			"minecraft:experience_bottle",
			20,
			false,
			ctx -> { }
		));
		core.actions().register(new IslandActionDescriptor(
			OgskyCore.id("island_home"),
			"Teleport Home",
			"Go home",
			"minecraft:oak_door",
			10,
			true,
			ctx -> { }
		));

		PanelSnapshot snapshot = viewModels.buildSnapshot(island.islandId());
		assertEquals(
			List.of("ogsky:island_home", "ogsky:island_level"),
			snapshot.entries().stream().map(e -> e.actionId().toString()).toList()
		);
		assertTrue(snapshot.actions().getFirst().closePanelOnExecute());
		assertFalse(snapshot.actions().getLast().closePanelOnExecute());
		assertNotNull(snapshot.island());

		// Dynamic toggle_lock action state follows island_lock setting flag
		core.actions().register(new IslandActionDescriptor(
			OgskyCore.id("island_toggle_lock"),
			"Toggle Island Lock",
			"Static fallback description",
			"minecraft:iron_door",
			30,
			false,
			ctx -> { }
		));
		PanelSnapshot unlockedActions = viewModels.buildSnapshot(island.islandId());
		PanelSnapshot.ActionEntry unlockedLockEntry = unlockedActions.actions().stream()
			.filter(a -> a.actionId().equals(OgskyCore.id("island_toggle_lock")))
			.findFirst()
			.orElseThrow();
		assertEquals(Boolean.FALSE, unlockedLockEntry.activeState());
		assertEquals("Island Lock: UNLOCKED", unlockedLockEntry.title());
		assertEquals("minecraft:oak_door", unlockedLockEntry.iconItemId());

		assertTrue(core.islands().setSettingFlag(null, ownerId, island.islandId(), OgskyCore.id("island_lock"), true, false).success());
		PanelSnapshot lockedActions = viewModels.buildSnapshot(island.islandId());
		PanelSnapshot.ActionEntry lockedLockEntry = lockedActions.actions().stream()
			.filter(a -> a.actionId().equals(OgskyCore.id("island_toggle_lock")))
			.findFirst()
			.orElseThrow();
		assertEquals(Boolean.TRUE, lockedLockEntry.activeState());
		assertEquals("Island Lock: LOCKED", lockedLockEntry.title());
		assertEquals("minecraft:iron_bars", lockedLockEntry.iconItemId());

		// When islandId is null (islandless player), actions tab stays empty while blueprints tab shows starter bundles
		PanelSnapshot noIslandActions = viewModels.buildSnapshot(
			null,
			ownerId,
			false,
			PanelTab.ACTIONS,
			FlagModeFilter.BASIC,
			0,
			UUID::toString,
			bundle -> true
		);
		assertNull(noIslandActions.island());
		assertTrue(noIslandActions.actions().isEmpty());

		PanelSnapshot noIslandBlueprints = viewModels.buildSnapshot(
			null,
			ownerId,
			false,
			PanelTab.BLUEPRINTS_INFO,
			FlagModeFilter.BASIC,
			0,
			UUID::toString,
			bundle -> true
		);
		assertEquals(1, noIslandBlueprints.blueprints().size());
		assertEquals("default", noIslandBlueprints.blueprints().getFirst().id());
	}

	@Test
	void protectionAndSettingFlagsSupportModeFilteringPaginationAndRoleEditability() throws Exception {
		createDummyBundle("default", false);
		UUID ownerId = UUID.randomUUID();
		UUID memberId = UUID.randomUUID();
		UUID strangerId = UUID.randomUUID();
		IslandView island = createIsland(ownerId, "default");
		assertTrue(core.team().inviteMember(core.coreIslands().grid().findById(island.islandId()).orElseThrow(), ownerId, memberId).success());
		assertTrue(core.team().acceptInvite(memberId).success());

		// BASIC protection flags fit in <= 1 page and all entries carry FlagMode.BASIC
		PanelSnapshot basicFlags = viewModels.buildSnapshot(
			island.islandId(),
			ownerId,
			false,
			PanelTab.PROTECTION_FLAGS,
			FlagModeFilter.BASIC,
			0,
			UUID::toString,
			bundle -> true
		);
		assertTrue(basicFlags.protectionFlags().stream().allMatch(f -> f.mode() == FlagMode.BASIC));
		assertTrue(basicFlags.protectionFlags().stream().allMatch(PanelSnapshot.ProtectionFlagEntry::editable));
		assertEquals(0, basicFlags.page());

		// ADVANCED protection flags include all 34 built-in flags -> 2 pages (28 + 6)
		PanelSnapshot advPage0 = viewModels.buildSnapshot(
			island.islandId(),
			ownerId,
			false,
			PanelTab.PROTECTION_FLAGS,
			FlagModeFilter.ADVANCED,
			0,
			UUID::toString,
			bundle -> true
		);
		assertEquals(34, advPage0.totalEntries());
		assertEquals(2, advPage0.totalPages());
		assertEquals(PanelSnapshot.PAGE_SIZE, advPage0.protectionFlags().size());
		assertTrue(advPage0.hasNextPage());
		assertFalse(advPage0.hasPreviousPage());

		PanelSnapshot advPage1 = viewModels.buildSnapshot(
			island.islandId(),
			ownerId,
			false,
			PanelTab.PROTECTION_FLAGS,
			FlagModeFilter.ADVANCED,
			1,
			UUID::toString,
			bundle -> true
		);
		assertEquals(6, advPage1.protectionFlags().size());
		assertFalse(advPage1.hasNextPage());
		assertTrue(advPage1.hasPreviousPage());

		// Member cannot edit settings; admin-bypass visitor CAN edit settings
		PanelSnapshot memberSettings = viewModels.buildSnapshot(
			island.islandId(),
			memberId,
			false,
			PanelTab.SETTING_FLAGS,
			FlagModeFilter.ADVANCED,
			0,
			UUID::toString,
			bundle -> true
		);
		assertEquals(10, memberSettings.settingFlags().size());
		assertTrue(memberSettings.settingFlags().stream().noneMatch(PanelSnapshot.SettingFlagEntry::editable));

		PanelSnapshot bypassSettings = viewModels.buildSnapshot(
			island.islandId(),
			strangerId,
			true,
			PanelTab.SETTING_FLAGS,
			FlagModeFilter.ADVANCED,
			0,
			UUID::toString,
			bundle -> true
		);
		assertTrue(bypassSettings.settingFlags().stream().allMatch(PanelSnapshot.SettingFlagEntry::editable));
	}

	@Test
	void membersAndBlueprintsTabsReflectRolesCapabilitiesAndBundlePermissions() throws Exception {
		createDummyBundle("default", true);
		createDummyBundle("vip_sky", false);

		UUID ownerId = UUID.randomUUID();
		UUID adminId = UUID.randomUUID();
		UUID memberId = UUID.randomUUID();
		UUID coopId = UUID.randomUUID();
		UUID bannedId = UUID.randomUUID();

		IslandView view = createIsland(ownerId, "default");
		var internalIsland = core.coreIslands().grid().findById(view.islandId()).orElseThrow();
		assertTrue(core.team().inviteMember(internalIsland, ownerId, adminId).success());
		assertTrue(core.team().acceptInvite(adminId).success());
		assertTrue(core.islands().promoteMember(view.islandId(), ownerId, adminId, false).success());
		assertTrue(core.team().inviteMember(internalIsland, ownerId, memberId).success());
		assertTrue(core.team().acceptInvite(memberId).success());
		assertTrue(core.team().addCoop(internalIsland, ownerId, coopId, false).success());
		assertTrue(core.islands().banPlayer(null, view.islandId(), ownerId, bannedId, false).success());

		Map<UUID, String> names = Map.of(
			ownerId, "Owner",
			adminId, "AdminUser",
			memberId, "MemberUser",
			coopId, "CoopUser",
			bannedId, "BannedUser"
		);

		PanelSnapshot ownerMembers = viewModels.buildSnapshot(
			view.islandId(),
			ownerId,
			false,
			PanelTab.MEMBERS,
			FlagModeFilter.BASIC,
			0,
			id -> names.getOrDefault(id, id.toString()),
			bundle -> true
		);
		assertEquals(5, ownerMembers.members().size());
		assertEquals(IslandRole.OWNER, ownerMembers.members().get(0).role());
		assertFalse(ownerMembers.members().get(0).canRemoveOrKick());
		assertEquals(IslandRole.ADMIN, ownerMembers.members().get(1).role());
		assertTrue(ownerMembers.members().get(1).canDemote());
		assertTrue(ownerMembers.members().get(1).canRemoveOrKick());
		assertEquals(IslandRole.MEMBER, ownerMembers.members().get(2).role());
		assertTrue(ownerMembers.members().get(2).canPromote());
		assertEquals(PanelSnapshot.MemberEntry.Kind.SECONDARY_HELPER, ownerMembers.members().get(3).kind());
		assertTrue(ownerMembers.members().get(3).canRemoveOrKick());
		assertEquals(PanelSnapshot.MemberEntry.Kind.BANNED_PLAYER, ownerMembers.members().get(4).kind());
		assertTrue(ownerMembers.members().get(4).canUnban());

		PanelSnapshot blueprints = viewModels.buildSnapshot(
			view.islandId(),
			ownerId,
			false,
			PanelTab.BLUEPRINTS_INFO,
			FlagModeFilter.BASIC,
			0,
			UUID::toString,
			bundle -> !bundle.id().equals("vip_sky")
		);
		assertEquals(2, blueprints.blueprints().size());
		PanelSnapshot.BlueprintEntry defEntry = blueprints.blueprints().stream()
			.filter(b -> b.id().equals("default"))
			.findFirst()
			.orElseThrow();
		assertTrue(defEntry.activeOnIsland());
		assertTrue(defEntry.defaultBundle());
		assertTrue(defEntry.hasOverworld());
		assertTrue(defEntry.hasNether());
		assertTrue(defEntry.permitted());

		PanelSnapshot.BlueprintEntry vipEntry = blueprints.blueprints().stream()
			.filter(b -> b.id().equals("vip_sky"))
			.findFirst()
			.orElseThrow();
		assertFalse(vipEntry.activeOnIsland());
		assertFalse(vipEntry.permitted());
	}

	@Test
	void asyncPanelPreferencesMigrateFromV1AndPersistTabAndFlagMode() {
		Path migrationRoot = tempDir.resolve("panel_v1_migration");
		UUID legacyPlayer = UUID.randomUUID();
		UUID newPlayer = UUID.randomUUID();

		// 1. Simulate a pre-existing v1 panel.db from Phase 0
		SqliteDatabaseService legacyDbs = new SqliteDatabaseService();
		legacyDbs.registerModule(PanelPreferenceRepository.MODULE_ID, List.of(PanelPreferenceRepository.MIGRATIONS.getFirst()));
		legacyDbs.openAllForRoot(migrationRoot);
		ModuleDatabase legacyDb = legacyDbs.require(PanelPreferenceRepository.MODULE_ID);
		legacyDb.executeBlocking(conn -> {
			try (PreparedStatement stmt = conn.prepareStatement(
				"INSERT INTO panel_preferences (player_uuid, ui_mode, updated_at) VALUES (?, ?, ?)"
			)) {
				stmt.setString(1, legacyPlayer.toString());
				stmt.setString(2, "CHEST_MENU");
				stmt.setString(3, "2026-01-01T00:00:00Z");
				stmt.executeUpdate();
			}
		});
		legacyDbs.closeAll();

		// 2. Reopen with full v1+v2 migrations and verify defaults + async persistence
		SqliteDatabaseService upgradedDbs = new SqliteDatabaseService();
		upgradedDbs.registerModule(PanelPreferenceRepository.MODULE_ID, PanelPreferenceRepository.MIGRATIONS);
		upgradedDbs.openAllForRoot(migrationRoot);
		try {
			PanelPreferenceRepository repo = new PanelPreferenceRepository(upgradedDbs);

			PanelPreferences migrated = repo.loadOrCreateAsync(legacyPlayer).join();
			assertEquals("CHEST_MENU", migrated.uiMode());
			assertEquals(PanelTab.ACTIONS, migrated.preferredTab());
			assertEquals(FlagModeFilter.BASIC, migrated.flagMode());

			repo.saveTabAsync(legacyPlayer, PanelTab.MEMBERS).join();
			repo.saveFlagModeAsync(legacyPlayer, FlagModeFilter.ADVANCED).join();
			PanelPreferences inserted = repo.loadOrCreateAsync(newPlayer).join();
			assertEquals(PanelTab.ACTIONS, inserted.preferredTab());

			repo.evict(legacyPlayer);
			PanelPreferences reloaded = repo.loadOrCreateAsync(legacyPlayer).join();
			assertEquals(PanelTab.MEMBERS, reloaded.preferredTab());
			assertEquals(FlagModeFilter.ADVANCED, reloaded.flagMode());

			Integer version = upgradedDbs.require(PanelPreferenceRepository.MODULE_ID).queryBlocking(conn -> {
				try (Statement st = conn.createStatement();
					 ResultSet rs = st.executeQuery("SELECT MAX(version) FROM ogsky_schema_migrations")) {
					rs.next();
					return rs.getInt(1);
				}
			});
			assertEquals(2, version);
		} finally {
			upgradedDbs.closeAll();
		}
	}

	@Test
	void islandSummaryIncludesContributedModuleLinesInSortOrder() throws Exception {
		createDummyBundle("default", false);
		UUID ownerId = UUID.randomUUID();
		IslandView island = createIsland(ownerId, "default");

		core.guis().registerSummaryContributor(
			OgskyCore.id("second_metric"),
			20,
			ctx -> List.of(Component.literal("Rank: #3"))
		);
		core.guis().registerSummaryContributor(
			OgskyCore.id("first_metric"),
			10,
			ctx -> List.of(Component.literal("Level: 42 (" + ctx.viewerRole() + ")"))
		);

		PanelSnapshot snapshot = viewModels.buildSnapshot(island.islandId());
		assertNotNull(snapshot.island());
		assertEquals(
			List.of("Level: 42 (" + IslandRole.OWNER + ")", "Rank: #3"),
			snapshot.island().contributedLines().stream().map(Component::getString).toList()
		);
	}
}
