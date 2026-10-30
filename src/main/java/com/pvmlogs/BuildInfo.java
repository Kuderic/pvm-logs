package com.pvmlogs;

import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Properties;
import java.util.regex.Pattern;
import net.runelite.client.externalplugins.ExternalPluginManager;
import net.runelite.client.externalplugins.PluginHubManifest;

/**
 * Which build of the plugin wrote a debug log (T-096). A log read against a fix is worth nothing if the client ran a
 * build from before it, and nothing in the log used to say.
 * <p>
 * Our {@code build.gradle} writes {@code com/pvmlogs/build-info.properties} into the build on every build: the commit,
 * the branch, how many tracked files had uncommitted changes, the commit's date and the build's time. Nothing else:
 * no path, user or machine name.
 * <p>
 * The Plugin Hub builds with its own {@code build.gradle} and copies only {@code src/main}, so a hub build has no such
 * file. There the version the hub shows is read instead (from {@code runelite-plugin.properties}, through RuneLite's
 * {@link ExternalPluginManager#getDisplayData}).
 */
final class BuildInfo
{
	static final String RESOURCE = "build-info.properties";

	// What each value may look like. A value that doesn't fit is a malformed file, and is not printed.
	private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{7,40}");
	private static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9._/+-]{1,100}");
	private static final Pattern COUNT = Pattern.compile("[0-9]{1,6}");
	private static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	private BuildInfo()
	{
	}

	/** The build that is running, in words, for the debug log. Read once; it can't change while the client runs. */
	static String describe()
	{
		return Holder.LINE;
	}

	private static final class Holder
	{
		static final String LINE = describe(read(BuildInfo.class.getResourceAsStream(RESOURCE)), hubVersion(),
			BuildInfo.class.getPackage() == null ? null : BuildInfo.class.getPackage().getImplementationVersion(),
			ZoneId.systemDefault());
	}

	/** The file's values, or null when there is no file or it cannot be read. */
	static Properties read(InputStream in)
	{
		if (in == null)
		{
			return null;
		}
		try (InputStream stream = in)
		{
			final Properties p = new Properties();
			p.load(stream);
			return p;
		}
		catch (IOException | IllegalArgumentException e)
		{
			return null;
		}
	}

	// The version the Plugin Hub lists, or null when the plugin wasn't installed from it (the dev client, the tests).
	private static String hubVersion()
	{
		try
		{
			final PluginHubManifest.DisplayData data = ExternalPluginManager.getDisplayData(PvmPerformancePlugin.class);
			return data == null ? null : data.getVersion();
		}
		catch (RuntimeException | LinkageError e)
		{
			return null;
		}
	}

	/**
	 * The build in words, from what could be read.
	 *
	 * @param build       the build-info file's values, or null
	 * @param hubVersion  the version the Plugin Hub lists, or null
	 * @param implVersion the jar's {@code Implementation-Version}, or null
	 * @param zone        the zone the times are shown in: the player's own
	 */
	static String describe(Properties build, String hubVersion, String implVersion, ZoneId zone)
	{
		final String commit = build == null ? null : value(build, "commit", COMMIT);
		if (commit != null)
		{
			final StringBuilder b = new StringBuilder("build ").append(commit).append(" (");
			final String branch = value(build, "branch", BRANCH);
			b.append(branch == null ? "branch unknown" : "HEAD".equals(branch) ? "detached" : branch);
			final String dirty = value(build, "dirtyFiles", COUNT);
			if (dirty == null)
			{
				b.append(", changes unknown");
			}
			else
			{
				final int n = Integer.parseInt(dirty);
				b.append(n == 0 ? ", clean" : ", dirty: " + n + (n == 1 ? " file" : " files") + " changed");
			}
			final String committed = time(build.getProperty("commitTime"), zone);
			if (committed != null)
			{
				b.append(", committed ").append(committed);
			}
			final String built = time(build.getProperty("buildTime"), zone);
			if (built != null)
			{
				b.append(", built ").append(built);
			}
			return b.append(')').toString();
		}
		if (hubVersion != null && !hubVersion.trim().isEmpty())
		{
			return "release " + hubVersion.trim();
		}
		if (implVersion != null && !implVersion.trim().isEmpty())
		{
			return "version " + implVersion.trim();
		}
		return build == null ? "build unknown (no build info)" : "build unknown (build info unreadable)";
	}

	private static String value(Properties p, String key, Pattern shape)
	{
		final String v = p.getProperty(key);
		return v != null && shape.matcher(v.trim()).matches() ? v.trim() : null;
	}

	// An ISO-8601 time with its offset, shown in the player's zone; null when missing or malformed.
	private static String time(String iso, ZoneId zone)
	{
		if (iso == null)
		{
			return null;
		}
		try
		{
			return OffsetDateTime.parse(iso.trim()).atZoneSameInstant(zone).format(SHOWN);
		}
		catch (DateTimeParseException e)
		{
			return null;
		}
	}
}
