package me.ogsammaenr.ogsky.panel;

import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Optional;

/**
 * Canonical tabs exposed by {@link PanelSnapshot} for both server {@code ChestMenu} and future client UIs.
 */
public enum PanelTab {
	ACTIONS("actions", "Actions", "minecraft:compass", "ogsky.command.panel"),
	PROTECTION_FLAGS("protection_flags", "Protection Flags", "minecraft:shield", "ogsky.command.settings"),
	SETTING_FLAGS("setting_flags", "Island Settings", "minecraft:comparator", "ogsky.command.settings"),
	MEMBERS("members", "Members & Bans", "minecraft:player_head", "ogsky.command.panel"),
	BLUEPRINTS_INFO("blueprints_info", "Blueprints & Info", "minecraft:grass_block", "ogsky.command.panel");

	private final String id;
	private final String displayName;
	private final String iconItemId;
	private final String requiredPermission;

	PanelTab(String id, String displayName, String iconItemId, String requiredPermission) {
		this.id = id;
		this.displayName = displayName;
		this.iconItemId = iconItemId;
		this.requiredPermission = requiredPermission;
	}

	public String id() {
		return id;
	}

	public String displayName() {
		return displayName;
	}

	public String iconItemId() {
		return iconItemId;
	}

	public String requiredPermission() {
		return requiredPermission;
	}

	public static PanelTab fromDbKey(@Nullable String raw) {
		if (raw == null || raw.isBlank()) {
			return ACTIONS;
		}
		String normalized = raw.trim().toLowerCase(Locale.ROOT);
		for (PanelTab tab : values()) {
			if (tab.id.equals(normalized) || tab.name().toLowerCase(Locale.ROOT).equals(normalized)) {
				return tab;
			}
		}
		return ACTIONS;
	}

	public static Optional<PanelTab> fromCommandArg(@Nullable String raw) {
		if (raw == null || raw.isBlank()) {
			return Optional.empty();
		}
		return switch (raw.trim().toLowerCase(Locale.ROOT)) {
			case "actions", "action" -> Optional.of(ACTIONS);
			case "protections", "protection", "protection_flags", "flags" -> Optional.of(PROTECTION_FLAGS);
			case "settings", "setting", "setting_flags" -> Optional.of(SETTING_FLAGS);
			case "members", "member", "team", "bans" -> Optional.of(MEMBERS);
			case "blueprints", "blueprint", "blueprints_info", "info" -> Optional.of(BLUEPRINTS_INFO);
			default -> Optional.empty();
		};
	}
}
