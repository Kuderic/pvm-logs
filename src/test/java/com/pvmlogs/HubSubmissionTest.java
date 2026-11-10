package com.pvmlogs;

import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * What the Plugin Hub's packager checks when it builds a submission, so a failure shows here rather than on the hub PR.
 *
 * <p>Source: {@code runelite/plugin-hub-tooling}, {@code package/.../packager/Plugin.java} at {@code 034eaff}
 * (2026-10-14), read 2026-10-28 for T-067. With {@code build=standard} the packager replaces {@code build.gradle} and
 * {@code settings.gradle} with its own (the client, Lombok and JetBrains annotations, {@code release 11}) and copies
 * only {@code src/main/java}, {@code src/main/resources} and {@code lombok.config}; the tests are not built there. The
 * jar limit is 10 MiB: T-067's hub-style build of the plugin came to 0.70 MiB. When the packager changes, change this.
 */
public class HubSubmissionTest
{
	/** The keys the packager reads from runelite-plugin.properties; any other fails the submission. */
	private static final Set<String> KNOWN_KEYS = new HashSet<>(Arrays.asList(
		"displayName", "author", "description", "tags", "plugins", "version", "build", "support"));

	private static Properties properties() throws IOException
	{
		final Properties p = new Properties();
		try (InputStream in = Files.newInputStream(Paths.get("runelite-plugin.properties")))
		{
			p.load(in);
		}
		return p;
	}

	@Test
	public void pluginPropertiesHoldOnlyWhatThePackagerReads() throws IOException
	{
		final Properties p = properties();
		for (String key : p.stringPropertyNames())
		{
			assertTrue("unknown key in runelite-plugin.properties: " + key, KNOWN_KEYS.contains(key));
		}
		for (String key : new String[]{"displayName", "author", "description", "plugins"})
		{
			assertTrue(key + " must be set", p.getProperty(key, "").trim().length() > 0);
		}
		// standard: the packager builds src/main alone with its own build file, so nothing in build.gradle may matter.
		assertEquals("standard", p.getProperty("build"));
		// A missing or -SNAPSHOT version shows as the commit hash instead.
		assertTrue(!p.getProperty("version", "").isEmpty() && !p.getProperty("version").endsWith("SNAPSHOT"));
	}

	@Test
	public void eachListedPluginIsARealPluginWithTheHubsInternalName() throws Exception
	{
		final String plugins = properties().getProperty("plugins");
		for (String name : plugins.split("[,:;]"))
		{
			final Class<?> c = Class.forName(name.trim());
			assertTrue(name + " must extend Plugin", Plugin.class.isAssignableFrom(c));
			final PluginDescriptor d = c.getAnnotation(PluginDescriptor.class);
			assertNotNull(name + " must carry @PluginDescriptor", d);
			// The packager fails a descriptor whose internalName isn't the hub file's name.
			assertEquals(PvmPerformanceConfig.INTERNAL_NAME, d.internalName());
		}
		assertTrue("hub file names are lowercase letters, digits and dashes",
			PvmPerformanceConfig.INTERNAL_NAME.matches("^[a-z0-9-]+$"));
	}

	/** The hub listing's icon: at most 256 KiB and 50 × 100 px in area (the README asks for no more than 48 × 72). */
	@Test
	public void iconFitsTheHub() throws IOException
	{
		final Path icon = Paths.get("icon.png");
		assertTrue(Files.size(icon) <= 256 * 1024);
		final BufferedImage image = ImageIO.read(icon.toFile());
		assertNotNull("icon.png must be a readable image", image);
		assertTrue(image.getWidth() <= 48 && image.getHeight() <= 72);
		assertTrue(image.getWidth() * image.getHeight() <= 50 * 100);
	}

	@Test
	public void licenceIsThere()
	{
		assertTrue("the packager rejects a repo without LICENSE", Files.exists(Paths.get("LICENSE")));
	}

	/** No class files in resources, and no {@code runelite_plugin.json}: the packager writes that file itself. */
	@Test
	public void resourcesHoldNothingThePackagerRejects() throws IOException
	{
		final List<String> bad = new ArrayList<>();
		try (Stream<Path> files = Files.walk(Paths.get("src/main/resources")))
		{
			files.filter(Files::isRegularFile)
				.filter(p -> p.toString().endsWith(".class") || p.getFileName().toString().equals("runelite_plugin.json"))
				.forEach(p -> bad.add(p.toString()));
		}
		assertEquals(new ArrayList<String>(), bad);
	}

	/** Classes must be Java 11 (major version 55) or older. */
	@Test
	public void classesTargetJava11() throws IOException
	{
		try (DataInputStream in = new DataInputStream(
			PvmPerformancePlugin.class.getResourceAsStream("PvmPerformancePlugin.class")))
		{
			assertEquals(0xCAFEBABE, in.readInt());
			in.readUnsignedShort();
			assertTrue(in.readUnsignedShort() <= 55);
		}
	}
}
