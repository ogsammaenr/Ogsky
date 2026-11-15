package me.ogsammaenr.ogsky.core.internal.config;

import me.ogsammaenr.ogsky.core.api.island.IslandRole;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Validated configuration model persisted at {@code config/ogsky/core.json}.
 */
public final class CoreConfig {
	public GridConfig grid = new GridConfig();
	public IslandConfig island = new IslandConfig();
	public RolesConfig roles = new RolesConfig();
	public MembershipConfig membership = new MembershipConfig();
	public BlueprintsConfig blueprints = new BlueprintsConfig();
	public VoidTeleportConfig voidTeleport = new VoidTeleportConfig();
	public AdminConfig admin = new AdminConfig();
	public FlagsConfig flags = new FlagsConfig();

	public static CoreConfig defaults() {
		CoreConfig config = new CoreConfig();
		config.ensureDefaultsAndValidate();
		return config;
	}

	public void ensureDefaultsAndValidate() {
		if (grid == null) grid = new GridConfig();
		if (island == null) island = new IslandConfig();
		if (roles == null) roles = new RolesConfig();
		if (membership == null) membership = new MembershipConfig();
		if (blueprints == null) blueprints = new BlueprintsConfig();
		if (voidTeleport == null) voidTeleport = new VoidTeleportConfig();
		if (admin == null) admin = new AdminConfig();
		if (flags == null) flags = new FlagsConfig();

		roles.ensureDefaults();
		flags.ensureDefaults();
		blueprints.ensureDefaults();

		if (grid.cellSize < 32 || (grid.cellSize % 16) != 0) {
			throw new IllegalArgumentException(
				"grid.cellSize must be >= 32 and a multiple of 16, got: " + grid.cellSize
			);
		}
		if (island.initialHalfSize < 1) {
			throw new IllegalArgumentException(
				"island.initialHalfSize must be >= 1, got: " + island.initialHalfSize
			);
		}
		if (island.maxHalfSize < island.initialHalfSize) {
			throw new IllegalArgumentException(
				"island.maxHalfSize (" + island.maxHalfSize + ") must be >= island.initialHalfSize ("
					+ island.initialHalfSize + ")"
			);
		}
		if (island.maxHalfSize >= (grid.cellSize / 2)) {
			throw new IllegalArgumentException(
				"island.maxHalfSize (" + island.maxHalfSize + ") must be strictly < grid.cellSize / 2 ("
					+ (grid.cellSize / 2) + ") to preserve inter-island buffers."
			);
		}
		if (island.defaultSpawnY < -64 || island.defaultSpawnY > 319) {
			throw new IllegalArgumentException(
				"island.defaultSpawnY out of range [-64, 319]: " + island.defaultSpawnY
			);
		}
		if (membership.maxCoop < 0 || membership.maxTrusted < 0 || membership.inviteTimeoutSeconds < 5) {
			throw new IllegalArgumentException("Invalid membership caps or invite timeout.");
		}
		if (blueprints.defaultBundle == null || blueprints.defaultBundle.isBlank()) {
			throw new IllegalArgumentException("blueprints.defaultBundle must not be blank.");
		}
		if (blueprints.blocksPerTick < 1 || blueprints.maxNanosPerTick < 100_000L || blueprints.cleanupChunksPerTick < 1) {
			throw new IllegalArgumentException("Invalid blueprints tick-budget configuration.");
		}
		if (voidTeleport.mode == null) {
			voidTeleport.mode = "ISLAND_HOME";
		} else {
			String normalized = voidTeleport.mode.trim().toUpperCase(Locale.ROOT);
			if (!normalized.equals("ISLAND_HOME") && !normalized.equals("WORLD_SPAWN")) {
				throw new IllegalArgumentException(
					"voidTeleport.mode must be ISLAND_HOME or WORLD_SPAWN, got: " + voidTeleport.mode
				);
			}
			voidTeleport.mode = normalized;
		}
		if (admin.fallbackOpLevel < 0 || admin.fallbackOpLevel > 4) {
			throw new IllegalArgumentException("admin.fallbackOpLevel must be 0..4, got: " + admin.fallbackOpLevel);
		}
	}

	public String roleDisplayName(byte role) {
		String key = Byte.toString(role);
		String configured = roles.names.get(key);
		if (configured != null && !configured.isBlank()) {
			return configured;
		}
		return IslandRole.defaultKey(role);
	}

	public void copyRuntimeFrom(CoreConfig fresh) {
		Objects.requireNonNull(fresh, "fresh");
		fresh.ensureDefaultsAndValidate();
		this.island.initialHalfSize = fresh.island.initialHalfSize;
		this.island.maxHalfSize = fresh.island.maxHalfSize;
		this.island.defaultSpawnY = fresh.island.defaultSpawnY;
		this.roles.names.clear();
		this.roles.names.putAll(fresh.roles.names);
		this.membership.maxCoop = fresh.membership.maxCoop;
		this.membership.maxTrusted = fresh.membership.maxTrusted;
		this.membership.inviteTimeoutSeconds = fresh.membership.inviteTimeoutSeconds;
		this.blueprints.defaultBundle = fresh.blueprints.defaultBundle;
		this.blueprints.blocksPerTick = fresh.blueprints.blocksPerTick;
		this.blueprints.maxNanosPerTick = fresh.blueprints.maxNanosPerTick;
		this.blueprints.cleanupChunksPerTick = fresh.blueprints.cleanupChunksPerTick;
		this.blueprints.lazyNether = fresh.blueprints.lazyNether;
		this.blueprints.lazyEnd = fresh.blueprints.lazyEnd;
		this.blueprints.welcomeSignLines = new ArrayList<>(fresh.blueprints.welcomeSignLines);
		this.voidTeleport.mode = fresh.voidTeleport.mode;
		this.admin.fallbackOpLevel = fresh.admin.fallbackOpLevel;
		this.flags.protectionDefaults.clear();
		this.flags.protectionDefaults.putAll(fresh.flags.protectionDefaults);
		this.flags.settingDefaults.clear();
		this.flags.settingDefaults.putAll(fresh.flags.settingDefaults);
		this.flags.worldSettingDefaults.clear();
		this.flags.worldSettingDefaults.putAll(fresh.flags.worldSettingDefaults);
		this.flags.wildernessAllow.clear();
		this.flags.wildernessAllow.putAll(fresh.flags.wildernessAllow);
	}

	public static final class GridConfig {
		public int cellSize = 1024;
	}

	public static final class IslandConfig {
		public int initialHalfSize = 50;
		public int maxHalfSize = 480;
		public int defaultSpawnY = 64;
	}

	public static final class RolesConfig {
		public Map<String, String> names = defaultNames();

		void ensureDefaults() {
			if (names == null) {
				names = defaultNames();
				return;
			}
			defaultNames().forEach(names::putIfAbsent);
		}

		private static Map<String, String> defaultNames() {
			Map<String, String> map = new LinkedHashMap<>();
			map.put("0", "Ziyaretçi");
			map.put("1", "Coop");
			map.put("2", "Güvenilir");
			map.put("3", "Üye");
			map.put("4", "Yönetici");
			map.put("5", "Sahip");
			map.put("6", "Devre Dışı");
			return map;
		}
	}

	public static final class MembershipConfig {
		public int maxCoop = 1;
		public int maxTrusted = 2;
		public int inviteTimeoutSeconds = 60;
	}

	public static final class BlueprintsConfig {
		public String defaultBundle = "default";
		public int blocksPerTick = 256;
		public long maxNanosPerTick = 2_000_000L;
		public int cleanupChunksPerTick = 2;
		public boolean lazyNether = true;
		public boolean lazyEnd = true;
		public List<String> welcomeSignLines = defaultWelcomeSignLines();

		void ensureDefaults() {
			if (welcomeSignLines == null) {
				welcomeSignLines = defaultWelcomeSignLines();
				return;
			}
			List<String> normalized = new ArrayList<>(4);
			for (int i = 0; i < Math.min(4, welcomeSignLines.size()); i++) {
				String line = welcomeSignLines.get(i);
				normalized.add(line == null ? "" : line);
			}
			welcomeSignLines = normalized;
		}

		public static List<String> defaultWelcomeSignLines() {
			return new ArrayList<>(List.of(
				"&aHoş Geldin,",
				"&f{player}",
				"&7Adan hazır!",
				"&e/is panel"
			));
		}
	}

	public static final class VoidTeleportConfig {
		public String mode = "ISLAND_HOME";
	}

	public static final class AdminConfig {
		public int fallbackOpLevel = 2;
	}

	public static final class FlagsConfig {
		public Map<String, String> protectionDefaults = new LinkedHashMap<>();
		public Map<String, Boolean> settingDefaults = new LinkedHashMap<>();
		public Map<String, Boolean> worldSettingDefaults = new LinkedHashMap<>();
		public Map<String, Boolean> wildernessAllow = new LinkedHashMap<>();

		void ensureDefaults() {
			if (protectionDefaults == null) protectionDefaults = new LinkedHashMap<>();
			if (settingDefaults == null) settingDefaults = new LinkedHashMap<>();
			if (worldSettingDefaults == null) worldSettingDefaults = new LinkedHashMap<>();
			if (wildernessAllow == null) wildernessAllow = new LinkedHashMap<>();
		}

		public byte resolveProtectionDefault(String flagId, byte fallback) {
			Objects.requireNonNull(flagId, "flagId");
			String override = protectionDefaults.get(flagId);
			return override == null ? fallback : IslandRole.parseThreshold(override);
		}

		public boolean resolveSettingDefault(String flagId, boolean fallback) {
			Boolean override = settingDefaults.get(flagId);
			return override == null ? fallback : override;
		}

		public boolean resolveWorldSettingDefault(String flagId, boolean fallback) {
			Boolean override = worldSettingDefaults.get(flagId);
			return override == null ? fallback : override;
		}

		public boolean resolveWildernessAllowed(String flagId, boolean fallback) {
			Boolean override = wildernessAllow.get(flagId);
			return override == null ? fallback : override;
		}
	}
}
