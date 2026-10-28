package com.pvmlogs;

import java.awt.Color;
import java.awt.Font;
import java.util.Locale;
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

		Font font()
		{
			switch (this)
			{
				case CLOCK:
					return Fonts.CLOCK;
				case TITLE:
					return FontManager.getRunescapeBoldFont();
				case SMALL:
					return FontManager.getRunescapeSmallFont();
				default:
					return FontManager.getRunescapeFont();
			}
		}

		/** The fixed line height: 16 for Regular and Bold, 12 for Small, 30 for the clock. */
		int lineHeight()
		{
			switch (this)
			{
				case CLOCK:
					return 30;
				case SMALL:
					return 12;
				default:
					return 16;
			}
		}
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

	/** {@code 14 s}, never {@code -0 s}; rounded half away from zero. */
	static String seconds(double seconds)
	{
		final long r = LedgerRows.roundHalfAway(seconds);
		return (r == 0 ? 0 : r) + " s";
	}
}
