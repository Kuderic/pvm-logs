package com.pvmlogs;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import net.runelite.client.ui.laf.RuneLiteLAF;

/**
 * Not a test: draws every Fight-tab state (spec section 6, states 1 to 8) with
 * the real panel classes, RuneLite's own look and feel and fonts, at 1× and 2×,
 * for the PR (T-033 item 7). Like the overlay's {@code LedgerPictures} and
 * {@code Render.java}, but the panel is Swing, so it paints itself.
 * <pre>
 *   java -Djava.awt.headless=true -cp &lt;test classpath&gt; com.pvmlogs.SidePanelPictures &lt;out dir&gt;
 * </pre>
 * The sidebar is drawn 242 px wide: the tab bar across it, the Fight tab's
 * content in its 225 px, and RuneLite's 17 px scrollbar track beside it.
 */
public final class SidePanelPictures
{
	static final int SIDEBAR = 242;
	static final int SCROLLBAR = 17;

	private SidePanelPictures()
	{
	}

	public static void main(String[] args) throws IOException
	{
		final Path out = Paths.get(args.length > 0 ? args[0] : "build/side-panel-renders");
		Files.createDirectories(out);
		RuneLiteLAF.setup();
		if (args.length > 1 && "text".equals(args[1]))
		{
			textModes(out);
			return;
		}
		if (args.length > 2 && "icons".equals(args[1]))
		{
			bossIcons(out, Paths.get(args[2]));
			return;
		}
		for (int scale = 1; scale <= 2; scale++)
		{
			for (Map.Entry<String, SidePanelModel.Input> e : SidePanelExamples.states().entrySet())
			{
				write(out, e.getKey(), scale, render(e.getValue(), scale));
			}
			// T-034: states 9 to 11.
			write(out, "9-trip", scale, renderTrip(SidePanelExamples.trip(), scale));
			write(out, "10-history", scale, renderHistory(false, scale));
			write(out, "11-saved-kill", scale, renderHistory(true, scale));
			// T-036: a kill from this session opened from History, with its rows, chart and strip.
			write(out, "11b-saved-kill-this-session", scale, renderSavedThisSession(scale));
			// T-042: "Where the time went" with your real time ticked: luck saved 15 s, 2 s and 60 s, cost 7 s, and the
			// average marker on the bar.
			write(out, "12a-luck-saved-15s", scale, render(SidePanelExamples.luckSaved(15), scale));
			write(out, "12b-luck-saved-2s", scale, render(SidePanelExamples.luckSaved(2), scale));
			write(out, "12c-luck-saved-60s", scale, render(SidePanelExamples.luckSaved(60), scale));
			write(out, "12d-luck-slower", scale, render(SidePanelExamples.luckSaved(-7), scale));
			write(out, "12e-average-on-the-bar", scale, render(SidePanelExamples.luckSavedAverageOnTheBar(), scale));
			// T-044: a ruled kill (16:03 kill 4's figures), and a ruled kill opened from History.
			write(out, "13a-ruled-kill", scale, render(SidePanelExamples.ruledKill(true), scale));
			write(out, "13b-ruled-saved-kill", scale, renderRuledSavedKill(scale));
			// T-052: the Defence card, the live line and the trip's drains.
			write(out, "14a-two-hammers-one-landed", scale, render(SidePanelExamples.twoHammersOneLanded(), scale));
			write(out, "14b-bandos-godsword", scale, render(SidePanelExamples.bandosGodsword(), scale));
			write(out, "14c-at-its-floor", scale, render(SidePanelExamples.atItsFloor(), scale));
			write(out, "14d-immune", scale, render(SidePanelExamples.immune(), scale));
			write(out, "14e-live-defence", scale, render(SidePanelExamples.liveDrained(), scale));
			write(out, "14f-trip-drains", scale, renderTrip(SidePanelExamples.tripWithDrains(), scale));
			// T-063: damage over time, a line a kind.
			write(out, "15a-venom-dealt-poison-taken", scale, render(SidePanelExamples.venomDealtPoisonTaken(), scale));
			write(out, "15b-burn-claws", scale, render(SidePanelExamples.burnClaws(), scale));
			write(out, "15c-two-kinds-dealt", scale, render(SidePanelExamples.twoKindsDealt(), scale));
			write(out, "15d-old-kill", scale, render(SidePanelExamples.oldKill(), scale));
			write(out, "15e-trip-old-and-new", scale, renderTrip(SidePanelExamples.tripMixedOldAndNew(), scale));
			// T-068: the Damage taken card mid-fight (the total only) and after; the trip while a fight runs.
			write(out, "16a-taken-mid-fight", scale, render(SidePanelExamples.takenMidFight(), scale));
			write(out, "16b-taken-after", scale, render(SidePanelExamples.ruledKill(true), scale));
			write(out, "16c-trip-while-fighting", scale, renderTrip(SidePanelExamples.tripWhileFighting(), scale));
		}
	}

	/** T-053's text settings for the renders: both off draws exactly as before. */
	private static boolean larger;
	private static boolean smooth;

	/** The panel in the renders' text settings: built again in them, as the setting changing in game does. */
	private static PvmPerformancePanel styled(PvmPerformancePanel panel)
	{
		panel.setText(larger, smooth);
		return panel;
	}

	/**
	 * T-053 item 10: Fight live, after a kill, Trip and History in each of the four text settings, at 1×, 1.5× and 2×,
	 * each mode in a folder of its own.
	 */
	private static void textModes(Path out) throws IOException
	{
		final String[] modes = {"normal", "larger", "smooth", "smooth-larger"};
		for (int m = 0; m < modes.length; m++)
		{
			larger = m == 1 || m == 3;
			smooth = m >= 2;
			final Path dir = out.resolve(modes[m]);
			Files.createDirectories(dir);
			for (double scale : new double[]{1, 1.5, 2})
			{
				write(dir, "a-live", scale, render(SidePanelExamples.live(), scale));
				write(dir, "b-ended", scale, render(SidePanelExamples.ended(), scale));
				write(dir, "c-ruled-kill", scale, render(SidePanelExamples.ruledKill(true), scale));
				write(dir, "d-trip", scale, renderTrip(SidePanelExamples.trip(), scale));
				write(dir, "e-history", scale, renderHistory(false, scale));
				write(dir, "f-saved-kill", scale, renderSavedThisSession(scale));
			}
		}
		larger = false;
		smooth = false;
		SidePanelStyle.setText(false, false);
	}

	/**
	 * T-054: the boss icons in Fight (live, after a kill, a raid), Trip and History, with T-053's settings off, Smooth,
	 * and Smooth + Larger, at 1×, 1.5× and 2×. The renders have no game cache, so the sprites come from {@code
	 * sprites}: a PNG per sprite id ({@code 5634.png}), stand-ins for what the game serves.
	 */
	private static void bossIcons(Path out, Path sprites) throws IOException
	{
		SidePanelBossIcons.setSource(new SidePanelIcons()
		{
			@Override
			public void item(int itemId, java.util.function.Consumer<BufferedImage> done)
			{
			}

			@Override
			public void sprite(int spriteId, java.util.function.Consumer<BufferedImage> done)
			{
				final Path f = sprites.resolve(spriteId + ".png");
				if (Files.exists(f))
				{
					try (java.io.InputStream in = Files.newInputStream(f))
					{
						done.accept(ImageIO.read(in));
					}
					catch (IOException e)
					{
						throw new java.io.UncheckedIOException(e);
					}
				}
			}
		});
		final String[] modes = {"normal", "smooth", "smooth-larger"};
		for (int m = 0; m < modes.length; m++)
		{
			larger = m == 2;
			smooth = m >= 1;
			final Path dir = out.resolve(modes[m]);
			Files.createDirectories(dir);
			for (double scale : new double[]{1, 1.5, 2})
			{
				write(dir, "a-live", scale, render(SidePanelExamples.live(), scale));
				write(dir, "b-ruled-kill", scale, render(SidePanelExamples.ruledKill(true), scale));
				write(dir, "c-raid", scale, render(SidePanelExamples.raid(), scale));
				write(dir, "d-trip", scale, renderTrip(SidePanelExamples.trip(), scale));
				write(dir, "e-history", scale, renderHistory(false, scale));
				write(dir, "f-saved-kill", scale, renderSavedThisSession(scale));
			}
		}
		larger = false;
		smooth = false;
		SidePanelStyle.setText(false, false);
		SidePanelBossIcons.setSource(SidePanelIcons.NONE);
	}

	private static void write(Path out, String name, double scale, BufferedImage img) throws IOException
	{
		final String x = scale == Math.rint(scale) ? String.valueOf((int) scale) : String.valueOf(scale);
		final Path file = out.resolve(name + "@" + x + "x.png");
		try (OutputStream o = Files.newOutputStream(file))
		{
			ImageIO.write(img, "png", o);
		}
		System.out.println(file + " " + img.getWidth() + "x" + img.getHeight());
	}

	/** The fixtures' saved fights, as the History tab reads them. */
	static SidePanelHistoryTab.Source source()
	{
		final java.util.List<Fight> history = SidePanelHistoryExamples.history();
		return new SidePanelHistoryTab.Source()
		{
			public java.util.List<Fight> history()
			{
				return history;
			}

			public SidePanelHistory.Versions versions()
			{
				// The boss rules' name for Vardorvis at 700 HP, without loading them.
				return (room, maxHp) -> "Vardorvis".equals(room.getName()) && maxHp == 700 ? "Post-quest" : null;
			}

			public long sessionStart()
			{
				return SidePanelExamples.at(11, 0);
			}

			public long now()
			{
				return SidePanelHistoryExamples.NOW;
			}

			public java.time.ZoneId zone()
			{
				return SidePanelExamples.ZONE;
			}

			public void exportNpc(String name)
			{
			}
		};
	}

	/** State 9: the Trip tab showing this input. */
	static BufferedImage renderTrip(SidePanelModel.Input in, double scale)
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		panel.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.empty()), SidePanelModel.trip(in)));
		styled(panel);
		panel.tabs().select(panel.tabs().getTab(2));
		return draw(panel.tabs(), panel.tripTab(), scale);
	}

	/** States 10 and 11: the History tab on the fixtures, or the 26 Oct kill opened from it. */
	static BufferedImage renderHistory(boolean savedKill, double scale)
	{
		final SidePanelHistoryTab.Source source = source();
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null, source);
		panel.tabs().select(panel.tabs().getTab(1));
		final SidePanelHistoryTab tab = panel.historyTab();
		tab.refresh();
		if (savedKill)
		{
			// The oldest Vardorvis kill: 26 Oct, before T-028 and before this session.
			final java.util.List<Fight> h = source.history();
			tab.open(h.get(h.size() - 1));
		}
		styled(panel);
		return draw(panel.tabs(), tab, scale);
	}

	/** A kill from this session, opened from History: its timeline comes with it (T-036). */
	static BufferedImage renderSavedThisSession(double scale)
	{
		final SidePanelTimelineExamples.Built kill = SidePanelTimelineExamples.kill();
		kill.fight.end(true, SidePanelExamples.at(15, 31));
		final java.util.List<Fight> history = java.util.Collections.singletonList(kill.fight);
		final SidePanelHistoryTab.Source source = new SidePanelHistoryTab.Source()
		{
			public java.util.List<Fight> history()
			{
				return history;
			}

			public SidePanelHistory.Versions versions()
			{
				return (room, maxHp) -> maxHp == 700 ? "Post-quest" : null;
			}

			public long sessionStart()
			{
				return -1;
			}

			public long now()
			{
				return SidePanelHistoryExamples.NOW;
			}

			public java.time.ZoneId zone()
			{
				return SidePanelExamples.ZONE;
			}

			public void exportNpc(String name)
			{
			}

			@Override
			public void charts(Encounter room, java.util.function.Consumer<FightTimeline.View> done)
			{
				done.accept(FightTimeline.room(room, kill.store));
			}
		};
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null, source);
		panel.tabs().select(panel.tabs().getTab(1));
		panel.historyTab().open(kill.fight);
		styled(panel);
		return draw(panel.tabs(), panel.historyTab(), scale);
	}

	/**
	 * T-044: a Vardorvis kill classed by attack rules, from this session, opened
	 * from History with its charts and the rules' avoidable names: 5 of its 7
	 * eating ticks for the avoidable hits.
	 */
	static BufferedImage renderRuledSavedKill(double scale)
	{
		final SidePanelTimelineExamples.Built kill = SidePanelTimelineExamples.kill();
		final java.util.Map<String, Integer> by = new java.util.LinkedHashMap<>();
		by.put("melee", 101);
		by.put("spike", 52);
		by.put("melee off prayer", 34);
		by.put("axe", 27);
		by.put("bleed", 20);
		by.put("head", 8);
		// The hits those 242 HP came in.
		for (int hit : new int[]{31, 27, 24, 22, 20, 19, 18, 17, 16, 14, 12, 10, 8, 4})
		{
			kill.fight.recordHitTaken(hit, false, SidePanelExamples.at(15, 30));
		}
		kill.fight.recordAvoidable(1, 132.5, by, 5.0, 29, AvoidableEating.LINE_THIS_KILL);
		kill.fight.end(true, SidePanelExamples.at(15, 31));
		final java.util.List<Fight> history = java.util.Collections.singletonList(kill.fight);
		final java.util.Set<String> avoidable = SidePanelModel.avoidableAttacks(AvoidableReplayTest.vardorvis());
		final SidePanelHistoryTab.Source source = new SidePanelHistoryTab.Source()
		{
			public java.util.List<Fight> history()
			{
				return history;
			}

			public SidePanelHistory.Versions versions()
			{
				return new SidePanelHistory.Versions()
				{
					@Override
					public String name(Encounter room, int maxHp)
					{
						return maxHp == 700 ? "Post-quest" : null;
					}

					@Override
					public java.util.Set<String> avoidable(Encounter room)
					{
						return avoidable;
					}
				};
			}

			public long sessionStart()
			{
				return -1;
			}

			public long now()
			{
				return SidePanelHistoryExamples.NOW;
			}

			public java.time.ZoneId zone()
			{
				return SidePanelExamples.ZONE;
			}

			public void exportNpc(String name)
			{
			}

			@Override
			public void charts(Encounter room, java.util.function.Consumer<FightTimeline.View> done)
			{
				done.accept(FightTimeline.room(room, kill.store));
			}
		};
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null, source);
		panel.tabs().select(panel.tabs().getTab(1));
		panel.historyTab().open(kill.fight);
		styled(panel);
		return draw(panel.tabs(), panel.historyTab(), scale);
	}

	/** The panel showing this input's snapshot, drawn at {@code scale}. */
	static BufferedImage render(SidePanelModel.Input in, double scale)
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		panel.apply(new PanelSnapshot(SidePanelModel.fight(in)));
		styled(panel);
		return draw(panel.tabs(), panel.fightTab(), scale);
	}

	/** The tab bar across the sidebar, a tab's content under it, RuneLite's scrollbar beside it. */
	static BufferedImage draw(JComponent tabs, JComponent content, double scale)
	{
		tabs.setSize(SIDEBAR, tabs.getPreferredSize().height);
		layout(tabs);
		final int contentWidth = SIDEBAR - SCROLLBAR;
		content.setSize(contentWidth, 10_000);
		layout(content);
		final int contentHeight = content.getPreferredSize().height;
		content.setSize(contentWidth, contentHeight);
		layout(content);

		final int w = SIDEBAR;
		final int h = tabs.getHeight() + contentHeight;
		final BufferedImage img = new BufferedImage((int) Math.ceil(w * scale), (int) Math.ceil(h * scale),
			BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		g.scale(scale, scale);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
		g.setColor(SidePanelStyle.PANEL);
		g.fillRect(0, 0, w, h);
		tabs.printAll(g);
		final Graphics2D cg = (Graphics2D) g.create(0, tabs.getHeight(), contentWidth, contentHeight);
		content.printAll(cg);
		cg.dispose();
		// RuneLite's scrollbar: its track, and a thumb as long as one screen of a 900 px sidebar would be.
		g.setColor(new Color(0x191919));
		g.fillRect(contentWidth, tabs.getHeight(), SCROLLBAR, contentHeight);
		g.setColor(SidePanelStyle.SELECTED);
		g.fillRect(contentWidth + 3, tabs.getHeight() + 2, SCROLLBAR - 6,
			Math.max(40, Math.min(contentHeight - 4, 900 * contentHeight / Math.max(900, contentHeight) - 4)));
		g.dispose();
		return img;
	}

	/** Lays out a tree of components that isn't on screen. */
	static void layout(Component c)
	{
		if (c instanceof Container)
		{
			final Container k = (Container) c;
			k.doLayout();
			for (Component child : k.getComponents())
			{
				layout(child);
			}
		}
	}

	static Dimension size(JComponent c)
	{
		return c.getPreferredSize();
	}
}
