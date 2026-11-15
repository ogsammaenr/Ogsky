package me.ogsammaenr.ogsky.panel;

import me.ogsammaenr.ogsky.core.api.OgskyCoreApi;
import me.ogsammaenr.ogsky.core.api.action.IslandActionContext;
import me.ogsammaenr.ogsky.core.api.action.IslandActionDescriptor;
import me.ogsammaenr.ogsky.core.api.event.IslandBanEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandDeletedEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandDimensionInitEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandLevelCalculatedEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandLockChangedEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandProtectionFlagChangedEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandRangeChangeEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandResetEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandSettingFlagChangedEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandUnbanEvent;
import me.ogsammaenr.ogsky.core.api.event.OgskyEventBus;
import me.ogsammaenr.ogsky.core.api.event.TeamJoinEvent;
import me.ogsammaenr.ogsky.core.api.event.TeamKickEvent;
import me.ogsammaenr.ogsky.core.api.event.TeamLeaveEvent;
import me.ogsammaenr.ogsky.core.api.event.TeamOwnerChangeEvent;
import me.ogsammaenr.ogsky.core.api.event.TeamRankChangeEvent;
import me.ogsammaenr.ogsky.core.api.island.IslandOperationResult;
import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import me.ogsammaenr.ogsky.core.api.island.IslandView;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ContainerInput;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinates open {@link OgskyPanelMenu} sessions, click actions, confirmations, and live event refreshes.
 */
public final class PanelController {
	private static final Duration CONFIRM_TTL = Duration.ofSeconds(10);

	public enum ConfirmKind {
		RESET_ISLAND,
		KICK_MEMBER,
		BAN_PLAYER
	}

	public record PendingConfirmation(
		ConfirmKind kind,
		@Nullable UUID islandId,
		String targetKey,
		Instant expiresAt
	) {
		public boolean isExpired() {
			return Instant.now().isAfter(expiresAt);
		}
	}

	private final OgskyCoreApi core;
	private final PanelViewModelService viewModels;
	private final PanelPreferenceRepository preferences;
	private final Map<UUID, OgskyPanelMenu> openMenus = new ConcurrentHashMap<>();
	private final Map<UUID, PendingConfirmation> pendingConfirmations = new ConcurrentHashMap<>();

	public PanelController(
		OgskyCoreApi core,
		PanelViewModelService viewModels,
		PanelPreferenceRepository preferences
	) {
		this.core = Objects.requireNonNull(core, "core");
		this.viewModels = Objects.requireNonNull(viewModels, "viewModels");
		this.preferences = Objects.requireNonNull(preferences, "preferences");
	}

	public void registerEvents() {
		OgskyEventBus bus = core.events();
		bus.subscribe(IslandProtectionFlagChangedEvent.class, e -> scheduleIslandRefresh(e.islandId()));
		bus.subscribe(IslandSettingFlagChangedEvent.class, e -> scheduleIslandRefresh(e.islandId()));
		bus.subscribe(IslandLockChangedEvent.class, e -> scheduleIslandRefresh(e.islandId()));
		bus.subscribe(IslandRangeChangeEvent.class, e -> scheduleIslandRefresh(e.islandId()));
		bus.subscribe(IslandDimensionInitEvent.class, e -> scheduleIslandRefresh(e.islandId()));
		bus.subscribe(IslandLevelCalculatedEvent.class, e -> scheduleIslandRefresh(e.islandId()));
		bus.subscribe(IslandUnbanEvent.class, e -> scheduleIslandRefresh(e.islandId()));
		bus.subscribe(TeamJoinEvent.class, e -> scheduleIslandRefresh(e.islandId()));
		bus.subscribe(IslandBanEvent.class, e -> {
			if (!e.isCancelled()) {
				scheduleIslandRefresh(e.islandId());
			}
		});
		bus.subscribe(TeamKickEvent.class, e -> {
			if (!e.isCancelled()) {
				scheduleIslandRefresh(e.islandId());
			}
		});
		bus.subscribe(TeamLeaveEvent.class, e -> {
			if (!e.isCancelled()) {
				scheduleIslandRefresh(e.islandId());
			}
		});
		bus.subscribe(TeamRankChangeEvent.class, e -> {
			if (!e.isCancelled()) {
				scheduleIslandRefresh(e.islandId());
			}
		});
		bus.subscribe(TeamOwnerChangeEvent.class, e -> {
			if (!e.isCancelled()) {
				scheduleIslandRefresh(e.islandId());
			}
		});
		bus.subscribe(IslandResetEvent.class, e -> {
			if (!e.isCancelled()) {
				scheduleIslandRefresh(e.islandId());
			}
		});
		bus.subscribe(IslandDeletedEvent.class, e -> closeMenusForDeletedIsland(e.islandId()));
	}

	public void openPanel(
		ServerPlayer player,
		@Nullable UUID islandId,
		PanelTab tab,
		FlagModeFilter flagMode,
		int page
	) {
		Objects.requireNonNull(player, "player");
		Objects.requireNonNull(tab, "tab");
		Objects.requireNonNull(flagMode, "flagMode");

		pendingConfirmations.remove(player.getUUID());
		PanelSnapshot snapshot = viewModels.buildSnapshot(islandId, player, tab, flagMode, page);
		player.openMenu(new SimpleMenuProvider(
			(containerId, playerInventory, p) -> new OgskyPanelMenu(containerId, playerInventory, this, snapshot),
			Component.literal("Skyblock Control Panel")
		));
	}

	public void registerOpenMenu(OgskyPanelMenu menu) {
		openMenus.put(menu.viewer().getUUID(), menu);
	}

	public void onMenuClosed(OgskyPanelMenu menu) {
		openMenus.remove(menu.viewer().getUUID(), menu);
		pendingConfirmations.remove(menu.viewer().getUUID());
	}

	public void onPlayerDisconnect(UUID playerUuid) {
		openMenus.remove(playerUuid);
		pendingConfirmations.remove(playerUuid);
		preferences.evict(playerUuid);
	}

	public void clearAll() {
		openMenus.clear();
		pendingConfirmations.clear();
		preferences.clearCache();
	}

	public boolean isViewerAllowed(UUID islandId, UUID viewerUuid) {
		Optional<IslandView> island = core.islands().findById(islandId);
		return island.isPresent() && !island.get().isBanned(viewerUuid);
	}

	public String roleDisplayName(byte role) {
		return core.islands().roleDisplayName(role);
	}

	public void handleClick(
		OgskyPanelMenu menu,
		ServerPlayer player,
		int slotIndex,
		int buttonNum,
		ContainerInput input
	) {
		if (!player.getUUID().equals(menu.viewer().getUUID())) {
			return;
		}
		if (buttonNum != 0 && buttonNum != 1) {
			return;
		}

		PanelSnapshot current = menu.snapshot();
		if (slotIndex >= 0 && slotIndex < PanelTab.values().length) {
			handleTabClick(menu, player, current, PanelTab.values()[slotIndex]);
			return;
		}
		if (slotIndex == OgskyPanelMenu.SLOT_LOCK_STATUS && current.island() != null) {
			handleQuickLockToggleClick(menu, player, current);
			return;
		}
		if (slotIndex == OgskyPanelMenu.SLOT_ISLAND_SUMMARY) {
			if (current.activeTab() != PanelTab.BLUEPRINTS_INFO) {
				pendingConfirmations.remove(player.getUUID());
				preferences.saveTabAsync(player.getUUID(), PanelTab.BLUEPRINTS_INFO);
				refreshMenu(menu, current.islandId(), PanelTab.BLUEPRINTS_INFO, current.flagMode(), 0);
			}
			playClickSound(player);
			return;
		}
		if (slotIndex == OgskyPanelMenu.SLOT_CLOSE) {
			playClickSound(player);
			player.closeContainer();
			return;
		}
		if (slotIndex == OgskyPanelMenu.SLOT_CONTEXT_CONTROL) {
			handleContextControlClick(menu, player, current);
			return;
		}
		if (slotIndex == OgskyPanelMenu.SLOT_PREV_PAGE && current.hasPreviousPage()) {
			pendingConfirmations.remove(player.getUUID());
			refreshMenu(menu, current.islandId(), current.activeTab(), current.flagMode(), current.page() - 1);
			playClickSound(player);
			return;
		}
		if (slotIndex == OgskyPanelMenu.SLOT_NEXT_PAGE && current.hasNextPage()) {
			pendingConfirmations.remove(player.getUUID());
			refreshMenu(menu, current.islandId(), current.activeTab(), current.flagMode(), current.page() + 1);
			playClickSound(player);
			return;
		}
		if (slotIndex == OgskyPanelMenu.SLOT_PAGE_INDICATOR || slotIndex == OgskyPanelMenu.SLOT_REFRESH) {
			refreshMenu(menu, current.islandId(), current.activeTab(), current.flagMode(), current.page());
			playClickSound(player);
			return;
		}

		int contentIndex = menu.contentIndexForSlot(slotIndex);
		if (contentIndex < 0) {
			return;
		}
		switch (current.activeTab()) {
			case ACTIONS -> handleActionEntryClick(menu, player, current, contentIndex);
			case PROTECTION_FLAGS -> handleProtectionFlagClick(menu, player, current, contentIndex, buttonNum, input);
			case SETTING_FLAGS -> handleSettingFlagClick(menu, player, current, contentIndex, input);
			case MEMBERS -> handleMemberClick(menu, player, current, contentIndex, buttonNum, input);
			case BLUEPRINTS_INFO -> handleBlueprintClick(menu, player, current, contentIndex);
		}
	}

	private void handleTabClick(
		OgskyPanelMenu menu,
		ServerPlayer player,
		PanelSnapshot current,
		PanelTab nextTab
	) {
		if (current.islandId() == null && nextTab != PanelTab.BLUEPRINTS_INFO) {
			sendFeedback(player, false, "Create an island first from Blueprints & Info.");
			playErrorSound(player);
			return;
		}
		if (!core.permissions().hasPermission(player, nextTab.requiredPermission(), 0)) {
			sendFeedback(player, false, "You do not have permission to view " + nextTab.displayName() + ".");
			playErrorSound(player);
			return;
		}
		if (nextTab == current.activeTab()) {
			playClickSound(player);
			return;
		}
		pendingConfirmations.remove(player.getUUID());
		preferences.saveTabAsync(player.getUUID(), nextTab);
		refreshMenu(menu, current.islandId(), nextTab, current.flagMode(), 0);
		playClickSound(player);
	}

	private void handleQuickLockToggleClick(
		OgskyPanelMenu menu,
		ServerPlayer player,
		PanelSnapshot current
	) {
		PanelSnapshot.IslandSummary summary = current.island();
		if (summary == null || current.islandId() == null) {
			return;
		}
		boolean canEdit = current.viewerAdminBypass() || current.viewerRole() >= IslandRole.ADMIN;
		if (!canEdit) {
			sendFeedback(player, false, "Only island admins, the owner, or bypass staff can modify island lock.");
			playErrorSound(player);
			return;
		}
		boolean nextLocked = !summary.locked();
		boolean bypass = core.permissions().isBypassActive(player);
		IslandOperationResult<Boolean> result = core.islands().setSettingFlag(
			player.level().getServer(),
			player.getUUID(),
			current.islandId(),
			PanelViewModelService.ISLAND_LOCK_FLAG,
			nextLocked,
			bypass
		);
		applyMutationFeedback(player, result.success(), result.message());
		refreshMenu(menu, current.islandId(), current.activeTab(), current.flagMode(), current.page());
	}

	private void handleContextControlClick(
		OgskyPanelMenu menu,
		ServerPlayer player,
		PanelSnapshot current
	) {
		if (current.activeTab() == PanelTab.PROTECTION_FLAGS || current.activeTab() == PanelTab.SETTING_FLAGS) {
			FlagModeFilter nextMode = current.flagMode().next();
			preferences.saveFlagModeAsync(player.getUUID(), nextMode);
			refreshMenu(menu, current.islandId(), current.activeTab(), nextMode, 0);
			playClickSound(player);
			return;
		}
		if (current.activeTab() == PanelTab.MEMBERS) {
			player.sendSystemMessage(
				Component.literal("[Ogsky] Team commands: /is invite <player>, /is coop <player>, /is trust <player>, /is ban <player>, /is transfer <player>")
					.withStyle(ChatFormatting.YELLOW)
			);
			playClickSound(player);
		}
	}

	private void handleActionEntryClick(
		OgskyPanelMenu menu,
		ServerPlayer player,
		PanelSnapshot current,
		int contentIndex
	) {
		if (current.islandId() == null || contentIndex >= current.actions().size()) {
			return;
		}
		PanelSnapshot.ActionEntry entry = current.actions().get(contentIndex);
		Optional<IslandActionDescriptor> descriptor = core.actions().find(entry.actionId());
		if (descriptor.isEmpty()) {
			playErrorSound(player);
			refreshMenu(menu, current.islandId(), current.activeTab(), current.flagMode(), current.page());
			return;
		}
		playClickSound(player);
		descriptor.get().handler().execute(new IslandActionContext(player, current.islandId()));
		if (entry.closePanelOnExecute()) {
			player.closeContainer();
		} else if (openMenus.get(player.getUUID()) == menu) {
			refreshMenu(menu, current.islandId(), current.activeTab(), current.flagMode(), current.page());
		}
	}

	private void handleProtectionFlagClick(
		OgskyPanelMenu menu,
		ServerPlayer player,
		PanelSnapshot current,
		int contentIndex,
		int buttonNum,
		ContainerInput input
	) {
		if (current.islandId() == null || contentIndex >= current.protectionFlags().size()) {
			return;
		}
		PanelSnapshot.ProtectionFlagEntry entry = current.protectionFlags().get(contentIndex);
		if (!entry.editable()) {
			sendFeedback(player, false, "Only island admins, the owner, or bypass staff can modify protection flags.");
			playErrorSound(player);
			return;
		}
		byte nextRank;
		if (input == ContainerInput.QUICK_MOVE) {
			nextRank = entry.defaultMinRank();
		} else if (buttonNum == 1) {
			nextRank = (byte) ((entry.minRank() + 6) % 7);
		} else {
			nextRank = (byte) ((entry.minRank() + 1) % 7);
		}
		boolean bypass = core.permissions().isBypassActive(player);
		IslandOperationResult<Byte> result = core.islands().setProtectionRank(
			player.getUUID(),
			current.islandId(),
			entry.flagId(),
			nextRank,
			bypass
		);
		applyMutationFeedback(player, result.success(), result.message());
		refreshMenu(menu, current.islandId(), current.activeTab(), current.flagMode(), current.page());
	}

	private void handleSettingFlagClick(
		OgskyPanelMenu menu,
		ServerPlayer player,
		PanelSnapshot current,
		int contentIndex,
		ContainerInput input
	) {
		if (current.islandId() == null || contentIndex >= current.settingFlags().size()) {
			return;
		}
		PanelSnapshot.SettingFlagEntry entry = current.settingFlags().get(contentIndex);
		if (!entry.editable()) {
			sendFeedback(player, false, "Only island admins, the owner, or bypass staff can modify island settings.");
			playErrorSound(player);
			return;
		}
		boolean nextValue = input == ContainerInput.QUICK_MOVE ? entry.defaultValue() : !entry.enabled();
		boolean bypass = core.permissions().isBypassActive(player);
		IslandOperationResult<Boolean> result = core.islands().setSettingFlag(
			player.level().getServer(),
			player.getUUID(),
			current.islandId(),
			entry.flagId(),
			nextValue,
			bypass
		);
		applyMutationFeedback(player, result.success(), result.message());
		refreshMenu(menu, current.islandId(), current.activeTab(), current.flagMode(), current.page());
	}

	private void handleMemberClick(
		OgskyPanelMenu menu,
		ServerPlayer player,
		PanelSnapshot current,
		int contentIndex,
		int buttonNum,
		ContainerInput input
	) {
		UUID islandId = current.islandId();
		if (islandId == null || contentIndex >= current.members().size()) {
			return;
		}
		PanelSnapshot.MemberEntry entry = current.members().get(contentIndex);
		MinecraftServer server = player.level().getServer();
		boolean bypass = core.permissions().isBypassActive(player);
		UUID actorId = player.getUUID();
		UUID targetId = entry.playerUuid();

		if (entry.kind() == PanelSnapshot.MemberEntry.Kind.BANNED_PLAYER) {
			if (!entry.canUnban()) {
				sendFeedback(player, false, "Only island admins or the owner can unban players.");
				playErrorSound(player);
				return;
			}
			pendingConfirmations.remove(actorId);
			IslandOperationResult<?> result = core.islands().unbanPlayer(islandId, actorId, targetId, bypass);
			applyMutationFeedback(player, result.success(), result.message());
			refreshMenu(menu, islandId, current.activeTab(), current.flagMode(), current.page());
			return;
		}

		if (input == ContainerInput.QUICK_MOVE && buttonNum == 1) {
			if (!entry.canBan()) {
				sendFeedback(player, false, "You cannot ban this player.");
				playErrorSound(player);
				return;
			}
			if (!consumeOrPromptConfirmation(
				player,
				ConfirmKind.BAN_PLAYER,
				islandId,
				targetId.toString(),
				"Shift-Right-click " + entry.displayName() + " again within 10s to confirm BAN!"
			)) {
				refreshMenu(menu, islandId, current.activeTab(), current.flagMode(), current.page());
				return;
			}
			IslandOperationResult<?> result = core.islands().banPlayer(server, islandId, actorId, targetId, bypass);
			applyMutationFeedback(player, result.success(), result.message());
			refreshMenu(menu, islandId, current.activeTab(), current.flagMode(), current.page());
			return;
		}

		if (entry.kind() == PanelSnapshot.MemberEntry.Kind.SECONDARY_HELPER) {
			if (!entry.canRemoveOrKick()) {
				sendFeedback(player, false, "Only island admins or the owner can remove secondary helpers.");
				playErrorSound(player);
				return;
			}
			pendingConfirmations.remove(actorId);
			IslandOperationResult<?> result = entry.role() == IslandRole.COOP
				? core.islands().removeCoop(server, islandId, actorId, targetId, bypass)
				: core.islands().removeTrust(server, islandId, actorId, targetId, bypass);
			applyMutationFeedback(player, result.success(), result.message());
			refreshMenu(menu, islandId, current.activeTab(), current.flagMode(), current.page());
			return;
		}

		// Primary member interactions
		if (input == ContainerInput.QUICK_MOVE && buttonNum == 0) {
			if (!entry.canRemoveOrKick()) {
				sendFeedback(player, false, "You cannot kick this member.");
				playErrorSound(player);
				return;
			}
			if (!consumeOrPromptConfirmation(
				player,
				ConfirmKind.KICK_MEMBER,
				islandId,
				targetId.toString(),
				"Shift-Left-click " + entry.displayName() + " again within 10s to confirm KICK!"
			)) {
				refreshMenu(menu, islandId, current.activeTab(), current.flagMode(), current.page());
				return;
			}
			IslandOperationResult<?> result = core.islands().kickMember(server, islandId, actorId, targetId, bypass);
			applyMutationFeedback(player, result.success(), result.message());
			refreshMenu(menu, islandId, current.activeTab(), current.flagMode(), current.page());
			return;
		}

		pendingConfirmations.remove(actorId);
		if (buttonNum == 0) {
			if (!entry.canPromote()) {
				sendFeedback(player, false, "Only the island owner can promote members to Admin.");
				playErrorSound(player);
				return;
			}
			IslandOperationResult<?> result = core.islands().promoteMember(islandId, actorId, targetId, bypass);
			applyMutationFeedback(player, result.success(), result.message());
		} else {
			if (!entry.canDemote()) {
				sendFeedback(player, false, "Only the island owner can demote Admins.");
				playErrorSound(player);
				return;
			}
			IslandOperationResult<?> result = core.islands().demoteMember(islandId, actorId, targetId, bypass);
			applyMutationFeedback(player, result.success(), result.message());
		}
		refreshMenu(menu, islandId, current.activeTab(), current.flagMode(), current.page());
	}

	private void handleBlueprintClick(
		OgskyPanelMenu menu,
		ServerPlayer player,
		PanelSnapshot current,
		int contentIndex
	) {
		if (contentIndex >= current.blueprints().size()) {
			return;
		}
		PanelSnapshot.BlueprintEntry entry = current.blueprints().get(contentIndex);
		if (!entry.permitted()) {
			sendFeedback(player, false, "You do not have permission to use blueprint '" + entry.id() + "'.");
			playErrorSound(player);
			return;
		}

		MinecraftServer server = player.level().getServer();
		UUID islandId = current.islandId();
		if (islandId == null) {
			IslandOperationResult<IslandView> result = core.islands().createIsland(
				server,
				player,
				player.getUUID(),
				entry.id()
			);
			applyMutationFeedback(player, result.success(), result.message());
			if (result.success()) {
				player.closeContainer();
			}
			return;
		}

		boolean bypass = core.permissions().isBypassActive(player);
		if (current.viewerRole() != IslandRole.OWNER && !bypass) {
			sendFeedback(player, false, "Only the island owner can reset the island.");
			playErrorSound(player);
			return;
		}

		if (!consumeOrPromptConfirmation(
			player,
			ConfirmKind.RESET_ISLAND,
			islandId,
			entry.id(),
			"Click blueprint '" + entry.displayName() + "' again within 10s to confirm RESETTING your island!"
		)) {
			refreshMenu(menu, islandId, current.activeTab(), current.flagMode(), current.page());
			return;
		}

		IslandOperationResult<IslandView> result = core.islands().resetIsland(
			server,
			player.getUUID(),
			islandId,
			entry.id(),
			bypass
		);
		applyMutationFeedback(player, result.success(), result.message());
		if (result.success()) {
			player.closeContainer();
		} else {
			refreshMenu(menu, islandId, current.activeTab(), current.flagMode(), current.page());
		}
	}

	private boolean consumeOrPromptConfirmation(
		ServerPlayer player,
		ConfirmKind kind,
		@Nullable UUID islandId,
		String targetKey,
		String promptMessage
	) {
		UUID playerUuid = player.getUUID();
		PendingConfirmation existing = pendingConfirmations.get(playerUuid);
		if (existing != null
			&& !existing.isExpired()
			&& existing.kind() == kind
			&& Objects.equals(existing.islandId(), islandId)
			&& existing.targetKey().equals(targetKey)) {
			pendingConfirmations.remove(playerUuid);
			return true;
		}
		pendingConfirmations.put(
			playerUuid,
			new PendingConfirmation(kind, islandId, targetKey, Instant.now().plus(CONFIRM_TTL))
		);
		player.sendSystemMessage(Component.literal("[Ogsky] " + promptMessage).withStyle(ChatFormatting.YELLOW));
		playClickSound(player);
		return false;
	}

	private void refreshMenu(
		OgskyPanelMenu menu,
		@Nullable UUID islandId,
		PanelTab tab,
		FlagModeFilter flagMode,
		int page
	) {
		ServerPlayer viewer = menu.viewer();
		PendingConfirmation pending = activeConfirmation(viewer.getUUID());
		PanelSnapshot updated = viewModels.buildSnapshot(islandId, viewer, tab, flagMode, page);
		menu.renderSnapshot(updated, viewer.level().getServer(), pending);
	}

	private @Nullable PendingConfirmation activeConfirmation(UUID playerUuid) {
		PendingConfirmation pending = pendingConfirmations.get(playerUuid);
		if (pending != null && pending.isExpired()) {
			pendingConfirmations.remove(playerUuid, pending);
			return null;
		}
		return pending;
	}

	private void scheduleIslandRefresh(UUID islandId) {
		if (openMenus.isEmpty()) {
			return;
		}
		for (OgskyPanelMenu menu : openMenus.values()) {
			if (islandId.equals(menu.snapshot().islandId())) {
				MinecraftServer server = menu.viewer().level().getServer();
				server.execute(() -> refreshMenusForIsland(islandId));
				return;
			}
		}
	}

	private void refreshMenusForIsland(UUID islandId) {
		for (OgskyPanelMenu menu : openMenus.values()) {
			PanelSnapshot current = menu.snapshot();
			if (!islandId.equals(current.islandId())) {
				continue;
			}
			ServerPlayer viewer = menu.viewer();
			if (viewer.containerMenu != menu) {
				openMenus.remove(viewer.getUUID(), menu);
				continue;
			}
			if (!isViewerAllowed(islandId, viewer.getUUID())) {
				sendFeedback(viewer, false, "Your access to that island was revoked.");
				viewer.closeContainer();
				continue;
			}
			refreshMenu(menu, islandId, current.activeTab(), current.flagMode(), current.page());
		}
	}

	private void closeMenusForDeletedIsland(UUID islandId) {
		if (openMenus.isEmpty()) {
			return;
		}
		for (OgskyPanelMenu menu : openMenus.values()) {
			if (islandId.equals(menu.snapshot().islandId())) {
				MinecraftServer server = menu.viewer().level().getServer();
				server.execute(() -> {
					for (OgskyPanelMenu open : openMenus.values()) {
						if (islandId.equals(open.snapshot().islandId()) && open.viewer().containerMenu == open) {
							sendFeedback(open.viewer(), false, "The island you were viewing was deleted.");
							open.viewer().closeContainer();
						}
					}
				});
				return;
			}
		}
	}

	private static void applyMutationFeedback(ServerPlayer player, boolean success, String message) {
		PanelFeedback.applyMutationFeedback(player, success, message);
	}

	private static void sendFeedback(ServerPlayer player, boolean success, String message) {
		PanelFeedback.sendFeedback(player, success, message);
	}

	private static void playClickSound(ServerPlayer player) {
		PanelFeedback.playClickSound(player);
	}

	private static void playErrorSound(ServerPlayer player) {
		PanelFeedback.playErrorSound(player);
	}
}
