#!/usr/bin/env python3
"""
Backfill docs/BUILD_DIAGNOSTICS_LOG.md from real repository history.

Sources:
1) full local git history (author/date/SHA/subject);
2) GitHub Actions runs retained by GitHub, when `gh` is installed and authenticated.

Never invents historical push/failure data that is no longer available.
"""
from __future__ import annotations
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from datetime import datetime, timezone

ROOT = Path(subprocess.check_output(["git", "rev-parse", "--show-toplevel"], text=True).strip())
HISTORY = ROOT / "docs" / "BUILD_DIAGNOSTICS_LOG.md"
MARKER = "<!-- SOUNDSYNC_CI_ENTRIES -->"

def run(cmd, check=True):
    p = subprocess.run(cmd, cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if check and p.returncode != 0:
        raise RuntimeError(f"{' '.join(cmd)} failed: {p.stderr.strip()}")
    return p

def esc(s):
    return (s or "").replace("|", "\\|").replace("\n", " ").strip()

def git_history():
    p = run([
        "git","log","--all","--date=iso-strict",
        "--pretty=format:%H%x1f%ad%x1f%an%x1f%s"
    ])
    rows = []
    for line in p.stdout.splitlines():
        parts = line.split("\x1f")
        if len(parts) == 4:
            sha, date, author, subject = parts
            rows.append((sha, date, author, subject))
    return rows

def repo_name():
    if shutil.which("gh"):
        p = run(["gh","repo","view","--json","nameWithOwner","-q",".nameWithOwner"], check=False)
        if p.returncode == 0 and p.stdout.strip():
            return p.stdout.strip()
    p = run(["git","remote","get-url","origin"], check=False)
    url = p.stdout.strip()
    m = re.search(r"github\.com[:/](.+?)(?:\.git)?$", url)
    return m.group(1) if m else ""

def gh_runs():
    if not shutil.which("gh"):
        return None, "GitHub CLI (`gh`) is not installed."
    auth = run(["gh","auth","status"], check=False)
    if auth.returncode != 0:
        return None, "GitHub CLI is installed but not authenticated."
    fields = "databaseId,displayTitle,headSha,event,status,conclusion,createdAt,updatedAt,workflowName"
    p = run(["gh","run","list","--limit","1000","--json",fields], check=False)
    if p.returncode != 0:
        return None, f"GitHub Actions run listing failed: {p.stderr.strip()}"
    try:
        return json.loads(p.stdout), None
    except Exception as e:
        return None, f"Could not parse GitHub Actions history: {e}"

def failed_detail(run_id):
    p = run(["gh","run","view",str(run_id),"--log-failed"], check=False)
    if p.returncode != 0 or not p.stdout.strip():
        return "Failure logs unavailable/expired."
    lines = []
    for raw in p.stdout.splitlines():
        s = raw.strip()
        if re.search(r"(FAILURE|FAILED|error[: ]|exception|what went wrong|could not|compilation failed)", s, re.I):
            lines.append(s)
        if len(lines) >= 8:
            break
    if not lines:
        lines = [x.strip() for x in p.stdout.splitlines()[-8:] if x.strip()]
    return " / ".join(lines)[:1800] or "Failure recorded, but no concise cause could be extracted."

def main():
    HISTORY.parent.mkdir(parents=True, exist_ok=True)
    existing = HISTORY.read_text(encoding="utf-8") if HISTORY.exists() else (
        "# SoundSync Build & Diagnostics History\n\n" + MARKER + "\n"
    )
    if "## Historical Git commit backfill" in existing:
        print("Historical backfill already present; leaving existing history intact.")
        return 0

    commits = git_history()
    repo = repo_name()
    runs, gh_error = gh_runs()

    block = []
    block.append("## Historical Git commit backfill\n")
    block.append(f"_Generated: {datetime.now(timezone.utc).isoformat()}_\n")
    block.append(f"_Repository: `{repo or 'unable to resolve'}`_\n")
    block.append("\n| Date | Commit | Author | Summary |\n|---|---|---|---|\n")
    for sha, date, author, subject in commits:
        block.append(f"| {esc(date)} | `{sha[:10]}` | {esc(author)} | {esc(subject)} |\n")

    block.append("\n## Historical GitHub Actions backfill\n")
    if gh_error:
        block.append(f"\n**Actions history not imported:** {gh_error}\n")
        block.append("Run this script again after `gh auth login` to import retained workflow history.\n")
    else:
        block.append("\nOnly workflow runs still retained by GitHub can be listed. Expired logs cannot be reconstructed.\n")
        block.append("\n| Date | Workflow | Commit | Event | Result | Run |\n|---|---|---|---|---|---|\n")
        for r in runs:
            rid = r.get("databaseId")
            url = f"https://github.com/{repo}/actions/runs/{rid}" if repo and rid else ""
            result = r.get("conclusion") or r.get("status") or "unknown"
            link = f"[open]({url})" if url else str(rid or "")
            block.append(
                f"| {esc(r.get('createdAt'))} | {esc(r.get('workflowName') or r.get('displayTitle'))} | "
                f"`{esc((r.get('headSha') or '')[:10])}` | {esc(r.get('event'))} | {esc(result)} | {link} |\n"
            )
            if result in {"failure","cancelled","timed_out","action_required","stale"} and rid:
                detail = failed_detail(rid)
                block.append(f"\n**Failure `{rid}`:** {esc(detail)}\n")

    block.append("""
### Historical coverage note

- Git history above represents commits available in the repository.
- A Git repository does **not** preserve a complete historical ledger of every push event.
- GitHub may expire old Actions logs.
- Missing push/failure records are marked unavailable rather than guessed.

---
""")

    insert = "\n".join(block)
    if MARKER in existing:
        updated = existing.replace(MARKER, insert + "\n\n" + MARKER, 1)
    else:
        updated = existing + "\n" + insert + "\n" + MARKER + "\n"
    HISTORY.write_text(updated, encoding="utf-8")
    print(f"Backfilled {len(commits)} commits" + (f" and {len(runs)} retained Actions runs." if runs is not None else "."))
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
