package com.pvmlogs;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import com.google.gson.Gson;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import net.runelite.client.util.Filepath;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

/**
 * T-217: history saved as a stream. Eric's history ({@code history.json}, 2026-11-11, read in place): 2,500 fights, 6.7 MB,
 * a fight's JSON 2.5 KB at the median, most of it its drain record and its context. The old save built it all as one
 * string, then its bytes, at every kill.
 */
public class HistoryStreamTest
{
	private static final Gson GSON = new Gson();

	private Path tmp;
	private Filepath dir;

	@Before
	public void setUp() throws IOException
	{
		tmp = Files.createTempDirectory("luck-vs-skill");
		// A test-only way in: the plugin gets its Filepath from RuneLite.
		dir = Filepath.Unchecked.getRooted(tmp);
	}

	/** Fights shaped like Eric's: attacks resolved, a context with its gear and windows, a drain record with its events. */
	static List<Fight> fights(int n)
	{
		final List<Fight> list = new ArrayList<>();
		for (int i = 0; i < n; i++)
		{
			final Fight f = new Fight(i % 7 == 0 ? "Tumeken's Warden" : i % 5 == 0 ? "Phosani's Nightmare" : "Alchemical Hydra",
				8615, i, 1100, i * 120_000L, null, 0);
			for (int a = 0; a < 25; a++)
			{
				f.recordAttackMade(a % 9 == 0, 1.0 + a / 400.0, a % 4 == 0);
				f.recordAttackResolved(a % 6 != 0, a % 13 != 0, 10.91 + a / 7.0, 61.1 + a / 3.0);
				f.recordTickSpent(true);
			}
			final FightContext c = f.context();
			c.build = "0a76fa9 (master, clean)";
			c.rules = "weirdgloop/osrs-dps-calc@c446f2e (2026-10-26)";
			c.monsters = "monsters.json 2026-10-30";
			c.weapons = new LinkedHashMap<>();
			c.specWeapons = Arrays.asList("Dragon hunter crossbow (t)", "Zaryte crossbow", "Toxic blowpipe");
			c.ammoOrSpell = Arrays.asList("Ruby dragon bolts (e)", "Dragon darts");
			c.armour = new int[]{50, 90, 120, 70, 180, 0};
			c.baseLevels = new int[]{99, 99, 95, 99, 94, 99, 88};
			c.idealDpsFullHp = 10.96 + i / 1000.0;
			c.kc = 72 + i;
			c.gameDurationTicks = 212 + i % 40;
			c.worldTypes = Arrays.asList("MEMBERS");
			final DrainRecord d = new DrainRecord();
			d.defenceBase = 100;
			d.defenceStart = 100;
			d.defenceEnd = 70 - i % 30;
			d.specs.put("DRAGON_WARHAMMER", new int[]{2, 1});
			for (int e = 0; e < 4; e++)
			{
				final DrainEvent ev = new DrainEvent();
				ev.kind = e % 3 == 0 ? DrainEvent.KIND_REGEN : DrainEvent.KIND_DRAIN;
				ev.weapon = e % 3 == 0 ? null : "DRAGON_WARHAMMER";
				d.events.add(ev);
			}
			f.recordDrain(d);
			f.end(true, i * 120_000L + 109_200L);
			list.add(f);
		}
		return list;
	}

	private byte[] file(String name) throws IOException
	{
		return Files.readAllBytes(tmp.resolve(name));
	}

	@Test
	public void theStreamWritesTheSameBytesAsBefore() throws IOException
	{
		final List<Fight> fights = fights(300);
		final byte[] before = GSON.toJson(fights).getBytes(StandardCharsets.UTF_8);
		final long written = new HistoryStore(dir, GSON).save(fights);
		assertArrayEquals(before, file(HistoryStore.FILE));
		assertEquals(before.length, written);
		assertFalse(Files.exists(tmp.resolve(HistoryStore.TEMP)));
		// The plugin's own settings too (a client Gson customised with newBuilder()): NaN written, a name outside ASCII.
		final Gson special = GSON.newBuilder().serializeSpecialFloatingPointValues().create();
		final List<Fight> odd = fights(3);
		odd.add(new Fight("Phosani's Nightmare é—", 8615, 9, 2400, 0L, null, 0));
		odd.get(3).recordAttackResolved(true, true, Double.NaN, Double.NaN);
		new HistoryStore(dir, special).save(odd);
		assertArrayEquals(special.toJson(odd).getBytes(StandardCharsets.UTF_8), file(HistoryStore.FILE));
		// And it loads back.
		assertEquals(4, new HistoryStore(dir, special).load(0L).fights.length);
	}

	@Test
	public void aStopMidWriteLeavesHistoryWhole() throws IOException
	{
		final HistoryStore store = new HistoryStore(dir, GSON);
		store.save(fights(20));
		final byte[] before = file(HistoryStore.FILE);
		// The stream stops halfway (the disk full, the client closed): history.json is the old file, whole.
		try
		{
			HistoryStore.writeWhole(dir.joinSegment(HistoryStore.TEMP), dir.joinSegment(HistoryStore.FILE), out ->
			{
				HistoryStore.json(GSON, fights(10), new java.io.FilterOutputStream(out)
				{
					private int left = 5_000;

					@Override
					public void write(byte[] b, int off, int len) throws IOException
					{
						if ((left -= len) < 0)
						{
							throw new IOException("stopped");
						}
						out.write(b, off, len);
					}
				});
			});
			fail("the write should have stopped");
		}
		catch (IOException e)
		{
			assertEquals("stopped", e.getMessage());
		}
		assertArrayEquals(before, file(HistoryStore.FILE));
		assertEquals(20, new HistoryStore(dir, GSON).load(0L).fights.length);
	}

	/**
	 * Eric's size: 2,500 fights. The old save against the stream, its time and what it allocates on the saving thread
	 * ({@code com.sun.management.ThreadMXBean}, test only: the plugin never reads it). Figures printed for the PR.
	 */
	@Test
	public void theSaveOfTwoAndAHalfThousandFightsAllocatesFarLess() throws IOException
	{
		Assume.assumeTrue(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean);
		final com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
		Assume.assumeTrue(threads.isThreadAllocatedMemorySupported());
		threads.setThreadAllocatedMemoryEnabled(true);
		final long me = Thread.currentThread().getId();
		final List<Fight> fights = fights(2500);
		final HistoryStore store = new HistoryStore(dir, GSON);
		// Warm both paths.
		for (int i = 0; i < 3; i++)
		{
			HistoryStore.writeWhole(dir.joinSegment(HistoryStore.TEMP), dir.joinSegment(HistoryStore.FILE),
				GSON.toJson(fights).getBytes(StandardCharsets.UTF_8));
			store.save(fights);
		}
		long oldBytes = Long.MAX_VALUE;
		long newBytes = Long.MAX_VALUE;
		long oldNanos = Long.MAX_VALUE;
		long newNanos = Long.MAX_VALUE;
		long size = 0;
		for (int i = 0; i < 5; i++)
		{
			long a = threads.getThreadAllocatedBytes(me);
			long t = System.nanoTime();
			// The old save: the whole file as one string, then its bytes.
			size = HistoryStore.writeWhole(dir.joinSegment(HistoryStore.TEMP), dir.joinSegment(HistoryStore.FILE),
				GSON.toJson(fights).getBytes(StandardCharsets.UTF_8));
			oldNanos = Math.min(oldNanos, System.nanoTime() - t);
			oldBytes = Math.min(oldBytes, threads.getThreadAllocatedBytes(me) - a);
			a = threads.getThreadAllocatedBytes(me);
			t = System.nanoTime();
			store.save(fights);
			newNanos = Math.min(newNanos, System.nanoTime() - t);
			newBytes = Math.min(newBytes, threads.getThreadAllocatedBytes(me) - a);
		}
		System.out.printf("T-217 history save, %d fights, %,d bytes: before %.1f ms, %,d bytes allocated; after %.1f ms, "
			+ "%,d bytes allocated%n", fights.size(), size, oldNanos / 1e6, oldBytes, newNanos / 1e6, newBytes);
		assertTrue("a file of Eric's size: " + size, size > 5_000_000 && size < 9_000_000);
		// The old way allocated the file several times over: its chars, again as the builder grew, and its bytes, each one
		// array the size of the file. The stream leaves only Gson's own small allocations (a field's boxed value, a number
		// as text), which both ways make: on this machine 56 MB before, 21 MB after.
		assertTrue(oldBytes + " allocated before", oldBytes > 5 * size);
		assertTrue(newBytes + " allocated after against " + oldBytes + " before", newBytes < oldBytes - 3 * size);
	}
}
