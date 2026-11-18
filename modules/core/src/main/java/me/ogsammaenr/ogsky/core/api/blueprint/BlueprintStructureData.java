package me.ogsammaenr.ogsky.core.api.blueprint;

import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Objects;

/**
 * Read-only decoded block geometry of a blueprint template, excluding air and internal anchor-only markers.
 */
public record BlueprintStructureData(
	int sizeX,
	int sizeY,
	int sizeZ,
	List<PositionedBlock> blocks
) {
	public record PositionedBlock(int x, int y, int z, BlockState state) {
		public PositionedBlock {
			Objects.requireNonNull(state, "state");
		}
	}

	public BlueprintStructureData {
		sizeX = Math.max(0, sizeX);
		sizeY = Math.max(0, sizeY);
		sizeZ = Math.max(0, sizeZ);
		blocks = blocks == null ? List.of() : List.copyOf(blocks);
	}
}
