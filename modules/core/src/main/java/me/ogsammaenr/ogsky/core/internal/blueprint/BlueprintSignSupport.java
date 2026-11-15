package me.ogsammaenr.ogsky.core.internal.blueprint;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared sign parsing, facing extraction, and marker rewriting utilities for {@code .nbt} blueprints.
 */
public final class BlueprintSignSupport {
	public static final String MARKER_SPAWN = "[SPAWN]";
	public static final String MARKER_WELCOME = "[WELCOME]";

	private static final String[] LEGACY_TEXT_KEYS = { "Text1", "Text2", "Text3", "Text4" };

	private BlueprintSignSupport() {
	}

	public record WelcomeContext(
		String ownerName,
		String bundleId,
		String bundleDisplayName,
		UUID islandId,
		int gridX,
		int gridZ
	) {
		public WelcomeContext {
			Objects.requireNonNull(ownerName, "ownerName");
			Objects.requireNonNull(bundleId, "bundleId");
			Objects.requireNonNull(bundleDisplayName, "bundleDisplayName");
			Objects.requireNonNull(islandId, "islandId");
		}
	}

	public static boolean isSignBlockId(@Nullable String blockId) {
		if (blockId == null || blockId.isBlank()) {
			return false;
		}
		int colon = blockId.indexOf(':');
		String path = (colon >= 0 ? blockId.substring(colon + 1) : blockId).toLowerCase(Locale.ROOT);
		return path.equals("sign")
			|| path.equals("wall_sign")
			|| path.equals("hanging_sign")
			|| path.endsWith("_sign");
	}

	public static boolean isSignBlockEntityNbt(@Nullable CompoundTag nbt) {
		if (nbt == null) {
			return false;
		}
		Optional<String> id = nbt.getString("id");
		if (id.isPresent() && isSignBlockId(id.get())) {
			return true;
		}
		return nbt.contains("front_text") || nbt.contains("back_text");
	}

	public static @Nullable Float extractSignYaw(@Nullable CompoundTag properties) {
		if (properties == null) {
			return null;
		}
		Optional<String> rotationProp = properties.getString("rotation");
		if (rotationProp.isPresent()) {
			try {
				int segment = Integer.parseInt(rotationProp.get().trim());
				return RotationSegment.convertToDegrees(segment);
			} catch (NumberFormatException ignored) {
				// Fall through to facing property check
			}
		}
		Optional<String> facingProp = properties.getString("facing");
		if (facingProp.isPresent()) {
			Direction facing = Direction.byName(facingProp.get().trim().toLowerCase(Locale.ROOT));
			if (facing != null && facing.getAxis().isHorizontal()) {
				return facing.toYRot();
			}
		}
		return null;
	}

	public static boolean isSpawnSign(@Nullable CompoundTag signNbt) {
		return hasMarker(signNbt, MARKER_SPAWN);
	}

	public static boolean hasWelcomeMarker(@Nullable CompoundTag signNbt) {
		return hasMarker(signNbt, MARKER_WELCOME);
	}

	public static boolean hasMarker(@Nullable CompoundTag signNbt, String marker) {
		if (signNbt == null || marker.isBlank()) {
			return false;
		}
		if (faceHasMarker(signNbt.getCompound("front_text").orElse(null), marker)) {
			return true;
		}
		if (faceHasMarker(signNbt.getCompound("back_text").orElse(null), marker)) {
			return true;
		}
		for (String legacyKey : LEGACY_TEXT_KEYS) {
			Tag raw = signNbt.get(legacyKey);
			if (raw != null && normalizeMarkerLine(extractPlainText(raw)).equalsIgnoreCase(marker)) {
				return true;
			}
		}
		return false;
	}

	public static boolean rewriteWelcomeSignNbt(
		CompoundTag signNbt,
		List<String> configuredLines,
		WelcomeContext context
	) {
		Objects.requireNonNull(signNbt, "signNbt");
		Objects.requireNonNull(configuredLines, "configuredLines");
		Objects.requireNonNull(context, "context");

		boolean changed = false;
		for (String faceKey : new String[] { "front_text", "back_text" }) {
			Optional<CompoundTag> faceOpt = signNbt.getCompound(faceKey);
			if (faceOpt.isPresent() && faceHasMarker(faceOpt.get(), MARKER_WELCOME)) {
				SignText original = SignText.CODEC.parse(NbtOps.INSTANCE, faceOpt.get())
					.result()
					.orElse(SignText.EMPTY);
				SignText updated = applyConfiguredWelcomeLines(original, configuredLines, context);
				SignText.CODEC.encodeStart(NbtOps.INSTANCE, updated)
					.result()
					.ifPresent(encoded -> signNbt.put(faceKey, encoded));
				changed = true;
			}
		}

		if (!changed) {
			boolean legacyWelcome = false;
			for (String legacyKey : LEGACY_TEXT_KEYS) {
				Tag raw = signNbt.get(legacyKey);
				if (raw != null && normalizeMarkerLine(extractPlainText(raw)).equalsIgnoreCase(MARKER_WELCOME)) {
					legacyWelcome = true;
					break;
				}
			}
			if (legacyWelcome) {
				for (String legacyKey : LEGACY_TEXT_KEYS) {
					signNbt.remove(legacyKey);
				}
				SignText updated = applyConfiguredWelcomeLines(SignText.EMPTY, configuredLines, context);
				SignText.CODEC.encodeStart(NbtOps.INSTANCE, updated)
					.result()
					.ifPresent(encoded -> signNbt.put("front_text", encoded));
				changed = true;
			}
		}

		return changed;
	}

	private static SignText applyConfiguredWelcomeLines(
		SignText original,
		List<String> configuredLines,
		WelcomeContext context
	) {
		SignText.Mutable mutable = original.asMutable();
		for (int i = 0; i < SignText.LINES; i++) {
			String rawTemplate = i < configuredLines.size() ? configuredLines.get(i) : "";
			String formatted = formatLine(rawTemplate, context);
			mutable.setLine(i, Component.literal(formatted));
		}
		return mutable.asImmutable();
	}

	public static String formatLine(@Nullable String template, WelcomeContext context) {
		if (template == null || template.isEmpty()) {
			return "";
		}
		String result = template
			.replace("{player}", context.ownerName())
			.replace("<player>", context.ownerName())
			.replace("{owner}", context.ownerName())
			.replace("<owner>", context.ownerName())
			.replace("{bundle}", context.bundleDisplayName())
			.replace("<bundle>", context.bundleDisplayName())
			.replace("{bundle_id}", context.bundleId())
			.replace("{grid_x}", Integer.toString(context.gridX()))
			.replace("{grid_z}", Integer.toString(context.gridZ()))
			.replace("{island}", context.islandId().toString())
			.replace("{island_id}", context.islandId().toString());
		return translateLegacyColorCodes(result);
	}

	private static String translateLegacyColorCodes(String input) {
		int len = input.length();
		if (len < 2 || input.indexOf('&') < 0) {
			return input;
		}
		StringBuilder sb = new StringBuilder(len);
		for (int i = 0; i < len; i++) {
			char c = input.charAt(i);
			if (c == '&' && i + 1 < len && isColorOrFormatCode(input.charAt(i + 1))) {
				sb.append('§').append(Character.toLowerCase(input.charAt(i + 1)));
				i++;
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	private static boolean isColorOrFormatCode(char code) {
		return (code >= '0' && code <= '9')
			|| (code >= 'a' && code <= 'f')
			|| (code >= 'A' && code <= 'F')
			|| (code >= 'k' && code <= 'o')
			|| (code >= 'K' && code <= 'O')
			|| code == 'r'
			|| code == 'R';
	}

	private static boolean faceHasMarker(@Nullable CompoundTag faceTag, String marker) {
		if (faceTag == null) {
			return false;
		}
		Optional<ListTag> messages = faceTag.getList("messages");
		if (messages.isEmpty()) {
			return false;
		}
		for (Tag messageTag : messages.get()) {
			if (normalizeMarkerLine(extractPlainText(messageTag)).equalsIgnoreCase(marker)) {
				return true;
			}
		}
		return false;
	}

	private static String normalizeMarkerLine(@Nullable String raw) {
		if (raw == null || raw.isEmpty()) {
			return "";
		}
		String stripped = ChatFormatting.stripFormatting(raw);
		return stripped == null ? "" : stripped.trim();
	}

	static String extractPlainText(@Nullable Tag tag) {
		if (tag == null) {
			return "";
		}
		if (tag instanceof StringTag(String value)) {
			String trimmed = value.trim();
			if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
				try {
					JsonElement parsed = JsonParser.parseString(trimmed);
					if (parsed.isJsonObject()) {
						return extractJsonText(parsed.getAsJsonObject());
					}
				} catch (Exception ignored) {
					// Not JSON component; keep literal string
				}
			}
			return value;
		}
		if (tag instanceof CompoundTag compound) {
			StringBuilder sb = new StringBuilder();
			compound.getString("text").ifPresent(sb::append);
			Optional<ListTag> extra = compound.getList("extra");
			if (extra.isPresent()) {
				for (Tag child : extra.get()) {
					sb.append(extractPlainText(child));
				}
			}
			return sb.toString();
		}
		if (tag instanceof ListTag list) {
			StringBuilder sb = new StringBuilder();
			for (Tag child : list) {
				sb.append(extractPlainText(child));
			}
			return sb.toString();
		}
		return "";
	}

	private static String extractJsonText(JsonObject obj) {
		StringBuilder sb = new StringBuilder();
		if (obj.has("text") && obj.get("text").isJsonPrimitive()) {
			sb.append(obj.get("text").getAsString());
		}
		if (obj.has("extra") && obj.get("extra").isJsonArray()) {
			for (JsonElement child : obj.getAsJsonArray("extra")) {
				if (child.isJsonPrimitive()) {
					sb.append(child.getAsString());
				} else if (child.isJsonObject()) {
					sb.append(extractJsonText(child.getAsJsonObject()));
				}
			}
		}
		return sb.toString();
	}
}
