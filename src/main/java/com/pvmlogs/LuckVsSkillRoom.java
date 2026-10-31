package com.pvmlogs;

import java.util.List;
import java.util.Map;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * T-128: a room's Fight tab, shared with the RuneLite party once, when the room ends: what a member's click-in on the
 * Party tab needs to show the sender's Fight tab, drawn by the Fight tab's own components ({@link SidePanelMemberFight}).
 * Sent over RuneLite's own party server ({@code PartyService.send}), never anywhere else. The live figures stay in
 * T-124's small {@link LuckVsSkillUpdate}, every 5 ticks.
 *
 * <p><b>A wire format between versions of the plugin,</b> as {@link LuckVsSkillUpdate} is: RuneLite keys a party message
 * by its class's simple name, so the name never changes; fields are only ever added, never renamed or given a new
 * meaning; every default means "not sent"; {@link #v} says which version wrote it. Short field names keep it small (sizes
 * in {@code SidePanelMemberFightTest}). No NaN or infinity: Gson can't write them, so unknown is null.
 *
 * <p><b>Only the player's own output,</b> as the update: their damage and its expectation, their attacks, Luck and
 * Execution and what they're made of, the damage they took, their tick strip. Never a name, location, gear, levels, the
 * install id or history; the room's name and its start, as the update has them, key it.
 */
public final class LuckVsSkillRoom extends PartyMemberMessage
{
	static final int VERSION = 1;

	/** The version that wrote it. */
	int v = VERSION;
	/** The room, as {@link LuckVsSkillUpdate#r}, {@link LuckVsSkillUpdate#rs}, {@link LuckVsSkillUpdate#rn}. */
	String r;
	long rs;
	String rn;
	int ri;
	/** The room's Fight tab. */
	Fight f;

	public LuckVsSkillRoom()
	{
	}

	/** The room this is about: as {@link LuckVsSkillUpdate#roomKey}. */
	String roomKey()
	{
		return r + "@" + rs;
	}

	/** What the Fight tab's model reads for a room ({@code SidePanelModel.Input}), as far as a member's view needs. */
	static final class Fight
	{
		/** The scope's figures: {@link Values}. */
		Values s;
		/** The room's name, its boss's name for "Luck counts the X's fights only", and whether it's a run of monsters. */
		String n;
		String sn;
		boolean run;
		int kills;
		boolean died;
		boolean killed;
		/** T-106's room damage on the boss, and its sources' names. */
		int rd;
		List<String> rds;
		int hp;
		/** Solo: null for unknown. */
		Boolean solo;
		boolean unscored;
		int ht;
		int mht;
		String raid;
		Map<String, Integer> tba;
		List<String> avoid;
		int sth;
		int sdt;
		Map<String, Integer> sdk;
		int srf;
		int th;
		int dt;
		Map<String, Integer> dk;
		int rf;
		/** The stats warning's text, or null. */
		String warn;
		/** The boss's version's name ("Araxxor (enraged)"), or null. */
		String ver;
		Drain dr;
		List<Weapon> w;
		Chart c;

		Fight()
		{
		}
	}

	/** A {@code LedgerRows.Scope}'s figures, each method's value; null for a double that was NaN. */
	static final class Values
	{
		int ct;
		int st;
		int d;
		Double e;
		int th;
		int rf;
		int so;
		Double dv;
		int dt;
		Map<String, Integer> dk;
		int he;
		int tl;
		Double tls;
		Double ef;
		Double bs;
		Double bc;
		Double sc;
		Double is;
		int am;
		int ap;
		int as;
		int ad;
		int tk;
		int ot;
		Map<String, Integer> ok;
		int bh;
		int up;
		int hi;
		Double eh;
		int le;
		int tm;
		Double ae;
		int ta;
		int ds;
		int de;
		int ms;
		int me;
		Double dr;
		Double sl;
		/** For {@code luckSeconds}: the scored expected damage and other damage, as {@code Encounter.luckSeconds}. */
		Double lse;
		int lso;

		Values()
		{
		}
	}

	/** T-052's drain record for the room. */
	static final class Drain
	{
		int ds = -1;
		int de = -1;
		int db = -1;
		int df = -1;
		int ms = -1;
		int me = -1;
		Map<String, int[]> sp;
		int pd;
		Double vd;
		Double vs;
		Double ld;
		Double lsec;

		Drain()
		{
		}
	}

	/** A weapon row of the Damage card. */
	static final class Weapon
	{
		String n;
		boolean x;
		int a;
		int d;
		double e;
		boolean ek;
		int t;
		int l;
		double le;
		int lk;
		int mh = -1;
		int b;
		int i = -1;
		/** The spell's {@code VarbitID.AUTOCAST_SPELL} value; 0 for none. */
		int sp;
		/** {@code FightTimeline.Style}'s name. */
		String st;

		Weapon()
		{
		}
	}

	/**
	 * The Damage over time chart and the tick strip: the strip's states run-length encoded, each attack's tick and style,
	 * and the cumulative damage, expected and variance every {@link #STEP} ticks, the last tick always.
	 */
	static final class Chart
	{
		static final int STEP = 5;
		/** Ticks. */
		int n;
		/** The strip: runs of {@code state}{@code count} pairs, as {@code 1x4,2x1,1x3}. */
		String s;
		/** Attacks: {@code tick:style[:x]} by tick offset, {@code x} for a special. */
		String a;
		/** At ticks 0, STEP, 2·STEP ... and the last: cumulative damage, expected, variance. */
		int[] pt;
		int[] pd;
		double[] pe;
		double[] pv;

		Chart()
		{
		}
	}
}
