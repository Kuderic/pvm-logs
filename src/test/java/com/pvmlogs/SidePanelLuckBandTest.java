package com.pvmlogs;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.font.FontRenderContext;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JComponent;
import net.runelite.client.ui.FontManager;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * T-201 (Eric, 2026-11-10 07:35 to 07:47, on {@code eef42ce}): luck reads "top 19%" everywhere, Luck before Skill on the
 * card, live Skill is the Skill score, every tooltip is wrapped HTML, and the panel draws only what its fonts have.
 */
public class SidePanelLuckBandTest
{
	// ---- the words ----

	@Test
	public void noRollInWhatThePlayerReads() throws IOException
	{
		final Pattern literal = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
		final Pattern roll = Pattern.compile("(?i)\\brolled\\b|\\d+ of 100\\b|\\bof 100\\b(?! fights)");
		final List<String> found = new ArrayList<>();
		for (Path f : panelSources())
		{
			int n = 0;
			for (String line : Files.readAllLines(f, StandardCharsets.UTF_8))
			{
				n++;
				final String code = line.trim();
				if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*") || code.contains("log."))
				{
					continue;
				}
				final Matcher m = literal.matcher(line);
				while (m.find())
				{
					if (roll.matcher(m.group(1)).find())
					{
						found.add(f.getFileName() + ":" + n + " \"" + m.group(1) + "\"");
					}
				}
			}
		}
		assertTrue(String.join("\n", found), found.isEmpty());
		// And none on the tabs as drawn, with their tooltips.
		for (String s : everything())
		{
			assertFalse(s, roll.matcher(Tips.plain(s)).find());
		}
	}

	@Test
	public void luckIsItsBandOnTheCardTheSectionHistoryAndTheParty()
	{
		final PanelSnapshot.FightTab kill = SidePanelModel.fight(SidePanelKillCardExamples.araxxorKill());
		assertTrue(SidePanelKillCard.drawnTexts(kill.card).contains("top 19%"));
		assertEquals("top 19%", kill.summaries.get("curve").text);
		// The Luck section: one row, the band in its strength and the seconds in violet; no hit rows.
		assertEquals(1, kill.clear.luckHits.size());
		final PanelSnapshot.Row row = kill.clear.luckHits.get(0);
		assertEquals("top 19%", row.left.text);
		assertEquals(SidePanelStyle.LUCK_BRIGHT, row.left.colour);
		assertEquals("+10 s", row.right.text);
		assertEquals(SidePanelStyle.LUCK, row.right.colour);
		// The curve's hover explains the band once, with the hits against average.
		assertTrue(kill.curve.tooltip, kill.curve.tooltip.startsWith("Top 19%: of 100 fights with these attacks, 81"));
		assertEquals(1, count(kill.curve.tooltip, "of 100 fights"));
		assertTrue(kill.curve.tooltip, kill.curve.tooltip.contains("Hits 30"));
		// Live: one word and the marker, no band.
		final PanelSnapshot.KillCard live = SidePanelModel.fight(SidePanelKillCardExamples.araxxorLive()).card;
		for (String s : SidePanelKillCard.drawnTexts(live))
		{
			assertFalse(s, s.matches("(top|bottom) \\d+%"));
		}
		assertTrue(live.luckWord.text, live.luckWord.text.matches("lucky|average|unlucky"));
		// History's chips and Personal bests.
		for (SidePanelHistory.KillRow r : SidePanelHistory.kills(SidePanelHistoryExamples.history(), "Vardorvis",
			SidePanelHistoryExamples.NOW, SidePanelExamples.ZONE))
		{
			if (r.chip != null && !r.chip.text.matches("\\d+ s (faster|slower)|even"))
			{
				assertTrue(r.chip.text, r.chip.text.matches("(top|bottom) \\d+%|average"));
			}
		}
		// The party's.
		final PanelSnapshot.PartyTab party = SidePanelParty.party(SidePanelPartyExamples.toa());
		assertEquals("top 12%", party.rows.get(0).luck[0]);
	}

	@Test
	public void oneCutOffRuleAtEveryEdge()
	{
		for (int i = 0; i <= 10_000; i++)
		{
			final double p = i / 10_000.0;
			final String band = SidePanelModel.luckText(p);
			final SidePanelModel.LuckStrength s = SidePanelModel.strength(p);
			assertEquals(p + " " + band, s == SidePanelModel.LuckStrength.DIM, band.startsWith("bottom "));
			assertEquals(p + " " + band, s == SidePanelModel.LuckStrength.BRIGHT, band.startsWith("top "));
			assertFalse(band, band.endsWith(" 0%"));
		}
	}

	// ---- the card ----

	@Test
	public void theCardIsLuckThenSkillAndLiveSkillIsTheScore()
	{
		final PanelSnapshot.KillCard kill = SidePanelModel.fight(SidePanelKillCardExamples.araxxorKill()).card;
		assertEquals(Arrays.asList("Luck", "Skill", "DPS"), SidePanelKillCard.blocks(kill));
		final SidePanelModel.Input in = SidePanelKillCardExamples.araxxorLive();
		final PanelSnapshot.KillCard live = SidePanelModel.fight(in).card;
		assertEquals(Arrays.asList("Luck", "Skill", "DPS"), SidePanelKillCard.blocks(live));
		final LedgerRows.Numbers n = new LedgerRows.Numbers(in.scope);
		assertEquals(LedgerRows.partPercent(n.executionLive), live.skill);
		assertFalse(LedgerRows.partPercent(n.uptime).equals(live.skill));
		for (String s : SidePanelKillCard.drawnTexts(live))
		{
			assertFalse(s, s.contains("uptime"));
		}
		assertTrue(live.skillTip, live.skillTip.contains("Uptime "));
		assertTrue(live.skillTip, live.skillTip.contains("Avoidable damage is counted at the kill."));
		assertEquals(null, live.grade);
	}

	@Test
	public void theIdleStripSitsUnderSkill()
	{
		final PanelSnapshot.KillCard live = SidePanelModel.fight(SidePanelKillCardExamples.araxxorLive()).card;
		final SidePanelKillCard view = new SidePanelKillCard(live, null);
		final int h = view.getPreferredSize().height;
		view.setSize(SidePanelStyle.CONTENT_WIDTH, h);
		final BufferedImage img = new BufferedImage(SidePanelStyle.CONTENT_WIDTH, h, BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		view.paint(g);
		g.dispose();
		// The strip's idle orange shows only in the card's right half, under Skill's tile.
		int left = 0;
		int right = 0;
		for (int y = 0; y < h; y++)
		{
			for (int x = 0; x < img.getWidth(); x++)
			{
				if (img.getRGB(x, y) == SidePanelStyle.COST.getRGB())
				{
					if (x < img.getWidth() / 2)
					{
						left++;
					}
					else
					{
						right++;
					}
				}
			}
		}
		assertTrue("idle under Skill: " + right, right > 0);
		assertEquals(0, left);
	}

	// ---- tooltips ----

	@Test
	public void everyTooltipIsWrappedHtml()
	{
		final List<String> tips = tooltips();
		assertTrue(tips.size() > 40);
		for (String t : tips)
		{
			assertTrue(t, t.startsWith("<html>") && t.endsWith("</html>"));
			final String body = t.substring(6, t.length() - 7);
			assertFalse(t, body.contains("<html>") || body.contains("&lt;br"));
			assertFalse(t, Tips.plain(t).matches("(?s).*\\bnull\\b.*"));
			for (String line : body.split("<br>", -1))
			{
				final String plain = Tips.plain(line);
				assertFalse(t, plain.trim().isEmpty());
				assertTrue(plain.length() + ": " + t, plain.length() <= SidePanelStyle.TIP_LINE || !plain.contains(" "));
			}
		}
	}

	@Test
	public void theHelperEscapesWrapsAndIsIdempotent()
	{
		final String t = SidePanelStyle.tip("a < b & c<br>" + "word ".repeat(30).trim());
		assertTrue(t, t.startsWith("<html>a &lt; b &amp; c<br>word"));
		assertEquals(t, SidePanelStyle.tip(t));
		assertEquals(null, SidePanelStyle.tip(" "));
		assertEquals("<html>1 to 2<br>-3 s</html>", SidePanelStyle.tip("1 → 2\n−3 s"));
	}

	// ---- glyphs ----

	@Test
	public void everythingDrawnIsInTheRuneScapeFonts()
	{
		final Font[] fonts = {FontManager.getRunescapeFont(), FontManager.getRunescapeSmallFont(),
			FontManager.getRunescapeBoldFont()};
		final Set<String> missing = new LinkedHashSet<>();
		for (String s : everything())
		{
			final String text = Tips.plain(s);
			for (int i = 0; i < text.length(); i++)
			{
				final char c = text.charAt(i);
				if (String.valueOf(c).equals(SidePanelText.STAR))
				{
					continue;
				}
				for (Font f : fonts)
				{
					if (!inFont(f, c))
					{
						missing.add(String.format("U+%04X in \"%s\" (%s)", (int) c, text, f.getFontName()));
					}
				}
			}
		}
		assertTrue(String.join("\n", missing), missing.isEmpty());
	}

	@Test
	public void theSmoothFontHasThemToo()
	{
		SidePanelStyle.setText(false, true);
		try
		{
			final Font f = SidePanelStyle.Type.SMALL.font();
			for (String s : everything())
			{
				final String text = Tips.plain(s).replace(SidePanelText.STAR, "");
				assertEquals(f.getFontName() + ": " + text, -1, f.canDisplayUpTo(text));
			}
		}
		finally
		{
			SidePanelStyle.setText(false, false);
		}
	}

	@Test
	public void noPanelLiteralTheFontsLack() throws IOException
	{
		final Font font = FontManager.getRunescapeSmallFont();
		final Pattern literal = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
		final List<String> found = new ArrayList<>();
		for (Path f : panelSources())
		{
			int n = 0;
			for (String line : Files.readAllLines(f, StandardCharsets.UTF_8))
			{
				n++;
				final String code = line.trim();
				// SidePanelStyle.drawable's own replacements, and the star's name: never drawn.
				if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*") || code.contains(".replace(\"")
					|| code.contains("static final String STAR"))
				{
					continue;
				}
				final Matcher m = literal.matcher(line);
				while (m.find())
				{
					final String s = unescape(m.group(1));
					for (int i = 0; i < s.length(); i++)
					{
						if (!inFont(font, s.charAt(i)))
						{
							found.add(f.getFileName() + ":" + n + String.format(" U+%04X", (int) s.charAt(i)));
						}
					}
				}
			}
		}
		assertTrue(String.join("\n", found), found.isEmpty());
	}

	@Test
	public void theStarIsPaintedInGold()
	{
		final SidePanelText t = new SidePanelText(PanelSnapshot.Text.body("Fastest with average luck",
			SidePanelStyle.SECONDARY), false);
		t.set(PanelSnapshot.Text.body("Fastest with average luck", SidePanelStyle.SECONDARY),
			PanelSnapshot.Text.small(" " + SidePanelText.STAR, SidePanelStyle.GOLD));
		final int w = t.getPreferredSize().width;
		final int h = t.getPreferredSize().height;
		t.setSize(w, h);
		final BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		t.paint(g);
		g.dispose();
		int gold = 0;
		for (int y = 0; y < h; y++)
		{
			for (int x = w * 2 / 3; x < w; x++)
			{
				final Color c = new Color(img.getRGB(x, y));
				gold += Math.abs(c.getRed() - 0xFF) + Math.abs(c.getGreen() - 0xD7) + Math.abs(c.getBlue() - 0x5E) < 60 ? 1 : 0;
			}
		}
		assertTrue("gold pixels " + gold, gold >= 4);
		assertEquals("Fastest with average luck " + SidePanelText.STAR, t.getText());
	}

	// ---- helpers ----

	/** A character the font itself has: not one Swing draws from a fallback font (a glyph code past the first slot). */
	static boolean inFont(Font f, char c)
	{
		if (c < 0x20)
		{
			return true;
		}
		final FontRenderContext frc = new FontRenderContext(null, false, false);
		final int code = f.createGlyphVector(frc, String.valueOf(c)).getGlyphCode(0);
		return code >>> 24 == 0 && code != f.getMissingGlyphCode();
	}

	private static String unescape(String s)
	{
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < s.length(); i++)
		{
			final char c = s.charAt(i);
			if (c == '\\' && i + 5 < s.length() + 0 && s.charAt(i + 1) == 'u')
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

	private static List<Path> panelSources() throws IOException
	{
		final List<Path> out = new ArrayList<>();
		try (java.util.stream.Stream<Path> walk = Files.list(Paths.get("src/main/java/com/pvmlogs")))
		{
			for (Path f : (Iterable<Path>) walk::iterator)
			{
				final String name = f.getFileName().toString();
				if (name.startsWith("SidePanel") || name.equals("PvmPerformancePanel.java")
					|| name.equals("PanelSnapshot.java"))
				{
					out.add(f);
				}
			}
		}
		return out;
	}

	private static int count(String s, String part)
	{
		int n = 0;
		for (int i = s.indexOf(part); i >= 0; i = s.indexOf(part, i + 1))
		{
			n++;
		}
		return n;
	}

	/** The Fight tab in its states, and History with a record, every section opened. */
	private static List<Container> tabs()
	{
		final List<Container> out = new ArrayList<>();
		final SidePanelModel.Input[] inputs = {SidePanelKillCardExamples.araxxorKill(),
			SidePanelKillCardExamples.araxxorLive(), SidePanelKillCardExamples.vardorvisKill(),
			SidePanelKillCardExamples.vardorvisLive(), SidePanelKillCardExamples.unrecorded(),
			SidePanelKillCardExamples.pbKill(), SidePanelKillCardExamples.allBadges(), SidePanelKillCardExamples.off(),
			// T-202: a whole raid and a run of monsters, the same sections under their titles.
			SidePanelOneTabTest.toaRaid(true), SidePanelOneTabTest.toaRaid(false), SidePanelRunExamples.saved()};
		for (SidePanelModel.Input in : inputs)
		{
			final PvmPerformancePanel p = SidePanelKillCardTest.panel(in);
			SidePanelPictures.openAll(p.fightTab());
			out.add(p.fightTab());
		}
		// A party.
		final PvmPerformancePanel party = new PvmPerformancePanel(null, null, null, null);
		party.setCelebrations(true);
		party.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelKillCardExamples.araxxorKill()), null,
			SidePanelParty.party(SidePanelPartyExamples.toa())));
		SidePanelPictures.openAll(party.fightTab());
		out.add(party.fightTab());
		// History, before the record has the kills and after.
		for (SidePanelHistoryTab.Source source : new SidePanelHistoryTab.Source[]{SidePanelPictures.source(),
			SidePanelProgressExamples.source(SidePanelProgressExamples.araxxorHistory(),
				SidePanelProgressExamples.araxxor())})
		{
			final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null, source);
			p.tabs().select(p.tabs().getTab(1));
			p.historyTab().refresh();
			SidePanelPictures.openAll(p.historyTab());
			p.historyTab().setSize(SidePanelStyle.CONTENT_WIDTH, 4000);
			SidePanelPictures.layout(p.historyTab());
			out.add(p.historyTab());
		}
		return out;
	}

	/** Every tooltip on the tabs: each component's, and those a component answers per point (the card, the rows). */
	private static List<String> tooltips()
	{
		final List<String> out = new ArrayList<>();
		for (Container tab : tabs())
		{
			tab.setSize(SidePanelStyle.CONTENT_WIDTH, Math.max(tab.getHeight(), tab.getPreferredSize().height));
			SidePanelPictures.layout(tab);
			for (Component k : components(tab))
			{
				if (!(k instanceof JComponent))
				{
					continue;
				}
				final JComponent j = (JComponent) k;
				final Set<String> seen = new LinkedHashSet<>();
				add(seen, j.getToolTipText());
				if (k instanceof SidePanelKillCard || k instanceof SidePanelKillRow || k instanceof SidePanelPartyMeter
					|| k.getClass().getName().contains("SidePanelProgress"))
				{
					k.setSize(Math.max(k.getWidth(), SidePanelStyle.CONTENT_WIDTH), Math.max(k.getHeight(),
						k.getPreferredSize().height));
					if (k instanceof SidePanelKillCard)
					{
						final BufferedImage img = new BufferedImage(k.getWidth(), k.getHeight(), BufferedImage.TYPE_INT_RGB);
						final Graphics2D g = img.createGraphics();
						k.paint(g);
						g.dispose();
					}
					for (int y = 1; y < k.getHeight(); y += 4)
					{
						for (int x = 1; x < k.getWidth(); x += 8)
						{
							add(seen, j.getToolTipText(new MouseEvent(j, MouseEvent.MOUSE_MOVED, 0, 0, x, y, 0, false)));
						}
					}
				}
				if (k instanceof SidePanelSection)
				{
					add(seen, ((SidePanelSection) k).headerTip());
				}
				out.addAll(seen);
			}
		}
		return out;
	}

	private static void add(Set<String> seen, String tip)
	{
		// "" only registers a component that answers per point.
		if (tip != null && !tip.isEmpty())
		{
			seen.add(tip);
		}
	}

	/** Every string the tabs draw, and every tooltip. */
	private static List<String> everything()
	{
		final List<String> out = new ArrayList<>(tooltips());
		for (Container tab : tabs())
		{
			for (Component k : components(tab))
			{
				if (k instanceof SidePanelText)
				{
					out.add(((SidePanelText) k).getText());
				}
				if (k instanceof SidePanelSection)
				{
					out.add(((SidePanelSection) k).title());
					out.add(((SidePanelSection) k).summary());
				}
				if (k instanceof SidePanelKillCard)
				{
					out.addAll(SidePanelKillCard.drawnTexts(((SidePanelKillCard) k).card()));
				}
				// RuneLite's own search field (its "clear" glyph and the like) isn't the panel's text.
				if (k instanceof javax.swing.AbstractButton && javax.swing.SwingUtilities.getAncestorOfClass(
					net.runelite.client.ui.components.IconTextField.class, k) == null)
				{
					out.add(((javax.swing.AbstractButton) k).getText());
				}
			}
		}
		out.removeIf(s -> s == null);
		return out;
	}

	private static List<Component> components(Container c)
	{
		final List<Component> out = new ArrayList<>();
		for (Component k : c.getComponents())
		{
			out.add(k);
			if (k instanceof Container)
			{
				out.addAll(components((Container) k));
			}
		}
		return out;
	}

	static String lower(String s)
	{
		return s.toLowerCase(Locale.ROOT);
	}
}
