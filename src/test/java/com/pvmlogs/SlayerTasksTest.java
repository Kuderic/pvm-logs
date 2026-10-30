package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import org.junit.Test;

/**
 * T-090: the slayer table's alternatives, the Wilderness exception, and the game's own check on the table.
 * <p>
 * A debug log of 2026-10-29 has fourteen Deranged archaeologist kills on a Crazy Archaeologists task, the task's count
 * falling 14 to 1 across them, every one logged "on task no". The same log's hits went over the expected max by up to
 * 15% (37 against 33, 36 against 32), which is the imbued slayer helmet's magic damage the calc had left out.
 */
public class SlayerTasksTest
{
	private static final String CRAZY = "Crazy Archaeologists";
	private static final String DERANGED = "Deranged archaeologist";
	private static final Set<String> NONE = Collections.emptySet();

	private static SlayerTasks.Verdict verdict(String task, String npc, int master, String areaName)
	{
		return SlayerTasks.verdict(14, task, npc, 0, master, areaName, NONE);
	}

	// --- The Deranged archaeologist ------------------------------------------------------------------------------------

	/** The log's case: Duradel or Nieve, say, give the task (5 and 6); the master unread is 0. */
	@Test
	public void derangedArchaeologistCountsForACrazyArchaeologistTask()
	{
		for (int master : new int[]{0, 5, 6})
		{
			final SlayerTasks.Verdict v = verdict(CRAZY, DERANGED, master, null);
			assertTrue("master " + master, v.onTask);
			assertTrue(v.known);
		}
		assertTrue(verdict(CRAZY, DERANGED, 0, null).toString().startsWith("yes (task is Crazy Archaeologists, 14 left"));
		assertTrue(verdict(CRAZY, DERANGED, 5, null).toString().contains("master 5"));
		// The boss himself, of course, and in any case.
		assertTrue(verdict(CRAZY, "Crazy archaeologist", 0, null).onTask);
		assertTrue(SlayerTasks.counts("CRAZY ARCHAEOLOGISTS", "deranged archaeologist"));
	}

	/** "unless the Wilderness is specified as the task location when given by Krystilia or Konar" (wiki). */
	@Test
	public void derangedArchaeologistDoesNotCountForAWildernessTask()
	{
		for (int master : new int[]{SlayerTasks.KRYSTILIA, SlayerTasks.KONAR})
		{
			final SlayerTasks.Verdict v = verdict(CRAZY, DERANGED, master, null);
			assertFalse("master " + master, v.onTask);
			assertTrue(v.known);
			assertTrue(v.why, v.why.contains("not taken for it in the Wilderness"));
			// The Crazy archaeologist still counts there: he lives in it.
			assertTrue(verdict(CRAZY, "Crazy archaeologist", master, null).onTask);
		}
		// A task whose area names the Wilderness, whoever gave it.
		assertFalse(verdict(CRAZY, DERANGED, 0, "the Wilderness").onTask);
		assertFalse(SlayerTasks.counts(CRAZY, DERANGED, true));
		assertTrue(SlayerTasks.counts(CRAZY, DERANGED, false));
		assertTrue(SlayerTasks.inWilderness(7, null));
		assertTrue(SlayerTasks.inWilderness(0, "Wilderness"));
		assertFalse(SlayerTasks.inWilderness(5, null));
		assertFalse(SlayerTasks.inWilderness(0, "Kourend Catacombs"));
	}

	/** Only the alternatives the game turns down there: the rest of a Wilderness task's table is untouched. */
	@Test
	public void theWildernessLeavesOtherAlternativesAlone()
	{
		assertTrue(SlayerTasks.counts("Callisto", "Artio", true));
		assertTrue(SlayerTasks.counts("Bears", "Callisto", true));
	}

	// --- The audit's additions ----------------------------------------------------------------------------------------

	/** Boss Slayer: "Artio can be killed instead", "Spindel may be killed instead", "Calvar'ion may be killed instead". */
	@Test
	public void wildernessBossesTakeTheirSmallerVersions()
	{
		assertTrue(SlayerTasks.counts("Callisto", "Artio"));
		assertTrue(SlayerTasks.counts("Venenatis", "Spindel"));
		assertTrue(SlayerTasks.counts("Vet'ion", "Calvar'ion"));
		// Not the other way round's neighbours.
		assertFalse(SlayerTasks.counts("Callisto", "Spindel"));
		assertFalse(SlayerTasks.counts("Venenatis", "Calvar'ion"));
	}

	/** The rest of the audit, each against the wiki page named beside it in {@code SlayerTasks}. */
	@Test
	public void theAuditsAdditions()
	{
		final String[][] cases = {
			{"Barrows Brothers", "Ahrim the Blighted", "Dharok the Wretched", "Guthan the Infested", "Karil the Tainted",
				"Torag the Corrupted", "Verac the Defiled"},
			{"Dagannoth Kings", "Dagannoth Rex", "Dagannoth Prime", "Dagannoth Supreme"},
			{"TzTok-Jad", "Tz-Kih", "Tz-Kek", "Tok-Xil", "Yt-MejKot", "Ket-Zek", "Yt-HurKot"},
			{"TzKal-Zuk", "Jal-Nib", "Jal-MejRah", "Jal-Ak", "Jal-AkRek-Mej", "Jal-AkRek-Xil", "Jal-AkRek-Ket", "Jal-ImKot",
				"Jal-Xil", "Jal-Zek", "JalTok-Jad", "Jal-MejJak", "Yt-HurKot"},
			{"Tzhaar", "TzHaar-Hur", "TzHaar-Mej", "TzHaar-Xil", "TzHaar-Ket"},
			{"Rats", "Hell-Rat Behemoth", "Scurrius"},
			{"Pyrefiends", "Pyrelord", "Infernal pyrelord"},
			{"Lesser Nagua", "Earthen Nagua"},
			{"Vampyres", "Venator", "Blood-starved venator"},
			{"Skeletons", "Skogre"},
			{"Scorpions", "Giant lobster", "Scorpia's offspring", "Scorpia's guardian"},
			{"Dogs", "Shadow hound"},
		};
		for (String[] c : cases)
		{
			for (int i = 1; i < c.length; i++)
			{
				assertTrue(c[0] + " / " + c[i], SlayerTasks.counts(c[0], c[i]));
				// As an NPC's name comes, with a non-breaking space.
				assertTrue(c[0] + " / " + c[i], SlayerTasks.counts(c[0], c[i].replace(' ', '\u00A0')));
			}
		}
		// Whole names, not parts: the Inferno's Jal-Ak is not every Jal-AkRek, but each is listed.
		assertFalse(SlayerTasks.counts("Tzhaar", "TzHaar-Ket-Rak"));
		assertFalse(SlayerTasks.counts("Dagannoth Kings", "Dagannoth spawn"));
	}

	/** RuneLite spells boss tasks with "The"; the NPC has none. Either spelling of the task finds the boss. */
	@Test
	public void aBossTaskMatchesWithoutItsThe()
	{
		assertTrue(SlayerTasks.counts("The Giant Mole", "Giant Mole"));
		assertTrue(SlayerTasks.counts("The Abyssal Sire", "Abyssal Sire"));
		assertTrue(SlayerTasks.counts("The Maggot King", "Maggot King"));
		assertTrue(SlayerTasks.counts("The Kalphite Queen", "Kalphite Queen"));
		assertTrue(SlayerTasks.counts("The King Black Dragon", "King Black Dragon"));
		assertTrue(SlayerTasks.counts("The Leviathan", "The Leviathan"));
		// The table's row is found from the bare name too: Dusk for the guardians, the Kraken for the kraken boss.
		assertTrue(SlayerTasks.counts("Grotesque Guardians", "Dusk"));
		assertTrue(SlayerTasks.counts("Cave Kraken Boss", "Kraken"));
		// "The" is a word of its own, not the start of one.
		assertFalse(SlayerTasks.counts("The Giant Mole", "Giant Rat"));
	}

	// --- TASK MISMATCH ------------------------------------------------------------------------------------------------

	private static SlayerTasks.Watch watching(String task, int count)
	{
		final SlayerTasks.Watch w = new SlayerTasks.Watch();
		w.noteTask(task, false, count);
		return w;
	}

	/** An NPC the table says is off task dies as the count falls: logged, and on task from then on. */
	@Test
	public void aCountFallingOnAnOffTaskNpcIsCaught()
	{
		final SlayerTasks.Watch w = watching("Abyssal Demons", 10);
		assertNull(w.onCount(9, 100));
		// Seen as it despawns, after its death animation. Nothing yet: an NPC the table counts may still die in the window.
		assertNull(w.onDeath(1234, "Sire spawn", true, 104));
		assertNull(w.onTick(110));
		final String line = w.onTick(100 + SlayerTasks.Watch.WINDOW + 1);
		assertNotNull(line);
		assertTrue(line, line.startsWith("TASK MISMATCH Sire spawn (npc 1234): the Abyssal Demons task's count fell 10 -> 9"));
		assertTrue(line, line.endsWith("counted as on task for the rest of the session"));
		// Once only.
		assertNull(w.onTick(200));

		final SlayerTasks.Verdict after = SlayerTasks.verdict(9, "Abyssal Demons", "Sire spawn", 0, 0, null, w.learned());
		assertTrue(after.onTask);
		assertTrue(after.known);
		assertTrue(after.why, after.why.contains("TASK MISMATCH"));
		// For that task only.
		assertFalse(SlayerTasks.verdict(9, "Kalphites", "Sire spawn", 0, 0, null, w.learned()).onTask);
		// And the watch itself now counts its deaths as on task.
		assertNull(w.onCount(8, 300));
		assertNull(w.onDeath(1234, "Sire spawn", true, 303));
		assertNull(w.onTick(400));
	}

	/** The death can come before the count falls as well as after. */
	@Test
	public void theDeathMayComeFirst()
	{
		final SlayerTasks.Watch w = watching(CRAZY, 14);
		// The Wilderness version, where the table turns the Deranged archaeologist down.
		w.noteTask(CRAZY, true, 14);
		assertNull(w.onDeath(7806, DERANGED, true, 50));
		assertNull(w.onCount(13, 52));
		final String line = w.onTick(52 + SlayerTasks.Watch.WINDOW + 1);
		assertNotNull(line);
		assertTrue(line, line.contains("(the task is in the Wilderness)"));
	}

	/** The table already explains the fall: nothing to log. */
	@Test
	public void anOnTaskDeathExplainsTheFall()
	{
		final SlayerTasks.Watch w = watching(CRAZY, 14);
		assertNull(w.onCount(13, 100));
		assertNull(w.onDeath(7806, DERANGED, true, 104));
		assertNull(w.onTick(200));
		assertTrue(w.learned().isEmpty());

		// An off-task kill beside an on-task one teaches nothing either: which one counted cannot be told.
		final SlayerTasks.Watch both = watching("Abyssal Demons", 10);
		assertNull(both.onCount(9, 100));
		assertNull(both.onDeath(1, "Sire spawn", true, 103));
		assertNull(both.onDeath(2, "Abyssal demon", false, 105));
		assertNull(both.onTick(200));
		assertTrue(both.learned().isEmpty());
	}

	/** Only an NPC the player was fighting, and only a death near the fall. */
	@Test
	public void otherDeathsTeachNothing()
	{
		final SlayerTasks.Watch w = watching("Abyssal Demons", 10);
		assertNull(w.onCount(9, 100));
		// Another player's kill.
		assertNull(w.onDeath(1, "Goblin", false, 103));
		// Too long after.
		assertNull(w.onDeath(2, "Hill Giant", true, 100 + SlayerTasks.Watch.WINDOW + 5));
		assertNull(w.onTick(300));
		assertTrue(w.learned().isEmpty());

		// A death with no fall: the count did not move, so the table was right.
		final SlayerTasks.Watch still = watching("Abyssal Demons", 10);
		assertNull(still.onDeath(3, "Vardorvis", true, 100));
		assertNull(still.onTick(200));
		assertTrue(still.learned().isEmpty());
	}

	/** The task's last kill: the count falls to none, and the task is matched by the name it had. */
	@Test
	public void theLastKillStillHasTheTasksName()
	{
		final SlayerTasks.Watch w = watching("Abyssal Demons", 1);
		assertNull(w.onCount(0, 100));
		assertNull(w.onDeath(1, "Sire spawn", true, 102));
		assertNotNull(w.onTick(200));
	}

	/** A count going up is a new task, not a kill. */
	@Test
	public void aNewTaskIsNotAKill()
	{
		final SlayerTasks.Watch w = watching("Abyssal Demons", 0);
		assertNull(w.onCount(150, 100));
		assertNull(w.onDeath(1, "Goblin", true, 101));
		assertNull(w.onTick(200));
		assertTrue(w.learned().isEmpty());
	}

	// --- The helmet ---------------------------------------------------------------------------------------------------

	/**
	 * The log's helmet, "Oathplate slayer helmet (i)" (item 33443 in its GEAR line), is a slayer helmet to
	 * {@code GearBonusCalc}, which goes by the name: imbued, so x1.15 for magic and ranged, and 7/6 for melee, on task
	 * only. Its hits over the expected max in that log fit x1.15 to the damage.
	 */
	@Test
	public void theOathplateSlayerHelmetIsASlayerHelmet() throws Exception
	{
		final GearBonusCalc calc = new GearBonusCalc(client());
		final Loadout imbued = head(33443, "Oathplate slayer helmet (i)");
		assertEquals(1.15, blackMask(calc, AttackType.MAGIC, imbued, true).getAccuracy(), 1e-9);
		assertEquals(1.15, blackMask(calc, AttackType.MAGIC, imbued, true).getDamage(), 1e-9);
		assertEquals(1.15, blackMask(calc, AttackType.RANGED, imbued, true).getDamage(), 1e-9);
		assertEquals(7.0 / 6.0, blackMask(calc, AttackType.SLASH, imbued, true).getDamage(), 1e-9);
		assertTrue(blackMask(calc, AttackType.MAGIC, imbued, false).isNone());
		// Not imbued: melee only.
		// The id does not matter here: the name is what is matched.
		final Loadout plain = head(1, "Oathplate slayer helmet");
		assertTrue(blackMask(calc, AttackType.MAGIC, plain, true).isNone());
		assertEquals(7.0 / 6.0, blackMask(calc, AttackType.CRUSH, plain, true).getAccuracy(), 1e-9);
	}

	// A worn helmet with its name already known, as the item manager would answer it.
	private static Loadout head(int id, String name) throws Exception
	{
		final Loadout gear = Loadout.worn(null, null).with(EquipmentInventorySlot.HEAD, id);
		final Field names = Loadout.class.getDeclaredField("names");
		names.setAccessible(true);
		((String[]) names.get(gear))[EquipmentInventorySlot.HEAD.getSlotIdx()] = name;
		return gear;
	}

	private static GearBonus blackMask(GearBonusCalc calc, AttackType type, Loadout gear, boolean onTask)
		throws Exception
	{
		final Method m = GearBonusCalc.class.getDeclaredMethod("blackMaskBonus", AttackType.class, Loadout.class,
			boolean.class);
		m.setAccessible(true);
		return (GearBonus) m.invoke(calc, type, gear, onTask);
	}

	private static Client client()
	{
		return (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(proxy, method, args) ->
			{
				final Class<?> type = method.getReturnType();
				if (type == int.class)
				{
					return 0;
				}
				if (type == boolean.class)
				{
					return false;
				}
				if (type == long.class)
				{
					return 0L;
				}
				return null;
			});
	}
}
