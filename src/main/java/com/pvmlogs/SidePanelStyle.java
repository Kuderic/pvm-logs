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
	static final Color BETTER = new Color(LedgerRows.Colour.BETTER.rgb);
	static final Color COST = new Color(LedgerRows.Colour.COST.rgb);
	static final Color WARNING = new Color(LedgerRows.Colour.WARNING.rgb);
	static final Color DEAD = new Color(LedgerRows.Colour.DEAD.rgb);
	static final Color ACCENT = ColorScheme.BRAND_ORANGE;

	// ---- fills ----
	static final Color PANEL = ColorScheme.DARK_GRAY_COLOR;
	static final Color CARD = ColorScheme.DARKER_GRAY_COLOR;
	static final Color SELECTED = ColorScheme.DARKER_GRAY_HOVER_COLOR;
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
}
