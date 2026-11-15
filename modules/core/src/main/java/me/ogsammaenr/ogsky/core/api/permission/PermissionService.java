package me.ogsammaenr.ogsky.core.api.permission;

import me.ogsammaenr.ogsky.core.api.blueprint.BlueprintBundleView;
import net.minecraft.server.level.ServerPlayer;

/**
 * Public permission and admin bypass query API exposed by {@code ogsky-core}.
 */
public interface PermissionService {
	boolean hasPermission(ServerPlayer player, String permissionNode, int fallbackOpLevel);

	boolean hasAdmin(ServerPlayer player);

	boolean isBypassActive(ServerPlayer player);

	boolean hasBlueprintPermission(ServerPlayer player, String bundleId);

	boolean hasBlueprintPermission(ServerPlayer player, BlueprintBundleView bundle);
}
