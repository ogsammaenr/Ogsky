package me.ogsammaenr.ogsky.core.api.gui;

/**
 * Factory registered by core or optional modules to build a {@link GuiScreenModel} on demand.
 */
@FunctionalInterface
public interface GuiScreenProvider {
	GuiScreenModel build(GuiBuildContext context);
}
