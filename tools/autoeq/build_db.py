#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Builds the AutoEQ database the app bundles (app/src/main/assets/autoeq.tsv).

Input: a checkout of https://github.com/jaakkopasanen/AutoEq (MIT). Only the results are read:
results/<source>/<rig>/<name>/<name> ParametricEQ.txt, and results/README.md for the result
AutoEQ recommends per headphone. A sparse, blob-less clone is enough:

    git clone --depth 1 --filter=blob:none --no-checkout https://github.com/jaakkopasanen/AutoEq.git
    cd AutoEq && git sparse-checkout set --no-cone /LICENSE '/results/README.md' '/results/**/*ParametricEQ.txt'
    git checkout
    python3 tools/autoeq/build_db.py path/to/AutoEq app/src/main/assets/autoeq.tsv

Output, one headphone per line (the format AutoEq.readDatabase reads in android-core:model):
    name TAB source TAB rig TAB recommended(0|1) TAB filters
filters: space-separated <type><freq>:<gain>:<q>, type L (low shelf), H (high shelf), P (peaking).
Values are kept as AutoEQ wrote them; the app clamps them to its ranges when it reads them.
"""

import re
import subprocess
import sys
import urllib.parse
from pathlib import Path

FILTER = re.compile(r"^Filter\s*\d*:\s*ON\s+(\w+)\s+Fc\s+([-\d.]+)\s*Hz\s+Gain\s+([-\d.]+)\s*dB\s+Q\s+([\d.]+)")
PREAMP = re.compile(r"^Preamp:")
TYPES = {"PK": "P", "LSC": "L", "HSC": "H"}
LINK = re.compile(r"^- \[.*?\]\(\./(.*)\)\s*$")


def number(text: str) -> str:
    return "%g" % float(text)


def recommended(results: Path) -> set:
    """source/rig/name of each result results/README.md lists."""
    out = set()
    for line in (results / "README.md").read_text(encoding="utf-8").splitlines():
        m = LINK.match(line)
        if m:
            out.add(urllib.parse.unquote(m.group(1)))
    return out


def filters(path: Path) -> str:
    parts = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or PREAMP.match(line):
            continue
        m = FILTER.match(line)
        if not m or m.group(1) not in TYPES:
            raise ValueError(f"{path}: unexpected line {line!r}")
        parts.append(f"{TYPES[m.group(1)]}{number(m.group(2))}:{number(m.group(3))}:{number(m.group(4))}")
    if not parts:
        raise ValueError(f"{path}: no filters")
    return " ".join(parts)


def commit(repo: Path) -> str:
    try:
        return subprocess.run(["git", "-C", str(repo), "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return "unknown"


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    repo, out = Path(sys.argv[1]), Path(sys.argv[2])
    results = repo / "results"
    best = recommended(results)
    rows = []
    for path in sorted(results.glob("*/*/*/* ParametricEQ.txt")):
        source, rig, name = path.parts[-4], path.parts[-3], path.parts[-2]
        if any(c in "\t\n" for c in source + rig + name):
            raise ValueError(f"{path}: tab or newline in a name")
        flag = "1" if f"{source}/{rig}/{name}" in best else "0"
        rows.append("\t".join((name, source, rig, flag, filters(path))))
    rows.sort(key=lambda r: (r.split("\t")[0].lower(), r))
    header = [
        "# AutoEQ results (https://github.com/jaakkopasanen/AutoEq, MIT License, Copyright (c) 2018-2022 Jaakko Pasanen)",
        f"# commit {commit(repo)}; {len(rows)} results, {sum(r.split(chr(9))[3] == '1' for r in rows)} recommended",
        "# written by tools/autoeq/build_db.py: name, source, rig, recommended, filters (<L|H|P><Hz>:<dB>:<Q>)",
    ]
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(header + rows) + "\n", encoding="utf-8")
    print(f"{out}: {len(rows)} results", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
