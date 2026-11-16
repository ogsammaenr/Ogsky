package me.ogsammaenr.ogsky.core.api.permission;

import me.ogsammaenr.ogsky.core.api.blueprint.BlueprintBundleView;
import net.minecraft.server.level.ServerPlayer;

/**
 * Public permission and admin bypass query API exposed by {@code ogsky-core}.
 * Checks LuckPerms (when installed), {@code fabric-permission-api-v1}, and vanilla OP level fallback in order.
 */
public interface PermissionService {
	/**
	 * Evaluates a permission node for {@code player}, falling back to {@code fallbackOpLevel} ({@code <= 0} allows all).
	 */
	boolean hasPermission(ServerPlayer player, String permissionNode, int fallbackOpLevel);

	/**
	 * Checks {@code ogsky.admin} with the configured {@code admin.fallbackOpLevel} from {@code core.json}.
	 */
	boolean hasAdmin(ServerPlayer player);

	/**
	 * Returns {@code true} if {@code player} toggled session bypass ({@code /isadmin bypass}) or holds {@code ogsky.admin.bypass.*}.
	 */
	boolean isBypassActive(ServerPlayer player);

	/**
	 * Checks blueprint selection permission {@code ogsky.blueprint.<bundleId>} (default fallback {@code 0}).
	 */
	boolean hasBlueprintPermission(ServerPlayer player, String bundleId);

	/**
	 * Checks blueprint selection permission using {@code bundle}'s custom node or {@code ogsky.blueprint.<id>}
	 * and configurable admin OP fallback when {@code bundle.requiresPermission()} is {@code true}.
	 */
	boolean hasBlueprintPermission(ServerPlayer player, BlueprintBundleView bundle);
}
