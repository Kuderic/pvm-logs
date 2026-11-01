package com.pvmlogs;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.border.EmptyBorder;

/**
 * T-142 stage 1: three layouts for an overhauled Defence card, on mock data shaped like T-141's per-stat and
 * per-special drain fields, for Eric to pick. Built from the side panel's own parts (cards, lines, the drain bar), so
 * they look as the panel would. Stage 2 replaces this with the pick, wired to T-141.
 */
public final class SidePanelDefenceMocks
{
	private SidePanelDefenceMocks()
	{
	}

	/** One special's drains on one stat: its landings, what it took off, what it added to your attacks on that stat. */
	static final class Source
	{
		final String name;
		/** Landed of thrown; -1 for a party member's drain (counted, not thrown by you). */
		final int landed;
		final int thrown;
		final int drained;
		final int added;
		/** {@code expected 2.4 to land}, for the tooltip; empty for none. */
		final String expected;

		Source(String name, int landed, int thrown, int drained, int added, String expected)
		{
			this.name = name;
			this.landed = landed;
			this.thrown = thrown;
			this.drained = drained;
			this.added = added;
			this.expected = expected;
		}

		boolean party()
		{
			return landed < 0;
		}

		String landings()
		{
			return party() ? thrown + (thrown == 1 ? " drain" : " drains") : "landed " + landed + " of " + thrown;
		}

		String shortLandings()
		{
			return party() ? String.valueOf(thrown) : landed + "/" + thrown;
		}
	}

	/** One drained stat: its path, base and floor, its regen, which attacks it helps, its sources. */
	static final class Stat
	{
		final String name;
		final String shortName;
		final int start;
		final int end;
		final int base;
		final int floor;
		/** A bonus (magic defence +60) rather than a level: shown signed. */
		final boolean bonus;
		final String regen;
		final String helps;
		final List<Source> sources;

		Stat(String name, String shortName, int start, int end, int base, int floor, boolean bonus, String regen,
			String helps, Source... sources)
		{
			this.name = name;
			this.shortName = shortName;
			this.start = start;
			this.end = end;
			this.base = base;
			this.floor = floor;
			this.bonus = bonus;
			this.regen = regen;
			this.helps = helps;
			this.sources = Arrays.asList(sources);
		}

		int added()
		{
			int a = 0;
			for (Source s : sources)
			{
				a += s.added;
			}
			return a;
		}

		String value(int v)
		{
			return bonus ? (v >= 0 ? "+" + v : "−" + -v) : String.valueOf(v);
		}

		String path()
		{
			return value(start) + " → " + value(end);
		}

		boolean atFloor()
		{
			return floor > 0 && end <= floor;
		}
	}

	/** A kill's drains: its stats, the total (their sum), its time on hover, the specials' luck. */
	static final class Kill
	{
		final String boss;
		final List<Stat> stats;
		final String seconds;
		final int specLuck;
		final String specLuckSeconds;

		Kill(String boss, String seconds, int specLuck, String specLuckSeconds, Stat... stats)
		{
			this.boss = boss;
			this.stats = Arrays.asList(stats);
			this.seconds = seconds;
			this.specLuck = specLuck;
			this.specLuckSeconds = specLuckSeconds;
		}

		int added()
		{
			int a = 0;
			for (Stat s : stats)
			{
				a += s.added();
			}
			return a;
		}
	}

	// ---- The cases (illustrative figures, shaped on Eric's kills and T-141's brief) ----

	/** Yama: the Emberlight on his Defence (for melee and ranged), Soul Rend on his magic defence (for magic). */
	static Kill yama()
	{
		return new Kill("Yama", "about 6 s faster", 9, "1 s faster",
			new Stat("Defence", "Def", 225, 191, 225, 145, false, "+1 every 100 ticks", "melee & ranged",
				new Source("Emberlight", 2, 5, 34, 38, "expected 2.4 to land")),
			new Stat("Magic defence", "Magic def", 60, 20, 60, 0, true, "", "magic",
				new Source("Soul Rend", 3, 3, 40, 27, "expected 2.6 to land")));
	}

	/** General Graardor: a Dragon warhammer (one of two landed) and a Bandos godsword, both on his Defence. */
	static Kill graardor()
	{
		return new Kill("General Graardor", "about 11 s faster", -18, "1 s slower",
			new Stat("Defence", "Def", 250, 113, 250, 0, false, "+1 every 100 ticks", "melee & ranged",
				new Source("Dragon warhammer", 1, 2, 75, 96, "expected 1.2 to land"),
				new Source("Bandos godsword", 1, 1, 62, 51, "drains its damage")));
	}

	/** The Abyssal Sire: the Emberlight alone. */
	static Kill sire()
	{
		return new Kill("Abyssal Sire", "about 4 s faster", 12, "1 s faster",
			new Stat("Defence", "Def", 250, 174, 250, 0, false, "", "melee & ranged",
				new Source("Emberlight", 2, 2, 76, 42, "expected 1.7 to land")));
	}

	/** Xarth at its floor: your two hammers (the second had nothing left) and a party member's drain. */
	static Kill floorAndParty()
	{
		return new Kill("Xarth", "about 5 s faster", 2, "about even",
			new Stat("Defence", "Def", 170, 102, 170, 102, false, "", "melee & ranged",
				new Source("Dragon warhammer", 2, 2, 34, 41, "expected 1.4 to land"),
				new Source("Party", -1, 1, 34, 19, "")));
	}

	// ---- Rows ----

	private static PanelSnapshot.Text small(String s, Color c)
	{
		return PanelSnapshot.Text.small(s, c);
	}

	private static PanelSnapshot.Text body(String s, Color c)
	{
		return PanelSnapshot.Text.body(s, c);
	}

	private static String signedDmg(int d)
	{
		return (d >= 0 ? "+" : "−") + Math.abs(d) + " dmg";
	}

	private static JComponent line(PanelSnapshot.Text left, PanelSnapshot.Text hint, PanelSnapshot.Text right, int indent,
		String tooltip)
	{
		final SidePanelParts.Line l = new SidePanelParts.Line(new PanelSnapshot.Row(left, hint == null
			? PanelSnapshot.Text.NONE : hint, right, tooltip));
		if (indent > 0)
		{
			l.setBorder(new EmptyBorder(0, indent, 0, 0));
		}
		return l;
	}

	private static JComponent bar(Stat s)
	{
		final SidePanelDrainBar.Bar b = new SidePanelDrainBar.Bar();
		// A bonus (magic defence) is drawn from 0 up to its start.
		b.set(s.start, s.end, Math.max(1, s.base), s.floor);
		return b;
	}

	private static List<JComponent> totals(Kill k)
	{
		final List<JComponent> out = new ArrayList<>();
		out.add(line(body("Drains added", SidePanelStyle.BODY), null, body(signedDmg(k.added()), SidePanelStyle.BETTER),
			0, k.seconds));
		out.add(line(body("Specials' luck", SidePanelStyle.BODY), null, body(signedDmg(k.specLuck), k.specLuck >= 0
			? SidePanelStyle.BETTER : SidePanelStyle.COST), 0, k.specLuckSeconds));
		return out;
	}

	// ---- A: stacked blocks ----

	/** A: a block per drained stat (its path, a small bar with the floor, its specials, its regen, what it added). */
	static JComponent stacked(Kill k)
	{
		final JPanel card = SidePanelParts.card(4);
		for (int i = 0; i < k.stats.size(); i++)
		{
			final Stat s = k.stats.get(i);
			if (i > 0)
			{
				card.add(gap(6));
			}
			card.add(line(body(s.name, SidePanelStyle.VALUE), s.atFloor() ? small(" at its floor", SidePanelStyle.MUTED)
				: null, body(s.path(), SidePanelStyle.VALUE), 0, null));
			card.add(bar(s));
			sources(card, s, SidePanelParts.INDENT);
		}
		card.add(gap(6));
		for (JComponent t : totals(k))
		{
			card.add(t);
		}
		return card;
	}

	/**
	 * Each special under its stat: its name and what it added on the first line, its landings and what it took off
	 * under it; then the stat's regen, and which of your attacks it helped (with their sum, where there are two or more).
	 */
	private static void sources(JPanel card, Stat s, int indent)
	{
		for (Source src : s.sources)
		{
			card.add(line(body(src.party() ? "Party drain" : src.name, SidePanelStyle.BODY), null, body(signedDmg(src.added),
				SidePanelStyle.BETTER), indent, src.expected.isEmpty() ? null : src.expected));
			card.add(line(small(src.landings() + " · −" + src.drained + " " + s.shortName, SidePanelStyle.SECONDARY), null,
				PanelSnapshot.Text.NONE, indent + 6, null));
		}
		if (!s.regen.isEmpty())
		{
			card.add(line(small("regens " + s.regen, SidePanelStyle.MUTED), null, PanelSnapshot.Text.NONE, indent, null));
		}
		card.add(line(small("for your " + s.helps, SidePanelStyle.MUTED), null, s.sources.size() > 1
			? small(signedDmg(s.added()), SidePanelStyle.BETTER) : PanelSnapshot.Text.NONE, indent, null));
	}

	private static String perSource(Stat s)
	{
		final List<String> parts = new ArrayList<>();
		for (Source src : s.sources)
		{
			parts.add((src.party() ? "party" : src.name) + " " + signedDmg(src.added));
		}
		return String.join(", ", parts);
	}

	// ---- B: a table ----

	/** B: a row per special under a header per stat: weapon, landed, drained, added. */
	static JComponent table(Kill k)
	{
		final JPanel card = SidePanelParts.card(4);
		card.add(new Table(null, new String[]{"special", "landed", "took", "added"}, true));
		for (Stat s : k.stats)
		{
			card.add(gap(3));
			card.add(line(body(s.name, SidePanelStyle.VALUE), null, body(s.path(), SidePanelStyle.VALUE), 0,
				"For your " + s.helps + (s.regen.isEmpty() ? "" : "; regens " + s.regen)));
			for (Source src : s.sources)
			{
				card.add(new Table(src, new String[]{src.party() ? "Party" : src.name, src.shortLandings(),
					"−" + src.drained, "+" + src.added}, false));
			}
		}
		card.add(gap(6));
		for (JComponent t : totals(k))
		{
			card.add(t);
		}
		return card;
	}

	/** One row of B's table: a name, then three right-aligned columns. */
	static final class Table extends JComponent
	{
		private final String[] cells;
		private final boolean header;

		Table(Source s, String[] cells, boolean header)
		{
			this.cells = cells;
			this.header = header;
		}

		private SidePanelStyle.Type type()
		{
			return header ? SidePanelStyle.Type.SMALL : SidePanelStyle.Type.BODY;
		}

		@Override
		public Dimension getPreferredSize()
		{
			return new Dimension(SidePanelStyle.CONTENT_WIDTH - 2 * SidePanelStyle.CARD_PAD_H, type().lineHeight());
		}

		@Override
		protected void paintComponent(Graphics g0)
		{
			final Graphics2D g = (Graphics2D) g0.create();
			SidePanelStyle.textHints(g);
			final Font f = type().font();
			final FontMetrics fm = SidePanelStyle.metrics(g, f);
			g.setFont(f);
			final int w = getWidth();
			final int base = fm.getAscent();
			// Columns from the right: added, drained, landed, each as wide as its widest figure.
			final FontMetrics body = SidePanelStyle.metrics(g, SidePanelStyle.Type.BODY.font());
			final int col = Math.max(body.stringWidth("−888"), SidePanelStyle.metrics(g, SidePanelStyle.Type.SMALL.font())
				.stringWidth("landed")) + 8;
			final int[] right = {w, w - col, w - 2 * col};
			final Color[] colours = header ? new Color[]{SidePanelStyle.MUTED, SidePanelStyle.MUTED, SidePanelStyle.MUTED}
				: new Color[]{SidePanelStyle.BETTER, SidePanelStyle.VALUE, SidePanelStyle.SECONDARY};
			for (int i = 0; i < 3; i++)
			{
				final String c = cells[3 - i];
				g.setColor(colours[i]);
				g.drawString(c, right[i] - fm.stringWidth(c), base);
			}
			g.setColor(header ? SidePanelStyle.MUTED : SidePanelStyle.BODY);
			g.drawString(SidePanelPartyMeter.cut(cells[0], fm, right[2] - col - SidePanelParts.INDENT + 4),
				header ? 0 : SidePanelParts.INDENT, base);
			g.dispose();
		}
	}

	// ---- C: compact, expanding ----

	/** C: a line per stat, its specials and value folded in; {@code open} stats unfold to A's block. */
	static JComponent compact(Kill k, int open)
	{
		final JPanel card = SidePanelParts.card(4);
		for (int i = 0; i < k.stats.size(); i++)
		{
			final Stat s = k.stats.get(i);
			final List<String> specs = new ArrayList<>();
			for (Source src : s.sources)
			{
				specs.add((src.party() ? "party" : src.name) + " " + src.shortLandings());
			}
			final boolean opened = i == open;
			card.add(line(body((opened ? "▾ " : "▸ ") + s.shortName + " " + s.path(), SidePanelStyle.VALUE), null,
				body(signedDmg(s.added()), SidePanelStyle.BETTER), 0, null));
			card.add(line(small(String.join(" · ", specs), SidePanelStyle.SECONDARY), null, PanelSnapshot.Text.NONE,
				SidePanelParts.INDENT + 4, null));
			if (opened)
			{
				card.add(bar(s));
				sources(card, s, SidePanelParts.INDENT + 4);
			}
			if (i + 1 < k.stats.size())
			{
				card.add(gap(4));
			}
		}
		card.add(gap(6));
		for (JComponent t : totals(k))
		{
			card.add(t);
		}
		return card;
	}

	private static JComponent gap(int h)
	{
		final JPanel p = new JPanel();
		p.setOpaque(false);
		p.setPreferredSize(new Dimension(1, h));
		return p;
	}

	// ---- Renders ----

	static BufferedImage render(Kill k, JComponent card, String hint, double scale)
	{
		final JPanel page = new JPanel(new SidePanelStack(SidePanelStyle.BLOCK_GAP));
		page.setBackground(SidePanelStyle.PANEL);
		page.setBorder(new EmptyBorder(SidePanelStyle.BLOCK_GAP, SidePanelStyle.SIDE_BORDER, 10, SidePanelStyle.SIDE_BORDER));
		// "Drains" rather than "Defence": at Yama the card holds his magic defence too.
		page.add(new SidePanelSection("defence", "Drains", PanelSnapshot.Text.small(hint, SidePanelStyle.SECONDARY), card,
			new SidePanelSection.MemoryFolds()));
		return SidePanelPictures.draw(new JPanel(null), page, scale);
	}

	public static void main(String[] args) throws IOException
	{
		final Path out = Paths.get(args.length > 0 ? args[0] : "build/defence-mocks");
		final String[] modes = {"normal", "larger"};
		final Kill[] kills = {yama(), graardor(), sire(), floorAndParty()};
		final String[] names = {"1-yama", "2-warhammer-and-godsword", "3-sire-emberlight", "4-floor-and-party"};
		for (int m = 0; m < modes.length; m++)
		{
			SidePanelStyle.setText(m == 1, false);
			final Path dir = out.resolve(modes[m]);
			Files.createDirectories(dir);
			for (double scale : new double[]{1, 2})
			{
				for (int i = 0; i < kills.length; i++)
				{
					final Kill k = kills[i];
					final String hint = k.stats.size() == 1 ? k.stats.get(0).path() : k.stats.size() + " stats";
					write(dir, names[i] + "-a-stacked", scale, render(k, stacked(k), signedDmg(k.added()), scale));
					write(dir, names[i] + "-b-table", scale, render(k, table(k), signedDmg(k.added()), scale));
					write(dir, names[i] + "-c-compact", scale, render(k, compact(k, -1), signedDmg(k.added()), scale));
					write(dir, names[i] + "-c-compact-opened", scale, render(k, compact(k, 0), signedDmg(k.added()),
						scale));
				}
			}
		}
		SidePanelStyle.setText(false, false);
	}

	private static void write(Path dir, String name, double scale, BufferedImage img) throws IOException
	{
		final Path file = dir.resolve(name + "@" + (int) scale + "x.png");
		try (OutputStream o = Files.newOutputStream(file))
		{
			ImageIO.write(img, "png", o);
		}
		System.out.println(file + " " + img.getWidth() + "x" + img.getHeight());
	}
}
