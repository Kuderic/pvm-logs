package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * T-055: Eric's two Abyssal Sire kills of 2026-10-28 (13:05 and 14:34), on a
 * build where loot from anything in the Sire's group was its kill. A scion's
 * drop closed the first kill's room in phase 2; in the second, a scion's drop
 * did the same, a spawn's ended the Sire's own fight and closed the next room,
 * and the last room held only phase 3 - the "20 seconds" Eric saw.
 *
 * <p>The fixture holds the logs' fight START and END, AoE and LOOT lines only,
 * copied with Eric's OK. Replayed through the plugin's own rules: its room rule
 * ({@link PvmPerformancePlugin#opensRoom}), the loot rules
 * ({@link PvmPerformancePlugin#lootEndsFight}, {@link Encounter#closesOnLootFrom})
 * and the real group tables. The plugin itself needs a client.
 *
 * <p>The build that wrote the logs ended the Sire's fight on the spawn's loot
 * ("loot received"); the replay leaves that to the loot rule, and a fight still
 * open when the player attacks something else ends there, as the plugin's switch
 * does.
 */
public class SireAddLootReplayTest
{
	private static final String LOG = "replay/sire-add-loot-20261028.log";
	private static final int SIRE_LAST_FORM = 5908;

	private static final Pattern LINE = Pattern.compile("^\\[(\\d+)] (.*)$");
	private static final Pattern START = Pattern.compile(
		"^fight START (.+) \\(npc (\\d+), index (\\d+), maxHp (-?\\d+)\\)");
	private static final Pattern END = Pattern.compile(
		"^fight END (.+) \\(npc (\\d+)\\) (.+?): attempts=(\\d+) made=\\d+ damage=(\\d+)");
	private static final Pattern LOOT = Pattern.compile("^LOOT from (.+?) \\(npc (\\d+), index (\\d+)\\)");

	/** A room as the replay saw it, and the ticks it opened and closed on. */
	static final class Room
	{
		final Encounter encounter;
		final int startTick;
		int closedTick = -1;
		int closedBy = -1;

		Room(Encounter encounter, int startTick)
		{
			this.encounter = encounter;
			this.startTick = startTick;
		}
	}

	private static List<String[]> lines() throws Exception
	{
		final List<String[]> out = new ArrayList<>();
		try (BufferedReader in = new BufferedReader(new InputStreamReader(
			SireAddLootReplayTest.class.getResourceAsStream(LOG), StandardCharsets.UTF_8)))
		{
			String raw;
			while ((raw = in.readLine()) != null)
			{
				final Matcher m = LINE.matcher(raw);
				if (m.matches())
				{
					out.add(new String[]{m.group(1), m.group(2)});
				}
			}
		}
		return out;
	}

	private static long millis(int tick)
	{
		return tick * 600L;
	}

	/**
	 * The rooms the fixture makes. {@code oldRule}: loot from anything in the
	 * group ends the fight and closes the room, as before T-055.
	 */
	static List<Room> rooms(boolean oldRule) throws Exception
	{
		final List<Room> rooms = new ArrayList<>();
		Room room = null;
		Fight open = null;
		for (String[] line : lines())
		{
			final int tick = Integer.parseInt(line[0]);
			final String text = line[1];
			final Matcher start = START.matcher(text);
			if (start.find())
			{
				if (open != null && !open.isEnded())
				{
					open.end(false, millis(tick));
				}
				open = new Fight(start.group(1), Integer.parseInt(start.group(2)), Integer.parseInt(start.group(3)),
					Integer.parseInt(start.group(4)), millis(tick), null, 0);
				open.noteStartTick(tick);
				if (PvmPerformancePlugin.opensRoom(room == null ? null : room.encounter, open))
				{
					if (room != null && !room.encounter.isEnded())
					{
						room.encounter.end(millis(tick));
					}
					room = new Room(new Encounter(open.encounterName(), null, millis(tick)), tick);
					rooms.add(room);
				}
				room.encounter.add(open);
				continue;
			}
			final Matcher end = END.matcher(text);
			if (end.find())
			{
				assertNotNull(open);
				assertEquals(text, open.getTargetId(), Integer.parseInt(end.group(2)));
				for (int i = Integer.parseInt(end.group(4)); i > 0; i--)
				{
					open.recordAttackMade(false);
				}
				open.recordDamageDealt(Integer.parseInt(end.group(5)), millis(tick), false);
				// The loot ended this one on the old build: the loot line below decides it here.
				if (!"loot received".equals(end.group(3)))
				{
					open.end("target died".equals(end.group(3)), millis(tick));
				}
				continue;
			}
			final Matcher loot = LOOT.matcher(text);
			if (loot.find())
			{
				final int id = Integer.parseInt(loot.group(2));
				final int index = Integer.parseInt(loot.group(3));
				final boolean endsFight = oldRule
					? open != null && !open.isEnded() && (open.getTargetIndex() == index
						|| EncounterGroup.sameGroup(id, open.getTargetId()))
					: PvmPerformancePlugin.lootEndsFight(open, id, index);
				if (endsFight)
				{
					open.end(true, millis(tick));
				}
				final boolean closes = oldRule
					? !room.encounter.isEnded() && room.encounter.holds(id)
					: room.encounter.closesOnLootFrom(id);
				if (closes)
				{
					room.encounter.end(millis(tick));
					room.closedTick = tick;
					room.closedBy = id;
				}
			}
		}
		return rooms;
	}

	@Test
	public void eachKillIsOneRoomFromPhaseOneToTheDeath() throws Exception
	{
		final List<Room> rooms = rooms(false);
		assertEquals(2, rooms.size());
		final Room first = rooms.get(0);
		assertEquals(8196, first.startTick);
		assertEquals(8373, first.closedTick);
		final Room second = rooms.get(1);
		assertEquals(186, second.startTick);
		assertEquals(399, second.closedTick);
		for (Room r : rooms)
		{
			assertEquals("Abyssal Sire", r.encounter.getName());
			assertTrue(r.encounter.isKilled());
			// Closed by the Sire's own drop, from its last form.
			assertEquals(SIRE_LAST_FORM, r.closedBy);
			// Phase one's Sire opens it and the Sire's death is its last fight.
			final List<Fight> fights = r.encounter.getFights();
			assertEquals(5886, fights.get(0).getTargetId());
			assertTrue(fights.get(fights.size() - 1).isTargetDied());
		}
		// The time: 177 and 213 ticks, where the second kill read as its last 34 combat ticks of phase 3.
		assertEquals(millis(8373 - 8196), first.encounter.durationMillis());
		assertEquals(millis(399 - 186), second.encounter.durationMillis());
		assertEquals(7, first.encounter.getFights().size());
		assertEquals(10, second.encounter.getFights().size());
	}

	/** The spawn's drop at 353 no longer ends the Sire's fight: the player's switch to the next spawn does. */
	@Test
	public void aSpawnsDropLeavesTheSiresFightOpen() throws Exception
	{
		final List<Fight> fights = rooms(false).get(1).encounter.getFights();
		final Fight phaseTwo = fights.get(7);
		assertEquals(5890, phaseTwo.getTargetId());
		assertFalse(phaseTwo.isTargetDied());
		assertEquals(14, phaseTwo.getAttempts());
		assertEquals(5916, fights.get(8).getTargetId());
		assertTrue(fights.get(8).isTargetDied());
		assertEquals(5891, fights.get(9).getTargetId());
	}

	/** Under the old rule the same two kills made six rooms: two and four (T-055 item 5). */
	@Test
	public void theOldRuleSplitBothKills() throws Exception
	{
		final List<Room> rooms = rooms(true);
		assertEquals(6, rooms.size());
		assertEquals(5918, rooms.get(0).closedBy);
		assertEquals(SIRE_LAST_FORM, rooms.get(1).closedBy);
		assertEquals(5918, rooms.get(2).closedBy);
		assertEquals(5916, rooms.get(3).closedBy);
		assertEquals(5916, rooms.get(4).closedBy);
		assertEquals(SIRE_LAST_FORM, rooms.get(5).closedBy);
		// The last room of the second kill: phase 3 alone, 362 to 399.
		assertEquals(362, rooms.get(5).startTick);
		assertEquals(1, rooms.get(5).encounter.getFights().size());
	}
}
