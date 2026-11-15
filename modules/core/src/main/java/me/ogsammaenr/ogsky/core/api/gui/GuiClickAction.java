package me.ogsammaenr.ogsky.core.api.gui;

/**
 * Mouse/modifier interaction types forwarded from UI renderers to {@link GuiButton} click handlers.
 */
public enum GuiClickAction {
	LEFT,
	RIGHT,
	SHIFT_LEFT,
	SHIFT_RIGHT;

	public boolean isLeft() {
		return this == LEFT || this == SHIFT_LEFT;
	}

	public boolean isRight() {
		return this == RIGHT || this == SHIFT_RIGHT;
	}

	public boolean isShift() {
		return this == SHIFT_LEFT || this == SHIFT_RIGHT;
	}
}
