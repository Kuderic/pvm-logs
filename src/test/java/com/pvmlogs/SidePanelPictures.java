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
		if (args.length > 1 && "dps".equals(args[1]))
		{
			dpsCards(out);
			return;
		}
		if (args.length > 1 && "room".equals(args[1]))
		{
			roomDamage(out);
			return;
		}
		if (args.length > 1 && "switch".equals(args[1]))
		{
			switchInHeader(out);
			return;
		}
		if (args.length > 1 && "findings".equals(args[1]))
		{
			calibrationFindings(out);
			return;
		}
		if (args.length > 1 && "calibration".equals(args[1]))
		{
			calibration(out);
			return;
		}
		if (args.length > 2 && "details".equals(args[1]))
		{
			detailsGroups(out, Paths.get(args[2]));
			return;
		}
		if (args.length > 1 && "spec".equals(args[1]))
		{
			specRow(out);
			return;
		}
		if (args.length > 1 && "order".equals(args[1]))
		{
			fightOrder(out);
			return;
		}
		if (args.length > 1 && "wording".equals(args[1]))
		{
			wording(out);
			return;
		}
		if (args.length > 1 && "ticks".equals(args[1]))
		{
			ticks(out);
			return;
		}
		if (args.length > 1 && "party".equals(args[1]))
		{
			partyTab(out);
			return;
		}
		if (args.length > 1 && "alldps".equals(args[1]))
		{
			allYourDamage(out);
			return;
		}
		if (args.length > 1 && "reset".equals(args[1]))
		{
			setupDamageAndNewTrip(out);
			return;
		}
		if (args.length > 1 && "bosses".equals(args[1]))
		{
			bossesAndMonsters(out);
			return;
		}
		if (args.length > 1 && "targets".equals(args[1]))
		{
			targets(out);
			return;
		}
		if (args.length > 1 && "execution".equals(args[1]))
		{
			executionCards(out);
			return;
		}
		if (args.length > 1 && "runs".equals(args[1]))
		{
			runs(out);
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
			// T-082: searching, the boss put away, no match, and the close control hovered.
			write(out, "10c-history-searching", scale, renderHistory(t -> t.search().setText("abyss"), scale));
			write(out, "10d-history-put-away", scale, renderHistory(SidePanelHistoryTab::putAway, scale));
			write(out, "10e-history-no-match", scale, renderHistory(t -> t.search().setText("zulrah"), scale));
			write(out, "10f-history-close-hovered", scale, renderHistory(SidePanelPictures::hoverClose, scale));
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
			// T-072: times of day with am and pm: a kill at midnight, noon and 12:05 am.
			write(out, "17a-dead-at-midnight", scale, render(SidePanelExamples.deadAt(0, 0), scale));
			write(out, "17b-dead-at-noon", scale, render(SidePanelExamples.deadAt(12, 0), scale));
			write(out, "17c-dead-at-12-05-am", scale, render(SidePanelExamples.deadAt(0, 5), scale));
			// T-068: the Damage taken card mid-fight (the total only) and after; the trip while a fight runs.
			write(out, "16a-taken-mid-fight", scale, render(SidePanelExamples.takenMidFight(), scale));
			write(out, "16b-taken-after", scale, render(SidePanelExamples.ruledKill(true), scale));
			write(out, "16c-trip-while-fighting", scale, renderTrip(SidePanelExamples.tripWhileFighting(), scale));
			// T-068 part 2: a Sire room with a respiratory system dead runs on; the Sire's death ends it.
			write(out, "16d-sire-lung-dead-running", scale, render(SidePanelExamples.sireRoom(false), scale));
			write(out, "16e-sire-dead", scale, render(SidePanelExamples.sireRoom(true), scale));
			// T-076: the recorded Sire kill (Defence card, no zero "Not matched" row, the chart's expected as thrown,
			// hits that did damage), a floor not reached, and an average far shorter than the kill.
			write(out, "18a-sire-emberlight", scale, render(SidePanelExamples.sireEmberlight(), scale));
			write(out, "18b-floor-not-reached", scale, render(SidePanelExamples.floorNotReached(), scale));
			write(out, "18c-short-average", scale, render(SidePanelExamples.sireShortAverage(), scale));
			// T-081: forced downtime at the Leviathan (details, the time budget, the tick strip); a boss without
			// allowances and an old kill show nothing new.
			write(out, "19a-leviathan-mechanics", scale, render(SidePanelExamples.leviathanKill(), scale));
			write(out, "19b-no-allowances", scale, render(SidePanelExamples.dead(), scale));
			write(out, "19c-old-kill", scale, renderHistory(true, scale));
			write(out, "18d-at-its-floor-card", scale, card(SidePanelExamples.atItsFloor(), scale, "defence"));
			// T-085: each weapon's DPS on the Damage card, and a row's details on hover.
			write(out, "20a-sire-weapons-dps", scale, render(SidePanelExamples.sireWeapons(), scale));
			write(out, "20b-leviathan-weapons-dps", scale, render(SidePanelExamples.leviathanWeapons(), scale));
			write(out, "20c-weapon-tooltip", scale, tooltip(SidePanelExamples.sireWeapons(), 0, scale));
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
				write(dir, "e2-history-searching", scale, renderHistory(t -> t.search().setText("abyss"), scale));
				write(dir, "e3-history-put-away", scale, renderHistory(SidePanelHistoryTab::putAway, scale));
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

	/**
	 * T-095: a run of monsters, live, under a minute, opened from History; History's runs (the kind's card and its
	 * list, and the boss list); a trip with a run and a boss kill; old per-fight history; and a boss, unchanged. Normal
	 * and Smooth text, at 1× and 2×, a folder each.
	 */
	private static void runs(Path out) throws IOException
	{
		final String[] modes = {"normal", "smooth"};
		for (int m = 0; m < modes.length; m++)
		{
			smooth = m == 1;
			larger = false;
			final Path dir = out.resolve(modes[m]);
			Files.createDirectories(dir);
			for (double scale : new double[]{1, 2})
			{
				write(dir, "a-run-live", scale, render(SidePanelRunExamples.live(), scale));
				write(dir, "b-run-under-a-minute", scale, render(SidePanelRunExamples.liveUnderAMinute(), scale));
				write(dir, "c-run-from-history", scale, renderSavedRun(scale));
				write(dir, "d-history-runs", scale, renderHistory(source(SidePanelRunExamples.history()),
					t -> t.pick(SidePanelRunExamples.DEMON), scale));
				write(dir, "e-history-boss-list", scale, renderHistory(source(SidePanelRunExamples.history()),
					SidePanelHistoryTab::putAway, scale));
				write(dir, "f-trip-run-and-boss", scale, renderTrip(SidePanelRunExamples.trip(), scale));
				write(dir, "g-trip-runs-only", scale, renderTrip(SidePanelRunExamples.tripOfRunsOnly(), scale));
				write(dir, "h-old-per-fight-history", scale, renderHistory(source(),
					t -> t.pick(SidePanelRunExamples.DEMON), scale));
				write(dir, "i-boss-unchanged", scale, render(SidePanelExamples.dead(), scale));
			}
		}
		smooth = false;
		larger = false;
		SidePanelStyle.setText(false, false);
	}

	/**
	 * T-102: History's Bosses and Monsters: Eric's mix (bosses, a demon run, the Deranged archaeologist, the old araxyte
	 * rooms under Araxxor), opened on its last kill and with none picked; bosses only (no Monsters); a search matching
	 * only a monster; the export's choice off and on; and a died room, on the Fight tab and in the Trip tab's rows.
	 * Normal and Smooth text, 1× and 2×, a folder each.
	 */
	private static void bossesAndMonsters(Path out) throws IOException
	{
		final String[] modes = {"normal", "smooth"};
		for (int m = 0; m < modes.length; m++)
		{
			smooth = m == 1;
			larger = false;
			final Path dir = out.resolve(modes[m]);
			Files.createDirectories(dir);
			for (double scale : new double[]{1, 2})
			{
				write(dir, "a-mix-opened", scale, renderHistory(source(SidePanelBossesExamples.mix()), t ->
				{
				}, scale));
				write(dir, "b-mix-none-picked", scale, renderHistory(source(SidePanelBossesExamples.mix()),
					SidePanelHistoryTab::putAway, scale));
				write(dir, "c-bosses-only", scale, renderHistory(source(SidePanelBossesExamples.bossesOnly()),
					SidePanelHistoryTab::putAway, scale));
				write(dir, "d-search-a-monster", scale, renderHistory(source(SidePanelBossesExamples.mix()),
					t -> t.search().setText("reanimated"), scale));
				write(dir, "e-search-neither", scale, renderHistory(source(SidePanelBossesExamples.mix()),
					t -> t.search().setText("zulrah"), scale));
				write(dir, "f-export-all", scale, renderHistory(source(SidePanelBossesExamples.mix()),
					SidePanelHistoryTab::putAway, scale));
				write(dir, "g-export-bosses-only", scale, renderHistory(source(SidePanelBossesExamples.mix()), t ->
				{
					t.putAway();
					t.exportChoice().setSelected(true);
				}, scale));
				write(dir, "h-died-fight-tab", scale, render(diedInput(), scale));
				write(dir, "i-died-trip-rows", scale, renderTrip(diedTrip(), scale));
			}
		}
		smooth = false;
		larger = false;
		SidePanelStyle.setText(false, false);
	}

	/** T-102: the Araxxor attempt the player died in, on the Fight tab once it's over. */
	private static SidePanelModel.Input diedInput()
	{
		final SidePanelModel.Input in = new SidePanelModel.Input();
		in.zone = SidePanelExamples.ZONE;
		in.kind = SidePanelModel.Kind.ROOM;
		SidePanelModel.fillRoom(in, SidePanelBossesExamples.diedRoom());
		in.ended = true;
		in.killed = false;
		in.endMillis = SidePanelExamples.at(15, 1);
		return in;
	}

	/** T-102: the trip's rows with the died attempt, a room left alive, and a kill. */
	private static SidePanelModel.Input diedTrip()
	{
		final SidePanelModel.Input in = SidePanelExamples.trip();
		final java.util.List<PanelSnapshot.TripRow> rows = new java.util.ArrayList<>();
		rows.add(SidePanelModel.tripRow(SidePanelHistory.row(SidePanelBossesExamples.leftRoom(),
			SidePanelHistoryExamples.NOW, SidePanelExamples.ZONE)));
		rows.add(SidePanelModel.tripRow(SidePanelHistory.row(SidePanelBossesExamples.diedRoom(),
			SidePanelHistoryExamples.NOW, SidePanelExamples.ZONE)));
		rows.addAll(in.tripRows.subList(0, 3));
		in.tripRows = rows;
		return in;
	}

	/**
	 * T-099: the Damage card's Boss · Adds · All switch: an Araxxor kill in each view (the whole tab, for the chart and
	 * strip following); the Sire's card in Boss and Adds; Zulrah's in Adds (the snakelings' time only) and Boss; and an
	 * ungrouped boss's card with no switch. Normal and Smooth text, 1× and 2×, a folder each.
	 */
	private static void targets(Path out) throws IOException
	{
		final String[] modes = {"normal", "smooth"};
		final String[] views = {"boss", "adds", "all"};
		for (int m = 0; m < modes.length; m++)
		{
			smooth = m == 1;
			larger = false;
			final Path dir = out.resolve(modes[m]);
			Files.createDirectories(dir);
			for (double scale : new double[]{1, 2})
			{
				for (int v = 0; v < views.length; v++)
				{
					SidePanelFightTab.useTarget(v);
					write(dir, "a" + v + "-araxxor-" + views[v], scale,
						render(SidePanelTargetExamples.input(SidePanelTargetExamples.araxxor()), scale));
					write(dir, "b" + v + "-araxxor-card-" + views[v], scale,
						card(SidePanelTargetExamples.input(SidePanelTargetExamples.araxxor()), scale, "damage"));
				}
				SidePanelFightTab.useTarget(0);
				write(dir, "c0-sire-boss", scale, card(SidePanelTargetExamples.input(SidePanelTargetExamples.sire()), scale,
					"damage"));
				write(dir, "d1-zulrah-boss", scale, card(SidePanelTargetExamples.input(SidePanelTargetExamples.zulrah()),
					scale, "damage"));
				SidePanelFightTab.useTarget(1);
				write(dir, "c1-sire-adds", scale, card(SidePanelTargetExamples.input(SidePanelTargetExamples.sire()), scale,
					"damage"));
				write(dir, "d0-zulrah-adds", scale, card(SidePanelTargetExamples.input(SidePanelTargetExamples.zulrah()),
					scale, "damage"));
				SidePanelFightTab.useTarget(0);
				write(dir, "e-ungrouped-no-switch", scale, card(SidePanelTargetExamples.ungrouped(), scale, "damage"));
			}
		}
		smooth = false;
		larger = false;
		SidePanelFightTab.resetTarget();
		SidePanelStyle.setText(false, false);
	}

	/** T-095: a run from this session, opened from History, with its timeline. */
	static BufferedImage renderSavedRun(double scale)
	{
		final SidePanelTimelineExamples.Built run = SidePanelRunExamples.demonRun(12);
		run.fight.end(true, SidePanelRunExamples.START + SidePanelRunExamples.RUN_MILLIS);
		run.fight.closeRoom();
		final java.util.List<Fight> history = java.util.Collections.singletonList(run.fight);
		final SidePanelHistoryTab.Source base = source(history);
		final SidePanelHistoryTab.Source source = new SidePanelHistoryTab.Source()
		{
			public java.util.List<Fight> history()
			{
				return history;
			}

			public SidePanelHistory.Versions versions()
			{
				return base.versions();
			}

			public long sessionStart()
			{
				return -1;
			}

			public long now()
			{
				return base.now();
			}

			public java.time.ZoneId zone()
			{
				return base.zone();
			}

			public void exportNpc(String name)
			{
			}

			@Override
			public void charts(Encounter room, java.util.function.Consumer<FightTimeline.View> done)
			{
				done.accept(FightTimeline.room(room, run.store));
			}
		};
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null, source);
		panel.tabs().select(panel.tabs().getTab(1));
		panel.historyTab().open(run.fight);
		styled(panel);
		return draw(panel.tabs(), panel.historyTab(), scale);
	}

	/** T-082 and T-095: the History tab on these fights after {@code state}. */
	static BufferedImage renderHistory(SidePanelHistoryTab.Source source,
		java.util.function.Consumer<SidePanelHistoryTab> state, double scale)
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null, source);
		panel.tabs().select(panel.tabs().getTab(1));
		final SidePanelHistoryTab tab = panel.historyTab();
		tab.refresh();
		state.accept(tab);
		styled(panel);
		return draw(panel.tabs(), tab, scale);
	}

	/** The fixtures' saved fights, as the History tab reads them. */
	static SidePanelHistoryTab.Source source()
	{
		return source(SidePanelHistoryExamples.history());
	}

	/** These saved fights, as the History tab reads them. */
	static SidePanelHistoryTab.Source source(java.util.List<Fight> history)
	{
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

	/** T-082: the History tab after {@code state} (a search typed, the boss put away). */
	static BufferedImage renderHistory(java.util.function.Consumer<SidePanelHistoryTab> state, double scale)
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null, source());
		panel.tabs().select(panel.tabs().getTab(1));
		final SidePanelHistoryTab tab = panel.historyTab();
		tab.refresh();
		state.accept(tab);
		styled(panel);
		return draw(panel.tabs(), tab, scale);
	}

	/** T-082: the boss card's close control hovered. */
	static void hoverClose(SidePanelHistoryTab tab)
	{
		for (Component k : all(tab))
		{
			if (k instanceof SidePanelHistoryTab.Close)
			{
				k.dispatchEvent(new java.awt.event.MouseEvent(k, java.awt.event.MouseEvent.MOUSE_ENTERED, 0L, 0, 2, 2, 0,
					false));
			}
		}
	}

	private static java.util.List<Component> all(Container c)
	{
		final java.util.List<Component> out = new java.util.ArrayList<>();
		for (Component k : c.getComponents())
		{
			out.add(k);
			if (k instanceof Container)
			{
				out.addAll(all((Container) k));
			}
		}
		return out;
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

	/**
	 * T-085: the Damage card with each weapon's DPS (Eric's layout A), and a row's tooltip: the Sire and Leviathan kills
	 * with T-053's settings off, Smooth, and Smooth + Larger, at 1× and 2×, the card alone.
	 */
	private static void dpsCards(Path out) throws IOException
	{
		final String[] modes = {"normal", "smooth", "smooth-larger"};
		for (int m = 0; m < modes.length; m++)
		{
			smooth = m >= 1;
			larger = m == 2;
			final Path dir = out.resolve(modes[m]);
			Files.createDirectories(dir);
			for (double scale : new double[]{1, 2})
			{
				write(dir, "a-sire", scale, card(SidePanelExamples.sireWeapons(), scale, "damage"));
				write(dir, "b-leviathan", scale, card(SidePanelExamples.leviathanWeapons(), scale, "damage"));
				write(dir, "c-tooltip", scale, tooltip(SidePanelExamples.sireWeapons(), 0, scale));
			}
		}
		smooth = false;
		larger = false;
		SidePanelStyle.setText(false, false);
	}

	/** T-085: row {@code index}'s tooltip on the Damage card, as Swing draws it, under the card with the row hovered. */
	static BufferedImage tooltip(SidePanelModel.Input in, int index, double scale)
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		panel.apply(new PanelSnapshot(SidePanelModel.fight(in)));
		styled(panel);
		final java.util.List<SidePanelParts.SourceRowView> rows = new java.util.ArrayList<>();
		for (Component k : all(panel.fightTab()))
		{
			if (k instanceof SidePanelParts.SourceRowView)
			{
				rows.add((SidePanelParts.SourceRowView) k);
			}
		}
		final SidePanelParts.SourceRowView row = rows.get(index);
		row.dispatchEvent(new java.awt.event.MouseEvent(row, java.awt.event.MouseEvent.MOUSE_ENTERED, 0L, 0, 2, 2, 0,
			false));
		final BufferedImage whole = draw(panel.tabs(), panel.fightTab(), scale);
		final SidePanelSection section = sectionOf(panel.fightTab(), "damage");
		final java.awt.Rectangle r = javax.swing.SwingUtilities.convertRectangle(section.getParent(), section.getBounds(),
			panel.fightTab());
		final int top = panel.tabs().getHeight();
		final BufferedImage card = whole.getSubimage(0, (int) Math.floor((top + r.y) * scale),
			(int) Math.ceil((SIDEBAR - SCROLLBAR) * scale), (int) Math.ceil(r.height * scale));
		final javax.swing.JToolTip tip = row.createToolTip();
		tip.setTipText(row.getToolTipText());
		tip.setSize(tip.getPreferredSize());
		layout(tip);
		final int w = Math.max(card.getWidth(), (int) Math.ceil(tip.getWidth() * scale));
		final BufferedImage img = new BufferedImage(w, card.getHeight() + (int) Math.ceil((tip.getHeight() + 8) * scale),
			BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		g.setColor(SidePanelStyle.PANEL);
		g.fillRect(0, 0, img.getWidth(), img.getHeight());
		g.drawImage(card, 0, 0, null);
		g.translate(0, card.getHeight() + 4 * scale);
		g.scale(scale, scale);
		tip.printAll(g);
		g.dispose();
		return img;
	}

	/**
	 * T-105: the Damage card's {@code boss · adds · all} in its header: Araxxor in each view, a word hovered, the
	 * tooltip, the Sire, Zulrah (its adds: nothing scored) and an ungrouped boss with today's hint. Normal and Smooth,
	 * Larger, and Smooth with Larger, 1× and 2×, a folder each.
	 */
	private static void switchInHeader(Path out) throws IOException
	{
		final String[] modes = {"normal", "smooth", "larger", "smooth-larger"};
		final String[] views = {"boss", "adds", "all"};
		for (int m = 0; m < modes.length; m++)
		{
			smooth = m == 1 || m == 3;
			larger = m >= 2;
			final Path dir = out.resolve(modes[m]);
			Files.createDirectories(dir);
			for (double scale : new double[]{1, 2})
			{
				for (int v = 0; v < views.length; v++)
				{
					SidePanelFightTab.useTarget(v);
					write(dir, "a" + v + "-araxxor-" + views[v], scale, switchCard(SidePanelTargetExamples.input(
						SidePanelTargetExamples.araxxor()), scale, -1, false));
				}
				SidePanelFightTab.useTarget(0);
				write(dir, "b-hover-adds", scale, switchCard(SidePanelTargetExamples.input(
					SidePanelTargetExamples.araxxor()), scale, 1, false));
				write(dir, "c-tooltip", scale, switchCard(SidePanelTargetExamples.input(SidePanelTargetExamples.araxxor()),
					scale, -1, true));
				write(dir, "d0-sire-boss", scale, switchCard(SidePanelTargetExamples.input(SidePanelTargetExamples.sire()),
					scale, -1, false));
				SidePanelFightTab.useTarget(1);
				write(dir, "d1-sire-adds", scale, switchCard(SidePanelTargetExamples.input(SidePanelTargetExamples.sire()),
					scale, -1, false));
				write(dir, "e1-zulrah-adds", scale, switchCard(SidePanelTargetExamples.input(
					SidePanelTargetExamples.zulrah()), scale, -1, false));
				SidePanelFightTab.useTarget(0);
				write(dir, "e0-zulrah-boss", scale, switchCard(SidePanelTargetExamples.input(
					SidePanelTargetExamples.zulrah()), scale, -1, false));
				write(dir, "f-ungrouped-no-switch", scale, card(SidePanelTargetExamples.ungrouped(), scale, "damage"));
				write(dir, "g-araxxor-whole-tab", scale, render(SidePanelTargetExamples.input(
					SidePanelTargetExamples.araxxor()), scale));
			}
		}
		smooth = false;
		larger = false;
		SidePanelFightTab.resetTarget();
		SidePanelStyle.setText(false, false);
	}

	/** T-105: the Damage card with the word {@code hover} hovered (-1 none), and its tooltip under it if asked. */
	static BufferedImage switchCard(SidePanelModel.Input in, double scale, int hover, boolean tooltip)
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		panel.apply(new PanelSnapshot(SidePanelModel.fight(in)));
		styled(panel);
		SidePanelFightTab.TargetSwitch words = null;
		for (Component k : all(panel.fightTab()))
		{
			if (k instanceof SidePanelFightTab.TargetSwitch)
			{
				words = (SidePanelFightTab.TargetSwitch) k;
			}
		}
		if (hover >= 0)
		{
			words.hover(hover);
		}
		final BufferedImage whole = draw(panel.tabs(), panel.fightTab(), scale);
		final SidePanelSection section = sectionOf(panel.fightTab(), "damage");
		final java.awt.Rectangle r = javax.swing.SwingUtilities.convertRectangle(section.getParent(), section.getBounds(),
			panel.fightTab());
		final int top = panel.tabs().getHeight();
		final int y0 = (int) Math.floor((top + r.y) * scale);
		final int y1 = Math.min(whole.getHeight(), (int) Math.ceil((top + r.y + r.height) * scale));
		final BufferedImage card = whole.getSubimage(0, y0, (int) Math.ceil((SIDEBAR - SCROLLBAR) * scale), y1 - y0);
		if (!tooltip)
		{
			return card;
		}
		final javax.swing.JToolTip tip = words.createToolTip();
		tip.setTipText(words.getToolTipText());
		tip.setSize(tip.getPreferredSize());
		layout(tip);
		final int w = Math.max(card.getWidth(), (int) Math.ceil(tip.getWidth() * scale));
		final BufferedImage img = new BufferedImage(w, card.getHeight() + (int) Math.ceil((tip.getHeight() + 8) * scale),
			BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		g.setColor(SidePanelStyle.PANEL);
		g.fillRect(0, 0, img.getWidth(), img.getHeight());
		g.drawImage(card, 0, 0, null);
		g.translate(0, card.getHeight() + 4 * scale);
		g.scale(scale, scale);
		tip.printAll(g);
		g.dispose();
		return img;
	}

	/**
	 * T-109: the room's damage in Details: an Araxxor kill with 160 (the card, and its row's tooltip), a kill with none,
	 * and the Trip tab with two Araxxor kills. Normal and Smooth, 1× and 2×, a folder each.
	 */
	private static void roomDamage(Path out) throws IOException
	{
		final BossRules rules = BossRules.load(new com.google.gson.Gson(), null);
		final String[] modes = {"normal", "smooth"};
		for (int m = 0; m < modes.length; m++)
		{
			smooth = m == 1;
			larger = false;
			final Path dir = out.resolve(modes[m]);
			Files.createDirectories(dir);
			for (double scale : new double[]{1, 2})
			{
				final SidePanelModel.Input kill = roomInput(SidePanelRoomDamageExamples.araxxor(160,
					SidePanelExamples.at(15, 0)), rules);
				write(dir, "a-araxxor-160", scale, card(kill, scale, "details"));
				write(dir, "b-tooltip", scale, rowTooltip(kill, scale, "details", SidePanelModel.ROOM_DMG));
				write(dir, "c-no-room-damage", scale, card(roomInput(SidePanelRoomDamageExamples.araxxor(0,
					SidePanelExamples.at(15, 0)), rules), scale, "details"));
				write(dir, "d-whole-tab", scale, render(kill, scale));
				final SidePanelModel.Input trip = SidePanelExamples.trip();
				SidePanelModel.fillTripRoomDamage(trip, java.util.Arrays.asList(SidePanelRoomDamageExamples.araxxor(160,
					SidePanelExamples.at(15, 0)), SidePanelRoomDamageExamples.araxxor(120, SidePanelExamples.at(15, 5))),
					rules);
				write(dir, "e-trip-two-kills", scale, renderTrip(trip, scale));
			}
		}
		smooth = false;
		larger = false;
		SidePanelStyle.setText(false, false);
	}

	private static SidePanelModel.Input roomInput(Encounter room, BossRules rules)
	{
		final SidePanelModel.Input in = new SidePanelModel.Input();
		in.zone = SidePanelExamples.ZONE;
		in.kind = SidePanelModel.Kind.ROOM;
		SidePanelModel.fillRoom(in, room);
		in.roomDamageSources = SidePanelModel.roomDamageSources(java.util.Collections.singletonList(room), rules);
		in.ended = true;
		in.killed = true;
		in.endMillis = SidePanelExamples.at(15, 3);
		return in;
	}

	/** T-109: section {@code id}'s card with the tooltip of its row labelled {@code label} under it. */
	/**
	 * T-126: the Execution card on a kill like Eric's (00:37: about 600 ideal, 2 damage to prayer, 17 to gear and 5 to
	 * the boost, a tick idle) and on Eric's 2:17 kill, each with its header's tooltip under it.
	 */
	static void executionCards(Path out) throws IOException
	{
		final LedgerExamples.FakeScope erics = LedgerExamples.kill();
		erics.ticksLost = 2;
		erics.ticksLostEating = 0;
		erics.idealSetupDamage = 600;
		erics.efficiency = 1 - 24 / 600.0;
		erics.boostShare = 0.97;
		erics.boostCostShare = 5 / 600.0;
		erics.switchCostShare = 17 / 600.0;
		final SidePanelModel.Input likeErics = SidePanelExamples.dead();
		likeErics.scope = erics;
		for (int scale = 1; scale <= 2; scale++)
		{
			write(out, "26a-execution-like-erics", scale, headerTooltip(likeErics, scale, "execution"));
			write(out, "26b-execution-kill", scale, headerTooltip(SidePanelExamples.dead(), scale, "execution"));
		}
	}

	/** T-126: section {@code id}'s card with its header's tooltip under it, as Swing draws it. */
	static BufferedImage headerTooltip(SidePanelModel.Input in, double scale, String id)
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		panel.apply(new PanelSnapshot(SidePanelModel.fight(in)));
		styled(panel);
		final BufferedImage whole = draw(panel.tabs(), panel.fightTab(), scale);
		final SidePanelSection section = sectionOf(panel.fightTab(), id);
		final javax.swing.JComponent header = (javax.swing.JComponent) section.getComponent(0);
		final java.awt.Rectangle r = javax.swing.SwingUtilities.convertRectangle(section.getParent(), section.getBounds(),
			panel.fightTab());
		final int top = panel.tabs().getHeight();
		final int y0 = (int) Math.floor((top + r.y) * scale);
		final int y1 = Math.min(whole.getHeight(), (int) Math.ceil((top + r.y + r.height) * scale));
		final BufferedImage card = whole.getSubimage(0, y0, (int) Math.ceil((SIDEBAR - SCROLLBAR) * scale), y1 - y0);
		final javax.swing.JToolTip tip = header.createToolTip();
		tip.setTipText(header.getToolTipText());
		tip.setSize(tip.getPreferredSize());
		layout(tip);
		final int w = Math.max(card.getWidth(), (int) Math.ceil(tip.getWidth() * scale));
		final BufferedImage img = new BufferedImage(w, card.getHeight() + (int) Math.ceil((tip.getHeight() + 8) * scale),
			BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		g.setColor(SidePanelStyle.PANEL);
		g.fillRect(0, 0, img.getWidth(), img.getHeight());
		g.drawImage(card, 0, 0, null);
		g.translate(0, card.getHeight() + 4 * scale);
		g.scale(scale, scale);
		tip.printAll(g);
		g.dispose();
		return img;
	}

	static BufferedImage rowTooltip(SidePanelModel.Input in, double scale, String id, String label)
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		panel.apply(new PanelSnapshot(SidePanelModel.fight(in)));
		styled(panel);
		final BufferedImage whole = draw(panel.tabs(), panel.fightTab(), scale);
		final SidePanelSection section = sectionOf(panel.fightTab(), id);
		SidePanelParts.Line line = null;
		for (Component k : all(section))
		{
			if (k instanceof SidePanelParts.Line && label.equals(((SidePanelParts.Line) k).left.getText()))
			{
				line = (SidePanelParts.Line) k;
			}
		}
		final java.awt.Rectangle r = javax.swing.SwingUtilities.convertRectangle(section.getParent(), section.getBounds(),
			panel.fightTab());
		final int top = panel.tabs().getHeight();
		final int y0 = (int) Math.floor((top + r.y) * scale);
		final int y1 = Math.min(whole.getHeight(), (int) Math.ceil((top + r.y + r.height) * scale));
		final BufferedImage card = whole.getSubimage(0, y0, (int) Math.ceil((SIDEBAR - SCROLLBAR) * scale), y1 - y0);
		final javax.swing.JToolTip tip = line.createToolTip();
		tip.setTipText(line.getToolTipText());
		tip.setSize(tip.getPreferredSize());
		layout(tip);
		final int w = Math.max(card.getWidth(), (int) Math.ceil(tip.getWidth() * scale));
		final BufferedImage img = new BufferedImage(w, card.getHeight() + (int) Math.ceil((tip.getHeight() + 8) * scale),
			BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		g.setColor(SidePanelStyle.PANEL);
		g.fillRect(0, 0, img.getWidth(), img.getHeight());
		g.drawImage(card, 0, 0, null);
		g.translate(0, card.getHeight() + 4 * scale);
		g.scale(scale, scale);
		tip.printAll(g);
		g.dispose();
		return img;
	}

	/**
	 * T-111: History's Tools with the Calibration report button, its tooltip, the status after a click, and greyed
	 * with nothing saved. Normal and Smooth, 1× and 2×, a folder each.
	 */
	private static void calibration(Path out) throws IOException
	{
		final String[] modes = {"normal", "smooth"};
		for (int m = 0; m < modes.length; m++)
		{
			smooth = m == 1;
			larger = false;
			final Path dir = out.resolve(modes[m]);
			Files.createDirectories(dir);
			for (double scale : new double[]{1, 2})
			{
				write(dir, "a-tools", scale, renderHistory(source(), t ->
				{
					t.putAway();
					t.setDebug(true);
				}, scale));
				write(dir, "b-after-a-click", scale, renderHistory(source(), t ->
				{
					t.putAway();
					t.setDebug(true);
					t.setStatus(PvmPerformancePanel.calibrationStatus(3));
				}, scale));
				write(dir, "c-nothing-saved", scale, renderHistory(source(java.util.Collections.emptyList()), t ->
				{
				}, scale));
				write(dir, "d-tooltip", scale, buttonTooltip(scale));
			}
		}
		smooth = false;
		larger = false;
		SidePanelStyle.setText(false, false);
	}

	/** T-114: Eric's kind of findings, in T-113's sentences (his four old groups, as if they were on a current build). */
	static final java.util.List<String> FINDINGS = java.util.Arrays.asList(
		"The Leviathan: you dealt 8% more than the plugin expected over 54 kills. More than luck explains, so likely a"
			+ " scoring error. (build abc1234)",
		"The Deranged archaeologist: you dealt 11% more than the plugin expected over 12 kills. More than luck explains,"
			+ " so likely a scoring error. (build abc1234)",
		"Araxxor's Acidic araxytes: you dealt 38% more than the plugin expected over 31 kills. More than luck explains,"
			+ " so likely a scoring error. (build abc1234)",
		"Araxxor's Mirrorback araxytes: you dealt 37% more than the plugin expected over 30 kills. More than luck"
			+ " explains, so likely a scoring error. (build abc1234)");
	/** T-114: what T-113's summary said on Eric's history on 2026-10-30 (read from it, not committed). */
	static final java.util.List<String> NOTHING_OFF = java.util.Collections.singletonList("Nothing looks off: over 15"
		+ " kills on current builds, your damage matches what the plugin expected.");

	/**
	 * T-114: History's Tools with Debug logging off (no button) and on; the findings card with Eric's kind of findings,
	 * and with nothing flagged. Normal and Smooth, 1× and 2×, cut to the Tools section.
	 */
	private static void calibrationFindings(Path out) throws IOException
	{
		final String file = "calibration-20261030-063906.txt";
		final String[] modes = {"normal", "smooth"};
		for (int m = 0; m < modes.length; m++)
		{
			smooth = m == 1;
			larger = false;
			final Path dir = out.resolve(modes[m]);
			Files.createDirectories(dir);
			for (double scale : new double[]{1, 2})
			{
				write(dir, "a-debug-off", scale, tools(t -> t.setDebug(false), scale));
				write(dir, "b-debug-on", scale, tools(t -> t.setDebug(true), scale));
				write(dir, "c-findings", scale, tools(t ->
				{
					t.setDebug(true);
					t.showCalibration(file, FINDINGS);
				}, scale));
				write(dir, "d-nothing-off", scale, tools(t ->
				{
					t.setDebug(true);
					t.showCalibration(file, NOTHING_OFF);
				}, scale));
			}
		}
		smooth = false;
		larger = false;
		SidePanelStyle.setText(false, false);
	}

	/** T-114: History's Tools section after {@code state}, cut out of the tab's render. */
	private static BufferedImage tools(java.util.function.Consumer<SidePanelHistoryTab> state, double scale)
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null, source());
		panel.tabs().select(panel.tabs().getTab(1));
		final SidePanelHistoryTab tab = panel.historyTab();
		tab.refresh();
		tab.putAway();
		state.accept(tab);
		styled(panel);
		final BufferedImage whole = draw(panel.tabs(), tab, scale);
		final SidePanelSection section = sectionOf(tab, "historyTools");
		final java.awt.Rectangle r = javax.swing.SwingUtilities.convertRectangle(section.getParent(), section.getBounds(),
			tab);
		final int top = panel.tabs().getHeight();
		final int y0 = (int) Math.floor((top + r.y) * scale);
		final int y1 = Math.min(whole.getHeight(), (int) Math.ceil((top + r.y + r.height) * scale));
		return whole.getSubimage(0, y0, (int) Math.ceil((SIDEBAR - SCROLLBAR) * scale), y1 - y0);
	}

	/** T-111: the Calibration report button's tooltip under History's Tools. */
	private static BufferedImage buttonTooltip(double scale)
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null, source());
		panel.tabs().select(panel.tabs().getTab(1));
		final SidePanelHistoryTab tab = panel.historyTab();
		tab.refresh();
		tab.putAway();
		tab.setDebug(true);
		styled(panel);
		final BufferedImage whole = draw(panel.tabs(), tab, scale);
		final javax.swing.JButton b = tab.calibrationButton();
		final javax.swing.JToolTip tip = b.createToolTip();
		tip.setTipText(b.getToolTipText());
		tip.setSize(tip.getPreferredSize());
		layout(tip);
		final int w = Math.max(whole.getWidth(), (int) Math.ceil(tip.getWidth() * scale));
		final BufferedImage img = new BufferedImage(w, whole.getHeight() + (int) Math.ceil((tip.getHeight() + 8) * scale),
			BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		g.setColor(SidePanelStyle.PANEL);
		g.fillRect(0, 0, img.getWidth(), img.getHeight());
		g.drawImage(whole, 0, 0, null);
		g.translate(0, whole.getHeight() + 4 * scale);
		g.scale(scale, scale);
		tip.printAll(g);
		g.dispose();
		return img;
	}

	/**
	 * T-112 item 5: Details and Damage taken in Eric's pick (layout A), on real kills read
	 * from a {@code history.json} outside the repo (Eric's: an Araxxor, a Leviathan and a Vardorvis kill, each the one
	 * with the most rows). Normal and Smooth text, at 1× and 2×, and Larger at 1×, a folder each.
	 */
	private static void detailsGroups(Path out, Path history) throws IOException
	{
		final com.google.gson.Gson gson = new com.google.gson.Gson();
		final java.util.List<Fight> fights = new java.util.ArrayList<>(java.util.Arrays.asList(gson.fromJson(
			new String(Files.readAllBytes(history), java.nio.charset.StandardCharsets.UTF_8), Fight[].class)));
		final BossRules rules = BossRules.load(gson, null);
		final java.util.List<Encounter> rooms = SidePanelHistory.allRooms(fights);
		final String[][] bosses = {{"araxxor", "Araxxor"}, {"leviathan", "The Leviathan"}, {"vardorvis", "Vardorvis"}};
		final java.util.Map<String, SidePanelModel.Input> kills = new java.util.LinkedHashMap<>();
		for (String[] b : bosses)
		{
			SidePanelModel.Input best = null;
			int most = -1;
			for (Encounter room : rooms)
			{
				if (!b[1].equals(room.getName()) || !SidePanelHistory.isKill(room))
				{
					continue;
				}
				final SidePanelModel.Input in = SidePanelHistory.savedKill(fights, room, 0L, SidePanelExamples.at(23, 0),
					SidePanelExamples.ZONE, new SidePanelHistory.Versions()
					{
						public String name(Encounter r, int maxHp)
						{
							return null;
						}

						public java.util.Set<String> avoidable(Encounter r)
						{
							return SidePanelModel.avoidableAttacks(rules, r.getFights());
						}
					});
				in.roomDamageSources = SidePanelModel.roomDamageSources(java.util.Collections.singletonList(room), rules);
				final PanelSnapshot.FightTab tab = SidePanelModel.fight(in);
				final int n = (tab.details == null ? 0 : tab.details.rows.size()) + (tab.taken == null ? 0
					: tab.taken.rows.size());
				if (n > most)
				{
					most = n;
					best = in;
				}
			}
			if (best != null)
			{
				kills.put(b[0], best);
				System.out.println(b[1] + ": " + most + " rows");
			}
		}
		final String[] modes = {"normal", "smooth", "larger"};
		for (int m = 0; m < modes.length; m++)
		{
			smooth = m == 1;
			larger = m == 2;
			final Path dir = out.resolve(modes[m]);
			Files.createDirectories(dir);
			for (double scale : larger ? new double[]{1} : new double[]{1, 2})
			{
				for (java.util.Map.Entry<String, SidePanelModel.Input> k : kills.entrySet())
				{
					// Eric's pick: layout A. Eating and mechanics are Where the time went's alone now.
					write(dir, k.getKey(), scale, sections(k.getValue(), scale, "details", "taken"));
				}
			}
		}
		smooth = false;
		larger = false;
		SidePanelStyle.setText(false, false);
	}

	/** T-112: the Fight tab cut from section {@code from}'s top to section {@code to}'s bottom. */
	static BufferedImage sections(SidePanelModel.Input in, double scale, String from, String to)
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		panel.apply(new PanelSnapshot(SidePanelModel.fight(in)));
		styled(panel);
		return crop(panel, scale, from, to);
	}

	private static BufferedImage crop(PvmPerformancePanel panel, double scale, String from, String to)
	{
		final BufferedImage whole = draw(panel.tabs(), panel.fightTab(), scale);
		final SidePanelSection a = sectionOf(panel.fightTab(), from);
		final SidePanelSection b = sectionOf(panel.fightTab(), to);
		final java.awt.Rectangle ra = javax.swing.SwingUtilities.convertRectangle(a.getParent(), a.getBounds(),
			panel.fightTab());
		final java.awt.Rectangle rb = javax.swing.SwingUtilities.convertRectangle(b.getParent(), b.getBounds(),
			panel.fightTab());
		final int top = panel.tabs().getHeight();
		final int y0 = (int) Math.floor((top + ra.y) * scale);
		final int y1 = Math.min(whole.getHeight(), (int) Math.ceil((top + rb.y + rb.height) * scale));
		return whole.getSubimage(0, y0, (int) Math.ceil((SIDEBAR - SCROLLBAR) * scale), y1 - y0);
	}

	/**
	 * T-112: the Fight tab's new order (after a kill, live, a monster run, and History's picked kill); the luck curve at the top 1%, the middle and the bottom 10% with its tooltip; and the hover
	 * line at three moments on the chart and once from the strip. Normal, Smooth and Larger, 1× and 2×, a folder each.
	 */
	private static void fightOrder(Path out) throws IOException
	{
		final String[] modes = {"normal", "smooth", "larger"};
		for (int m = 0; m < modes.length; m++)
		{
			smooth = m == 1;
			larger = m == 2;
			final Path dir = out.resolve(modes[m]);
			Files.createDirectories(dir);
			for (double scale : new double[]{1, 2})
			{
				write(dir, "a-kill", scale, render(SidePanelExamples.dead(), scale));
				write(dir, "b-live", scale, render(SidePanelExamples.live(), scale));
				write(dir, "c-monster-run", scale, render(SidePanelRunExamples.saved(), scale));
				write(dir, "d-history-picked-kill", scale, renderSavedThisSession(scale));
				write(dir, "e1-curve-top-1", scale, curveTooltip(SidePanelExamples.luckSaved(15), scale));
				write(dir, "e2-curve-middle", scale, curveTooltip(SidePanelExamples.luckSaved(2), scale));
				write(dir, "e3-curve-bottom-10", scale, curveTooltip(SidePanelExamples.luckSaved(-7), scale));
				final SidePanelModel.Input kill = SidePanelExamples.dead();
				write(dir, "f1-hover-early", scale, hoverCrop(kill, scale, 30, false));
				write(dir, "f2-hover-middle", scale, hoverCrop(kill, scale, 115, false));
				write(dir, "f3-hover-late", scale, hoverCrop(kill, scale, 215, false));
				write(dir, "f4-hover-from-strip", scale, hoverCrop(kill, scale, 71, true));
			}
		}
		smooth = false;
		larger = false;
		SidePanelStyle.setText(false, false);
	}

	/** T-112: the Luck card with its tooltip under it. */
	static BufferedImage curveTooltip(SidePanelModel.Input in, double scale)
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		panel.apply(new PanelSnapshot(SidePanelModel.fight(in)));
		styled(panel);
		final BufferedImage whole = draw(panel.tabs(), panel.fightTab(), scale);
		final BufferedImage card = crop(panel, whole, scale, "curve", "curve");
		SidePanelLuckCurve curve = null;
		for (Component k : all(panel.fightTab()))
		{
			if (k instanceof SidePanelLuckCurve)
			{
				curve = (SidePanelLuckCurve) k;
			}
		}
		return withTooltip(card, curve, scale);
	}

	/**
	 * T-112: Damage over time and Ticks with tick {@code tick} hovered: from the chart, or from its cell in the strip
	 * ({@code fromStrip}), through the same mouse listeners a pointer would reach.
	 */
	static BufferedImage hoverCrop(SidePanelModel.Input in, double scale, int tick, boolean fromStrip)
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		panel.apply(new PanelSnapshot(SidePanelModel.fight(in)));
		styled(panel);
		draw(panel.tabs(), panel.fightTab(), scale);
		SidePanelDamageChart chart = null;
		SidePanelTickStrip strip = null;
		for (Component k : all(panel.fightTab()))
		{
			if (k instanceof SidePanelDamageChart)
			{
				chart = (SidePanelDamageChart) k;
			}
			if (k instanceof SidePanelTickStrip)
			{
				strip = (SidePanelTickStrip) k;
			}
		}
		final Component target = fromStrip ? strip : chart;
		final int x = fromStrip ? (int) Math.round((tick % SidePanelTickStrip.PER_ROW) * (SidePanelTickStrip.TICK
			+ SidePanelTickStrip.GAP) + 1) : (int) Math.round(chart.xOf(tick));
		final int y = fromStrip ? (tick / SidePanelTickStrip.PER_ROW) * (SidePanelTickStrip.TICK_HEIGHT
			+ SidePanelTickStrip.ROW_GAP) + 2 : 10;
		for (java.awt.event.MouseMotionListener l : target.getMouseMotionListeners())
		{
			l.mouseMoved(new java.awt.event.MouseEvent(target, java.awt.event.MouseEvent.MOUSE_MOVED, 0L, 0, x, y, 0,
				false));
		}
		final BufferedImage whole = draw(panel.tabs(), panel.fightTab(), scale);
		return crop(panel, whole, scale, "chart", "ticks");
	}

	/** The Fight tab's render from section {@code first}'s top to section {@code last}'s bottom. */
	private static BufferedImage crop(PvmPerformancePanel panel, BufferedImage whole, double scale, String first,
		String last)
	{
		final SidePanelSection a = sectionOf(panel.fightTab(), first);
		final SidePanelSection b = sectionOf(panel.fightTab(), last);
		final java.awt.Rectangle ra = javax.swing.SwingUtilities.convertRectangle(a.getParent(), a.getBounds(),
			panel.fightTab());
		final java.awt.Rectangle rb = javax.swing.SwingUtilities.convertRectangle(b.getParent(), b.getBounds(),
			panel.fightTab());
		final int top = panel.tabs().getHeight();
		final int y0 = (int) Math.floor((top + ra.y) * scale);
		final int y1 = Math.min(whole.getHeight(), (int) Math.ceil((top + rb.y + rb.height) * scale));
		return whole.getSubimage(0, y0, (int) Math.ceil((SIDEBAR - SCROLLBAR) * scale), y1 - y0);
	}

	/** {@code image} with {@code c}'s tooltip drawn under it. */
	private static BufferedImage withTooltip(BufferedImage image, javax.swing.JComponent c, double scale)
	{
		final javax.swing.JToolTip tip = c.createToolTip();
		tip.setTipText(c.getToolTipText());
		tip.setSize(tip.getPreferredSize());
		layout(tip);
		final int w = Math.max(image.getWidth(), (int) Math.ceil(tip.getWidth() * scale));
		final BufferedImage img = new BufferedImage(w, image.getHeight() + (int) Math.ceil((tip.getHeight() + 8) * scale),
			BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		g.setColor(SidePanelStyle.PANEL);
		g.fillRect(0, 0, img.getWidth(), img.getHeight());
		g.drawImage(image, 0, 0, null);
		g.translate(0, image.getHeight() + 4 * scale);
		g.scale(scale, scale);
		tip.printAll(g);
		g.dispose();
		return img;
	}

	/**
	 * T-112 item 6: the Damage card with the fixture's Emberlight specials (which drain on landing), one spec of a weapon
	 * that drains by damage, an unmatched row, a warhammer, an elder maul that missed, and a Bandos godsword. Normal,
	 * Smooth and Larger, at 1× and 2×.
	 */
	private static void specRow(Path out) throws IOException
	{
		final String[] modes = {"normal", "smooth", "larger"};
		for (int m = 0; m < modes.length; m++)
		{
			smooth = m == 1;
			larger = m == 2;
			final Path dir = out.resolve(modes[m]);
			Files.createDirectories(dir);
			for (double scale : new double[]{1, 2})
			{
				final SidePanelModel.Input two = SidePanelExamples.sireWeapons();
				write(dir, "a-two-specs", scale, card(two, scale, "damage"));
				final SidePanelModel.Input one = SidePanelExamples.sireWeapons();
				one.weaponRows = new java.util.ArrayList<>(SidePanelModel.weaponRows(one.view));
				for (SidePanelModel.WeaponRow w : one.weaponRows)
				{
					if (w.special)
					{
						w.name = "Voidwaker";
						w.itemId = -1;
						w.attacks = 1;
					}
				}
				write(dir, "b-one-spec", scale, card(one, scale, "damage"));
				final SidePanelModel.Input unmatched = SidePanelExamples.sireWeapons();
				unmatched.weaponRows = new java.util.ArrayList<>(SidePanelModel.weaponRows(unmatched.view));
				for (SidePanelModel.WeaponRow w : unmatched.weaponRows)
				{
					if (w.special)
					{
						w.expected = 36;
					}
				}
				write(dir, "c-unmatched", scale, card(unmatched, scale, "damage"));
				// T-112 item 6, Eric 06:52: specials that drain on landing read their landing.
				write(dir, "d-warhammer-one-of-two", scale, card(special(SidePanelExamples.sireWeapons(),
					"Dragon warhammer", net.runelite.api.gameval.ItemID.DRAGON_WARHAMMER, 2, 1), scale, "damage"));
				write(dir, "e-elder-maul-missed", scale, card(special(SidePanelExamples.sireWeapons(), "Elder maul",
					net.runelite.api.gameval.ItemID.ELDER_MAUL, 1, 0), scale, "damage"));
				write(dir, "f-bgs-damage", scale, card(special(SidePanelExamples.sireWeapons(), "Bandos godsword",
					net.runelite.api.gameval.ItemID.BGS, 2, 2), scale, "damage"));
			}
		}
		smooth = false;
		larger = false;
		SidePanelStyle.setText(false, false);
	}

	/** T-112 item 6: the fixture's special row as this weapon, {@code landed} of {@code attacks} landing. */
	private static SidePanelModel.Input special(SidePanelModel.Input in, String name, int itemId, int attacks, int landed)
	{
		in.weaponRows = new java.util.ArrayList<>(SidePanelModel.weaponRows(in.view));
		for (SidePanelModel.WeaponRow w : in.weaponRows)
		{
			if (w.special)
			{
				final double chance = w.landedKnown == 0 ? 0 : w.landedExpected / w.landedKnown;
				w.name = name;
				w.itemId = itemId;
				w.attacks = attacks;
				w.landed = landed;
				w.landedKnown = attacks;
				w.landedExpected = chance * attacks;
			}
		}
		return in;
	}

	/**
	 * T-119: Damage taken, Other damage dealt and Defence drains reworded: the Trip tab like Eric's (heavy venom, the
	 * room's damage, warhammer and godsword specials), the same without this session's timelines, a kill with drains and
	 * damage taken, and a kill with none taken. Normal, Smooth and Larger, 1× and 2×.
	 */
	private static void wording(Path out) throws IOException
	{
		final String[] modes = {"normal", "smooth", "larger"};
		for (int m = 0; m < modes.length; m++)
		{
			smooth = m == 1;
			larger = m == 2;
			final Path dir = out.resolve(modes[m]);
			Files.createDirectories(dir);
			for (double scale : new double[]{1, 2})
			{
				write(dir, "a-trip-like-erics", scale, renderTrip(SidePanelWordingExamples.ericsTrip(), scale));
				final SidePanelModel.Input old = SidePanelWordingExamples.ericsTrip();
				old.drain = SidePanelWordingExamples.drains(false);
				write(dir, "b-trip-no-timelines", scale, renderTrip(old, scale));
				final SidePanelModel.Input kill = SidePanelExamples.sireEmberlight();
				write(dir, "c-kill", scale, render(kill, scale));
				final SidePanelModel.Input none = SidePanelExamples.dead();
				((LedgerExamples.FakeScope) none.scope).damageTaken = 0;
				write(dir, "d-kill-nothing-taken", scale, render(none, scale));
			}
		}
		smooth = false;
		larger = false;
		SidePanelStyle.setText(false, false);
	}

	/**
	 * T-118: time won or lost in ticks: a kill (tiles, Where the time went, Execution, Luck), a kill with drains (the
	 * Defence card), History's picked kill, the Trip tab (T-119's like Eric's), and a monster run. Normal, Smooth and
	 * Larger, 1× and 2×.
	 */
	private static void ticks(Path out) throws IOException
	{
		final String[] modes = {"normal", "smooth", "larger"};
		for (int m = 0; m < modes.length; m++)
		{
			smooth = m == 1;
			larger = m == 2;
			final Path dir = out.resolve(modes[m]);
			Files.createDirectories(dir);
			for (double scale : new double[]{1, 2})
			{
				write(dir, "a-kill", scale, render(SidePanelExamples.dead(), scale));
				write(dir, "b-kill-with-drains", scale, render(SidePanelExamples.sireEmberlight(), scale));
				write(dir, "c-history-picked-kill", scale, renderSavedThisSession(scale));
				write(dir, "d-trip", scale, renderTrip(SidePanelWordingExamples.ericsTrip(), scale));
				write(dir, "e-monster-run", scale, render(SidePanelRunExamples.saved(), scale));
				write(dir, "f-luck-saved-a-lot", scale, render(SidePanelExamples.luckSaved(60), scale));
			}
		}
		smooth = false;
		larger = false;
		SidePanelStyle.setText(false, false);
	}

	/**
	 * T-123: Where the time went's setup parts in damage (a kill; one saved before T-117, which keeps its ticks; the card
	 * alone), and the Trip tab after New trip right after a kill and across a run of monsters. Normal, Smooth and Larger,
	 * at 1× and 2×.
	 */
	private static void setupDamageAndNewTrip(Path out) throws IOException
	{
		final String[] modes = {"normal", "smooth", "larger"};
		for (int m = 0; m < modes.length; m++)
		{
			smooth = m == 1;
			larger = m == 2;
			final Path dir = out.resolve(modes[m]);
			Files.createDirectories(dir);
			for (double scale : new double[]{1, 2})
			{
				write(dir, "a-kill", scale, render(SidePanelExamples.dead(), scale));
				write(dir, "b-time-budget", scale, card(SidePanelExamples.dead(), scale, "budget"));
				write(dir, "c-time-budget-ruled", scale, card(SidePanelExamples.ruledKill(true), scale, "budget"));
				final SidePanelModel.Input before = SidePanelExamples.dead();
				((LedgerExamples.FakeScope) before.scope).idealSetupDamage = 0;
				write(dir, "d-time-budget-saved-before-T-117", scale, card(before, scale, "budget"));
				write(dir, "e-trip-new-trip-right-after-a-kill", scale,
					renderTrip(SidePanelTripResetTest.newTripRightAfterAKill(), scale));
				write(dir, "f-trip-run-across-new-trip", scale, renderTrip(SidePanelTripResetTest.runAcrossNewTrip(), scale));
			}
		}
		smooth = false;
		larger = false;
		SidePanelStyle.setText(false, false);
	}

	/**
	 * T-125: the Party tab (Eric's pick, option B) on the example boards, in Normal, Smooth and Larger at 1× and 2×: the
	 * meter on each board and switch, a member opened, the chart hovered, the empty states, and the Fight tab's line.
	 */
	private static void partyTab(Path out) throws IOException
	{
		final String[] modes = {"normal", "smooth", "larger"};
		for (int m = 0; m < modes.length; m++)
		{
			smooth = m == 1;
			larger = m == 2;
			final Path dir = out.resolve(modes[m]);
			Files.createDirectories(dir);
			for (double scale : new double[]{1, 2})
			{
				// T-130: one page, your Fight panels under the party by default.
				write(dir, "a-you-selected-by-default", scale, party(SidePanelPartyExamples.toaYouAndBrindle(), null, -1,
					null, scale));
				write(dir, "b-member-selected-after-their-room", scale, party(SidePanelPartyExamples.toaYouAndBrindle(),
					22L, -1, null, scale));
				// T-132: the tint options. The default: the area behind the member's part toward their colour, yours plain.
				SidePanelPartyTab.tintYours = true;
				write(dir, "a2-option-yours-tinted-too", scale, party(SidePanelPartyExamples.toaYouAndBrindle(), null, -1,
					null, scale));
				SidePanelPartyTab.tintYours = false;
				SidePanelPartyTab.tint = SidePanelPartyTab.Tint.CARDS;
				write(dir, "b2-option-cards-tinted", scale, party(SidePanelPartyExamples.toaYouAndBrindle(), 22L, -1, null,
					scale));
				SidePanelPartyTab.tintYours = true;
				write(dir, "a3-option-cards-tinted-yours-too", scale, party(SidePanelPartyExamples.toaYouAndBrindle(), null,
					-1, null, scale));
				SidePanelPartyTab.tintYours = false;
				SidePanelPartyTab.tint = SidePanelPartyTab.Tint.AREA;
				// T-132: a member's Damage card on adds, from her room message.
				SidePanelFightTab.useTarget(1);
				write(dir, "k-member-damage-on-adds", scale, party(SidePanelPartyExamples.araxxorWithAdds(), 22L, -1, null,
					scale));
				SidePanelFightTab.resetTarget();
				write(dir, "c-member-selected-mid-fight", scale, party(SidePanelPartyExamples.tobQuillonLive(), 31L, 120,
					null, scale));
				write(dir, "d-cox-8-one-selected", scale, party(SidePanelPartyExamples.cox(), 51L, -1, null, scale));
				write(dir, "e-no-room-message-yet-compact", scale, party(SidePanelPartyExamples.toa(), 22L, -1, null,
					scale));
				write(dir, "f-meter-with-pictures", scale, party(SidePanelPartyExamples.toaWithPictures(), null, -1, null,
					scale));
				write(dir, "g-chart-you-and-your-echo", scale, party(SidePanelPartyExamples.withEcho(), null, 120, null,
					scale));
				write(dir, "h-whole-raid-luck", scale, party(SidePanelPartyExamples.tobRaid(), null, -1,
					SidePanelParty.Metric.LUCK, scale));
				// T-129: the empty states.
				final SidePanelParty.Input waiting = new SidePanelParty.Input();
				waiting.inParty = true;
				waiting.partySize = 3;
				write(dir, "i-in-a-party-before-anyone-shares", scale, party(waiting, null, -1, null, scale));
				write(dir, "j-not-in-a-party", scale, party(SidePanelPartyExamples.notInParty(), null, -1, null, scale));
			}
		}
		smooth = false;
		larger = false;
		SidePanelStyle.setText(false, false);
	}

	/**
	 * T-134: DPS counting all your damage: the Fight tab's tiles with the DPS tile's tooltip (a thrall; all three kinds;
	 * none), a run's tiles, and the Party meter with a member's other damage. Normal, Smooth and Larger at 1x and 2x.
	 */
	private static void allYourDamage(Path out) throws IOException
	{
		final String[] modes = {"normal", "smooth", "larger"};
		for (int m = 0; m < modes.length; m++)
		{
			smooth = m == 1;
			larger = m == 2;
			final Path dir = out.resolve(modes[m]);
			Files.createDirectories(dir);
			for (double scale : new double[]{1, 2})
			{
				write(dir, "a-tile-with-a-thrall", scale, dpsTiles(SidePanelExamples.dead(), scale));
				final SidePanelModel.Input three = SidePanelExamples.dead();
				final LedgerExamples.FakeScope s = (LedgerExamples.FakeScope) three.scope;
				s.reflectDamage = 41;
				s.dotDamage = 30;
				three.scoredReflect = 41;
				three.scoredDot = 30;
				three.scoredDotByKind = java.util.Collections.singletonMap("venom", 30);
				write(dir, "b-tile-with-all-three", scale, dpsTiles(three, scale));
				final SidePanelModel.Input none = SidePanelExamples.dead();
				((LedgerExamples.FakeScope) none.scope).thrallDamage = 0;
				none.scoredThrall = 0;
				write(dir, "c-tile-weapon-only", scale, dpsTiles(none, scale));
				final SidePanelTimelineExamples.Built run = SidePanelRunExamples.demonRun(12);
				run.fight.recordThrallDamage(120);
				write(dir, "d-run-tiles-with-a-thrall", scale, dpsTiles(SidePanelRunExamples.live(run), scale));
				final SidePanelParty.Input party = SidePanelPartyExamples.toa();
				party.members.get(0).figures.o = 80;
				party.members.get(2).figures.o = 410;
				write(dir, "e-party-meter-with-other-damage", scale, party(party, null, -1, null, scale));
			}
		}
		smooth = false;
		larger = false;
		SidePanelStyle.setText(false, false);
	}

	/** The Fight tab down to its four tiles, with the DPS tile's tooltip under it where it has one. */
	static BufferedImage dpsTiles(SidePanelModel.Input in, double scale)
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		panel.apply(new PanelSnapshot(SidePanelModel.fight(in)));
		styled(panel);
		final BufferedImage whole = draw(panel.tabs(), panel.fightTab(), scale);
		SidePanelStatTile dps = null;
		for (Component k : all(panel.fightTab()))
		{
			if (k instanceof SidePanelStatTile)
			{
				for (Component t : all((Container) k))
				{
					if (t instanceof SidePanelText && "DPS".equals(((SidePanelText) t).getText()))
					{
						dps = (SidePanelStatTile) k;
					}
				}
			}
		}
		final Container grid = dps.getParent();
		final java.awt.Rectangle r = javax.swing.SwingUtilities.convertRectangle(grid.getParent(), grid.getBounds(),
			panel.fightTab());
		final int top = panel.tabs().getHeight();
		final BufferedImage tiles = whole.getSubimage(0, 0, (int) Math.ceil((SIDEBAR - SCROLLBAR) * scale),
			Math.min(whole.getHeight(), (int) Math.ceil((top + r.y + r.height + 4) * scale)));
		return dps.getToolTipText() == null ? tiles : withTooltip(tiles, dps, scale);
	}

	/** The Party tab on {@code in}: {@code open} a member's breakdown, {@code hover} a tick of the chart, a metric picked. */
	static BufferedImage party(SidePanelParty.Input in, Long open, int hover, SidePanelParty.Metric metric, double scale)
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		styled(panel);
		panel.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.empty()), null, SidePanelParty.party(in)));
		panel.tabs().select(panel.tabs().getTab(3));
		final SidePanelPartyTab tab = panel.partyTab();
		if (open != null)
		{
			tab.select(open);
		}
		if (metric != null)
		{
			tab.pick(metric);
		}
		final BufferedImage first = draw(panel.tabs(), tab, scale);
		if (hover < 0 || tab.chart() == null)
		{
			return first;
		}
		tab.chart().setHover(hover);
		return draw(panel.tabs(), tab, scale);
	}

	/** One section of the Fight tab, cut out of its render: its header and card. */
	static BufferedImage card(SidePanelModel.Input in, double scale, String id)
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		panel.apply(new PanelSnapshot(SidePanelModel.fight(in)));
		styled(panel);
		final BufferedImage whole = draw(panel.tabs(), panel.fightTab(), scale);
		final SidePanelSection section = sectionOf(panel.fightTab(), id);
		final java.awt.Rectangle r = javax.swing.SwingUtilities.convertRectangle(section.getParent(), section.getBounds(),
			panel.fightTab());
		final int top = panel.tabs().getHeight();
		final int y0 = (int) Math.floor((top + r.y) * scale);
		final int y1 = Math.min(whole.getHeight(), (int) Math.ceil((top + r.y + r.height) * scale));
		return whole.getSubimage(0, y0, (int) Math.ceil((SIDEBAR - SCROLLBAR) * scale), y1 - y0);
	}

	static SidePanelSection sectionOf(Container c, String id)
	{
		for (Component k : c.getComponents())
		{
			if (k instanceof SidePanelSection && id.equals(((SidePanelSection) k).id()))
			{
				return (SidePanelSection) k;
			}
			if (k instanceof Container)
			{
				final SidePanelSection s = sectionOf((Container) k, id);
				if (s != null)
				{
					return s;
				}
			}
		}
		return null;
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
