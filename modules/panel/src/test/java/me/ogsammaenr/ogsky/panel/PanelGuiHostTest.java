package me.ogsammaenr.ogsky.panel;

import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.gui.GuiButton;
import me.ogsammaenr.ogsky.core.api.gui.GuiOpenRequest;
import me.ogsammaenr.ogsky.core.api.gui.GuiScreenModel;
import net.minecraft.ChatFormatting;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PanelGuiHostTest {
	@Test
	void paginateModelComputesSlicesAndClampsOutOfRangePages() {
		GuiOpenRequest base = GuiOpenRequest.of(OgskyCore.id("top_list"), UUID.randomUUID());

		GuiScreenModel emptyModel = GuiScreenModel.paged("Empty").build();
		PanelGuiHost.RenderedScreen emptyView = PanelGuiHost.paginateModel(base, emptyModel);
		assertEquals(0, emptyView.page());
		assertEquals(1, emptyView.totalPages());
		assertTrue(emptyView.pagedSlice().isEmpty());
		assertFalse(emptyView.hasPreviousPage());
		assertFalse(emptyView.hasNextPage());

		List<GuiButton> exactOnePage = new ArrayList<>();
		for (int i = 0; i < 28; i++) {
			exactOnePage.add(GuiButton.of("minecraft:stone", "Entry " + i, ChatFormatting.WHITE));
		}
		GuiScreenModel exactModel = GuiScreenModel.paged("Exact").addPagedEntries(exactOnePage).build();
		PanelGuiHost.RenderedScreen exactView = PanelGuiHost.paginateModel(base, exactModel);
		assertEquals(1, exactView.totalPages());
		assertEquals(28, exactView.pagedSlice().size());
		assertFalse(exactView.hasNextPage());

		List<GuiButton> twoPages = new ArrayList<>(exactOnePage);
		twoPages.add(GuiButton.of("minecraft:diamond", "Entry 28", ChatFormatting.AQUA));
		GuiScreenModel twoPageModel = GuiScreenModel.paged("Two").addPagedEntries(twoPages).build();

		PanelGuiHost.RenderedScreen page0 = PanelGuiHost.paginateModel(base.withPage(0), twoPageModel);
		assertEquals(0, page0.page());
		assertEquals(2, page0.totalPages());
		assertEquals(28, page0.pagedSlice().size());
		assertFalse(page0.hasPreviousPage());
		assertTrue(page0.hasNextPage());

		PanelGuiHost.RenderedScreen page1 = PanelGuiHost.paginateModel(base.withPage(1), twoPageModel);
		assertEquals(1, page1.page());
		assertEquals(2, page1.totalPages());
		assertEquals(1, page1.pagedSlice().size());
		assertEquals("Entry 28", page1.pagedSlice().getFirst().name().getString());
		assertTrue(page1.hasPreviousPage());
		assertFalse(page1.hasNextPage());

		PanelGuiHost.RenderedScreen clamped = PanelGuiHost.paginateModel(base.withPage(99), twoPageModel);
		assertEquals(1, clamped.page());
		assertEquals(1, clamped.pagedSlice().size());
	}

	@Test
	void paginateModelTreatsCustomFixedAsSingleUnpagedView() {
		GuiScreenModel fixed = GuiScreenModel.fixed("Details", 3)
			.pinButton(13, GuiButton.of("minecraft:beacon", "Core Info", ChatFormatting.AQUA))
			.build();
		GuiOpenRequest req = GuiOpenRequest.of(OgskyCore.id("details"), null, 4);

		PanelGuiHost.RenderedScreen view = PanelGuiHost.paginateModel(req, fixed);
		assertEquals(0, view.page());
		assertEquals(1, view.totalPages());
		assertTrue(view.pagedSlice().isEmpty());
		assertEquals(3, view.model().rows());
	}
}
