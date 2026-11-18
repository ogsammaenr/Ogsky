package me.ogsammaenr.ogsky.panel;

import me.ogsammaenr.ogsky.core.api.blueprint.BlueprintDimension;
import me.ogsammaenr.ogsky.core.internal.OgskyCoreApiImpl;
import me.ogsammaenr.ogsky.core.internal.config.CoreConfig;
import me.ogsammaenr.ogsky.core.internal.island.CoreIslandRepository;
import me.ogsammaenr.ogui.api.OguiAssetKind;
import me.ogsammaenr.ogui.api.OguiAssetRef;
import me.ogsammaenr.ogui.api.OguiStructureShell;
import me.ogsammaenr.ogui.runtime.OguiSessionManager;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelBlueprintAssetManagerTest {
	@TempDir
	Path tempDir;

	private OgskyCoreApiImpl core;
	private Path blueprintsDir;
	private PanelBlueprintAssetManager manager;

	@BeforeEach
	void setUp() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();

		this.blueprintsDir = tempDir.resolve("blueprints");
		this.core = new OgskyCoreApiImpl(CoreConfig.defaults(), blueprintsDir);
		this.core.sqliteDatabases().registerModule(CoreIslandRepository.MODULE_ID, CoreIslandRepository.MIGRATIONS);
		this.core.freezeRegistries();
		this.core.sqliteDatabases().openAllForRoot(tempDir.resolve("data"));
		this.core.coreIslands().loadFromDatabase();
		this.core.blueprints().onServerStarting();

		this.manager = new PanelBlueprintAssetManager(core);
	}

	@AfterEach
	void tearDown() {
		core.sqliteDatabases().closeAll();
	}

	@Test
	void registersPngAnd3DStructureShellsAndRefreshesOnRevisionBump() throws Exception {
		Path starterDir = blueprintsDir.resolve("starter");
		Files.createDirectories(starterDir);
		byte[] pngV1 = createTinyPng(0xFF22C55E);
		Files.write(starterDir.resolve("preview.png"), pngV1);
		NbtIo.writeCompressed(
			singleBlockStructure("minecraft:grass_block"),
			starterDir.resolve("overworld.nbt")
		);
		NbtIo.writeCompressed(
			singleBlockStructure("minecraft:netherrack"),
			starterDir.resolve("nether.nbt")
		);

		Path iconOnlyDir = blueprintsDir.resolve("icon_only");
		Files.createDirectories(iconOnlyDir);
		NbtIo.writeCompressed(emptyStructureTag(), iconOnlyDir.resolve("overworld.nbt"));

		core.blueprints().registry().reload();

		OguiAssetRef pngRef1 = manager.previewPng("starter");
		assertNotNull(pngRef1);
		assertEquals(OguiAssetKind.PNG, pngRef1.kind());
		assertTrue(OguiSessionManager.instance().findAssetBytes(pngRef1).isPresent());

		OguiAssetRef owRef1 = manager.structureShell("starter", BlueprintDimension.OVERWORLD);
		assertNotNull(owRef1);
		assertEquals(OguiAssetKind.STRUCTURE_SHELL, owRef1.kind());
		byte[] owBytes = OguiSessionManager.instance().findAssetBytes(owRef1).orElseThrow();
		OguiStructureShell owShell = OguiStructureShell.fromCompressedBytes(owBytes);
		assertEquals(1, owShell.blocks().size());

		OguiAssetRef netherRef1 = manager.structureShell("starter", BlueprintDimension.NETHER);
		assertNotNull(netherRef1);
		assertNotEquals(owRef1.sha256(), netherRef1.sha256());

		assertNull(manager.previewPng("icon_only"));
		assertNull(manager.structureShell("icon_only", BlueprintDimension.OVERWORLD), "Empty 0-block template must not register invalid shell");

		// Replace starter preview.png and bump revision via reload
		byte[] pngV2 = createTinyPng(0xFFEF4444);
		Files.write(starterDir.resolve("preview.png"), pngV2);
		core.blueprints().registry().reload();

		OguiAssetRef pngRef2 = manager.previewPng("starter");
		assertNotNull(pngRef2);
		assertNotEquals(pngRef1.sha256(), pngRef2.sha256());
	}

	private static CompoundTag singleBlockStructure(String blockId) {
		CompoundTag root = new CompoundTag();
		ListTag size = new ListTag();
		size.add(IntTag.valueOf(1));
		size.add(IntTag.valueOf(1));
		size.add(IntTag.valueOf(1));
		root.put("size", size);

		ListTag palette = new ListTag();
		CompoundTag entry = new CompoundTag();
		entry.putString("id", blockId);
		palette.add(entry);
		root.put("palette", palette);

		ListTag blocks = new ListTag();
		CompoundTag blk = new CompoundTag();
		ListTag pos = new ListTag();
		pos.add(IntTag.valueOf(0));
		pos.add(IntTag.valueOf(0));
		pos.add(IntTag.valueOf(0));
		blk.put("pos", pos);
		blk.putInt("state", 0);
		blocks.add(blk);
		root.put("blocks", blocks);
		root.put("entities", new ListTag());
		return root;
	}

	private static CompoundTag emptyStructureTag() {
		CompoundTag root = new CompoundTag();
		ListTag size = new ListTag();
		size.add(IntTag.valueOf(1));
		size.add(IntTag.valueOf(1));
		size.add(IntTag.valueOf(1));
		root.put("size", size);
		root.put("palette", new ListTag());
		root.put("blocks", new ListTag());
		root.put("entities", new ListTag());
		return root;
	}

	private static byte[] createTinyPng(int rgba) throws Exception {
		ByteArrayOutputStream raw = new ByteArrayOutputStream();
		raw.write(0);
		raw.write((rgba >> 16) & 0xFF);
		raw.write((rgba >> 8) & 0xFF);
		raw.write(rgba & 0xFF);
		raw.write((rgba >> 24) & 0xFF);

		Deflater deflater = new Deflater();
		deflater.setInput(raw.toByteArray());
		deflater.finish();
		ByteArrayOutputStream compressed = new ByteArrayOutputStream();
		byte[] buf = new byte[64];
		while (!deflater.finished()) {
			compressed.write(buf, 0, deflater.deflate(buf));
		}
		deflater.end();

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(new byte[] { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A });
		byte[] ihdr = new byte[13];
		DataOutputStream ihdrDos = new DataOutputStream(new ByteArrayOutputStream());
		ByteArrayOutputStream ihdrBaos = new ByteArrayOutputStream();
		DataOutputStream d = new DataOutputStream(ihdrBaos);
		d.writeInt(1);
		d.writeInt(1);
		d.writeByte(8);
		d.writeByte(6);
		d.writeByte(0);
		d.writeByte(0);
		d.writeByte(0);
		ihdr = ihdrBaos.toByteArray();
		writeChunk(out, "IHDR", ihdr);
		writeChunk(out, "IDAT", compressed.toByteArray());
		writeChunk(out, "IEND", new byte[0]);
		return out.toByteArray();
	}

	private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) throws Exception {
		DataOutputStream dos = new DataOutputStream(out);
		dos.writeInt(data.length);
		byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		dos.write(typeBytes);
		dos.write(data);
		CRC32 crc = new CRC32();
		crc.update(typeBytes);
		crc.update(data);
		dos.writeInt((int) crc.getValue());
	}
}
