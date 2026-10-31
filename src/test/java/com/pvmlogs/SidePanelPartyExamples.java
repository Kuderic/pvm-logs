package com.pvmlogs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * T-125: party boards for the Party tab's tests and renders, each member's figures a {@link LuckVsSkillUpdate} as T-124
 * sends them. Names are invented; figures are illustrative. Shared by {@link SidePanelPartyTest} and
 * {@link SidePanelPictures}.
 */
final class SidePanelPartyExamples
{
	private SidePanelPartyExamples()
	{
	}

	static final int TWISTED_BOW = net.runelite.api.gameval.ItemID.TWISTED_BOW;
	static final int ZARYTE_CROSSBOW = net.runelite.api.gameval.ItemID.ZARYTE_XBOW;
	static final int SCYTHE = net.runelite.api.gameval.ItemID.SCYTHE_OF_VITUR;
	static final int BOWFA = net.runelite.api.gameval.ItemID.BOW_OF_FAERDHINEN;

	/** A row of attacks: item, attacks, landed, damage, expected; a special with {@code special}. */
	static LuckVsSkillUpdate.Row attacks(int item, int a, int l, int d, double e, boolean special)
	{
		final LuckVsSkillUpdate.Row r = new LuckVsSkillUpdate.Row();
		r.i = item;
		r.a = a;
		r.l = l;
		r.d = d;
		r.e = e;
		r.x = special;
		return r;
	}

	/**
	 * One member's figures for a room: damage and expected, scored ticks; with {@code after}, Luck at percentile {@code p}
	 * and {@code ls} seconds, Execution {@code x}, uptime and idle ticks, the setup's shares and costs, damage taken.
	 */
	static LuckVsSkillUpdate figures(String room, boolean after, int d, double e, int st, double p, double ls, double x,
		int idle, LuckVsSkillUpdate.Row... rows)
	{
		final LuckVsSkillUpdate u = new LuckVsSkillUpdate();
		u.k = after ? LuckVsSkillUpdate.ROOM_END : LuckVsSkillUpdate.LIVE;
		u.r = room;
		u.rs = 1_000L;
		u.d = d;
		u.e = e;
		u.st = st;
		u.ct = st;
		u.a = st / 4;
		u.kill = after;
		u.p = after ? p : -1;
		u.ls = after ? ls : 0;
		u.x = x;
		u.u = Math.max(0, 1 - idle / (double) Math.max(1, st));
		u.il = idle;
		u.bs = 0.97;
		u.bd = e * 0.012;
		u.gs = 0.98;
		u.gd = e * 0.008;
		u.ps = 1;
		u.pd = 0;
		u.tk = st / 3;
		u.th = st / 30;
		u.w = Arrays.asList(rows);
		return u;
	}

	/** A member: their id, name and figures, and a damage series over {@code ticks} reaching their damage. */
	static SidePanelParty.Member member(long id, String name, boolean you, int slot, LuckVsSkillUpdate f, int ticks)
	{
		final SidePanelParty.Member m = new SidePanelParty.Member(id, name, you, f);
		m.slot = slot;
		final int[][] s = series(f.d, ticks, 4 + (int) (id % 2), id);
		m.ticks = s[0];
		m.damage = s[1];
		return m;
	}

	/** Cumulative damage every 5 ticks, as T-124's updates come, in steps like attacks. */
	static int[][] series(int total, int ticks, int cooldown, long seed)
	{
		final Random r = new Random(seed);
		final double[] raw = new double[ticks + 1];
		double sum = 0;
		for (int t = 1; t <= ticks; t++)
		{
			sum += t % cooldown == 0 ? 0.4 + r.nextDouble() * 1.6 : 0;
			raw[t] = sum;
		}
		final List<int[]> points = new ArrayList<>();
		for (int t = 0; t <= ticks; t += 5)
		{
			points.add(new int[]{t, sum == 0 ? 0 : (int) Math.round(raw[t] / sum * total)});
		}
		if (ticks % 5 != 0)
		{
			points.add(new int[]{ticks, total});
		}
		final int[][] out = new int[2][points.size()];
		for (int i = 0; i < points.size(); i++)
		{
			out[0][i] = points.get(i)[0];
			out[1][i] = points.get(i)[1];
		}
		return out;
	}

	private static SidePanelParty.Input input(String scope, double seconds, boolean live, Boolean wholeRaid, int others)
	{
		final SidePanelParty.Input in = new SidePanelParty.Input();
		in.inParty = true;
		in.scope = scope;
		in.seconds = seconds;
		in.live = live;
		in.wholeRaid = wholeRaid;
		in.othersDamage = others;
		in.itemNames = SidePanelPartyExamples::itemName;
		return in;
	}

	static String itemName(int id)
	{
		return id == TWISTED_BOW ? "Twisted bow" : id == ZARYTE_CROSSBOW ? "Zaryte crossbow" : id == SCYTHE
			? "Scythe of vitur" : id == BOWFA ? "Bow of faerdhinen" : id == net.runelite.api.gameval.ItemID.ABYSSAL_TENTACLE
			? "Abyssal tentacle" : id == net.runelite.api.gameval.ItemID.BONE_CLAWS ? "Burning claws" : "item " + id;
	}

	/** A duo at Nex, both sharing, after the kill: nobody else hit the boss. */
	static SidePanelParty.Input duo()
	{
		final SidePanelParty.Input in = input("Nex", 252, false, null, 19_540);
		in.members.add(member(SidePanelParty.YOU_ID, "you", true, 0, figures("Nex", true, 21_880, 20_910, 420, 0.69, 9,
			0.93, 17, attacks(TWISTED_BOW, 168, 121, 18_670, 17_860, false), attacks(ZARYTE_CROSSBOW, 6, 5, 3_210, 3_050,
				true)), 420));
		in.members.add(member(11, "Ash Kettle", false, 0, figures("Nex", true, 19_540, 20_200, 420, 0.3, -11, 0.90, 29,
			attacks(TWISTED_BOW, 171, 113, 16_880, 17_390, false), attacks(ZARYTE_CROSSBOW, 5, 3, 2_660, 2_810, true)), 420));
		return in;
	}

	/** A 4-man ToA room, Zebak, after the room; one member not sharing, whose damage is in others. */
	static SidePanelParty.Input toa()
	{
		final SidePanelParty.Input in = input("Zebak", 129, false, false, 3_420 + 1_990 + 1_830);
		in.members.add(member(SidePanelParty.YOU_ID, "you", true, 0, figures("Zebak", true, 2_960, 2_880, 215, 0.5, -1,
			0.91, 6, attacks(TWISTED_BOW, 52, 41, 2_410, 2_290, false), attacks(ZARYTE_CROSSBOW, 4, 3, 550, 590, true)),
			215));
		in.members.add(member(21, "Moss Pike", false, 0, figures("Zebak", true, 3_420, 3_150, 215, 0.88, 8, 0.96, 2,
			attacks(TWISTED_BOW, 54, 45, 3_420, 3_150, false)), 215));
		in.members.add(member(22, "Brindle", false, 1, figures("Zebak", true, 1_990, 2_310, 215, 0.09, -12, 0.84, 13,
			attacks(BOWFA, 61, 44, 1_990, 2_310, false)), 215));
		return in;
	}

	/** A 5-man ToB room mid-fight: Verzik, live. */
	static SidePanelParty.Input tobLive()
	{
		final SidePanelParty.Input in = input("Verzik Vitur", 98, true, false, 1_880 + 1_290 + 1_545 + 1_120);
		final String[] names = {"you", "Quillon", "Vey", "Old Tam", "Corvane"};
		final int[] dmg = {1_610, 1_880, 1_290, 1_545, 1_120};
		for (int i = 0; i < names.length; i++)
		{
			in.members.add(member(i == 0 ? SidePanelParty.YOU_ID : 30 + i, names[i], i == 0, i - 1, figures("Verzik Vitur",
				false, dmg[i], dmg[i] * 0.96, 163, -1, 0, 0.9, 12, attacks(i == 2 ? SCYTHE : TWISTED_BOW, 40, 31, dmg[i],
					dmg[i] * 0.96, false)), 163));
		}
		return in;
	}

	/** A 5-man ToB after three rooms, the whole raid on show; Corvane left after Bloat. */
	static SidePanelParty.Input tobRaid()
	{
		final SidePanelParty.Input in = input("Theatre of Blood", 611, false, true, 10_310 + 8_870 + 9_050 + 2_140 + 610);
		final String[] names = {"you", "Quillon", "Vey", "Old Tam", "Corvane"};
		final int[] dmg = {9_420, 10_310, 8_870, 9_050, 2_140};
		final double[] p = {0.6, 0.82, 0.5, 0.25, -1};
		for (int i = 0; i < names.length; i++)
		{
			final SidePanelParty.Member m = member(i == 0 ? SidePanelParty.YOU_ID : 40 + i, names[i], i == 0, i - 1,
				figures("Theatre of Blood", true, dmg[i], dmg[i] * 0.97, 1018, p[i], 15 - 6 * i, i == 4 ? -1 : 0.92 - 0.02 * i,
					31, attacks(SCYTHE, 140, 121, (int) (dmg[i] * 0.7), dmg[i] * 0.68, false), attacks(TWISTED_BOW, 40, 29,
						dmg[i] - (int) (dmg[i] * 0.7), dmg[i] * 0.29, false)), 1018);
			m.left = i == 4;
			in.members.add(m);
		}
		return in;
	}

	/** An 8-man CoX after Olm: six sharing, two not. */
	static SidePanelParty.Input cox()
	{
		final String[] names = {"you", "Quillon", "Vey", "Old Tam", "Corvane", "Ash Kettle"};
		final int[] dmg = {4_120, 4_880, 3_310, 3_905, 2_740, 4_210};
		final double[] p = {0.65, 0.9, 0.18, 0.5, 0.06, 0.56};
		int othersSeen = 6_350;
		for (int i = 1; i < dmg.length; i++)
		{
			othersSeen += dmg[i];
		}
		final SidePanelParty.Input in = input("Great Olm", 301, false, false, othersSeen);
		for (int i = 0; i < names.length; i++)
		{
			in.members.add(member(i == 0 ? SidePanelParty.YOU_ID : 50 + i, names[i], i == 0, i - 1, figures("Great Olm",
				true, dmg[i], dmg[i] * 0.98, 502, p[i], (p[i] - 0.5) * 30, 0.93 - 0.02 * i, 21, attacks(TWISTED_BOW, 70, 52,
					dmg[i], dmg[i] * 0.98, false)), 502));
		}
		return in;
	}

	/** In a party, nobody else sharing. */
	static SidePanelParty.Input nobody()
	{
		final SidePanelParty.Input in = input("Zebak", 129, false, false, 3_000);
		in.members.add(member(SidePanelParty.YOU_ID, "you", true, 0, figures("Zebak", true, 2_960, 2_880, 215, 0.5, -1,
			0.91, 6), 215));
		return in;
	}

	/** Not in a party. */
	static SidePanelParty.Input notInParty()
	{
		final SidePanelParty.Input in = nobody();
		in.inParty = false;
		return in;
	}

	// ---- T-128 ----

	/** A stand-in for a Discord picture: a 128 px square in two tones with a letter (the real ones come from RuneLite). */
	static java.awt.image.BufferedImage picture(int seed, char letter)
	{
		final java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(128, 128,
			java.awt.image.BufferedImage.TYPE_INT_RGB);
		final java.awt.Graphics2D g = img.createGraphics();
		final java.awt.Color a = java.awt.Color.getHSBColor((seed * 0.17f) % 1f, 0.45f, 0.75f);
		final java.awt.Color b = java.awt.Color.getHSBColor((seed * 0.17f + 0.08f) % 1f, 0.55f, 0.45f);
		g.setPaint(new java.awt.GradientPaint(0, 0, a, 128, 128, b));
		g.fillRect(0, 0, 128, 128);
		g.setColor(java.awt.Color.WHITE);
		g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 72));
		final java.awt.FontMetrics f = g.getFontMetrics();
		g.drawString(String.valueOf(letter), (128 - f.charWidth(letter)) / 2, (128 + f.getAscent() - f.getDescent()) / 2);
		g.dispose();
		return img;
	}

	/** The ToA room with Discord pictures for you and Moss Pike; Brindle has none, so her colour stands in. */
	static SidePanelParty.Input toaWithPictures()
	{
		final SidePanelParty.Input in = toa();
		in.members.get(0).avatar = picture(1, 'Y');
		in.members.get(1).avatar = picture(4, 'M');
		return in;
	}

	/** A 4-minute fight played through the plugin's calls, as a member's room message brings it (T-128). */
	static SidePanelTimelineExamples.Built longRoom()
	{
		return SidePanelTimelineExamples.fight("Zebak", "Burning claws", ItemID_BONE_CLAWS, 215, new int[]{5, 20},
			new int[]{60, 4, 150, 6}, new int[]{100, 3}, 2_100, 2_050, 300, 280, 140);
	}

	private static final int ItemID_BONE_CLAWS = net.runelite.api.gameval.ItemID.BONE_CLAWS;

	// ---- T-130 review: each row's panels from the same played fight as its meter row ----

	/**
	 * A member's fight played through the plugin's own calls ({@link SidePanelTimelineExamples#plan}): {@code attacks}
	 * with {@code item} every {@code speed} ticks, about three in four landing, dealing {@code damage} against
	 * {@code expected}, then {@code specs} specials with {@code specItem} dealing {@code specDamage}, and {@code idle}
	 * idle ticks spread through it. Everything a row shows of them (the meter's figures, the chart's line, the Fight
	 * panels) is worked out from it, so they agree.
	 */
	static SidePanelTimelineExamples.Built played(String boss, long seed, int item, String name, int speed, int attacks,
		int damage, double expected, int maxHit, int specItem, String specName, int specs, int specDamage, int idle)
	{
		final Random r = new Random(seed);
		final int[] hits = spread(r, attacks, damage, 0.75);
		for (int h : hits)
		{
			maxHit = Math.max(maxHit, h);
		}
		final List<SidePanelTimelineExamples.Stretch> plan = new ArrayList<>();
		// The idle ticks in three gaps: after a third, two thirds and all of the main weapon's attacks.
		final int third = attacks / 3;
		final int[][] parts = {Arrays.copyOfRange(hits, 0, third), Arrays.copyOfRange(hits, third, 2 * third),
			Arrays.copyOfRange(hits, 2 * third, attacks)};
		for (int i = 0; i < parts.length; i++)
		{
			plan.add(new SidePanelTimelineExamples.Stretch(name, item, FightTimeline.Style.RANGED, false, speed, parts[i],
				expected / attacks, maxHit, 0.75, idle / 3 + (i == 2 ? idle % 3 : 0)));
		}
		if (specs > 0)
		{
			plan.add(new SidePanelTimelineExamples.Stretch(specName, specItem, FightTimeline.Style.RANGED, true, 5,
				spread(r, specs, specDamage, 0.9), specDamage * 0.95 / specs, specDamage / specs * 2, 0.9, 0));
		}
		return SidePanelTimelineExamples.plan(boss, 1_000L, plan.toArray(new SidePanelTimelineExamples.Stretch[0]));
	}

	/** {@code n} hits adding to {@code total}, about {@code landing} of them landing. */
	private static int[] spread(Random r, int n, int total, double landing)
	{
		final double[] w = new double[n];
		double sum = 0;
		for (int i = 0; i < n; i++)
		{
			w[i] = r.nextDouble() < landing ? 0.3 + r.nextDouble() : 0;
			sum += w[i];
		}
		final int[] out = new int[n];
		int left = total;
		for (int i = 0; i < n; i++)
		{
			out[i] = i == n - 1 ? Math.max(0, left) : (int) Math.round(w[i] / Math.max(1e-9, sum) * total);
			left -= out[i];
		}
		return out;
	}

	/** The row for a played fight: the figures as {@link PartyShare#build} sends them, and its line on the chart. */
	static SidePanelParty.Member row(long id, String name, boolean you, int slot, SidePanelTimelineExamples.Built b,
		boolean live)
	{
		return row(id, name, you, slot, b.room, b.view(), live);
	}

	static SidePanelParty.Member row(long id, String name, boolean you, int slot, Encounter room, FightTimeline.View v,
		boolean live)
	{
		final LuckVsSkillUpdate u = PartyShare.build(live ? LuckVsSkillUpdate.LIVE : LuckVsSkillUpdate.ROOM_END, room,
			v.attacks, 0);
		final SidePanelParty.Member m = new SidePanelParty.Member(id, name, you, u);
		m.slot = slot;
		final int[][] series = SidePanelParty.series(v.damage);
		m.ticks = series[0];
		m.damage = series[1];
		return m;
	}

	/** Your own Fight panels for a played fight: the Fight tab's model on it, as the panel builds them. */
	static PanelSnapshot.FightTab yourPanels(SidePanelTimelineExamples.Built b, boolean ended)
	{
		final SidePanelModel.Input in = new SidePanelModel.Input();
		in.zone = SidePanelExamples.ZONE;
		in.kind = SidePanelModel.Kind.ROOM;
		SidePanelModel.fillRoom(in, b.room);
		in.view = b.view();
		in.weaponRows = new SidePanelWeaponRows().rows(b.room, in.view);
		in.ended = ended;
		in.killed = ended;
		// In a party: the label reads "party" (T-132).
		in.inParty = true;
		return SidePanelModel.fight(in);
	}

	/** A member's Fight panels from their room message for a played fight, round-tripped through JSON, named. */
	static PanelSnapshot.FightTab theirPanels(SidePanelTimelineExamples.Built b, String name)
	{
		final com.google.gson.Gson gson = new com.google.gson.Gson();
		final LuckVsSkillRoom sent = gson.fromJson(gson.toJson(SidePanelMemberFight.message(b.room, b.view(), 0, null)),
			LuckVsSkillRoom.class);
		final SidePanelModel.Input in = SidePanelMemberFight.decode(sent.f);
		in.whose = name;
		return SidePanelModel.fight(in);
	}

	/**
	 * T-132: an Araxxor kill with araxytes, you and Brindle, each played (the same plan): her panels from her room message
	 * with the boss-only and adds-only parts, so her Damage card has the switch.
	 */
	static SidePanelParty.Input araxxorWithAdds()
	{
		final SidePanelTargetExamples.Room mine = SidePanelTargetExamples.araxxor();
		final SidePanelTargetExamples.Room hers = SidePanelTargetExamples.araxxor();
		final SidePanelParty.Input in = input("Araxxor", new LedgerRows.Numbers(LedgerRows.of(mine.room)).clock, false,
			null, 0);
		in.members.add(row(SidePanelParty.YOU_ID, "you", true, 0, mine.room, FightTimeline.room(mine.room, mine.store),
			false));
		in.members.add(row(22, "Brindle", false, 1, hers.room, FightTimeline.room(hers.room, hers.store), false));
		final SidePanelModel.Input yours = SidePanelTargetExamples.input(mine);
		yours.inParty = true;
		in.members.get(0).fight = SidePanelModel.fight(yours);
		in.members.get(1).fight = SidePanelModel.fight(araxxorDecoded(hers, "Brindle"));
		in.members.get(0).avatar = picture(1, 'Y');
		in.partyKey = 11;
		return in;
	}

	/** A room's message with its parts' timelines, through JSON, read back as a member's input. */
	static SidePanelModel.Input araxxorDecoded(SidePanelTargetExamples.Room r, String name)
	{
		final com.google.gson.Gson gson = new com.google.gson.Gson();
		final LuckVsSkillRoom sent = gson.fromJson(gson.toJson(SidePanelMemberFight.message(r.room,
			e -> FightTimeline.room(e, r.store), 0, null)), LuckVsSkillRoom.class);
		final SidePanelModel.Input in = SidePanelMemberFight.decode(sent.f);
		in.whose = name;
		return in;
	}

	/**
	 * The ToA room at Zebak: you, Moss Pike and Brindle, each played over the same 215 ticks (2:09) and killed; one more
	 * member not sharing, in others.
	 */
	private static SidePanelTimelineExamples.Built[] zebak()
	{
		final SidePanelTimelineExamples.Built[] b = {
			played("Zebak", 1, TWISTED_BOW, "Twisted bow", 5, 38, 2_410, 2_290, 81, ZARYTE_CROSSBOW, "Zaryte crossbow", 4,
				550, 5),
			played("Zebak", 2, TWISTED_BOW, "Twisted bow", 5, 42, 3_420, 3_150, 81, 0, null, 0, 0, 5),
			played("Zebak", 3, BOWFA, "Bow of faerdhinen", 4, 49, 1_990, 2_310, 52, 0, null, 0, 0, 19)};
		for (SidePanelTimelineExamples.Built x : b)
		{
			x.fight.recordKill();
			x.fight.end(true, 1_000L + 129_000L);
		}
		return b;
	}

	private static SidePanelParty.Input zebakBoard(SidePanelTimelineExamples.Built[] b)
	{
		final SidePanelParty.Input in = input("Zebak", new LedgerRows.Numbers(LedgerRows.of(b[0].room)).clock, false,
			false, 3_420 + 1_990 + 1_830);
		in.members.add(row(SidePanelParty.YOU_ID, "you", true, 0, b[0], false));
		in.members.add(row(21, "Moss Pike", false, 0, b[1], false));
		in.members.add(row(22, "Brindle", false, 1, b[2], false));
		in.partyKey = 7;
		return in;
	}

	/** T-130 review: the ToA board's rows alone, with pictures, as the board gives them before any panels are attached. */
	static SidePanelParty.Input toaRows()
	{
		final SidePanelParty.Input in = zebakBoard(zebak());
		in.members.get(0).avatar = picture(1, 'Y');
		in.members.get(1).avatar = picture(4, 'M');
		return in;
	}

	/** Your Fight panels in that room, as the panel builds them while you're selected. */
	static PanelSnapshot.FightTab toaYourPanels()
	{
		return yourPanels(zebak()[0], true);
	}

	/** Brindle's room message for that room, as it arrives (through JSON). */
	static LuckVsSkillRoom brindlesMessage()
	{
		final SidePanelTimelineExamples.Built b = zebak()[2];
		final com.google.gson.Gson gson = new com.google.gson.Gson();
		return gson.fromJson(gson.toJson(SidePanelMemberFight.message(b.room, b.view(), 0, null)), LuckVsSkillRoom.class);
	}

	/** The ToA room with Brindle's Fight panels from her room message, and pictures for you and Moss Pike. */
	static SidePanelParty.Input toaBrindleAfter()
	{
		final SidePanelTimelineExamples.Built[] b = zebak();
		final SidePanelParty.Input in = zebakBoard(b);
		in.members.get(0).avatar = picture(1, 'Y');
		in.members.get(1).avatar = picture(4, 'M');
		in.members.get(2).fight = theirPanels(b[2], "Brindle");
		return in;
	}

	/**
	 * T-130: the ToA room with your own Fight panels (the Fight tab's) and Brindle's from her room message; Moss Pike's
	 * message isn't in, so his are the compact breakdown. Every row's figures, line and panels from its own fight.
	 */
	static SidePanelParty.Input toaYouAndBrindle()
	{
		final SidePanelTimelineExamples.Built[] b = zebak();
		final SidePanelParty.Input in = zebakBoard(b);
		in.members.get(0).avatar = picture(1, 'Y');
		in.members.get(1).avatar = picture(4, 'M');
		in.members.get(0).fight = yourPanels(b[0], true);
		in.members.get(2).fight = theirPanels(b[2], "Brindle");
		return in;
	}

	/** The ToB room mid-fight at Verzik, five sharing, each played; Quillon's panels from his live update. */
	static SidePanelParty.Input tobQuillonLive()
	{
		final String[] names = {"you", "Quillon", "Vey", "Old Tam", "Corvane"};
		final int[] dmg = {1_610, 1_880, 1_290, 1_545, 1_120};
		final int[] items = {TWISTED_BOW, TWISTED_BOW, SCYTHE, TWISTED_BOW, BOWFA};
		final SidePanelTimelineExamples.Built[] b = new SidePanelTimelineExamples.Built[names.length];
		for (int i = 0; i < names.length; i++)
		{
			final int speed = items[i] == BOWFA ? 4 : 5;
			// Every one 163 ticks into the room (1:38), their idle ticks what their attacks leave.
			final int attacks = (163 - 8 - 3 * i) / speed;
			b[i] = played("Verzik Vitur", 30 + i, items[i], itemName(items[i]), speed, attacks, dmg[i], dmg[i] * 0.96, 70,
				0, null, 0, 0, 163 - attacks * speed);
		}
		final SidePanelParty.Input in = input("Verzik Vitur", new LedgerRows.Numbers(LedgerRows.of(b[0].room)).clock,
			true, false, 1_880 + 1_290 + 1_545 + 1_120);
		for (int i = 0; i < names.length; i++)
		{
			in.members.add(row(i == 0 ? SidePanelParty.YOU_ID : 30 + i, names[i], i == 0, i - 1, b[i], true));
		}
		final SidePanelParty.Member q = in.members.get(1);
		final SidePanelModel.Input live = SidePanelMemberFight.live(q.figures, SidePanelPartyExamples::itemName);
		live.whose = q.name;
		q.fight = SidePanelModel.fight(live);
		q.fightLive = true;
		q.avatar = picture(7, 'Q');
		in.members.get(0).fight = yourPanels(b[0], false);
		in.partyKey = 9;
		return in;
	}

	/** The loopback: you and your echo, the same updates, on one axis. */
	static SidePanelParty.Input withEcho()
	{
		final SidePanelParty.Input in = input("Araxxor", 129, false, null, 2_960);
		final SidePanelParty.Member you = member(SidePanelParty.YOU_ID, "you", true, 0, figures("Araxxor", true, 2_960,
			2_880, 215, 0.5, -1, 0.91, 6, attacks(TWISTED_BOW, 52, 41, 2_960, 2_880, false)), 215);
		in.members.add(you);
		final SidePanelParty.Member echo = new SidePanelParty.Member(31, PartyBoard.ECHO_NAME, false, you.figures);
		echo.ticks = you.ticks;
		echo.damage = you.damage;
		in.members.add(echo);
		return in;
	}
}
