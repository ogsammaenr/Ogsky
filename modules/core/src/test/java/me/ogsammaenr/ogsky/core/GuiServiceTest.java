package me.ogsammaenr.ogsky.core;

import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.gui.GuiButton;
import me.ogsammaenr.ogsky.core.api.gui.GuiClickAction;
import me.ogsammaenr.ogsky.core.api.gui.GuiConfirmSpec;
import me.ogsammaenr.ogsky.core.api.gui.GuiHost;
import me.ogsammaenr.ogsky.core.api.gui.GuiOpenRequest;
import me.ogsammaenr.ogsky.core.api.gui.GuiScreenModel;
import me.ogsammaenr.ogsky.core.api.gui.GuiScreenProvider;
import me.ogsammaenr.ogsky.core.api.gui.IslandSummaryContext;
import me.ogsammaenr.ogsky.core.internal.gui.SimpleGuiService;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuiServiceTest {
	@Test
	void hostRegistrationAvailabilityAndNoOpSafety() {
		SimpleGuiService guis = new SimpleGuiService();
		assertFalse(guis.isAvailable());

		UUID islandId = UUID.randomUUID();
		// Safe no-ops when no GuiHost is bundled (e.g. -Pmodules=core or -Pmodules=core,level)
		guis.refreshIslandScreens(islandId);

		List<UUID> refreshed = new ArrayList<>();
		GuiHost host = new GuiHost() {
			@Override
			public boolean openScreen(ServerPlayer player, GuiOpenRequest request) {
				return true;
			}

			@Override
			public void refreshIslandScreens(UUID targetIslandId) {
				refreshed.add(targetIslandId);
			}

			@Override
			public void refreshPlayerScreen(ServerPlayer player, Identifier screenId) {
			}
		};

		guis.registerHost(host);
		assertTrue(guis.isAvailable());
		// Re-registering same instance is idempotent
		guis.registerHost(host);

		GuiHost secondHost = new GuiHost() {
			@Override
			public boolean openScreen(ServerPlayer player, GuiOpenRequest request) {
				return false;
			}

			@Override
			public void refreshIslandScreens(UUID targetIslandId) {
			}

			@Override
			public void refreshPlayerScreen(ServerPlayer player, Identifier screenId) {
			}
		};
		assertThrows(IllegalStateException.class, () -> guis.registerHost(secondHost));

		guis.refreshIslandScreens(islandId);
		assertEquals(List.of(islandId), refreshed);
	}

	@Test
	void screensRejectDuplicatesAndLookupWorks() {
		SimpleGuiService guis = new SimpleGuiService();
		Identifier screenId = OgskyCore.id("test_screen");
		assertTrue(guis.findScreen(screenId).isEmpty());

		GuiScreenProvider provider = ctx -> GuiScreenModel.paged("Test Title").build();
		guis.registerScreen(screenId, provider);
		assertTrue(guis.findScreen(screenId).isPresent());
		assertThrows(IllegalStateException.class, () -> guis.registerScreen(screenId, provider));
	}

	@Test
	void summaryContributorsSortDeterministicallyAndIsolateFailures() {
		SimpleGuiService guis = new SimpleGuiService();
		Identifier bId = OgskyCore.id("b_contrib");
		Identifier aId = OgskyCore.id("a_contrib");
		Identifier brokenId = OgskyCore.id("broken_contrib");
		Identifier lateId = OgskyCore.id("late_contrib");

		guis.registerSummaryContributor(bId, 10, ctx -> List.of(Component.literal("Order10-B")));
		guis.registerSummaryContributor(aId, 10, ctx -> List.of(Component.literal("Order10-A")));
		guis.registerSummaryContributor(brokenId, 20, ctx -> {
			throw new RuntimeException("simulated module error");
		});
		guis.registerSummaryContributor(lateId, 30, ctx -> List.of(Component.literal("Order30")));

		assertThrows(
			IllegalStateException.class,
			() -> guis.registerSummaryContributor(aId, 5, ctx -> List.of())
		);

		List<Component> collected = guis.collectSummaryLines(
			new IslandSummaryContext(UUID.randomUUID(), UUID.randomUUID(), (byte) 5, false)
		);
		assertEquals(
			List.of("Order10-A", "Order10-B", "Order30"),
			collected.stream().map(Component::getString).toList()
		);
	}

	@Test
	void declarativeModelsEnforceValidationAndBackDepthCaps() {
		assertThrows(IllegalArgumentException.class, () -> GuiConfirmSpec.of("  ", "Click again"));
		assertThrows(
			IllegalArgumentException.class,
			() -> GuiButton.of("   ", "Bad", ChatFormatting.RED)
		);
		assertThrows(
			IllegalArgumentException.class,
			() -> GuiScreenModel.fixed("Fixed", 2)
				.pinButton(18, GuiButton.of("minecraft:stone", "OutOfBounds", ChatFormatting.GRAY))
				.build()
		);

		GuiButton clamped = GuiButton.builder("minecraft:diamond", "Test", ChatFormatting.AQUA)
			.count(200)
			.lore(Component.literal("Line 1"), null, Component.literal("Line 2"))
			.glowing(true)
			.build();
		assertEquals(99, clamped.count());
		assertEquals(2, clamped.lore().size());
		assertTrue(clamped.glowing());

		GuiScreenModel clampedRows = GuiScreenModel.fixed("Compact", 99).build();
		assertEquals(6, clampedRows.rows());
		assertEquals(GuiScreenModel.LayoutMode.CUSTOM_FIXED, clampedRows.layoutMode());

		// Back navigation chain truncates safely at MAX_BACK_DEPTH (16)
		GuiOpenRequest chain = GuiOpenRequest.of(OgskyCore.id("root"), null);
		for (int i = 0; i < 25; i++) {
			chain = GuiOpenRequest.of(OgskyCore.id("child_" + i), null).withBackRequest(chain);
		}
		int depth = 0;
		GuiOpenRequest cursor = chain.backRequest();
		while (cursor != null) {
			depth++;
			cursor = cursor.backRequest();
		}
		assertEquals(16, depth);

		GuiOpenRequest withState = GuiOpenRequest.of(OgskyCore.id("paged"), UUID.randomUUID(), 3)
			.withState("sort", "level");
		assertEquals(0, withState.page()); // Changing filter state resets to page 0
		assertEquals("level", withState.state().get("sort"));
		assertNull(withState.backScreenId());

		assertTrue(GuiClickAction.SHIFT_LEFT.isLeft());
		assertTrue(GuiClickAction.SHIFT_LEFT.isShift());
		assertTrue(GuiClickAction.RIGHT.isRight());
		assertFalse(GuiClickAction.RIGHT.isShift());
	}
}
