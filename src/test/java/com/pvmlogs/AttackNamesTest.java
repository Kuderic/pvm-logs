package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

/**
 * T-166: Eric's duo Yama (2026-11-04 01:54 log, build 159cd35). The Damage card named the Eye of ayak's specials "Dark
 * Demonbane", and split the Purging staff's Dark Demonbane casts into "Purging staff" and "Dark Demonbane".
 */
public class AttackNamesTest
{
	private int now = 1000;
	private final FightTimeline.Store store = new FightTimeline.Store();

	{
		store.setClock(() -> now);
	}

	@Test
	public void aSpecialScoredFromAnotherRowIsNamedByItsWeapon()
	{
		final Fight fight = new Fight("Yama", 14176, 1, 2500, 0L, null, 0);
		store.attach(fight);
		final FightTimeline line = store.of(fight);
		// Thrown with the Eye of ayak, booked off the Purging staff's row (casting Dark Demonbane, before the switch): the
		// booking named it by the row's spell.
		fight.recordAttackMade(false, 1.0, false);
		line.describeLatest("Dark Demonbane", 31113, Spell.DARK_DEMONBANE, FightTimeline.Style.MAGIC, 1031);
		line.markLatestSpecial("Eye of Ayak");
		assertEquals("Eye of Ayak (special)", line.latest().rowKey());
		// The card's row: "Eye of Ayak", a special.
		final Map<String, SidePanelModel.WeaponRow> rows = new LinkedHashMap<>();
		SidePanelWeaponRows.add(rows, line.latest());
		assertEquals(Collections.singleton("Eye of Ayak (special)"), rows.keySet());
		assertEquals("Eye of Ayak", rows.get("Eye of Ayak (special)").name);
		// With no weapon to go by, the booked name stays.
		now++;
		fight.recordAttackMade(false, 1.0, false);
		line.describeLatest("Dragon claws", 13652, null, FightTimeline.Style.MELEE, 1035);
		line.markLatestSpecial(null);
		assertEquals("Dragon claws (special)", line.latest().rowKey());
	}

	@Test
	public void aCastWithTheStyleOutOfDateIsNamedByItsSpell()
	{
		// [1131]: the Purging staff's row casts Dark Demonbane (max 57). [1134], its booking: the style reads the staff's
		// melee (max 30), so the booking's spell is none.
		final Map<Integer, Spell> byTick = new HashMap<>();
		byTick.put(1131, Spell.DARK_DEMONBANE);
		byTick.put(1134, null);
		assertSame(Spell.DARK_DEMONBANE, CombatCalc.spellAtThrow(byTick, 1131, null));
		assertEquals("Dark Demonbane", CombatCalc.attackName("Purging staff",
			CombatCalc.spellAtThrow(byTick, 1131, null).getDisplayName()));
		// A tick read with no spell (a melee blow, a powered staff's own attack): none, whatever the booking reads.
		assertNull(CombatCalc.spellAtThrow(byTick, 1134, Spell.DARK_DEMONBANE));
		// A tick not read at all: the booking's, as before.
		assertSame(Spell.DARK_DEMONBANE, CombatCalc.spellAtThrow(byTick, 1129, Spell.DARK_DEMONBANE));
		assertNull(CombatCalc.spellAtThrow(null, 1131, null));
	}

	@Test
	public void aSavedSpellNamedSpecialWithOneWeaponShowsTheWeapon()
	{
		assertEquals("Eye of Ayak (special)",
			SpecialAttack.savedKey("Dark Demonbane (special)", Collections.singletonList("Eye of Ayak")));
		// Already the weapon's, an ordinary attack's, or no single weapon to go by: as saved.
		assertEquals("Eye of Ayak (special)",
			SpecialAttack.savedKey("Eye of Ayak (special)", Collections.singletonList("Eye of Ayak")));
		assertEquals("Dark Demonbane", SpecialAttack.savedKey("Dark Demonbane", Collections.singletonList("Eye of Ayak")));
		assertEquals("Dark Demonbane (special)",
			SpecialAttack.savedKey("Dark Demonbane (special)", Arrays.asList("Eye of Ayak", "Dragon claws")));
		assertEquals("Dark Demonbane (special)", SpecialAttack.savedKey("Dark Demonbane (special)", null));
		// Not a spell's name: as saved.
		assertEquals("Purging staff (special)",
			SpecialAttack.savedKey("Purging staff (special)", Collections.singletonList("Eye of Ayak")));
		assertNull(SpecialAttack.savedKey(null, Collections.singletonList("Eye of Ayak")));
	}
}
