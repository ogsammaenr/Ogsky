package me.ogsammaenr.ogsky.core.internal.permission;

import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.internal.config.CoreConfig;
import net.fabricmc.fabric.api.permission.v1.PermissionContextOwner;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Permission resolution bridge prioritizing LuckPerms (via reflection when present)
 * and {@code fabric-permission-api-v1}, with vanilla OP-level fallback from {@link CoreConfig}.
 */
public final class LuckPermsPermissionBridge {
	public static final String ADMIN_NODE = "ogsky.admin";
	public static final String BYPASS_WILDCARD_NODE = "ogsky.admin.bypass.*";
	public static final String BYPASS_PREFIX = "ogsky.admin.bypass.";
	public static final String COMMAND_PREFIX = "ogsky.command.";
	public static final String BLUEPRINT_PREFIX = "ogsky.blueprint.";

	private final CoreConfig config;
	private final PermissionLookup externalLookup;
	private final Map<UUID, Boolean> sessionBypassOverrides = new ConcurrentHashMap<>();

	public LuckPermsPermissionBridge(CoreConfig config) {
		this(config, ReflectLuckPermsLookup.INSTANCE);
	}

	public LuckPermsPermissionBridge(CoreConfig config, PermissionLookup externalLookup) {
		this.config = Objects.requireNonNull(config, "config");
		this.externalLookup = Objects.requireNonNull(externalLookup, "externalLookup");
	}

	public boolean hasPermission(CommandSourceStack source, String permissionNode, int fallbackOpLevel) {
		Objects.requireNonNull(source, "source");
		if (permissionNode == null || permissionNode.isBlank()) {
			return hasOpLevel(source, fallbackOpLevel);
		}
		if (source.getEntity() instanceof Player player) {
			Boolean explicit = queryExplicit(player, permissionNode);
			if (explicit != null) {
				return explicit;
			}
			return hasOpLevel(source, fallbackOpLevel);
		}
		if (source instanceof PermissionContextOwner owner) {
			Boolean fabricResult = queryFabricPermission(owner, permissionNode);
			if (fabricResult != null) {
				return fabricResult;
			}
			return hasOpLevel(owner, fallbackOpLevel);
		}
		return fallbackOpLevel <= 0;
	}

	public boolean hasPermission(Player player, String permissionNode, int fallbackOpLevel) {
		Objects.requireNonNull(player, "player");
		if (permissionNode == null || permissionNode.isBlank()) {
			return hasOpLevel(player, fallbackOpLevel);
		}
		Boolean explicit = queryExplicit(player, permissionNode);
		if (explicit != null) {
			return explicit;
		}
		return hasOpLevel(player, fallbackOpLevel);
	}

	public boolean hasPermission(
		UUID playerUuid,
		PermissionLevel opLevel,
		String permissionNode,
		int fallbackOpLevel
	) {
		Objects.requireNonNull(playerUuid, "playerUuid");
		Objects.requireNonNull(opLevel, "opLevel");
		if (permissionNode == null || permissionNode.isBlank()) {
			return fallbackOpLevel <= 0 || opLevel.isEqualOrHigherThan(PermissionLevel.byId(Math.min(4, fallbackOpLevel)));
		}
		Boolean lp = externalLookup.check(playerUuid, permissionNode);
		if (lp != null) {
			return lp;
		}
		return fallbackOpLevel <= 0 || opLevel.isEqualOrHigherThan(PermissionLevel.byId(Math.min(4, fallbackOpLevel)));
	}

	public boolean hasAdmin(CommandSourceStack source) {
		return hasPermission(source, ADMIN_NODE, config.admin.fallbackOpLevel);
	}

	public boolean hasAdmin(Player player) {
		return hasPermission(player, ADMIN_NODE, config.admin.fallbackOpLevel);
	}

	public boolean hasBlueprintPermission(CommandSourceStack source, String bundleId) {
		Objects.requireNonNull(bundleId, "bundleId");
		return hasPermission(source, BLUEPRINT_PREFIX + bundleId.toLowerCase(Locale.ROOT), 0);
	}

	public boolean hasBypass(Player player, Identifier flagId) {
		Objects.requireNonNull(player, "player");
		Objects.requireNonNull(flagId, "flagId");
		Boolean session = sessionBypassOverrides.get(player.getUUID());
		if (session != null) {
			return session;
		}
		return hasExplicitBypassPermission(player, flagId);
	}

	public boolean hasBypass(UUID playerUuid, Identifier flagId) {
		Objects.requireNonNull(playerUuid, "playerUuid");
		Objects.requireNonNull(flagId, "flagId");
		Boolean session = sessionBypassOverrides.get(playerUuid);
		if (session != null) {
			return session;
		}
		Boolean specific = externalLookup.check(playerUuid, BYPASS_PREFIX + flagId.getPath());
		if (specific != null) {
			return specific;
		}
		return Boolean.TRUE.equals(externalLookup.check(playerUuid, BYPASS_WILDCARD_NODE));
	}

	public boolean isBypassActive(Player player) {
		Objects.requireNonNull(player, "player");
		Boolean session = sessionBypassOverrides.get(player.getUUID());
		if (session != null) {
			return session;
		}
		return Boolean.TRUE.equals(queryExplicit(player, BYPASS_WILDCARD_NODE));
	}

	public boolean toggleBypass(Player player) {
		Objects.requireNonNull(player, "player");
		boolean next = !isBypassActive(player);
		sessionBypassOverrides.put(player.getUUID(), next);
		return next;
	}

	public boolean toggleBypass(UUID playerUuid) {
		Objects.requireNonNull(playerUuid, "playerUuid");
		Boolean session = sessionBypassOverrides.get(playerUuid);
		boolean current = session != null
			? session
			: Boolean.TRUE.equals(externalLookup.check(playerUuid, BYPASS_WILDCARD_NODE));
		boolean next = !current;
		sessionBypassOverrides.put(playerUuid, next);
		return next;
	}

	public void clearSessionBypass(UUID playerUuid) {
		sessionBypassOverrides.remove(playerUuid);
	}

	public void clearAllSessionBypass() {
		sessionBypassOverrides.clear();
	}

	private boolean hasExplicitBypassPermission(Player player, Identifier flagId) {
		String flagNode = BYPASS_PREFIX + flagId.getPath();
		Boolean specific = queryExplicit(player, flagNode);
		if (specific != null) {
			return specific;
		}
		Boolean wildcard = queryExplicit(player, BYPASS_WILDCARD_NODE);
		return Boolean.TRUE.equals(wildcard);
	}

	private @Nullable Boolean queryExplicit(Player player, String permissionNode) {
		Boolean lp = externalLookup.check(player.getUUID(), permissionNode);
		if (lp != null) {
			return lp;
		}
		if (player instanceof PermissionContextOwner owner) {
			return queryFabricPermission(owner, permissionNode);
		}
		return null;
	}

	private static @Nullable Boolean queryFabricPermission(PermissionContextOwner owner, String permissionNode) {
		Identifier id = toFabricPermissionId(permissionNode);
		if (id == null) {
			return null;
		}
		TriState state = owner.checkPermission(id);
		return switch (state) {
			case TRUE -> Boolean.TRUE;
			case FALSE -> Boolean.FALSE;
			case DEFAULT -> null;
		};
	}

	private static @Nullable Identifier toFabricPermissionId(String permissionNode) {
		String lower = permissionNode.trim().toLowerCase(Locale.ROOT);
		if (lower.isEmpty() || lower.contains("*")) {
			return null;
		}
		String path = lower.startsWith("ogsky.") ? lower.substring("ogsky.".length()) : lower;
		return Identifier.tryBuild(OgskyCore.MOD_ID, path);
	}

	private static boolean hasOpLevel(Object sourceOrPlayer, int fallbackOpLevel) {
		if (fallbackOpLevel <= 0) {
			return true;
		}
		if (sourceOrPlayer instanceof PermissionContextOwner owner) {
			return owner.getPermissionContext()
				.permissionLevel()
				.isEqualOrHigherThan(PermissionLevel.byId(Math.min(4, fallbackOpLevel)));
		}
		return false;
	}

	@FunctionalInterface
	public interface PermissionLookup {
		@Nullable Boolean check(UUID playerUuid, String node);
	}

	private enum ReflectLuckPermsLookup implements PermissionLookup {
		INSTANCE;

		private static volatile boolean initialized;
		private static volatile boolean available;
		private static @Nullable Method getProviderMethod;
		private static @Nullable Method getUserManagerMethod;
		private static @Nullable Method getUserMethod;
		private static @Nullable Method getCachedDataMethod;
		private static @Nullable Method getPermissionDataMethod;
		private static @Nullable Method checkPermissionMethod;
		private static @Nullable Method tristateAsBooleanMethod;
		private static @Nullable Method tristateNameMethod;

		@Override
		public @Nullable Boolean check(UUID playerUuid, String node) {
			ensureInitialized();
			if (!available) {
				return null;
			}
			try {
				Object api = getProviderMethod.invoke(null);
				Object userManager = getUserManagerMethod.invoke(api);
				Object user = getUserMethod.invoke(userManager, playerUuid);
				if (user == null) {
					return null;
				}
				Object cachedData = getCachedDataMethod.invoke(user);
				Object permData = getPermissionDataMethod.invoke(cachedData);
				Object tristate = checkPermissionMethod.invoke(permData, node);
				if (tristate == null) {
					return null;
				}
				String name = (String) tristateNameMethod.invoke(tristate);
				if ("UNDEFINED".equals(name)) {
					return null;
				}
				return (Boolean) tristateAsBooleanMethod.invoke(tristate);
			} catch (Throwable ignored) {
				return null;
			}
		}

		private static void ensureInitialized() {
			if (initialized) {
				return;
			}
			synchronized (ReflectLuckPermsLookup.class) {
				if (initialized) {
					return;
				}
				try {
					Class<?> providerClass = Class.forName("net.luckperms.api.LuckPermsProvider");
					Class<?> apiClass = Class.forName("net.luckperms.api.LuckPerms");
					Class<?> userManagerClass = Class.forName("net.luckperms.api.model.user.UserManager");
					Class<?> userClass = Class.forName("net.luckperms.api.model.user.User");
					Class<?> cachedDataClass = Class.forName("net.luckperms.api.cacheddata.CachedDataManager");
					Class<?> permDataClass = Class.forName("net.luckperms.api.cacheddata.CachedPermissionData");
					Class<?> tristateClass = Class.forName("net.luckperms.api.util.Tristate");

					getProviderMethod = providerClass.getMethod("get");
					getUserManagerMethod = apiClass.getMethod("getUserManager");
					getUserMethod = userManagerClass.getMethod("getUser", UUID.class);
					getCachedDataMethod = userClass.getMethod("getCachedData");
					getPermissionDataMethod = cachedDataClass.getMethod("getPermissionData");
					checkPermissionMethod = permDataClass.getMethod("checkPermission", String.class);
					tristateAsBooleanMethod = tristateClass.getMethod("asBoolean");
					tristateNameMethod = tristateClass.getMethod("name");
					available = true;
				} catch (Throwable ignored) {
					available = false;
				}
				initialized = true;
			}
		}
	}
}
