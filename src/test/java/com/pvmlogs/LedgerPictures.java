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
		// T-029: illustrative spreads, so Luck shows its band: 370 against 418 over 6,000 sits in the middle half.
		final LedgerExamples.FakeScope mid = LedgerExamples.midFight();
		mid.damageVariance = 6000;
		s.put("2-mid", LedgerRows.build(mid, LedgerRows.State.fight("Vardorvis", false, false, false), all));
		// Illustrative average: the clock's median 1:59 over 10 kills.
		// T-019: illustrative damage taken after the fight.
		final LedgerExamples.FakeScope dead = LedgerExamples.kill();
		dead.dotTaken = 27;
		// T-062: Vardorvis's axes bleed.
		dead.dotTakenByKind.put("bleed", 27);
		dead.biggestHitTaken = 41;
		dead.damageTakenUnprotected = 85;
		// 688 against 778 over 11,436: the bottom 20%.
		dead.damageVariance = 11436;
		s.put("3-dead", LedgerRows.build(dead, LedgerRows.State.fight("Vardorvis", true, true, false)
			.withAverage(new LedgerHistory.Average(new LedgerHistory.Spread(119, 115, 125), 10)), all));
		final LedgerExamples.FakeScope ended = LedgerExamples.ended();
		ended.damageVariance = 8000;
		s.put("4-ended", LedgerRows.build(ended, LedgerRows.State.fight("Vardorvis", true, false, false), all));
		final LedgerExamples.FakeScope trip = LedgerExamples.trip();
		trip.damageVariance = 60_000;
		s.put("5-trip", LedgerRows.build(trip, LedgerRows.State.trip(6, false, false), all));
		// 8,410 against 8,200 over 40,000: the top 15%.
		final LedgerExamples.FakeScope raid = LedgerExamples.raid();
		raid.damageVariance = 40_000;
		s.put("6-raid", LedgerRows.build(raid, LedgerRows.State.raid("Tombs of Amascut", 3, false, false), all));
		// T-080: a Leviathan kill whose stun and specials held the player off for 10 ticks, with every part and Costing only.
		final LedgerExamples.FakeScope leviathan = LedgerExamples.kill();
		leviathan.damageVariance = 11436;
		leviathan.ticksMechanics = 10;
		s.put("31-leviathan-mechanics", LedgerRows.build(leviathan, LedgerRows.State.fight("The Leviathan", true, true,
			false), all));
		final LedgerRows.Settings costingOnly = new LedgerRows.Settings();
		costingOnly.parts = ExecutionParts.COSTING;
		s.put("32-leviathan-costing", LedgerRows.build(leviathan, LedgerRows.State.fight("The Leviathan", false, false,
			false), costingOnly));
		// T-094: a run of Abyssal demons, the light view. 150 s of fighting in a 3:10 run, 930 against 975: 6.2 against 6.5.
		final LedgerExamples.FakeScope demons = new LedgerExamples.FakeScope();
		demons.combatTicks = 250;
		demons.damage = 930;
		demons.expectedDamage = 975;
		s.put("33-run-mid", LedgerRows.build(demons, LedgerRows.State.run("Abyssal demon", 12, 190, false, false), all));
		// Ended after its pause: the clock stopped at the last attack.
		s.put("34-run-ended", LedgerRows.build(demons, LedgerRows.State.run("Abyssal demon", 12, 172, true, false), all));
		final LedgerExamples.FakeScope first = new LedgerExamples.FakeScope();
		first.combatTicks = 40;
		first.damage = 150;
		first.expectedDamage = 156;
		s.put("35-run-no-kills", LedgerRows.build(first, LedgerRows.State.run("Abyssal demon", 0, 26, false, false),
			all));
		s.put("36-run-unsure", LedgerRows.build(demons, LedgerRows.State.run("Abyssal demon", 12, 190, false, true),
			all));
		s.put("37-run-narrow", LedgerRows.build(demons, LedgerRows.State.run("Abyssal demon", 12, 190, false, false),
			all));
		s.put("38-run-long", LedgerRows.build(demons, LedgerRows.State.run("Greater abyssal demon", 120, 3_753, false,
			false), all));
		// T-075: the trip's drains and specs, in words.
		final LedgerExamples.FakeScope tripDrains = LedgerExamples.trip();
		tripDrains.damageVariance = 60_000;
		tripDrains.secondsPerDamage = 14.0 / 182;
		tripDrains.drainValueDamage = 520;
		tripDrains.specLuckDamage = 65;
		s.put("30-trip-drains", LedgerRows.build(tripDrains, LedgerRows.State.trip(6, false, false), all));
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
		// T-030: the burning claws' spec, with its burn.
		s.put("13-claws", LedgerRows.build(LedgerExamples.midFight(),
			LedgerRows.State.fight("Vardorvis", false, false, false), all));
		// T-043: the same kill at a boss with attack rules: 16:03 kill 4's figures (21.6 avoidable of 27 eating ticks over
		// 216, 263 taken, 141 avoidable), as 08-boss-stats.md section 3.4 draws them.
		final LedgerExamples.FakeScope ruled = LedgerExamples.kill();
		ruled.combatTicks = 216;
		ruled.ticksLost = 25 + 27;
		ruled.ticksLostEating = 27;
		ruled.damageTaken = 263;
		ruled.avoidableEatingTicks = 21.6;
		ruled.damageTakenAvoidable = 141;
		ruled.dotTaken = 27;
		ruled.biggestHitTaken = 41;
		ruled.damageTakenUnprotected = 85;
		ruled.damageVariance = 11436;
		ruled.setup(0.98, 0.85, 2.352, 0);
		s.put("14-ruled", LedgerRows.build(ruled, LedgerRows.State.fight("Vardorvis", true, true, false)
			.withAverage(new LedgerHistory.Average(new LedgerHistory.Spread(119, 115, 125), 10)), all));
		// And unruled (an old kill, or a boss without rules): the same figures, nothing avoidable.
		ruled.avoidableEatingTicks = -1;
		ruled.damageTakenAvoidable = -1;
		s.put("15-unruled", LedgerRows.build(ruled, LedgerRows.State.fight("Vardorvis", true, true, false)
			.withAverage(new LedgerHistory.Average(new LedgerHistory.Spread(119, 115, 125), 10)), all));
		// T-051: tracked Defence. Live after a hammer (215 to 145), live at the floor with Magic drained, then after the kill
		// with two hammers (one landed: the drains worth 14 s, the specs 5 s faster), and after a kill the party drained.
		final LedgerExamples.FakeScope live = LedgerExamples.midFight();
		live.damageVariance = 6000;
		s.put("16-live-drain", LedgerRows.build(live, LedgerRows.State.fight("Vardorvis", false, false, false)
			.withDrain(new LedgerRows.LiveDrain(145, 215, 0, 150, 150, 0)), all));
		s.put("17-live-floor", LedgerRows.build(live, LedgerRows.State.fight("Vardorvis", false, false, false)
			.withDrain(new LedgerRows.LiveDrain(150, 215, 150, 120, 150, 0)), all));
		final LedgerExamples.FakeScope drained = LedgerExamples.kill();
		drained.damageVariance = 11436;
		drained.dotTaken = 27;
		drained.dotTakenByKind.put("bleed", 27);
		drained.biggestHitTaken = 41;
		drained.damageTakenUnprotected = 85;
		drained.defenceStart = 215;
		drained.defenceEnd = 145;
		drained.magicStart = 150;
		drained.magicEnd = 150;
		drained.secondsPerDamage = 14.0 / 182;
		drained.drainValueDamage = 182;
		drained.specLuckDamage = 65;
		s.put("18-drained-kill", LedgerRows.build(drained, LedgerRows.State.fight("Vardorvis", true, true, false)
			.withAverage(new LedgerHistory.Average(new LedgerHistory.Spread(119, 115, 125), 10)), all));
		// T-075: specs that cost time read "slower".
		drained.specLuckDamage = -65;
		s.put("29-specs-slower", LedgerRows.build(drained, LedgerRows.State.fight("Vardorvis", true, true, false)
			.withAverage(new LedgerHistory.Average(new LedgerHistory.Spread(119, 115, 125), 10)), all));
		drained.specLuckDamage = 0;
		s.put("19-party-drained", LedgerRows.build(drained, LedgerRows.State.fight("Vardorvis", true, true, false)
			.withAverage(new LedgerHistory.Average(new LedgerHistory.Spread(119, 115, 125), 10)), all));
		// T-062, illustrative: venom dealt (a blowpipe at Zulrah) and her poison and venom taken, then a burning-claws kill,
		// then an old kill (saved before T-062) whose totals have no kind.
		final LedgerExamples.FakeScope zulrah = LedgerExamples.kill();
		zulrah.damageVariance = 11436;
		zulrah.thrallDamage = 0;
		zulrah.dotDamage = 30;
		zulrah.dotDamageByKind.put("venom", 30);
		zulrah.dotTaken = 37;
		zulrah.dotTakenByKind.put("poison", 25);
		zulrah.dotTakenByKind.put("venom", 12);
		zulrah.biggestHitTaken = 41;
		s.put("20-venom-poison", LedgerRows.build(zulrah, LedgerRows.State.fight("Zulrah", true, true, false), all));
		final LedgerExamples.FakeScope claws = LedgerExamples.kill();
		claws.damageVariance = 11436;
		claws.dotDamage = 49;
		claws.dotDamageByKind.put("burn", 49);
		claws.dotTaken = 27;
		claws.dotTakenByKind.put("bleed", 27);
		s.put("21-burn-claws", LedgerRows.build(claws, LedgerRows.State.fight("Vardorvis", true, true, false)
			.withAverage(new LedgerHistory.Average(new LedgerHistory.Spread(119, 115, 125), 10)), all));
		final LedgerExamples.FakeScope old = LedgerExamples.kill();
		old.damageVariance = 11436;
		old.dotDamage = 49;
		old.dotTaken = 27;
		s.put("22-old-kill", LedgerRows.build(old, LedgerRows.State.fight("Vardorvis", true, true, false)
			.withAverage(new LedgerHistory.Average(new LedgerHistory.Spread(119, 115, 125), 10)), all));
		// T-073: "Under Execution" at its new default, Costing only, and Off, after the kill and mid-fight.
		final LedgerRows.Settings costing = new LedgerRows.Settings();
		costing.parts = ExecutionParts.COSTING;
		final LedgerRows.Settings off = new LedgerRows.Settings();
		off.parts = ExecutionParts.OFF;
		s.put("23-costing-mid", LedgerRows.build(mid, LedgerRows.State.fight("Vardorvis", false, false, false), costing));
		s.put("24-costing-dead", LedgerRows.build(dead, LedgerRows.State.fight("Vardorvis", true, true, false)
			.withAverage(new LedgerHistory.Average(new LedgerHistory.Spread(119, 115, 125), 10)), costing));
		s.put("25-parts-off", LedgerRows.build(mid, LedgerRows.State.fight("Vardorvis", false, false, false), off));
		// T-073: the DPS overlay on its own: a grouped room's part, idle (and before any fight, the same rows), and a
		// narrow saved width.
		final LedgerRows.Loadout blowpipe = new LedgerRows.Loadout();
		blowpipe.attack = "Toxic blowpipe";
		blowpipe.versus = "Dusk";
		blowpipe.versusRgb = BossColours.of("Grotesque Guardians");
		blowpipe.maxHit = 27;
		blowpipe.accuracy = 0.58;
		blowpipe.dps = 6.4;
		blowpipe.specMaxHit = 40;
		blowpipe.specAccuracy = 0.58;
		blowpipe.specAverage = 11.6;
		s.put("26-dps-grouped", LedgerRows.loadout(blowpipe, false));
		final LedgerRows.Loadout idle = new LedgerRows.Loadout();
		idle.attack = "Abyssal tentacle";
		idle.maxHit = 46;
		idle.specMaxHit = 48;
		s.put("27-dps-idle", LedgerRows.loadout(idle, false));
		final LedgerRows.Loadout queen = LedgerExamples.loadout();
		queen.attack = "Toxic blowpipe";
		queen.versus = "Eldric the Fire Queen";
		queen.versusRgb = BossColours.of("The Royal Titans");
		s.put("28-dps-narrow", LedgerRows.loadout(queen, false));
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
		// T-073: the DPS overlay names the monster.
		tentacle.versus = "Vardorvis";
		tentacle.versusRgb = BossColours.of("Vardorvis");
		// Without monster data there is no accuracy to show; the max hit is known.
		final LedgerRows.Loadout fang = new LedgerRows.Loadout();
		fang.attack = "Osmumten's fang";
		fang.maxHit = 44;
		fang.versus = "Vardorvis";
		final LedgerRows.Loadout staff = LedgerExamples.loadout();
		staff.attack = "Harmonised nightmare staff (Fire Surge)";
		staff.versus = "Vardorvis";
		LOADOUTS.put("1-pre", LedgerRows.loadout(tentacle, false));
		LOADOUTS.put("2-mid", LedgerRows.loadout(tentacle, false));
		LOADOUTS.put("3-dead", LedgerRows.loadout(tentacle, false));
		LOADOUTS.put("7-unsure", LedgerRows.loadout(tentacle, true));
		LOADOUTS.put("8-nodata", LedgerRows.loadout(fang, false));
		LOADOUTS.put("10-pre-unsure", LedgerRows.loadout(tentacle, true));
		LOADOUTS.put("12-saved-width", LedgerRows.loadout(staff, false));
		// Illustrative figures for burning claws (T-030).
		final LedgerRows.Loadout claws = new LedgerRows.Loadout();
		claws.attack = "Burning claws";
		claws.maxHit = 41;
		claws.accuracy = 0.62;
		claws.dps = 5.9;
		claws.specMaxHit = 48;
		claws.specAccuracy = 0.71;
		claws.specAverage = 17.8;
		claws.specExtra = 8.2;
		claws.specExtraKind = "burn";
		LOADOUTS.put("13-claws", LedgerRows.loadout(claws, false));
	}

	/** States drawn at a width the player saved rather than the panel's own. */
	static final Map<String, Integer> SAVED_WIDTH = new LinkedHashMap<>();

	static
	{
		SAVED_WIDTH.put("12-saved-width", 160);
		SAVED_WIDTH.put("12-saved-width.L", 160);
		SAVED_WIDTH.put("28-dps-narrow", 120);
		SAVED_WIDTH.put("37-run-narrow", PvmPerformanceOverlay.MIN_WIDTH);
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
					rows.add(PvmPerformanceOverlay.fit(row, left::stringWidth, right::stringWidth, saved));
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
							row.drawnLeft(), row.right, roleName(roles.get(row.leftFont)), roleName(roles.get(row.rightFont)),
							row.leftRgb(), row.rightColour.rgb));
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
