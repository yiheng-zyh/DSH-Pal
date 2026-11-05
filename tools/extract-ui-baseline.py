"""Filter a device ART text dump into a small, reviewable UI Baseline Profile."""
import argparse
from pathlib import Path
import re

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("source", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()
rules = set()
for line in args.source.read_text(encoding="utf-8").splitlines():
    match = re.match(r"^([HSP]*)(L[^;]+;)(.*)$", line)
    if not match:
        continue
    flags, owner, suffix = match.groups()
    app_ui = owner.startswith("Lcom/dsh/mobile/ui/") or owner in {
        "Lcom/dsh/mobile/MainActivity;", "Lcom/dsh/mobile/DshApp;"
    }
    hot_ui = "H" in flags and owner.startswith((
        "Landroidx/compose/", "Landroidx/activity/", "Landroidx/lifecycle/",
        "Ltop/yukonga/", "Lcom/dsh/mobile/",
    ))
    if app_ui or hot_ui:
        rules.add(line)
if not rules:
    raise SystemExit("No UI profile rules found; output was not changed")
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text("# Physical-device startup, navigation and scrolling UI profile.\n"
                       "# Regeneration and validation: docs/interaction-performance.md\n"
                       + "\n".join(sorted(rules)) + "\n", encoding="utf-8")
print(f"Wrote {len(rules)} UI rules; no runtime content or device identifiers included")
