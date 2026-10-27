package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

// T-026: what the tomes, sunfire runes, the chaos gauntlets, the twinflame staff and Charge each turn on.
public class SpellElementTest
{
	@Test
	public void onlyTheStandardElementalSpellsHaveAnElement()
	{
		assertEquals("fire", Spell.FIRE_SURGE.element());
		assertEquals("water", Spell.WATER_BOLT.element());
		assertEquals("earth", Spell.EARTH_WAVE.element());
		assertEquals("air", Spell.WIND_STRIKE.element());
		// Fire runes are not fire spells (wiki, Tome of Fire).
		assertNull(Spell.IBAN_BLAST.element());
		assertNull(Spell.FLAMES_OF_ZAMORAK.element());
		assertNull(Spell.DARK_DEMONBANE.element());
		assertNull(Spell.SMOKE_BARRAGE.element());
		assertNull(Spell.SARADOMIN_STRIKE.element());
	}

	@Test
	public void boltsForTheChaosGauntlets()
	{
		assertTrue(Spell.FIRE_BOLT.isBolt());
		assertTrue(Spell.WIND_BOLT.isBolt());
		assertFalse(Spell.FIRE_BLAST.isBolt());
		assertFalse(Spell.MAGIC_DART.isBolt());
	}

	/** Bolt, blast and wave, "excluding Strike and Surge" (wiki, Twinflame staff). */
	@Test
	public void twinflameFiresTwiceOnBoltsBlastsAndWaves()
	{
		assertTrue(Spell.FIRE_BOLT.twinflameFiresTwice());
		assertTrue(Spell.EARTH_BLAST.twinflameFiresTwice());
		assertTrue(Spell.WATER_WAVE.twinflameFiresTwice());
		assertFalse(Spell.FIRE_STRIKE.twinflameFiresTwice());
		assertFalse(Spell.FIRE_SURGE.twinflameFiresTwice());
		assertFalse(Spell.IBAN_BLAST.twinflameFiresTwice());
	}

	@Test
	public void demonbaneAndGodSpells()
	{
		assertTrue(Spell.INFERIOR_DEMONBANE.isDemonbane());
		assertTrue(Spell.SUPERIOR_DEMONBANE.isDemonbane());
		assertTrue(Spell.DARK_DEMONBANE.isDemonbane());
		assertFalse(Spell.UNDEAD_GRASP.isDemonbane());
		assertTrue(Spell.SARADOMIN_STRIKE.isGodSpell());
		assertTrue(Spell.CLAWS_OF_GUTHIX.isGodSpell());
		assertTrue(Spell.FLAMES_OF_ZAMORAK.isGodSpell());
		assertFalse(Spell.FIRE_SURGE.isGodSpell());
	}
}
