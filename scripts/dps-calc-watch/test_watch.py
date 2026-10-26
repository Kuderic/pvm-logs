#!/usr/bin/env python3
"""Tests for watch.py. Standard library only: python3 scripts/dps-calc-watch/test_watch.py"""

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import watch  # noqa: E402

GIT_ENV = {**os.environ, "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@t", "GIT_COMMITTER_NAME": "t",
	"GIT_COMMITTER_EMAIL": "t@t", "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_SYSTEM": os.devnull}

CONSTANTS = """import { X } from './x';

export const BLOWPIPE_IDS: number[] = [
  12926,
];

export const NUMBER_OF_LOADOUTS = 6;

export const VARDORVIS_IDS = [
  12223, 12224,
  12228,
];

export const ARAXXOR_PHASES = ['Standard', 'Enraged'];
"""


def monster(npc_id, name, version="", hp=100, atk=50, strength=50, defence=50, stab=0):
	return {"id": npc_id, "name": name, "version": version, "size": 1, "max_hit": "1", "speed": 4,
		"skills": {"atk": atk, "def": defence, "hp": hp, "magic": 1, "ranged": 1, "str": strength},
		"offensive": {"atk": 0, "magic": 0, "magic_str": 0, "ranged": 0, "ranged_str": 0, "str": 0},
		"defensive": {"flat_armour": 0, "crush": 0, "magic": 0, "heavy": 0, "standard": 0, "light": 0, "slash": 0,
			"stab": stab},
		"attributes": [], "immunities": {"burn": None}, "weakness": None}


MONSTERS = [
	monster(1, "Goblin"),
	monster(12223, "Vardorvis", "Post-quest", hp=700, strength=0, defence=0, atk=280),
	monster(12223, "Vardorvis", "Awakened", hp=1400, strength=0, defence=0, atk=400),
	monster(12214, "The Leviathan", "Post-quest", hp=900, defence=250),
	monster(12214, "The Leviathan", "Awakened", hp=2700, defence=287),
	# Same stats under two names: not reported.
	monster(13012, "Eclipse Moon", "Clone", hp=500),
	monster(13012, "Eclipse Moon", "Regular", hp=500),
	# Same hp, different defence bonus: reported.
	monster(16659, "Archive Guardian", "Flying", hp=550, stab=120),
	monster(16659, "Archive Guardian", "Grounded", hp=550, stab=115),
]


def git(repo, *args):
	return subprocess.run(["git", "-C", str(repo), *args], check=True, capture_output=True, text=True,
		env=GIT_ENV).stdout.strip()


def commit(repo, files, message):
	for path, text in files.items():
		target = Path(repo) / path
		target.parent.mkdir(parents=True, exist_ok=True)
		target.write_text(text if isinstance(text, str) else json.dumps(text), encoding="utf-8")
		git(repo, "add", path)
	git(repo, "commit", "-q", "-m", message)
	return git(repo, "rev-parse", "HEAD")


class FakeCalc:
	"""A calc repo with one ported commit and then a scaling change, two id-list edits and an unrelated one."""

	def __init__(self, tmp):
		self.src = Path(tmp) / "calc-src"
		self.src.mkdir()
		git(self.src, "init", "-q", "-b", "main")
		self.ported = commit(self.src, {"src/lib/constants.ts": CONSTANTS, "src/lib/scaling/Vardorvis.ts": "a\n",
			"cdn/json/monsters.json": MONSTERS}, "Start")
		self.scaling = commit(self.src, {"src/lib/scaling/Vardorvis.ts": "b\n"}, "Tweak Vardorvis (#12) thanks @someone")
		# Inside a multi-line list: the changed line itself has no _IDS on it.
		self.inside = commit(self.src, {"src/lib/constants.ts": CONSTANTS.replace("  12228,\n", "  12228, 13656,\n")},
			"More Vardorvis ids")
		self.unrelated = commit(self.src, {"src/lib/constants.ts":
			CONSTANTS.replace("12228,\n", "12228, 13656,\n").replace("= 6", "= 7")}, "More loadouts")
		self.added = commit(self.src, {"src/lib/constants.ts": CONSTANTS.replace("12228,\n", "12228, 13656,\n")
			.replace("= 6", "= 7") + "\n// The new delve boss\nexport const DOOM_IDS = [14707];\n"}, "Add Doom")
		self.clone = Path(tmp) / "calc"
		subprocess.run(["git", "clone", "-q", str(self.src), str(self.clone)], check=True, env=GIT_ENV)


class FakeGitHub:
	def __init__(self, issues=None):
		self.issues = issues or []
		self.comments = []
		self.next = 100

	def open_issues(self):
		return [i for i in self.issues if i["state"] == "open"]

	def create_issue(self, title, body):
		self.next += 1
		self.issues.append({"number": self.next, "title": title, "body": body, "state": "open"})
		return self.next

	def edit_issue(self, number, body):
		next(i for i in self.issues if i["number"] == number)["body"] = body

	def comment(self, number, body):
		self.comments.append((number, body))


def report(**overrides):
	r = {"head": "b" * 40, "headDate": "2026-11-02T10:00:00+00:00", "ported": "a" * 40, "portedSource": "x",
		"scaling": [], "idLists": [], "placeholder": [], "versions": []}
	r.update(overrides)
	return r


class DiffParsing(unittest.TestCase):
	def test_hunk_headers(self):
		diff = "@@ -3 +3 @@\n-a\n+b\n@@ -10,0 +11,2 @@\n+c\n+d\n@@ -20,2 +22,0 @@\n-e\n-f\n"
		old, new = watch.changed_lines(diff)
		self.assertEqual(old, {3, 20, 21})
		self.assertEqual(new, {3, 11, 12})

	def test_a_line_inside_a_multiline_list_belongs_to_that_list(self):
		index = watch.declarations(CONSTANTS)
		line = CONSTANTS.splitlines().index("  12228,") + 1
		self.assertEqual(watch.declaration_at(index, line), "VARDORVIS_IDS")
		self.assertIsNone(watch.declaration_at(index, 1))

	def test_any_list_counts_but_calc_settings(self):
		old = "export const NUMBER_OF_LOADOUTS = 6;\nexport const TFA_DEF_FLOOR_NPCS = [\n  1,\n];\n"
		new = "export const NUMBER_OF_LOADOUTS = 7;\nexport const TFA_DEF_FLOOR_NPCS = [\n  1, 2,\n];\n"
		diff = "@@ -1 +1 @@\n-a\n+b\n@@ -3 +3 @@\n-c\n+d\n"
		self.assertEqual(watch.touched_lists(old, new, diff), ["TFA_DEF_FLOOR_NPCS"])


class Monsters(unittest.TestCase):
	def test_placeholder_shape(self):
		found = watch.placeholders(MONSTERS)
		self.assertEqual(sorted(found), ["12223/Awakened", "12223/Post-quest"])

	def test_versions_count_only_when_stats_differ(self):
		self.assertEqual(sorted(watch.multi_versions(MONSTERS)), [12214, 12223, 16659])

	def test_baseline_and_rules_hide_what_is_known(self):
		ph, mv = watch.placeholders(MONSTERS), watch.multi_versions(MONSTERS)
		baseline = {"placeholder": [{"id": 12223, "version": "Post-quest"}],
			"multiVersion": [{"id": 12214, "versions": ["Post-quest"]}]}
		placeholder, versions = watch.new_monsters(ph, mv, baseline, covered=set())
		self.assertEqual([p["version"] for p in placeholder], ["Awakened"])
		self.assertEqual([(v["id"], v["new"], v["knownId"]) for v in versions],
			[(12214, ["Awakened"], True), (12223, ["Awakened", "Post-quest"], False),
				(16659, ["Flying", "Grounded"], False)])
		placeholder, versions = watch.new_monsters(ph, mv, baseline, covered={12223})
		self.assertEqual(placeholder, [])
		self.assertEqual([v["id"] for v in versions], [12214, 16659])


class Scan(unittest.TestCase):
	def setUp(self):
		self.tmp = tempfile.TemporaryDirectory()
		self.calc = FakeCalc(self.tmp.name)
		self.root = Path(self.tmp.name) / "repo"
		(self.root / "scripts/dps-calc-watch").mkdir(parents=True)

	def tearDown(self):
		self.tmp.cleanup()

	def rules(self, commit, ids=()):
		path = self.root / watch.RULES_PATH
		path.parent.mkdir(parents=True, exist_ok=True)
		path.write_text(json.dumps({"schemaVersion": 1, "portedFrom": {"commit": commit},
			"bosses": [{"name": "Vardorvis", "npcIds": list(ids)}]}))

	def test_commits_since_the_ported_one(self):
		self.rules(self.calc.ported)
		r = watch.scan(self.root, self.calc.clone)
		self.assertEqual([c["sha"] for c in r["scaling"]], [self.calc.scaling])
		self.assertEqual(r["scaling"][0]["files"], ["src/lib/scaling/Vardorvis.ts"])
		self.assertEqual([(c["sha"], c["lists"]) for c in r["idLists"]],
			[(self.calc.added, ["DOOM_IDS"]), (self.calc.inside, ["VARDORVIS_IDS"])])

	def test_nothing_new_once_ported_up_to_head_and_baselined(self):
		self.rules(self.calc.added, ids=[12223])
		path, _ = watch.write_baseline(self.root, watch.scan(self.root, self.calc.clone))
		r = watch.scan(self.root, self.calc.clone)
		self.assertEqual(watch.report_keys(r), [])
		self.assertTrue(path.exists())

	def test_a_stale_baseline_reports_the_monsters(self):
		self.rules(self.calc.added, ids=[12223])
		r = watch.scan(self.root, self.calc.clone, ignore_baseline=True)
		self.assertEqual(watch.report_keys(r), ["versions:12214/Awakened", "versions:12214/Post-quest",
			"versions:16659/Flying", "versions:16659/Grounded"])

	def test_fallback_commit_without_a_rules_file(self):
		commit, covered, source = watch.load_rules(self.root)
		self.assertEqual((commit, covered), (watch.FALLBACK_COMMIT, set()))
		self.assertIn("fallback", source)

	def test_unknown_ported_commit_is_an_error(self):
		self.rules("0" * 40)
		with self.assertRaises(watch.WatchError):
			watch.scan(self.root, self.calc.clone)


class Rendering(unittest.TestCase):
	def test_no_mentions_or_cross_references(self):
		self.assertEqual(watch.safe("Fix (#984) thanks @someone, see GH-12 | x"),
			"Fix (`#984`) thanks `@someone`, see `GH-12` \\| x")
		self.assertEqual(watch.safe("mail a@b.com"), "mail a@b.com")

	def test_keys_round_trip_through_the_body(self):
		r = report(placeholder=[{"id": 1, "name": "A", "version": "x->y", "hp": 300, "atk": 5}])
		body = watch.render(r)
		self.assertNotIn("x->y -->", body.splitlines()[0])
		self.assertEqual(watch.previous_keys(body), {"placeholder:1/x->y"})


class Issue(unittest.TestCase):
	def test_opens_one_then_never_duplicates(self):
		gh = FakeGitHub([{"number": 5, "title": watch.ISSUE_TITLE, "body": "old", "state": "closed"},
			{"number": 6, "title": "something else", "body": "", "state": "open"}])
		r = report(versions=[{"id": 2, "name": "B", "versions": ["p", "q"], "new": ["p", "q"], "knownId": False}])
		self.assertEqual(watch.upsert_issue(gh, r, log=lambda *_: None), ("opened", 101))
		self.assertEqual(watch.upsert_issue(gh, r, log=lambda *_: None), ("unchanged", 101))
		self.assertEqual(len([i for i in gh.issues if i["title"] == watch.ISSUE_TITLE and i["state"] == "open"]), 1)
		self.assertEqual(gh.comments, [])

	def test_new_findings_edit_the_issue_and_comment_only_the_additions(self):
		gh = FakeGitHub()
		first = report(placeholder=[{"id": 1, "name": "A", "version": "", "hp": 300, "atk": 5}])
		watch.upsert_issue(gh, first, log=lambda *_: None)
		more = report(head="c" * 40, placeholder=first["placeholder"],
			scaling=[{"sha": "d" * 40, "date": "2026-11-03", "subject": "s", "files": ["src/lib/scaling/X.ts"]}])
		self.assertEqual(watch.upsert_issue(gh, more, log=lambda *_: None), ("updated", 101))
		self.assertEqual(len(gh.issues), 1)
		self.assertEqual(len(gh.comments), 1)
		self.assertIn("dddddd", gh.comments[0][1])
		self.assertNotIn("Placeholder", gh.comments[0][1])
		# A newer calc head with nothing new refreshes the body quietly.
		quiet = dict(more, head="e" * 40)
		self.assertEqual(watch.upsert_issue(gh, quiet, log=lambda *_: None), ("refreshed", 101))
		self.assertEqual(len(gh.comments), 1)


if __name__ == "__main__":
	unittest.main(verbosity=1)
