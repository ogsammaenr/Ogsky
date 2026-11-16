package me.ogsammaenr.ogsky.limits;

import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.UUID;

/**
 * Compact per-island primitive RAM counter state aligned with {@link LimitRuleTable}.
 */
final class IslandLimitState {
	final UUID islandId;
	int[] blockCounts;
	int[] entityCounts;
	boolean synchronizedFromWorld;
	boolean scanning;
	int @Nullable [] pendingBlockDeltasWhileScanning;
	int @Nullable [] pendingEntityDeltasWhileScanning;
	String syncedRulesHash = "";
	long lastSyncedAtMillis;
	boolean dirty;
	long scanToken;

	IslandLimitState(UUID islandId, int blockRuleCount, int entityRuleCount) {
		this.islandId = islandId;
		this.blockCounts = new int[blockRuleCount];
		this.entityCounts = new int[entityRuleCount];
	}

	void addBlockDelta(int[] ruleIndices, int delta) {
		if (ruleIndices.length == 0 || delta == 0) {
			return;
		}
		for (int idx : ruleIndices) {
			if (idx >= 0 && idx < blockCounts.length) {
				blockCounts[idx] = Math.max(0, blockCounts[idx] + delta);
				if (scanning && pendingBlockDeltasWhileScanning != null) {
					pendingBlockDeltasWhileScanning[idx] += delta;
				}
			}
		}
		dirty = true;
	}

	void addEntityDelta(int[] ruleIndices, int delta) {
		if (ruleIndices.length == 0 || delta == 0) {
			return;
		}
		for (int idx : ruleIndices) {
			if (idx >= 0 && idx < entityCounts.length) {
				entityCounts[idx] = Math.max(0, entityCounts[idx] + delta);
				if (scanning && pendingEntityDeltasWhileScanning != null) {
					pendingEntityDeltasWhileScanning[idx] += delta;
				}
			}
		}
		dirty = true;
	}

	long beginScan() {
		scanToken++;
		scanning = true;
		pendingBlockDeltasWhileScanning = new int[blockCounts.length];
		pendingEntityDeltasWhileScanning = new int[entityCounts.length];
		return scanToken;
	}

	void finishScan(long expectedToken, int[] scannedBlocks, int[] scannedEntities, String rulesHash, long completedAtMillis) {
		if (scanToken != expectedToken) {
			return;
		}
		int[] blockDeltas = pendingBlockDeltasWhileScanning;
		int[] entityDeltas = pendingEntityDeltasWhileScanning;
		for (int i = 0; i < blockCounts.length; i++) {
			int scanned = i < scannedBlocks.length ? scannedBlocks[i] : 0;
			int delta = blockDeltas != null && i < blockDeltas.length ? blockDeltas[i] : 0;
			blockCounts[i] = Math.max(0, scanned + delta);
		}
		for (int i = 0; i < entityCounts.length; i++) {
			int scanned = i < scannedEntities.length ? scannedEntities[i] : 0;
			int delta = entityDeltas != null && i < entityDeltas.length ? entityDeltas[i] : 0;
			entityCounts[i] = Math.max(0, scanned + delta);
		}
		scanning = false;
		pendingBlockDeltasWhileScanning = null;
		pendingEntityDeltasWhileScanning = null;
		synchronizedFromWorld = true;
		syncedRulesHash = rulesHash;
		lastSyncedAtMillis = completedAtMillis;
		dirty = true;
	}

	void cancelScan() {
		scanToken++;
		scanning = false;
		pendingBlockDeltasWhileScanning = null;
		pendingEntityDeltasWhileScanning = null;
	}

	void resetAllCounts() {
		cancelScan();
		Arrays.fill(blockCounts, 0);
		Arrays.fill(entityCounts, 0);
		synchronizedFromWorld = false;
		syncedRulesHash = "";
		lastSyncedAtMillis = 0L;
		dirty = false;
	}

	void remapFrom(LimitRuleTable oldTable, LimitRuleTable newTable) {
		cancelScan();
		int[] nextBlocks = new int[newTable.blockRuleCount()];
		for (LimitRuleTable.LimitRule oldRule : oldTable.blockRules()) {
			Integer newIdx = newTable.findBlockRuleIndex(oldRule.key());
			if (newIdx != null && oldRule.index() < blockCounts.length) {
				nextBlocks[newIdx] = blockCounts[oldRule.index()];
			}
		}
		int[] nextEntities = new int[newTable.entityRuleCount()];
		for (LimitRuleTable.LimitRule oldRule : oldTable.entityRules()) {
			Integer newIdx = newTable.findEntityRuleIndex(oldRule.key());
			if (newIdx != null && oldRule.index() < entityCounts.length) {
				nextEntities[newIdx] = entityCounts[oldRule.index()];
			}
		}
		this.blockCounts = nextBlocks;
		this.entityCounts = nextEntities;
		if (!newTable.rulesHash().equals(this.syncedRulesHash)) {
			this.synchronizedFromWorld = false;
		}
	}
}
