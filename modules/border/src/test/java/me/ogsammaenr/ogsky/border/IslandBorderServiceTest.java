package me.ogsammaenr.ogsky.border;

import me.ogsammaenr.ogsky.core.api.action.IslandActionPresentation;
import me.ogsammaenr.ogsky.core.api.gui.IslandSummaryContext;
import me.ogsammaenr.ogsky.core.api.island.IslandOperationResult;
import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import me.ogsammaenr.ogsky.core.api.island.IslandView;
import me.ogsammaenr.ogsky.core.internal.OgskyCoreApiImpl;
import me.ogsammaenr.ogsky.core.internal.config.CoreConfig;
import me.ogsammaenr.ogsky.core.internal.island.CoreIslandRepository;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.border.BorderStatus;
import net.minecraft.world.level.border.WorldBorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IslandBorderServiceTest {
	@TempDir
	Path tempDir;

	private OgskyCoreApiImpl core;
	private IslandBorderService service;

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@BeforeEach
	void setUp() throws Exception {
		Path blueprintsDir = tempDir.resolve("blueprints");
		Path bundleDir = blueprintsDir.resolve("default");
		Files.createDirectories(bundleDir);
		CompoundTag tag = new CompoundTag();
		ListTag size = new ListTag();
		size.add(IntTag.valueOf(1));
		size.add(IntTag.valueOf(1));
		size.add(IntTag.valueOf(1));
		tag.put("size", size);
		tag.put("palette", new ListTag());
		tag.put("blocks", new ListTag());
		tag.put("entities", new ListTag());
		NbtIo.writeCompressed(tag, bundleDir.resolve("overworld.nbt"));

		this.core = new OgskyCoreApiImpl(CoreConfig.defaults(), blueprintsDir);
		this.core.databases().registerModule(CoreIslandRepository.MODULE_ID, CoreIslandRepository.MIGRATIONS);
		this.core.databases().registerModule(BorderRepository.MODULE_ID, BorderRepository.MIGRATIONS);
		this.core.freezeRegistries();
		this.core.sqliteDatabases().openAllForRoot(tempDir.resolve("data"));
		this.core.coreIslands().loadFromDatabase();
		this.core.blueprints().onServerStarting();

		BorderRepository repo = new BorderRepository(this.core.databases());
		this.service = new IslandBorderService(this.core, tempDir.resolve("config").resolve("border.json"), repo);
		new BorderGuiRegistrar(this.core, this.service).registerAll();
	}

	@AfterEach
	void tearDown() {
		this.core.sqliteDatabases().closeAll();
	}

	@Test
	void createsExactWorldBorderGeometryAndColorsForBlueGreenAndRed() {
		UUID ownerId = UUID.randomUUID();
		IslandOperationResult<IslandView> created = core.islands().createIsland(null, null, ownerId, "default");
		assertTrue(created.success(), created.message());
		IslandView island = created.value();

		WorldBorder blue = IslandBorderService.createIslandWorldBorder(island, BorderColor.BLUE, 100L, 0, 0);
		assertEquals(BorderStatus.STATIONARY, blue.getStatus());
		assertEquals(island.centerX() + 0.5D, blue.getCenterX());
		assertEquals(island.centerZ() + 0.5D, blue.getCenterZ());
		assertEquals(island.halfSize() * 2.0D + 1.0D, blue.getSize());
		assertEquals(island.minX(), blue.getMinX(), 1.0E-6D);
		assertEquals(island.maxX() + 1.0D, blue.getMaxX(), 1.0E-6D);
		assertEquals(island.minZ(), blue.getMinZ(), 1.0E-6D);
		assertEquals(island.maxZ() + 1.0D, blue.getMaxZ(), 1.0E-6D);
		assertEquals(0, blue.getWarningBlocks());
		assertEquals(0, blue.getWarningTime());
		assertTrue(blue.isWithinBounds(island.minX(), island.minZ()));
		assertTrue(blue.isWithinBounds(island.maxX() + 0.99D, island.maxZ() + 0.99D));
		assertFalse(blue.isWithinBounds(island.minX() - 0.1D, island.centerZ()));

		WorldBorder green = IslandBorderService.createIslandWorldBorder(island, BorderColor.GREEN, 100L, 0, 0);
		assertEquals(BorderStatus.GROWING, green.getStatus());
		assertEquals(island.minX(), Math.floor(green.getMinX()), 1.0E-6D);
		assertEquals(island.maxX() + 1.0D, Math.ceil(green.getMaxX()), 1.0E-6D);

		WorldBorder red = IslandBorderService.createIslandWorldBorder(island, BorderColor.RED, 100L, 0, 0);
		assertEquals(BorderStatus.SHRINKING, red.getStatus());
		// After ticking forward, size stays strictly inside [diameter - 1e-4, diameter] preserving exact block box.
		red.tick();
		assertEquals(island.minX(), Math.floor(red.getMinX()), 1.0E-6D);
		assertEquals(island.maxX() + 1.0D, Math.ceil(red.getMaxX()), 1.0E-6D);
	}

	@Test
	void actionPresenterAndSummaryReflectPersistedPreferences() {
		UUID ownerId = UUID.randomUUID();
		IslandView island = core.islands().createIsland(null, null, ownerId, "default").value();

		IslandActionPresentation initialPres = core.actions()
			.present(BorderGuiRegistrar.ACTION_TOGGLE_BORDER, ownerId, island)
			.orElseThrow();
		assertEquals(Boolean.FALSE, initialPres.activeState());
		assertEquals("OFF", initialPres.badgeLabel());
		assertEquals("Island Border: OFF", initialPres.title());

		List<String> offSummary = core.guis()
			.collectSummaryLines(new IslandSummaryContext(island.islandId(), ownerId, IslandRole.OWNER, false))
			.stream()
			.map(Component::getString)
			.toList();
		assertTrue(offSummary.stream().anyMatch(s -> s.contains("Border: OFF")));

		service.adminSetEnabled(null, ownerId, "Owner", true);
		IslandActionPresentation enabledPres = core.actions()
			.present(BorderGuiRegistrar.ACTION_TOGGLE_BORDER, ownerId, island)
			.orElseThrow();
		assertEquals(Boolean.TRUE, enabledPres.activeState());
		assertEquals("ON", enabledPres.badgeLabel());
		assertEquals("Island Border: ON", enabledPres.title());

		List<String> onSummary = core.guis()
			.collectSummaryLines(new IslandSummaryContext(island.islandId(), ownerId, IslandRole.OWNER, false))
			.stream()
			.map(Component::getString)
			.toList();
		assertTrue(onSummary.stream().anyMatch(s -> s.contains("Border: ON (Blue)")));
	}
}
