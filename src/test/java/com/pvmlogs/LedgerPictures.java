package com.pvmlogs;

import java.awt.Font;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.client.ui.FontManager;

/**
 * Not a test: writes every v1 overlay state, from {@link LedgerRows}' real
 * output and the overlay's own font roles, as input files for the design
 * harness ({@code claude/specs/overlay-harness/Render.java} in the project),
 * which draws them with RuneLite's own panel and line components and fonts.
 * <pre>
 *   java -cp &lt;test classpath&gt; com.pvmlogs.LedgerPictures &lt;out dir&gt; &lt;name=scene.png&gt;...
 *   java -cp &lt;harness classpath&gt; Render &lt;out dir&gt;/*.txt
 * </pre>
 * One file per state, interface font and background.
 */
public final class LedgerPictures
{
	private LedgerPictures()
	{
	}

	/** Every state in the spec's section 4, with the rows the canvas draws. */
	static Map<String, List<LedgerRows.Row>> states()
	{
		final LedgerRows.Settings all = new LedgerRows.Settings();
		final LedgerRows.Loadout tentacle = LedgerExamples.loadout();
		final Map<String, List<LedgerRows.Row>> s = new LinkedHashMap<>();
		s.put("1-pre", LedgerRows.build(null, LedgerRows.State.preAttack("Vardorvis", tentacle), all));
		s.put("2-mid", LedgerRows.build(LedgerExamples.midFight(),
			LedgerRows.State.fight("Vardorvis", false, false, false).withLoadout(tentacle), all));
		// Illustrative average over 10 kills: the clock's median 1:59 (middle half 1:55 to 2:05); with the luck taken out,
		// 2:10 (2:05 to 2:15); three of the ten had worse luck than this kill's -10.5% of its clock.
		s.put("3-dead", LedgerRows.build(LedgerExamples.kill(), LedgerRows.State.fight("Vardorvis", true, true, false)
			.withLoadout(tentacle).withAverage(new LedgerHistory.Average(new LedgerHistory.Spread(119, 115, 125),
				new LedgerHistory.Spread(130, 125, 135),
				new double[]{-0.25, -0.2, -0.15, -0.08, -0.05, -0.02, 0, 0.03, 0.06, 0.1}, 10)), all));
		s.put("4-ended", LedgerRows.build(LedgerExamples.ended(),
			LedgerRows.State.fight("Vardorvis", true, false, false), all));
		s.put("5-trip", LedgerRows.build(LedgerExamples.trip(), LedgerRows.State.trip(6, false, false), all));
		s.put("6-raid", LedgerRows.build(LedgerExamples.raid(),
			LedgerRows.State.raid("Tombs of Amascut", 3, false, false), all));
		s.put("7-unsure", LedgerRows.build(LedgerExamples.midFight(),
			LedgerRows.State.fight("Vardorvis", false, false, true).withLoadout(tentacle), all));
		// Without monster data there is no accuracy to show; the max hit is known.
		final LedgerRows.Loadout noAccuracy = new LedgerRows.Loadout();
		noAccuracy.attack = "Osmumten's fang";
		noAccuracy.maxHit = 44;
		s.put("8-nodata", LedgerRows.build(LedgerExamples.noData(),
			LedgerRows.State.fight("New boss", false, false, false).withLoadout(noAccuracy), all));
		s.put("9-setup", LedgerRows.build(LedgerExamples.midFightBeforeT010(),
			LedgerRows.State.fight("Vardorvis", false, false, false).withLoadout(tentacle), all));
		s.put("10-pre-unsure", LedgerRows.build(null,
			LedgerRows.State.preAttack("Vardorvis", tentacle, true), all));
		// T-016: brewed down for most of the fight. The word stands in for 0%, the seconds carry the cost.
		final LedgerExamples.FakeScope brewed = LedgerExamples.midFight();
		brewed.attacksDrained = 12;
		brewed.setup(0.95, 0.0, 3.36, 0);
		s.put("11-drained", LedgerRows.build(brewed,
			LedgerRows.State.fight("Vardorvis", false, false, false).withLoadout(tentacle), all));
		// A panel the player dragged narrower than a long attack's name (Eric, 2026-10-26): drawn at SAVED_WIDTH, the
		// name falls back to the spell rather than wrapping.
		final LedgerRows.Loadout staff = LedgerExamples.loadout();
		staff.attack = "Harmonised nightmare staff (Fire Surge)";
		s.put("12-saved-width", LedgerRows.build(LedgerExamples.midFight(),
			LedgerRows.State.fight("Vardorvis", false, false, false).withLoadout(staff), all));
		return s;
	}

	/** States drawn at a width the player saved rather than the panel's own. */
	static final Map<String, Integer> SAVED_WIDTH = new LinkedHashMap<>();

	static
	{
		SAVED_WIDTH.put("12-saved-width", 160);
	}

	public static void main(String[] args) throws IOException
	{
		final Path out = Paths.get(args.length > 0 ? args[0] : "build/ledger-pictures");
		Files.createDirectories(out);
		final Map<String, String> scenes = new LinkedHashMap<>();
		for (int i = 1; i < args.length; i++)
		{
			final int eq = args[i].indexOf('=');
			scenes.put(args[i].substring(0, eq), args[i].substring(eq + 1));
		}
		if (scenes.isEmpty())
		{
			scenes.put("flat", null);
		}
		final Map<String, Font> fonts = new LinkedHashMap<>();
		fonts.put("regular", FontManager.getRunescapeFont());
		fonts.put("bold", FontManager.getRunescapeBoldFont());
		fonts.put("small", FontManager.getRunescapeSmallFont());

		for (Map.Entry<String, List<LedgerRows.Row>> state : states().entrySet())
		{
			for (Map.Entry<String, Font> font : fonts.entrySet())
			{
				final PvmPerformanceOverlay.FontRoles roles = PvmPerformanceOverlay.FontRoles.of(font.getValue());
				final Integer saved = SAVED_WIDTH.get(state.getKey());
				final List<LedgerRows.Row> rows = new ArrayList<>();
				for (LedgerRows.Row row : state.getValue())
				{
					if (saved == null)
					{
						rows.add(row);
						continue;
					}
					// As the overlay fits it, with the same font metrics.
					final java.awt.FontMetrics left = METRICS.getFontMetrics(roles.get(row.leftFont));
					final java.awt.FontMetrics right = METRICS.getFontMetrics(roles.get(row.rightFont));
					rows.add(PvmPerformanceOverlay.fit(row, left::stringWidth, right.stringWidth(row.right), saved));
				}
				for (Map.Entry<String, String> scene : scenes.entrySet())
				{
					final String name = state.getKey() + "-" + font.getKey() + "-" + scene.getKey();
					final List<String> lines = new ArrayList<>();
					lines.add("@ font=" + font.getKey() + " bg=70,61,50,156"
						+ (scene.getValue() == null ? "" : " scene=" + scene.getValue())
						+ " x=6 y=6 pad=6 width=" + (saved == null ? "auto" : String.valueOf(saved))
						+ " padding=" + PvmPerformanceOverlay.PADDING
						+ " out=" + out.resolve(name + ".png"));
					for (LedgerRows.Row row : rows)
					{
						lines.add(row.isBlank() ? "G" : String.format("L|%s|%s|lf=%s rf=%s lc=%06X rc=%06X",
							row.left, row.right, roleName(roles.get(row.leftFont)), roleName(roles.get(row.rightFont)),
							row.leftColour.rgb, row.rightColour.rgb));
					}
					Files.write(out.resolve(name + ".txt"), lines, StandardCharsets.UTF_8);
				}
			}
		}
	}

	/** Font metrics for fitting, off a scratch image. */
	private static final java.awt.Graphics2D METRICS =
		new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB).createGraphics();

	/** The harness's name for one of the three RuneScape fonts. */
	private static String roleName(Font f)
	{
		if (f == FontManager.getRunescapeBoldFont())
		{
			return "bold";
		}
		if (f == FontManager.getRunescapeSmallFont())
		{
			return "small";
		}
		if (f == FontManager.getRunescapeFont())
		{
			return "regular";
		}
		throw new IllegalArgumentException("not a RuneScape font: " + f);
	}
}
