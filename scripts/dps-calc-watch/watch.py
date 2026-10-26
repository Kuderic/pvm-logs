#!/usr/bin/env python3
"""Watch weirdgloop/osrs-dps-calc for changes our boss rules may need to follow.

The plugin reads monster stats from the calc's cdn/json/monsters.json at runtime, but some bosses' real
stats live in the calc's code instead: scaling rules under src/lib/scaling/, and id lists in
src/lib/constants.ts. We port those by hand into src/main/resources/com/pvmlogs/boss-rules.json, which
records the calc commit it was ported from. This script reports what changed since then, and opens or
updates one GitHub issue about it:

  1. calc commits since the ported one that touch the scaling code or a monster list in constants.ts;
  2. monsters in the latest monsters.json with placeholder stats, or with several versions whose stats
     differ, that neither boss-rules.json nor the checked-in baseline (baseline.json, next to this file)
     already knows about.

Standard library only. Needs git; GitHub calls go through the gh CLI, which reads GH_TOKEN.

  python3 scripts/dps-calc-watch/watch.py --dry-run          print what it would report
  python3 scripts/dps-calc-watch/watch.py --write-baseline   accept today's monsters as known
"""

import argparse
import datetime
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

CALC_REPO = "weirdgloop/osrs-dps-calc"
CALC_URL = "https://github.com/" + CALC_REPO
CALC_BRANCH = "main"
# Used until boss-rules.json exists on master (it lands with T-001); the calc commit the first rules came from.
FALLBACK_COMMIT = "c446f2e1027a5d0f7ced0d961f3e5ee22fecf2e7"

RULES_PATH = "src/main/resources/com/pvmlogs/boss-rules.json"
BASELINE_PATH = "scripts/dps-calc-watch/baseline.json"

SCALING_PATHS = ("src/lib/scaling", "src/lib/scaling.ts", "src/lib/MonsterScaling.ts")
CONSTANTS_PATH = "src/lib/constants.ts"
MONSTERS_PATH = "cdn/json/monsters.json"
# constants.ts is mostly per-monster lists, and they're named every which way: VARDORVIS_IDS, MAGGOT_KING_ID,
# ARAXXOR_PHASES, TFA_DEF_FLOOR_NPCS, GUARANTEED_ACCURACY_MONSTERS, NPC_HARDCODED_MAX_HIT. So a change to any
# declaration counts, except these calc settings. A new setting shows up once; add it here.
IGNORED_CONSTANTS = frozenset({
	"NUMBER_OF_LOADOUTS", "ACCURACY_PRECISION", "DPS_PRECISION", "EXPECTED_HIT_PRECISION", "AUTOCAST_STANCES",
	"CAST_STANCES", "DEFAULT_ATTACK_SPEED", "SECONDS_PER_TICK", "TTK_DIST_MAX_ITER_ROUNDS", "TTK_DIST_EPSILON",
})
DECLARATION = re.compile(r"^(?:export\s+)?(?:const|let|var|enum|function|class|type|interface)\s+([A-Za-z_$][\w$]*)")
HUNK = re.compile(r"^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@")

# What "stats differ" means for two versions of one id: everything that changes a player's damage against it.
# The monster's own attack (max_hit, speed, style) is left out.
STAT_FIELDS = ("size", "skills", "offensive", "defensive", "attributes", "weakness", "immunities")

ISSUE_TITLE = "DPS calc changed: boss rules may need porting"
MARKER = "dps-calc-watch:v1"
MARKER_LINE = re.compile(r"<!-- " + re.escape(MARKER) + r" (\{.*\}) -->")
MAX_ROWS = 100


class WatchError(Exception):
	pass


# --- git -----------------------------------------------------------------------------------------------------

def git(repo, *args, check=True):
	result = subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True, encoding="utf-8")
	if check and result.returncode != 0:
		raise WatchError("git " + " ".join(args) + " failed: " + result.stderr.strip())
	return result.stdout


def clone_calc(dest):
	# Blobless: every commit and tree, but file contents only as they are read. A few seconds.
	result = subprocess.run(["git", "clone", "--quiet", "--filter=blob:none", "--no-checkout", "--single-branch",
		"--branch", CALC_BRANCH, CALC_URL + ".git", str(dest)], capture_output=True, text=True,
		env={**os.environ, "GIT_LFS_SKIP_SMUDGE": "1"})
	if result.returncode != 0:
		raise WatchError("could not clone " + CALC_URL + ": " + result.stderr.strip())


def show(repo, rev, path):
	"""A file's text at a commit, or '' where it doesn't exist."""
	return git(repo, "show", rev + ":" + path, check=False)


# --- what we have ported -------------------------------------------------------------------------------------

def load_rules(root):
	"""(ported calc commit, npc ids the rules cover, where the commit came from)."""
	path = root / RULES_PATH
	if not path.exists():
		return FALLBACK_COMMIT, set(), "fallback (no " + RULES_PATH + " yet)"
	rules = json.loads(path.read_text(encoding="utf-8"))
	commit = (rules.get("portedFrom") or {}).get("commit")
	if not commit:
		raise WatchError(RULES_PATH + " has no portedFrom.commit")
	covered = {int(i) for boss in rules.get("bosses", []) for i in boss.get("npcIds", [])}
	return commit, covered, RULES_PATH


def load_baseline(root):
	path = root / BASELINE_PATH
	if not path.exists():
		return {"placeholder": [], "multiVersion": []}
	return json.loads(path.read_text(encoding="utf-8"))


# --- calc commits --------------------------------------------------------------------------------------------

def scaling_commits(calc, since, head):
	out = git(calc, "log", "--no-merges", "--format=%x1e%H%x1f%cI%x1f%s", "--name-only", since + ".." + head,
		"--", *SCALING_PATHS)
	commits = []
	for record in out.split("\x1e"):
		if not record.strip():
			continue
		header, _, files = record.partition("\n")
		sha, date, subject = header.split("\x1f", 2)
		commits.append({"sha": sha, "date": date, "subject": subject,
			"files": [f for f in files.splitlines() if f.strip()]})
	return commits


def changed_lines(diff):
	"""Line numbers a -U0 diff removes (in the old file) and adds (in the new one)."""
	old, new = set(), set()
	for line in diff.splitlines():
		m = HUNK.match(line)
		if not m:
			continue
		a, b = int(m.group(1)), int(m.group(2) if m.group(2) is not None else 1)
		c, d = int(m.group(3)), int(m.group(4) if m.group(4) is not None else 1)
		old.update(range(a, a + b))
		new.update(range(c, c + d))
	return old, new


def bracket_depth(line):
	"""How far a line opens ([{ minus closes, ignoring strings and // comments."""
	code = re.sub(r"'(?:\\.|[^'\\])*'|\"(?:\\.|[^\"\\])*\"|`(?:\\.|[^`\\])*`", "''", line)
	code = code.split("//", 1)[0]
	return sum(code.count(c) for c in "([{") - sum(code.count(c) for c in ")]}")


def declarations(text):
	"""(first line, last line, name) of each top-level declaration, in order. A declaration runs from its
	line until its brackets close, so blank lines and comments between two of them belong to neither."""
	found = []
	lines = text.splitlines()
	number = 0
	while number < len(lines):
		m = DECLARATION.match(lines[number])
		if not m:
			number += 1
			continue
		start, depth = number, bracket_depth(lines[number])
		while depth > 0 and number + 1 < len(lines):
			number += 1
			depth += bracket_depth(lines[number])
		found.append((start + 1, number + 1, m.group(1)))
		number += 1
	return found


def declaration_at(index, line):
	"""The declaration a line sits in, or None for a line outside every declaration."""
	for start, end, name in index:
		if start <= line <= end:
			return name
	return None


def touched_lists(old_text, new_text, diff):
	"""Names of the lists a constants.ts diff changes, whatever line of a multi-line list it touches."""
	old, new = changed_lines(diff)
	old_index, new_index = declarations(old_text), declarations(new_text)
	names = {declaration_at(old_index, n) for n in old} | {declaration_at(new_index, n) for n in new}
	return sorted(n for n in names if n and n not in IGNORED_CONSTANTS)


def id_list_commits(calc, since, head):
	out = git(calc, "log", "--no-merges", "--format=%H%x1f%cI%x1f%s", since + ".." + head, "--", CONSTANTS_PATH)
	commits = []
	for line in out.splitlines():
		if not line.strip():
			continue
		sha, date, subject = line.split("\x1f", 2)
		diff = git(calc, "show", "--no-color", "--format=", "-U0", sha, "--", CONSTANTS_PATH)
		lists = touched_lists(show(calc, sha + "^", CONSTANTS_PATH), show(calc, sha, CONSTANTS_PATH), diff)
		if lists:
			commits.append({"sha": sha, "date": date, "subject": subject, "lists": lists})
	return commits


# --- monsters.json -------------------------------------------------------------------------------------------

def is_placeholder(m):
	s = m.get("skills") or {}
	return s.get("def") == 0 and s.get("str") == 0 and s.get("atk", 0) > 0 and s.get("hp", 0) >= 200


def placeholders(monsters):
	found = {}
	for m in monsters:
		if is_placeholder(m):
			found["%d/%s" % (m["id"], m.get("version", ""))] = {
				"id": m["id"], "name": m["name"], "version": m.get("version", ""),
				"hp": m["skills"]["hp"], "atk": m["skills"]["atk"]}
	return found


def stat_signature(m):
	return json.dumps({k: m.get(k) for k in STAT_FIELDS}, sort_keys=True)


def multi_versions(monsters):
	"""Ids with more than one entry whose stats differ, with their version names."""
	by_id = {}
	for m in monsters:
		by_id.setdefault(m["id"], []).append(m)
	found = {}
	for npc_id, entries in by_id.items():
		if len(entries) > 1 and len({stat_signature(m) for m in entries}) > 1:
			found[npc_id] = {"id": npc_id, "name": entries[0]["name"],
				"versions": sorted({m.get("version", "") for m in entries})}
	return found


def new_monsters(current_placeholders, current_versions, baseline, covered):
	"""What the baseline and the rules don't already know about."""
	known_placeholder = {"%d/%s" % (p["id"], p["version"]) for p in baseline.get("placeholder", [])}
	known_versions = {v["id"]: set(v["versions"]) for v in baseline.get("multiVersion", [])}

	placeholder = [p for key, p in sorted(current_placeholders.items())
		if key not in known_placeholder and p["id"] not in covered]
	versions = []
	for npc_id, entry in sorted(current_versions.items()):
		if npc_id in covered:
			continue
		known = known_versions.get(npc_id)
		fresh = [v for v in entry["versions"] if known is None or v not in known]
		if fresh:
			versions.append({**entry, "new": fresh, "knownId": known is not None})
	return placeholder, versions


# --- the report ----------------------------------------------------------------------------------------------

def report_keys(report):
	keys = ["scaling:" + c["sha"] for c in report["scaling"]]
	keys += ["ids:" + c["sha"] for c in report["idLists"]]
	keys += ["placeholder:%d/%s" % (p["id"], p["version"]) for p in report["placeholder"]]
	keys += ["versions:%d/%s" % (v["id"], name) for v in report["versions"] for name in v["new"]]
	return sorted(keys)


def safe(text):
	"""Commit subjects and monster names go into our issue: no @mentions, no #refs that link or notify."""
	text = text.replace("`", "'").replace("|", "\\|")
	return re.sub(r"(?<![\w`])([@#][\w/.-]+|GH-\d+)", r"`\1`", text)


def short(sha):
	return sha[:7]


def commit_link(sha):
	return "[`%s`](%s/commit/%s)" % (short(sha), CALC_URL, sha)


def npc_link(npc_id):
	return "[%d](https://oldschool.runescape.wiki/w/Special:Lookup?type=npc&id=%d)" % (npc_id, npc_id)


def capped(rows):
	if len(rows) <= MAX_ROWS:
		return rows
	return rows[:MAX_ROWS] + ["| ... | %d more not shown | | |" % (len(rows) - MAX_ROWS)]


def render(report):
	r = report
	# The keys ride along in an HTML comment so the next run can tell what's new; ">" is escaped so no version
	# name can end the comment early.
	marker = json.dumps({"head": r["head"], "keys": report_keys(r)}, separators=(",", ":")).replace(">", "\\u003e")
	lines = ["<!-- %s %s -->" % (MARKER, marker),
		"The weekly DPS calculator check found changes that `boss-rules.json` may need to follow.",
		"",
		"- Calc checked at %s (%s)." % (commit_link(r["head"]), r["headDate"][:10]),
		"- Rules ported from %s, read from %s.%s" % (commit_link(r["ported"]), r["portedSource"],
			" [Everything since](%s/compare/%s...%s)." % (CALC_URL, r["ported"], r["head"]) if r["ported"] != r["head"]
			else ""),
		""]
	if r["scaling"]:
		lines += ["### Scaling code", "", "| Commit | Date | Files | Subject |", "|---|---|---|---|"]
		lines += capped(["| %s | %s | %s | %s |" % (commit_link(c["sha"]), c["date"][:10],
			"<br>".join("[`%s`](%s/blob/%s/%s)" % (f, CALC_URL, c["sha"], f) for f in c["files"]), safe(c["subject"]))
			for c in r["scaling"]])
		lines.append("")
	if r["idLists"]:
		lines += ["### Monster lists in `%s`" % CONSTANTS_PATH, "", "| Commit | Date | Lists | Subject |",
			"|---|---|---|---|"]
		lines += capped(["| %s | %s | %s | %s |" % (commit_link(c["sha"]), c["date"][:10],
			", ".join("`%s`" % n for n in c["lists"]), safe(c["subject"])) for c in r["idLists"]])
		lines.append("")
	if r["placeholder"]:
		lines += ["### Placeholder stats (def 0, str 0, atk > 0, hp ≥ 200)", "",
			"Their real stats are probably in the calc's code. Until a rule exists, expected damage against them is wrong.",
			"", "| Id | Name | Version | HP | Attack |", "|---|---|---|---|---|"]
		lines += capped(["| %s | %s | %s | %d | %d |" % (npc_link(p["id"]), safe(p["name"]), safe(p["version"]) or "-",
			p["hp"], p["atk"]) for p in r["placeholder"]])
		lines.append("")
	if r["versions"]:
		lines += ["### Several versions with different stats", "",
			"One id, several stat blocks: the plugin has to pick the right one in-game.",
			"", "| Id | Name | Versions | New |", "|---|---|---|---|"]
		lines += capped(["| %s | %s | %s | %s |" % (npc_link(v["id"]), safe(v["name"]),
			"; ".join(safe(n) or "(blank)" for n in v["versions"]),
			"versions " + "; ".join(safe(n) or "(blank)" for n in v["new"]) if v["knownId"] else "new id")
			for v in r["versions"]])
		lines.append("")
	lines += ["### Clearing this", "",
		"- Port what matters into `%s` and set `portedFrom.commit` to the calc commit you reviewed up to, even for "
		"commits that needed nothing." % RULES_PATH,
		"- For monsters that need no rule, run `python3 %s --write-baseline` and commit `%s`." % (
			"scripts/dps-calc-watch/watch.py", BASELINE_PATH),
		"- Close this issue once that's merged. The next run opens a new one only for things found after it.",
		"",
		"_Opened by `.github/workflows/dps-calc-watch.yml`. It edits this issue instead of opening another, and "
		"comments when something new turns up._"]
	return "\n".join(lines) + "\n"


def previous_keys(body):
	m = MARKER_LINE.search(body or "")
	if not m:
		return set()
	try:
		return set(json.loads(m.group(1)).get("keys", []))
	except ValueError:
		return set()


def describe_key(key):
	kind, _, rest = key.partition(":")
	if kind in ("scaling", "ids"):
		return "%s commit %s" % ("Scaling" if kind == "scaling" else "Monster list", commit_link(rest))
	npc_id, _, version = rest.partition("/")
	what = "Placeholder stats" if kind == "placeholder" else "Version"
	return "%s: %s %s" % (what, npc_link(int(npc_id)), "`%s`" % safe(version) if version else "(blank)")


# --- GitHub --------------------------------------------------------------------------------------------------

class GitHub:
	"""The four issue calls this needs, through gh."""

	def __init__(self, repo):
		self.repo = repo

	def _api(self, *args, payload=None):
		result = subprocess.run(["gh", "api", *args] + (["--input", "-"] if payload is not None else []),
			input=json.dumps(payload) if payload is not None else None, capture_output=True, text=True)
		if result.returncode != 0:
			message = (result.stderr or result.stdout).strip()
			if "Issues has been disabled" in message:
				raise WatchError("Issues are turned off for %s (GitHub turns them off on forks). Turn them on under "
					"Settings > General > Features > Issues, then run this again." % self.repo)
			raise WatchError("gh api " + " ".join(args) + " failed: " + message)
		return result.stdout

	def open_issues(self):
		out = self._api("--paginate", "repos/%s/issues?state=open&per_page=100" % self.repo,
			"--jq", ".[] | select(.pull_request == null) | {number, title, body}")
		return [json.loads(line) for line in out.splitlines() if line.strip()]

	def create_issue(self, title, body):
		return json.loads(self._api("repos/%s/issues" % self.repo, "-X", "POST",
			payload={"title": title, "body": body}))["number"]

	def edit_issue(self, number, body):
		self._api("repos/%s/issues/%d" % (self.repo, number), "-X", "PATCH", payload={"body": body})

	def comment(self, number, body):
		self._api("repos/%s/issues/%d/comments" % (self.repo, number), "-X", "POST", payload={"body": body})


def upsert_issue(github, report, log=print):
	"""Open the one issue, or bring the open one up to date. Returns (action, issue number)."""
	body = render(report)
	mine = sorted((i for i in github.open_issues() if i["title"] == ISSUE_TITLE), key=lambda i: i["number"])
	if not mine:
		number = github.create_issue(ISSUE_TITLE, body)
		log("Opened issue #%d." % number)
		return "opened", number
	issue = mine[0]
	if len(mine) > 1:
		log("Several open issues are titled %r; updating the oldest, #%d." % (ISSUE_TITLE, issue["number"]))
	added = sorted(set(report_keys(report)) - previous_keys(issue.get("body")))
	if issue.get("body") == body:
		log("Issue #%d is already up to date." % issue["number"])
		return "unchanged", issue["number"]
	github.edit_issue(issue["number"], body)
	if added:
		github.comment(issue["number"], "Found since the last report:\n\n" + "\n".join("- " + describe_key(k)
			for k in added[:MAX_ROWS]) + ("\n- ... and %d more" % (len(added) - MAX_ROWS) if len(added) > MAX_ROWS else "")
			+ "\n")
		log("Updated issue #%d with %d new item(s)." % (issue["number"], len(added)))
		return "updated", issue["number"]
	log("Refreshed issue #%d; nothing new in it." % issue["number"])
	return "refreshed", issue["number"]


# --- main ----------------------------------------------------------------------------------------------------

def scan(root, calc, ignore_baseline=False):
	ported, covered, source = load_rules(root)
	head = git(calc, "rev-parse", "origin/" + CALC_BRANCH).strip()
	if subprocess.run(["git", "-C", str(calc), "cat-file", "-e", ported + "^{commit}"], capture_output=True).returncode:
		raise WatchError("ported commit %s (from %s) is not in %s" % (ported, source, CALC_REPO))
	if subprocess.run(["git", "-C", str(calc), "merge-base", "--is-ancestor", ported, head],
			capture_output=True).returncode:
		raise WatchError("ported commit %s is not on %s's %s branch" % (ported, CALC_REPO, CALC_BRANCH))

	monsters = json.loads(show(calc, head, MONSTERS_PATH))
	current_placeholders, current_versions = placeholders(monsters), multi_versions(monsters)
	baseline = {"placeholder": [], "multiVersion": []} if ignore_baseline else load_baseline(root)
	placeholder, versions = new_monsters(current_placeholders, current_versions, baseline, covered)
	return {
		"head": head,
		"headDate": git(calc, "show", "-s", "--format=%cI", head).strip(),
		"ported": git(calc, "rev-parse", ported).strip(),
		"portedSource": "`%s`" % RULES_PATH if source == RULES_PATH else "the fallback in `watch.py` (no rules file yet)",
		"scaling": scaling_commits(calc, ported, head),
		"idLists": id_list_commits(calc, ported, head),
		"placeholder": placeholder,
		"versions": versions,
		"monsterCount": len(monsters),
		"currentPlaceholders": current_placeholders,
		"currentVersions": current_versions,
	}


def write_baseline(root, report):
	baseline = {
		"about": "Monsters the DPS calc watcher already knows about, so it reports only new ones. "
			"Regenerate with: python3 scripts/dps-calc-watch/watch.py --write-baseline",
		"calcCommit": report["head"],
		"calcDate": report["headDate"][:10],
		"statFields": list(STAT_FIELDS),
		"placeholder": [report["currentPlaceholders"][k] for k in sorted(report["currentPlaceholders"])],
		"multiVersion": [report["currentVersions"][k] for k in sorted(report["currentVersions"])],
	}
	path = root / BASELINE_PATH
	path.write_text(dump_baseline(baseline), encoding="utf-8")
	return path, baseline


def dump_baseline(baseline):
	"""JSON with one monster per line, so accepting new ones is a readable diff."""
	out = ["{"]
	for key, value in baseline.items():
		if not isinstance(value, list) or not value or not isinstance(value[0], dict):
			out.append(" %s: %s," % (json.dumps(key), json.dumps(value, ensure_ascii=False)))
			continue
		out.append(" %s: [" % json.dumps(key))
		out += ["  " + json.dumps(item, ensure_ascii=False) + ("," if i < len(value) - 1 else "")
			for i, item in enumerate(value)]
		out.append(" ],")
	out[-1] = out[-1].rstrip(",")
	return "\n".join(out) + "\n}\n"


def summary_text(r):
	return ("Calc %s (%s), rules ported from %s: %d monsters; %d scaling commit(s), %d monster-list commit(s), "
		"%d new placeholder entr(ies), %d new multi-version id(s)." % (short(r["head"]), r["headDate"][:10],
		short(r["ported"]), r["monsterCount"], len(r["scaling"]), len(r["idLists"]), len(r["placeholder"]),
		len(r["versions"])))


def main(argv=None):
	parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
	parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY"),
		help="owner/name the issue goes in (default: $GITHUB_REPOSITORY)")
	parser.add_argument("--calc-dir", type=Path, help="an existing clone of the calc to read instead of cloning")
	parser.add_argument("--ignore-baseline", action="store_true",
		help="treat every placeholder and multi-version monster as new (tests the issue path)")
	parser.add_argument("--dry-run", action="store_true", help="print the report; don't touch GitHub")
	parser.add_argument("--write-baseline", action="store_true", help="record today's monsters as known, then stop")
	parser.add_argument("--summary", type=Path, default=os.environ.get("GITHUB_STEP_SUMMARY"),
		help="also append the report here (default: $GITHUB_STEP_SUMMARY)")
	args = parser.parse_args(argv)
	root = Path(__file__).resolve().parents[2]

	try:
		with tempfile.TemporaryDirectory() as tmp:
			calc = args.calc_dir
			if calc is None:
				calc = Path(tmp) / "osrs-dps-calc"
				clone_calc(calc)
			report = scan(root, calc, ignore_baseline=args.ignore_baseline)

		if args.write_baseline:
			path, baseline = write_baseline(root, report)
			print("Wrote %s: %d placeholder entries, %d multi-version ids, calc %s." % (path, len(baseline["placeholder"]),
				len(baseline["multiVersion"]), short(report["head"])))
			return 0

		print(summary_text(report))
		found = bool(report_keys(report))
		body = render(report) if found else None
		if args.summary:
			with open(args.summary, "a", encoding="utf-8") as f:
				f.write("## DPS calc watch\n\n" + summary_text(report) + "\n\n" + (body or "Nothing new.\n"))
		if not found:
			print("Nothing new.")
			return 0
		if args.dry_run:
			print("\n--- issue body (dry run) ---\n" + body)
			return 0
		if not args.repo:
			raise WatchError("--repo or $GITHUB_REPOSITORY is needed to open the issue")
		upsert_issue(GitHub(args.repo), report)
		return 0
	except WatchError as e:
		print("error: " + str(e), file=sys.stderr)
		return 1


if __name__ == "__main__":
	sys.exit(main())
