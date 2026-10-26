package com.pvmlogs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.plugins.PluginDescriptor;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The names a user sees stay Arodab's, so an update on his Plugin Hub entry keeps
 * everyone's settings and fight history. Renaming any of these resets ~134 users'
 * settings or hides their history, so a change here has to be a deliberate decision,
 * not a side effect.
 */
public class OriginalNamesTest
{
	private static final String GROUP = "pvmperformance";
	private static final String DATA_DIR = "pvm-performance-tracker";
	private static final String DISPLAY_NAME = "PvM Performance Tracker";

	@Test
	public void settingsGroupIsTheOriginal()
	{
		assertEquals(GROUP, PvmPerformanceConfig.GROUP);
		assertEquals(GROUP, PvmPerformanceConfig.class.getAnnotation(ConfigGroup.class).value());
	}

	@Test
	public void dataFolderIsTheOriginal()
	{
		assertEquals(DATA_DIR, PvmPerformanceConfig.DATA_DIR);
	}

	@Test
	public void displayNameIsTheOriginal() throws IOException
	{
		assertEquals(DISPLAY_NAME, PvmPerformancePlugin.class.getAnnotation(PluginDescriptor.class).name());

		final Properties hub = new Properties();
		try (InputStream in = Files.newInputStream(Paths.get("runelite-plugin.properties")))
		{
			hub.load(in);
		}
		assertEquals(DISPLAY_NAME, hub.getProperty("displayName"));
	}

	/**
	 * Every caller goes through {@link PvmPerformanceConfig#DATA_DIR}; a folder name
	 * typed out anywhere else would split the user's files across two folders the
	 * next time the name changes.
	 */
	@Test
	public void noOtherSourceSpellsOutADataFolder() throws IOException
	{
		final List<String> offenders = new ArrayList<>();
		try (Stream<Path> files = Files.walk(Paths.get("src/main/java")))
		{
			for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator)
			{
				if (file.getFileName().toString().equals("PvmPerformanceConfig.java"))
				{
					continue;
				}
				final List<String> lines = Files.readAllLines(file);
				for (int i = 0; i < lines.size(); i++)
				{
					final String line = lines.get(i);
					if (line.contains("\"" + DATA_DIR + "\"") || line.contains("\"pvm-logs\""))
					{
						offenders.add(file + ":" + (i + 1) + ": " + line.trim());
					}
				}
			}
		}
		assertTrue("Use PvmPerformanceConfig.DATA_DIR instead of a literal folder name:\n"
			+ String.join("\n", offenders), offenders.isEmpty());
	}
}
