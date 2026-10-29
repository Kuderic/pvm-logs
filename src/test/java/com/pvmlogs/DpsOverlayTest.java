package com.pvmlogs;

import static com.pvmlogs.LedgerRows.Colour.NEUTRAL;
import static com.pvmlogs.LedgerRows.Colour.WARNING;
import static com.pvmlogs.LedgerRows.FontRole.BODY;
import static com.pvmlogs.LedgerRows.FontRole.DETAIL;
import static com.pvmlogs.LedgerRows.FontRole.EMPHASIS;
import static com.pvmlogs.LedgerRows.FontRole.VALUE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.pvmlogs.LedgerRows.Row;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.config.Range;
import net.runelite.client.ui.FontManager;
import org.junit.Test;

/**
 * T-073: the loadout overlay becomes the DPS overlay. It names the monster,
 * puts the attack and its DPS on rows of their own, can always show, and with
 * "Always show" on goes idle (max hit and spec only) after the set time.
 */
public class DpsOverlayTest
{
	/** The {@code  vs} sub-row, straight under the attack (Eric, 2026-10-28). */
	private static Row vs(String monster)
	{
		final String left = " vs " + monster;
		return new Row(left, "", DETAIL, DETAIL, NEUTRAL, NEUTRAL, left);
	}

	private static Row attack(String name)
	{
		return new Row(name, "", EMPHASIS, VALUE, NEUTRAL, NEUTRAL, name);
	}

	private static Row main(String left, String right)
	{
		return new Row(left, right, EMPHASIS, VALUE, NEUTRAL, NEUTRAL);
	}

	private static Row d(String left, String right)
	{
		return new Row(left, right, DETAIL, DETAIL, NEUTRAL, NEUTRAL);
	}

	/** A config with "Always show DPS overlay" as given, the rest at their defaults. */
	private static PvmPerformanceConfig config(boolean bossesOnly, int holdSeconds)
	{
		return new PvmPerformanceConfig()
		{
			@Override
			public boolean overlayBossesOnly()
			{
				return bossesOnly;
			}

			@Override
			public int overlayHoldSeconds()
			{
				return holdSeconds;
			}
		};
	}

	// ---- the rows ----

	@Test
	public void inAFightItNamesTheAttackThenTheMonsterUnderItThenItsDps()
	{
		final LedgerRows.Loadout l = LedgerExamples.loadout();
		l.versus = "Vardorvis";
		assertEquals(Arrays.asList(attack("Abyssal tentacle"), vs("Vardorvis"), main("DPS", "5.5"), d(" max hit", "49"),
			d(" accuracy", "54%"), d(" spec", "50 max · 71%"), d(" spec damage", "17.8")), LedgerRows.loadout(l, false));
	}

	@Test
	public void theWarningStillHeadsIt()
	{
		final LedgerRows.Loadout l = LedgerExamples.loadout();
		l.versus = "Vardorvis";
		final List<Row> rows = LedgerRows.loadout(l, true);
		assertEquals(new Row(LedgerRows.WARNING_TEXT, "", BODY, BODY, WARNING, NEUTRAL), rows.get(0));
		assertEquals(attack("Abyssal tentacle"), rows.get(1));
		assertEquals(vs("Vardorvis"), rows.get(2));
	}

	@Test
	public void inAGroupedRoomItNamesThePartBeingFought()
	{
		final Fight dusk = new Fight("Dusk", NpcID.GARGBOSS_DUSK_PHASE4, 1, 500, 0L, null, 0);
		assertEquals("Grotesque Guardians", dusk.encounterName());
		assertEquals("Dusk", LoadoutOverlay.versus(dusk));
		assertNull(LoadoutOverlay.versus(null));
	}

	@Test
	public void idleOrBeforeAnyFightItsTheAttackMaxHitAndSpecAlone()
	{
		// What LoadoutOverlay.idleLoadout hands over, and what the plugin's figures are with no monster.
		final LedgerRows.Loadout l = new LedgerRows.Loadout();
		l.attack = "Abyssal tentacle (or)";
		l.maxHit = 46;
		l.specMaxHit = 48;
		assertEquals(Arrays.asList(attack("Abyssal tentacle"), d(" max hit", "46"), d(" spec", "48 max")),
			LedgerRows.loadout(l, false));
		// A weapon with no special: the attack and its max hit.
		final LedgerRows.Loadout bow = new LedgerRows.Loadout();
		bow.attack = "Twisted bow";
		bow.maxHit = 70;
		assertEquals(Arrays.asList(attack("Twisted bow"), d(" max hit", "70")), LedgerRows.loadout(bow, false));
	}

	@Test
	public void noFigureNoBox()
	{
		final LedgerRows.Loadout l = new LedgerRows.Loadout();
		l.versus = "Vardorvis";
		assertTrue(LedgerRows.loadout(l, true).isEmpty());
	}

	// ---- always show ----

	@Test
	public void alwaysShowIgnoresBossesOnlyAndTheHold()
	{
		final Fight f = new Fight("Goblin", NpcIds.GOBLIN, 1, 5, 0L, null, 0);
		f.end(true, 1_000L);
		final long later = 1_000L + 3_600_000L;
		assertFalse(LoadoutOverlay.shows(f, false, config(false, 60), later));
		assertFalse(LoadoutOverlay.shows(f, false, config(true, 0), 1_001L));
		assertTrue(LoadoutOverlay.shows(f, true, config(false, 60), later));
		assertTrue(LoadoutOverlay.shows(f, true, config(true, 60), later));
		// Before any fight too.
		assertFalse(LoadoutOverlay.shows(null, false, config(false, 60), 0L));
		assertTrue(LoadoutOverlay.shows(null, true, config(false, 60), 0L));
	}

	// ---- going idle ----

	@Test
	public void idleAfterTheSetTimeSinceTheLastAttack()
	{
		// 60 s is 100 ticks.
		assertFalse(LoadoutOverlay.idle(1_099, 1_000, 60));
		assertTrue(LoadoutOverlay.idle(1_100, 1_000, 60));
		assertFalse(LoadoutOverlay.idle(1_009, 1_000, 6));
		assertTrue(LoadoutOverlay.idle(1_010, 1_000, 6));
		// No attack seen yet.
		assertTrue(LoadoutOverlay.idle(5, Integer.MIN_VALUE, 60));
	}

	@Test
	public void zeroNeverSimplifies()
	{
		assertFalse(LoadoutOverlay.idle(1_000_000, 0, 0));
		assertFalse(LoadoutOverlay.idle(5, Integer.MIN_VALUE, 0));
	}

	@Test
	public void theNextAttackBringsTheFiguresBack()
	{
		final Fight f = new Fight("Vardorvis", NpcIds.GOBLIN, 1, 700, 0L, null, 0);
		f.end(true, 1_000L);
		assertTrue(LoadoutOverlay.idleView(f, true, 1_200, 1_000, 60));
		// An attack on tick 1,200 is the last attack now.
		assertFalse(LoadoutOverlay.idleView(f, true, 1_200, 1_200, 60));
	}

	@Test
	public void idleOnlyWithAlwaysShowAndAfterAFight()
	{
		final Fight f = new Fight("Vardorvis", NpcIds.GOBLIN, 1, 700, 0L, null, 0);
		assertFalse(LoadoutOverlay.idleView(f, false, 1_200, 1_000, 60));
		// Before any fight the plugin's figures already have no monster: no idle view needed.
		assertFalse(LoadoutOverlay.idleView(null, true, 1_200, 1_000, 60));
	}

	@Test
	public void theIdleSettingIsBoundedLikeTheHold() throws NoSuchMethodException
	{
		final Range range = PvmPerformanceConfig.class.getMethod("overlayDpsIdleSeconds").getAnnotation(Range.class);
		assertEquals(0, range.min());
		assertEquals(600, range.max());
	}

	// ---- a narrow saved width ----

	@Test
	public void aNarrowSavedWidthCutsTheNamesNotTheFigures()
	{
		final Graphics2D g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
		g.setFont(FontManager.getRunescapeFont());
		final LedgerRows.Loadout l = LedgerExamples.loadout();
		l.attack = "Toxic blowpipe";
		l.versus = "Eldric the Fire Queen";
		final List<Row> rows = LedgerRows.loadout(l, false);
		final PvmPerformanceOverlay.Lines full = PvmPerformanceOverlay.lines(g, rows, null);
		final PvmPerformanceOverlay.Lines narrow = PvmPerformanceOverlay.lines(g, rows, new Dimension(110, 0));
		assertNotEquals(full.rows.get(1).left, narrow.rows.get(1).left);
		assertTrue(narrow.rows.get(1).left.startsWith(" vs "));
		assertTrue(narrow.rows.get(1).left.endsWith("…"));
		// The figures are as they were.
		for (int i = 2; i < rows.size(); i++)
		{
			assertEquals(full.rows.get(i).right, narrow.rows.get(i).right);
		}
	}

	// ---- the fonts: bold labels, regular figures ----

	@Test
	public void theFiguresAreRegularBesideBoldLabelsInEveryFont()
	{
		final Font regular = FontManager.getRunescapeFont();
		final Font bold = FontManager.getRunescapeBoldFont();
		final Font small = FontManager.getRunescapeSmallFont();
		final PvmPerformanceOverlay.FontRoles inRegular = PvmPerformanceOverlay.FontRoles.of(regular);
		assertEquals(bold, inRegular.get(EMPHASIS));
		assertEquals(regular, inRegular.get(VALUE));
		final PvmPerformanceOverlay.FontRoles inBold = PvmPerformanceOverlay.FontRoles.of(bold);
		assertEquals(bold, inBold.get(EMPHASIS));
		assertEquals(regular, inBold.get(VALUE));
		final PvmPerformanceOverlay.FontRoles inSmall = PvmPerformanceOverlay.FontRoles.of(small);
		assertEquals(small, inSmall.get(VALUE));
		final Font other = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
		assertFalse(PvmPerformanceOverlay.FontRoles.of(other).get(VALUE).isBold());
		assertTrue(PvmPerformanceOverlay.FontRoles.of(other).get(EMPHASIS).isBold());
	}
}
