package me.ogsammaenr.ogsky.core.internal.mutation;

import me.ogsammaenr.ogsky.core.api.mutation.BlockChangeObserver;
import me.ogsammaenr.ogsky.core.api.mutation.BlockMutationDecision;
import me.ogsammaenr.ogsky.core.api.mutation.BlockPlacementGuard;
import me.ogsammaenr.ogsky.core.api.mutation.BlockPlacementSource;
import me.ogsammaenr.ogsky.core.api.mutation.EntityLifecycleObserver;
import me.ogsammaenr.ogsky.core.api.mutation.EntitySpawnGuard;
import me.ogsammaenr.ogsky.core.api.mutation.EntitySpawnSource;
import me.ogsammaenr.ogsky.core.api.mutation.WorldMutationService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Default {@link WorldMutationService} implementation with volatile fast-path flags
 * so hot chunk/entity paths pay negligible cost when no guards or observers are registered.
 */
public final class CoreWorldMutationService implements WorldMutationService {
	private static volatile @Nullable CoreWorldMutationService active;

	private final List<BlockPlacementGuard> blockPlacementGuards = new CopyOnWriteArrayList<>();
	private final List<BlockChangeObserver> blockChangeObservers = new CopyOnWriteArrayList<>();
	private final List<EntitySpawnGuard> entitySpawnGuards = new CopyOnWriteArrayList<>();
	private final List<EntityLifecycleObserver> entityLifecycleObservers = new CopyOnWriteArrayList<>();

	private volatile boolean anyBlockPlacementGuards;
	private volatile boolean anyBlockChangeObservers;
	private volatile boolean anyEntitySpawnGuards;
	private volatile boolean anyEntityLifecycleObservers;
	private final ThreadLocal<@Nullable ServerPlayer> activeActor = new ThreadLocal<>();

	public CoreWorldMutationService() {
	}

	public @Nullable ServerPlayer currentActor() {
		return activeActor.get();
	}

	public @Nullable ServerPlayer pushActor(@Nullable ServerPlayer actor) {
		ServerPlayer previous = activeActor.get();
		if (actor != null) {
			activeActor.set(actor);
		} else {
			activeActor.remove();
		}
		return previous;
	}

	public void popActor(@Nullable ServerPlayer previous) {
		if (previous != null) {
			activeActor.set(previous);
		} else {
			activeActor.remove();
		}
	}

	public static @Nullable CoreWorldMutationService active() {
		return active;
	}

	public static void setActive(@Nullable CoreWorldMutationService service) {
		active = service;
	}

	public static boolean hasBlockPlacementGuards() {
		CoreWorldMutationService service = active;
		return service != null && service.anyBlockPlacementGuards;
	}

	public static boolean hasBlockChangeObservers() {
		CoreWorldMutationService service = active;
		return service != null && service.anyBlockChangeObservers;
	}

	public static boolean hasEntitySpawnGuards() {
		CoreWorldMutationService service = active;
		return service != null && service.anyEntitySpawnGuards;
	}

	public static boolean hasEntityLifecycleObservers() {
		CoreWorldMutationService service = active;
		return service != null && service.anyEntityLifecycleObservers;
	}

	@Override
	public void registerBlockPlacementGuard(BlockPlacementGuard guard) {
		blockPlacementGuards.add(Objects.requireNonNull(guard, "guard"));
		anyBlockPlacementGuards = true;
	}

	@Override
	public void registerBlockChangeObserver(BlockChangeObserver observer) {
		blockChangeObservers.add(Objects.requireNonNull(observer, "observer"));
		anyBlockChangeObservers = true;
	}

	@Override
	public void registerEntitySpawnGuard(EntitySpawnGuard guard) {
		entitySpawnGuards.add(Objects.requireNonNull(guard, "guard"));
		anyEntitySpawnGuards = true;
	}

	@Override
	public void registerEntityLifecycleObserver(EntityLifecycleObserver observer) {
		entityLifecycleObservers.add(Objects.requireNonNull(observer, "observer"));
		anyEntityLifecycleObservers = true;
	}

	public BlockMutationDecision evaluateBlockPlacement(
		ServerLevel level,
		BlockPos pos,
		BlockState newState,
		BlockState previousState,
		@Nullable ServerPlayer actor,
		BlockPlacementSource source
	) {
		if (!anyBlockPlacementGuards || !isServerThread(level)) {
			return BlockMutationDecision.allow();
		}
		BlockState effective = newState;
		boolean replaced = false;
		for (BlockPlacementGuard guard : blockPlacementGuards) {
			BlockMutationDecision decision = guard.checkPlacement(level, pos, effective, previousState, actor, source);
			if (decision == null || decision.isAllowed()) {
				continue;
			}
			if (decision.isDenied()) {
				return BlockMutationDecision.deny();
			}
			if (decision.isReplaced() && decision.replacementState() != null) {
				effective = decision.replacementState();
				replaced = true;
			}
		}
		return replaced ? BlockMutationDecision.replace(effective) : BlockMutationDecision.allow();
	}

	public void dispatchBlockChanged(
		ServerLevel level,
		BlockPos pos,
		BlockState oldState,
		BlockState newState,
		int updateFlags
	) {
		if (!anyBlockChangeObservers || !isServerThread(level)) {
			return;
		}
		for (BlockChangeObserver observer : blockChangeObservers) {
			observer.onBlockChanged(level, pos, oldState, newState, updateFlags);
		}
	}

	public boolean evaluateEntitySpawn(
		ServerLevel level,
		EntityType<?> entityType,
		double x,
		double y,
		double z,
		@Nullable ServerPlayer actor,
		EntitySpawnSource source
	) {
		if (!anyEntitySpawnGuards || !isServerThread(level)) {
			return true;
		}
		for (EntitySpawnGuard guard : entitySpawnGuards) {
			if (!guard.allowEntitySpawn(level, entityType, x, y, z, actor, source)) {
				return false;
			}
		}
		return true;
	}

	public void dispatchEntityAdded(ServerLevel level, Entity entity) {
		if (!anyEntityLifecycleObservers || !isServerThread(level)) {
			return;
		}
		for (EntityLifecycleObserver observer : entityLifecycleObservers) {
			observer.onEntityAdded(level, entity);
		}
	}

	public void dispatchEntityRemoved(ServerLevel level, Entity entity) {
		if (!anyEntityLifecycleObservers || !isServerThread(level)) {
			return;
		}
		for (EntityLifecycleObserver observer : entityLifecycleObservers) {
			observer.onEntityRemoved(level, entity);
		}
	}

	public boolean dispatchEntityCrossedBlockColumn(
		ServerLevel level,
		Entity entity,
		int fromBlockX,
		int fromBlockZ,
		int toBlockX,
		int toBlockZ
	) {
		if (!anyEntityLifecycleObservers || !isServerThread(level)) {
			return true;
		}
		for (EntityLifecycleObserver observer : entityLifecycleObservers) {
			if (!observer.onEntityCrossedBlockColumn(level, entity, fromBlockX, fromBlockZ, toBlockX, toBlockZ)) {
				return false;
			}
		}
		return true;
	}

	private static boolean isServerThread(@Nullable ServerLevel level) {
		if (level == null) {
			return true;
		}
		MinecraftServer server = level.getServer();
		return server == null || server.isSameThread();
	}
}
