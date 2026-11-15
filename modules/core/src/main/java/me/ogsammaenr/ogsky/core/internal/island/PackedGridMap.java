package me.ogsammaenr.ogsky.core.internal.island;

import java.util.Arrays;
import java.util.Objects;

/**
 * Zero-boxing open-addressing map keyed by packed {@code (gridX, gridZ)} {@code long} coordinates.
 */
public final class PackedGridMap<V> {
	private static final byte EMPTY = 0;
	private static final byte OCCUPIED = 1;
	private static final byte TOMBSTONE = 2;

	private long[] keys;
	private Object[] values;
	private byte[] states;
	private int size;
	private int usedSlots;
	private int threshold;

	public PackedGridMap() {
		this(16);
	}

	public PackedGridMap(int initialCapacity) {
		int cap = Integer.highestOneBit(Math.max(16, initialCapacity) - 1) << 1;
		this.keys = new long[cap];
		this.values = new Object[cap];
		this.states = new byte[cap];
		this.threshold = (int) (cap * 0.65f);
	}

	public static long pack(int gridX, int gridZ) {
		return ((long) gridX << 32) | (gridZ & 0xFFFFFFFFL);
	}

	public static int unpackX(long packed) {
		return (int) (packed >> 32);
	}

	public static int unpackZ(long packed) {
		return (int) packed;
	}

	public int size() {
		return size;
	}

	public boolean isEmpty() {
		return size == 0;
	}

	public boolean containsKey(long key) {
		return findIndex(key) >= 0;
	}

	@SuppressWarnings("unchecked")
	public V get(long key) {
		int idx = findIndex(key);
		return idx >= 0 ? (V) values[idx] : null;
	}

	@SuppressWarnings("unchecked")
	public V put(long key, V value) {
		Objects.requireNonNull(value, "value");
		if (usedSlots >= threshold) {
			rehash(keys.length << 1);
		}
		int mask = keys.length - 1;
		int idx = mix64(key) & mask;
		int firstTombstone = -1;
		while (true) {
			byte state = states[idx];
			if (state == EMPTY) {
				int target = firstTombstone >= 0 ? firstTombstone : idx;
				if (states[target] == EMPTY) {
					usedSlots++;
				}
				keys[target] = key;
				values[target] = value;
				states[target] = OCCUPIED;
				size++;
				return null;
			}
			if (state == OCCUPIED && keys[idx] == key) {
				V prev = (V) values[idx];
				values[idx] = value;
				return prev;
			}
			if (state == TOMBSTONE && firstTombstone < 0) {
				firstTombstone = idx;
			}
			idx = (idx + 1) & mask;
		}
	}

	@SuppressWarnings("unchecked")
	public V remove(long key) {
		int idx = findIndex(key);
		if (idx < 0) {
			return null;
		}
		V prev = (V) values[idx];
		values[idx] = null;
		states[idx] = TOMBSTONE;
		size--;
		return prev;
	}

	public void clear() {
		Arrays.fill(states, EMPTY);
		Arrays.fill(values, null);
		size = 0;
		usedSlots = 0;
	}

	private int findIndex(long key) {
		int mask = keys.length - 1;
		int idx = mix64(key) & mask;
		while (true) {
			byte state = states[idx];
			if (state == EMPTY) {
				return -1;
			}
			if (state == OCCUPIED && keys[idx] == key) {
				return idx;
			}
			idx = (idx + 1) & mask;
		}
	}

	private void rehash(int newCapacity) {
		long[] oldKeys = keys;
		Object[] oldValues = values;
		byte[] oldStates = states;

		keys = new long[newCapacity];
		values = new Object[newCapacity];
		states = new byte[newCapacity];
		threshold = (int) (newCapacity * 0.65f);
		size = 0;
		usedSlots = 0;

		for (int i = 0; i < oldKeys.length; i++) {
			if (oldStates[i] == OCCUPIED) {
				@SuppressWarnings("unchecked")
				V val = (V) oldValues[i];
				put(oldKeys[i], val);
			}
		}
	}

	private static int mix64(long z) {
		z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
		z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
		z ^= (z >>> 31);
		return (int) z;
	}
}
