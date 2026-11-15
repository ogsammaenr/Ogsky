package me.ogsammaenr.ogsky.core.api.gui;

import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * UI-agnostic declarative layout describing a module GUI screen rendered by {@link GuiHost}.
 */
public record GuiScreenModel(
	Component title,
	LayoutMode layoutMode,
	int rows,
	List<GuiButton> pagedEntries,
	Map<Integer, GuiButton> pinnedButtons,
	@Nullable GuiButton contextButton,
	@Nullable GuiButton emptyStateButton
) {
	public enum LayoutMode {
		/**
		 * Standard 6-row shell: 28-slot paginated grid + header pinned slots + navigation footer (Back/Close, Context, Prev/Page/Next, Refresh).
		 */
		PAGED_GRID,
		/**
		 * Free-form 1..6 row layout where every slot is explicitly positioned via {@link #pinnedButtons}.
		 */
		CUSTOM_FIXED
	}

	public static final int DEFAULT_ROWS = 6;
	public static final int PAGED_GRID_SIZE = 28;

	public GuiScreenModel {
		Objects.requireNonNull(title, "title");
		Objects.requireNonNull(layoutMode, "layoutMode");
		rows = layoutMode == LayoutMode.PAGED_GRID ? DEFAULT_ROWS : Math.clamp(rows, 1, 6);
		int maxSlots = rows * 9;
		pagedEntries = pagedEntries == null ? List.of() : List.copyOf(pagedEntries);
		if (pinnedButtons == null || pinnedButtons.isEmpty()) {
			pinnedButtons = Map.of();
		} else {
			for (Integer slot : pinnedButtons.keySet()) {
				if (slot == null || slot < 0 || slot >= maxSlots) {
					throw new IllegalArgumentException(
						"Pinned slot index " + slot + " is out of bounds (0.." + (maxSlots - 1) + ")"
					);
				}
			}
			pinnedButtons = Map.copyOf(pinnedButtons);
		}
	}

	public static Builder paged(Component title) {
		return new Builder(title, LayoutMode.PAGED_GRID, DEFAULT_ROWS);
	}

	public static Builder paged(String title) {
		Objects.requireNonNull(title, "title");
		return paged(Component.literal(title));
	}

	public static Builder fixed(Component title, int rows) {
		return new Builder(title, LayoutMode.CUSTOM_FIXED, rows);
	}

	public static Builder fixed(String title, int rows) {
		Objects.requireNonNull(title, "title");
		return fixed(Component.literal(title), rows);
	}

	public static final class Builder {
		private final Component title;
		private final LayoutMode layoutMode;
		private final int rows;
		private final List<GuiButton> pagedEntries = new ArrayList<>();
		private final Map<Integer, GuiButton> pinnedButtons = new LinkedHashMap<>();
		private @Nullable GuiButton contextButton;
		private @Nullable GuiButton emptyStateButton;

		private Builder(Component title, LayoutMode layoutMode, int rows) {
			this.title = Objects.requireNonNull(title, "title");
			this.layoutMode = Objects.requireNonNull(layoutMode, "layoutMode");
			this.rows = rows;
		}

		public Builder addPagedEntry(GuiButton button) {
			this.pagedEntries.add(Objects.requireNonNull(button, "button"));
			return this;
		}

		public Builder addPagedEntries(List<GuiButton> buttons) {
			Objects.requireNonNull(buttons, "buttons");
			for (GuiButton button : buttons) {
				this.pagedEntries.add(Objects.requireNonNull(button, "button"));
			}
			return this;
		}

		public Builder pinButton(int slotIndex, GuiButton button) {
			this.pinnedButtons.put(slotIndex, Objects.requireNonNull(button, "button"));
			return this;
		}

		public Builder contextButton(@Nullable GuiButton contextButton) {
			this.contextButton = contextButton;
			return this;
		}

		public Builder emptyStateButton(@Nullable GuiButton emptyStateButton) {
			this.emptyStateButton = emptyStateButton;
			return this;
		}

		public GuiScreenModel build() {
			return new GuiScreenModel(
				this.title,
				this.layoutMode,
				this.rows,
				this.pagedEntries,
				this.pinnedButtons,
				this.contextButton,
				this.emptyStateButton
			);
		}
	}
}
