package me.ogsammaenr.ogsky.panel;

import me.ogsammaenr.ogsky.core.api.OgskyCore;
import me.ogsammaenr.ogsky.core.api.OgskyCoreApi;
import me.ogsammaenr.ogsky.core.api.action.IslandActionDescriptor;
import me.ogsammaenr.ogsky.core.api.blueprint.BlueprintBundleView;
import me.ogsammaenr.ogsky.core.api.flag.FlagType;
import me.ogsammaenr.ogsky.core.api.flag.IslandFlagDefinition;
import me.ogsammaenr.ogsky.core.api.gui.IslandSummaryContext;
import me.ogsammaenr.ogsky.core.api.island.IslandMemberEntry;
import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import me.ogsammaenr.ogsky.core.api.island.IslandService;
import me.ogsammaenr.ogsky.core.api.island.IslandView;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Builds paginated, UI-agnostic {@link PanelSnapshot} instances exclusively from {@link OgskyCoreApi} contracts.
 */
public final class PanelViewModelService {
	public static final Identifier ISLAND_LOCK_FLAG = OgskyCore.id("island_lock");
	private static final Identifier TOGGLE_LOCK_ACTION = OgskyCore.id("island_toggle_lock");
	private static final Set<String> UPPERCASE_TOKENS = Set.of("tnt", "xp", "ai");
	private static final Map<String, String> MIXED_CASE_TOKENS = Map.of(
		"pvp", "PvP"
	);

	public record PanelViewData(
		@Nullable UUID islandId,
		PanelSnapshot.@Nullable IslandSummary island,
		UUID viewerUuid,
		byte viewerRole,
		boolean viewerAdminBypass,
		PanelTab activeTab,
		FlagModeFilter flagMode,
		List<PanelSnapshot.ActionEntry> actions,
		List<PanelSnapshot.ProtectionFlagEntry> protectionFlags,
		List<PanelSnapshot.SettingFlagEntry> settingFlags,
		List<PanelSnapshot.MemberEntry> members,
		List<PanelSnapshot.BlueprintEntry> blueprints
	) {
	}

	private final OgskyCoreApi core;

	public PanelViewModelService(OgskyCoreApi core) {
		this.core = Objects.requireNonNull(core, "core");
	}

	/**
	 * Builds a first-page {@link PanelTab#ACTIONS} snapshot using default resolver functions (primarily for unit tests).
	 */
	/**
	 * Builds unpaginated, optionally search-filtered panel view data for connected viewers (used by Ogui screens).
	 */
	public PanelViewData buildFullData(
		@Nullable UUID islandId,
		ServerPlayer viewer,
		PanelTab activeTab,
		FlagModeFilter flagMode,
		@Nullable String searchQuery
	) {
		Objects.requireNonNull(viewer, "viewer");
		Objects.requireNonNull(activeTab, "activeTab");
		Objects.requireNonNull(flagMode, "flagMode");

		MinecraftServer server = viewer.level().getServer();
		boolean viewerAdminBypass = core.permissions().isBypassActive(viewer);
		UUID viewerUuid = viewer.getUUID();
		String normalizedQuery = searchQuery == null ? "" : searchQuery.trim().toLowerCase(Locale.ROOT);

		IslandView island = islandId == null ? null : core.islands().findById(islandId).orElse(null);
		byte viewerRole = island == null ? IslandRole.VISITOR : island.roleOf(viewerUuid);
		PanelSnapshot.IslandSummary summary = island == null
			? null
			: buildSummary(island, viewerUuid, viewerRole, viewerAdminBypass, uuid -> resolvePlayerName(server, uuid));

		List<PanelSnapshot.ActionEntry> actions = List.of();
		List<PanelSnapshot.ProtectionFlagEntry> protectionFlags = List.of();
		List<PanelSnapshot.SettingFlagEntry> settingFlags = List.of();
		List<PanelSnapshot.MemberEntry> members = List.of();
		List<PanelSnapshot.BlueprintEntry> blueprints = List.of();

		switch (activeTab) {
			case ACTIONS -> actions = buildActionEntries(island, summary).stream()
				.filter(e -> normalizedQuery.isEmpty() || containsAny(normalizedQuery, e.title(), e.description(), e.actionId().toString()))
				.toList();
			case PROTECTION_FLAGS -> protectionFlags = buildProtectionFlags(island, viewerRole, viewerAdminBypass, flagMode).stream()
				.filter(e -> normalizedQuery.isEmpty() || containsAny(normalizedQuery, e.title(), e.flagId().toString(), e.minRankName()))
				.toList();
			case SETTING_FLAGS -> settingFlags = buildSettingFlags(island, viewerRole, viewerAdminBypass, flagMode).stream()
				.filter(e -> normalizedQuery.isEmpty() || containsAny(normalizedQuery, e.title(), e.flagId().toString()))
				.toList();
			case MEMBERS -> members = buildMembers(island, viewerUuid, viewerRole, viewerAdminBypass, uuid -> resolvePlayerName(server, uuid)).stream()
				.filter(e -> normalizedQuery.isEmpty() || containsAny(normalizedQuery, e.displayName(), e.roleName()))
				.toList();
			case BLUEPRINTS_INFO -> blueprints = buildBlueprints(island, bundle -> core.permissions().hasBlueprintPermission(viewer, bundle)).stream()
				.filter(e -> normalizedQuery.isEmpty() || containsAny(normalizedQuery, e.displayName(), e.description(), e.id()))
				.toList();
		}

		return new PanelViewData(
			island == null ? null : island.islandId(),
			summary,
			viewerUuid,
			viewerRole,
			viewerAdminBypass,
			activeTab,
			flagMode,
			actions,
			protectionFlags,
			settingFlags,
			members,
			blueprints
		);
	}

	private static boolean containsAny(String query, String... fields) {
		for (String field : fields) {
			if (field != null && field.toLowerCase(Locale.ROOT).contains(query)) {
				return true;
			}
		}
		return false;
	}

	private List<PanelSnapshot.ActionEntry> buildActionEntries(
		@Nullable IslandView island,
		PanelSnapshot.@Nullable IslandSummary summary
	) {
		if (island == null) {
			return List.of();
		}
		return core.actions().listOrdered().stream()
			.map(descriptor -> buildActionEntry(descriptor, summary))
			.toList();
	}

	public PanelSnapshot buildSnapshot(UUID islandId) {
		Objects.requireNonNull(islandId, "islandId");
		UUID viewerUuid = core.islands().findById(islandId)
			.map(IslandView::ownerId)
			.orElse(islandId);
		return buildSnapshot(
			islandId,
			viewerUuid,
			false,
			PanelTab.ACTIONS,
			FlagModeFilter.BASIC,
			0,
			PanelViewModelService::shortUuid,
			bundle -> true
		);
	}

	/**
	 * Builds a snapshot tailored for a connected {@link ServerPlayer}, resolving profile names and permissions via core APIs.
	 */
	public PanelSnapshot buildSnapshot(
		@Nullable UUID islandId,
		ServerPlayer viewer,
		PanelTab activeTab,
		FlagModeFilter flagMode,
		int requestedPage
	) {
		Objects.requireNonNull(viewer, "viewer");
		MinecraftServer server = viewer.level().getServer();
		boolean adminBypass = core.permissions().isBypassActive(viewer);
		return buildSnapshot(
			islandId,
			viewer.getUUID(),
			adminBypass,
			activeTab,
			flagMode,
			requestedPage,
			uuid -> resolvePlayerName(server, uuid),
			bundle -> core.permissions().hasBlueprintPermission(viewer, bundle)
		);
	}

	public PanelSnapshot buildSnapshot(
		@Nullable UUID islandId,
		UUID viewerUuid,
		boolean viewerAdminBypass,
		PanelTab activeTab,
		FlagModeFilter flagMode,
		int requestedPage,
		Function<UUID, String> playerNameResolver,
		Predicate<BlueprintBundleView> blueprintPermissionChecker
	) {
		Objects.requireNonNull(viewerUuid, "viewerUuid");
		Objects.requireNonNull(activeTab, "activeTab");
		Objects.requireNonNull(flagMode, "flagMode");
		Objects.requireNonNull(playerNameResolver, "playerNameResolver");
		Objects.requireNonNull(blueprintPermissionChecker, "blueprintPermissionChecker");

		IslandService islands = core.islands();
		IslandView island = islandId == null ? null : islands.findById(islandId).orElse(null);
		byte viewerRole = island == null ? IslandRole.VISITOR : island.roleOf(viewerUuid);
		PanelSnapshot.IslandSummary summary = island == null
			? null
			: buildSummary(island, viewerUuid, viewerRole, viewerAdminBypass, playerNameResolver);

		return switch (activeTab) {
			case ACTIONS -> {
				List<PanelSnapshot.ActionEntry> all = buildActionEntries(island, summary);
				PageSlice<PanelSnapshot.ActionEntry> slice = paginate(all, requestedPage);
				yield new PanelSnapshot(
					island == null ? null : island.islandId(),
					summary,
					viewerUuid,
					viewerRole,
					viewerAdminBypass,
					activeTab,
					flagMode,
					slice.page(),
					slice.totalPages(),
					all.size(),
					slice.items(),
					List.of(),
					List.of(),
					List.of(),
					List.of()
				);
			}
			case PROTECTION_FLAGS -> {
				List<PanelSnapshot.ProtectionFlagEntry> all = buildProtectionFlags(island, viewerRole, viewerAdminBypass, flagMode);
				PageSlice<PanelSnapshot.ProtectionFlagEntry> slice = paginate(all, requestedPage);
				yield new PanelSnapshot(
					island == null ? null : island.islandId(),
					summary,
					viewerUuid,
					viewerRole,
					viewerAdminBypass,
					activeTab,
					flagMode,
					slice.page(),
					slice.totalPages(),
					all.size(),
					List.of(),
					slice.items(),
					List.of(),
					List.of(),
					List.of()
				);
			}
			case SETTING_FLAGS -> {
				List<PanelSnapshot.SettingFlagEntry> all = buildSettingFlags(island, viewerRole, viewerAdminBypass, flagMode);
				PageSlice<PanelSnapshot.SettingFlagEntry> slice = paginate(all, requestedPage);
				yield new PanelSnapshot(
					island == null ? null : island.islandId(),
					summary,
					viewerUuid,
					viewerRole,
					viewerAdminBypass,
					activeTab,
					flagMode,
					slice.page(),
					slice.totalPages(),
					all.size(),
					List.of(),
					List.of(),
					slice.items(),
					List.of(),
					List.of()
				);
			}
			case MEMBERS -> {
				List<PanelSnapshot.MemberEntry> all = buildMembers(island, viewerUuid, viewerRole, viewerAdminBypass, playerNameResolver);
				PageSlice<PanelSnapshot.MemberEntry> slice = paginate(all, requestedPage);
				yield new PanelSnapshot(
					island == null ? null : island.islandId(),
					summary,
					viewerUuid,
					viewerRole,
					viewerAdminBypass,
					activeTab,
					flagMode,
					slice.page(),
					slice.totalPages(),
					all.size(),
					List.of(),
					List.of(),
					List.of(),
					slice.items(),
					List.of()
				);
			}
			case BLUEPRINTS_INFO -> {
				List<PanelSnapshot.BlueprintEntry> all = buildBlueprints(island, blueprintPermissionChecker);
				PageSlice<PanelSnapshot.BlueprintEntry> slice = paginate(all, requestedPage);
				yield new PanelSnapshot(
					island == null ? null : island.islandId(),
					summary,
					viewerUuid,
					viewerRole,
					viewerAdminBypass,
					activeTab,
					flagMode,
					slice.page(),
					slice.totalPages(),
					all.size(),
					List.of(),
					List.of(),
					List.of(),
					List.of(),
					slice.items()
				);
			}
		};
	}

	private static PanelSnapshot.ActionEntry buildActionEntry(
		IslandActionDescriptor descriptor,
		PanelSnapshot.@Nullable IslandSummary summary
	) {
		if (summary != null && TOGGLE_LOCK_ACTION.equals(descriptor.id())) {
			boolean locked = summary.locked();
			return new PanelSnapshot.ActionEntry(
				descriptor.id(),
				locked ? "Island Lock: LOCKED" : "Island Lock: UNLOCKED",
				locked
					? "Visitors are currently blocked from entering. Click to unlock."
					: "Visitors can currently enter your island. Click to lock.",
				locked ? "minecraft:iron_bars" : "minecraft:oak_door",
				descriptor.closePanelOnExecute(),
				locked
			);
		}
		return PanelSnapshot.ActionEntry.fromDescriptor(descriptor);
	}

	private PanelSnapshot.IslandSummary buildSummary(
		IslandView island,
		UUID viewerUuid,
		byte viewerRole,
		boolean viewerAdminBypass,
		Function<UUID, String> playerNameResolver
	) {
		boolean locked = core.flags().find(ISLAND_LOCK_FLAG)
			.map(def -> island.isSettingEnabled(def.id()))
			.orElse(false);
		List<Component> contributedLines = core.guis().collectSummaryLines(
			new IslandSummaryContext(island.islandId(), viewerUuid, viewerRole, viewerAdminBypass)
		);
		return new PanelSnapshot.IslandSummary(
			island.islandId(),
			island.ownerId(),
			playerNameResolver.apply(island.ownerId()),
			island.gridX(),
			island.gridZ(),
			island.centerX(),
			island.centerZ(),
			island.halfSize(),
			island.bundleId(),
			island.createdAt(),
			island.isOverworldReady(),
			island.isNetherReady(),
			island.isEndReady(),
			island.isPasting(),
			island.isDeleting(),
			locked,
			island.members().size(),
			island.bannedPlayers().size(),
			contributedLines
		);
	}

	private List<PanelSnapshot.ProtectionFlagEntry> buildProtectionFlags(
		@Nullable IslandView island,
		byte viewerRole,
		boolean viewerAdminBypass,
		FlagModeFilter flagMode
	) {
		if (island == null) {
			return List.of();
		}
		boolean editable = viewerAdminBypass || viewerRole >= IslandRole.ADMIN;
		return core.flags().listByType(FlagType.PROTECTION).stream()
			.filter(def -> flagMode.matches(def.mode()))
			.sorted(Comparator.comparingInt(IslandFlagDefinition::sortOrder)
				.thenComparing(def -> def.id().toString()))
			.map(def -> {
				byte currentRank = island.protectionRank(def.id());
				return new PanelSnapshot.ProtectionFlagEntry(
					def.id(),
					formatFlagTitle(def.id()),
					def.mode(),
					def.iconItemId(),
					currentRank,
					core.islands().roleDisplayName(currentRank),
					def.defaultMinRank(),
					core.islands().roleDisplayName(def.defaultMinRank()),
					editable
				);
			})
			.toList();
	}

	private List<PanelSnapshot.SettingFlagEntry> buildSettingFlags(
		@Nullable IslandView island,
		byte viewerRole,
		boolean viewerAdminBypass,
		FlagModeFilter flagMode
	) {
		if (island == null) {
			return List.of();
		}
		boolean editable = viewerAdminBypass || viewerRole >= IslandRole.ADMIN;
		return core.flags().listByType(FlagType.SETTING).stream()
			.filter(def -> flagMode.matches(def.mode()))
			.sorted(Comparator.comparingInt(IslandFlagDefinition::sortOrder)
				.thenComparing(def -> def.id().toString()))
			.map(def -> new PanelSnapshot.SettingFlagEntry(
				def.id(),
				formatFlagTitle(def.id()),
				def.mode(),
				def.iconItemId(),
				island.isSettingEnabled(def.id()),
				def.defaultBoolValue(),
				editable
			))
			.toList();
	}

	private List<PanelSnapshot.MemberEntry> buildMembers(
		@Nullable IslandView island,
		UUID viewerUuid,
		byte viewerRole,
		boolean viewerAdminBypass,
		Function<UUID, String> playerNameResolver
	) {
		if (island == null) {
			return List.of();
		}
		boolean isOwnerOrBypass = viewerAdminBypass || island.ownerId().equals(viewerUuid);
		boolean isAdminOrBypass = viewerAdminBypass || viewerRole >= IslandRole.ADMIN;
		List<PanelSnapshot.MemberEntry> entries = new ArrayList<>();

		List<IslandMemberEntry> sortedMembers = island.members().stream()
			.sorted(Comparator.<IslandMemberEntry>comparingInt(IslandMemberEntry::role).reversed()
				.thenComparing(member -> playerNameResolver.apply(member.playerUuid()), String.CASE_INSENSITIVE_ORDER))
			.toList();

		for (IslandMemberEntry member : sortedMembers) {
			byte role = member.role();
			UUID targetId = member.playerUuid();
			String name = playerNameResolver.apply(targetId);
			boolean primary = IslandRole.isPrimary(role);
			PanelSnapshot.MemberEntry.Kind kind = primary
				? PanelSnapshot.MemberEntry.Kind.PRIMARY_MEMBER
				: PanelSnapshot.MemberEntry.Kind.SECONDARY_HELPER;

			boolean canPromote = primary && role == IslandRole.MEMBER && isOwnerOrBypass;
			boolean canDemote = primary && role == IslandRole.ADMIN && isOwnerOrBypass;
			boolean canRemoveOrKick = !targetId.equals(viewerUuid)
				&& role < IslandRole.OWNER
				&& (viewerAdminBypass || (viewerRole >= IslandRole.ADMIN && (!primary || viewerRole > role)));
			boolean canBan = !targetId.equals(viewerUuid)
				&& role < IslandRole.OWNER
				&& (viewerAdminBypass || (viewerRole >= IslandRole.ADMIN && viewerRole > role));

			entries.add(new PanelSnapshot.MemberEntry(
				kind,
				targetId,
				name,
				role,
				core.islands().roleDisplayName(role),
				false,
				canPromote,
				canDemote,
				canRemoveOrKick,
				canBan,
				false
			));
		}

		List<UUID> sortedBans = island.bannedPlayers().stream()
			.sorted(Comparator.comparing(playerNameResolver, String.CASE_INSENSITIVE_ORDER))
			.toList();
		for (UUID bannedId : sortedBans) {
			entries.add(new PanelSnapshot.MemberEntry(
				PanelSnapshot.MemberEntry.Kind.BANNED_PLAYER,
				bannedId,
				playerNameResolver.apply(bannedId),
				IslandRole.VISITOR,
				"Banned",
				true,
				false,
				false,
				false,
				false,
				isAdminOrBypass
			));
		}

		return List.copyOf(entries);
	}

	private List<PanelSnapshot.BlueprintEntry> buildBlueprints(
		@Nullable IslandView island,
		Predicate<BlueprintBundleView> blueprintPermissionChecker
	) {
		String defaultId = core.islands().defaultBlueprintId();
		String activeBundleId = island == null ? null : island.bundleId();
		return core.islands().availableBlueprints().stream()
			.map(bundle -> new PanelSnapshot.BlueprintEntry(
				bundle.id(),
				bundle.displayName(),
				bundle.description(),
				bundle.iconItemId(),
				bundle.hasOverworld(),
				bundle.hasNether(),
				bundle.hasEnd(),
				defaultId.equals(bundle.id()),
				activeBundleId != null && activeBundleId.equals(bundle.id()),
				blueprintPermissionChecker.test(bundle),
				bundle.requiresPermission()
			))
			.toList();
	}

	private static <T> PageSlice<T> paginate(List<T> items, int requestedPage) {
		int totalPages = Math.max(1, (items.size() + PanelSnapshot.PAGE_SIZE - 1) / PanelSnapshot.PAGE_SIZE);
		int clampedPage = Math.clamp(requestedPage, 0, totalPages - 1);
		if (items.isEmpty()) {
			return new PageSlice<>(clampedPage, totalPages, List.of());
		}
		int from = clampedPage * PanelSnapshot.PAGE_SIZE;
		int to = Math.min(items.size(), from + PanelSnapshot.PAGE_SIZE);
		return new PageSlice<>(clampedPage, totalPages, items.subList(from, to));
	}

	public static String formatFlagTitle(Identifier id) {
		String[] parts = id.getPath().split("_");
		StringBuilder builder = new StringBuilder();
		for (String part : parts) {
			if (part.isEmpty()) {
				continue;
			}
			if (!builder.isEmpty()) {
				builder.append(' ');
			}
			String lower = part.toLowerCase(Locale.ROOT);
			if (MIXED_CASE_TOKENS.containsKey(lower)) {
				builder.append(MIXED_CASE_TOKENS.get(lower));
			} else if (UPPERCASE_TOKENS.contains(lower)) {
				builder.append(lower.toUpperCase(Locale.ROOT));
			} else {
				builder.append(Character.toUpperCase(lower.charAt(0)))
					.append(lower.substring(1));
			}
		}
		return builder.isEmpty() ? id.toString() : builder.toString();
	}

	public static String resolvePlayerName(MinecraftServer server, UUID uuid) {
		ServerPlayer online = server.getPlayerList().getPlayer(uuid);
		if (online != null) {
			return online.getScoreboardName();
		}
		return server.services().nameToIdCache().get(uuid)
			.map(NameAndId::name)
			.orElseGet(() -> shortUuid(uuid));
	}

	private static String shortUuid(UUID uuid) {
		return uuid.toString().substring(0, 8);
	}

	private record PageSlice<T>(int page, int totalPages, List<T> items) {
	}
}
