package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.gameval.AnimationID;
import org.junit.Test;

/**
 * T-066: a shadow spell cast at the Leviathan is seen, booked and spent, so the
 * bow shot after it is the bow's; the stun opens from it, timed from its
 * landing; and a window's close is logged once.
 *
 * <p>The sequence is the brief's, rebuilt (no log lines copied): a bow shot
 * lands, Shadow Rush is clicked and cast, the bow fires the next tick and its
 * projectile surfaces two ticks later. Before the fix the click stayed in hand
 * for {@code MANUAL_CAST_TICKS}, so that projectile was scored as the spell (max
 * 14) and a 42 broke it.
 */
public class ShadowCastStunTest
{
	private static final BossRules RULES = BossRules.load(new Gson(), null);
	private static final int LEVI = 12214;
	private static final int INDEX = 61375;
	private static final int ZAROS = AnimationID.ZAROS_CASTING_WALKMERGE;
	private static final int ZAROS_VERTICAL = AnimationID.ZAROS_VERTICAL_CASTING_WALKMERGE;
	private static final List<Spell> SHADOW = Arrays.asList(Spell.SHADOW_RUSH, Spell.SHADOW_BURST, Spell.SHADOW_BLITZ,
		Spell.SHADOW_BARRAGE);

	private static BossRules.Boss levi()
	{
		return RULES.forNpc(LEVI);
	}

	/** The plugin's route for one animation: whether it books the manual cast in hand, and spends it if so. */
	private static boolean castAnimation(CombatCalc.ManualCast hand, int animation, BossRules.Boss boss, int now)
	{
		final Spell manual = hand.active(now);
		final boolean stuns = PvmPerformancePlugin.startsAState(boss, manual);
		if (!PvmPerformancePlugin.isAncientCast(animation, manual) || !PvmPerformancePlugin.booksFromAnimation(manual, stuns))
		{
			return false;
		}
		// Booked as a cast (never melee-proven), and spent at that booking.
		hand.attackThrown(false, now);
		return true;
	}

	/** The plugin's projectile route: a stun spell still in hand is dropped, then the projectile is booked and spends. */
	private static Spell projectile(CombatCalc.ManualCast hand, BossRules.Boss boss, int now)
	{
		final Spell manual = hand.active(now);
		if (manual != null && PvmPerformancePlugin.startsAState(boss, manual))
		{
			hand.drop();
		}
		final Spell scoredAs = hand.active(now);
		hand.attackThrown(false, now);
		return scoredAs;
	}

	@Test
	public void theCastIsSpentAndTheBowShotIsTheBows()
	{
		final CombatCalc.ManualCast hand = new CombatCalc.ManualCast();
		hand.record(Spell.SHADOW_RUSH, 464);
		assertTrue(castAnimation(hand, ZAROS, levi(), 465));
		assertNull(hand.active(465));
		// The bow fired on 466 and surfaced on 468: nothing in hand, so it is scored as the bow (max 44 in the brief's kill).
		assertNull(projectile(hand, levi(), 468));
	}

	@Test
	public void beforeTheFixTheClickStayedInHand()
	{
		// No booking from the animation, and no drop: what master did. The projectile four ticks after the click is the spell.
		final CombatCalc.ManualCast hand = new CombatCalc.ManualCast();
		hand.record(Spell.SHADOW_RUSH, 464);
		assertEquals(Spell.SHADOW_RUSH, hand.active(468));
	}

	@Test
	public void anUnseenCastAnimationStillLeavesTheBowShotTheBows()
	{
		// Whatever animation the client plays, a projectile is never a shadow spell at the Leviathan.
		final CombatCalc.ManualCast hand = new CombatCalc.ManualCast();
		hand.record(Spell.SHADOW_RUSH, 464);
		assertFalse(castAnimation(hand, 9999, levi(), 465));
		assertNull(projectile(hand, levi(), 468));
		// Clicked twice: both are gone.
		hand.record(Spell.SHADOW_BLITZ, 500);
		hand.record(Spell.SHADOW_BLITZ, 500);
		assertTrue(castAnimation(hand, ZAROS, levi(), 501));
		assertNull(projectile(hand, levi(), 504));
	}

	@Test
	public void everyShadowSpellIsSeenAndStuns()
	{
		for (Spell spell : SHADOW)
		{
			for (int animation : new int[]{AnimationID.ZAROS_CASTING, AnimationID.ZAROS_VERTICAL_CASTING, ZAROS,
				ZAROS_VERTICAL})
			{
				final CombatCalc.ManualCast hand = new CombatCalc.ManualCast();
				hand.record(spell, 100);
				assertTrue(spell + " " + animation, castAnimation(hand, animation, levi(), 101));
				assertNull(hand.active(101));
			}
			assertTrue(spell.name(), PvmPerformancePlugin.startsAState(levi(), spell));
		}
		// The rush and blitz deal nothing at the Leviathan; the burst and barrage are booked as the area spells they are.
		assertTrue(PvmPerformancePlugin.isMechanicCast(Spell.SHADOW_RUSH, true));
		assertTrue(PvmPerformancePlugin.isMechanicCast(Spell.SHADOW_BLITZ, true));
		assertFalse(PvmPerformancePlugin.isMechanicCast(Spell.SHADOW_BURST, true));
		assertFalse(PvmPerformancePlugin.isMechanicCast(Spell.SHADOW_BARRAGE, true));
		// Not at a boss it doesn't stun.
		assertFalse(PvmPerformancePlugin.startsAState(RULES.forNpc(5889), Spell.SHADOW_RUSH));
		assertFalse(PvmPerformancePlugin.isMechanicCast(Spell.SHADOW_RUSH, false));
	}

	@Test
	public void anAutocastStaffIsUnchanged()
	{
		// No click in hand: the manual route books nothing and the autocast route goes on as before.
		final CombatCalc.ManualCast hand = new CombatCalc.ManualCast();
		assertFalse(castAnimation(hand, ZAROS_VERTICAL, levi(), 200));
		assertFalse(PvmPerformancePlugin.isAncientCast(ZAROS_VERTICAL, null));
		assertFalse(PvmPerformancePlugin.booksFromAnimation(null, false));
	}

	@Test
	public void aManualIceBarrageIsUnchanged()
	{
		// Booked from its animation as an area spell, spent, never a stun nor a mechanic.
		final CombatCalc.ManualCast hand = new CombatCalc.ManualCast();
		hand.record(Spell.ICE_BARRAGE, 300);
		assertFalse(PvmPerformancePlugin.startsAState(levi(), Spell.ICE_BARRAGE));
		assertTrue(castAnimation(hand, ZAROS_VERTICAL, levi(), 301));
		assertFalse(PvmPerformancePlugin.isMechanicCast(Spell.ICE_BARRAGE, false));
		// A spell with a projectile is left to the projectile, which spends it.
		hand.record(Spell.SMOKE_RUSH, 400);
		assertFalse(castAnimation(hand, ZAROS, levi(), 401));
		assertEquals(Spell.SMOKE_RUSH, projectile(hand, levi(), 402));
		assertNull(hand.active(402));
		// A standard spell's animation is not an ancient cast.
		hand.record(Spell.FIRE_SURGE, 500);
		assertFalse(PvmPerformancePlugin.isAncientCast(ZAROS, Spell.FIRE_SURGE));
	}

	@Test
	public void theStunOpensOnTheCastsLandingAndTheWoundShotGetsItsMinimum()
	{
		final List<String> lines = new ArrayList<>();
		final ModifierTracker tracker = new ModifierTracker(lines::add);
		final BossRules.DamageModifierRules rules = levi().damageModifiers;
		// Cast on 465 from five tiles: it lands on the hit delay, which is when the stun starts.
		final int lands = 465 + PvmPerformancePlugin.magicHitDelay(5);
		assertEquals(469, lands);
		tracker.onPlayerSpell(INDEX, "The Leviathan", rules, Spell.SHADOW_RUSH.name(), lands);
		assertEquals("MOD The Leviathan: stunned on at tick 469 (your SHADOW_RUSH, for 15 ticks)", lines.get(0));
		final ModifierTracker.InsideCheck nowhere = (id, range) -> false;
		assertTrue(tracker.applying(INDEX, rules, LEVI, "RANGED", lands - 1, true, nowhere).isEmpty());
		// A bow shot into the wound landing within the 15 ticks: it cannot miss and hits at least 0.65 of a 44 max.
		for (int landing = lands; landing < lands + 15; landing++)
		{
			final List<BossRules.ModifierRule> wound = tracker.applying(INDEX, rules, LEVI, "RANGED", landing, true, nowhere);
			assertEquals(Collections.singletonList("into the wound"), names(wound));
			assertEquals(28, ModifierTracker.damage(wound, 0, 44, true));
			assertEquals(1.0, ModifierTracker.accuracy(wound, 0.4), 1e-12);
		}
		assertTrue(tracker.applying(INDEX, rules, LEVI, "RANGED", lands + 15, true, nowhere).isEmpty());
		// From the front it is capped at 10.
		assertEquals(10, ModifierTracker.max(tracker.applying(INDEX, rules, LEVI, "RANGED", lands + 3, false, nowhere), 44));
	}

	@Test
	public void aWindowsCloseIsLoggedOnce()
	{
		final List<String> lines = new ArrayList<>();
		final ModifierTracker tracker = new ModifierTracker(lines::add);
		final BossRules.DamageModifierRules rules = levi().damageModifiers;
		tracker.onBossAnim(INDEX, "The Leviathan", rules, 10285, 200);
		tracker.onBossAnim(INDEX, "The Leviathan", rules, 10287, 240);
		tracker.tick(240);
		tracker.tick(241);
		assertEquals(2, lines.size());
		assertTrue(lines.get(1), lines.get(1).startsWith("MOD The Leviathan: special off at tick 240 (anim 10287"));
		// One that runs out is still logged, once.
		tracker.onBossAnim(INDEX, "The Leviathan", rules, 10288, 300);
		tracker.tick(360);
		tracker.tick(361);
		assertEquals(4, lines.size());
		assertTrue(lines.get(3), lines.get(3).contains("special off at tick 360 (ran out"));
	}

	@Test
	public void theStunCastFillsItsTicks()
	{
		// The bow's last shot was thrown on 461 at speed 4, so the next attack is due on 465; the stun goes out on 465 and the
		// bow's next shot, thrown on 466, is booked when it surfaces on 468. Holding the bow, a booking trails by two.
		final int lag = PvmPerformancePlugin.pendingBookingLag(false, false);
		// Unbooked, as on master: 467 read as lost (the logs' "tick LOST: overdue since 171 + lag 2", on the same pattern).
		int lostBefore = 0;
		for (int tick = 466; tick < 468; tick++)
		{
			lostBefore += PvmPerformancePlugin.attackOverdue(tick, 465, lag) ? 1 : 0;
		}
		assertEquals(1, lostBefore);
		// Booked on its own tick, and the stun resets the attack delay (T-078): due 466, the tick after. The bow's shot thrown
		// on 466 books on 468, so nothing is lost before it.
		final int due = 465 + PvmPerformancePlugin.bookedSpeed(true, false, 5, 5);
		assertEquals(466, due);
		int lostAfter = 0;
		for (int tick = 466; tick < 468; tick++)
		{
			lostAfter += PvmPerformancePlugin.attackOverdue(tick, due, lag) ? 1 : 0;
		}
		assertEquals(0, lostAfter);
		// Waiting instead: ticks are lost from the tick after the cast plus the booking lag, as after any attack.
		assertFalse(PvmPerformancePlugin.attackOverdue(467, due, lag));
		assertTrue(PvmPerformancePlugin.attackOverdue(468, due, lag));
	}

	private static List<String> names(List<BossRules.ModifierRule> mods)
	{
		final List<String> out = new ArrayList<>();
		for (BossRules.ModifierRule m : mods)
		{
			out.add(m.name);
		}
		return out;
	}
}
