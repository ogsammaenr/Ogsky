package me.ogsammaenr.ogsky.panel;

import me.ogsammaenr.ogsky.core.api.action.IslandActionDescriptor;
import me.ogsammaenr.ogsky.core.api.flag.FlagMode;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, UI-agnostic ViewModel snapshot representing a {@code TabbedPanel} state.
 * Rendered to a vanilla server {@code ChestMenu} in Phase 2 and reusable by optional client UIs in Phase 4+.
 */
public record PanelSnapshot(
	@Nullable UUID islandId,
	@Nullable IslandSummary island,
	UUID viewerUuid,
	byte viewerRole,
	boolean viewerAdminBypass,
	PanelTab activeTab,
	FlagModeFilter flagMode,
	int page,
	int totalPages,
	int totalEntries,
	List<ActionEntry> actions,
	List<ProtectionFlagEntry> protectionFlags,
	List<SettingFlagEntry> settingFlags,
	List<MemberEntry> members,
	List<BlueprintEntry> blueprints
) {
	public static final int PAGE_SIZE = 28;

	public PanelSnapshot {
		Objects.requireNonNull(viewerUuid, "viewerUuid");
		Objects.requireNonNull(activeTab, "activeTab");
		Objects.requireNonNull(flagMode, "flagMode");
		totalPages = Math.max(1, totalPages);
		page = Math.clamp(page, 0, totalPages - 1);
		totalEntries = Math.max(0, totalEntries);
		actions = List.copyOf(actions);
		protectionFlags = List.copyOf(protectionFlags);
		settingFlags = List.copyOf(settingFlags);
		members = List.copyOf(members);
		blueprints = List.copyOf(blueprints);
	}

	/**
	 * Backwards-compatible summary list of visible action entries on the {@link PanelTab#ACTIONS} tab.
	 */
	public List<PanelEntry> entries() {
		return actions.stream()
			.map(entry -> new PanelEntry(entry.actionId(), entry.title(), entry.iconItemId()))
			.toList();
	}

	public boolean hasPreviousPage() {
		return page > 0;
	}

	public boolean hasNextPage() {
		return page + 1 < totalPages;
	}

	public record IslandSummary(
		UUID islandId,
		UUID ownerId,
		String ownerName,
		int gridX,
		int gridZ,
		int centerX,
		int centerZ,
		short halfSize,
		String bundleId,
		Instant createdAt,
		boolean overworldReady,
		boolean netherReady,
		boolean endReady,
		boolean pasting,
		boolean deleting,
		boolean locked,
		int memberCount,
		int bannedCount,
		List<Component> contributedLines
	) {
		public IslandSummary {
			contributedLines = contributedLines == null ? List.of() : List.copyOf(contributedLines);
		}

		public IslandSummary(
			UUID islandId,
			UUID ownerId,
			String ownerName,
			int gridX,
			int gridZ,
			int centerX,
			int centerZ,
			short halfSize,
			String bundleId,
			Instant createdAt,
			boolean overworldReady,
			boolean netherReady,
			boolean endReady,
			boolean pasting,
			boolean deleting,
			boolean locked,
			int memberCount,
			int bannedCount
		) {
			this(
				islandId,
				ownerId,
				ownerName,
				gridX,
				gridZ,
				centerX,
				centerZ,
				halfSize,
				bundleId,
				createdAt,
				overworldReady,
				netherReady,
				endReady,
				pasting,
				deleting,
				locked,
				memberCount,
				bannedCount,
				List.of()
			);
		}

		public int sideLength() {
			return halfSize * 2 + 1;
		}
	}

	public record ActionEntry(
		Identifier actionId,
		String title,
		String description,
		String iconItemId,
		boolean closePanelOnExecute,
		@Nullable Boolean activeState,
		@Nullable String stateLabel,
		@Nullable String badgeLabel,
		boolean invertActiveColor
	) {
		public ActionEntry(
			Identifier actionId,
			String title,
			String description,
			String iconItemId,
			boolean closePanelOnExecute
		) {
			this(actionId, title, description, iconItemId, closePanelOnExecute, null, null, null, true);
		}

		public ActionEntry(
			Identifier actionId,
			String title,
			String description,
			String iconItemId,
			boolean closePanelOnExecute,
			@Nullable Boolean activeState
		) {
			this(actionId, title, description, iconItemId, closePanelOnExecute, activeState, null, null, true);
		}

		public static ActionEntry fromDescriptor(IslandActionDescriptor descriptor) {
			return new ActionEntry(
				descriptor.id(),
				descriptor.title(),
				descriptor.description(),
				descriptor.iconItemId(),
				descriptor.closePanelOnExecute(),
				null
			);
		}
	}

	public record ProtectionFlagEntry(
		Identifier flagId,
		String title,
		FlagMode mode,
		String iconItemId,
		byte minRank,
		String minRankName,
		byte defaultMinRank,
		String defaultMinRankName,
		boolean editable
	) {
	}

	public record SettingFlagEntry(
		Identifier flagId,
		String title,
		FlagMode mode,
		String iconItemId,
		boolean enabled,
		boolean defaultValue,
		boolean editable
	) {
	}

	public record MemberEntry(
		Kind kind,
		UUID playerUuid,
		String displayName,
		byte role,
		String roleName,
		boolean banned,
		boolean canPromote,
		boolean canDemote,
		boolean canRemoveOrKick,
		boolean canBan,
		boolean canUnban
	) {
		public enum Kind {
			PRIMARY_MEMBER,
			SECONDARY_HELPER,
			BANNED_PLAYER
		}
	}

	public record BlueprintEntry(
		String id,
		String displayName,
		String description,
		String iconItemId,
		boolean hasOverworld,
		boolean hasNether,
		boolean hasEnd,
		boolean defaultBundle,
		boolean activeOnIsland,
		boolean permitted,
		boolean requiresPermission
	) {
	}

	public record PanelEntry(Identifier actionId, String title, String iconItemId) {
		public static PanelEntry fromDescriptor(IslandActionDescriptor descriptor) {
			return new PanelEntry(descriptor.id(), descriptor.title(), descriptor.iconItemId());
		}
	}
}
