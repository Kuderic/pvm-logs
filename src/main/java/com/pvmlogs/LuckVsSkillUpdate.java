package com.pvmlogs;

import java.util.ArrayList;
import java.util.List;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * T-124: the player's own figures, shared with their RuneLite party, for the
 * side panel's Party card (T-125). Sent over RuneLite's own party server
 * ({@code PartyService.send}), never anywhere else.
 *
 * <p><b>A wire format between versions of the plugin.</b> RuneLite keys a
 * party message by its class's simple name and reads it with its own Gson, so:
 * <ul>
 * <li>the class's name never changes ({@code PartyShareTest} pins it);</li>
 * <li>fields are only ever added, never renamed or given a new meaning: an
 * older build ignores a field it doesn't know, and a newer one reads a field
 * an older build never sent as its default, so every default below means
 * "not sent" or "unknown";</li>
 * <li>{@link #v} says which version wrote it, for a reader that needs to
 * tell.</li>
 * </ul>
 * Short field names keep it small (about 0.5-0.9 KB with eight attack rows;
 * see the test). No NaN or infinity: Gson can't write them, so unknown is -1.
 *
 * <p><b>Only the player's own output:</b> their damage, its expectation,
 * their attacks, Luck and Execution, the damage they took. Nothing about the
 * boss's state or behaviour, and never a name, location, gear, levels, the
 * install id or history. The party already shows each member's display name.
 */
public final class LuckVsSkillUpdate extends PartyMemberMessage
{
	static final int VERSION = 1;
	/** {@link #k}: a running room's figures, at most every {@link PartyShare#INTERVAL_TICKS} ticks. */
	static final int LIVE = 0;
	/** {@link #k}: a room's summary as it ended. */
	static final int ROOM_END = 1;
	/** At most this many attack rows; the rest are summed into one row with item and spell -1. */
	static final int MAX_ROWS = 8;

	/** The version that wrote it. */
	int v = VERSION;
	/** {@link #LIVE} or {@link #ROOM_END}. */
	int k;
	/** The room's name: the boss, or the raid room. */
	String r;
	/** The room's start, epoch millis: tells two rooms of one name apart. */
	long rs;
	/** The raid's name, or null outside a raid. */
	String rn;
	/** The sender's own number for the raid, grouping its rooms; 0 outside a raid. Means nothing to another client. */
	int ri;
	/** The weapon's damage (A). */
	int d;
	/** What the weapon's attacks were expected to deal (E). */
	double e;
	/** Scored combat ticks: what DPS is a rate over. */
	int st;
	/** Attacks made. */
	int a;
	/** The room's clock in ticks, from the first attack: at {@link #ROOM_END} on a kill, the kill time. */
	int ct;
	/** At {@link #ROOM_END}: the room ended on its kill. */
	boolean kill;
	/** Luck's percentile, 0 to 1; -1 unknown. */
	double p = -1;
	/** Luck in seconds, positive when the rolls saved time; 0 with {@link #p} unknown. */
	double ls;
	/** Execution, 0 to 1; -1 unknown. */
	double x = -1;
	/** Uptime, 0 to 1; -1 unknown. */
	double u = -1;
	/** Idle ticks lost (T-117). */
	int il;
	/** The stat boost, gear and prayer's cost in expected damage (T-117); -1 unknown. */
	double bd = -1;
	double gd = -1;
	double pd = -1;
	/** The shares of the full boost, the best gear on hand and the right prayer, 0 to 1; -1 unknown. */
	double bs = -1;
	double gs = -1;
	double ps = -1;
	/** Damage taken from hits, and the hits. */
	int tk;
	int th;
	/** The attack rows, as the Damage card has them, biggest damage first. */
	List<Row> w;
	/**
	 * T-134: the sender's other damage on the scored fights (thrall, recoil and vengeance, damage over time:
	 * {@code LedgerRows.Scope.scoredOtherDamage()}), luck-neutral. Add-only: 0 from a sender before T-134.
	 */
	int o;

	public LuckVsSkillUpdate()
	{
	}

	/** One weapon's or spell's attacks, its special apart. */
	static final class Row
	{
		/** The weapon's item id; -1 for the row the rest are summed into. */
		int i = -1;
		/** The spell's id ({@code VarbitID.AUTOCAST_SPELL}'s value, as {@link Spell}'s); 0 for none. */
		int s;
		/** Its special: then {@link #a} is specials thrown and {@link #l} those that landed. */
		boolean x;
		/** Attacks. */
		int a;
		/** Attacks that put damage down. */
		int l;
		int d;
		double e;

		Row()
		{
		}
	}

	/** Whether this is a running room's figures. */
	boolean isLive()
	{
		return k == LIVE;
	}

	/** The room this is about, for keying a member's rooms: its name and start. */
	/** T-134: all the sender's damage, the weapon's and the other: what the Party meter shows. */
	int all()
	{
		return Math.max(0, d) + Math.max(0, o);
	}

	String roomKey()
	{
		return r + "@" + rs;
	}

	List<Row> rows()
	{
		return w == null ? new ArrayList<>() : w;
	}

	/** -1 for anything Gson couldn't write, else to two decimals. */
	static double wire(double value)
	{
		return Double.isNaN(value) || Double.isInfinite(value) ? -1 : Math.round(value * 100) / 100.0;
	}
}
