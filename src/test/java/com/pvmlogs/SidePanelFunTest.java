package com.pvmlogs;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** T-186: the prototype's celebration facts: rewards from execution, luck reported as luck. */
public class SidePanelFunTest
{
	@Test
	public void underTenKillsTheGradeReadsFixedBands()
	{
		final double[] few = {0.9, 0.95, 0.97};
		assertEquals(SidePanelFun.Grade.S, SidePanelFun.grade(0.996, few));
		assertEquals(SidePanelFun.Grade.A, SidePanelFun.grade(0.98, few));
		assertEquals(SidePanelFun.Grade.B, SidePanelFun.grade(0.92, few));
		assertEquals(SidePanelFun.Grade.C, SidePanelFun.grade(0.85, few));
	}

	@Test
	public void fromTenKillsItReadsYourOwnSpread()
	{
		// A boss whose kills all sit low (Cerberus's uptime): your best is an S, not a C as the fixed bands would say.
		final double[] cerberus = new double[20];
		for (int i = 0; i < cerberus.length; i++)
		{
			cerberus[i] = 0.70 + i * 0.01;
		}
		assertEquals(SidePanelFun.Grade.S, SidePanelFun.grade(0.90, cerberus));
		assertEquals(SidePanelFun.Grade.C, SidePanelFun.grade(0.70, cerberus));
		assertEquals("top 20% of last 20", SidePanelFun.basis(SidePanelFun.share(0.855, cerberus), 20));
		assertEquals("bottom 3% of last 20", SidePanelFun.basis(SidePanelFun.share(0.70, cerberus), 20));
		assertEquals("best of last 20", SidePanelFun.basis(1, 20));
	}

	@Test
	public void theAraxxorKillIsAnAAndItsLuckEarnsNothing()
	{
		final SidePanelFun.Facts f = SidePanelFunPictures.facts(SidePanelFunPictures.araxxorKill(),
			SidePanelFunPictures.araxxorPast(), 555, false);
		assertEquals(86.4, f.clock, 1e-9);
		assertEquals(0.987, f.execution, 0.001);
		assertEquals(SidePanelFun.Grade.A, f.grade);
		assertEquals(65.4, f.pb, 1e-9);
		assertFalse(f.newPb);
		assertEquals(199, f.ranked);
		// Luck: the 80th percentile, about 10 s saved. Reported, never a badge.
		assertEquals(0.81, f.luckPercentile, 0.01);
		assertEquals(9.85, f.luckSeconds, 0.01);
		assertTrue(f.badges.toString(), f.badges.isEmpty());
		assertEquals("uptime", f.tipPart);
		assertEquals(1, f.streak);
	}

	@Test
	public void aThirdAInARowAndTheHundredthKillAreBadges()
	{
		final SidePanelFun.Past p = SidePanelFunPictures.araxxorPast();
		final SidePanelFun.Facts f = SidePanelFunPictures.facts(SidePanelFunPictures.araxxorKill(),
			new SidePanelFun.Past(p.clocks, p.luckRemoved, p.executions, 2), 600, false);
		assertEquals(3, f.streak);
		assertTrue(f.badges.contains("3 A's in a row"));
		assertTrue(f.badges.contains("600 kills"));
	}

	@Test
	public void liveThereIsNoPbRankOrBadge()
	{
		final SidePanelFun.Facts f = SidePanelFunPictures.facts(SidePanelFunPictures.araxxorLive(),
			SidePanelFunPictures.araxxorPast(), 0, true);
		assertTrue(f.live);
		assertFalse(f.newPb);
		assertEquals(0, f.ranked);
		assertTrue(f.badges.isEmpty());
	}
}
