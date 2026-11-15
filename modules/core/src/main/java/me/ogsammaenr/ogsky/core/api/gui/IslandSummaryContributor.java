package me.ogsammaenr.ogsky.core.api.gui;

import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Optional module contributor appending extra lines to an island's overview summary card.
 */
@FunctionalInterface
public interface IslandSummaryContributor {
	List<Component> contributeLines(IslandSummaryContext context);
}
