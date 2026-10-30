package com.pvmlogs;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * T-110: a random id for this install, made once and kept in its own small
 * file in the plugin's data folder (the plugin reads and writes it through
 * {@code Filepath}). Kept for later: nothing is sent anywhere. It identifies
 * an install, never an account: it is not the account hash, the name or the
 * world, and nothing about the account goes into it.
 *
 * <p>In the data folder rather than the settings: RuneLite syncs settings to a
 * logged-in profile and across PCs, and an install id that followed the
 * profile would no longer be one install's.
 */
final class InstallId
{
	static final String FILE = "install-id";
	private static final Pattern VALID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

	private InstallId()
	{
	}

	/**
	 * The id kept, or a new one written there when there is none or what is
	 * there isn't one.
	 *
	 * @param read  the file's text, or null when it doesn't exist or can't be read
	 * @param write writes the new id
	 */
	static String load(Supplier<String> read, Consumer<String> write)
	{
		final String kept = read.get();
		if (kept != null && VALID.matcher(kept.trim()).matches())
		{
			return kept.trim();
		}
		final String made = UUID.randomUUID().toString();
		write.accept(made);
		return made;
	}
}
