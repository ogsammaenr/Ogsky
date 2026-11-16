package me.ogsammaenr.ogsky.core.api.mutation;

/**
 * Module-agnostic registry for pre-mutation guards and committed block/entity lifecycle callbacks.
 * Hot mixin call sites short-circuit via volatile flags when no handlers of a given category are registered.
 */
public interface WorldMutationService {
	/**
	 * Registers a pre-placement guard consulted before guarded placements or fluid generators mutate blocks.
	 */
	void registerBlockPlacementGuard(BlockPlacementGuard guard);

	/**
	 * Registers a post-commit observer notified whenever block state changes commit on the main server thread.
	 */
	void registerBlockChangeObserver(BlockChangeObserver observer);

	/**
	 * Registers a pre-spawn guard consulted before entities are instantiated or added to a server level.
	 */
	void registerEntitySpawnGuard(EntitySpawnGuard guard);

	/**
	 * Registers an entity creation, permanent removal/dimension-transfer, and block-column crossing observer.
	 */
	void registerEntityLifecycleObserver(EntityLifecycleObserver observer);
}
