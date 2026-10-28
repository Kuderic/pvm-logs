package com.pvmlogs;

import java.awt.image.BufferedImage;
import java.util.function.Consumer;
import net.runelite.api.SpriteID;
import net.runelite.api.gameval.ItemID;

/**
 * Where the Damage rows' icons come from (T-036; spec 3.1): an item's image
 * through RuneLite's {@code ItemManager} for a weapon, a spell's sprite through
 * its {@code SpriteManager} for a cast. Behind an interface so tests and renders
 * run without a client; the panel's implementation is in
 * {@link PvmPerformancePanel}. Callbacks arrive on Swing's thread.
 */
interface SidePanelIcons
{
	/** No icons: a painted swatch stands in. */
	SidePanelIcons NONE = new SidePanelIcons()
	{
		@Override
		public void item(int itemId, Consumer<BufferedImage> done)
		{
		}

		@Override
		public void sprite(int spriteId, Consumer<BufferedImage> done)
		{
		}
	};

	/** The thrall row's icon: the Resurrect spells' ghost (1.13.4 has a sprite for the lesser one only). */
	int THRALL_SPRITE = SpriteID.SPELL_RESURRECT_LESSER_GHOST;
	/** The recoil and vengeance row's icon. */
	int RECOIL_ITEM = ItemID.RING_OF_RECOIL;

	void item(int itemId, Consumer<BufferedImage> done);

	void sprite(int spriteId, Consumer<BufferedImage> done);

	/** The spellbook sprite for a spell this plugin knows, or -1. */
	static int spellSprite(Spell spell)
	{
		if (spell == null)
		{
			return -1;
		}
		switch (spell)
		{
			case ICE_BARRAGE:
				return SpriteID.SPELL_ICE_BARRAGE;
			case BLOOD_BARRAGE:
				return SpriteID.SPELL_BLOOD_BARRAGE;
			case SHADOW_BARRAGE:
				return SpriteID.SPELL_SHADOW_BARRAGE;
			case SMOKE_BARRAGE:
				return SpriteID.SPELL_SMOKE_BARRAGE;
			case ICE_BLITZ:
				return SpriteID.SPELL_ICE_BLITZ;
			case BLOOD_BLITZ:
				return SpriteID.SPELL_BLOOD_BLITZ;
			case SHADOW_BLITZ:
				return SpriteID.SPELL_SHADOW_BLITZ;
			case SMOKE_BLITZ:
				return SpriteID.SPELL_SMOKE_BLITZ;
			case ICE_BURST:
				return SpriteID.SPELL_ICE_BURST;
			case BLOOD_BURST:
				return SpriteID.SPELL_BLOOD_BURST;
			case SHADOW_BURST:
				return SpriteID.SPELL_SHADOW_BURST;
			case SMOKE_BURST:
				return SpriteID.SPELL_SMOKE_BURST;
			case ICE_RUSH:
				return SpriteID.SPELL_ICE_RUSH;
			case BLOOD_RUSH:
				return SpriteID.SPELL_BLOOD_RUSH;
			case SHADOW_RUSH:
				return SpriteID.SPELL_SHADOW_RUSH;
			case SMOKE_RUSH:
				return SpriteID.SPELL_SMOKE_RUSH;
			case FIRE_SURGE:
				return SpriteID.SPELL_FIRE_SURGE;
			case EARTH_SURGE:
				return SpriteID.SPELL_EARTH_SURGE;
			case WATER_SURGE:
				return SpriteID.SPELL_WATER_SURGE;
			case WIND_SURGE:
				return SpriteID.SPELL_WIND_SURGE;
			case FIRE_WAVE:
				return SpriteID.SPELL_FIRE_WAVE;
			case EARTH_WAVE:
				return SpriteID.SPELL_EARTH_WAVE;
			case WATER_WAVE:
				return SpriteID.SPELL_WATER_WAVE;
			case WIND_WAVE:
				return SpriteID.SPELL_WIND_WAVE;
			case FIRE_BLAST:
				return SpriteID.SPELL_FIRE_BLAST;
			case EARTH_BLAST:
				return SpriteID.SPELL_EARTH_BLAST;
			case WATER_BLAST:
				return SpriteID.SPELL_WATER_BLAST;
			case WIND_BLAST:
				return SpriteID.SPELL_WIND_BLAST;
			case FIRE_BOLT:
				return SpriteID.SPELL_FIRE_BOLT;
			case EARTH_BOLT:
				return SpriteID.SPELL_EARTH_BOLT;
			case WATER_BOLT:
				return SpriteID.SPELL_WATER_BOLT;
			case WIND_BOLT:
				return SpriteID.SPELL_WIND_BOLT;
			case FIRE_STRIKE:
				return SpriteID.SPELL_FIRE_STRIKE;
			case EARTH_STRIKE:
				return SpriteID.SPELL_EARTH_STRIKE;
			case WATER_STRIKE:
				return SpriteID.SPELL_WATER_STRIKE;
			case WIND_STRIKE:
				return SpriteID.SPELL_WIND_STRIKE;
			case FLAMES_OF_ZAMORAK:
				return SpriteID.SPELL_FLAMES_OF_ZAMORAK;
			case CLAWS_OF_GUTHIX:
				return SpriteID.SPELL_CLAWS_OF_GUTHIX;
			case SARADOMIN_STRIKE:
				return SpriteID.SPELL_SARADOMIN_STRIKE;
			case CRUMBLE_UNDEAD:
				return SpriteID.SPELL_CRUMBLE_UNDEAD;
			case IBAN_BLAST:
				return SpriteID.SPELL_IBAN_BLAST;
			case MAGIC_DART:
				return SpriteID.SPELL_MAGIC_DART;
			case INFERIOR_DEMONBANE:
				return SpriteID.SPELL_INFERIOR_DEMONBANE;
			case SUPERIOR_DEMONBANE:
				return SpriteID.SPELL_SUPERIOR_DEMONBANE;
			case DARK_DEMONBANE:
				return SpriteID.SPELL_DARK_DEMONBANE;
			case GHOSTLY_GRASP:
				return SpriteID.SPELL_GHOSTLY_GRASP;
			case SKELETAL_GRASP:
				return SpriteID.SPELL_SKELETAL_GRASP;
			case UNDEAD_GRASP:
				return SpriteID.SPELL_UNDEAD_GRASP;
			default:
				return -1;
		}
	}
}
