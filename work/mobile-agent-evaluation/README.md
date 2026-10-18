# Offline Mobile-Agent action-boundary evaluation

Run from the repository root using the existing Python installation:

```powershell
python -B work/mobile-agent-evaluation/evaluate.py
```

This is a small, reproducible synthetic-state experiment, not Mobile-Agent inference.
It uses fixed `mobile_use`-shaped actions, a minimal simulated notes screen and real
isolated SQLite transactions. It has no ADB, subprocess, network, screenshot, model
or credential integration. No real app data is accessed.

`fixtures.json` describes the 17 scenarios. `results.json` records the latest run,
including actual SQLite readback, denied actions and cancellation. Each run creates
and removes a fresh database directory under this folder; keep the script, fixtures
and result as evidence. `-B` avoids disposable Python bytecode caches.

`replay_upstream.py` also runs the actual `parse_action` / `rescale_coordinates` pure
functions from the official sample pinned to commit
`11cea575561fb7800b5fb6b6cafa56f7a91de11f`, with protocol outputs constrained to names
in the official dispatcher. The source, MIT license and provenance are retained in
`upstream/`. The official file is never imported or run as a program; only the two
reviewed pure AST functions are loaded, without model, ADB or dependency imports.

The local adapter checks that the `time.create_record` / `time.list_records` names
still exist in Caesar's current source. It does not execute the Kotlin tools or
replace their authorization, account isolation, cancellation or idempotency tests.

See [the 3.0 evaluation record](../../docs/caesar-3.0-mobile-agent.md) for official
references, findings and the unverified model/device boundary.
