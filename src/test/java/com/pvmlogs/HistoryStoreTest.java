package com.pvmlogs;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.util.Filepath;
import org.junit.Before;
import org.junit.Test;

/**
 * T-178: Eric's history went from 2,500 fights to 0 overnight on 2026-11-05. His machine crashed during a save, the empty
 * file loaded as 0 fights, and the next fight's save wrote over it. Here: a whole file through any stop, and an
 * unreadable one moved aside, never written over, with the last good copy loaded.
 */
public class HistoryStoreTest
{
	private static final Gson GSON = new Gson();
	private static final long NOW = 1_762_400_000_000L;

	private Path tmp;
	private Filepath dir;
	private HistoryStore store;

	@Before
	public void setUp() throws IOException
	{
		tmp = Files.createTempDirectory("luck-vs-skill");
		// A test-only way in: the plugin gets its Filepath from RuneLite, never from Filepath.Unchecked.
		dir = Filepath.Unchecked.getRooted(tmp);
		store = new HistoryStore(dir, GSON);
	}

	private static List<Fight> fights(int n, int from)
	{
		final List<Fight> list = new ArrayList<>();
		for (int i = 0; i < n; i++)
		{
			final Fight f = new Fight("Vardorvis", NpcID.VARDORVIS, from + i, 700, (from + i) * 1000L, null, 0);
			f.end(true, (from + i) * 1000L + 500);
			list.add(f);
		}
		return list;
	}

	private byte[] bytes(String name) throws IOException
	{
		return Files.readAllBytes(tmp.resolve(name));
	}

	private void write(String name, byte[] bytes) throws IOException
	{
		Files.write(tmp.resolve(name), bytes);
	}

	private List<String> unreadable() throws IOException
	{
		try (Stream<Path> files = Files.list(tmp))
		{
			return files.map(p -> p.getFileName().toString()).filter(n -> n.startsWith(HistoryStore.UNREADABLE_PREFIX))
				.sorted().collect(Collectors.toList());
		}
	}

	@Test
	public void aSaveIsWholeAndLoadsBack() throws IOException
	{
		store.save(fights(3, 1));
		assertFalse("nothing left beside it", Files.exists(tmp.resolve(HistoryStore.TEMP)));
		final HistoryStore.Loaded loaded = new HistoryStore(dir, GSON).load(NOW);
		assertEquals(3, loaded.fights.length);
		assertEquals(HistoryStore.FILE, loaded.source);
		assertNull(loaded.notice);
		assertTrue(loaded.line, loaded.line.startsWith("HISTORY loaded 3 fights ("));
		assertTrue(loaded.line, loaded.line.endsWith(" bytes) from history.json"));
	}

	@Test
	public void aSaveThatFailsHalfwayLeavesTheOldFileWhole() throws IOException
	{
		store.save(fights(5, 1));
		final byte[] before = bytes(HistoryStore.FILE);
		// The write beside it fails (here: something in the way of the .tmp).
		Files.createDirectory(tmp.resolve(HistoryStore.TEMP));
		try
		{
			store.save(fights(6, 1));
			fail("the save should have failed");
		}
		catch (IOException e)
		{
			// expected
		}
		assertArrayEquals(before, bytes(HistoryStore.FILE));
		Files.delete(tmp.resolve(HistoryStore.TEMP));
		// A stop mid-write of the .tmp (cut short), before its move: history.json is still the old whole file and loads.
		write(HistoryStore.TEMP, "[{\"targetName\":\"Vard".getBytes(StandardCharsets.UTF_8));
		final HistoryStore.Loaded loaded = new HistoryStore(dir, GSON).load(NOW);
		assertEquals(5, loaded.fights.length);
		assertEquals(HistoryStore.FILE, loaded.source);
	}

	@Test
	public void aGoodLoadRefreshesTheLastGoodCopy() throws IOException
	{
		store.save(fights(4, 1));
		assertFalse(Files.exists(tmp.resolve(HistoryStore.LAST_GOOD)));
		new HistoryStore(dir, GSON).load(NOW);
		assertArrayEquals(bytes(HistoryStore.FILE), bytes(HistoryStore.LAST_GOOD));
		// Replaced by a later good load, and only one file.
		store.save(fights(7, 1));
		new HistoryStore(dir, GSON).load(NOW);
		assertEquals(7, GSON.fromJson(new String(bytes(HistoryStore.LAST_GOOD), StandardCharsets.UTF_8), Fight[].class).length);
		assertFalse(Files.exists(tmp.resolve(HistoryStore.LAST_GOOD_TEMP)));
		// A cleared history ("[]") reads, as 0 fights, and leaves the last good copy as it was.
		store.save(new ArrayList<>());
		final HistoryStore.Loaded cleared = new HistoryStore(dir, GSON).load(NOW);
		assertEquals(0, cleared.fights.length);
		assertEquals(HistoryStore.FILE, cleared.source);
		assertNull(cleared.notice);
		assertEquals(7, GSON.fromJson(new String(bytes(HistoryStore.LAST_GOOD), StandardCharsets.UTF_8), Fight[].class).length);
		assertTrue(unreadable().isEmpty());
	}

	private void anUnreadableFileLoadsTheLastGoodCopy(byte[] broken, String why) throws IOException
	{
		store.save(fights(2500, 1));
		new HistoryStore(dir, GSON).load(NOW);
		Files.setLastModifiedTime(tmp.resolve(HistoryStore.LAST_GOOD), FileTime.fromMillis(NOW - 3_600_000L));
		// The crash: history.json left as this.
		write(HistoryStore.FILE, broken);
		final HistoryStore after = new HistoryStore(dir, GSON);
		final HistoryStore.Loaded loaded = after.load(NOW);
		assertEquals(2500, loaded.fights.length);
		assertEquals(HistoryStore.LAST_GOOD, loaded.source);
		assertTrue(loaded.line, loaded.line.contains("didn't read (" + why));
		assertTrue(loaded.line, loaded.line.contains("loaded 2500 fights"));
		assertNotNull(loaded.notice);
		assertTrue(loaded.notice, loaded.notice.contains("couldn't read its history; it loaded the last good copy from "));
		// Moved aside, untouched.
		final List<String> aside = unreadable();
		assertEquals(1, aside.size());
		assertEquals(HistoryStore.UNREADABLE_PREFIX + HistoryStore.stamp(NOW) + ".json", aside.get(0));
		assertArrayEquals(broken, bytes(aside.get(0)));
		// The next fight's save writes the loaded fights and the new one, and leaves the unreadable file as it was.
		final List<Fight> history = new ArrayList<>(java.util.Arrays.asList(loaded.fights));
		history.addAll(fights(1, 9000));
		after.save(history);
		assertEquals(2501, new HistoryStore(dir, GSON).load(NOW).fights.length);
		assertArrayEquals(broken, bytes(aside.get(0)));
	}

	@Test
	public void anEmptyFileLoadsTheLastGoodCopyAndIsMovedAside() throws IOException
	{
		anUnreadableFileLoadsTheLastGoodCopy(new byte[0], "empty");
	}

	@Test
	public void aFileOfNulBytesDoesTheSame() throws IOException
	{
		// What a write the crash cut off can leave: the length kept, the data not.
		anUnreadableFileLoadsTheLastGoodCopy(new byte[4096], "blank");
	}

	@Test
	public void aCutShortFileDoesTheSame() throws IOException
	{
		final String whole = GSON.toJson(fights(3, 1));
		anUnreadableFileLoadsTheLastGoodCopy(whole.substring(0, whole.length() / 2).getBytes(StandardCharsets.UTF_8),
			"unparsable");
	}

	@Test
	public void aWholeTempNewerThanTheLastGoodCopyIsTakenInstead() throws IOException
	{
		store.save(fights(10, 1));
		new HistoryStore(dir, GSON).load(NOW);
		Files.setLastModifiedTime(tmp.resolve(HistoryStore.LAST_GOOD), FileTime.fromMillis(NOW - 7_200_000L));
		// A save written and forced in full, its move not yet made; and history.json lost.
		write(HistoryStore.TEMP, GSON.toJson(fights(12, 1)).getBytes(StandardCharsets.UTF_8));
		Files.setLastModifiedTime(tmp.resolve(HistoryStore.TEMP), FileTime.fromMillis(NOW - 60_000L));
		write(HistoryStore.FILE, new byte[0]);
		final HistoryStore.Loaded loaded = new HistoryStore(dir, GSON).load(NOW);
		assertEquals(12, loaded.fights.length);
		assertEquals(HistoryStore.TEMP, loaded.source);
		// A cut-short .tmp isn't: the last good copy is.
		write(HistoryStore.TEMP, "[{".getBytes(StandardCharsets.UTF_8));
		write(HistoryStore.FILE, new byte[0]);
		final HistoryStore.Loaded second = new HistoryStore(dir, GSON).load(NOW + 1000);
		assertEquals(10, second.fights.length);
		assertEquals(HistoryStore.LAST_GOOD, second.source);
	}

	@Test
	public void nothingReadsSoItStartsEmptyAndTheFileStaysAside() throws IOException
	{
		write(HistoryStore.FILE, "{not json".getBytes(StandardCharsets.UTF_8));
		final HistoryStore after = new HistoryStore(dir, GSON);
		final HistoryStore.Loaded loaded = after.load(NOW);
		assertEquals(0, loaded.fights.length);
		assertNull(loaded.source);
		assertTrue(loaded.line, loaded.line.contains("starts empty"));
		assertNotNull(loaded.notice);
		final List<String> aside = unreadable();
		assertEquals(1, aside.size());
		after.save(fights(1, 1));
		assertArrayEquals("{not json".getBytes(StandardCharsets.UTF_8), bytes(aside.get(0)));
	}

	@Test
	public void aFreshFolderAndAnOldFileLoadAsBefore() throws IOException
	{
		final HistoryStore.Loaded none = store.load(NOW);
		assertEquals(0, none.fights.length);
		assertNull(none.source);
		assertNull(none.notice);
		// A file written the old way (in place, by gson): read as it is.
		write(HistoryStore.FILE, GSON.toJson(fights(40, 1)).getBytes(StandardCharsets.UTF_8));
		final HistoryStore.Loaded old = new HistoryStore(dir, GSON).load(NOW);
		assertEquals(40, old.fights.length);
		assertEquals(HistoryStore.FILE, old.source);
		// Everything it holds, past the budgets (T-093): they trim only when a fight is next added.
		assertEquals(40, old.fights.length);
	}
}
