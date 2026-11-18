package me.ogsammaenr.ogsky.core.api.blueprint;

import java.util.Objects;

/**
 * Read-only blueprint bundle descriptor exposed to optional modules via {@code ogsky-core} API.
 */
public record BlueprintBundleView(
	String id,
	String displayName,
	String description,
	String iconItemId,
	boolean requiresPermission,
	String permissionNode,
	int order,
	boolean hasOverworld,
	boolean hasNether,
	boolean hasEnd,
	boolean hasPreviewImage
) {
	public BlueprintBundleView(
		String id,
		String displayName,
		String description,
		String iconItemId,
		boolean requiresPermission,
		String permissionNode,
		int order,
		boolean hasOverworld,
		boolean hasNether,
		boolean hasEnd
	) {
		this(id, displayName, description, iconItemId, requiresPermission, permissionNode, order, hasOverworld, hasNether, hasEnd, false);
	}

	public BlueprintBundleView {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(displayName, "displayName");
		Objects.requireNonNull(description, "description");
		Objects.requireNonNull(iconItemId, "iconItemId");
		Objects.requireNonNull(permissionNode, "permissionNode");
	}
}
