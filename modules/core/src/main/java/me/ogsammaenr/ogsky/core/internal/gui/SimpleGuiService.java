package me.ogsammaenr.ogsky.core.internal.gui;

import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.gui.GuiHost;
import me.ogsammaenr.ogsky.core.api.gui.GuiOpenRequest;
import me.ogsammaenr.ogsky.core.api.gui.GuiScreenProvider;
import me.ogsammaenr.ogsky.core.api.gui.GuiService;
import me.ogsammaenr.ogsky.core.api.gui.IslandSummaryContext;
import me.ogsammaenr.ogsky.core.api.gui.IslandSummaryContributor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Core-owned thread-safe {@link GuiService} coordinating UI-agnostic module screen definitions,
 * summary contributors, and optional {@link GuiHost} binding.
 * Safely degrades to no-op when no UI host (e.g., {@code ogsky-panel}) is bundled.
 */
public final class SimpleGuiService implements GuiService {
	private record ContributorEntry(
		Identifier id,
		int sortOrder,
		IslandSummaryContributor contributor
	) {
		private static final Comparator<ContributorEntry> ORDER = Comparator
			.comparingInt(ContributorEntry::sortOrder)
			.thenComparing(e -> e.id().toString());
	}

	private final AtomicReference<@Nullable GuiHost> hostRef = new AtomicReference<>();
	private final ConcurrentHashMap<Identifier, GuiScreenProvider> screens = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Identifier, ContributorEntry> contributorsById = new ConcurrentHashMap<>();
	private volatile List<ContributorEntry> orderedContributors = List.of();

	@Override
	public boolean isAvailable() {
		return hostRef.get() != null;
	}

	@Override
	public void registerHost(GuiHost host) {
		Objects.requireNonNull(host, "host");
		GuiHost existing = hostRef.compareAndExchange(null, host);
		if (existing != null && existing != host) {
			throw new IllegalStateException("Duplicate GuiHost registration: already bound to " + existing.getClass().getName());
		}
	}

	@Override
	public void registerScreen(Identifier screenId, GuiScreenProvider provider) {
		Objects.requireNonNull(screenId, "screenId");
		Objects.requireNonNull(provider, "provider");
		GuiScreenProvider previous = screens.putIfAbsent(screenId, provider);
		if (previous != null) {
			throw new IllegalStateException("Duplicate GUI screen id: " + screenId);
		}
	}

	@Override
	public synchronized void registerSummaryContributor(
		Identifier contributorId,
		int sortOrder,
		IslandSummaryContributor contributor
	) {
		Objects.requireNonNull(contributorId, "contributorId");
		Objects.requireNonNull(contributor, "contributor");
		ContributorEntry entry = new ContributorEntry(contributorId, sortOrder, contributor);
		ContributorEntry previous = contributorsById.putIfAbsent(contributorId, entry);
		if (previous != null) {
			throw new IllegalStateException("Duplicate island summary contributor id: " + contributorId);
		}
		this.orderedContributors = contributorsById.values().stream()
			.sorted(ContributorEntry.ORDER)
			.toList();
	}

	@Override
	public boolean openScreen(ServerPlayer player, Identifier screenId, @Nullable UUID islandId) {
		Objects.requireNonNull(player, "player");
		Objects.requireNonNull(screenId, "screenId");
		return openScreen(player, GuiOpenRequest.of(screenId, islandId));
	}

	@Override
	public boolean openScreen(ServerPlayer player, GuiOpenRequest request) {
		Objects.requireNonNull(player, "player");
		Objects.requireNonNull(request, "request");
		GuiHost host = hostRef.get();
		if (host == null) {
			return false;
		}
		if (!MAIN_PANEL_SCREEN_ID.equals(request.screenId()) && !screens.containsKey(request.screenId())) {
			return false;
		}
		return host.openScreen(player, request);
	}

	@Override
	public void refreshIslandScreens(UUID islandId) {
		Objects.requireNonNull(islandId, "islandId");
		GuiHost host = hostRef.get();
		if (host != null) {
			host.refreshIslandScreens(islandId);
		}
	}

	@Override
	public void refreshScreen(ServerPlayer player, Identifier screenId) {
		Objects.requireNonNull(player, "player");
		Objects.requireNonNull(screenId, "screenId");
		GuiHost host = hostRef.get();
		if (host != null) {
			host.refreshPlayerScreen(player, screenId);
		}
	}

	@Override
	public Optional<GuiScreenProvider> findScreen(Identifier screenId) {
		Objects.requireNonNull(screenId, "screenId");
		return Optional.ofNullable(screens.get(screenId));
	}

	@Override
	public List<Component> collectSummaryLines(IslandSummaryContext context) {
		Objects.requireNonNull(context, "context");
		List<ContributorEntry> entries = this.orderedContributors;
		if (entries.isEmpty()) {
			return List.of();
		}
		List<Component> collected = new ArrayList<>();
		for (ContributorEntry entry : entries) {
			try {
				List<Component> lines = entry.contributor().contributeLines(context);
				if (lines != null) {
					for (Component line : lines) {
						if (line != null) {
							collected.add(line);
						}
					}
				}
			} catch (RuntimeException ex) {
				OgskyCore.LOGGER.error("IslandSummaryContributor '{}' threw while contributing summary lines", entry.id(), ex);
			}
		}
		return collected.isEmpty() ? List.of() : List.copyOf(collected);
	}
}
