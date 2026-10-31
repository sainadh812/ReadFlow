#!/usr/bin/env python3
"""Retain notices from the exact resolved Play runtime artifacts (including nested JARs).

This is an inventory, not a legal approval: POM declarations and missing upstream
notices still require publisher review. Nothing is fetched or executed from an artifact.
"""
import hashlib
import io
import json
import pathlib
import re
import shutil
import sys
import xml.etree.ElementTree as ET
import zipfile


def package(inventory: pathlib.Path, output: pathlib.Path):
    directory = output / "licenses" / "dependencies"
    if directory.exists():
        shutil.rmtree(directory)
    directory.mkdir(parents=True)
    entries = []
    for line in inventory.read_text().splitlines():
        coordinate, filename = line.split("\t", 1)
        artifact = pathlib.Path(filename)
        stem = re.sub(r"[^A-Za-z0-9._-]", "_", coordinate + "__" + artifact.name)
        entry = {"coordinate": coordinate, "artifact": artifact.name,
                 "sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(),
                 "declaredLicenses": [], "embeddedNotices": []}
        # Maven cache: group/module/version/hash/artifact. Retain POM declarations too.
        for pom in sorted(artifact.parent.parent.glob("*/*.pom")):
            root = ET.fromstring(pom.read_bytes())
            for license_node in root.findall("{*}licenses/{*}license"):
                entry["declaredLicenses"].append({child.tag.split("}")[-1]: child.text or "" for child in license_node})

        def extract(archive, prefix=""):
            for item in archive.infolist():
                if item.is_dir():
                    continue
                name = item.filename
                basename = pathlib.PurePosixPath(name).name.lower()
                if basename.endswith(".jar") and item.file_size < 100_000_000:
                    with zipfile.ZipFile(io.BytesIO(archive.read(item))) as nested:
                        extract(nested, prefix + name + "/")
                elif (any(word in basename for word in ("license", "notice", "copying", "copyright"))
                      and not basename.endswith((".class", ".kt", ".java")) and item.file_size < 20_000_000):
                    label = prefix + name
                    suffix = re.sub(r"[^A-Za-z0-9._-]", "_", label)
                    target = directory / f"{stem}__{suffix}"
                    target.write_bytes(archive.read(item))
                    entry["embeddedNotices"].append(target.name)

        with zipfile.ZipFile(artifact) as archive:
            extract(archive)
        entries.append(entry)
    (directory / "resolved-runtime.json").write_text(json.dumps(entries, indent=2) + "\n")
    missing = [entry["coordinate"] for entry in entries if not entry["embeddedNotices"]]
    (directory / "README.txt").write_text(
        "Exact resolved release dependencies and their upstream notices. See resolved-runtime.json for hashes and Maven license declarations.\n"
        "Some artifacts omit full license texts; the publisher must reconcile these against upstream sources before publication.\n"
        "Artifacts without embedded license/notice files:\n" + "\n".join(missing) + "\n")
    print(f"Packaged notices for {len(entries)} runtime artifacts; {len(missing)} require source/POM reconciliation.")


if __name__ == "__main__":
    package(pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]))
