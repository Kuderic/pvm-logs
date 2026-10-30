package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import org.junit.Test;

/** T-096: the debug log names the build that wrote it. */
public class BuildInfoTest
{
	private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

	private static Properties file(String text)
	{
		return BuildInfo.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1)));
	}

	private static final String CLEAN = "commit=c93ca97\nbranch=master\ndirtyFiles=0\n"
		+ "commitTime=2026-10-29T20:50:43-05:00\nbuildTime=2026-10-30T02:19:06Z\n";

	@Test
	public void withTheFile()
	{
		assertEquals("build c93ca97 (master, clean, committed 2026-10-29 20:50, built 2026-10-29 21:19)",
			BuildInfo.describe(file(CLEAN), null, null, CHICAGO));
		// In the player's own zone.
		assertEquals("build c93ca97 (master, clean, committed 2026-10-30 01:50, built 2026-10-30 02:19)",
			BuildInfo.describe(file(CLEAN), null, null, ZoneId.of("UTC")));
		final String dirty = CLEAN.replace("dirtyFiles=0", "dirtyFiles=3").replace("branch=master", "branch=t-089/runs");
		assertEquals("build c93ca97 (t-089/runs, dirty: 3 files changed, committed 2026-10-29 20:50, built 2026-10-29 21:19)",
			BuildInfo.describe(file(dirty), null, null, CHICAGO));
		assertTrue(BuildInfo.describe(file(CLEAN.replace("dirtyFiles=0", "dirtyFiles=1")), null, null, CHICAGO)
			.contains("dirty: 1 file changed"));
		// A detached checkout, as CI's is.
		assertTrue(BuildInfo.describe(file(CLEAN.replace("branch=master", "branch=HEAD")), null, null, CHICAGO)
			.startsWith("build c93ca97 (detached, clean"));
		// The file beats a hub version: a file is only ever there in our own build.
		assertTrue(BuildInfo.describe(file(CLEAN), "1.0", null, CHICAGO).startsWith("build c93ca97"));
	}

	/** The Plugin Hub's build has no file: the release it lists, or the jar's version, or "unknown". */
	@Test
	public void withoutTheFile()
	{
		assertEquals("release 1.0", BuildInfo.describe(null, "1.0", "0.9", CHICAGO));
		assertEquals("version 0.9", BuildInfo.describe(null, null, "0.9", CHICAGO));
		assertEquals("version 0.9", BuildInfo.describe(null, " ", "0.9", CHICAGO));
		assertEquals("build unknown (no build info)", BuildInfo.describe(null, null, null, CHICAGO));
		assertNull(BuildInfo.read(null));
	}

	/** A value that isn't the shape it should be is not printed; with no commit there is no build to name. */
	@Test
	public void withAMalformedFile()
	{
		assertEquals("build unknown (build info unreadable)", BuildInfo.describe(file("garbage\n"), null, null, CHICAGO));
		assertEquals("release 1.0", BuildInfo.describe(file("commit=not a hash\n"), "1.0", null, CHICAGO));
		assertEquals("build c93ca97 (branch unknown, changes unknown)",
			BuildInfo.describe(file("commit=c93ca97\nbranch=a b\ndirtyFiles=x\ncommitTime=yesterday\nbuildTime=\n"), null,
				null, CHICAGO));
		// Not a properties file at all: a broken escape.
		assertNull(BuildInfo.read(new ByteArrayInputStream("commit=\\u12".getBytes(StandardCharsets.ISO_8859_1))));
		// A stream that fails.
		assertNull(BuildInfo.read(new InputStream()
		{
			@Override
			public int read() throws IOException
			{
				throw new IOException("gone");
			}
		}));
	}

	/**
	 * The file this build wrote (Gradle's {@code buildInfo} runs before the tests) holds the five keys and nothing that
	 * says who or where: no user, home folder, machine name or path.
	 */
	@Test
	public void theGeneratedFileHoldsNothingPrivate() throws IOException
	{
		final InputStream in = BuildInfo.class.getResourceAsStream(BuildInfo.RESOURCE);
		assertNotNull("build.gradle's buildInfo did not write com/pvmlogs/" + BuildInfo.RESOURCE, in);
		final Properties p = BuildInfo.read(in);
		assertNotNull(p);
		assertEquals(new HashSet<>(Arrays.asList("commit", "branch", "dirtyFiles", "commitTime", "buildTime")),
			p.stringPropertyNames());
		assertTrue(p.getProperty("commit"), p.getProperty("commit").matches("[0-9a-f]{7,40}"));
		assertTrue(p.getProperty("dirtyFiles"), p.getProperty("dirtyFiles").matches("[0-9]+"));

		final Set<String> privateWords = new HashSet<>();
		privateWords.add(System.getProperty("user.name"));
		privateWords.add(System.getProperty("user.home"));
		privateWords.add(System.getProperty("user.dir"));
		try
		{
			privateWords.add(InetAddress.getLocalHost().getHostName());
		}
		catch (IOException e)
		{
			// No machine name to check against.
		}
		for (String key : p.stringPropertyNames())
		{
			final String value = p.getProperty(key);
			assertTrue(key + " holds a path: " + value, !value.contains("\\") && !value.contains(":/"));
			for (String word : privateWords)
			{
				if (word != null && word.length() > 2)
				{
					assertTrue(key + " holds " + word, !value.toLowerCase().contains(word.toLowerCase()));
				}
			}
		}
		// And it reads as a build.
		assertTrue(BuildInfo.describe().startsWith("build " + p.getProperty("commit") + " ("));
	}
}
