package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * T-211: the Alchemical Hydra takes 75% less damage from each of her first three phases' start until the right vent
 * weakens her (OSRS Wiki, Alchemical Hydra: "Barring the last phase, the damage reduction is reapplied"). The state
 * opens on a phase's npc id, closes on the weakening's game message, on a hit over the shielded max, or at the phase's
 * end; a shielded hit is a quarter, rounded down (a type-43 max-hit splat of 13 under an ordinary max of 54 in Eric's
 * kills of 2026-11-11).
 */
public class HydraShieldTest
{
	private static final BossRules RULES = BossRules.load(new Gson(), null);
	private static final int INDEX = 37533;
	private static final ModifierTracker.InsideCheck NOWHERE = (id, range) -> false;
	private static final String WEAKENED = "The chemicals neutralise the Alchemical Hydra's defences!";

	private final List<String> lines = new ArrayList<>();
	private final ModifierTracker tracker = new ModifierTracker(lines::add);

	private static BossRules.Boss hydra(int npcId)
	{
		final BossRules.Boss boss = RULES.forNpc(npcId);
		assertNotNull("npc " + npcId, boss);
		assertEquals("Alchemical Hydra", boss.name);
		return boss;
	}

	private BossRules.DamageModifierRules rules()
	{
		return hydra(8615).damageModifiers;
	}

	private void form(int npcId, int tick)
	{
		tracker.onNpcId(INDEX, "Alchemical Hydra", rules(), npcId, tick);
	}

	private boolean shielded(int npcId, int tick)
	{
		return tracker.isOpen(INDEX, "unweakened", tick)
			&& !tracker.applying(INDEX, rules(), npcId, "RANGED", tick, false, NOWHERE).isEmpty();
	}

	@Test
	public void opensOnEachOfTheFirstThreePhasesAndNotTheEnraged()
	{
		// The order of Eric's KC 1 (2026-11-11, [1471]-[1733]).
		form(8615, 1471);
		assertTrue(shielded(8615, 1472));
		form(8616, 1500);
		assertFalse(shielded(8616, 1501));
		form(8619, 1538);
		assertTrue(shielded(8619, 1540));
		form(8617, 1600);
		form(8620, 1610);
		assertTrue(shielded(8620, 1611));
		form(8618, 1680);
		form(8621, 1690);
		assertFalse(shielded(8621, 1691));
		assertFalse(tracker.isOpen(INDEX, "unweakened", 1720));
		// The same form read again (each tick, each attack) opens nothing new and closes nothing.
		form(8615, 2000);
		final int opened = lines.size();
		form(8615, 2001);
		form(8615, 2002);
		assertEquals(opened, lines.size());
	}

	@Test
	public void closesOnTheWeakeningMessage()
	{
		form(8615, 100);
		tracker.onPlayerHit(INDEX, 102);
		tracker.onPlayerHit(INDEX, 107);
		// Any other message leaves it open.
		tracker.onGameMessage(INDEX, "Alchemical Hydra", rules(), "The Alchemical Hydra's defences partially absorb your attack!", 108);
		assertTrue(shielded(8615, 108));
		tracker.onGameMessage(INDEX, "Alchemical Hydra", rules(), WEAKENED, 109);
		assertFalse(shielded(8615, 109));
		assertTrue(shielded(8615, 108)); // an attack landing before it was still shielded
		assertTrue(lines.toString(), lines.contains(
			"SHIELD Alchemical Hydra phase 1: 9 ticks, 2 attacks shielded (the message \"" + WEAKENED + "\")"));
		// The next phase starts shielded again, and its own message ends it.
		form(8617, 150);
		form(8619, 151);
		assertTrue(shielded(8619, 152));
		tracker.onGameMessage(INDEX, "Alchemical Hydra", rules(), WEAKENED, 160);
		assertFalse(shielded(8619, 160));
		assertTrue(lines.toString(), lines.contains(
			"SHIELD Alchemical Hydra phase 2: 9 ticks, 0 attacks shielded (the message \"" + WEAKENED + "\")"));
		assertEquals(Collections.singletonMap("shield", 18), tracker.shownTicks(INDEX, 200));
	}

	@Test
	public void aHitOverTheShieldedMaxEndsItAndThePhaseEndsItAnyway()
	{
		form(8615, 100);
		tracker.onMaxExceeded(INDEX, 43, 25, 118);
		assertFalse(shielded(8615, 118));
		assertTrue(lines.toString(), lines.stream().anyMatch(l -> l.startsWith("SHIELD Alchemical Hydra phase 1: 18 ticks")
			&& l.endsWith("(a hit of 43 over the max of 25)")));
		// Never weakened: the transition closes it, and a still-open window counts to now.
		form(8619, 200);
		assertEquals(Integer.valueOf(18 + 10), tracker.shownTicks(INDEX, 210).get("shield"));
		form(8617, 230);
		assertEquals(Integer.valueOf(18 + 30), tracker.shownTicks(INDEX, 400).get("shield"));
		// A boss the tracker hasn't seen has none.
		assertTrue(tracker.shownTicks(1, 400).isEmpty());
	}

	@Test
	public void aShieldedHitIsAQuarterRoundedDown()
	{
		form(8615, 100);
		final List<BossRules.ModifierRule> mods = tracker.applying(INDEX, rules(), 8615, "RANGED", 101, false, NOWHERE);
		assertEquals(1, mods.size());
		assertEquals("shield", mods.get(0).name);
		// Eric's type-43 splats: 13 under an ordinary max of 54, 25 for the ruby bolt's 100.
		assertEquals(13, ModifierTracker.max(mods, 54));
		assertEquals(25, ModifierTracker.max(mods, 100));
		assertEquals(13, ModifierTracker.damage(mods, 54, 54, true));
		assertEquals(0, ModifierTracker.damage(mods, 3, 54, true));
		// The moments: each landed roll r of 0..54 (0 pushed to 1) to floor(r / 4), by accuracy 0.9.
		final DamageMoments m = ModifierTracker.moments(mods, new int[]{54}, 0.9, 1100);
		double mean = 0;
		for (int r = 0; r <= 54; r++)
		{
			mean += 0.9 / 55 * (Math.max(1, r) / 4);
		}
		assertEquals(mean, m.mean, 1e-9);
	}

	@Test
	public void theHitpointsCapComesAfterTheQuarter()
	{
		form(8615, 100);
		final List<BossRules.ModifierRule> mods = tracker.applying(INDEX, rules(), 8615, "RANGED", 101, false, NOWHERE);
		// 5 hp left: a 54 is 13 shielded, then capped at 5; a 12 is 3, under the cap.
		final DamageMoments capped = ModifierTracker.moments(mods, new int[]{54}, 1.0, 5);
		double mean = 0;
		for (int r = 0; r <= 54; r++)
		{
			mean += Math.min(5, Math.max(1, r) / 4) / 55.0;
		}
		assertEquals(mean, capped.mean, 1e-9);
		// Capping first would give min(5, r) / 4, at most 1: less than the game's.
		assertTrue(capped.mean > 1.0);
	}

	@Test
	public void versionsByForm()
	{
		final String[][] expected = {
			{"8615", "Serpentine"}, {"8616", "Serpentine"}, {"8619", "Electric"}, {"8617", "Electric"},
			{"8620", "Fire"}, {"8618", "Fire"}, {"8621", "Extinguished"}, {"8622", "Extinguished"}};
		for (String[] e : expected)
		{
			final int id = Integer.parseInt(e[0]);
			assertEquals(e[0], e[1], BossRules.versionFor(hydra(id), id));
		}
		// The shield is hidden from what's shown live: the overlay shows her weakened rate, never whether she's shielded.
		form(8615, 100);
		final List<BossRules.ModifierRule> mods = tracker.applying(INDEX, rules(), 8615, "RANGED", 101, false, NOWHERE);
		assertEquals(1, mods.size());
		assertTrue(ModifierTracker.shownLive(mods).isEmpty());
	}

	/** The triggers are general: a made-up boss whose form opens a state and whose message ends it. */
	@Test
	public void theTriggersWorkForAnyBoss()
	{
		final BossRules.StateRule s = new BossRules.StateRule();
		s.name = "warded";
		s.startOnNpcId = new int[]{1001};
		s.endOnNpcId = new int[]{1002};
		s.startOnGameMessage = Collections.singletonList("raises a ward");
		s.endOnGameMessage = Arrays.asList("ward shatters", "ward fades");
		s.shownAs = "ward";
		final BossRules.ModifierRule m = new BossRules.ModifierRule();
		m.name = "halved";
		m.state = "warded";
		m.multiply = new int[]{1, 2};
		final BossRules.DamageModifierRules r = new BossRules.DamageModifierRules();
		r.states = Collections.singletonList(s);
		r.modifiers = Collections.singletonList(m);
		assertTrue(ModifierTracker.opensOnNpcId(r));
		assertTrue(ModifierTracker.listensForMessages(r));
		assertTrue(ModifierTracker.hasShown(r));

		tracker.onNpcId(7, "Warden", r, 1000, 10);
		assertFalse(tracker.isOpen(7, "warded", 11));
		tracker.onNpcId(7, "Warden", r, 1001, 12);
		assertTrue(tracker.isOpen(7, "warded", 13));
		assertEquals(30, ModifierTracker.max(tracker.applying(7, r, 1001, "MELEE", 13, false, NOWHERE), 61));
		// Case ignored (the plugin hands it the message with its tags removed).
		tracker.onGameMessage(7, "Warden", r, "The WARD SHATTERS!", 20);
		assertFalse(tracker.isOpen(7, "warded", 20));
		tracker.onGameMessage(7, "Warden", r, "The warden raises a ward.", 30);
		assertTrue(tracker.isOpen(7, "warded", 31));
		tracker.onNpcId(7, "Warden", r, 1002, 40);
		assertFalse(tracker.isOpen(7, "warded", 40));
		final Map<String, Integer> shown = tracker.shownTicks(7, 50);
		assertEquals(Collections.singletonMap("ward", 8 + 10), shown);
		// A state with none of the new triggers is untouched by messages and forms.
		final BossRules.DamageModifierRules plain = new BossRules.DamageModifierRules();
		assertFalse(ModifierTracker.opensOnNpcId(plain));
		assertFalse(ModifierTracker.listensForMessages(plain));
		assertFalse(ModifierTracker.matches(null, "x"));
		assertFalse(ModifierTracker.matches(Collections.singletonList("ward"), null));
	}

	@Test
	public void theFightKeepsTheShieldShownAndUncounted()
	{
		final Fight f = new Fight("Alchemical Hydra", 8615, INDEX, 1100, 0L, null, 0);
		f.recordAttackMade(true);
		f.recordAttackResolved(true, false, 13, 7);
		final int combat = f.getCombatTicks();
		final int lost = f.getTicksLost();
		f.recordShownTicks(Collections.singletonMap("shield", 42));
		assertEquals(42, f.getShownTicks("shield"));
		assertEquals(0, f.getShownTicks("stall"));
		assertEquals(Collections.singleton("shield"), f.getShownStates());
		assertEquals(combat, f.getCombatTicks());
		assertEquals(lost, f.getTicksLost());
		assertEquals(0, f.getTicksMechanics());
		// Saved and read back with the history's Gson.
		final Fight back = new Gson().fromJson(new Gson().toJson(f), Fight.class);
		assertEquals(42, back.getShownTicks("shield"));
		// A fight saved before has none.
		assertEquals(0, new Fight("Cerberus", 5862, 1, 600, 0L, null, 0).getShownTicks("shield"));
	}
}
