package me.ogsammaenr.ogsky.core.api.action;

import me.ogsammaenr.ogsky.core.api.island.IslandView;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * Optional dynamic presentation provider for a registered {@link IslandActionDescriptor}.
 */
@FunctionalInterface
public interface IslandActionPresenter {
	/**
	 * Computes viewer-specific display overrides for an action button, or {@code null} to fall back
	 * to the static descriptor metadata.
	 */
	@Nullable IslandActionPresentation present(UUID viewerUuid, @Nullable IslandView island);
}
