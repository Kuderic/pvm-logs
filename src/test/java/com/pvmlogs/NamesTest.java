package com.pvmlogs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.plugins.PluginDescriptor;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The plugin's names, since T-060 its own Plugin Hub entry: Luck vs Skill. Once released, the settings group holds
 * users' saved settings and the internal name names their data folder, so changing either resets settings or hides
 * fight history. A change here has to be a deliberate decision, not a side effect.
 * <p>
 * It began as a fork of Arodab's PvM Performance Tracker, and must not touch his plugin's settings or files: no
 * {@code legacyDataDirectory} (RuneLite would move his users' folder into this plugin's), and none of his names.
 */
public class NamesTest
{
	private static final String DISPLAY_NAME = "Luck vs Skill";
	private static final String INTERNAL_NAME = "luck-vs-skill";
	private static final String GROUP = "luckvsskill";

	/** Arodab's settings group and folder, and this fork's working name for a folder before T-004. */
	private static final String[] NOT_OURS = {"pvmperformance", "pvm-performance-tracker", "pvm-logs"};

	@Test
	public void displayName() throws IOException
	{
		assertEquals(DISPLAY_NAME, PvmPerformanceConfig.DISPLAY_NAME);
		assertEquals(DISPLAY_NAME, descriptor().name());

		final Properties hub = new Properties();
		try (InputStream in = Files.newInputStream(Paths.get("runelite-plugin.properties")))
		{
			hub.load(in);
		}
		assertEquals(DISPLAY_NAME, hub.getProperty("displayName"));

		final List<String> readme = Files.readAllLines(Paths.get("README.md"), StandardCharsets.UTF_8);
		assertEquals("# " + DISPLAY_NAME, readme.get(0));
	}

	@Test
	public void internalNameNamesTheDataFolder()
	{
		assertEquals(INTERNAL_NAME, PvmPerformanceConfig.INTERNAL_NAME);
		assertEquals(INTERNAL_NAME, descriptor().internalName());
	}

	@Test
	public void settingsGroup()
	{
		assertEquals(GROUP, PvmPerformanceConfig.GROUP);
		assertEquals(GROUP, PvmPerformanceConfig.class.getAnnotation(ConfigGroup.class).value());
	}

	/** With one, RuneLite would move {@code ~/.runelite/<it>/} into this plugin's folder the first time it starts. */
	@Test
	public void noLegacyDataDirectory()
	{
		assertEquals("", descriptor().legacyDataDirectory());
	}

	/**
	 * No string in the plugin's code names Arodab's settings group or folder, so nothing reads, moves or shares his
	 * plugin's data; and the plugin's own folder name is only {@link PvmPerformanceConfig#INTERNAL_NAME}, so a typed-out
	 * copy can't split a user's files across two folders.
	 */
	@Test
	public void noSourceSpellsOutAFolderOrGroup() throws IOException
	{
		final List<String> offenders = new ArrayList<>();
		for (Path file : mainSources())
		{
			final boolean config = file.getFileName().toString().equals("PvmPerformanceConfig.java");
			final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			for (int i = 0; i < lines.size(); i++)
			{
				final String line = lines.get(i);
				boolean bad = !config && (line.contains("\"" + INTERNAL_NAME + "\"") || line.contains("\"" + GROUP + "\""));
				for (String name : NOT_OURS)
				{
					bad |= line.contains("\"" + name + "\"");
				}
				if (bad)
				{
					offenders.add(file + ":" + (i + 1) + ": " + line.trim());
				}
			}
		}
		assertTrue("Use PvmPerformanceConfig's names, and none of Arodab's:\n" + String.join("\n", offenders),
			offenders.isEmpty());
	}

	/**
	 * What a player reads says Luck vs Skill: no text left over from the old name, nor from the working name "PvM Logs"
	 * that client.log lines and saved debug logs carried until T-067.
	 */
	@Test
	public void noTextShowsTheOldName() throws IOException
	{
		final List<String> offenders = new ArrayList<>();
		for (Path file : mainSources())
		{
			final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			for (int i = 0; i < lines.size(); i++)
			{
				if (lines.get(i).matches(".*\"[^\"]*(PvM Performance|PvM Logs)[^\"]*\".*"))
				{
					offenders.add(file + ":" + (i + 1) + ": " + lines.get(i).trim());
				}
			}
		}
		assertTrue("Use PvmPerformanceConfig.DISPLAY_NAME:\n" + String.join("\n", offenders), offenders.isEmpty());
	}

	/** client.log lines start with the display name, so a player searching their log finds them under it. */
	@Test
	public void clientLogLinesStartWithTheName() throws IOException
	{
		final Pattern logCall = Pattern.compile("\\blog\\.(?:warn|info|debug|error)\\(\"([^\"]*)\"");
		final List<String> offenders = new ArrayList<>();
		int named = 0;
		for (Path file : mainSources())
		{
			final List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			for (int i = 0; i < lines.size(); i++)
			{
				final Matcher m = logCall.matcher(lines.get(i));
				if (m.find() && m.group(1).startsWith("PvM"))
				{
					offenders.add(file + ":" + (i + 1) + ": " + lines.get(i).trim());
				}
				else if (m.find(0) && m.group(1).startsWith(DISPLAY_NAME + ": "))
				{
					named++;
				}
			}
		}
		assertTrue(String.join("\n", offenders), offenders.isEmpty());
		assertTrue("expected the client.log lines to carry the name", named > 10);
	}

	/**
	 * A saved debug log opens with the name. Logs saved before T-067 open with "=== PvM Logs, the last" instead, the
	 * replay fixtures among them; the replay readers take only {@code [tick]} lines, so both headers are skipped alike.
	 */
	@Test
	public void savedDebugLogHeader() throws IOException
	{
		assertEquals("=== Luck vs Skill, the last ", DebugLog.headerStart());

		final Pattern replayLine = Pattern.compile("^\\[(\\d+)\\] (.*)$");
		final String oldHeader = "=== PvM Logs, the last 120 lines before this was saved. Plugin dev ===";
		final String newHeader = DebugLog.headerStart() + "120 lines before this was saved. Plugin dev ===";
		assertTrue(!replayLine.matcher(oldHeader).matches() && !replayLine.matcher(newHeader).matches());

		int fixtures = 0;
		try (Stream<Path> files = Files.walk(Paths.get("src/test/resources/com/pvmlogs/replay")))
		{
			for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".log"))::iterator)
			{
				final String first = Files.readAllLines(file, StandardCharsets.UTF_8).get(0);
				if (first.startsWith("=== "))
				{
					fixtures++;
					assertTrue(file + ": " + first, first.startsWith("=== PvM Logs, the last ")
						|| first.startsWith(DebugLog.headerStart()));
				}
			}
		}
		assertTrue("expected saved-log fixtures with a header", fixtures > 0);
	}

	/** The monster-data request names the plugin, and nothing about the player. */
	@Test
	public void userAgentNamesThePlugin()
	{
		assertEquals("RuneLite plugin " + INTERNAL_NAME, MonsterStatsProvider.USER_AGENT);
	}

	private static PluginDescriptor descriptor()
	{
		return PvmPerformancePlugin.class.getAnnotation(PluginDescriptor.class);
	}

	private static List<Path> mainSources() throws IOException
	{
		final List<Path> out = new ArrayList<>();
		try (Stream<Path> files = Files.walk(Paths.get("src/main/java")))
		{
			files.filter(p -> p.toString().endsWith(".java")).sorted().forEach(out::add);
		}
		assertTrue("no sources found; run from the repo root", !out.isEmpty());
		return out;
	}
}
