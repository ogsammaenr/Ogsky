package me.ogsammaenr.ogsky.core.internal.island;

import java.util.function.LongPredicate;

/**
 * Deterministic outward integer spiral allocator centered on {@code (0, 0)}.
 */
public final class SpiralGridAllocator {
	private SpiralGridAllocator() {
	}

	/**
	 * Finds the first grid coordinate {@code (gridX, gridZ)} in outward spiral order from {@code (0, 0)}
	 * where {@code isUnavailable.test(PackedGridMap.pack(gridX, gridZ))} returns {@code false}.
	 */
	public static long nextAvailableCell(LongPredicate isUnavailable) {
		long origin = PackedGridMap.pack(0, 0);
		if (!isUnavailable.test(origin)) {
			return origin;
		}

		int x = 0;
		int z = 0;
		int legLength = 1;
		int dir = 0; // 0=+X, 1=+Z, 2=-X, 3=-Z

		while (true) {
			for (int repeat = 0; repeat < 2; repeat++) {
				for (int step = 0; step < legLength; step++) {
					switch (dir) {
						case 0 -> x++;
						case 1 -> z++;
						case 2 -> x--;
						case 3 -> z--;
					}
					long packed = PackedGridMap.pack(x, z);
					if (!isUnavailable.test(packed)) {
						return packed;
					}
				}
				dir = (dir + 1) & 3;
			}
			legLength++;
		}
	}
}
