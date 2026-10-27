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
		final Map<String, List<LedgerRows.Row>> s = new LinkedHashMap<>();
		s.put("1-pre", LedgerRows.build(null, LedgerRows.State.preAttack("Vardorvis"), all));
		s.put("2-mid", LedgerRows.build(LedgerExamples.midFight(),
			LedgerRows.State.fight("Vardorvis", false, false, false), all));
		// Illustrative average: the clock's median 1:59 over 10 kills.
		// T-019: illustrative damage taken after the fight.
		final LedgerExamples.FakeScope dead = LedgerExamples.kill();
		dead.dotTaken = 27;
		dead.biggestHitTaken = 41;
		dead.damageTakenUnprotected = 85;
		s.put("3-dead", LedgerRows.build(dead, LedgerRows.State.fight("Vardorvis", true, true, false)
			.withAverage(new LedgerHistory.Average(new LedgerHistory.Spread(119, 115, 125), 10)), all));
		s.put("4-ended", LedgerRows.build(LedgerExamples.ended(),
			LedgerRows.State.fight("Vardorvis", true, false, false), all));
		s.put("5-trip", LedgerRows.build(LedgerExamples.trip(), LedgerRows.State.trip(6, false, false), all));
		s.put("6-raid", LedgerRows.build(LedgerExamples.raid(),
			LedgerRows.State.raid("Tombs of Amascut", 3, false, false), all));
		s.put("7-unsure", LedgerRows.build(LedgerExamples.midFight(),
			LedgerRows.State.fight("Vardorvis", false, false, true), all));
		s.put("8-nodata", LedgerRows.build(LedgerExamples.noData(),
			LedgerRows.State.fight("New boss", false, false, false), all));
		s.put("9-setup", LedgerRows.build(LedgerExamples.midFightBeforeT010(),
			LedgerRows.State.fight("Vardorvis", false, false, false), all));
		s.put("10-pre-unsure", LedgerRows.build(null,
			LedgerRows.State.preAttack("Vardorvis"), all));
		// T-016: brewed down for most of the fight. The word stands in for 0%, the seconds carry the cost.
		final LedgerExamples.FakeScope brewed = LedgerExamples.midFight();
		brewed.attacksDrained = 12;
		brewed.setup(0.95, 0.0, 3.36, 0);
		s.put("11-drained", LedgerRows.build(brewed,
			LedgerRows.State.fight("Vardorvis", false, false, false), all));
		// A panel the player dragged narrower than a long attack's name (Eric, 2026-10-26): drawn at SAVED_WIDTH; the
		// loadout's name falls back to the spell rather than wrapping.
		s.put("12-saved-width", LedgerRows.build(LedgerExamples.midFight(),
			LedgerRows.State.fight("Vardorvis", false, false, false), all));
		// T-022: the loadout overlay under the Ledger, in the states it's worth seeing in. Each is drawn as its own picture,
		// "<state>.L", which the sheet stacks under the Ledger's as RuneLite stacks the two boxes.
		final Map<String, List<LedgerRows.Row>> both = new LinkedHashMap<>();
		for (Map.Entry<String, List<LedgerRows.Row>> state : s.entrySet())
		{
			both.put(state.getKey(), state.getValue());
			final List<LedgerRows.Row> loadout = LOADOUTS.get(state.getKey());
			if (loadout != null)
			{
				both.put(state.getKey() + ".L", loadout);
			}
		}
		return both;
	}

	/** The loadout overlay's rows for the states that show one. */
	static final Map<String, List<LedgerRows.Row>> LOADOUTS = new LinkedHashMap<>();

	static
	{
		final LedgerRows.Loadout tentacle = LedgerExamples.loadout();
		// Without monster data there is no accuracy to show; the max hit is known.
		final LedgerRows.Loadout fang = new LedgerRows.Loadout();
		fang.attack = "Osmumten's fang";
		fang.maxHit = 44;
		final LedgerRows.Loadout staff = LedgerExamples.loadout();
		staff.attack = "Harmonised nightmare staff (Fire Surge)";
		LOADOUTS.put("1-pre", LedgerRows.loadout(tentacle, false));
		LOADOUTS.put("2-mid", LedgerRows.loadout(tentacle, false));
		LOADOUTS.put("3-dead", LedgerRows.loadout(tentacle, false));
		LOADOUTS.put("7-unsure", LedgerRows.loadout(tentacle, true));
		LOADOUTS.put("8-nodata", LedgerRows.loadout(fang, false));
		LOADOUTS.put("10-pre-unsure", LedgerRows.loadout(tentacle, true));
		LOADOUTS.put("12-saved-width", LedgerRows.loadout(staff, false));
	}

	/** States drawn at a width the player saved rather than the panel's own. */
	static final Map<String, Integer> SAVED_WIDTH = new LinkedHashMap<>();

	static
	{
		SAVED_WIDTH.put("12-saved-width", 160);
		SAVED_WIDTH.put("12-saved-width.L", 160);
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
