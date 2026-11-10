package com.pvmlogs;

import static org.junit.Assert.assertTrue;
import java.awt.Font;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.client.ui.FontManager;
import org.junit.Test;

/**
 * T-202 item 3b: the overlays draw in the same RuneScape fonts as the side panel (T-201), so they draw no character
 * the fonts lack either: no box. The Defence and Magic rows read "215 to 145", Execution's sum "100% - uptime 0.9%".
 * Checked over LedgerRows' rows as the overlay shows them, in every state and setting, and over every string literal
 * in LoadoutOverlay, PvmPerformanceOverlay and LedgerRows.
 */
public class OverlayGlyphTest
{
	private static final Font[] FONTS = {FontManager.getRunescapeFont(), FontManager.getRunescapeSmallFont(),
		FontManager.getRunescapeBoldFont()};

	@Test
	public void theOverlaysRowsAreInTheRuneScapeFonts()
	{
		final Set<String> missing = new LinkedHashSet<>();
		int rows = 0;
		for (LedgerRows.Scope scope : scopes())
		{
			for (LedgerRows.State state : states())
			{
				for (LedgerRows.Settings settings : settings())
				{
					for (LedgerRows.Row r : LedgerRows.build(scope, state, settings))
					{
						rows++;
						for (String s : new String[]{r.left, r.right, r.shortLeft, r.shortRight})
						{
							check(s, missing);
						}
					}
				}
			}
			final LedgerRows.Numbers n = new LedgerRows.Numbers(scope);
			check(LedgerRows.executionSum(n, false), missing);
			check(LedgerRows.executionSum(n, true), missing);
		}
		assertTrue(String.join("\n", missing), missing.isEmpty());
		assertTrue(String.valueOf(rows), rows > 500);
	}

	@Test
	public void theDrainRowsAndTheSumReadInWords()
	{
		final List<String> seen = new ArrayList<>();
		for (LedgerRows.Row r : LedgerRows.build(drained(), LedgerRows.State.fight("Vardorvis", true, true, false),
			new LedgerRows.Settings()))
		{
			seen.add(r.left + "|" + r.right);
		}
		assertTrue(seen.toString(), seen.contains(" defence|215 to 145"));
		assertTrue(seen.toString(), seen.contains(" magic|150 to 120"));
		final String sum = LedgerRows.executionSum(new LedgerRows.Numbers(LedgerExamples.kill()), false);
		assertTrue(sum, sum.startsWith("100% - uptime "));
	}

	@Test
	public void noOverlayLiteralTheFontsLack() throws IOException
	{
		final Pattern literal = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
		final List<String> found = new ArrayList<>();
		int literals = 0;
		for (String name : new String[]{"LoadoutOverlay.java", "PvmPerformanceOverlay.java", "LedgerRows.java"})
		{
			final Path f = Paths.get("src/main/java/com/pvmlogs", name);
			int n = 0;
			for (String line : Files.readAllLines(f, StandardCharsets.UTF_8))
			{
				n++;
				final String code = line.trim();
				if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*"))
				{
					continue;
				}
				final Matcher m = literal.matcher(line);
				while (m.find())
				{
					literals++;
					final String s = unescape(m.group(1));
					for (int i = 0; i < s.length(); i++)
					{
						if (!SidePanelLuckBandTest.inFont(FontManager.getRunescapeSmallFont(), s.charAt(i)))
						{
							found.add(name + ":" + n + String.format(" U+%04X", (int) s.charAt(i)));
						}
					}
				}
			}
		}
		assertTrue(String.join("\n", found), found.isEmpty());
		assertTrue(String.valueOf(literals), literals > 100);
	}

	// ---- helpers ----

	private static void check(String s, Set<String> missing)
	{
		if (s == null)
		{
			return;
		}
		for (int i = 0; i < s.length(); i++)
		{
			for (Font f : FONTS)
			{
				if (!SidePanelLuckBandTest.inFont(f, s.charAt(i)))
				{
					missing.add(String.format("U+%04X in \"%s\" (%s)", (int) s.charAt(i), s, f.getFontName()));
				}
			}
		}
	}

	/** Eric's kill with drains: Defence 215 to 145, Magic 150 to 120. */
	private static LedgerExamples.FakeScope drained()
	{
		final LedgerExamples.FakeScope s = LedgerExamples.kill();
		s.defenceStart = 215;
		s.defenceEnd = 145;
		s.magicStart = 150;
		s.magicEnd = 120;
		s.secondsPerDamage = 14.0 / 182;
		s.drainValueDamage = 182;
		s.specLuckDamage = 65;
		return s;
	}

	private static List<LedgerRows.Scope> scopes()
	{
		return Arrays.asList(LedgerExamples.midFight(), LedgerExamples.kill(), LedgerExamples.ended(),
			LedgerExamples.trip(), LedgerExamples.raid(), LedgerExamples.noData(), LedgerExamples.midFightBeforeT010(),
			drained(), SidePanelExamples.ruledKill(true).scope, SidePanelExamples.leviathanKill().scope,
			SidePanelExamples.unscoredAdds().scope, SidePanelExamples.luckSaved(15).scope,
			SidePanelExamples.luckSaved(-60).scope, SidePanelKillCardExamples.araxxorKill().scope,
			SidePanelRunExamples.saved().scope);
	}

	private static List<LedgerRows.State> states()
	{
		final List<LedgerRows.State> out = new ArrayList<>();
		for (boolean warning : new boolean[]{false, true})
		{
			out.add(LedgerRows.State.fight("Vardorvis", false, false, warning));
			out.add(LedgerRows.State.fight("Vardorvis", true, true, warning));
			out.add(LedgerRows.State.fight("Vardorvis", true, false, warning));
			out.add(LedgerRows.State.trip(6, false, warning));
			out.add(LedgerRows.State.trip(6, true, warning));
			out.add(LedgerRows.State.raid("Tombs of Amascut", 3, false, warning));
			out.add(LedgerRows.State.raid("Tombs of Amascut", 3, true, warning));
			out.add(LedgerRows.State.run(SidePanelRunExamples.DEMON, 12, 190, false, warning));
			out.add(LedgerRows.State.run(SidePanelRunExamples.DEMON, 12, 190, true, warning));
		}
		return out;
	}

	private static List<LedgerRows.Settings> settings()
	{
		final List<LedgerRows.Settings> out = new ArrayList<>();
		for (ExecutionParts parts : ExecutionParts.values())
		{
			for (boolean luck : new boolean[]{true, false})
			{
				final LedgerRows.Settings s = new LedgerRows.Settings();
				s.parts = parts;
				s.luck = luck;
				out.add(s);
			}
		}
		final LedgerRows.Settings noDetails = new LedgerRows.Settings();
		noDetails.details = false;
		out.add(noDetails);
		return out;
	}

	private static String unescape(String s)
	{
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < s.length(); i++)
		{
			final char c = s.charAt(i);
			if (c == '\\' && i + 5 < s.length() && s.charAt(i + 1) == 'u')
			{
				out.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
				i += 5;
			}
			else if (c == '\\' && i + 1 < s.length())
			{
				out.append(s.charAt(++i));
			}
			else
			{
				out.append(c);
			}
		}
		return out.toString();
	}
}
