package me.ogsammaenr.ogsky.core.internal.command;

import me.ogsammaenr.ogsky.core.api.command.IslandCommandRegistry;
import me.ogsammaenr.ogsky.core.api.command.IslandSubcommand;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Thread-safe {@link IslandCommandRegistry} collecting subcommands for Brigadier registration.
 */
public final class SimpleIslandCommandRegistry implements IslandCommandRegistry {
	private final List<IslandSubcommand> subcommands = new ArrayList<>();
	private final Set<String> usedNames = new HashSet<>();

	private final List<IslandSubcommand> adminSubcommands = new ArrayList<>();
	private final Set<String> usedAdminNames = new HashSet<>();

	@Override
	public synchronized void registerSubcommand(IslandSubcommand subcommand) {
		registerInto(subcommand, subcommands, usedNames, "/island");
	}

	@Override
	public synchronized void registerAdminSubcommand(IslandSubcommand subcommand) {
		registerInto(subcommand, adminSubcommands, usedAdminNames, "/isadmin");
	}

	@Override
	public synchronized List<IslandSubcommand> subcommands() {
		return List.copyOf(subcommands);
	}

	@Override
	public synchronized List<IslandSubcommand> adminSubcommands() {
		return List.copyOf(adminSubcommands);
	}

	private static void registerInto(
		IslandSubcommand subcommand,
		List<IslandSubcommand> target,
		Set<String> names,
		String rootName
	) {
		Objects.requireNonNull(subcommand, "subcommand");
		String primary = normalizeLiteral(subcommand.name(), rootName);
		List<String> normalizedAliases = subcommand.aliases().stream()
			.map(alias -> normalizeLiteral(alias, rootName))
			.toList();

		if (!names.add(primary)) {
			throw new IllegalStateException("Duplicate " + rootName + " subcommand literal: " + primary);
		}
		for (String alias : normalizedAliases) {
			if (!names.add(alias)) {
				throw new IllegalStateException("Duplicate " + rootName + " subcommand alias: " + alias);
			}
		}
		target.add(subcommand);
	}

	private static String normalizeLiteral(String raw, String rootName) {
		if (raw == null || raw.isBlank()) {
			throw new IllegalArgumentException(rootName + " subcommand literal must not be blank.");
		}
		return raw.trim().toLowerCase(Locale.ROOT);
	}
}
