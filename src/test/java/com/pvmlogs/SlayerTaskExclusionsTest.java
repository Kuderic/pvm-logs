package com.pvmlogs;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.Collections;
import org.junit.Test;

/** Synthetic cases for the exclusions requested in the T-090 follow-up. */
public class SlayerTaskExclusionsTest
{
	@Test
	public void rockTheTrollDoesNotMatchCrabs()
	{
		assertTrue(SlayerTasks.counts("Trolls", "Rock"));
		assertTrue(SlayerTasks.counts("Trolls", "rOcK"));
		for (String crab : new String[]{"Rock Crab", "Giant Rock Crab", "Rock\u00A0Crab"})
		{
			assertFalse(crab, SlayerTasks.counts("Trolls", crab));
			assertTrue(crab, SlayerTasks.counts("Crabs", crab));
		}
		assertTrue(SlayerTasks.counts("Trolls", "Dad"));
		assertTrue(SlayerTasks.counts("Trolls", "Ice troll"));
	}

	@Test
	public void revenantsAreNotTheirLivingTaskTargets()
	{
		final String[][] pairs = {
			{"Goblins", "Revenant goblin"},
			{"Hobgoblins", "Revenant hobgoblin"},
			{"Hill giants", "Revenant cyclops"},
			{"Pyrefiends", "Revenant pyrefiend"},
			{"Hellhounds", "Revenant hellhound"},
			{"Dark beasts", "Revenant dark beast"}
		};
		for (String[] pair : pairs)
		{
			for (String npc : new String[]{pair[1], pair[1].toUpperCase(java.util.Locale.ROOT), pair[1].replace(' ', '\u00A0')})
			{
				assertFalse(pair[0] + " / " + npc, SlayerTasks.counts(pair[0], npc));
				assertFalse(pair[0] + " / " + npc, SlayerTasks.counts(pair[0], npc, true));
				assertTrue(npc, SlayerTasks.counts("Ghosts", npc));
				assertTrue(npc, SlayerTasks.counts("Revenants", npc));
			}
		}
		assertTrue(SlayerTasks.counts("Hellhounds", "Hellhound"));
		assertTrue(SlayerTasks.counts("Hellhounds", "Cerberus"));
		assertTrue(SlayerTasks.counts("Hill giants", "Cyclops"));
	}

	@Test
	public void zombiesChampionDoesNotGetTheZombiesTaskBonus()
	{
		assertFalse(SlayerTasks.counts("Zombies", "Zombies Champion"));
		assertFalse(SlayerTasks.counts("Zombies", "zombies champion"));
		assertFalse(SlayerTasks.counts("Zombies", "Zombies\u00A0Champion"));
		assertTrue(SlayerTasks.counts("Zombies", "Zombie"));
		assertTrue(SlayerTasks.counts("Zombies", "Armoured zombie"));
		assertTrue(SlayerTasks.counts("Zombies", "Vorkath"));
	}

	@Test
	public void theOffTaskVerdictUsesTheExclusions()
	{
		assertFalse(SlayerTasks.verdict(10, "Trolls", "Rock Crab", 0).onTask);
		assertFalse(SlayerTasks.verdict(10, "Hellhounds", "Revenant hellhound", 0).onTask);
		assertFalse(SlayerTasks.verdict(10, "Zombies", "Zombies Champion", 0).onTask);
		assertTrue(SlayerTasks.verdict(10, "Ghosts", "Revenant hellhound", 0, SlayerTasks.KRYSTILIA, null,
			Collections.emptySet()).onTask);
	}
}
