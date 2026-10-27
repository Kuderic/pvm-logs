package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import org.junit.Test;

// Every hitsplat type on a boss lands somewhere, and only the player's own types are the player's.
public class HitsplatKindTest
{
	private static final Set<Integer> MINE = new HashSet<>(Arrays.asList(12, 16, 18, 20, 22, 24, 43, 44, 45, 46, 47, 53, 55));
	private static final Set<Integer> OTHERS = new HashSet<>(Arrays.asList(13, 17, 19, 21, 23, 25, 54));

	private static Hitsplat splat(int type)
	{
		return new Hitsplat()
		{
			@Override
			public int getHitsplatType()
			{
				return type;
			}

			@Override
			public int getAmount()
			{
				return 1;
			}

			@Override
			public int getDisappearsOnGameCycle()
			{
				return 0;
			}
		};
	}

	private static HitsplatKind kindOf(int type)
	{
		final Hitsplat s = splat(type);
		return HitsplatKind.of(s.isMine(), s.isOthers(), type);
	}

	/**
	 * What the brief rests on, checked against RuneLite's own API rather than taken
	 * on trust: isMine() is true for these types and no others, so burn, poison,
	 * venom, bleed and heals never reached the old handler at all.
	 */
	@Test
	public void theApiMarksOnlyThePlayersOwnTypesAsMine()
	{
		for (int type = 0; type <= 200; type++)
		{
			assertEquals("isMine for type " + type, MINE.contains(type), splat(type).isMine());
			assertEquals("isOthers for type " + type, OTHERS.contains(type), splat(type).isOthers());
		}
	}

	/** Block, damage and max hit: the player's attack on hitpoints. */
	@Test
	public void thePlayersRedSplatsAreHitpoints()
	{
		for (int type : new int[]{HitsplatID.BLOCK_ME, HitsplatID.DAMAGE_ME, HitsplatID.DAMAGE_MAX_ME})
		{
			assertEquals(HitsplatKind.PLAYER, kindOf(type));
			assertTrue(kindOf(type).damagesTarget());
		}
	}

	/**
	 * Shield (cyan), armour (orange), charge (yellow), uncharge (white) and poise:
	 * still the player's, but not the target's hitpoints. Every mine type is one or
	 * the other.
	 */
	@Test
	public void thePlayersColouredSplatsAreTheirsButNotHitpoints()
	{
		for (int type : new int[]{18, 20, 22, 24, 44, 45, 46, 47, 53, 55})
		{
			assertEquals("type " + type, HitsplatKind.PLAYER_NOT_HITPOINTS, kindOf(type));
			assertFalse(kindOf(type).damagesTarget());
		}
		for (int type : MINE)
		{
			final HitsplatKind kind = kindOf(type);
			assertTrue(kind == HitsplatKind.PLAYER || kind == HitsplatKind.PLAYER_NOT_HITPOINTS);
		}
		assertEquals("shield", HitsplatKind.typeName(HitsplatID.DAMAGE_ME_CYAN));
		assertEquals("armour", HitsplatKind.typeName(HitsplatID.DAMAGE_MAX_ME_ORANGE));
		assertEquals("poise", HitsplatKind.typeName(HitsplatID.DAMAGE_ME_POISE));
	}

	@Test
	public void damageOverTimeHasNoOwner()
	{
		for (int type : new int[]{HitsplatID.BURN, HitsplatID.POISON, HitsplatID.VENOM, HitsplatID.BLEED})
		{
			assertFalse(splat(type).isMine());
			assertEquals(HitsplatKind.DAMAGE_OVER_TIME, kindOf(type));
			assertTrue(kindOf(type).damagesTarget());
		}
	}

	@Test
	public void aHealIsAHealAndTakesNothingOff()
	{
		assertEquals(HitsplatKind.HEAL, kindOf(HitsplatID.HEAL));
		assertFalse(HitsplatKind.HEAL.damagesTarget());
	}

	@Test
	public void otherPlayersHitsAreTheirs()
	{
		for (int type : new int[]{HitsplatID.BLOCK_OTHER, HitsplatID.DAMAGE_OTHER})
		{
			assertEquals(HitsplatKind.OTHER_PLAYER, kindOf(type));
			assertTrue(kindOf(type).damagesTarget());
		}
		// Their shield, armour, charge, uncharge and poise splats are no more hitpoints than the player's.
		for (int type : new int[]{19, 21, 23, 25, 54})
		{
			assertEquals("type " + type, HitsplatKind.NO_HEALTH_EFFECT, kindOf(type));
		}
	}

	/** Disease drains stats, never hitpoints; charge, poise, prayer and sanity are not hitpoints either. */
	@Test
	public void splatsThatLeaveHitpointsAloneAreNotCounted()
	{
		for (int type : new int[]{HitsplatID.DISEASE, HitsplatID.DISEASE_BLOCKED, HitsplatID.CYAN_UP,
			HitsplatID.CYAN_DOWN, HitsplatID.PRAYER_DRAIN, HitsplatID.SANITY_DRAIN, HitsplatID.SANITY_RESTORE,
			HitsplatID.DAMAGE_OTHER_POISE})
		{
			assertEquals("type " + type, HitsplatKind.NO_HEALTH_EFFECT, kindOf(type));
			assertFalse(kindOf(type).damagesTarget());
		}
	}

	/** A type nobody knows still comes off the target's health, so the count stays honest, but is never the player's. */
	@Test
	public void anUnknownTypeCountsAgainstTheTargetButIsNeverThePlayers()
	{
		for (int type : new int[]{99, HitsplatID.CORRUPTION, HitsplatID.DOOM})
		{
			assertEquals(HitsplatKind.UNCLASSIFIED, kindOf(type));
			assertTrue(kindOf(type).damagesTarget());
		}
		assertTrue(HitsplatKind.PLAYER.damagesTarget());
	}

	@Test
	public void typesAreNamedForTheLog()
	{
		assertEquals("burn", HitsplatKind.typeName(HitsplatID.BURN));
		assertEquals("heal", HitsplatKind.typeName(HitsplatID.HEAL));
		assertEquals("type 99", HitsplatKind.typeName(99));
	}
}
