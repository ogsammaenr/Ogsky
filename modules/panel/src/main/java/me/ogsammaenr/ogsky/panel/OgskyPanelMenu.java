package me.ogsammaenr.ogsky.panel;

import me.ogsammaenr.ogsky.core.api.flag.FlagMode;
import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import me.ogsammaenr.ogsky.panel.PanelController.ConfirmKind;
import me.ogsammaenr.ogsky.panel.PanelController.PendingConfirmation;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-side 6-row vanilla {@link net.minecraft.world.inventory.ChestMenu} rendering {@link PanelSnapshot} state.
 */
public final class OgskyPanelMenu extends OgskyReadOnlyChestMenu {
	public static final int ROWS = DEFAULT_ROWS;
	public static final int MENU_SIZE = ROWS * 9;
	public static final int SLOT_LOCK_STATUS = 7;
	public static final int SLOT_ISLAND_SUMMARY = 8;

	private static final DateTimeFormatter DATE_FORMATTER =
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

	private final PanelController controller;
	private PanelSnapshot snapshot;

	public OgskyPanelMenu(
		int containerId,
		Inventory playerInventory,
		PanelController controller,
		PanelSnapshot initialSnapshot
	) {
		super(containerId, playerInventory, ROWS);
		this.controller = Objects.requireNonNull(controller, "controller");
		this.snapshot = Objects.requireNonNull(initialSnapshot, "initialSnapshot");
		this.controller.registerOpenMenu(this);
		renderSnapshot(initialSnapshot, viewer().level().getServer(), null);
	}

	public PanelSnapshot snapshot() {
		return snapshot;
	}

	public void renderSnapshot(
		PanelSnapshot nextSnapshot,
		MinecraftServer server,
		@Nullable PendingConfirmation pendingConfirm
	) {
		this.snapshot = Objects.requireNonNull(nextSnapshot, "nextSnapshot");
		Container container = getContainer();
		fillBackground(container);

		renderTabBar(container, server);
		renderContent(container, server, pendingConfirm);
		renderFooter(container);
		setCarried(ItemStack.EMPTY);
		broadcastChanges();
	}

	private void renderTabBar(Container container, MinecraftServer server) {
		PanelTab[] tabs = PanelTab.values();
		for (int i = 0; i < tabs.length; i++) {
			PanelTab tab = tabs[i];
			boolean active = tab == snapshot.activeTab();
			boolean disabledNoIsland = snapshot.islandId() == null && tab != PanelTab.BLUEPRINTS_INFO;
			ChatFormatting color = active
				? ChatFormatting.AQUA
				: (disabledNoIsland ? ChatFormatting.DARK_GRAY : ChatFormatting.YELLOW);
			List<Component> lore = new ArrayList<>();
			if (active) {
				lore.add(line("Currently Viewing", ChatFormatting.GREEN));
			} else if (disabledNoIsland) {
				lore.add(line("Create an island first to unlock this tab.", ChatFormatting.RED));
			} else {
				lore.add(line("Click to open " + tab.displayName(), ChatFormatting.GRAY));
			}
			container.setItem(
				i,
				buildItem(tab.iconItemId(), line(tab.displayName(), color), lore, active)
			);
		}
		PanelSnapshot.IslandSummary summary = snapshot.island();
		if (summary != null) {
			container.setItem(SLOT_LOCK_STATUS, buildLockStatusItem(summary));
		}
		container.setItem(SLOT_ISLAND_SUMMARY, buildIslandSummaryItem(server));
	}

	private ItemStack buildLockStatusItem(PanelSnapshot.IslandSummary summary) {
		boolean locked = summary.locked();
		boolean canEdit = snapshot.viewerAdminBypass() || snapshot.viewerRole() >= IslandRole.ADMIN;
		List<Component> lore = new ArrayList<>();
		lore.add(line("State: " + (locked ? "LOCKED" : "UNLOCKED"), locked ? ChatFormatting.RED : ChatFormatting.GREEN));
		lore.add(line(
			locked ? "Visitors cannot enter your island." : "Visitors are allowed to enter your island.",
			ChatFormatting.GRAY
		));
		if (canEdit) {
			lore.add(line(locked ? "Click to UNLOCK island" : "Click to LOCK island", ChatFormatting.YELLOW));
		} else {
			lore.add(line("View only (requires Admin rank)", ChatFormatting.RED));
		}
		return buildItem(
			locked ? Items.IRON_BARS : Items.OAK_DOOR,
			line(locked ? "Island Access: LOCKED" : "Island Access: UNLOCKED", locked ? ChatFormatting.RED : ChatFormatting.GREEN),
			lore,
			locked
		);
	}

	private ItemStack buildIslandSummaryItem(MinecraftServer server) {
		PanelSnapshot.IslandSummary summary = snapshot.island();
		if (summary == null) {
			return buildItem(
				Items.BARRIER,
				line("No Skyblock Island", ChatFormatting.RED),
				List.of(
					line("You do not currently belong to an island here.", ChatFormatting.GRAY),
					line("Pick a starter blueprint below or run /is create.", ChatFormatting.YELLOW)
				),
				false
			);
		}
		List<Component> lore = new ArrayList<>();
		lore.add(line("Owner: " + summary.ownerName(), ChatFormatting.GRAY));
		lore.add(line("Grid Cell: (" + summary.gridX() + ", " + summary.gridZ() + ")", ChatFormatting.GRAY));
		lore.add(line("Center: (" + summary.centerX() + ", " + summary.centerZ() + ")", ChatFormatting.GRAY));
		lore.add(line("Size: " + summary.sideLength() + "x" + summary.sideLength() + " (halfSize=" + summary.halfSize() + ")", ChatFormatting.GRAY));
		lore.add(line("Blueprint: " + summary.bundleId(), ChatFormatting.GRAY));
		lore.add(line("Locked: " + (summary.locked() ? "Yes" : "No"), summary.locked() ? ChatFormatting.RED : ChatFormatting.GREEN));
		lore.add(line("Members: " + summary.memberCount() + " | Banned: " + summary.bannedCount(), ChatFormatting.GRAY));
		lore.add(line(
			"Dims: OW=" + yesNo(summary.overworldReady()) + " | Nether=" + yesNo(summary.netherReady()) + " | End=" + yesNo(summary.endReady()),
			ChatFormatting.DARK_AQUA
		));
		lore.add(line("Created: " + DATE_FORMATTER.format(summary.createdAt()), ChatFormatting.DARK_GRAY));
		lore.addAll(summary.contributedLines());
		lore.add(line("Click to open Blueprints & Info", ChatFormatting.YELLOW));
		return buildPlayerHead(
			server,
			summary.ownerId(),
			line("Island Overview", ChatFormatting.GOLD),
			lore,
			false
		);
	}

	private void renderContent(
		Container container,
		MinecraftServer server,
		@Nullable PendingConfirmation pendingConfirm
	) {
		switch (snapshot.activeTab()) {
			case ACTIONS -> {
				List<PanelSnapshot.ActionEntry> entries = snapshot.actions();
				for (int i = 0; i < entries.size() && i < CONTENT_SLOTS.length; i++) {
					container.setItem(CONTENT_SLOTS[i], buildActionItem(entries.get(i)));
				}
			}
			case PROTECTION_FLAGS -> {
				List<PanelSnapshot.ProtectionFlagEntry> entries = snapshot.protectionFlags();
				for (int i = 0; i < entries.size() && i < CONTENT_SLOTS.length; i++) {
					container.setItem(CONTENT_SLOTS[i], buildProtectionFlagItem(entries.get(i)));
				}
			}
			case SETTING_FLAGS -> {
				List<PanelSnapshot.SettingFlagEntry> entries = snapshot.settingFlags();
				for (int i = 0; i < entries.size() && i < CONTENT_SLOTS.length; i++) {
					container.setItem(CONTENT_SLOTS[i], buildSettingFlagItem(entries.get(i)));
				}
			}
			case MEMBERS -> {
				List<PanelSnapshot.MemberEntry> entries = snapshot.members();
				for (int i = 0; i < entries.size() && i < CONTENT_SLOTS.length; i++) {
					container.setItem(CONTENT_SLOTS[i], buildMemberItem(entries.get(i), server, pendingConfirm));
				}
			}
			case BLUEPRINTS_INFO -> {
				List<PanelSnapshot.BlueprintEntry> entries = snapshot.blueprints();
				if (entries.isEmpty()) {
					container.setItem(22, buildEmptyBlueprintsItem());
				} else {
					for (int i = 0; i < entries.size() && i < CONTENT_SLOTS.length; i++) {
						container.setItem(CONTENT_SLOTS[i], buildBlueprintItem(entries.get(i), pendingConfirm));
					}
				}
			}
		}
	}

	private ItemStack buildActionItem(PanelSnapshot.ActionEntry entry) {
		List<Component> lore = new ArrayList<>();
		if (entry.activeState() != null) {
			boolean active = entry.activeState();
			lore.add(line("State: " + (active ? "LOCKED" : "UNLOCKED"), active ? ChatFormatting.RED : ChatFormatting.GREEN));
		}
		if (!entry.description().isBlank()) {
			lore.add(line(entry.description(), ChatFormatting.GRAY));
		}
		lore.add(line("Action ID: " + entry.actionId(), ChatFormatting.DARK_GRAY));
		lore.add(line("Click to execute", ChatFormatting.YELLOW));
		ChatFormatting titleColor = entry.activeState() == null
			? ChatFormatting.AQUA
			: (entry.activeState() ? ChatFormatting.RED : ChatFormatting.GREEN);
		boolean glint = Boolean.TRUE.equals(entry.activeState());
		return buildItem(entry.iconItemId(), line(entry.title(), titleColor), lore, glint);
	}

	private ItemStack buildProtectionFlagItem(PanelSnapshot.ProtectionFlagEntry entry) {
		ChatFormatting rankColor = rankColor(entry.minRank());
		List<Component> lore = new ArrayList<>();
		lore.add(line("Required Rank: " + entry.minRankName(), rankColor));
		lore.add(line("Default: " + entry.defaultMinRankName() + " | Mode: " + modeLabel(entry.mode()), ChatFormatting.DARK_GRAY));
		if (entry.editable()) {
			lore.add(line("Left-click: Raise rank | Right-click: Lower rank", ChatFormatting.YELLOW));
			lore.add(line("Shift-click: Reset to default", ChatFormatting.GOLD));
		} else {
			lore.add(line("View only (requires Admin rank)", ChatFormatting.RED));
		}
		boolean modified = entry.minRank() != entry.defaultMinRank();
		return buildItem(entry.iconItemId(), line(entry.title(), ChatFormatting.GOLD), lore, modified);
	}

	private ItemStack buildSettingFlagItem(PanelSnapshot.SettingFlagEntry entry) {
		List<Component> lore = new ArrayList<>();
		lore.add(line(
			"State: " + (entry.enabled() ? "Enabled" : "Disabled"),
			entry.enabled() ? ChatFormatting.GREEN : ChatFormatting.RED
		));
		lore.add(line("Default: " + (entry.defaultValue() ? "Enabled" : "Disabled") + " | Mode: " + modeLabel(entry.mode()), ChatFormatting.DARK_GRAY));
		if (entry.editable()) {
			lore.add(line("Click: Toggle setting | Shift-click: Reset default", ChatFormatting.YELLOW));
		} else {
			lore.add(line("View only (requires Admin rank)", ChatFormatting.RED));
		}
		return buildItem(
			entry.iconItemId(),
			line(entry.title(), entry.enabled() ? ChatFormatting.GREEN : ChatFormatting.GRAY),
			lore,
			entry.enabled()
		);
	}

	private ItemStack buildMemberItem(
		PanelSnapshot.MemberEntry entry,
		MinecraftServer server,
		@Nullable PendingConfirmation pendingConfirm
	) {
		List<Component> lore = new ArrayList<>();
		ChatFormatting roleColor = switch (entry.kind()) {
			case PRIMARY_MEMBER -> rankColor(entry.role());
			case SECONDARY_HELPER -> ChatFormatting.LIGHT_PURPLE;
			case BANNED_PLAYER -> ChatFormatting.DARK_RED;
		};
		lore.add(line("Role: " + entry.roleName(), roleColor));
		lore.add(line("Category: " + switch (entry.kind()) {
			case PRIMARY_MEMBER -> "Primary Member";
			case SECONDARY_HELPER -> "Secondary Helper";
			case BANNED_PLAYER -> "Banned Player";
		}, ChatFormatting.GRAY));

		boolean confirmKick = isPending(pendingConfirm, ConfirmKind.KICK_MEMBER, entry.playerUuid().toString());
		boolean confirmBan = isPending(pendingConfirm, ConfirmKind.BAN_PLAYER, entry.playerUuid().toString());
		if (entry.canUnban()) {
			lore.add(line("Click: Unban player", ChatFormatting.GREEN));
		}
		if (entry.kind() == PanelSnapshot.MemberEntry.Kind.SECONDARY_HELPER && entry.canRemoveOrKick()) {
			lore.add(line("Click: Remove " + entry.roleName() + " role", ChatFormatting.YELLOW));
		}
		if (entry.canPromote()) {
			lore.add(line("Left-click: Promote to Admin", ChatFormatting.GREEN));
		}
		if (entry.canDemote()) {
			lore.add(line("Right-click: Demote to Member", ChatFormatting.GOLD));
		}
		if (entry.kind() == PanelSnapshot.MemberEntry.Kind.PRIMARY_MEMBER && entry.canRemoveOrKick()) {
			lore.add(line(
				confirmKick ? "CONFIRM: Shift-Left-click again to KICK!" : "Shift-Left-click: Kick member",
				confirmKick ? ChatFormatting.RED : ChatFormatting.YELLOW
			));
		}
		if (entry.canBan()) {
			lore.add(line(
				confirmBan ? "CONFIRM: Shift-Right-click again to BAN!" : "Shift-Right-click: Ban player",
				confirmBan ? ChatFormatting.DARK_RED : ChatFormatting.RED
			));
		}
		return buildPlayerHead(
			server,
			entry.playerUuid(),
			line(entry.displayName(), roleColor),
			lore,
			confirmKick || confirmBan
		);
	}

	private ItemStack buildBlueprintItem(
		PanelSnapshot.BlueprintEntry entry,
		@Nullable PendingConfirmation pendingConfirm
	) {
		boolean creating = snapshot.islandId() == null;
		boolean confirmReset = !creating && isPending(pendingConfirm, ConfirmKind.RESET_ISLAND, entry.id());
		List<Component> lore = new ArrayList<>();
		if (!entry.description().isBlank()) {
			lore.add(line(entry.description(), ChatFormatting.GRAY));
		}
		lore.add(line(
			"Dimensions: OW=" + yesNo(entry.hasOverworld()) + " | Nether=" + yesNo(entry.hasNether()) + " | End=" + yesNo(entry.hasEnd()),
			ChatFormatting.DARK_AQUA
		));
		if (entry.activeOnIsland()) {
			lore.add(line("Currently active on this island", ChatFormatting.GREEN));
		}
		if (entry.defaultBundle()) {
			lore.add(line("Default server starter blueprint", ChatFormatting.AQUA));
		}
		if (!entry.permitted()) {
			lore.add(line("Locked: missing blueprint permission", ChatFormatting.RED));
		} else if (creating) {
			lore.add(line("Click to create your island with this blueprint!", ChatFormatting.GREEN));
		} else if (snapshot.viewerRole() == IslandRole.OWNER || snapshot.viewerAdminBypass()) {
			lore.add(line(
				confirmReset ? "CONFIRM: Click again within 10s to RESET island!" : "Click to reset island to this blueprint",
				confirmReset ? ChatFormatting.RED : ChatFormatting.GOLD
			));
		} else {
			lore.add(line("Only the island owner can reset the island.", ChatFormatting.GRAY));
		}
		return buildItem(
			entry.iconItemId(),
			line(entry.displayName(), entry.activeOnIsland() ? ChatFormatting.GREEN : ChatFormatting.AQUA),
			lore,
			entry.activeOnIsland() || confirmReset
		);
	}

	private ItemStack buildEmptyBlueprintsItem() {
		List<Component> lore = new ArrayList<>();
		lore.add(line("No blueprint bundles with overworld.nbt were found.", ChatFormatting.GRAY));
		lore.add(line("Add templates under config/ogsky/blueprints/<id>/", ChatFormatting.YELLOW));
		lore.add(line("then run /isadmin reload as an operator.", ChatFormatting.YELLOW));
		return buildItem(Items.STRUCTURE_BLOCK, line("No Starter Blueprints Loaded", ChatFormatting.GOLD), lore, false);
	}

	private void renderFooter(Container container) {
		container.setItem(
			SLOT_CLOSE,
			buildItem(Items.BARRIER, line("Close Panel", ChatFormatting.RED), List.of(line("Click to close menu", ChatFormatting.GRAY)), false)
		);

		if (snapshot.activeTab() == PanelTab.PROTECTION_FLAGS || snapshot.activeTab() == PanelTab.SETTING_FLAGS) {
			boolean advanced = snapshot.flagMode() == FlagModeFilter.ADVANCED;
			container.setItem(
				SLOT_CONTEXT_CONTROL,
				buildItem(
					Items.LEVER,
					line("Mode: " + snapshot.flagMode().displayName(), ChatFormatting.AQUA),
					List.of(
						line(advanced ? "Showing Basic + Advanced flags" : "Showing Basic flags only", ChatFormatting.GRAY),
						line("Click to switch between Basic and Advanced", ChatFormatting.YELLOW)
					),
					advanced
				)
			);
		} else if (snapshot.activeTab() == PanelTab.MEMBERS) {
			container.setItem(
				SLOT_CONTEXT_CONTROL,
				buildItem(
					Items.WRITABLE_BOOK,
					line("Add & Transfer Commands", ChatFormatting.GOLD),
					List.of(
						line("/is invite <player> - Invite primary member", ChatFormatting.GRAY),
						line("/is coop <player> - Add temporary coop helper", ChatFormatting.GRAY),
						line("/is trust <player> - Add persistent trusted helper", ChatFormatting.GRAY),
						line("/is ban <player> | /is transfer <player>", ChatFormatting.GRAY),
						line("Click to print commands in chat", ChatFormatting.YELLOW)
					),
					false
				)
			);
		}

		if (snapshot.hasPreviousPage()) {
			container.setItem(
				SLOT_PREV_PAGE,
				buildItem(
					Items.ARROW,
					line("Previous Page", ChatFormatting.YELLOW),
					List.of(line("Go to page " + snapshot.page(), ChatFormatting.GRAY)),
					false
				)
			);
		}

		container.setItem(
			SLOT_PAGE_INDICATOR,
			buildItem(
				Items.PAPER,
				line("Page " + (snapshot.page() + 1) + " / " + snapshot.totalPages(), ChatFormatting.WHITE),
				List.of(
					line("Entries in tab: " + snapshot.totalEntries(), ChatFormatting.GRAY),
					line("Your rank: " + controller.roleDisplayName(snapshot.viewerRole())
						+ (snapshot.viewerAdminBypass() ? " (Admin Bypass)" : ""), ChatFormatting.DARK_AQUA),
					line("Click to refresh", ChatFormatting.YELLOW)
				),
				false
			)
		);

		if (snapshot.hasNextPage()) {
			container.setItem(
				SLOT_NEXT_PAGE,
				buildItem(
					Items.ARROW,
					line("Next Page", ChatFormatting.YELLOW),
					List.of(line("Go to page " + (snapshot.page() + 2), ChatFormatting.GRAY)),
					false
				)
			);
		}

		container.setItem(
			SLOT_REFRESH,
			buildItem(
				Items.CLOCK,
				line("Refresh", ChatFormatting.GREEN),
				List.of(line("Reload latest island state", ChatFormatting.GRAY)),
				false
			)
		);
	}

	@Override
	protected void onMenuSlotClick(ServerPlayer player, int slotIndex, int buttonNum, ContainerInput containerInput) {
		controller.handleClick(this, player, slotIndex, buttonNum, containerInput);
	}

	@Override
	protected void onMenuClosed(Player player) {
		controller.onMenuClosed(this);
	}

	@Override
	public boolean stillValid(Player player) {
		if (!player.isAlive() || player.isRemoved()) {
			return false;
		}
		UUID islandId = snapshot.islandId();
		return islandId == null || controller.isViewerAllowed(islandId, player.getUUID());
	}

	private static boolean isPending(
		@Nullable PendingConfirmation pendingConfirm,
		ConfirmKind kind,
		String targetKey
	) {
		return pendingConfirm != null
			&& !pendingConfirm.isExpired()
			&& pendingConfirm.kind() == kind
			&& pendingConfirm.targetKey().equals(targetKey);
	}

	private static String modeLabel(FlagMode mode) {
		return mode == FlagMode.ADVANCED ? "Advanced" : "Basic";
	}

	private static String yesNo(boolean value) {
		return value ? "Yes" : "No";
	}

	private static ChatFormatting rankColor(byte rank) {
		return switch (rank) {
			case IslandRole.VISITOR -> ChatFormatting.GREEN;
			case IslandRole.COOP -> ChatFormatting.LIGHT_PURPLE;
			case IslandRole.TRUSTED -> ChatFormatting.AQUA;
			case IslandRole.MEMBER -> ChatFormatting.YELLOW;
			case IslandRole.ADMIN -> ChatFormatting.GOLD;
			case IslandRole.OWNER -> ChatFormatting.RED;
			default -> ChatFormatting.DARK_RED;
		};
	}
}
