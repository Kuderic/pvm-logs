package com.pvmlogs;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.util.Filepath;
import org.junit.Before;
import org.junit.Test;

/**
 * T-138: the history repair, on synthetic histories with the shapes Eric's has (nothing of his is copied): a Yama kill
 * saved split by a build before T-136, one saved whole by #156's, a lone boss's kill, and Araxxor kills from before
 * T-106.
 */
public class HistoryRepairTest
{
	private static final Gson GSON = new Gson();
	private static final BossRules RULES = BossRules.load(GSON, null);
	private static final long T0 = 1_793_480_000_000L;

	private Filepath dir;

	@Before
	public void setUp() throws IOException
	{
		final Path tmp = Files.createTempDirectory("history-repair");
		tmp.toFile().deleteOnExit();
		dir = Filepath.Unchecked.getRooted(tmp);
	}

	private static JsonObject fight(String name, int id, String group, long start, long end, int damage)
	{
		final JsonObject f = new JsonObject();
		f.addProperty("targetName", name);
		f.addProperty("targetId", id);
		if (group != null)
		{
			f.addProperty("groupName", group);
		}
		f.addProperty("startMillis", start);
		f.addProperty("endMillis", end);
		f.addProperty("damageDealt", damage);
		f.addProperty("attempts", 3);
		f.addProperty("ended", true);
		return f;
	}

	/** Kill 1's shape on build 2d9270f: Yama, the Judge, Yama, a flare's monster room, Yama's death. Oldest first. */
	private static List<JsonObject> splitYamaKill(long t)
	{
		final JsonObject yama1 = fight("Yama", NpcID.YAMA, "Yama", t, t + 60_000, 500);
		final JsonObject judge = fight("Judge of Yama", NpcID.YAMA_JUDGE_OF_YAMA, "Yama", t + 60_000, t + 80_000, 200);
		judge.addProperty("unscored", true);
		final JsonObject yama2 = fight("Yama", NpcID.YAMA, "Yama", t + 80_000, t + 200_000, 1700);
		final JsonObject flare = fight("Void Flare", NpcID.YAMA_VOIDFLARE, "Void Flare", t + 200_000, t + 202_000, 140);
		flare.addProperty("monsterRoom", true);
		flare.addProperty("kills", 1);
		flare.addProperty("targetDied", true);
		flare.addProperty("closedRoom", true);
		final JsonObject yama3 = fight("Yama", NpcID.YAMA, "Yama", t + 202_000, t + 212_000, 150);
		yama3.addProperty("targetDied", true);
		return Arrays.asList(yama1, judge, yama2, flare, yama3);
	}

	/** A Yama kill as #156 saves it: the flare in his group, his death closing the room. */
	private static List<JsonObject> wholeYamaKill(long t)
	{
		final JsonObject yama1 = fight("Yama", NpcID.YAMA, "Yama", t, t + 200_000, 2200);
		final JsonObject flare = fight("Void Flare", NpcID.YAMA_VOIDFLARE, "Yama", t + 200_000, t + 202_000, 71);
		flare.addProperty("targetDied", true);
		final JsonObject yama2 = fight("Yama", NpcID.YAMA, "Yama", t + 202_000, t + 212_000, 229);
		yama2.addProperty("targetDied", true);
		yama2.addProperty("closedRoom", true);
		return Arrays.asList(yama1, flare, yama2);
	}

	private static JsonObject araxxorKill(long t, int others, boolean party)
	{
		final JsonObject f = fight("Araxxor", NpcID.ARAXXOR, "Araxxor", t, t + 100_000, 1000);
		f.addProperty("othersDamage", others);
		f.addProperty("inParty", party);
		f.addProperty("targetDied", true);
		f.addProperty("closedRoom", true);
		return f;
	}

	/** The file is newest first: each list is given oldest first, as the fights happened. */
	private static JsonArray history(List<List<JsonObject>> oldestFirst)
	{
		final List<JsonObject> all = oldestFirst.stream().flatMap(List::stream).collect(Collectors.toList());
		final JsonArray a = new JsonArray();
		for (int i = all.size() - 1; i >= 0; i--)
		{
			a.add(all.get(i));
		}
		return a;
	}

	private void write(JsonArray history) throws IOException
	{
		dir.joinSegment(HistoryRepair.FILE).write(GSON.toJson(history).getBytes(StandardCharsets.UTF_8));
	}

	private byte[] read(String name) throws IOException
	{
		try (InputStream in = dir.joinSegment(name).openInputStream())
		{
			return in.readAllBytes();
		}
	}

	private List<String> backups() throws IOException
	{
		try (java.util.stream.Stream<Filepath> files = dir.walk(1))
		{
			return files.map(Filepath::getFileName).filter(n -> n.startsWith(HistoryRepair.FILE + ".bak-"))
				.collect(Collectors.toList());
		}
	}

	private int run(boolean apply, boolean araxxor, boolean runeLite, ByteArrayOutputStream out) throws IOException
	{
		return HistoryRepair.run(dir, apply, araxxor, runeLite, new PrintStream(out, true, "UTF-8"));
	}

	private static JsonObject lone(long t)
	{
		final JsonObject f = fight("Vorkath", 8061, null, t, t + 90_000, 750);
		f.addProperty("targetDied", true);
		return f;
	}

	@Test
	public void theDryRunListsTheSplitKillAndChangesNothing() throws IOException
	{
		final JsonArray h = history(Arrays.asList(splitYamaKill(T0), wholeYamaKill(T0 + 400_000)));
		write(h);
		final byte[] before = read(HistoryRepair.FILE);
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		assertEquals(0, run(false, false, false, out));
		final String said = out.toString("UTF-8");
		assertTrue(said, said.contains("Close RuneLite first"));
		assertTrue(said, said.contains("Yama kills saved before T-136 to stitch: 1"));
		assertTrue(said, said.contains("3 Yama, 1 Judge and 1 Void Flare fights (1 flare room kill dropped) -> one kill of 353 "
			+ "ticks (212.0 s), 2350 damage on Yama, 2690 in the room"));
		assertTrue(said, said.contains("Dry run: nothing was changed"));
		assertArrayEquals(before, read(HistoryRepair.FILE));
		assertTrue(backups().isEmpty());
	}

	@Test
	public void applyStitchesItIntoOneKillAfterABackup() throws IOException
	{
		final JsonArray h = history(Arrays.asList(Arrays.asList(lone(T0 - 500_000)), splitYamaKill(T0),
			wholeYamaKill(T0 + 400_000)));
		write(h);
		final byte[] before = read(HistoryRepair.FILE);
		final Fight[] old = GSON.fromJson(new String(before, StandardCharsets.UTF_8), Fight[].class);
		final List<Encounter> oldRooms = SidePanelHistory.allRooms(Arrays.asList(old));
		// Before: with no fight closing its room, the split kill's Yama fights ran on into the next Yama room, #156's
		// kill, as one room; the flare was a run of its own, a kill.
		assertEquals(1, oldRooms.stream().filter(r -> "Yama".equals(r.getName())).count());
		assertEquals(1, oldRooms.stream().filter(r -> "Void Flare".equals(r.getName())).filter(SidePanelHistory::isKill)
			.count());
		final long oldKills = oldRooms.stream().filter(SidePanelHistory::isKill).count();
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		assertEquals(out.toString("UTF-8"), 0, run(true, false, false, out));
		// The backup first, holding the file as it was.
		final List<String> backups = backups();
		assertEquals(1, backups.size());
		assertArrayEquals(before, read(backups.get(0)));
		// The result loads with the plugin's own code, and History reads the split kill as one whole kill.
		final Fight[] fights = GSON.fromJson(new String(read(HistoryRepair.FILE), StandardCharsets.UTF_8), Fight[].class);
		assertEquals(h.size(), fights.length);
		final List<Encounter> rooms = SidePanelHistory.allRooms(Arrays.asList(fights));
		final List<Encounter> yama = rooms.stream().filter(r -> "Yama".equals(r.getName())).collect(Collectors.toList());
		assertEquals(2, yama.size());
		assertTrue(yama.stream().allMatch(SidePanelHistory::isKill));
		assertTrue(yama.stream().anyMatch(r -> r.getFights().size() == 5));
		// No "Void Flare" monster room left, and its run kill is gone.
		assertTrue(rooms.stream().noneMatch(r -> "Void Flare".equals(r.getName())));
		assertTrue(Arrays.stream(fights).filter(f -> f.getTargetId() == NpcID.YAMA_VOIDFLARE)
			.allMatch(f -> f.getKills() == 0 && !f.isMonsterRoom()));
		// The flare's run kill gone, a Yama kill in its place.
		assertEquals(oldKills, rooms.stream().filter(SidePanelHistory::isKill).count());
		assertTrue(out.toString("UTF-8").contains("Written and read back: 1 Yama kills whole"));
	}

	@Test
	public void aNormalKillAndAWholeYamaKillAreLeftAlone()
	{
		final JsonArray h = history(Arrays.asList(Arrays.asList(lone(T0 - 500_000)), wholeYamaKill(T0),
			splitYamaKill(T0 + 400_000)));
		final HistoryRepair.Plan plan = HistoryRepair.plan(h, RULES, true);
		assertEquals(1, plan.stitches.size());
		final JsonArray after = HistoryRepair.apply(h, plan);
		// Only the split kill's five fights changed: the newest five in the file.
		for (int i = 5; i < h.size(); i++)
		{
			assertEquals("fight " + i, h.get(i), after.get(i));
		}
		assertFalse(h.get(0).equals(after.get(0)));
		assertNull(HistoryRepair.verify(GSON.toJson(after), h, plan));
		// Nothing to do on a history with no split kill.
		assertTrue(HistoryRepair.plan(history(Arrays.asList(wholeYamaKill(T0))), RULES, true).isEmpty());
	}

	@Test
	public void aKillNoFlareSplitButNothingClosedIsClosedToo() throws IOException
	{
		// Eric's 22:00 kill: Yama, the Judge, Yama, the Judge, Yama's death, no flare attacked. Not split, but before T-136
		// nothing closed Yama's room, so History ran it on into the next kill's room.
		final List<JsonObject> unsplit = Arrays.asList(fight("Yama", NpcID.YAMA, "Yama", T0, T0 + 60_000, 900),
			fight("Judge of Yama", NpcID.YAMA_JUDGE_OF_YAMA, "Yama", T0 + 60_000, T0 + 80_000, 200),
			fight("Yama", NpcID.YAMA, "Yama", T0 + 80_000, T0 + 210_000, 1600));
		unsplit.get(2).addProperty("targetDied", true);
		final JsonArray h = history(Arrays.asList(unsplit, splitYamaKill(T0 + 260_000)));
		final HistoryRepair.Plan plan = HistoryRepair.plan(h, RULES, false);
		assertEquals(2, plan.stitches.size());
		assertFalse(plan.stitches.get(0).split);
		assertTrue(plan.stitches.get(1).split);
		write(h);
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		assertEquals(out.toString("UTF-8"), 0, run(true, false, false, out));
		assertTrue(out.toString("UTF-8").contains("not split, but no fight closed it"));
		final Fight[] fights = GSON.fromJson(new String(read(HistoryRepair.FILE), StandardCharsets.UTF_8), Fight[].class);
		final List<Encounter> yama = SidePanelHistory.allRooms(Arrays.asList(fights)).stream()
			.filter(r -> "Yama".equals(r.getName())).collect(Collectors.toList());
		assertEquals(2, yama.size());
		assertTrue(yama.stream().allMatch(SidePanelHistory::isKill));
		assertEquals(Arrays.asList(5, 3), yama.stream().map(r -> r.getFights().size()).collect(Collectors.toList()));
	}

	@Test
	public void aPieceCutOffByADeathOrAGapIsNotStitched()
	{
		final List<JsonObject> died = splitYamaKill(T0);
		died.get(2).addProperty("closedRoom", true);
		died.get(2).addProperty("roomEnd", "died");
		// The attempt that ended on the death is left out; what follows it, a flare and Yama's death, is the next attempt.
		final JsonArray h = history(Arrays.asList(died));
		final HistoryRepair.Plan plan = HistoryRepair.plan(h, RULES, false);
		assertEquals(1, plan.stitches.size());
		assertEquals(Arrays.asList(1, 0), plan.stitches.get(0).indices);
		final List<JsonObject> gap = splitYamaKill(T0);
		gap.get(4).addProperty("startMillis", T0 + 202_000 + HistoryRepair.MAX_GAP_MILLIS + 1);
		gap.get(4).addProperty("endMillis", T0 + 400_000);
		// The pieces before the gap end on no death and are left alone; Yama's death after it is closed on its own.
		final HistoryRepair.Plan cut = HistoryRepair.plan(history(Arrays.asList(gap)), RULES, false);
		assertEquals(1, cut.stitches.size());
		assertEquals(Arrays.asList(0), cut.stitches.get(0).indices);
		assertFalse(cut.stitches.get(0).split);
	}

	@Test
	public void nothingIsWrittenWhileRuneLiteRuns() throws IOException
	{
		write(history(Arrays.asList(splitYamaKill(T0))));
		final byte[] before = read(HistoryRepair.FILE);
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		assertEquals(1, run(true, false, true, out));
		assertTrue(out.toString("UTF-8").contains("RuneLite is running"));
		assertArrayEquals(before, read(HistoryRepair.FILE));
		assertTrue(backups().isEmpty());
	}

	@Test
	public void araxxorKillsTheRulesExplainBecomeSolo() throws IOException
	{
		// 113 = 80 + 33 (two explosions); 91 = 80 + 11 (an explosion and a cleave); 14 fits nothing (a cleave is 8-12,
		// two are 16-24, an explosion 33-80).
		final JsonObject explained = araxxorKill(T0, 113, false);
		final JsonObject cleave = araxxorKill(T0 + 200_000, 91, false);
		final JsonObject odd = araxxorKill(T0 + 400_000, 14, false);
		final JsonObject party = araxxorKill(T0 + 600_000, 113, true);
		final JsonObject add = fight("Acidic Araxyte", NpcID.ARAXXOR_MINION_VENOM, "Araxxor", T0 + 800_000, T0 + 810_000, 90);
		add.addProperty("othersDamage", 40);
		final JsonObject afterAdd = araxxorKill(T0 + 810_000, 0, false);
		final JsonArray h = history(Arrays.asList(Arrays.asList(explained), Arrays.asList(cleave), Arrays.asList(odd),
			Arrays.asList(party), Arrays.asList(add, afterAdd)));
		// Without the switch, Araxxor isn't looked at.
		assertTrue(HistoryRepair.plan(h, RULES, false).solos.isEmpty());
		final HistoryRepair.Plan plan = HistoryRepair.plan(h, RULES, true);
		assertEquals(2, plan.solos.size());
		assertEquals(3, plan.leftAlone.size());
		write(h);
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		assertEquals(out.toString("UTF-8"), 0, run(true, true, false, out));
		final Fight[] fights = GSON.fromJson(new String(read(HistoryRepair.FILE), StandardCharsets.UTF_8), Fight[].class);
		// Newest first: the add's kill, the party's, the odd one, the cleave's, the explained one.
		assertEquals(0, fights[5].getOthersDamage());
		assertEquals(113, fights[5].getRoomDamage());
		assertEquals(0, fights[4].getOthersDamage());
		assertEquals(91, fights[4].getRoomDamage());
		assertEquals(14, fights[3].getOthersDamage());
		assertEquals(113, fights[2].getOthersDamage());
		assertEquals(40, fights[1].getOthersDamage());
		final String said = out.toString("UTF-8");
		assertTrue(said, said.contains("which his rules' amounts can't make"));
		assertTrue(said, said.contains("fought in a party"));
		assertTrue(said, said.contains("which Araxxor's rules don't cover"));
	}

	@Test
	public void amountsAreMadeOfTheRulesRanges()
	{
		final List<BossRules.RoomDamageRule> araxxor = RULES.forNpc(NpcID.ARAXXOR).roomDamage;
		assertTrue(HistoryRepair.madeOf(8, araxxor));
		assertTrue(HistoryRepair.madeOf(12, araxxor));
		assertFalse(HistoryRepair.madeOf(13, araxxor));
		assertFalse(HistoryRepair.madeOf(15, araxxor));
		assertTrue(HistoryRepair.madeOf(16, araxxor));
		assertTrue(HistoryRepair.madeOf(403, araxxor));
		assertFalse(HistoryRepair.madeOf(7, araxxor));
	}
}
