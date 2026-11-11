package com.pvmlogs;

import static com.pvmlogs.LedgerRows.Colour.MUTED;
import static com.pvmlogs.LedgerRows.Colour.NEUTRAL;
import static com.pvmlogs.LedgerRows.FontRole.DETAIL;
import static com.pvmlogs.LedgerRows.FontRole.EMPHASIS;
import static com.pvmlogs.LedgerRows.FontRole.VALUE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.pvmlogs.LedgerRows.Row;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.client.ui.FontManager;
import org.junit.Test;

/**
 * T-212: the DPS overlay's max hit is the attack's own; a bolt's proc or the keris's crit, which sets the top of the
 * attack's damage above it, gets a muted row of its own with its chance and its hit (its rule with no target). Eric,
 * 2026-11-11: "Dps infobox shows max hit 100 for ruby bolts but this is only on the special proc". His log of that day
 * at the Alchemical Hydra: {@code max 100} at 812 hp left with a crossbow whose own max-hit splats read 54.
 */
public class MaxHitProcTest
{
	private static final double DIARY = EnchantedBolt.RUBY.chance(true);

	private static MaxHitProc ruby(int hit)
	{
		return new MaxHitProc("ruby", DIARY, false, hit, EnchantedBolt.RUBY.rule());
	}

	private static Row attack(String name)
	{
		return new Row(name, "", EMPHASIS, VALUE, NEUTRAL, NEUTRAL, name);
	}

	private static Row d(String left, String right)
	{
		return new Row(left, right, DETAIL, DETAIL, NEUTRAL, NEUTRAL);
	}

	private static Row muted(String left, String right, String name)
	{
		return new Row(left, right, DETAIL, DETAIL, MUTED, MUTED, name);
	}

	private static LedgerRows.Loadout hydra(int top, int own, List<MaxHitProc> procs)
	{
		final LedgerRows.Loadout l = new LedgerRows.Loadout();
		l.attack = "Dragon hunter crossbow (t)";
		l.dps = 9.88;
		l.maxHit = top;
		l.ownMaxHit = own;
		l.procs = procs;
		l.accuracy = 0.903;
		return l;
	}

	@Test
	public void rubyAtFullHealthIsTheCrossbowsOwnMaxAndAProcOf100()
	{
		// A fifth of 1100 is 220, capped at 100: the top is the proc's, the attack's own is the crossbow's.
		assertEquals(54, CombatCalc.boltMax(EnchantedBolt.RUBY, CombatCalc.MaxPart.OWN, 54, 1100, 0));
		assertEquals(100, CombatCalc.boltMax(EnchantedBolt.RUBY, CombatCalc.MaxPart.PROC, 54, 1100, 0));
		assertEquals(100, CombatCalc.boltMax(EnchantedBolt.RUBY, CombatCalc.MaxPart.TOP, 54, 1100, 0));
		// Its chance: 6%, a tenth more with the hard Kandarin diary.
		assertEquals("6.6%", ruby(100).chanceLabel());
		assertEquals("6%", new MaxHitProc("ruby", EnchantedBolt.RUBY.chance(false), false, 100, "20% hp").chanceLabel());
		assertEquals(Arrays.asList(attack("Dragon hunter crossbow"), new Row("DPS", "9.9", EMPHASIS, VALUE, NEUTRAL,
			NEUTRAL), d(" max hit", "54"), muted(" ruby proc 6.6%", "100", " ruby"), d(" accuracy", "90%")),
			LedgerRows.loadout(hydra(100, 54, Collections.singletonList(ruby(100))), false));
		// The log line: the own max, then the proc.
		assertEquals("max 54 (ruby proc 100 at 6.6%)", CombatCalc.maxHitLabel(54, Collections.singletonList(ruby(100))));
		assertEquals("max 54", CombatCalc.maxHitLabel(54, Collections.emptyList()));
	}

	@Test
	public void lateInTheFightTheProcFollowsHerHealth()
	{
		// 300 hp left: a proc of 60, under the crossbow's 64, which is the top now.
		assertEquals(60, CombatCalc.boltMax(EnchantedBolt.RUBY, CombatCalc.MaxPart.PROC, 64, 300, 0));
		assertEquals(64, CombatCalc.boltMax(EnchantedBolt.RUBY, CombatCalc.MaxPart.TOP, 64, 300, 0));
		final List<Row> rows = LedgerRows.loadout(hydra(64, 64, Collections.singletonList(ruby(60))), false);
		assertEquals(d(" max hit", "64"), rows.get(2));
		assertEquals(muted(" ruby proc 6.6%", "60", " ruby"), rows.get(3));
	}

	@Test
	public void idleTheRubyShowsItsRule()
	{
		// No monster: a fifth of its health has nothing to be a fifth of.
		final LedgerRows.Loadout l = new LedgerRows.Loadout();
		l.attack = "Dragon hunter crossbow (t)";
		l.maxHit = 54;
		l.ownMaxHit = 54;
		l.procs = Collections.singletonList(ruby(-1));
		assertEquals(Arrays.asList(attack("Dragon hunter crossbow"), d(" max hit", "54"),
			muted(" ruby proc 6.6%", "20% hp", " ruby")), LedgerRows.loadout(l, false));
		assertEquals("ruby proc 20% hp at 6.6%", ruby(-1).logLabel());
	}

	@Test
	public void diamondOnyxAndDragonstoneRows()
	{
		// Diamond: 15% more on the proc; onyx 20%; dragonstone a fifth of the visible Ranged level on top.
		assertEquals(62, CombatCalc.boltMax(EnchantedBolt.DIAMOND, CombatCalc.MaxPart.PROC, 54, 0, 0));
		assertEquals(64, CombatCalc.boltMax(EnchantedBolt.ONYX, CombatCalc.MaxPart.PROC, 54, 0, 0));
		assertEquals(54 + 22, CombatCalc.boltMax(EnchantedBolt.DRAGONSTONE, CombatCalc.MaxPart.PROC, 54, 0, 112));
		assertEquals(54, CombatCalc.boltMax(EnchantedBolt.DIAMOND, CombatCalc.MaxPart.OWN, 54, 0, 0));
		final MaxHitProc diamond = new MaxHitProc(EnchantedBolt.DIAMOND.shortName(), EnchantedBolt.DIAMOND.chance(true),
			false, 62, EnchantedBolt.DIAMOND.rule());
		final MaxHitProc onyx = new MaxHitProc(EnchantedBolt.ONYX.shortName(), EnchantedBolt.ONYX.chance(true), false, 64,
			EnchantedBolt.ONYX.rule());
		assertEquals(muted(" diamond proc 11%", "62", " diamond"), LedgerRows.procRows(Collections.singletonList(diamond))
			.get(0));
		assertEquals(muted(" onyx proc 12.1%", "64", " onyx"), LedgerRows.procRows(Collections.singletonList(onyx))
			.get(0));
		assertEquals("dragonstone", EnchantedBolt.DRAGONSTONE.shortName());
		assertEquals("6%", MaxHitProc.percent(EnchantedBolt.DRAGONSTONE.chance(false)));
	}

	@Test
	public void theKerisCritAndTheZarytesEvoke()
	{
		// The keris's max multiplier has its crit's tripling in it (GearBonusCalc, 1.33 against a kalphite): the own max
		// takes it back out.
		assertEquals(53, CombatCalc.kerisOwnMax(40, 1.33 * 3.0));
		assertEquals((int) (40 * 1.33), CombatCalc.kerisOwnMax(40, 1.33 * 3.0));
		final MaxHitProc crit = new MaxHitProc("keris crit", 1.0 / 51, false, 159, "x3");
		assertEquals(muted(" keris crit 2%", "159", " keris crit"), LedgerRows.procRows(Collections.singletonList(crit))
			.get(0));
		assertEquals("keris crit 159 at 2%", crit.logLabel());
		// The Zaryte crossbow's special comes with the bolt's effect on every landed hit: "on hit", not a chance.
		final MaxHitProc evoke = new MaxHitProc("ruby", DIARY, true, 100, "20% hp");
		final LedgerRows.Loadout l = hydra(100, 54, Collections.singletonList(ruby(100)));
		l.specMaxHit = 100;
		l.specOwnMaxHit = 54;
		l.specProcs = Collections.singletonList(evoke);
		final List<Row> rows = LedgerRows.loadout(l, false);
		final int spec = rows.indexOf(new Row(LedgerRows.SPEC, "", EMPHASIS, VALUE, NEUTRAL, NEUTRAL));
		assertTrue(rows.toString(), spec > 0);
		assertEquals(d(" max hit", "54"), rows.get(spec + 1));
		assertEquals(muted(" ruby proc on hit", "100", " ruby"), rows.get(spec + 2));
	}

	@Test
	public void withNoProcTheMaxHitIsAsItWas()
	{
		// An own max of 0 (unknown) shows the top, as before T-212.
		final LedgerRows.Loadout l = LedgerExamples.loadout();
		final List<Row> before = LedgerRows.loadout(l, false);
		assertTrue(before.toString(), before.contains(d(" max hit", String.valueOf(l.maxHit))));
		assertTrue(LedgerRows.procRows(Collections.emptyList()).isEmpty());
		assertTrue(LedgerRows.procRows(null).isEmpty());
	}

	@Test
	public void theScoredDistributionIsUnchanged()
	{
		// The ruby's branch is weighed in as it always was, the proc's 100 at its chance and the crossbow's roll otherwise:
		// about the Hydra's expected 29.40 per attack at full health (Eric's log, max 54, acc 0.903, the diary's 6.6%).
		final DamageMoments ordinary = CombatCalc.ordinaryMoments(54, 0.903, 1, Integer.MAX_VALUE, true);
		final DamageMoments scored = CombatCalc.rubyMoments(DIARY, EnchantedBolt.rubyDamage(1100), ordinary);
		assertEquals(DIARY * 100 + (1 - DIARY) * ordinary.mean, scored.mean, 1e-12);
		assertEquals(DIARY * 100 * 100 + (1 - DIARY) * ordinary.second, scored.second, 1e-9);
		assertEquals(29.40, scored.mean, 0.02); // the log rounds its max and accuracy
		// The top is still the proc: what MAX EXCEEDED checks a hit against.
		assertEquals(100, CombatCalc.boltMax(EnchantedBolt.RUBY, CombatCalc.MaxPart.TOP, 54, 1100, 0));
	}

	@Test
	public void onABoxSavedNarrowTheLabelIsCutBeforeTheNumber()
	{
		final Graphics2D probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
		probe.setFont(FontManager.getRunescapeFont());
		final List<Row> rows = LedgerRows.loadout(hydra(100, 54, Collections.singletonList(ruby(100))), false);
		final PvmPerformanceOverlay.Lines narrow = PvmPerformanceOverlay.lines(probe, rows, new Dimension(100, 0));
		probe.dispose();
		final Row proc = narrow.rows.stream().filter(r -> r.leftColour == MUTED).findFirst().orElse(null);
		assertTrue(narrow.rows.toString(), proc != null);
		assertEquals("100", proc.right);
		assertTrue(proc.left, proc.left.startsWith(" ruby"));
		assertTrue(proc.left, proc.left.length() < " ruby proc 6.6%".length());
	}
}
