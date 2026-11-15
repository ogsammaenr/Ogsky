package me.ogsammaenr.ogsky.core;

import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.action.IslandActionDescriptor;
import me.ogsammaenr.ogsky.core.api.blueprint.BlueprintBundleView;
import me.ogsammaenr.ogsky.core.api.db.ModuleDatabase;
import me.ogsammaenr.ogsky.core.api.event.IslandCreatedEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandDeletedEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandLockChangedEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandPreCreateEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandPreDeleteEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandProtectionFlagChangedEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandRangeChangeEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandSettingFlagChangedEvent;
import me.ogsammaenr.ogsky.core.api.event.TeamInviteEvent;
import me.ogsammaenr.ogsky.core.api.event.TeamJoinEvent;
import me.ogsammaenr.ogsky.core.api.event.TeamKickEvent;
import me.ogsammaenr.ogsky.core.api.event.TeamLeaveEvent;
import me.ogsammaenr.ogsky.core.api.event.TeamOwnerChangeEvent;
import me.ogsammaenr.ogsky.core.api.event.TeamRankChangeEvent;
import me.ogsammaenr.ogsky.core.api.island.IslandOperationResult;
import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import me.ogsammaenr.ogsky.core.api.island.IslandService;
import me.ogsammaenr.ogsky.core.api.island.IslandView;
import me.ogsammaenr.ogsky.core.internal.OgskyCoreApiImpl;
import me.ogsammaenr.ogsky.core.internal.config.CoreConfig;
import me.ogsammaenr.ogsky.core.internal.flag.BuiltinFlags;
import me.ogsammaenr.ogsky.core.internal.island.CoreIslandRepository;
import me.ogsammaenr.ogsky.core.internal.island.Island;
import me.ogsammaenr.ogsky.core.internal.island.OperationResult;
import me.ogsammaenr.ogsky.core.internal.island.TeamService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IslandLifecycleAndTeamTest {
	@TempDir
	Path tempDir;

	private OgskyCoreApiImpl api;
	private Path blueprintsDir;

	@BeforeEach
	void setUp() {
		CoreConfig config = CoreConfig.defaults();
		config.membership.maxCoop = 1;
		config.membership.maxTrusted = 2;
		config.membership.inviteTimeoutSeconds = 30;
		this.blueprintsDir = this.tempDir.resolve("blueprints");

		this.api = new OgskyCoreApiImpl(config, this.blueprintsDir);
		this.api.sqliteDatabases().registerModule(CoreIslandRepository.MODULE_ID, CoreIslandRepository.MIGRATIONS);
		this.api.freezeRegistries();
		this.api.sqliteDatabases().openAllForRoot(this.tempDir.resolve("data"));
		this.api.coreIslands().loadFromDatabase();
		this.api.blueprints().onServerStarting();
	}

	@AfterEach
	void tearDown() {
		this.api.sqliteDatabases().closeAll();
	}

	private void createDummyOverworldBundle(String bundleId) throws Exception {
		Path bundleDir = this.blueprintsDir.resolve(bundleId);
		Files.createDirectories(bundleDir);
		NbtIo.writeCompressed(minimalStructureTag(), bundleDir.resolve("overworld.nbt"));
		this.api.blueprints().registry().reload();
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

	private void flushDb() {
		this.api.databases().require(CoreIslandRepository.MODULE_ID).executeAsync(conn -> { }).join();
	}

	@Test
	void createRequiresRealStarterBlueprintAndHonorsPreCreateCancellation() throws Exception {
		UUID owner = UUID.randomUUID();
		OperationResult<Island> missing = this.api.lifecycle().createIsland(null, null, owner, null);
		assertFalse(missing.success());
		assertEquals("missing_blueprint", missing.code());
		assertFalse(Files.exists(this.blueprintsDir.resolve("default").resolve("overworld.nbt")));

		createDummyOverworldBundle("default");

		AtomicBoolean cancelCreate = new AtomicBoolean(true);
		this.api.events().subscribe(IslandPreCreateEvent.class, event -> {
			if (cancelCreate.get()) {
				event.setCancelled(true);
			}
		});

		OperationResult<Island> cancelled = this.api.lifecycle().createIsland(null, null, owner, null);
		assertFalse(cancelled.success());
		assertEquals("cancelled", cancelled.code());
		assertEquals(0, this.api.islands().islandCount());
		assertFalse(this.api.coreIslands().isCellSizeLocked());

		cancelCreate.set(false);
		List<IslandCreatedEvent> createdEvents = new ArrayList<>();
		this.api.events().subscribe(IslandCreatedEvent.class, createdEvents::add);

		OperationResult<Island> created = this.api.lifecycle().createIsland(null, null, owner, null);
		assertTrue(created.success());
		assertTrue(this.api.coreIslands().isCellSizeLocked());
		assertEquals(1, this.api.islands().islandCount());
		assertEquals(1, createdEvents.size());
		assertEquals(owner, createdEvents.get(0).ownerId());

		// Player cannot create a second primary island.
		OperationResult<Island> duplicate = this.api.lifecycle().createIsland(null, null, owner, null);
		assertFalse(duplicate.success());
		assertEquals("already_has_island", duplicate.code());
	}

	@Test
	void lifecycleResizeFlagsLockAndDeleteCascadeCleanly() throws Exception {
		createDummyOverworldBundle("default");
		UUID owner = UUID.randomUUID();
		Island island = this.api.lifecycle().createIsland(null, null, owner, null).value();

		List<IslandRangeChangeEvent> rangeEvents = new ArrayList<>();
		List<IslandLockChangedEvent> lockEvents = new ArrayList<>();
		List<IslandProtectionFlagChangedEvent> protEvents = new ArrayList<>();
		List<IslandSettingFlagChangedEvent> setEvents = new ArrayList<>();
		List<IslandDeletedEvent> deleteEvents = new ArrayList<>();
		this.api.events().subscribe(IslandRangeChangeEvent.class, rangeEvents::add);
		this.api.events().subscribe(IslandLockChangedEvent.class, lockEvents::add);
		this.api.events().subscribe(IslandProtectionFlagChangedEvent.class, protEvents::add);
		this.api.events().subscribe(IslandSettingFlagChangedEvent.class, setEvents::add);
		this.api.events().subscribe(IslandDeletedEvent.class, deleteEvents::add);

		assertFalse(this.api.lifecycle().resizeIsland(island.islandId(), 9999).success());
		OperationResult<Short> resized = this.api.lifecycle().resizeIsland(island.islandId(), 120);
		assertTrue(resized.success());
		assertEquals((short) 120, island.halfSize());
		assertEquals(1, rangeEvents.size());
		assertEquals((short) 50, rangeEvents.get(0).oldHalfSize());
		assertEquals((short) 120, rangeEvents.get(0).newHalfSize());

		assertTrue(this.api.lifecycle().setLock(null, owner, island, true, false).success());
		assertTrue(island.isSettingEnabled(BuiltinFlags.ISLAND_LOCK));
		assertEquals(1, lockEvents.size());
		assertEquals(1, setEvents.size());

		assertTrue(this.api.lifecycle().setProtectionRank(owner, island, BuiltinFlags.BREAK_BLOCKS, IslandRole.COOP, false).success());
		assertEquals(IslandRole.COOP, island.protectionRank(BuiltinFlags.BREAK_BLOCKS));
		assertEquals(1, protEvents.size());

		assertTrue(this.api.lifecycle().setWorldSetting(BuiltinFlags.VOID_TELEPORT, false).success());
		assertFalse(this.api.islands().isWorldSettingEnabled(BuiltinFlags.VOID_TELEPORT));

		// Pre-delete cancellation works
		AtomicBoolean vetoDelete = new AtomicBoolean(true);
		this.api.events().subscribe(IslandPreDeleteEvent.class, event -> {
			if (vetoDelete.get()) {
				event.setCancelled(true);
			}
		});
		assertFalse(this.api.lifecycle().deleteIsland(null, island.islandId(), owner, IslandPreDeleteEvent.DeleteReason.PLAYER_DELETE).success());
		assertEquals(1, this.api.islands().islandCount());

		vetoDelete.set(false);
		assertTrue(this.api.lifecycle().deleteIsland(null, island.islandId(), owner, IslandPreDeleteEvent.DeleteReason.PLAYER_DELETE).success());
		assertEquals(0, this.api.islands().islandCount());
		assertEquals(1, deleteEvents.size());

		flushDb();
		ModuleDatabase db = this.api.databases().require(CoreIslandRepository.MODULE_ID);
		int dbCount = db.queryBlocking(conn -> {
			try (Statement st = conn.createStatement();
				 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM islands WHERE island_id = '" + island.islandId() + "'")) {
				rs.next();
				return rs.getInt(1);
			}
		});
		assertEquals(0, dbCount);
	}

	@Test
	void teamInvitesSecondaryRolesRankTransfersAndBansWork() throws Exception {
		createDummyOverworldBundle("default");
		UUID owner1 = UUID.randomUUID();
		UUID owner2 = UUID.randomUUID();
		UUID owner3 = UUID.randomUUID();
		UUID candidate = UUID.randomUUID();
		UUID helper = UUID.randomUUID();

		Island island1 = this.api.lifecycle().createIsland(null, null, owner1, null).value();
		Island island2 = this.api.lifecycle().createIsland(null, null, owner2, null).value();
		Island island3 = this.api.lifecycle().createIsland(null, null, owner3, null).value();

		TeamService team = this.api.team();
		List<TeamInviteEvent> inviteEvents = new ArrayList<>();
		List<TeamJoinEvent> joinEvents = new ArrayList<>();
		List<TeamRankChangeEvent> rankEvents = new ArrayList<>();
		List<TeamOwnerChangeEvent> ownerEvents = new ArrayList<>();
		List<TeamKickEvent> kickEvents = new ArrayList<>();
		List<TeamLeaveEvent> leaveEvents = new ArrayList<>();
		this.api.events().subscribe(TeamInviteEvent.class, inviteEvents::add);
		this.api.events().subscribe(TeamJoinEvent.class, joinEvents::add);
		this.api.events().subscribe(TeamRankChangeEvent.class, rankEvents::add);
		this.api.events().subscribe(TeamOwnerChangeEvent.class, ownerEvents::add);
		this.api.events().subscribe(TeamKickEvent.class, kickEvents::add);
		this.api.events().subscribe(TeamLeaveEvent.class, leaveEvents::add);

		// Invite -> deny -> invite -> accept
		assertTrue(team.inviteMember(island1, owner1, candidate).success());
		assertTrue(team.denyInvite(candidate).success());
		assertTrue(team.inviteMember(island1, owner1, candidate).success());
		assertTrue(team.acceptInvite(candidate).success());
		assertEquals(IslandRole.MEMBER, island1.roleOf(candidate));
		assertEquals(island1.islandId(), this.api.islands().findPrimaryIsland(candidate).orElseThrow().islandId());

		// Promote -> transfer ownership -> kick previous owner? Wait, demote first -> leave
		assertTrue(team.promoteMember(island1, owner1, candidate, false).success());
		assertEquals(IslandRole.ADMIN, island1.roleOf(candidate));
		assertTrue(team.transferOwnership(island1, owner1, candidate, false).success());
		assertEquals(candidate, island1.ownerId());
		assertEquals(IslandRole.OWNER, island1.roleOf(candidate));
		assertEquals(IslandRole.ADMIN, island1.roleOf(owner1));
		assertTrue(team.demoteMember(island1, candidate, owner1, false).success());
		assertEquals(IslandRole.MEMBER, island1.roleOf(owner1));
		assertTrue(team.leaveIsland(null, owner1, island1).success());
		assertEquals(IslandRole.VISITOR, island1.roleOf(owner1));
		assertTrue(this.api.islands().findPrimaryIsland(owner1).isEmpty());

		// Secondary COOP (max 1) and session disconnect cleanup
		assertTrue(team.addCoop(island1, candidate, helper, false).success());
		assertEquals(IslandRole.COOP, island1.roleOf(helper));
		assertEquals("coop_limit", team.addCoop(island2, owner2, helper, false).code());
		team.onPlayerDisconnect(null, candidate); // owner logs out -> coop cleared
		assertEquals(IslandRole.VISITOR, island1.roleOf(helper));
		assertFalse(leaveEvents.isEmpty());

		// Secondary TRUSTED (max 2)
		assertTrue(team.addTrust(island1, candidate, helper, false).success());
		assertTrue(team.addTrust(island2, owner2, helper, false).success());
		assertEquals("trusted_limit", team.addTrust(island3, owner3, helper, false).code());
		assertTrue(team.removeTrust(null, island2, owner2, helper, false).success());
		assertEquals(IslandRole.VISITOR, island2.roleOf(helper));

		// Ban removes TRUSTED role and rejects subsequent invite until unbanned
		assertTrue(team.banPlayer(null, island1, candidate, helper, false).success());
		assertTrue(island1.isBanned(helper));
		assertEquals(IslandRole.VISITOR, island1.roleOf(helper));
		assertEquals("banned", team.inviteMember(island1, candidate, helper).code());
		assertTrue(team.unbanPlayer(island1, candidate, helper, false).success());
		assertFalse(island1.isBanned(helper));

		assertFalse(inviteEvents.isEmpty());
		assertFalse(joinEvents.isEmpty());
		assertEquals(2, rankEvents.size());
		assertEquals(1, ownerEvents.size());
		assertEquals(1, kickEvents.size());

		flushDb();
	}

	@Test
	void publicIslandServiceMutationsBlueprintsAndBuiltinActionsWork() throws Exception {
		createDummyOverworldBundle("default");
		createDummyOverworldBundle("skycave");
		IslandService publicService = this.api.islands();

		assertEquals(1, publicService.maxCoop());
		assertEquals(2, publicService.maxTrusted());
		assertEquals("default", publicService.defaultBlueprintId());
		List<BlueprintBundleView> blueprints = publicService.availableBlueprints();
		assertEquals(2, blueprints.size());
		assertTrue(blueprints.stream().allMatch(BlueprintBundleView::hasOverworld));

		UUID ownerId = UUID.randomUUID();
		UUID otherId = UUID.randomUUID();
		IslandOperationResult<IslandView> created = publicService.createIsland(null, null, ownerId, "default");
		assertTrue(created.success());
		IslandView view = created.value();
		assertNotNull(view);
		assertNotNull(view.createdAt());

		assertTrue(publicService.setProtectionRank(ownerId, view.islandId(), BuiltinFlags.BREAK_BLOCKS, IslandRole.ADMIN, false).success());
		assertEquals(IslandRole.ADMIN, publicService.findById(view.islandId()).orElseThrow().protectionRank(BuiltinFlags.BREAK_BLOCKS));
		assertTrue(publicService.setSettingFlag(null, ownerId, view.islandId(), BuiltinFlags.ISLAND_LOCK, true, false).success());
		assertTrue(publicService.findById(view.islandId()).orElseThrow().isSettingEnabled(BuiltinFlags.ISLAND_LOCK));

		assertTrue(this.api.team().inviteMember(this.api.coreIslands().grid().findById(view.islandId()).orElseThrow(), ownerId, otherId).success());
		assertTrue(this.api.team().acceptInvite(otherId).success());
		assertEquals(IslandRole.ADMIN, publicService.promoteMember(view.islandId(), ownerId, otherId, false).value());
		assertEquals(IslandRole.MEMBER, publicService.demoteMember(view.islandId(), ownerId, otherId, false).value());
		assertTrue(publicService.banPlayer(null, view.islandId(), ownerId, otherId, false).success());
		assertTrue(publicService.findById(view.islandId()).orElseThrow().isBanned(otherId));
		assertTrue(publicService.unbanPlayer(view.islandId(), ownerId, otherId, false).success());
		assertFalse(publicService.findById(view.islandId()).orElseThrow().isBanned(otherId));

		// Reset through public API (actorId, islandId order must work for owner)
		IslandOperationResult<IslandView> reset = publicService.resetIsland(null, ownerId, view.islandId(), "skycave", false);
		assertTrue(reset.success(), reset.message());
		assertEquals("skycave", reset.value().bundleId());

		// Built-in panel actions
		this.api.registerBuiltinActions();
		List<IslandActionDescriptor> actions = this.api.actions().listOrdered();
		assertEquals(5, actions.size());
		assertEquals(OgskyCore.id("island_home"), actions.getFirst().id());
		assertTrue(actions.getFirst().closePanelOnExecute());
		assertFalse(actions.getLast().closePanelOnExecute());

		flushDb();
	}
}
