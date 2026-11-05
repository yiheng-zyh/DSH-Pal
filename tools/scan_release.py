"""Read-only text audit of a public source directory or ZIP. Prints locations, not secrets."""
from __future__ import annotations

import argparse
from pathlib import Path
import re
import sys
import zipfile

TEXT = {".kt", ".kts", ".xml", ".md", ".toml", ".properties", ".yml", ".yaml",
        ".json", ".txt", ".pro", ".py", ".ps1", ".js", ".mjs", ".svg", ".bat", ""}
SKIP = {".git", ".gradle", ".kotlin", "build", ".local", "__pycache__"}
LIMIT = 4 * 1024 * 1024
RULES = {
    "private-key": r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----",
    "credential-value": r'''(?i)(?:api[_-]?key|secret|password|token)\s*[=:]\s*['\"][A-Za-z0-9_./+\-=]{24,}['\"]''',
    "bearer-value": r"Bearer\s+[A-Za-z0-9_.-]{24,}",
    "pairing-grant": r"(?:pair|grant)=[A-Za-z0-9_-]{24,}",
    "windows-user-directory": r"[A-Za-z]:[\\/]Users[\\/][^\\/\s<>]+",
}


def entries(target: Path):
    if target.is_dir():
        for path in sorted(target.rglob("*")):
            rel = path.relative_to(target)
            if not path.is_file() or path.is_symlink() or set(rel.parts) & SKIP:
                continue
            if path.suffix.lower() in TEXT and path.stat().st_size <= LIMIT:
                yield rel.as_posix(), path.read_bytes()
    else:
        with zipfile.ZipFile(target) as archive:
            for item in archive.infolist():
                path = Path(item.filename)
                if not item.is_dir() and not set(path.parts) & SKIP and path.suffix.lower() in TEXT and item.file_size <= LIMIT:
                    yield item.filename, archive.read(item)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("target", type=Path, help="Public source directory or ZIP; never extracts or modifies it")
    args = parser.parse_args()
    if not args.target.exists():
        parser.error("Target does not exist")
    hits = 0
    files = 0
    for name, data in entries(args.target):
        text = data.decode("utf-8", errors="replace")
        files += 1
        for label, pattern in RULES.items():
            for match in re.finditer(pattern, text):
                hits += 1
                line = text.count("\n", 0, match.start()) + 1
                print(f"REVIEW {label} {name}:{line}")
    print(f"Audited {files} text files; {hits} matches. Values are intentionally hidden.")
    print("This is a limited pattern check, not proof of complete privacy or license compliance. Review endpoints, media, binaries and Git history separately.")
    return 1 if hits else 0


if __name__ == "__main__":
    sys.exit(main())
