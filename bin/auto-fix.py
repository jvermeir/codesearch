#!/Users/jan/dev/ai/codesearch/.venv/bin/python
"""Auto-fix SonarQube issues using a local Ollama model.

Iterates up to --max-iterations times (default 5), fixing highest-severity issues first.
Issues requiring human judgment get a // TODO [NEEDS HUMAN REVIEW] comment.
All fixes are committed on a new git branch and a markdown report is written to log/.
"""

import argparse
import os
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

try:
    import tomllib
except ModuleNotFoundError:
    try:
        import tomli as tomllib  # type: ignore[no-redef]
    except ModuleNotFoundError:
        print("ERROR: Python < 3.11 requires 'tomli': pip install tomli", file=sys.stderr)
        sys.exit(1)

try:
    import ollama
except ImportError:
    print("ERROR: 'ollama' package required: pip install ollama", file=sys.stderr)
    sys.exit(1)

try:
    import requests
except ImportError:
    print("ERROR: 'requests' package required: pip install requests", file=sys.stderr)
    sys.exit(1)

BIN_DIR = Path(__file__).parent
ROOT_DIR = BIN_DIR.parent
CONFIG_PATH = BIN_DIR / "config.toml"

SEVERITY_RANK = {"BLOCKER": 0, "CRITICAL": 1, "MAJOR": 2, "MINOR": 3, "INFO": 4}
TYPE_RANK = {"BUG": 0, "VULNERABILITY": 1, "CODE_SMELL": 2}
DEFAULT_MAX_ITERATIONS = 5
DEFAULT_MODEL = "qwen2.5-coder"
DEFAULT_OLLAMA_HOST = "http://localhost:11434"


# ---------------------------------------------------------------------------
# Config / pre-flight
# ---------------------------------------------------------------------------

def load_config() -> dict:
    with open(CONFIG_PATH, "rb") as f:
        return tomllib.load(f)


def sonar_running(host_url: str) -> bool:
    try:
        r = requests.get(f"{host_url}/api/system/status", timeout=5)
        return r.json().get("status") == "UP"
    except Exception:
        return False


def ollama_running(host: str) -> bool:
    try:
        r = requests.get(f"{host}/api/tags", timeout=5)
        return r.status_code == 200
    except Exception:
        return False


# ---------------------------------------------------------------------------
# Analysis
# ---------------------------------------------------------------------------

def run_analysis(project_dir: Path) -> tuple[int, str]:
    script = BIN_DIR / "run-analysis.sh"
    r = subprocess.run(
        [str(script), str(project_dir)],
        capture_output=True, text=True, timeout=600,
    )
    return r.returncode, r.stdout + "\n" + r.stderr


# ---------------------------------------------------------------------------
# SonarQube API
# ---------------------------------------------------------------------------

def fetch_issues(host_url: str, token: str, project_key: str) -> list[dict]:
    all_issues: list[dict] = []
    page = 1
    while True:
        r = requests.get(
            f"{host_url}/api/issues/search",
            params={"componentKeys": project_key, "statuses": "OPEN", "ps": 500, "p": page},
            auth=(token, ""),
            timeout=30,
        )
        data = r.json()
        all_issues.extend(data.get("issues", []))
        if len(all_issues) >= data.get("total", 0):
            break
        page += 1
    all_issues.sort(key=lambda i: (
        SEVERITY_RANK.get(i.get("severity", "INFO"), 99),
        TYPE_RANK.get(i.get("type", "CODE_SMELL"), 99),
    ))
    return all_issues


def fetch_metrics(host_url: str, token: str, project_key: str) -> dict:
    r = requests.get(
        f"{host_url}/api/measures/component",
        params={
            "component": project_key,
            "metricKeys": "coverage,bugs,vulnerabilities,code_smells,duplicated_lines_density",
        },
        auth=(token, ""),
        timeout=30,
    )
    return {
        m["metric"]: m.get("value", "n/a")
        for m in r.json().get("component", {}).get("measures", [])
    }


# ---------------------------------------------------------------------------
# File grouping
# ---------------------------------------------------------------------------

def group_by_file(issues: list[dict], project_dir: Path) -> dict[Path, list[dict]]:
    grouped: dict[Path, list[dict]] = {}
    for issue in issues:
        component = issue.get("component", "")
        rel = component.split(":", 1)[1] if ":" in component else component
        abs_path = (project_dir / rel).resolve()
        if abs_path.exists() and abs_path.is_file():
            grouped.setdefault(abs_path, []).append(issue)
    return grouped


# ---------------------------------------------------------------------------
# Ollama
# ---------------------------------------------------------------------------

_SYSTEM_PROMPT = """\
You are a Java code quality expert. Fix the listed SonarQube issues in the file below.

CRITICAL OUTPUT RULES — violating any of these will break the build:
- Output RAW Java source code ONLY. The very first character must be the first character of \
the Java file (e.g. a comment, 'package', or 'import'). The very last character must be '}'.
- Do NOT wrap the output in markdown fences (no ```java or ``` lines).
- Do NOT add any explanation, preamble, or trailing text.
- Do NOT remove or comment out existing methods, fields, or classes — even if they have issues.
- Do NOT shorten the file. The output must contain all the same classes and methods as the input.
- For issues that require human judgment (security architecture decisions, breaking API \
changes, intentional design choices that go beyond a mechanical fix), leave the relevant \
code unchanged and add a comment on that line:
  // TODO [NEEDS HUMAN REVIEW]: <brief reason>
- Fix as many issues as possible automatically.\
"""


def _strip_markdown_fences(text: str) -> str:
    """Remove markdown code fences that models sometimes wrap responses in."""
    lines = text.splitlines()
    if lines and lines[0].strip().startswith("```"):
        lines = lines[1:]
    if lines and lines[-1].strip().startswith("```"):
        lines = lines[:-1]
    return "\n".join(lines)


def fix_file(
    client: ollama.Client,
    model: str,
    path: Path,
    issues: list[dict],
    project_dir: Path,
) -> tuple[str | None, list[dict]]:
    """Return (fixed_source, list_of_human_review_items), or (None, []) if unsafe."""
    rel = path.relative_to(project_dir)
    content = path.read_text()

    issue_lines = []
    for idx, iss in enumerate(issues, 1):
        sev = iss.get("severity", "?")
        typ = iss.get("type", "?")
        line = iss.get("line", "?")
        rule = iss.get("rule", "?")
        msg = iss.get("message", "")
        issue_lines.append(f"{idx}. [{sev}/{typ}] Line {line}: {rule} — {msg}")

    user_msg = (
        f"File: {rel}\n\n{content}\n\n"
        f"Issues to fix (highest priority first):\n" + "\n".join(issue_lines)
    )

    response = client.chat(
        model=model,
        messages=[
            {"role": "system", "content": _SYSTEM_PROMPT},
            {"role": "user", "content": user_msg},
        ],
    )
    fixed = _strip_markdown_fences(response.message.content.strip())

    # Reject responses that lost more than 30% of the original content — the model
    # probably deleted or commented out code instead of fixing it.
    if len(fixed) < len(content) * 0.7:
        print(f"    WARNING: model response for {rel} is suspiciously short "
              f"({len(fixed)} vs {len(content)} chars) — skipping to avoid data loss.")
        return None, []

    todos = []
    for lineno, line_text in enumerate(fixed.splitlines(), 1):
        if "TODO [NEEDS HUMAN REVIEW]" in line_text:
            nearby = [iss for iss in issues if abs((iss.get("line") or 0) - lineno) <= 3]
            todos.append({
                "file": str(rel),
                "line": lineno,
                "comment": line_text.strip(),
                "issues": nearby,
            })
    return fixed, todos


# ---------------------------------------------------------------------------
# Git
# ---------------------------------------------------------------------------

def git_root(path: Path) -> Path:
    r = subprocess.run(
        ["git", "rev-parse", "--show-toplevel"],
        cwd=path, capture_output=True, text=True,
    )
    if r.returncode != 0:
        raise RuntimeError(f"Not inside a git repository: {path}")
    return Path(r.stdout.strip())


def git_create_branch(branch: str, repo: Path) -> tuple[bool, str]:
    r = subprocess.run(
        ["git", "checkout", "-b", branch],
        cwd=repo, capture_output=True, text=True,
    )
    return r.returncode == 0, r.stderr.strip()


def git_commit(message: str, repo: Path, paths: list[Path]) -> tuple[bool, str]:
    rel_paths = [str(p.relative_to(repo)) for p in paths]
    subprocess.run(["git", "add", "--"] + rel_paths, cwd=repo, capture_output=True)
    r = subprocess.run(
        ["git", "commit", "-m", message],
        cwd=repo, capture_output=True, text=True,
    )
    return r.returncode == 0, (r.stdout + r.stderr).strip()


# ---------------------------------------------------------------------------
# Report
# ---------------------------------------------------------------------------

def _mv(metrics: dict, key: str) -> str:
    return str(metrics.get(key, "n/a"))


def build_report(
    ts: datetime,
    branch: str,
    project_dir: Path,
    project_key: str,
    model: str,
    max_iter: int,
    iterations: list[dict],
    fixed_files: list[dict],
    todos: list[dict],
    initial_metrics: dict,
    final_metrics: dict,
    final_issues: list[dict],
    committed: bool,
) -> str:
    stamp = ts.strftime("%Y-%m-%d %H:%M UTC")
    iter_ran = len(iterations)

    iter_rows = "\n".join(
        f"| {it['iteration']} | {it['issues_before']} "
        f"| {it.get('issues_after', '—')} | {it.get('fixed', '—')} |"
        for it in iterations
    )

    metrics_rows = (
        f"| Coverage (%)     | {_mv(initial_metrics, 'coverage')} | {_mv(final_metrics, 'coverage')} |\n"
        f"| Bugs             | {_mv(initial_metrics, 'bugs')} | {_mv(final_metrics, 'bugs')} |\n"
        f"| Vulnerabilities  | {_mv(initial_metrics, 'vulnerabilities')} | {_mv(final_metrics, 'vulnerabilities')} |\n"
        f"| Code smells      | {_mv(initial_metrics, 'code_smells')} | {_mv(final_metrics, 'code_smells')} |\n"
        f"| Duplications (%) | {_mv(initial_metrics, 'duplicated_lines_density')} | {_mv(final_metrics, 'duplicated_lines_density')} |"
    )

    # Fixed issues grouped by iteration
    fixed_section = ""
    for it_num in sorted({f["iteration"] for f in fixed_files}):
        fixed_section += f"\n### Iteration {it_num}\n\n"
        for ff in [f for f in fixed_files if f["iteration"] == it_num]:
            fixed_section += f"#### `{ff['file']}` ({ff['issues_count']} issue(s))\n\n"
            for iss in ff["issues"]:
                sev = iss.get("severity", "?")
                typ = iss.get("type", "?")
                line = iss.get("line", "?")
                rule = iss.get("rule", "?")
                msg = iss.get("message", "")
                fixed_section += f"- Line {line}: [{sev}/{typ}] `{rule}` — {msg}\n"
            fixed_section += "\n"

    # TODOs
    if todos:
        todo_lines = [
            f"- `{td['file']}` line {td['line']}\n  `{td['comment']}`"
            for td in todos
        ]
        todos_section = "\n".join(todo_lines)
    else:
        todos_section = "_None — all issues were fixed automatically._"

    # Remaining issues
    if final_issues:
        remaining_lines = []
        for iss in final_issues:
            component = iss.get("component", "")
            rel = component.split(":", 1)[1] if ":" in component else component
            sev = iss.get("severity", "?")
            typ = iss.get("type", "?")
            line = iss.get("line", "?")
            rule = iss.get("rule", "?")
            msg = iss.get("message", "")
            remaining_lines.append(f"- `{rel}` line {line}: [{sev}/{typ}] `{rule}` — {msg}")
        remaining_section = "\n".join(remaining_lines)
    else:
        remaining_section = "_No remaining issues._"

    commit_note = (
        f"Changes committed on branch `{branch}`."
        if committed
        else "_No changes committed (nothing changed or git error)._"
    )

    return f"""\
# Auto-Fix Report — {stamp}

**Branch:** `{branch}`
**Target:** `{project_dir}`
**Project key:** `{project_key}`
**Model:** `{model}`
**Iterations:** {iter_ran} / {max_iter}

{commit_note}

## Iteration Summary

| Iteration | Issues Before | Issues After | Fixed |
|-----------|--------------|--------------|-------|
{iter_rows}

## Metrics

| Metric           | Before | After |
|------------------|--------|-------|
{metrics_rows}

## Fixed Issues
{fixed_section}
## Needs Human Review

{todos_section}

## Remaining Issues

{remaining_section}
"""


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Auto-fix SonarQube issues using a local Ollama model.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "Examples:\n"
            "  python3 bin/auto-fix.py java-qa-demo\n"
            "  python3 bin/auto-fix.py --max-iterations 3\n"
            "  python3 bin/auto-fix.py /abs/path/to/project --branch my-fix\n"
        ),
    )
    parser.add_argument("target_dir", nargs="?", help="Project directory (default: from config.toml)")
    parser.add_argument(
        "--max-iterations", type=int,
        help=f"Maximum fix iterations (default: {DEFAULT_MAX_ITERATIONS} or ollama.max_iterations in config)",
    )
    parser.add_argument("--branch", help="Git branch name (auto-generated if omitted)")
    parser.add_argument("--project-key", help="SonarQube project key (default: basename of target_dir)")
    args = parser.parse_args()

    config = load_config()
    ts = datetime.now(timezone.utc).replace(microsecond=0)

    # Resolve project directory
    raw_dir = args.target_dir or config["sonar"].get("project_dir", ".")
    project_dir = (ROOT_DIR / raw_dir).resolve()
    if not project_dir.is_dir():
        print(f"ERROR: Not a directory: {project_dir}", file=sys.stderr)
        sys.exit(1)

    project_key = args.project_key or project_dir.name

    # Ollama config (config.toml [ollama] section, overridden by CLI args)
    ollama_cfg = config.get("ollama", {})
    model = ollama_cfg.get("model", DEFAULT_MODEL)
    max_iter = args.max_iterations or ollama_cfg.get("max_iterations", DEFAULT_MAX_ITERATIONS)
    ollama_host = ollama_cfg.get("host", DEFAULT_OLLAMA_HOST)

    host_url = config["sonar"]["host_url"]
    sonar_token = os.environ.get("SONAR_TOKEN", config["sonar"]["token"])

    # Pre-flight checks
    if not sonar_running(host_url):
        print(f"ERROR: SonarQube not reachable at {host_url}. Run ./bin/start-sonar.sh", file=sys.stderr)
        sys.exit(1)
    if not ollama_running(ollama_host):
        print(f"ERROR: Ollama not reachable at {ollama_host}. Start it with: ollama serve", file=sys.stderr)
        sys.exit(1)

    # Git branch
    try:
        repo = git_root(project_dir)
    except RuntimeError as e:
        print(f"ERROR: {e}", file=sys.stderr)
        sys.exit(1)

    branch = args.branch or f"auto-fix/{ts.strftime('%Y-%m-%d-%H-%M')}"
    ok, err = git_create_branch(branch, repo)
    if not ok:
        print(f"ERROR: Could not create branch '{branch}': {err}", file=sys.stderr)
        sys.exit(1)
    print(f"Branch: {branch}")
    print(f"Target: {project_dir}  (key: {project_key})")
    print(f"Model:  {model} @ {ollama_host}  |  max iterations: {max_iter}\n")

    client = ollama.Client(host=ollama_host)

    iterations: list[dict] = []
    fixed_files: list[dict] = []
    all_todos: list[dict] = []
    changed_paths: set[Path] = set()

    # Fetch metrics before any changes
    print("Fetching initial metrics...")
    initial_metrics = fetch_metrics(host_url, sonar_token, project_key)

    # Initial scan so SonarQube has fresh data
    print("Running initial analysis...\n")
    rc, scanner_out = run_analysis(project_dir)
    if rc != 0:
        print("ERROR: Initial analysis failed.", file=sys.stderr)
        print(scanner_out[-2000:], file=sys.stderr)
        sys.exit(1)

    final_issues: list[dict] = []

    for iteration in range(1, max_iter + 1):
        print(f"=== Iteration {iteration}/{max_iter} ===")

        issues = fetch_issues(host_url, sonar_token, project_key)
        issue_count = len(issues)
        print(f"Open issues: {issue_count}")

        if issue_count == 0:
            iterations.append({"iteration": iteration, "issues_before": 0, "issues_after": 0, "fixed": 0})
            print("All issues resolved.\n")
            break

        iterations.append({"iteration": iteration, "issues_before": issue_count})

        grouped = group_by_file(issues, project_dir)
        if not grouped:
            print("No fixable source files found (issues may be in generated or test-output files).")
            iterations[-1].update({"issues_after": issue_count, "fixed": 0})
            final_issues = issues
            break

        for file_path, file_issues in grouped.items():
            rel = file_path.relative_to(project_dir)
            print(f"  Fixing {rel} ({len(file_issues)} issue(s))...")
            fixed_content, todos = fix_file(client, model, file_path, file_issues, project_dir)
            if fixed_content is None:
                continue
            file_path.write_text(fixed_content)
            changed_paths.add(file_path)

            fixed_files.append({
                "iteration": iteration,
                "file": str(rel),
                "issues_count": len(file_issues),
                "issues": file_issues,
            })
            all_todos.extend(todos)

        print("  Re-scanning...")
        rc, scanner_out = run_analysis(project_dir)
        if rc != 0:
            print("  WARNING: Re-scan failed — stopping iterations.", file=sys.stderr)
            iterations[-1].update({"issues_after": "?", "fixed": "?"})
            break

        remaining = fetch_issues(host_url, sonar_token, project_key)
        fixed_count = issue_count - len(remaining)
        iterations[-1].update({"issues_after": len(remaining), "fixed": fixed_count})
        print(f"  Fixed: {fixed_count}  |  Remaining: {len(remaining)}\n")
        final_issues = remaining

        if len(remaining) == 0:
            print("All issues resolved.")
            break

        if fixed_count == 0:
            print("No progress this iteration — stopping to avoid infinite loop.")
            break

    # Commit all changed files
    committed = False
    if changed_paths:
        commit_msg = f"auto-fix: Ollama ({model}) fixes for SonarQube issues ({len(iterations)} iteration(s))"
        committed, commit_out = git_commit(commit_msg, repo, list(changed_paths))
        status = "OK" if committed else f"FAILED — {commit_out}"
        print(f"Commit: {status}")

    # Final metrics
    final_metrics = fetch_metrics(host_url, sonar_token, project_key)

    # Write report
    log_dir = ROOT_DIR / "log"
    log_dir.mkdir(exist_ok=True)
    stamp = ts.strftime("%Y-%m-%d_%H-%M")
    report_path = log_dir / f"qa-loop-{stamp}.md"
    report_path.write_text(build_report(
        ts=ts,
        branch=branch,
        project_dir=project_dir,
        project_key=project_key,
        model=model,
        max_iter=max_iter,
        iterations=iterations,
        fixed_files=fixed_files,
        todos=all_todos,
        initial_metrics=initial_metrics,
        final_metrics=final_metrics,
        final_issues=final_issues,
        committed=committed,
    ))

    print(f"\nReport:    {report_path}")
    print(f"Branch:    {branch}")
    print(f"Dashboard: {host_url}/dashboard?id={project_key}")


if __name__ == "__main__":
    main()
