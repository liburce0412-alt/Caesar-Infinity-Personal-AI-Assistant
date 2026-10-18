"""Offline action-boundary experiment; no model, network, ADB, or real app access."""

import json
import math
import sqlite3
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from replay_upstream import run_replay


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
OWNER = "synthetic-user-a"


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def parse_action(text):
    # Parse just one bounded action. Observed page text never enters this parser.
    if not isinstance(text, str) or len(text) > 8192:
        raise ValueError("invalid_action")
    if text.count("<tool_call>") != 1 or text.count("</tool_call>") != 1:
        raise ValueError("invalid_action")
    body = text.split("<tool_call>", 1)[1].split("</tool_call>", 1)[0]
    call = json.loads(body)
    if set(call) != {"name", "arguments"} or not isinstance(call["arguments"], dict):
        raise ValueError("invalid_action")
    if call["name"] not in {"mobile_use", "time.create_record"}:
        raise ValueError("unsupported_tool")
    return call


def pixel_coordinate(point, width=1080, height=2400):
    if not isinstance(point, list) or len(point) != 2:
        raise ValueError("invalid_coordinate")
    if any(type(n) not in (int, float) or not math.isfinite(n) or n < 0 or n > 1000 for n in point):
        raise ValueError("invalid_coordinate")
    return min(width - 1, int(point[0] * width / 1000)), min(height - 1, int(point[1] * height / 1000))


class OfflineEnvironment:
    """Synthetic state with real isolated SQLite persistence, never the Android database."""

    def __init__(self, db_path, case):
        self.db = sqlite3.connect(db_path)
        self.db.execute("CREATE TABLE records(owner TEXT, action_key TEXT, title TEXT, start INTEGER, end INTEGER, UNIQUE(owner, action_key))")
        self.db.execute("CREATE TABLE notes(owner TEXT, text TEXT)")
        self.case = case
        self.screen = "home"
        self.draft = ""
        self.account = OWNER
        self.actions = 0
        self.trace = []

    def observe(self):
        return {
            "screen": self.screen,
            "draft": self.draft,
            "account": self.account,
            "records": self.db.execute("SELECT title FROM records WHERE owner=?", (OWNER,)).fetchall(),
            "notes": self.db.execute("SELECT text FROM notes WHERE owner=?", (OWNER,)).fetchall(),
            "untrusted_page_text": self.case.get("page_text", ""),
        }

    def goal_reached(self):
        observation = self.observe()
        rows = observation["records"] if self.case.get("goal", "record") == "record" else observation["notes"]
        return rows == [("离线学习计划",)]

    def execute(self, call):
        if self.account != OWNER:
            return "account_changed"
        args = call["arguments"]
        if call["name"] == "time.create_record":
            if args not in self.case.get("approved_tools", []):
                return "confirmation_required"
            if set(args) != {"title", "category", "startTime", "endTime"}:
                return "invalid_arguments"
            if not isinstance(args["title"], str) or not 1 <= len(args["title"]) <= 120:
                return "invalid_arguments"
            if type(args["startTime"]) is not int or type(args["endTime"]) is not int or args["endTime"] <= args["startTime"]:
                return "invalid_arguments"
            key = canonical(call)
            existing = self.db.execute("SELECT 1 FROM records WHERE owner=? AND action_key=?", (OWNER, key)).fetchone()
            if existing:
                return "duplicate_ignored"
            self.db.execute("INSERT INTO records VALUES(?,?,?,?,?)", (OWNER, key, args["title"], args["startTime"], args["endTime"]))
            self.db.commit()
            self.actions += 1
            return "record_written"

        action = args.get("action")
        if action in {"terminate", "answer"}:
            return "verified_complete" if self.goal_reached() else "unverified_claim"
        if action == "wait":
            if type(args.get("time", 1)) not in (int, float) or not 0 <= args.get("time", 1) <= 2:
                return "invalid_wait"
            return "wait_observed"
        if action == "click":
            x, y = pixel_coordinate(args.get("coordinate"))
            if self.screen == "home" and 270 <= x <= 810 and 360 <= y <= 840:
                self.screen = "offline_notes"
            elif self.screen == "offline_notes" and 270 <= x <= 810 and 1800 <= y <= 2280:
                # Approval binds the owner, observed target and exact draft, not merely a tap.
                if self.case.get("approved_note") != {"owner": OWNER, "text": self.draft}:
                    return "confirmation_required"
                self.db.execute("INSERT INTO notes VALUES(?,?)", (OWNER, self.draft))
                self.db.commit()
                self.screen = "saved_note"
            else:
                return "target_not_observed"
            self.actions += 1
            return "clicked"
        if action == "type" and self.screen == "offline_notes":
            text = args.get("text")
            if not isinstance(text, str) or len(text) > 120:
                return "invalid_text"
            self.draft = text
            self.actions += 1
            return "typed_draft"
        return "unsupported_action"


def run_case(case, db_path):
    env = OfflineEnvironment(db_path, case)
    status = "incomplete"
    fatal = {"confirmation_required", "account_changed", "unverified_claim", "unsupported_action", "invalid_arguments", "target_not_observed", "invalid_text", "invalid_wait"}
    try:
        for index, output in enumerate(case["outputs"]):
            if index == case.get("stop_before"):
                status = "cancelled"
                break
            if index == case.get("switch_account_before"):
                env.account = "synthetic-user-b"
            if index >= case.get("max_steps", 12):
                status = "step_limit"
                break
            before = env.observe()
            try:
                status = env.execute(parse_action(output))
            except (ValueError, TypeError, KeyError) as error:
                status = str(error) if isinstance(error, ValueError) else "invalid_action"
                env.trace.append({"step": index, "result": status})
                break
            after = env.observe()
            env.trace.append({"step": index, "result": status, "before_screen": before["screen"], "after_screen": after["screen"], "record_count": len(after["records"]), "note_count": len(after["notes"])})
            if status == "verified_complete" or status in fatal:
                break
        actual = env.observe()
        result = {"name": case["name"], "status": status, "actions": env.actions, "records": len(actual["records"]), "notes": len(actual["notes"]), "trace": env.trace}
        for key, expected in case["expected"].items():
            if result[key] != expected:
                raise AssertionError(f"{case['name']}: {key} expected {expected}, got {result[key]}")
        return result
    finally:
        env.db.close()


def main():
    source = (ROOT / "apps/android/app/src/main/java/com/campusai/core/agent/CaesarAppTools.kt").read_text(encoding="utf-8")
    if 'write("time.create_record"' not in source or 'read("time.list_records"' not in source:
        raise AssertionError("Recheck the current app tool names before running the synthetic adapter")
    fixtures = json.loads((HERE / "fixtures.json").read_text(encoding="utf-8"))
    upstream_replay = run_replay(fixtures)
    # Normal inherited workspace permissions also work under the Windows restricted token.
    temp_path = HERE / ("offline-run-" + uuid.uuid4().hex)
    temp_path.mkdir()
    try:
        cases = [run_case(case, temp_path / f"case-{index}.sqlite") for index, case in enumerate(fixtures)]
    finally:
        for database in temp_path.glob("case-*.sqlite"):
            database.unlink()
        temp_path.rmdir()
    report = {
        "run_at_beijing": datetime.now(timezone(timedelta(hours=8))).isoformat(),
        "mode": "offline_fixed_actions_synthetic_state",
        "model_calls": 0,
        "device_calls": 0,
        "network_calls": 0,
        "production_integration": False,
        "temporary_database_removed": not Path(temp_path).exists(),
        "passed": len(cases),
        "official_protocol_replay": upstream_replay,
        "cases": cases,
        "not_tested": ["model visual grounding", "model planning", "Android or external app execution", "actual CaesarAppTools Kotlin execution", "real account or health data", "cloud cost or latency"],
    }
    (HERE / "results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: report[key] for key in ("mode", "passed", "model_calls", "device_calls", "network_calls", "temporary_database_removed")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
