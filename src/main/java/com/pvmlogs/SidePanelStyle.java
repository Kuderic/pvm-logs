package com.pvmlogs;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.Transparency;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;

/**
 * The side panel's colours, type and sizes, from {@code claude/specs/side-panel-design.md}
 * section 2 (T-031). Every label and value uses one of these; nothing in the
 * panel picks a colour of its own.
 */
final class SidePanelStyle
{
	private SidePanelStyle()
	{
	}

	// ---- text colours (spec section 2, "Colour") ----
	static final Color VALUE = Color.WHITE;
	static final Color BODY = ColorScheme.TEXT_COLOR;
	static final Color SECONDARY = ColorScheme.LIGHT_GRAY_COLOR;
	/** New (spec): the least important hints. Not on selected rows: use Secondary there. */
	static final Color MUTED = new Color(0x909090);
	/** T-132: muted text on a tinted background (the selected party member's part, T-132), for the plain panel's contrast. */
	static final Color MUTED_ON_TINT = new Color(0x9C9C9C);
	static final Color BETTER = new Color(LedgerRows.Colour.BETTER.rgb);
	static final Color COST = new Color(LedgerRows.Colour.COST.rgb);
	static final Color WARNING = new Color(LedgerRows.Colour.WARNING.rgb);
	static final Color DEAD = new Color(LedgerRows.Colour.DEAD.rgb);
	static final Color ACCENT = ColorScheme.BRAND_ORANGE;

	// ---- fills ----
	static final Color PANEL = ColorScheme.DARK_GRAY_COLOR;
	static final Color CARD = ColorScheme.DARKER_GRAY_COLOR;
	static final Color SELECTED = ColorScheme.DARKER_GRAY_HOVER_COLOR;
	/** T-198: a folded section's card under the mouse, and an open header's ground under it: a step lighter. */
	static final Color CARD_HOVER = new Color(0x323232);
	static final Color HEADER_HOVER = new Color(0x363636);
	static final Color TRACK = new Color(0x3A3A3A);
	static final Color DIVIDER = new Color(0x2E2E2E);
	static final Color KEPT = new Color(0x7FA0B8);
	static final Color MINOR_COST = new Color(0x8A6A44);
	/** The stripes' dark ground for luck that cost time and for eating. */
	static final Color COST_STRIPE_GROUND = new Color(0x5A4530);
	static final Color AVERAGE_STRIPE = new Color(0x8A8A8A);
	static final Color UNSCORED = ColorScheme.MEDIUM_GRAY_COLOR;
	/** The time budget's eating stretch (T-038): a light, neutral grey, apart from the unscored grey and luck's stripes. */
	static final Color EATING = new Color(0xA8A8A8);
	/**
	 * T-081: time a boss's mechanic forced on you (T-074): neutral like eating, in a lighter grey so the two read apart on
	 * the bar and the strip.
	 */
	static final Color MECHANICS = new Color(0xDADADA);
	/**
	 * T-042: laid over the time budget's segments past your clock, where luck
	 * saved the time: the card's colour at 60%, so they show at about 40%.
	 */
	static final Color SAVED_DIM = alpha(CARD, 0.6);
	/** T-042: the light stripes over that stretch, in Better (or the average grey) at this opacity. */
	static final double SAVED_STRIPE_ALPHA = 0.6;
	/** T-042: the tick at your real time on the time budget's bar. */
	static final Color CLOCK_TICK = VALUE;
	/** The time budget's dashed average marker: grey, apart from the clock's white tick (T-042). */
	static final Color AVERAGE_MARKER = SECONDARY;
	static final Color NOTE_CARD = new Color(0x232A30);
	static final Color CHIP_COST = new Color(0x4A3320);
	static final Color CHIP_BETTER = new Color(0x173F40);
	static final Color CHIP_NEUTRAL = TRACK;
	static final Color WARNING_FILL = new Color(0x33330F);
	static final Color WARNING_EDGE = new Color(0x66661E);

	// ---- damage sources: what dealt it, never good or bad ----
	static final Color MELEE = new Color(0xE06C75);
	static final Color RANGED = new Color(0x8BD45A);
	static final Color MAGIC = new Color(0x6E9BFF);
	static final Color THRALL = new Color(0xB48CFF);
	static final Color DOT = new Color(0xE6A0D4);
	static final Color REFLECT = new Color(0x9AA5B1);
	/** T-204: the player's cannon: a khaki, apart from the styles', the thrall's violet, cost's orange and the grades'. */
	static final Color CANNON = new Color(0xBDB76B);
	/** T-188: luck's own colour on the kill card and in its words, always: the thrall's violet, apart from skill's. */
	static final Color LUCK = THRALL;
	/**
	 * T-194 (Eric, 2026-11-09: "ok lets do your idea"): luck's violet in three strengths by roll, so a lucky or unlucky
	 * roll shows at a glance in a column, never in skill's colours (gold and green are S and A, orange is ticks lost).
	 * They differ in lightness, not only hue. Text alone, on the card's #1E1E1E and the tiles' #282828: dim 5.6 and 5.0,
	 * {@link #LUCK} 6.5 and 5.7, bright 10.9 and 9.7.
	 */
	static final Color LUCK_DIM = new Color(0x9A92AE);
	static final Color LUCK_BRIGHT = new Color(0xDCC8FF);
	/** T-194: the roll's chip in each strength, text on fill: dim 4.73, normal 4.68 (a step darker than #3A3A3A's 4.41), bright 4.69. */
	static final Color LUCK_CHIP_DIM_FILL = new Color(0x2C2836);
	static final Color LUCK_CHIP_DIM_INK = new Color(0x9890AC);
	static final Color LUCK_CHIP_FILL = new Color(0x363636);
	static final Color LUCK_CHIP_BRIGHT_FILL = new Color(0x8456E8);
	static final Color LUCK_CHIP_BRIGHT_INK = Color.WHITE;
	/** T-196: the card's 1 to 100 track, its three stretches in luck's three strengths, lighter at each step. */
	static final Color LUCK_TRACK_DIM = new Color(0x3D3550);
	static final Color LUCK_TRACK_NORMAL = new Color(0x6C58A0);
	static final Color LUCK_TRACK_BRIGHT = LUCK;
	/**
	 * T-196: the grade's zones on the card, C to S over your last 50 (10/60/25/5%), each with its letter in white: C
	 * grey, B steel, A green, S gold, dark enough for the letter (white on each at 4.5 or more).
	 */
	static final Color ZONE_C = new Color(0x4A4A4A);
	/** T-196: the speed track's stretch from your best to this kill. */
	static final Color SPEED_FILL = new Color(0x6A6A6A);
	/**
	 * T-207 (Eric: "It's not clear that you can click dmg luck skill to change the graphs"): a segmented control's track,
	 * its chosen pill, and a pill under the mouse.
	 */
	static final Color SEGMENT_TRACK = new Color(0x2A2A2A);
	static final Color SEGMENT_ON = new Color(0x4C4C4C);
	static final Color SEGMENT_HOVER = new Color(0x3A3A3A);
	/** T-196: the card's rule between its blocks, on the card's ground. */
	static final Color DIVIDER_ON_CARD = new Color(0x3C3C3C);
	static final Color ZONE_B = new Color(0x3E5C7A);
	static final Color ZONE_A = new Color(0x3B6A38);
	static final Color ZONE_S = new Color(0x806618);
	/** T-188: an S grade, and the luck-removed PB's star: the only gold. */
	static final Color GOLD = new Color(0xFFD75E);
	/** T-188: an A grade. */
	static final Color GRADE_A = new Color(0x8FE08A);

	// ---- sizes (spec section 2, "Layout") ----
	/** The sidebar's 242 px less a 6 px border each side and the 17 px scrollbar. */
	static final int CONTENT_WIDTH = 213;
	static final int SIDE_BORDER = 6;
	static final int BLOCK_GAP = 6;
	static final int ROW_GAP = 2;
	static final int TILE_GAP = 4;
	static final int CARD_PAD_V = 6;
	static final int CARD_PAD_H = 7;
	static final int TILE_EDGE = 2;

	/** A colour role from the overlay's rows, in the panel's palette: neutral values are white. */
	static Color of(LedgerRows.Colour colour)
	{
		return colour == null || colour == LedgerRows.Colour.NEUTRAL ? VALUE : new Color(colour.rgb);
	}

	/** The same, for a hint or sub-line: neutral is Secondary rather than white. */
	static Color hint(LedgerRows.Colour colour)
	{
		return colour == null || colour == LedgerRows.Colour.NEUTRAL ? SECONDARY : new Color(colour.rgb);
	}

	/** The chip fill behind a coloured word in a list (spec: cost, better, neutral). */
	static Color chip(LedgerRows.Colour colour)
	{
		if (colour == LedgerRows.Colour.COST)
		{
			return CHIP_COST;
		}
		return colour == LedgerRows.Colour.BETTER ? CHIP_BETTER : CHIP_NEUTRAL;
	}

	/** A colour mixed {@code share} of the way toward white: a special's colour from its style's (spec section 2). */
	static Color towardWhite(Color c, double share)
	{
		return new Color((int) Math.round(c.getRed() + (255 - c.getRed()) * share),
			(int) Math.round(c.getGreen() + (255 - c.getGreen()) * share),
			(int) Math.round(c.getBlue() + (255 - c.getBlue()) * share));
	}

	/** A colour at a share of its opacity, for the curve's bands and the rows' share bars. */
	static Color alpha(Color c, double opacity)
	{
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) Math.round(255 * opacity));
	}

	/** Which of the four type roles a text is in (spec section 2, "Type"). */
	enum Type
	{
		/** RuneScape Bold at 32: the fight's clock. */
		CLOCK,
		/** RuneScape Bold 16: names, section titles, tile values, totals. */
		TITLE,
		/** RuneScape 16: row names. */
		BODY,
		/** RuneScape Small 16: sub-rows, hints, legends, chips, dates. */
		SMALL;

		/** The role's font under the panel's text settings (T-053). */
		Font font()
		{
			return text.fonts[ordinal()];
		}

		/**
		 * The role's line height: with the RuneScape fonts the fixed 16 for Regular
		 * and Bold, 12 for Small (their own ascent plus descent) and 30 for the
		 * clock; with the system font its ascent plus descent (T-053).
		 */
		int lineHeight()
		{
			return text.lines[ordinal()];
		}
	}

	// ---- the panel's text settings (T-053) ----

	/** The system font Smooth font uses where Windows has it; else Java's sans serif. */
	static final String SMOOTH_FAMILY = "Segoe UI";
	/**
	 * Smooth font's sizes at Normal, matched to the RuneScape roles' cap heights
	 * by renders (T-053): Title bold 13 and Body 13 against RuneScape Bold and
	 * Regular 16 (10 px caps), Small 11 against RuneScape Small (8 px). Larger
	 * adds {@link #LARGER_STEP} to each.
	 */
	static final float SMOOTH_TITLE = 13f;
	static final float SMOOTH_BODY = 13f;
	static final float SMOOTH_SMALL = 11f;
	static final float LARGER_STEP = 1f;

	/**
	 * The fonts, line heights and text smoothing for one combination of the
	 * "Larger text" and "Smooth font" settings. Made only when a setting changes,
	 * never per tick or per paint.
	 */
	static final class TextStyle
	{
		final boolean larger;
		final boolean smooth;
		/** The family Smooth font draws in, or null with the RuneScape fonts. */
		final String family;
		final Font[] fonts = new Font[Type.values().length];
		final int[] lines = new int[Type.values().length];
		/** Each role's metrics, measured once, for fixed columns. */
		final FontMetrics[] metrics = new FontMetrics[Type.values().length];
		/** The desktop's own text hints (ClearType on Windows), or null where it gives none. */
		final Map<?, ?> desktopHints;

		TextStyle(boolean larger, boolean smooth, String[] families, Map<?, ?> desktopHints)
		{
			this.larger = larger;
			this.smooth = smooth;
			this.desktopHints = smooth ? desktopHints : null;
			fonts[Type.CLOCK.ordinal()] = Fonts.CLOCK;
			lines[Type.CLOCK.ordinal()] = 30;
			if (!smooth)
			{
				this.family = null;
				fonts[Type.TITLE.ordinal()] = FontManager.getRunescapeBoldFont();
				fonts[Type.BODY.ordinal()] = FontManager.getRunescapeFont();
				// Larger with the RuneScape fonts: Small text in Regular, the next size that stays on the font's grid.
				fonts[Type.SMALL.ordinal()] = larger ? FontManager.getRunescapeFont() : FontManager.getRunescapeSmallFont();
				lines[Type.TITLE.ordinal()] = 16;
				lines[Type.BODY.ordinal()] = 16;
				lines[Type.SMALL.ordinal()] = larger ? 16 : 12;
				measure();
				return;
			}
			this.family = smoothFamily(families);
			final float step = larger ? LARGER_STEP : 0f;
			fonts[Type.TITLE.ordinal()] = new Font(family, Font.BOLD, 1).deriveFont(SMOOTH_TITLE + step);
			fonts[Type.BODY.ordinal()] = new Font(family, Font.PLAIN, 1).deriveFont(SMOOTH_BODY + step);
			fonts[Type.SMALL.ordinal()] = new Font(family, Font.PLAIN, 1).deriveFont(SMOOTH_SMALL + step);
			measure();
			for (Type t : new Type[]{Type.TITLE, Type.BODY, Type.SMALL})
			{
				lines[t.ordinal()] = metrics[t.ordinal()].getAscent() + metrics[t.ordinal()].getDescent();
			}
		}

		private void measure()
		{
			final Graphics2D g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
			if (fractional())
			{
				// Fractional advances: a string's width is the same at every scale, as it's drawn (see textHints).
				g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
			}
			if (smooth)
			{
				g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			}
			try
			{
				for (Type t : Type.values())
				{
					metrics[t.ordinal()] = g.getFontMetrics(fonts[t.ordinal()]);
				}
			}
			finally
			{
				g.dispose();
			}
		}

		Font font(Type t)
		{
			return fonts[t.ordinal()];
		}

		/**
		 * Whether text is measured and drawn with fractional advances: with either setting on. A font's advances rounded
		 * at 125% or 150% don't add up to its widths at 100%, so a value laid out at 100% was cut at 150%. Fractional
		 * advances are the same at every scale. The RuneScape fonts' advances are whole pixels at 100% and 200%, so there
		 * they draw as before. Both off keeps today's metrics.
		 */
		boolean fractional()
		{
			return smooth || larger;
		}

		/** With either setting on, the measured metrics of one of the roles' fonts; else null. */
		FontMetrics fractionalMetrics(Font f)
		{
			if (!fractional())
			{
				return null;
			}
			for (Type t : Type.values())
			{
				if (fonts[t.ordinal()].equals(f))
				{
					return metrics[t.ordinal()];
				}
			}
			return null;
		}

		int line(Type t)
		{
			return lines[t.ordinal()];
		}
	}

	/** Swing's thread writes it when a setting changes; painting reads it. */
	private static volatile TextStyle text = new TextStyle(false, false, null, null);

	static TextStyle text()
	{
		return text;
	}

	/**
	 * Applies the "Larger text" and "Smooth font" settings (T-053): the fonts and
	 * line heights are made now, once. False when they were already these.
	 */
	static boolean setText(boolean larger, boolean smooth)
	{
		final TextStyle now = text;
		if (now.larger == larger && now.smooth == smooth)
		{
			return false;
		}
		text = new TextStyle(larger, smooth, smooth ? availableFamilies() : null, smooth ? desktopHints() : null);
		return true;
	}

	/** For tests: the settings with a given list of installed families and desktop hints. */
	static void setText(TextStyle style)
	{
		text = style;
	}

	/** Segoe UI where it's installed, else Java's sans serif (T-053). */
	static String smoothFamily(String[] families)
	{
		return families != null && Arrays.asList(families).contains(SMOOTH_FAMILY) ? SMOOTH_FAMILY : Font.SANS_SERIF;
	}

	private static String[] availableFamilies()
	{
		try
		{
			return GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ENGLISH);
		}
		catch (RuntimeException e)
		{
			return new String[0];
		}
	}

	private static Map<?, ?> desktopHints()
	{
		try
		{
			final Object hints = Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints");
			return hints instanceof Map ? (Map<?, ?>) hints : null;
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

	/**
	 * Before drawing text (T-053). With the RuneScape fonts, nothing: they draw as
	 * they always have. With Smooth font, the desktop's own text hints (ClearType)
	 * where it gives them, else subpixel smoothing; on a surface with alpha, where
	 * subpixel text draws coloured fringes, plain greyscale smoothing.
	 */
	static void textHints(Graphics2D g)
	{
		final TextStyle t = text;
		if (!t.smooth)
		{
			if (t.larger)
			{
				// Drawn with the fractional advances it was measured with (see metrics); no smoothing.
				g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
			}
			return;
		}
		final boolean opaque = g.getDeviceConfiguration().getColorModel().getTransparency() == Transparency.OPAQUE;
		if (t.desktopHints != null)
		{
			g.addRenderingHints(t.desktopHints);
		}
		else
		{
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
		}
		if (!opaque)
		{
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		}
		// Drawn with the fractional advances it was measured with (see metrics).
		g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
	}

	/**
	 * The metrics a text is measured by when laid out (T-053). Both off: the component's own, as before. With either
	 * setting on, fractional ones made once per setting (see {@link TextStyle#fractional}).
	 */
	static FontMetrics metrics(java.awt.Component c, Font f)
	{
		final FontMetrics m = text.fractionalMetrics(f);
		return m != null ? m : c.getFontMetrics(f);
	}

	/** The metrics a text is drawn by: both off, the graphics' own, as before; with either setting on, as laid out. */
	static FontMetrics metrics(java.awt.Graphics g, Font f)
	{
		final FontMetrics m = text.fractionalMetrics(f);
		return m != null ? m : g.getFontMetrics(f);
	}

	/** At least {@code floor} px, or wider when {@code sample} takes more in the role's font: fixed columns (T-053). */
	static int columnWidth(Type type, String sample, int floor)
	{
		return Math.max(floor, text.metrics[type.ordinal()].stringWidth(sample));
	}

	/** Held apart so the derived clock font is made once. */
	private static final class Fonts
	{
		// A whole multiple of the font's own size, so the pixel font stays crisp (spec section 1).
		static final Font CLOCK = FontManager.getRunescapeBoldFont().deriveFont(32f);
	}

	// ---- formats the overlay doesn't already have ----

	/**
	 * A time in seconds as {@code m:ss}, rounded to the nearest second. The time
	 * budget's rows are seconds that were added up, not a stopwatch, so they
	 * round: Eric's 2:17 kill's best play is 100.9 s, which the spec writes
	 * {@code 1:41} (section 4). The clock itself stays {@link LedgerRows#clock(int)}.
	 */
	static String roundedDuration(double seconds)
	{
		final long s = Math.max(0, LedgerRows.roundHalfAway(seconds));
		final long h = s / 3600;
		final long m = s % 3600 / 60;
		final long r = s % 60;
		return h > 0 ? String.format(Locale.ENGLISH, "%d:%02d:%02d", h, m, r)
			: String.format(Locale.ENGLISH, "%d:%02d", m, r);
	}

	/**
	 * T-118: an amount of time won or lost, given in seconds, as whole ticks ({@link LedgerRows#ticks}): {@code 1 tick},
	 * {@code 25 ticks}, never negative zero.
	 */
	static String ticks(double seconds)
	{
		final long t = LedgerRows.ticks(seconds);
		return LedgerRows.ticksWord(t == 0 ? 0 : t);
	}

	/** T-118: {@link #ticks}'s short form for a box that doesn't fit it: {@code 25t}. */
	static String ticksShort(double seconds)
	{
		final long t = LedgerRows.ticks(seconds);
		return LedgerRows.number(t == 0 ? 0 : t) + LedgerRows.TICKS_SHORT;
	}

	/** {@code 14 s}, never {@code -0 s}; rounded half away from zero. A duration, not time won or lost: a run's pace. */
	static String seconds(double seconds)
	{
		final long r = LedgerRows.roundHalfAway(seconds);
		return (r == 0 ? 0 : r) + " s";
	}

	/** T-198: a tooltip's line length, in characters, before it breaks. */
	static final int TIP_LINE = 56;

	/**
	 * T-198 (Eric: "Your play tooltip needs a linebreak"): a tooltip broken into lines of at most {@link #TIP_LINE}
	 * characters, at spaces, as HTML; its own breaks ({@code <br>}) kept. Swing draws a tooltip on one line otherwise,
	 * wider than the panel at every text size.
	 */
	static String wrapTip(String tip)
	{
		return tip(tip);
	}

	/**
	 * T-201 (Eric: "The tooltip here is massive long and has a &lt;br&gt; in it with no actual working line breaks"): the
	 * panel's one way to make a tooltip, which every {@code setToolTipText}, {@code setTip} and {@code setTooltip} in the
	 * panel goes through. Its lines (split at {@code <br>} or a newline; an {@code <html>} around it is taken off) are
	 * made {@link #drawable}, escaped ({@code &}, {@code <}, {@code >}), broken at spaces into lines of at most
	 * {@link #TIP_LINE} characters, and wrapped in {@code <html>}. The width is fixed in characters: RuneLite draws a
	 * tooltip in its look and feel's default font (the RuneScape font, {@code RuneLiteLAF}'s {@code defaultFont}), which
	 * the panel's Larger setting doesn't change, so 56 characters is at most about 330 px in every text mode. The same tip
	 * again gives the same text (it's idempotent), and null or blank gives null.
	 */
	static String tip(String tip)
	{
		if (tip == null)
		{
			return null;
		}
		String body = tip.trim();
		if (body.startsWith("<html>"))
		{
			body = body.substring(6);
		}
		body = body.replace("</html>", "").replace("\n", "<br>");
		final StringBuilder out = new StringBuilder("<html>");
		int lines = 0;
		for (String paragraph : body.split("<br>", -1))
		{
			final String text = drawable(unescape(paragraph)).trim();
			if (text.isEmpty() || "null".equals(text))
			{
				continue;
			}
			if (lines > 0)
			{
				out.append("<br>");
			}
			int line = 0;
			for (String word : text.split(" "))
			{
				if (word.isEmpty())
				{
					continue;
				}
				final int len = word.length();
				if (line > 0 && line + 1 + len > TIP_LINE)
				{
					out.append("<br>");
					line = 0;
				}
				else if (line > 0)
				{
					out.append(' ');
					line++;
				}
				out.append(escape(word));
				line += len;
			}
			lines++;
		}
		return lines == 0 ? null : out.append("</html>").toString();
	}

	private static String escape(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static String unescape(String s)
	{
		return s.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
	}

	/**
	 * T-201 (Eric: "an empty yellow box beside Fastest with average luck"): a text as the panel's fonts can draw it. The
	 * RuneScape fonts have only a few characters past Latin-1; Swing draws the rest from a fallback font, or as a box
	 * on a client with none (the Orchestrator's measure on RuneLite 1.13.6). So the minus sign and the dash become the
	 * hyphen, an arrow becomes "to", and so on. The panel's own literals use these already; this catches any that
	 * come from elsewhere ({@code LedgerRows}' rows). The gold star ({@code SidePanelText.STAR}) is painted, never
	 * drawn, so it's kept for the text that paints it.
	 */
	static String drawable(String s)
	{
		if (s == null)
		{
			return null;
		}
		boolean plain = true;
		for (int i = 0; i < s.length() && plain; i++)
		{
			plain = s.charAt(i) < '\u2010';
		}
		if (plain)
		{
			return s;
		}
		return s.replace("\u2212", "-").replace("\u2013", "-").replace("\u2010", "-").replace("\u2011", "-")
			.replace(" \u2192 ", " to ").replace("\u2192", " to ").replace("\u2190", " from ").replace("\u2264", "<=")
			.replace("\u2265", ">=").replace("\u2022", "\u00b7").replace("\u2191", "up ").replace("\u2193", "down ")
			.replace("\u2018", "'").replace("\u2019", "'").replace("\u201c", "\"").replace("\u201d", "\"")
			.replace("\u2009", " ").replace("\u202f", " ").replace("\u2032", "'").replace("\u2713", "")
			.replace("\u25b8", "").replace("\u25be", "");
	}
}
