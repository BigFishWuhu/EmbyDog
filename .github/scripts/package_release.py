"""Validate APK metadata/ABIs and create versioned assets; no signing credentials required."""

import hashlib
import json
from pathlib import Path
import re
import shutil
import sys
import zipfile


def package(apk: Path, tag: str, abi: str, version_code: int, output: Path) -> Path:
    if not re.fullmatch(r"v(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?", tag):
        raise ValueError("Invalid release tag")
    expected_abis = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"} if abi == "universal" else {abi}
    if abi not in {"universal", "arm64-v8a"}:
        raise ValueError("Unsupported release ABI")
    metadata = json.loads(apk.with_name("output-metadata.json").read_text(encoding="utf-8"))
    if metadata["applicationId"] != "com.embydog.app" or metadata["variantName"] != "release":
        raise ValueError("Expected the EmbyDog release variant")
    element = next(item for item in metadata["elements"] if item["outputFile"] == apk.name)
    if element["versionName"] != tag[1:] or element["versionCode"] != version_code:
        raise ValueError("APK version does not match the release")
    with zipfile.ZipFile(apk) as archive:
        names = set(archive.namelist())
        actual_abis = {name.split("/")[1] for name in names if name.startswith("lib/") and name.endswith(".so")}
        if actual_abis != expected_abis:
            raise ValueError(f"Expected ABIs {expected_abis}, got {actual_abis}")
        for architecture in expected_abis:
            for library in ("libmpv.so", "libplayer.so"):
                if f"lib/{architecture}/{library}" not in names:
                    raise ValueError(f"Missing {architecture}/{library}")
    output.mkdir(parents=True, exist_ok=True)
    destination = output / f"EmbyDog-{tag}-{abi}.apk"
    shutil.copyfile(apk, destination)
    return destination


def checksums(output: Path) -> None:
    assets = sorted(path for path in output.iterdir() if path.is_file() and path.name != "SHA256SUMS.txt")
    if not any(path.suffix == ".apk" for path in assets):
        raise ValueError("No APK assets found")
    lines = []
    for path in assets:
        with path.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        lines.append(f"{digest}  {path.name}\n")
    (output / "SHA256SUMS.txt").write_text("".join(lines), encoding="utf-8")


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--checksums":
        checksums(Path(sys.argv[2]))
    elif len(sys.argv) == 6:
        package(Path(sys.argv[1]), sys.argv[2], sys.argv[3], int(sys.argv[4]), Path(sys.argv[5]))
    else:
        raise SystemExit("Usage: package_release.py APK TAG ABI VERSION_CODE OUTPUT_DIR | --checksums OUTPUT_DIR")
