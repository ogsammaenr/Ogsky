package me.ogsammaenr.ogsky.core.internal.blueprint;

import java.util.Locale;

/**
 * JSON metadata parsed from {@code config/ogsky/blueprints/<bundleId>/bundle.json}.
 */
public final class BlueprintBundleMetadata {
	public String id = "";
	public String displayName = "";
	public String description = "";
	public String iconItem = "minecraft:grass_block";
	public String previewImage = "preview.png";
	public boolean requiresPermission = false;
	public String permissionNode = "";
	public int order = 0;

	public DimensionSpec overworld = DimensionSpec.defaultSpec("overworld.nbt");
	public DimensionSpec nether = DimensionSpec.defaultSpec("nether.nbt");
	public DimensionSpec end = DimensionSpec.defaultSpec("end.nbt");

	public void normalizeDefaults(String directoryName) {
		if (id == null || id.isBlank()) {
			id = directoryName;
		} else {
			id = id.trim().toLowerCase(Locale.ROOT);
		}
		if (displayName == null || displayName.isBlank()) {
			displayName = capitalize(id);
		}
		if (description == null) {
			description = "";
		}
		if (iconItem == null || iconItem.isBlank()) {
			iconItem = "minecraft:grass_block";
		}
		if (previewImage == null || previewImage.isBlank()) {
			previewImage = "preview.png";
		}
		if (permissionNode == null) {
			permissionNode = "";
		}
		if (requiresPermission && permissionNode.isBlank()) {
			permissionNode = "ogsky.blueprint." + id;
		}

		if (overworld == null) overworld = DimensionSpec.defaultSpec("overworld.nbt");
		overworld.ensureDefaultFile("overworld.nbt");

		if (nether == null) nether = DimensionSpec.defaultSpec("nether.nbt");
		nether.ensureDefaultFile("nether.nbt");

		if (end == null) end = DimensionSpec.defaultSpec("end.nbt");
		end.ensureDefaultFile("end.nbt");
	}

	private static String capitalize(String raw) {
		if (raw.isEmpty()) {
			return raw;
		}
		return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
	}

	public static final class DimensionSpec {
		public String file = "";
		public boolean centerHorizontally = true;
		public int offsetX = 0;
		public int offsetY = 0;
		public int offsetZ = 0;
		public int spawnOffsetX = 0;
		public int spawnOffsetY = 0;
		public int spawnOffsetZ = 0;
		public float spawnYaw = 0.0F;
		public float spawnPitch = 0.0F;

		public static DimensionSpec defaultSpec(String defaultFile) {
			DimensionSpec spec = new DimensionSpec();
			spec.file = defaultFile;
			return spec;
		}

		public void ensureDefaultFile(String defaultFile) {
			if (file == null || file.isBlank()) {
				file = defaultFile;
			}
		}
	}
}
