package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import org.junit.Test;

/**
 * T-149: "The first elder maul or dragon warhammer special that makes contact with Tekton will always land" (OSRS
 * Wiki, Tekton). The first of them to reach him in a kill, anyone's, is scored certain to land, and its drain certain;
 * every later one at its roll. Eric's 11-01 raids scored his first at 0.38 ([5192]) and 0.45 ([10515]).
 */
public class TektonFirstSpecialTest
{
	private static final int TEKTON = 62026;
	private static final int TEKTON_ID = 7541;
	private static final int SLOT = 300;

	private final DrainTracker tracker = new DrainTracker(null);

	/** Tekton for a party of 3: Defence 209, back a level every 15 ticks, a missed hammer still takes 5%. */
	private static DrainRule.Target tekton()
	{
		return new DrainRule.Target(209, 209, 0, 1, 1, 1, 0, 0, false, false, true, false, 15);
	}

	private static List<SpecialWeapon> rule()
	{
		final BossRules rules = BossRules.load(new Gson(), null);
		return DefenceDrain.firstSpecialLands(rules.forNpc(NpcID.RAIDS_TEKTON_WAITING).defenceDrain);
	}

	private static final List<SpecialWeapon> FIRSTS = Arrays.asList(SpecialWeapon.DRAGON_WARHAMMER,
		SpecialWeapon.ELDER_MAUL);

	@Test
	public void tektonsRuleNamesTheHammerAndTheMaul()
	{
		assertEquals(FIRSTS, rule());
		// Every form of him.
		final BossRules rules = BossRules.load(new Gson(), null);
		for (int id : new int[]{7540, 7541, 7542, 7543, 7544, 7545})
		{
			assertEquals(FIRSTS, DefenceDrain.firstSpecialLands(rules.forNpc(id).defenceDrain));
		}
	}

	@Test
	public void ericsFirstHammerIsCertain()
	{
		// Thrown on 5191, its splat on 5192: nothing reached Tekton before it.
		tracker.ownThrow(5191, SpecialWeapon.DRAGON_WARHAMMER, TEKTON, TEKTON_ID, "Tekton", 5192, tekton(), false);
		assertNull(tracker.reachedBefore(TEKTON, FIRSTS, 5191, 5192));
		// Its own drain, in before its booking, is the one asked about.
		tracker.ownSplat(TEKTON, 5192, 39);
		tracker.tick(5192);
		assertEquals(147, tracker.tracked(TEKTON).state.defence);
		assertNull(tracker.reachedBefore(TEKTON, FIRSTS, 5191, 5192));
		// [5192]'s figures: chance 0.38, avg 11.54, var 332.3. Certain: its landing's mean and variance.
		final double[] landed = CombatCalc.certainToLand(0.38, 11.54, 332.3);
		assertEquals(1, landed[0], 0);
		assertEquals(11.54 / 0.38, landed[1], 1e-9);
		assertEquals((332.3 + 11.54 * 11.54) / 0.38 - landed[1] * landed[1], landed[2], 1e-9);
	}

	@Test
	public void hisSecondIsAtItsRoll()
	{
		tracker.ownThrow(5191, SpecialWeapon.DRAGON_WARHAMMER, TEKTON, TEKTON_ID, "Tekton", 5192, tekton(), false);
		tracker.ownSplat(TEKTON, 5192, 39);
		tracker.tick(5192);
		tracker.ownThrow(5203, SpecialWeapon.DRAGON_WARHAMMER, TEKTON, 7542, "Tekton", 5204, tekton(), false);
		final String before = tracker.reachedBefore(TEKTON, FIRSTS, 5203, 5204);
		assertEquals("my Dragon Warhammer reached it on tick 5192", before);
	}

	@Test
	public void aPartyMembersFirstPutsHisAtItsRoll()
	{
		tracker.partyDrain(100, SpecialWeapon.DRAGON_WARHAMMER, 40, TEKTON, TEKTON_ID, "Tekton", tekton());
		assertEquals("a party member's Dragon Warhammer reached it on tick 100",
			tracker.reachedBefore(TEKTON, FIRSTS, 101, 102));
		// A message on his landing tick landed before it.
		assertNotNull(tracker.reachedBefore(TEKTON, FIRSTS, 99, 100));
		// One that came after his landing can't be seen then: his is taken as the first.
		assertNull(new DrainTracker(null).reachedBefore(TEKTON, FIRSTS, 99, 100));
	}

	@Test
	public void aSeenFirstPutsHisAtItsRollWhileItWaitsAndAfter()
	{
		tracker.seenThrow(100, SpecialWeapon.DRAGON_WARHAMMER, SLOT, TEKTON, TEKTON_ID, "Tekton", 101, tekton());
		// Thrown, no splat yet: nothing has reached him.
		assertNull(tracker.reachedBefore(TEKTON, FIRSTS, 101, 102));
		tracker.othersSplat(TEKTON, 101, 40);
		tracker.tick(101);
		// Its splat is in, waiting for a party counter: it reached him first.
		assertEquals(1, tracker.seenCount());
		assertEquals("a player outside the party's Dragon Warhammer (slot 300) reached it on tick 101",
			tracker.reachedBefore(TEKTON, FIRSTS, 101, 102));
		for (int t = 102; t <= 105; t++)
		{
			tracker.tick(t);
		}
		assertEquals(0, tracker.seenCount());
		assertEquals("a player outside the party's Dragon Warhammer reached it on tick 101",
			tracker.reachedBefore(TEKTON, FIRSTS, 106, 107));
	}

	@Test
	public void anElderMaul()
	{
		// His maul first: drained on its throw from its experience, booked at its splat two ticks on. Still the first.
		tracker.ownThrow(10, SpecialWeapon.ELDER_MAUL, TEKTON, TEKTON_ID, "Tekton", 12, tekton(), true);
		assertTrue(tracker.tracked(TEKTON).state.defence < 209);
		assertNull(tracker.reachedBefore(TEKTON, FIRSTS, 10, 12));
		// A hammer after it: the maul was the first of them.
		assertEquals("my " + SpecialWeapon.ELDER_MAUL.getName() + " reached it on tick 12",
			tracker.reachedBefore(TEKTON, FIRSTS, 20, 21));
		// A party member's maul first puts his hammer at its roll.
		final DrainTracker other = new DrainTracker(null);
		other.partyDrain(10, SpecialWeapon.ELDER_MAUL, 30, TEKTON, TEKTON_ID, "Tekton", tekton());
		assertNotNull(other.reachedBefore(TEKTON, FIRSTS, 20, 21));
	}

	@Test
	public void aGodswordFirstDoesntCount()
	{
		// The rule names the hammer and the maul: a godsword before them leaves his hammer the first.
		tracker.partyDrain(10, SpecialWeapon.BANDOS_GODSWORD, 30, TEKTON, TEKTON_ID, "Tekton", tekton());
		assertNull(tracker.reachedBefore(TEKTON, FIRSTS, 20, 21));
		assertFalse(rule().contains(SpecialWeapon.BANDOS_GODSWORD));
	}

	@Test
	public void notTektonIsUnchanged()
	{
		final BossRules rules = BossRules.load(new Gson(), null);
		// Olm's left claw and a boss with no drain rules: no first special.
		final BossRules.Boss claw = rules.forNpc(NpcID.OLM_HAND_LEFT);
		assertTrue(DefenceDrain.firstSpecialLands(claw == null ? null : claw.defenceDrain).isEmpty());
		assertTrue(DefenceDrain.firstSpecialLands(null).isEmpty());
		// Figures that already land, or unknown, pass through.
		assertEquals(1, CombatCalc.certainToLand(1, 30, 100)[0], 0);
		assertEquals(30, CombatCalc.certainToLand(1, 30, 100)[1], 0);
		assertEquals(-1, CombatCalc.certainToLand(-1, 30, 100)[0], 0);
	}

	// ---- The drain value: a certain landing has one outcome ----

	private static final int INDEX = 5;
	private static final int NPC = NpcIds.GOBLIN;

	private static double[] figuresAt(DrainRule.State s)
	{
		final double p = SpecOutcomesTest.accuracy(s.defence);
		final double[] attack = SpecOutcomesTest.attackAt(s);
		final double[] hammer = SpecOutcomesTest.roll(p, 75);
		final double[] f = new double[CombatCalc.AT_WIDTH];
		f[CombatCalc.AT_MEAN] = attack[0];
		f[CombatCalc.AT_VARIANCE] = attack[1];
		f[CombatCalc.AT_SPEC_MEAN] = hammer[0];
		f[CombatCalc.AT_SPEC_VARIANCE] = hammer[1];
		f[CombatCalc.AT_SPEC_LAND] = p;
		f[CombatCalc.AT_SPEC_ACCURACY] = p;
		f[CombatCalc.AT_SPEC_MAX] = 75;
		f[CombatCalc.AT_CAP] = Integer.MAX_VALUE;
		return f;
	}

	private static Set<Integer> defences(SpecOutcomes o)
	{
		final Set<Integer> out = new HashSet<>();
		for (DrainRule.State s : o.probabilities().keySet())
		{
			out.add(s.defence);
		}
		return out;
	}

	@Test
	public void aCertainHammersDrainIsCertain()
	{
		final DrainTracker t = new DrainTracker(null);
		final DrainLuck luck = new DrainLuck(t::tracked, (npcId, base, s) -> figuresAt(s), (e, v) -> { }, null);
		final Fight f = new Fight("Tekton", NPC, INDEX, 1000, 0L, null, 0);
		final DrainRule.Target target = new DrainRule.Target(200, 100, 0, 200, 200, 100, 0, 0, false, false, false,
			false, 0);
		luck.start(f, t.track(INDEX, NPC, "Tekton", target));
		t.ownThrow(10, SpecialWeapon.DRAGON_WARHAMMER, INDEX, NPC, "Tekton", 11, target, false);
		luck.snapshot(f, NPC, 10);
		// Booked as the plugin books it when certain: a landing's figures.
		final double[] fig = CombatCalc.certainToLand(figuresAt(DrainRule.State.of(target)));
		assertEquals(1, fig[CombatCalc.AT_SPEC_LAND], 0);
		assertEquals(1, fig[CombatCalc.AT_SPEC_ACCURACY], 0);
		f.recordExpected(75, 1, fig[CombatCalc.AT_SPEC_MEAN], fig[CombatCalc.AT_SPEC_VARIANCE]);
		luck.booked(f, 10, 11, true, SpecialWeapon.DRAGON_WARHAMMER, fig[CombatCalc.AT_SPEC_MEAN],
			fig[CombatCalc.AT_SPEC_VARIANCE], true);
		t.ownSplat(INDEX, 11, 30);
		t.tick(11);
		for (int tick = 11; tick < 20; tick++)
		{
			luck.tick(tick);
		}
		// One state: the drain, never the miss.
		final SpecOutcomes o = luck.outcomes(f);
		assertEquals(new HashSet<>(Arrays.asList(140)), defences(o));
		assertFalse(o.branched());
		final DrainRule.State drained = t.tracked(INDEX).state;
		final double[] later = figuresAt(drained);
		f.recordExpected(50, SpecOutcomesTest.accuracy(drained.defence), later[CombatCalc.AT_MEAN],
			later[CombatCalc.AT_VARIANCE]);
		luck.snapshot(f, NPC, 20);
		luck.booked(f, 20, 21, false, null, later[CombatCalc.AT_MEAN], later[CombatCalc.AT_VARIANCE]);
		// Nothing left to luck in its landing: the drain's value as made is its value averaged.
		assertEquals(0, f.getSpecLuckDamage(), 1e-9);
		assertEquals(f.getDrainValueExpected(), f.getDrainValueDamage(), 1e-9);
		assertTrue(f.getDrainValueDamage() > 0);
	}
}
