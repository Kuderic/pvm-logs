package com.pvmlogs;

import java.awt.Component;
import java.awt.Container;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JPanel;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.ui.laf.RuneLiteLAF;

/**
 * T-186's prototype renders: today's Fight tab, direction A (the kill card on top, the sections folded below) and
 * direction B (today's layout with a PB, grade and luck strip under the title), each mid-fight and just after a kill,
 * on the Vardorvis example and one of Eric's Araxxor kills. Test scope; nothing here ships.
 * <p>
 * {@code ./gradlew testClasses}, then this class's main with the output folder.
 */
final class SidePanelFunPictures
{
	private SidePanelFunPictures()
	{
	}

	// ---- the examples ----

	/**
	 * Eric's Araxxor kill at KC 555 (history.json, 2026-11-09; read in place): 1:26, 901 against 803.6 expected over a
	 * variance of 11,966, thrall 51; one idle tick and two of eating in 144; setup 99.38% (stat boost 0.61 of it).
	 * Execution 98.7%, luck the 80th percentile, 10 s saved. The room's three fights (Araxxor, a Mirrorback, Araxxor)
	 * are one scope here; the weapons are its own: a hammer spec, the tentacle (or), the sunlight crossbow on the add.
	 */
	static SidePanelModel.Input araxxorKill()
	{
		final LedgerExamples.FakeScope s = new LedgerExamples.FakeScope();
		s.combatTicks = 144;
		s.damage = 901;
		s.expectedDamage = 803.6;
		s.thrallDamage = 51;
		s.damageVariance = 11_966;
		s.ticksLost = 3;
		s.ticksLostEating = 2;
		s.damageTaken = 12;
		s.biggestHitTaken = 3;
		s.attacksMade = 35;
		s.attacksPrayed = 35;
		s.attacksSwitched = 35;
		s.hits = 30;
		s.expectedHits = 29.6;
		s.efficiency = 0.9938;
		s.boostShare = 0.95;
		s.boostCostShare = 0.0061;
		s.switchCostShare = 0.0001;
		final SidePanelModel.Input in = araxxor(s, araxxorPlan(32));
		in.ended = true;
		in.killed = true;
		in.average = new LedgerHistory.Average(new LedgerHistory.Spread(94.2, 88.8, 106.2), 20);
		in.luckRemoved = new LedgerHistory.Spread(92.3, 89.9, 102.5);
		return in;
	}

	/** The same kill 42 s in (illustrative: its first 70 ticks, the hammer, 14 tentacle attacks and the add). */
	static SidePanelModel.Input araxxorLive()
	{
		final LedgerExamples.FakeScope s = new LedgerExamples.FakeScope();
		s.combatTicks = 70;
		s.damage = 428;
		s.expectedDamage = 403.2;
		s.thrallDamage = 25;
		s.damageVariance = 5_000;
		s.ticksLost = 2;
		s.ticksLostEating = 0;
		s.damageTaken = 12;
		s.biggestHitTaken = 3;
		s.attacksMade = 17;
		s.attacksPrayed = 17;
		s.attacksSwitched = 17;
		s.hits = 14;
		s.expectedHits = 14.4;
		s.efficiency = 0.9930;
		s.boostShare = 0.95;
		s.boostCostShare = 0.0068;
		s.switchCostShare = 0.0002;
		final SidePanelModel.Input in = araxxor(s, araxxorPlan(14));
		in.average = new LedgerHistory.Average(new LedgerHistory.Spread(94.2, 88.8, 106.2), 20);
		return in;
	}

	private static SidePanelModel.Input araxxor(LedgerExamples.FakeScope s, SidePanelTimelineExamples.Built b)
	{
		final SidePanelModel.Input in = SidePanelTimelineExamples.input(b, false);
		in.scope = s;
		in.name = "Araxxor";
		in.scoredName = "Araxxor";
		in.maxHp = 1020;
		in.solo = true;
		in.scoredThrall = s.thrallDamage;
		in.hitsTaken = 4;
		in.endMillis = SidePanelExamples.at(20, 41);
		return in;
	}

	/** The kill's weapons, played in order; {@code tentacle} attacks of the tentacle in all (32 at the kill). */
	private static SidePanelTimelineExamples.Built araxxorPlan(int tentacle)
	{
		final int first = Math.min(18, tentacle);
		final SidePanelTimelineExamples.Stretch hammer = new SidePanelTimelineExamples.Stretch("Dragon warhammer",
			ItemID.DRAGON_WARHAMMER, FightTimeline.Style.MELEE, true, 6, new int[]{16}, 35.8, 64, 0.7, 0);
		final SidePanelTimelineExamples.Stretch tentA = new SidePanelTimelineExamples.Stretch("Abyssal tentacle (or)",
			ItemID.ABYSSAL_TENTACLE, FightTimeline.Style.MELEE, false, 4,
			SidePanelTimelineExamples.hits(first, 31, 27, 0, 38, 23, 30), 22.24, 42, 0.84, 2);
		final SidePanelTimelineExamples.Stretch xbow = new SidePanelTimelineExamples.Stretch("Hunters' sunlight crossbow",
			ItemID.HUNTING_CROSSBOW_SUNLIGHT, FightTimeline.Style.RANGED, false, 3, new int[]{28, 28}, 28, 28, 1.0, 0);
		if (tentacle <= first)
		{
			return SidePanelTimelineExamples.plan("Araxxor", hammer, tentA, xbow);
		}
		final SidePanelTimelineExamples.Stretch tentB = new SidePanelTimelineExamples.Stretch("Abyssal tentacle (or)",
			ItemID.ABYSSAL_TENTACLE, FightTimeline.Style.MELEE, false, 4,
			SidePanelTimelineExamples.hits(tentacle - first, 31, 35, 28, 0, 37, 26, 34), 22.24, 42, 0.84, 1);
		return SidePanelTimelineExamples.plan("Araxxor", hammer, tentA, xbow, tentB);
	}

	/**
	 * Eric's 198 Araxxor kills before KC 555, by their quantiles (history.json, 2026-11-09): clock 1:05 best, 1:35
	 * median; luck removed 1:12 best, 1:38 median; execution 82.4% to 99.9%, 96.6% median.
	 */
	static SidePanelFun.Past araxxorPast()
	{
		final double[] q = {0, 0.05, 0.1, 0.25, 0.5, 0.75, 0.9, 0.95, 1};
		return new SidePanelFun.Past(
			SidePanelFun.spread(198, anchors(q, 65.4, 78.6, 81.0, 88.8, 94.8, 106.2, 114.6, 120.6, 147.0)),
			SidePanelFun.spread(198, anchors(q, 72.1, 89.9, 91.3, 94.3, 97.9, 102.5, 108.6, 112.7, 127.6)),
			SidePanelFun.spread(198, anchors(q, .824, .886, .903, .939, .966, .981, .991, .995, .999)), 0);
	}

	/**
	 * The Vardorvis example's history (illustrative PB 1:43, usual 1:54), with Eric's 16 Vardorvis kills' execution
	 * spread: 90.0% to 99.5%, 96.6% median, two A or better in a row before this one.
	 */
	static SidePanelFun.Past vardorvisPast()
	{
		final double[] q = {0, 0.15, 0.5, 0.7, 0.95, 1};
		return new SidePanelFun.Past(
			SidePanelFun.spread(16, anchors(q, 103, 108, 114, 118, 128, 140)),
			SidePanelFun.spread(16, anchors(q, 108, 111, 117, 120, 126, 133)),
			SidePanelFun.spread(16, anchors(q, .900, .926, .966, .978, .986, .995)), 2);
	}

	private static double[][] anchors(double[] q, double... v)
	{
		final double[][] a = new double[q.length][];
		for (int i = 0; i < q.length; i++)
		{
			a[i] = new double[]{q[i], v[i]};
		}
		return a;
	}

	static SidePanelFun.Facts facts(SidePanelModel.Input in, SidePanelFun.Past past, int kc, boolean live)
	{
		return SidePanelFun.of(new LedgerRows.Numbers(in.scope), past, in.average, kc, null, live);
	}

	// ---- the panels ----

	/** Today's Fight tab on this input. */
	static PvmPerformancePanel today(SidePanelModel.Input in)
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		panel.apply(new PanelSnapshot(SidePanelModel.fight(in), null, SidePanelParty.party(new SidePanelParty.Input())));
		return panel;
	}

	/** The title block: the tab's first component. */
	static Component header(JComponent tab)
	{
		return tab.getComponent(0);
	}

	/** The four tiles' grid. */
	static Component tiles(JComponent tab)
	{
		for (Component c : tab.getComponents())
		{
			if (c instanceof JPanel && ((JPanel) c).getLayout() instanceof GridLayout
				&& ((GridLayout) ((JPanel) c).getLayout()).getColumns() == 2)
			{
				return c;
			}
		}
		throw new AssertionError("no tiles");
	}

	/**
	 * Direction A on today's panel: the kill card in place of the title and the tiles, every section folded to its
	 * title and one-line hint, Damage's left open mid-fight.
	 */
	static SidePanelFunCard.KillCard directionA(PvmPerformancePanel panel, String boss, String when,
		SidePanelFun.Facts f)
	{
		final JComponent tab = panel.fightTab();
		tab.remove(tiles(tab));
		tab.remove(header(tab));
		final SidePanelFunCard.KillCard card = new SidePanelFunCard.KillCard(boss, when, f);
		tab.add(card, 0);
		fold(tab, f.live ? "damage" : null);
		return card;
	}

	/** Direction B on today's panel: the strip under the title. */
	static SidePanelFunCard.Strip directionB(PvmPerformancePanel panel, String boss, SidePanelFun.Facts f)
	{
		final JComponent tab = panel.fightTab();
		final SidePanelFunCard.Strip strip = new SidePanelFunCard.Strip(boss, f);
		tab.add(strip, 1);
		return strip;
	}

	/** Every section opened: direction A's card without its folds, to tell their cost from the card's. */
	static void openAll(Container c)
	{
		for (Component k : c.getComponents())
		{
			if (k instanceof SidePanelSection)
			{
				if (((SidePanelSection) k).isFolded())
				{
					((SidePanelSection) k).toggle();
				}
			}
			else if (k instanceof Container)
			{
				openAll((Container) k);
			}
		}
	}

	private static void fold(Container c, String keep)
	{
		for (Component k : c.getComponents())
		{
			if (k instanceof SidePanelSection)
			{
				final SidePanelSection s = (SidePanelSection) k;
				if (s.id().equals(keep) == s.isFolded())
				{
					s.toggle();
				}
			}
			else if (k instanceof Container)
			{
				fold((Container) k, keep);
			}
		}
	}

	// ---- the renders ----

	public static void main(String[] args) throws IOException
	{
		final Path out = Paths.get(args.length > 0 ? args[0] : "build/fun-pass-renders");
		Files.createDirectories(out);
		RuneLiteLAF.setup();
		SidePanelStyle.setText(false, false);
		for (double scale : new double[]{1, 2})
		{
			// Vardorvis: the example's 1:13 live and 2:17 kill.
			final SidePanelFun.Past vp = vardorvisPast();
			render(out, "vardorvis-live", scale, SidePanelExamples.live(), vp, 0, "Vardorvis", "live");
			render(out, "vardorvis-kill", scale, SidePanelExamples.dead(), vp, 0, "Vardorvis", "3:31 pm");
			// Araxxor KC 555: 50 s in, and the kill.
			final SidePanelFun.Past ap = araxxorPast();
			render(out, "araxxor-live", scale, araxxorLive(), ap, 0, "Araxxor", "live");
			render(out, "araxxor-kill", scale, araxxorKill(), ap, 555, "Araxxor", "KC 555");
			// The count-up's frame at 55%, the grade chip flashing.
			final PvmPerformancePanel p = today(araxxorKill());
			final SidePanelFunCard.KillCard card = directionA(p, "Araxxor", "KC 555", facts(araxxorKill(), ap, 555,
				false));
			card.shown = 0.7;
			write(out, "araxxor-kill-A-count-up-frame", scale, SidePanelPictures.draw(p.tabs(), p.fightTab(), scale));
			// A new PB and badges, to show them: the same kill had it been 1:04, flawless, the 600th, a third A.
			final SidePanelFun.Facts best = facts(araxxorKill(), new SidePanelFun.Past(ap.clocks, ap.luckRemoved,
				ap.executions, 2), 600, false);
			best.newPb = true;
			best.clock = 64.2;
			best.rank = 1;
			best.badges.add(0, "Flawless");
			final PvmPerformancePanel q = today(araxxorKill());
			directionA(q, "Araxxor", "KC 600", best);
			write(out, "araxxor-kill-A-new-pb-badges", scale, SidePanelPictures.draw(q.tabs(), q.fightTab(), scale));
		}
	}

	private static void render(Path out, String name, double scale, SidePanelModel.Input in, SidePanelFun.Past past,
		int kc, String boss, String when) throws IOException
	{
		final boolean live = !in.ended;
		final SidePanelFun.Facts f = facts(in, past, kc, live);
		final PvmPerformancePanel now = today(in);
		write(out, name + "-0-today", scale, SidePanelPictures.draw(now.tabs(), now.fightTab(), scale));
		final PvmPerformancePanel a = today(in);
		directionA(a, boss, when, f);
		write(out, name + "-A-kill-card", scale, SidePanelPictures.draw(a.tabs(), a.fightTab(), scale));
		final PvmPerformancePanel b = today(in);
		directionB(b, boss, f);
		write(out, name + "-B-strip", scale, SidePanelPictures.draw(b.tabs(), b.fightTab(), scale));
	}

	private static void write(Path out, String name, double scale, BufferedImage img) throws IOException
	{
		final Path file = out.resolve(name + "@" + (int) scale + "x.png");
		try (OutputStream o = Files.newOutputStream(file))
		{
			ImageIO.write(img, "png", o);
		}
		System.out.println(file + " " + img.getWidth() + "x" + img.getHeight());
	}
}
