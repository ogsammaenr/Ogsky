package me.ogsammaenr.ogsky.panel;

import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.OgskyCoreApi;
import me.ogsammaenr.ogsky.core.api.blueprint.BlueprintBundleView;
import me.ogsammaenr.ogsky.core.api.blueprint.BlueprintDimension;
import me.ogsammaenr.ogsky.core.api.blueprint.BlueprintStructureData;
import me.ogsammaenr.ogui.api.Ogui;
import me.ogsammaenr.ogui.api.OguiAssetRef;
import me.ogsammaenr.ogui.api.OguiService;
import me.ogsammaenr.ogui.api.OguiStructureShell;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Caches content-addressed {@code ogui} PNG thumbnails and 3D {@link OguiStructureShell} assets
 * per blueprint bundle, lazily rebuilding when {@code ogsky-core}'s blueprint registry revision changes.
 */
public final class PanelBlueprintAssetManager {
	public record DimensionStructureAsset(
		OguiAssetRef shellRef,
		int sizeX,
		int sizeY,
		int sizeZ,
		int blockCount
	) {
		public DimensionStructureAsset {
			Objects.requireNonNull(shellRef, "shellRef");
		}
	}

	public record BundleAssets(
		@Nullable OguiAssetRef previewPng,
		Map<BlueprintDimension, DimensionStructureAsset> structures
	) {
		public static final BundleAssets EMPTY = new BundleAssets(null, Map.of());

		public BundleAssets {
			structures = structures == null ? Map.of() : Map.copyOf(structures);
		}

		public @Nullable OguiAssetRef structureRef(BlueprintDimension dimension) {
			DimensionStructureAsset asset = this.structures.get(dimension);
			return asset != null ? asset.shellRef() : null;
		}

		public @Nullable DimensionStructureAsset structureInfo(BlueprintDimension dimension) {
			return this.structures.get(dimension);
		}
	}

	private final OgskyCoreApi core;
	private final Supplier<OguiService> oguiServiceSupplier;
	private volatile long syncedRevision = -1L;
	private volatile Map<String, BundleAssets> assetsByBundle = Map.of();

	public PanelBlueprintAssetManager(OgskyCoreApi core) {
		this(core, Ogui::service);
	}

	PanelBlueprintAssetManager(OgskyCoreApi core, Supplier<OguiService> oguiServiceSupplier) {
		this.core = Objects.requireNonNull(core, "core");
		this.oguiServiceSupplier = Objects.requireNonNull(oguiServiceSupplier, "oguiServiceSupplier");
	}

	/**
	 * Rebuilds cached {@link OguiAssetRef} registrations if {@code core.islands().blueprintRevision()} changed.
	 */
	public void syncIfNeeded() {
		long currentRevision = this.core.islands().blueprintRevision();
		if (currentRevision == this.syncedRevision) {
			return;
		}
		rebuildAssets(currentRevision);
	}

	public void invalidate() {
		this.syncedRevision = -1L;
	}

	public BundleAssets bundleAssets(String bundleId) {
		syncIfNeeded();
		if (bundleId == null || bundleId.isBlank()) {
			return BundleAssets.EMPTY;
		}
		return this.assetsByBundle.getOrDefault(bundleId.trim().toLowerCase(Locale.ROOT), BundleAssets.EMPTY);
	}

	public @Nullable OguiAssetRef previewPng(String bundleId) {
		return bundleAssets(bundleId).previewPng();
	}

	public @Nullable OguiAssetRef structureShell(String bundleId, BlueprintDimension dimension) {
		Objects.requireNonNull(dimension, "dimension");
		return bundleAssets(bundleId).structureRef(dimension);
	}

	private synchronized void rebuildAssets(long expectedRevision) {
		long liveRevision = this.core.islands().blueprintRevision();
		if (liveRevision == this.syncedRevision) {
			return;
		}

		OguiService service = this.oguiServiceSupplier.get();
		List<BlueprintBundleView> bundles = this.core.islands().availableBlueprints();
		Map<String, BundleAssets> rebuilt = new LinkedHashMap<>(bundles.size());

		for (BlueprintBundleView bundle : bundles) {
			String normalizedId = bundle.id().toLowerCase(Locale.ROOT);
			OguiAssetRef pngRef = this.core.islands()
				.findBlueprintPreviewPng(bundle.id())
				.map(bytes -> safeRegisterPng(service, bundle.id(), bytes))
				.orElse(null);

			Map<BlueprintDimension, DimensionStructureAsset> dimAssets = new EnumMap<>(BlueprintDimension.class);
			for (BlueprintDimension dim : BlueprintDimension.values()) {
				if (!dim.isSupportedBy(bundle)) {
					continue;
				}
				this.core.islands()
					.readBlueprintStructure(bundle.id(), dim)
					.flatMap(data -> buildShellAsset(service, bundle.id(), dim, data))
					.ifPresent(asset -> dimAssets.put(dim, asset));
			}
			rebuilt.put(normalizedId, new BundleAssets(pngRef, dimAssets));
		}

		this.assetsByBundle = Map.copyOf(rebuilt);
		this.syncedRevision = Math.max(expectedRevision, liveRevision);
	}

	private static @Nullable OguiAssetRef safeRegisterPng(OguiService service, String bundleId, byte[] bytes) {
		try {
			return service.registerPngAsset(bytes);
		} catch (RuntimeException ex) {
			OgskyCore.LOGGER.warn("Failed to register Ogui PNG preview asset for bundle '{}': {}", bundleId, ex.getMessage());
			return null;
		}
	}

	private static java.util.Optional<DimensionStructureAsset> buildShellAsset(
		OguiService service,
		String bundleId,
		BlueprintDimension dim,
		BlueprintStructureData data
	) {
		if (data.blocks().isEmpty()) {
			return java.util.Optional.empty();
		}
		List<OguiStructureShell.PositionedBlock> shellBlocks = new ArrayList<>(data.blocks().size());
		for (BlueprintStructureData.PositionedBlock pb : data.blocks()) {
			shellBlocks.add(new OguiStructureShell.PositionedBlock(pb.x(), pb.y(), pb.z(), pb.state()));
		}
		OguiStructureShell shell = OguiStructureShell.fromBlocks(shellBlocks);
		if (shell.blocks().isEmpty()) {
			return java.util.Optional.empty();
		}
		try {
			OguiAssetRef ref = service.registerStructureAsset(shell);
			return java.util.Optional.of(new DimensionStructureAsset(
				ref,
				data.sizeX(),
				data.sizeY(),
				data.sizeZ(),
				data.blocks().size()
			));
		} catch (RuntimeException ex) {
			OgskyCore.LOGGER.warn(
				"Failed to register Ogui 3D structure shell for bundle '{}' ({}): {}",
				bundleId,
				dim.key(),
				ex.getMessage()
			);
			return java.util.Optional.empty();
		}
	}
}
