package com.pvmlogs;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.gameval.ItemID;

// The dart loaded in a blowpipe. No varbit says which (RuneLite API 1.13.4 has only the dart count), so since T-015 it is
// read from what the player does: checking the blowpipe, or using darts on it. Until then a dragon dart is assumed.
@Getter
@RequiredArgsConstructor
public enum BlowpipeDart
{
	NONE("None", -1),
	BRONZE("Bronze", ItemID.BRONZE_DART),
	IRON("Iron", ItemID.IRON_DART),
	STEEL("Steel", ItemID.STEEL_DART),
	BLACK("Black", ItemID.BLACK_DART),
	MITHRIL("Mithril", ItemID.MITHRIL_DART),
	ADAMANT("Adamant", ItemID.ADAMANT_DART),
	RUNE("Rune", ItemID.RUNE_DART),
	AMETHYST("Amethyst", ItemID.AMETHYST_DART),
	DRAGON("Dragon", ItemID.DRAGON_DART);

	private final String displayName;
	private final int itemId;

	// The blowpipe's Check message: "Darts: Dragon dart x 1,234. Scales: 12,345 (75.0%)." The wording is from memory of the
	// game, not a log, so any "Darts:" message this does not understand is logged by the plugin.
	private static final Pattern CHECK = Pattern.compile("\\bDarts?:\\s*([A-Za-z]+)(?:\\s+dart)?", Pattern.CASE_INSENSITIVE);
	// Using darts on the blowpipe: the menu target reads "Dragon dart -> Toxic blowpipe".
	private static final Pattern USE = Pattern.compile("^\\s*([A-Za-z]+) dart(?:\\(p\\+*\\))?\\s*->\\s*.*blowpipe",
		Pattern.CASE_INSENSITIVE);

	@Override
	public String toString()
	{
		return displayName;
	}

	/** The dart named in a blowpipe Check message (tags already stripped), or null if it names none this knows. */
	static BlowpipeDart fromCheckMessage(String message)
	{
		if (message == null)
		{
			return null;
		}
		final Matcher m = CHECK.matcher(message);
		return m.find() ? named(m.group(1)) : null;
	}

	/** The dart in a "Use" menu target on a blowpipe (tags stripped), or null if it is not one. */
	static BlowpipeDart fromUseTarget(String target)
	{
		if (target == null)
		{
			return null;
		}
		final Matcher m = USE.matcher(target);
		return m.find() ? named(m.group(1)) : null;
	}

	private static BlowpipeDart named(String word)
	{
		final String w = word.toLowerCase(Locale.ROOT);
		for (BlowpipeDart dart : values())
		{
			if (dart.displayName.toLowerCase(Locale.ROOT).equals(w))
			{
				return dart;
			}
		}
		return null;
	}
}
