package me.ogsammaenr.ogsky.core.internal.island;

import me.ogsammaenr.ogsky.core.api.db.DatabaseService;
import me.ogsammaenr.ogsky.core.api.event.IslandBanEvent;
import me.ogsammaenr.ogsky.core.api.event.IslandUnbanEvent;
import me.ogsammaenr.ogsky.core.api.event.OgskyEventBus;
import me.ogsammaenr.ogsky.core.api.event.TeamInviteEvent;
import me.ogsammaenr.ogsky.core.api.event.TeamJoinEvent;
import me.ogsammaenr.ogsky.core.api.event.TeamKickEvent;
import me.ogsammaenr.ogsky.core.api.event.TeamLeaveEvent;
import me.ogsammaenr.ogsky.core.api.event.TeamOwnerChangeEvent;
import me.ogsammaenr.ogsky.core.api.event.TeamRankChangeEvent;
import me.ogsammaenr.ogsky.core.api.island.IslandRole;
import me.ogsammaenr.ogsky.core.internal.config.CoreConfig;
import me.ogsammaenr.ogsky.core.internal.flag.BuiltinFlags;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages Skyblock team invitations, membership roles ({@code COOP}, {@code TRUSTED}, {@code MEMBER},
 * {@code ADMIN}, {@code OWNER}), bans, and session-scoped {@code COOP} cleanup on player disconnect.
 */
public final class TeamService {
	public record PendingInvite(UUID islandId, UUID inviterId, byte targetRole, Instant expiresAt) {
		public boolean isExpired(Instant now) {
			return now.isAfter(expiresAt);
		}
	}

	private final CoreConfig config;
	private final CoreIslandService islandService;
	private final DatabaseService databaseService;
	private final OgskyEventBus eventBus;
	private final Map<UUID, PendingInvite> pendingInvites = new HashMap<>();

	public TeamService(
		CoreConfig config,
		CoreIslandService islandService,
		DatabaseService databaseService,
		OgskyEventBus eventBus
	) {
		this.config = Objects.requireNonNull(config, "config");
		this.islandService = Objects.requireNonNull(islandService, "islandService");
		this.databaseService = Objects.requireNonNull(databaseService, "databaseService");
		this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
	}

	public Optional<PendingInvite> findActiveInvite(UUID inviteeId, Instant now) {
		PendingInvite invite = this.pendingInvites.get(inviteeId);
		if (invite == null) {
			return Optional.empty();
		}
		if (invite.isExpired(now)) {
			this.pendingInvites.remove(inviteeId);
			return Optional.empty();
		}
		return Optional.of(invite);
	}

	public void clearPendingInvites() {
		this.pendingInvites.clear();
	}

	public OperationResult<PendingInvite> inviteMember(
		Island island,
		UUID inviterId,
		UUID inviteeId
	) {
		Objects.requireNonNull(island, "island");
		Objects.requireNonNull(inviterId, "inviterId");
		Objects.requireNonNull(inviteeId, "inviteeId");
		if (inviterId.equals(inviteeId)) {
			return OperationResult.fail("self_target", "You cannot invite yourself.");
		}
		if (island.roleOf(inviterId) < IslandRole.ADMIN) {
			return OperationResult.fail("insufficient_rank", "Only island admins or the owner can send invites.");
		}
		if (island.isBanned(inviteeId)) {
			return OperationResult.fail("banned", "Unban this player before inviting them.");
		}
		if (IslandRole.isPrimary(island.roleOf(inviteeId))) {
			return OperationResult.fail("already_member", "Player is already a member of this island.");
		}
		if (this.islandService.grid().findPrimaryIsland(inviteeId).isPresent()) {
			return OperationResult.fail("already_has_island", "Target player already belongs to a primary island.");
		}
		Optional<PendingInvite> existing = findActiveInvite(inviteeId, Instant.now());
		if (existing.isPresent() && existing.get().islandId().equals(island.islandId())) {
			return OperationResult.fail("invite_pending", "An invitation is already pending for that player.");
		}
		if (!this.eventBus.publishCancellable(new TeamInviteEvent(island.islandId(), inviterId, inviteeId, IslandRole.MEMBER))) {
			return OperationResult.fail("cancelled", "Team invite was cancelled.");
		}
		PendingInvite invite = new PendingInvite(
			island.islandId(),
			inviterId,
			IslandRole.MEMBER,
			Instant.now().plusSeconds(Math.max(5, this.config.membership.inviteTimeoutSeconds))
		);
		this.pendingInvites.put(inviteeId, invite);
		return OperationResult.ok("invited", "Invitation sent.", invite);
	}

	public OperationResult<Island> acceptInvite(UUID inviteeId) {
		Objects.requireNonNull(inviteeId, "inviteeId");
		PendingInvite invite = this.pendingInvites.get(inviteeId);
		if (invite == null) {
			return OperationResult.fail("no_invite", "You do not have a pending island invitation.");
		}
		if (invite.isExpired(Instant.now())) {
			this.pendingInvites.remove(inviteeId);
			return OperationResult.fail("invite_expired", "That island invitation has expired.");
		}
		IslandGrid grid = this.islandService.grid();
		if (grid.findPrimaryIsland(inviteeId).isPresent()) {
			this.pendingInvites.remove(inviteeId);
			return OperationResult.fail("already_has_island", "Leave or delete your current primary island first.");
		}
		Optional<Island> maybeIsland = grid.findById(invite.islandId());
		if (maybeIsland.isEmpty()) {
			this.pendingInvites.remove(inviteeId);
			return OperationResult.fail("no_island", "That island no longer exists.");
		}
		Island island = maybeIsland.get();
		if (island.isBanned(inviteeId)) {
			this.pendingInvites.remove(inviteeId);
			return OperationResult.fail("banned", "You are banned from that island.");
		}
		if (!grid.canJoinOrSetRole(island, inviteeId, IslandRole.MEMBER)) {
			return OperationResult.fail("membership_limit", "Cannot join island due to membership limits.");
		}
		this.pendingInvites.remove(inviteeId);
		grid.assignMemberRole(island, inviteeId, IslandRole.MEMBER);
		persistMember(island.islandId(), inviteeId, IslandRole.MEMBER);
		this.eventBus.publish(new TeamJoinEvent(island.islandId(), inviteeId, IslandRole.MEMBER));
		return OperationResult.ok("joined", "Joined island as " + this.islandService.roleDisplayName(IslandRole.MEMBER) + ".", island);
	}

	public OperationResult<Void> denyInvite(UUID inviteeId) {
		Objects.requireNonNull(inviteeId, "inviteeId");
		PendingInvite removed = this.pendingInvites.remove(inviteeId);
		if (removed == null || removed.isExpired(Instant.now())) {
			return OperationResult.fail("no_invite", "You have no active invite to decline.");
		}
		return OperationResult.ok("denied", "Declined the island invitation.");
	}

	public OperationResult<Void> cancelInvite(Island island, UUID actorId, UUID inviteeId) {
		Objects.requireNonNull(island, "island");
		Objects.requireNonNull(actorId, "actorId");
		Objects.requireNonNull(inviteeId, "inviteeId");
		if (island.roleOf(actorId) < IslandRole.ADMIN) {
			return OperationResult.fail("insufficient_rank", "Only island admins or the owner can cancel invites.");
		}
		PendingInvite current = this.pendingInvites.get(inviteeId);
		if (current == null || !current.islandId().equals(island.islandId()) || current.isExpired(Instant.now())) {
			this.pendingInvites.remove(inviteeId);
			return OperationResult.fail("no_invite", "No active invite from your island exists for that player.");
		}
		this.pendingInvites.remove(inviteeId);
		return OperationResult.ok("invite_cancelled", "Cancelled invitation.");
	}

	public OperationResult<Void> addCoop(Island island, UUID actorId, UUID targetId, boolean bypassRole) {
		return assignSecondaryRole(island, actorId, targetId, IslandRole.COOP, bypassRole, false);
	}

	public OperationResult<Void> removeCoop(
		@Nullable MinecraftServer server,
		Island island,
		UUID actorId,
		UUID targetId,
		boolean bypassRole
	) {
		return removeSecondaryRole(server, island, actorId, targetId, IslandRole.COOP, bypassRole, false);
	}

	public OperationResult<Void> addTrust(Island island, UUID actorId, UUID targetId, boolean bypassRole) {
		return assignSecondaryRole(island, actorId, targetId, IslandRole.TRUSTED, bypassRole, true);
	}

	public OperationResult<Void> removeTrust(
		@Nullable MinecraftServer server,
		Island island,
		UUID actorId,
		UUID targetId,
		boolean bypassRole
	) {
		return removeSecondaryRole(server, island, actorId, targetId, IslandRole.TRUSTED, bypassRole, true);
	}

	public OperationResult<Void> kickMember(
		@Nullable MinecraftServer server,
		Island island,
		UUID actorId,
		UUID targetId,
		boolean bypassRole
	) {
		Objects.requireNonNull(island, "island");
		Objects.requireNonNull(actorId, "actorId");
		Objects.requireNonNull(targetId, "targetId");
		if (actorId.equals(targetId)) {
			return OperationResult.fail("self_target", "Use /is leave instead of kicking yourself.");
		}
		byte actorRole = island.roleOf(actorId);
		byte targetRole = island.roleOf(targetId);
		if (!bypassRole && actorRole < IslandRole.ADMIN) {
			return OperationResult.fail("insufficient_rank", "Only island admins or the owner can kick players.");
		}
		if (targetRole == IslandRole.VISITOR) {
			return OperationResult.fail("not_member", "Player is not part of this island.");
		}
		if (targetRole == IslandRole.OWNER) {
			return OperationResult.fail("cannot_kick_owner", "Cannot kick the island owner.");
		}
		if (!bypassRole && actorRole <= targetRole) {
			return OperationResult.fail("insufficient_rank", "You cannot kick a player with equal or higher rank.");
		}
		if (!this.eventBus.publishCancellable(new TeamKickEvent(island.islandId(), actorId, targetId, targetRole))) {
			return OperationResult.fail("cancelled", "Kick was cancelled.");
		}
		this.islandService.grid().removeMemberRole(island, targetId);
		if (targetRole != IslandRole.COOP) {
			deleteMemberFromDb(island.islandId(), targetId);
		}
		if (server != null) {
			IslandLifecycleService.evictPlayersInside(server, island, player -> player.getUUID().equals(targetId));
		}
		return OperationResult.ok("kicked", "Removed player from island.");
	}

	public OperationResult<Void> leaveIsland(@Nullable MinecraftServer server, UUID playerId, Island island) {
		Objects.requireNonNull(playerId, "playerId");
		Objects.requireNonNull(island, "island");
		byte role = island.roleOf(playerId);
		if (role == IslandRole.VISITOR) {
			return OperationResult.fail("not_member", "You do not belong to this island.");
		}
		if (role == IslandRole.OWNER) {
			return OperationResult.fail("owner_cannot_leave", "Transfer ownership or delete the island before leaving.");
		}
		if (!this.eventBus.publishCancellable(new TeamLeaveEvent(island.islandId(), playerId, role))) {
			return OperationResult.fail("cancelled", "Leaving the island was cancelled.");
		}
		this.islandService.grid().removeMemberRole(island, playerId);
		if (role != IslandRole.COOP) {
			deleteMemberFromDb(island.islandId(), playerId);
		}
		if (server != null) {
			IslandLifecycleService.evictPlayersInside(server, island, player -> player.getUUID().equals(playerId));
		}
		return OperationResult.ok("left", "Left the island.");
	}

	public OperationResult<Byte> promoteMember(
		Island island,
		UUID actorId,
		UUID targetId,
		boolean bypassOwnership
	) {
		return changePrimaryRank(island, actorId, targetId, IslandRole.MEMBER, IslandRole.ADMIN, bypassOwnership);
	}

	public OperationResult<Byte> demoteMember(
		Island island,
		UUID actorId,
		UUID targetId,
		boolean bypassOwnership
	) {
		return changePrimaryRank(island, actorId, targetId, IslandRole.ADMIN, IslandRole.MEMBER, bypassOwnership);
	}

	public OperationResult<Void> transferOwnership(
		Island island,
		UUID actorId,
		UUID newOwnerId,
		boolean bypassOwnership
	) {
		Objects.requireNonNull(island, "island");
		Objects.requireNonNull(actorId, "actorId");
		Objects.requireNonNull(newOwnerId, "newOwnerId");
		if (!bypassOwnership && !island.ownerId().equals(actorId)) {
			return OperationResult.fail("not_owner", "Only the island owner can transfer ownership.");
		}
		if (island.ownerId().equals(newOwnerId)) {
			return OperationResult.fail("already_owner", "That player is already the island owner.");
		}
		if (!IslandRole.isPrimary(island.roleOf(newOwnerId))) {
			return OperationResult.fail("must_be_primary_member", "New owner must already be a primary member or admin of this island.");
		}
		UUID oldOwnerId = island.ownerId();
		if (!this.eventBus.publishCancellable(new TeamOwnerChangeEvent(island.islandId(), oldOwnerId, newOwnerId))) {
			return OperationResult.fail("cancelled", "Ownership transfer was cancelled.");
		}
		this.islandService.grid().transferOwnership(island, newOwnerId);
		this.databaseService.find(CoreIslandRepository.MODULE_ID).ifPresent(db -> {
			CoreIslandRepository.upsertIslandAsync(db, island);
			CoreIslandRepository.upsertMemberAsync(db, island.islandId(), newOwnerId, IslandRole.OWNER);
			CoreIslandRepository.upsertMemberAsync(db, island.islandId(), oldOwnerId, IslandRole.ADMIN);
		});
		return OperationResult.ok("transferred", "Island ownership transferred.");
	}

	public OperationResult<Void> banPlayer(
		@Nullable MinecraftServer server,
		Island island,
		UUID actorId,
		UUID targetId,
		boolean bypassRole
	) {
		Objects.requireNonNull(island, "island");
		Objects.requireNonNull(actorId, "actorId");
		Objects.requireNonNull(targetId, "targetId");
		if (actorId.equals(targetId)) {
			return OperationResult.fail("self_target", "You cannot ban yourself.");
		}
		byte actorRole = island.roleOf(actorId);
		byte targetRole = island.roleOf(targetId);
		if (!bypassRole && actorRole < IslandRole.ADMIN) {
			return OperationResult.fail("insufficient_rank", "Only island admins or the owner can ban players.");
		}
		if (targetRole == IslandRole.OWNER) {
			return OperationResult.fail("cannot_ban_owner", "Cannot ban the island owner.");
		}
		if (!bypassRole && targetRole >= actorRole) {
			return OperationResult.fail("insufficient_rank", "Cannot ban a player with equal or higher rank.");
		}
		if (island.isBanned(targetId)) {
			return OperationResult.fail("already_banned", "Player is already banned from this island.");
		}
		if (!this.eventBus.publishCancellable(new IslandBanEvent(island.islandId(), actorId, targetId))) {
			return OperationResult.fail("cancelled", "Ban was cancelled.");
		}
		if (targetRole != IslandRole.VISITOR) {
			this.islandService.grid().removeMemberRole(island, targetId);
			if (targetRole != IslandRole.COOP) {
				deleteMemberFromDb(island.islandId(), targetId);
			}
		}
		island.addBan(targetId);
		this.databaseService.find(CoreIslandRepository.MODULE_ID)
			.ifPresent(db -> CoreIslandRepository.upsertBanAsync(db, island.islandId(), targetId, actorId));
		if (server != null) {
			IslandLifecycleService.evictPlayersInside(server, island, player -> player.getUUID().equals(targetId));
		}
		return OperationResult.ok("banned", "Player banned from island.");
	}

	public OperationResult<Void> unbanPlayer(Island island, UUID actorId, UUID targetId, boolean bypassRole) {
		Objects.requireNonNull(island, "island");
		Objects.requireNonNull(actorId, "actorId");
		Objects.requireNonNull(targetId, "targetId");
		if (!bypassRole && island.roleOf(actorId) < IslandRole.ADMIN) {
			return OperationResult.fail("insufficient_rank", "Only island admins or the owner can unban players.");
		}
		if (!island.isBanned(targetId)) {
			return OperationResult.fail("not_banned", "Player is not banned from this island.");
		}
		island.removeBan(targetId);
		this.databaseService.find(CoreIslandRepository.MODULE_ID)
			.ifPresent(db -> CoreIslandRepository.deleteBanAsync(db, island.islandId(), targetId));
		this.eventBus.publish(new IslandUnbanEvent(island.islandId(), actorId, targetId));
		return OperationResult.ok("unbanned", "Player unbanned from island.");
	}

	public void onPlayerDisconnect(@Nullable MinecraftServer server, UUID playerUuid) {
		Objects.requireNonNull(playerUuid, "playerUuid");
		this.pendingInvites.remove(playerUuid);
		List<IslandGrid.CoopRemoval> removals = this.islandService.grid().clearCoopForDisconnect(playerUuid);
		for (IslandGrid.CoopRemoval removal : removals) {
			this.eventBus.publish(new TeamLeaveEvent(removal.islandId(), removal.playerUuid(), IslandRole.COOP));
			if (server != null && !removal.playerUuid().equals(playerUuid)) {
				this.islandService.grid().findById(removal.islandId()).ifPresent(island -> {
					if (island.isSettingEnabled(BuiltinFlags.ISLAND_LOCK)) {
						IslandLifecycleService.evictPlayersInside(
							server,
							island,
							player -> player.getUUID().equals(removal.playerUuid())
						);
					}
				});
			}
		}
	}

	private OperationResult<Void> assignSecondaryRole(
		Island island,
		UUID actorId,
		UUID targetId,
		byte secondaryRole,
		boolean bypassRole,
		boolean persistInDb
	) {
		Objects.requireNonNull(island, "island");
		Objects.requireNonNull(actorId, "actorId");
		Objects.requireNonNull(targetId, "targetId");
		if (actorId.equals(targetId)) {
			return OperationResult.fail("self_target", "Cannot assign a secondary role to yourself.");
		}
		if (!bypassRole && island.roleOf(actorId) < IslandRole.ADMIN) {
			return OperationResult.fail("insufficient_rank", "Only island admins or the owner can assign secondary roles.");
		}
		if (island.isBanned(targetId)) {
			return OperationResult.fail("banned", "Unban that player first.");
		}
		byte currentRole = island.roleOf(targetId);
		if (IslandRole.isPrimary(currentRole)) {
			return OperationResult.fail("already_primary", "Player is already a primary member of this island.");
		}
		if (currentRole == secondaryRole) {
			return OperationResult.fail("already_assigned", "Player already has that role on this island.");
		}
		if (!this.islandService.grid().canJoinOrSetRole(island, targetId, secondaryRole)) {
			String code = secondaryRole == IslandRole.COOP ? "coop_limit" : "trusted_limit";
			return OperationResult.fail(code, "Player reached the secondary role limit for " + this.islandService.roleDisplayName(secondaryRole) + ".");
		}
		if (!this.eventBus.publishCancellable(new TeamInviteEvent(island.islandId(), actorId, targetId, secondaryRole))) {
			return OperationResult.fail("cancelled", "Secondary role assignment was cancelled.");
		}
		this.islandService.grid().assignMemberRole(island, targetId, secondaryRole);
		if (persistInDb) {
			persistMember(island.islandId(), targetId, secondaryRole);
		}
		this.eventBus.publish(new TeamJoinEvent(island.islandId(), targetId, secondaryRole));
		return OperationResult.ok("assigned", "Assigned role " + this.islandService.roleDisplayName(secondaryRole) + ".");
	}

	private OperationResult<Void> removeSecondaryRole(
		@Nullable MinecraftServer server,
		Island island,
		UUID actorId,
		UUID targetId,
		byte expectedRole,
		boolean bypassRole,
		boolean persistedInDb
	) {
		Objects.requireNonNull(island, "island");
		Objects.requireNonNull(actorId, "actorId");
		Objects.requireNonNull(targetId, "targetId");
		if (!bypassRole && island.roleOf(actorId) < IslandRole.ADMIN) {
			return OperationResult.fail("insufficient_rank", "Only island admins or the owner can remove secondary roles.");
		}
		if (island.roleOf(targetId) != expectedRole) {
			return OperationResult.fail("wrong_role", "Player does not hold that role on this island.");
		}
		if (!this.eventBus.publishCancellable(new TeamKickEvent(island.islandId(), actorId, targetId, expectedRole))) {
			return OperationResult.fail("cancelled", "Secondary role removal was cancelled.");
		}
		this.islandService.grid().removeMemberRole(island, targetId);
		if (persistedInDb) {
			deleteMemberFromDb(island.islandId(), targetId);
		}
		if (server != null && island.isSettingEnabled(BuiltinFlags.ISLAND_LOCK)) {
			IslandLifecycleService.evictPlayersInside(server, island, player -> player.getUUID().equals(targetId));
		}
		return OperationResult.ok("removed", "Removed role " + this.islandService.roleDisplayName(expectedRole) + ".");
	}

	private OperationResult<Byte> changePrimaryRank(
		Island island,
		UUID actorId,
		UUID targetId,
		byte expectedOldRole,
		byte targetNewRole,
		boolean bypassOwnership
	) {
		Objects.requireNonNull(island, "island");
		Objects.requireNonNull(actorId, "actorId");
		Objects.requireNonNull(targetId, "targetId");
		if (!bypassOwnership && !island.ownerId().equals(actorId)) {
			return OperationResult.fail("not_owner", "Only the island owner can promote or demote admins.");
		}
		byte currentRole = island.roleOf(targetId);
		if (currentRole != expectedOldRole) {
			return OperationResult.fail(
				"wrong_role",
				"Target must currently have role " + this.islandService.roleDisplayName(expectedOldRole) + "."
			);
		}
		if (!this.eventBus.publishCancellable(new TeamRankChangeEvent(island.islandId(), actorId, targetId, currentRole, targetNewRole))) {
			return OperationResult.fail("cancelled", "Rank change was cancelled.");
		}
		this.islandService.grid().assignMemberRole(island, targetId, targetNewRole);
		persistMember(island.islandId(), targetId, targetNewRole);
		return OperationResult.ok("rank_changed", "Updated rank to " + this.islandService.roleDisplayName(targetNewRole) + ".", targetNewRole);
	}

	private void persistMember(UUID islandId, UUID playerUuid, byte role) {
		this.databaseService.find(CoreIslandRepository.MODULE_ID)
			.ifPresent(db -> CoreIslandRepository.upsertMemberAsync(db, islandId, playerUuid, role));
	}

	private void deleteMemberFromDb(UUID islandId, UUID playerUuid) {
		this.databaseService.find(CoreIslandRepository.MODULE_ID)
			.ifPresent(db -> CoreIslandRepository.deleteMemberAsync(db, islandId, playerUuid));
	}
}
