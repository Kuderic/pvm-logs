package com.pvmlogs;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * T-198 (Eric played T-196's card, 2026-11-09 23:34): the card's tooltips wrap, the grade chips sit inside their boxes
 * at every text size, and a folded section looks clickable: a chevron, a hover state, the hand cursor, a tooltip.
 */
public class SidePanelCardFixesTest
{
	@After
	public void plainText()
	{
		SidePanelStyle.setText(false, false);
	}

	@Test
	public void aTooltipWrapsIntoShortLines()
	{
		final String tip = SidePanelStyle.wrapTip("Your grade, from your play only (never luck), against your own last kills"
			+ " of this boss.<br>" + SidePanelModel.TICK_NOTE);
		assertTrue(tip, tip.startsWith("<html>") && tip.endsWith("</html>"));
		for (String line : tip.substring(6, tip.length() - 7).split("<br>"))
		{
			assertTrue(line, line.length() <= SidePanelStyle.TIP_LINE);
		}
		assertTrue(tip, tip.contains("never luck"));
		assertEquals("<html>short</html>", SidePanelStyle.wrapTip("short"));
	}

	@Test
	public void theCardsTooltipsWrapAtEveryTextSize()
	{
		for (int mode = 0; mode < 3; mode++)
		{
			SidePanelStyle.setText(mode == 1, mode == 2);
			final SidePanelKillCard card = new SidePanelKillCard(SidePanelModel.fight(
				SidePanelKillCardExamples.allBadges()).card, SidePanelIcons.NONE);
			card.setSize(SidePanelStyle.CONTENT_WIDTH, card.getPreferredSize().height);
			paint(card);
			int tips = 0;
			for (int y = 0; y < card.getHeight(); y += 3)
			{
				final String tip = card.getToolTipText(new MouseEvent(card, MouseEvent.MOUSE_MOVED, 0, 0, 40, y, 0, false));
				if (tip == null)
				{
					continue;
				}
				tips++;
				assertTrue(tip, tip.startsWith("<html>"));
				for (String line : tip.replaceAll("</?html>", "").split("<br>"))
				{
					assertTrue(mode + ": " + line, line.replaceAll("<[^>]*>", "").length() <= SidePanelStyle.TIP_LINE);
				}
			}
			assertTrue(tips > 0);
		}
	}

	@Test
	public void theFourGradeChipsSitInsideTheirBoxes()
	{
		for (int mode = 0; mode < 3; mode++)
		{
			SidePanelStyle.setText(mode == 1, mode == 2);
			for (String grade : new String[]{"S", "A", "B", "C"})
			{
				final SidePanelModel.Input in = SidePanelKillCardExamples.araxxorKill();
				in.standing.grade = grade;
				final SidePanelKillCard card = new SidePanelKillCard(SidePanelModel.fight(in).card, SidePanelIcons.NONE);
				card.setSize(SidePanelStyle.CONTENT_WIDTH, card.getPreferredSize().height);
				final BufferedImage img = paint(card);
				final Rectangle box = card.gradeBox;
				assertTrue(grade, box.width > 0);
				int letter = 0;
				for (int y = box.y + 1; y < box.y + box.height - 1; y++)
				{
					for (int x = box.x + 1; x < box.x + box.width - 1; x++)
					{
						// Any pixel off the chip's ground is the letter's, smoothed or not (CI's fonts may smooth it).
						if (!near(img.getRGB(x, y), SidePanelStyle.CARD))
						{
							letter++;
							// No letter pixel against the edge: a pixel of clear ground either side, and above and below.
							final boolean edge = x <= box.x + 1 || x >= box.x + box.width - 2 || y <= box.y + 1
								|| y >= box.y + box.height - 2;
							assertFalse(mode + " " + grade + " touches its edge at " + x + "," + y + " in " + box, edge);
						}
					}
				}
				assertTrue(mode + " " + grade + " drew its letter", letter > 20);
			}
		}
	}

	private static boolean near(int rgb, Color c)
	{
		final int r = (rgb >> 16) & 0xFF;
		final int g = (rgb >> 8) & 0xFF;
		final int b = rgb & 0xFF;
		return Math.abs(r - c.getRed()) + Math.abs(g - c.getGreen()) + Math.abs(b - c.getBlue()) < 60;
	}

	private static BufferedImage paint(SidePanelKillCard card)
	{
		final BufferedImage img = new BufferedImage(card.getWidth(), card.getHeight(), BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		card.paint(g);
		g.dispose();
		return img;
	}

	@Test
	public void aFoldedHeaderShowsTheChevronHandCursorHoverAndTooltip()
	{
		for (boolean celebrations : new boolean[]{true, false})
		{
			final SidePanelModel.Input in = celebrations ? SidePanelKillCardExamples.araxxorKill()
				: SidePanelKillCardExamples.off();
			final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
			p.setCelebrations(in.celebrations);
			p.apply(new PanelSnapshot(SidePanelModel.fight(in), null, SidePanelParty.party(new SidePanelParty.Input())));
			final SidePanelSection luck = SidePanelPictures.sectionOf(p.fightTab(), "curve");
			assertNotNull(luck);
			if (!luck.isFolded())
			{
				luck.toggle();
			}
			assertFalse("the chevron points right while folded", luck.chevronOpen());
			assertEquals(SidePanelSection.OPEN_TIP, Tips.plain(luck.headerTip()));
			// The hand cursor over the whole header.
			final java.awt.Component head = luck.getComponent(0);
			assertEquals(Cursor.HAND_CURSOR, head.getCursor().getType());
			// Hover: a lighter ground, repainted, never laid out.
			final int before = SidePanelParts.relayouts;
			head.dispatchEvent(new MouseEvent(head, MouseEvent.MOUSE_ENTERED, 0, 0, 5, 5, 0, false));
			assertTrue(luck.hovered());
			// T-202: the same folded card with Celebrations off; T-206: the title strip alone lights.
			assertTrue(luck.strip().isOpaque());
			assertEquals(SidePanelStyle.CARD_HOVER, luck.strip().getBackground());
			head.dispatchEvent(new MouseEvent(head, MouseEvent.MOUSE_EXITED, 0, 0, -5, -5, 0, false));
			assertFalse(luck.hovered());
			assertEquals(before, SidePanelParts.relayouts);
			// Opened, the chevron points down and the tooltip says it folds; the fold key is kept.
			luck.toggle();
			assertTrue(luck.chevronOpen());
			assertEquals(SidePanelSection.FOLD_TIP, Tips.plain(luck.headerTip()));
		}
	}
}
