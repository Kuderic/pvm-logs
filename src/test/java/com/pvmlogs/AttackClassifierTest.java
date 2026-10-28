package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * T-041: each of Vardorvis's attacks from a constructed hit sequence, named by the
 * bundled rules (T-039's research): melee and spikes heal him half, axes and the
 * gaze don't, an axe's bleed follows it, Protect from Melee lets a quarter through.
 */
public class AttackClassifierTest
{
	private static final int MELEE = 10340;
	private static final int DASH = 10341;
	private static final int STRANGLE_LOOP = 10343;
	private static final int GAZE = 2521;
	private static final int HIT = 16;
	private static final int ZERO = 12;
	private static final int BLEED = 67;

	private static AttackClassifier classifier()
	{
		final BossRules.AttacksOnPlayer rules = AvoidableReplayTest.vardorvis();
		assertNotNull(rules);
		return new AttackClassifier(rules);
	}

	private static List<TargetActions.Action> anim(int id, int ticksBefore)
	{
		return Collections.singletonList(TargetActions.Action.animation(id, -ticksBefore));
	}

	private static List<TargetActions.Action> gazeLanded(int ticksBefore)
	{
		return Collections.singletonList(TargetActions.Action.projectile(GAZE, -ticksBefore - 2, -ticksBefore));
	}

	private static AttackClassifier.Named only(List<AttackClassifier.Named> named)
	{
		assertEquals(1, named.size());
		return named.get(0);
	}

	@Test
	public void theBundledRulesAreVardorvisPostQuestOnly()
	{
		final BossRules rules = BossRules.load(new com.google.gson.Gson(), null);
		final BossRules.AttacksOnPlayer postQuest = BossRules.attacksFor(rules.forNpc(12223), 700);
		assertEquals(1, postQuest.rulesVersion);
		assertEquals(7, postQuest.attacks.size());
		assertEquals("chip", postQuest.unnamed);
		assertNull(BossRules.attacksFor(rules.forNpc(12223), 1400));
		assertNull(BossRules.attacksFor(rules.forNpc(12223), 500));
		assertNull(AttackClassifier.usable(BossRules.attacksFor(rules.forNpc(5886), 400)));
	}

	@Test
	public void meleeThroughPrayerIsChip()
	{
		final AttackClassifier names = classifier();
		names.onHit(100, 8, HIT, "melee", anim(MELEE, 1));
		names.onBossHeal(100, 4);
		final AttackClassifier.Named named = only(names.settle(106));
		assertEquals("melee", named.name);
		assertEquals(0, named.avoidable, 0);
		assertEquals("as melee (certain: melee anim a tick before), avoidable 0", named.describe());
	}

	/** Off prayer only the part prayer would have stopped counts: ¾ of 20. */
	@Test
	public void meleeOffPrayerCountsThreeQuarters()
	{
		final AttackClassifier names = classifier();
		names.onHit(100, 20, HIT, null, anim(MELEE, 1));
		names.onBossHeal(100, 10);
		final AttackClassifier.Named named = only(names.settle(106));
		assertEquals("melee off prayer", named.name);
		assertEquals(15, named.avoidable, 1e-9);
		assertEquals(15, names.avoidable(), 1e-9);
	}

	/** More than the 10 that Protect from Melee lets through, under it: the prayer came up after the blow was rolled. */
	@Test
	public void aLatePrayerIsOffPrayer()
	{
		final AttackClassifier names = classifier();
		names.onHit(100, 20, HIT, "melee", anim(MELEE, 1));
		final AttackClassifier.Named named = only(names.settle(106));
		assertEquals("melee off prayer", named.name);
		assertTrue(named.evidence, named.evidence.contains("late prayer"));
		assertEquals(15, named.avoidable, 1e-9);
	}

	@Test
	public void dampenMeleeProtectsLikeProtectFromMelee()
	{
		final AttackClassifier names = classifier();
		names.onHit(100, 8, HIT, "dampen melee", anim(MELEE, 1));
		assertEquals("melee", only(names.settle(106)).name);
		assertTrue(AttackClassifier.protects("dampen ranged", "missiles"));
		assertFalse(AttackClassifier.protects("missiles", "melee"));
		assertFalse(AttackClassifier.protects(null, "melee"));
	}

	/** An axe heals him nothing and leaves a bleed: it waits the six ticks for it, then counts all of it. */
	@Test
	public void anAxeWaitsForItsBleed()
	{
		final AttackClassifier names = classifier();
		names.onHit(100, 14, HIT, "melee", Collections.emptyList());
		names.onHit(102, 3, BLEED, "melee", Collections.emptyList());
		assertTrue(names.settle(105).isEmpty());
		final List<AttackClassifier.Named> named = names.settle(106);
		assertEquals("axe", named.get(0).name);
		assertEquals(14, named.get(0).avoidable, 0);
		assertEquals(1, named.size());
		final AttackClassifier.Named bleed = only(names.settle(108));
		assertEquals("bleed", bleed.name);
		assertEquals(3, bleed.avoidable, 0);
		assertEquals(Integer.valueOf(14), names.damageByClass().get("axe"));
		assertEquals(Integer.valueOf(3), names.damageByClass().get("bleed"));
	}

	/** The gaze switches overheads off as it lands; its 4-6 splat comes a tick later, healing him nothing. */
	@Test
	public void theGazeIsTheHead()
	{
		final AttackClassifier names = classifier();
		names.onHit(100, 6, HIT, null, gazeLanded(1));
		final AttackClassifier.Named named = only(names.settle(106));
		assertEquals("head", named.name);
		assertEquals(6, named.avoidable, 0);
	}

	@Test
	public void aSpikeFollowsTheDashAndHealsHim()
	{
		final AttackClassifier names = classifier();
		names.onHit(100, 23, HIT, "melee", anim(DASH, 2));
		names.onBossHeal(101, 11);
		final AttackClassifier.Named named = only(names.settle(106));
		assertEquals("spike", named.name);
		assertEquals("strong: dash before", named.evidence);
		assertEquals(23, named.avoidable, 0);
	}

	/** No dash seen, but it healed him and is more than his melee gets through prayer: a spike, inferred. */
	@Test
	public void aBigHealedHitWithNoDashIsASpike()
	{
		final AttackClassifier names = classifier();
		names.onHit(100, 25, HIT, "melee", Collections.emptyList());
		names.onBossHeal(100, 12);
		final AttackClassifier.Named named = only(names.settle(106));
		assertEquals("spike", named.name);
		assertEquals("inferred: heals, no dash seen", named.evidence);
	}

	/** His attack while the strangle runs plays no melee animation of its own. */
	@Test
	public void meleeDuringTheStrangle()
	{
		final AttackClassifier names = classifier();
		names.onHit(100, 6, HIT, "melee", anim(STRANGLE_LOOP, 3));
		names.onBossHeal(100, 3);
		assertEquals("melee", only(names.settle(106)).name);
	}

	/** Two attacks on one tick, an axe and a melee: the one heal is the melee's, the bleed after is the axe's. */
	@Test
	public void anAxeAndAMeleeOnOneTick()
	{
		for (boolean axeFirst : new boolean[]{true, false})
		{
			final AttackClassifier names = classifier();
			if (axeFirst)
			{
				names.onHit(100, 14, HIT, "melee", anim(MELEE, 1));
				names.onHit(100, 8, HIT, "melee", anim(MELEE, 1));
			}
			else
			{
				names.onHit(100, 8, HIT, "melee", anim(MELEE, 1));
				names.onHit(100, 14, HIT, "melee", anim(MELEE, 1));
			}
			names.onBossHeal(100, 4);
			names.onHit(102, 3, BLEED, "melee", Collections.emptyList());
			final List<AttackClassifier.Named> named = names.settle(106);
			assertEquals(2, named.size());
			final AttackClassifier.Named axe = named.get(axeFirst ? 0 : 1);
			final AttackClassifier.Named melee = named.get(axeFirst ? 1 : 0);
			assertEquals("axe", axe.name);
			assertEquals(14, axe.avoidable, 0);
			assertEquals("melee", melee.name);
			assertEquals(0, melee.avoidable, 0);
		}
	}

	/** The gaze and an axe on one tick, as Eric's 16:03 [3544]: the small one is the gaze. */
	@Test
	public void theGazeAndAnAxeOnOneTick()
	{
		final AttackClassifier names = classifier();
		final List<TargetActions.Action> seen = new ArrayList<>(Arrays.asList(
			TargetActions.Action.animation(MELEE, -4), TargetActions.Action.projectile(GAZE, -3, -1)));
		names.onHit(100, 6, HIT, null, seen);
		names.onHit(100, 29, HIT, null, seen);
		names.onHit(102, 3, BLEED, null, Collections.emptyList());
		final List<AttackClassifier.Named> named = names.settle(106);
		assertEquals("head", named.get(0).name);
		assertEquals("axe", named.get(1).name);
		assertEquals(35, names.avoidable(), 0);
	}

	/** One heal pays for one hit. */
	@Test
	public void aHealIsUsedOnce()
	{
		final AttackClassifier names = classifier();
		names.onHit(100, 25, HIT, "melee", Collections.emptyList());
		names.onHit(100, 25, HIT, "melee", Collections.emptyList());
		names.onBossHeal(100, 12);
		final List<AttackClassifier.Named> named = names.settle(106);
		assertEquals("spike", named.get(0).name);
		assertEquals(AttackClassifier.UNNAMED, named.get(1).name);
	}

	/** A hit that fits nothing is chip. */
	@Test
	public void anUnnamedHitIsChip()
	{
		final AttackClassifier names = classifier();
		names.onHit(100, 7, HIT, "melee", Collections.emptyList());
		final AttackClassifier.Named named = only(names.settle(106));
		assertEquals(AttackClassifier.UNNAMED, named.name);
		assertEquals(0, named.avoidable, 0);
		assertEquals(Integer.valueOf(7), names.damageByClass().get(AttackClassifier.UNNAMED));
	}

	/**
	 * At the fight's end the hits still waiting are named with what there is: an
	 * axe whose bleed has come is still an axe; one whose bleed never came, with no
	 * heal and no animation, is chip.
	 */
	@Test
	public void pendingHitsAreSettledAtTheEnd()
	{
		final AttackClassifier names = classifier();
		names.onHit(100, 14, HIT, "melee", Collections.emptyList());
		names.onHit(101, 3, BLEED, "melee", Collections.emptyList());
		names.onHit(103, 15, HIT, "melee", Collections.emptyList());
		assertTrue(names.settle(104).isEmpty());
		assertTrue(names.hasPending());
		final List<AttackClassifier.Named> named = names.settleAll();
		assertEquals(3, named.size());
		assertEquals("axe", named.get(0).name);
		assertEquals("bleed", named.get(1).name);
		assertEquals(AttackClassifier.UNNAMED, named.get(2).name);
		assertFalse(names.hasPending());
		assertEquals(17, names.avoidable(), 0);
	}

	/** A 0 changes nothing; it is named by the animation for the debug log only. */
	@Test
	public void zerosAreNamedByTheAnimation()
	{
		final AttackClassifier names = classifier();
		names.onHit(100, 0, ZERO, "melee", anim(MELEE, 1));
		names.onHit(100, 0, ZERO, "melee", Collections.emptyList());
		final List<AttackClassifier.Named> named = names.settle(106);
		assertEquals("melee zero", named.get(0).name);
		assertEquals("unnamed zero", named.get(1).name);
		assertTrue(names.damageByClass().isEmpty());
		assertEquals(0, names.avoidable(), 0);
	}

	/** A bleed's 3 would ask for a heal of 1; the boss heals off his hits only, so it takes none from a melee. */
	@Test
	public void aBleedTakesNoHeal()
	{
		final AttackClassifier names = classifier();
		names.onHit(100, 3, BLEED, "melee", Collections.emptyList());
		names.onHit(100, 3, HIT, "melee", anim(MELEE, 1));
		names.onBossHeal(100, 1);
		names.onHit(100, 25, HIT, "melee", Collections.emptyList());
		names.onBossHeal(100, 12);
		final List<AttackClassifier.Named> named = names.settle(106);
		assertEquals("bleed", named.get(0).name);
		assertEquals("melee", named.get(1).name);
		assertEquals("spike", named.get(2).name);
	}

	@Test
	public void rulesWithoutAVersionOrAttacksAreNotUsed()
	{
		assertNull(AttackClassifier.usable(null));
		final BossRules.AttacksOnPlayer none = new BossRules.AttacksOnPlayer();
		assertNull(AttackClassifier.usable(none));
		none.rulesVersion = 1;
		assertNull(AttackClassifier.usable(none));
	}
}
