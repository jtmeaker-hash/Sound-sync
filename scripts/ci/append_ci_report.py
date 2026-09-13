#!/usr/bin/env python3
"""Append a readable CI result to docs/BUILD_DIAGNOSTICS_LOG.md and create ci-output/REPORT.md."""
from __future__ import annotations
import argparse
from pathlib import Path
import re
import subprocess
from datetime import datetime, timezone

def cmd(args):
    p = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    return p.stdout.strip()

def extract_section(message, heading):
    pat = rf"(?ims)^{re.escape(heading)}:\s*(.*?)(?=^[A-Za-z][A-Za-z ]*:\s*|\Z)"
    m = re.search(pat, message or "")
    return m.group(1).strip() if m else ""

def failure_excerpt(path):
    p = Path(path)
    if not p.exists():
        return "Log file was not produced."
    lines = p.read_text(encoding="utf-8", errors="replace").splitlines()
    hits = []
    rx = re.compile(r"(FAILURE|FAILED|error[: ]|exception|what went wrong|could not|compilation failed|execution failed)", re.I)
    for line in lines:
        if rx.search(line):
            s = line.strip()
            if s and s not in hits:
                hits.append(s)
        if len(hits) >= 10:
            break
    if not hits:
        hits = [x.strip() for x in lines[-12:] if x.strip()]
    return "\n".join(f"- `{x[:350]}`" for x in hits[:10]) or "- Cause could not be extracted; open the full workflow run."

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--history", required=True)
    ap.add_argument("--report", required=True)
    ap.add_argument("--run-url", required=True)
    ap.add_argument("--run-id", required=True)
    ap.add_argument("--repo", required=True)
    ap.add_argument("--sha", required=True)
    ap.add_argument("--ref", required=True)
    ap.add_argument("--actor", required=True)
    ap.add_argument("--event", required=True)
    ap.add_argument("--unit-status", required=True)
    ap.add_argument("--debug-status", required=True)
    ap.add_argument("--release-status", required=True)
    ap.add_argument("--unit-log", required=True)
    ap.add_argument("--debug-log", required=True)
    ap.add_argument("--release-log", required=True)
    args = ap.parse_args()

    message = cmd(["git","show","-s","--format=%B",args.sha])
    subject = cmd(["git","show","-s","--format=%s",args.sha])
    author = cmd(["git","show","-s","--format=%an <%ae>",args.sha])
    changed = cmd(["git","show","--stat","--oneline","--no-renames",args.sha])
    changes = extract_section(message, "Changes") or subject or "No explicit Changes: section in commit message."
    fixes = extract_section(message, "Fixes") or "No explicit Fixes: section in commit message."
    declared_issues = extract_section(message, "Issues") or "No explicit Issues: section in commit message."

    statuses = {
        "Unit tests": (args.unit_status, args.unit_log),
        "Debug APK": (args.debug_status, args.debug_log),
        "Release APK": (args.release_status, args.release_log),
    }
    failed = {k:v for k,v in statuses.items() if str(v[0]) not in ("0", "SKIPPED", "N/A")}
    overall = "PASS" if not failed else "FAIL"

    issue_lines = [declared_issues]
    if failed:
        issue_lines.append("\n### CI failures")
        for name, (status, logfile) in failed.items():
            issue_lines.append(f"\n**{name} failed (exit {status})**\n{failure_excerpt(logfile)}")
    else:
        issue_lines.append("\nNo CI build/test failures detected in this run.")

    entry = f"""
## CI Run {args.run_id} — {overall}

- **Date:** {datetime.now(timezone.utc).isoformat()}
- **Repository:** `{args.repo}`
- **Branch/ref:** `{args.ref}`
- **Commit:** [`{args.sha[:10]}`]({args.run_url.rsplit('/actions/runs/',1)[0]}/commit/{args.sha})
- **Author:** {author}
- **Actor:** `{args.actor}`
- **Event:** `{args.event}`
- **Full log / report:** [Open GitHub Actions run]({args.run_url})

### Test & build results

| Check | Result |
|---|---|
| Unit tests | {"✅ PASS" if args.unit_status == "0" else "❌ FAIL"} |
| Debug APK | {"✅ PASS" if args.debug_status == "0" else "❌ FAIL"} |
| Release APK | {"✅ PASS" if args.release_status == "0" else ("⏭️ SKIPPED (non-release push)" if str(args.release_status).upper() == "SKIPPED" else "❌ FAIL")} |

### Issues

{chr(10).join(issue_lines)}

### Summary of changes

{changes}

### Summary of fixes

{fixes}

### Commit/diff summary

```text
{changed[:5000]}
```

---
"""

    history = Path(args.history)
    history.parent.mkdir(parents=True, exist_ok=True)
    if not history.exists():
        history.write_text("# SoundSync Build & Diagnostics History\n\n<!-- SOUNDSYNC_CI_ENTRIES -->\n", encoding="utf-8")
    with history.open("a", encoding="utf-8") as f:
        f.write(entry)

    report = Path(args.report)
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text("# SoundSync CI Diagnostic Report\n" + entry, encoding="utf-8")
    print(overall)

if __name__ == "__main__":
    main()
