package me.ogsammaenr.ogsky.border;

import java.util.Locale;

/**
 * Configuration model serialized to {@code config/ogsky/border.json}.
 */
public final class BorderConfig {
	public boolean enabled = true;
	public boolean defaultEnabledForPlayers = false;
	public String defaultColor = "BLUE";
	public boolean allowColorSelection = true;

	public boolean allowInOverworld = true;
	public boolean allowInNether = true;
	public boolean allowInEnd = true;

	public int warningBlocks = 0;
	public int warningTime = 0;

	public static BorderConfig defaults() {
		BorderConfig config = new BorderConfig();
		config.ensureDefaultsAndValidate();
		return config;
	}

	public void ensureDefaultsAndValidate() {
		BorderColor parsedDefault = BorderColor.fromDbKey(this.defaultColor, BorderColor.BLUE);
		this.defaultColor = parsedDefault.name().toUpperCase(Locale.ROOT);
		this.warningBlocks = Math.clamp(this.warningBlocks, 0, 32);
		this.warningTime = Math.clamp(this.warningTime, 0, 60);
	}

	public BorderColor defaultColorEnum() {
		return BorderColor.fromDbKey(this.defaultColor, BorderColor.BLUE);
	}
}
