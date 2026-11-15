package me.ogsammaenr.ogsky.core.internal.blueprint;

import com.mojang.datafixers.DataFixer;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Eagerly loaded, GZIP-compressed in-memory representation of a {@code .nbt} blueprint template,
 * with pre-scanned structural metadata (template dimensions, {@code minecraft:bedrock} center anchor,
 * and optional {@code [SPAWN]} sign marker + front-facing yaw).
 */
public final class CompressedBlueprint {
	public record SpawnMarker(BlockPos blockPos, float yaw, float pitch) {
		public SpawnMarker {
			Objects.requireNonNull(blockPos, "blockPos");
		}
	}

	private record PaletteEntryMeta(boolean isBedrock, boolean isSign, @Nullable Float signYaw) {
	}

	private final byte[] compressedNbt;
	private final Vec3i size;
	private final @Nullable BlockPos bedrockOffset;
	private final @Nullable SpawnMarker spawnMarker;

	private CompressedBlueprint(
		byte[] compressedNbt,
		Vec3i size,
		@Nullable BlockPos bedrockOffset,
		@Nullable SpawnMarker spawnMarker
	) {
		this.compressedNbt = Objects.requireNonNull(compressedNbt, "compressedNbt");
		this.size = Objects.requireNonNull(size, "size");
		this.bedrockOffset = bedrockOffset;
		this.spawnMarker = spawnMarker;
	}

	public static CompressedBlueprint fromFile(Path nbtFile, @Nullable DataFixer fixerUpper) throws IOException {
		Objects.requireNonNull(nbtFile, "nbtFile");
		byte[] rawBytes = Files.readAllBytes(nbtFile);
		CompoundTag tag = readTagFromBytes(rawBytes, nbtFile.toString());
		return fromCompoundTag(tag, fixerUpper);
	}

	public static CompressedBlueprint fromCompoundTag(CompoundTag rawTag, @Nullable DataFixer fixerUpper) throws IOException {
		Objects.requireNonNull(rawTag, "rawTag");
		if (!isLikelyStructureTag(rawTag)) {
			throw new IOException("CompoundTag does not appear to be a valid StructureTemplate NBT.");
		}

		CompoundTag tag = rawTag;
		if (fixerUpper != null) {
			SharedConstants.tryDetectVersion();
			int currentVersion = SharedConstants.getCurrentVersion().dataVersion().version();
			int dataVersion = NbtUtils.getDataVersion(tag, 500);
			if (dataVersion < currentVersion) {
				tag = DataFixTypes.STRUCTURE.updateToCurrentVersion(fixerUpper, tag, dataVersion);
			}
		}

		Vec3i size = readSize(tag);
		List<PaletteEntryMeta> paletteMeta = readPaletteMeta(tag);
		AnchorScanResult anchors = scanAnchors(tag, size, paletteMeta);

		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		NbtIo.writeCompressed(tag, baos);
		return new CompressedBlueprint(baos.toByteArray(), size, anchors.bedrockOffset(), anchors.spawnMarker());
	}

	public Vec3i size() {
		return this.size;
	}

	public @Nullable BlockPos bedrockOffset() {
		return this.bedrockOffset;
	}

	public @Nullable SpawnMarker spawnMarker() {
		return this.spawnMarker;
	}

	public int compressedByteCount() {
		return this.compressedNbt.length;
	}

	public CompoundTag readDecompressedTag() throws IOException {
		try (ByteArrayInputStream bais = new ByteArrayInputStream(this.compressedNbt)) {
			return NbtIo.readCompressed(bais, NbtAccounter.unlimitedHeap());
		}
	}

	public StructureTemplate toStructureTemplate(HolderGetter<Block> blockLookup) throws IOException {
		Objects.requireNonNull(blockLookup, "blockLookup");
		CompoundTag tag = readDecompressedTag();
		StructureTemplate template = new StructureTemplate();
		template.load(blockLookup, tag);
		return template;
	}

	private static CompoundTag readTagFromBytes(byte[] rawBytes, String sourceLabel) throws IOException {
		if (rawBytes.length == 0) {
			throw new IOException("Empty NBT blueprint file: " + sourceLabel);
		}
		// GZIP magic bytes 0x1F 0x8B are standard for StructureTemplate .nbt files; fall back to raw NBT if needed.
		if (rawBytes.length >= 2 && (rawBytes[0] & 0xFF) == 0x1F && (rawBytes[1] & 0xFF) == 0x8B) {
			try (ByteArrayInputStream bais = new ByteArrayInputStream(rawBytes)) {
				return NbtIo.readCompressed(bais, NbtAccounter.unlimitedHeap());
			}
		}
		try (ByteArrayInputStream bais = new ByteArrayInputStream(rawBytes);
			 DataInputStream dis = new DataInputStream(bais)) {
			return NbtIo.read(dis, NbtAccounter.unlimitedHeap());
		} catch (IOException ioEx) {
			throw ioEx;
		} catch (RuntimeException ex) {
			throw new IOException("Failed to read NBT blueprint: " + sourceLabel, ex);
		}
	}

	private static boolean isLikelyStructureTag(CompoundTag tag) {
		return tag.contains(StructureTemplate.SIZE_TAG)
			|| tag.contains(StructureTemplate.BLOCKS_TAG)
			|| tag.contains(StructureTemplate.PALETTE_TAG)
			|| tag.contains(StructureTemplate.PALETTE_LIST_TAG);
	}

	private static Vec3i readSize(CompoundTag tag) {
		ListTag sizeTag = tag.getListOrEmpty(StructureTemplate.SIZE_TAG);
		if (sizeTag.size() < 3) {
			return Vec3i.ZERO;
		}
		return new Vec3i(sizeTag.getIntOr(0, 0), sizeTag.getIntOr(1, 0), sizeTag.getIntOr(2, 0));
	}

	private static List<PaletteEntryMeta> readPaletteMeta(CompoundTag tag) {
		ListTag paletteList;
		Optional<ListTag> multiPalettes = tag.getList(StructureTemplate.PALETTE_LIST_TAG);
		if (multiPalettes.isPresent() && !multiPalettes.get().isEmpty()) {
			paletteList = multiPalettes.get().getListOrEmpty(0);
		} else {
			paletteList = tag.getListOrEmpty(StructureTemplate.PALETTE_TAG);
		}

		List<PaletteEntryMeta> entries = new ArrayList<>(paletteList.size());
		for (int i = 0; i < paletteList.size(); i++) {
			CompoundTag entryTag = paletteList.getCompoundOrEmpty(i);
			String blockId = entryTag.getString("id")
				.or(() -> entryTag.getString("Name"))
				.orElse("");
			boolean isBedrock = "minecraft:bedrock".equals(blockId);
			boolean isSign = BlueprintSignSupport.isSignBlockId(blockId);
			Float yaw = null;
			if (isSign) {
				CompoundTag props = entryTag.getCompound("properties")
					.or(() -> entryTag.getCompound("Properties"))
					.orElse(null);
				yaw = BlueprintSignSupport.extractSignYaw(props);
			}
			entries.add(new PaletteEntryMeta(isBedrock, isSign, yaw));
		}
		return entries;
	}

	private record AnchorScanResult(@Nullable BlockPos bedrockOffset, @Nullable SpawnMarker spawnMarker) {
	}

	private static AnchorScanResult scanAnchors(
		CompoundTag tag,
		Vec3i size,
		List<PaletteEntryMeta> paletteMeta
	) {
		ListTag blockList = tag.getListOrEmpty(StructureTemplate.BLOCKS_TAG);
		if (blockList.isEmpty()) {
			return new AnchorScanResult(null, null);
		}

		double centerRefX = (size.getX() - 1) / 2.0D;
		double centerRefZ = (size.getZ() - 1) / 2.0D;

		BlockPos bestBedrock = null;
		double bestBedrockDistSq = Double.MAX_VALUE;
		SpawnMarker firstSpawn = null;

		for (int i = 0; i < blockList.size(); i++) {
			CompoundTag blockTag = blockList.getCompoundOrEmpty(i);
			ListTag posTag = blockTag.getListOrEmpty(StructureTemplate.BLOCK_TAG_POS);
			if (posTag.size() < 3) {
				continue;
			}
			int x = posTag.getIntOr(0, 0);
			int y = posTag.getIntOr(1, 0);
			int z = posTag.getIntOr(2, 0);

			int stateIdx = blockTag.getIntOr(StructureTemplate.BLOCK_TAG_STATE, -1);
			PaletteEntryMeta meta = (stateIdx >= 0 && stateIdx < paletteMeta.size()) ? paletteMeta.get(stateIdx) : null;

			if (meta != null && meta.isBedrock()) {
				double dx = x - centerRefX;
				double dz = z - centerRefZ;
				double distSq = dx * dx + dz * dz;
				if (bestBedrock == null || isBetterBedrockCandidate(x, y, z, distSq, bestBedrock, bestBedrockDistSq)) {
					bestBedrock = new BlockPos(x, y, z);
					bestBedrockDistSq = distSq;
				}
			}

			if (firstSpawn == null) {
				CompoundTag nbt = blockTag.getCompound(StructureTemplate.BLOCK_TAG_NBT).orElse(null);
				if (nbt != null && ((meta != null && meta.isSign()) || BlueprintSignSupport.isSignBlockEntityNbt(nbt))) {
					if (BlueprintSignSupport.isSpawnSign(nbt)) {
						float yaw = (meta != null && meta.signYaw() != null) ? meta.signYaw() : 0.0F;
						firstSpawn = new SpawnMarker(new BlockPos(x, y, z), yaw, 0.0F);
					}
				}
			}
		}

		return new AnchorScanResult(bestBedrock, firstSpawn);
	}

	private static boolean isBetterBedrockCandidate(
		int x,
		int y,
		int z,
		double distSq,
		BlockPos currentBest,
		double currentBestDistSq
	) {
		double diff = distSq - currentBestDistSq;
		if (diff < -1e-6D) {
			return true;
		}
		if (diff > 1e-6D) {
			return false;
		}
		if (y != currentBest.getY()) {
			return y < currentBest.getY();
		}
		if (x != currentBest.getX()) {
			return x < currentBest.getX();
		}
		return z < currentBest.getZ();
	}
}
