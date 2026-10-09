import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from package_release import checksums, package


class ReleasePackageTest(unittest.TestCase):
    def setUp(self):
        self.workspace = tempfile.TemporaryDirectory()
        self.addCleanup(self.workspace.cleanup)
        self.root = Path(self.workspace.name)
        self.apk = self.root / "app-release.apk"
        self.output = self.root / "dist"
        self.metadata = {
            "applicationId": "com.embydog.app", "variantName": "release",
            "elements": [{"outputFile": self.apk.name, "versionName": "0.2.0-rc.1", "versionCode": 42}],
        }
        self.write_metadata()
        self.write_apk({"arm64-v8a"})

    def write_metadata(self):
        self.apk.with_name("output-metadata.json").write_text(json.dumps(self.metadata), encoding="utf-8")

    def write_apk(self, abis, missing_player=False):
        with zipfile.ZipFile(self.apk, "w") as archive:
            for abi in abis:
                archive.writestr(f"lib/{abi}/libmpv.so", b"mpv fixture")
                if not missing_player:
                    archive.writestr(f"lib/{abi}/libplayer.so", b"JNI fixture")

    def test_arm64_asset_name_and_checksum(self):
        destination = package(self.apk, "v0.2.0-rc.1", "arm64-v8a", 42, self.output)
        self.assertEqual(destination.name, "EmbyDog-v0.2.0-rc.1-arm64-v8a.apk")
        self.assertEqual(destination.read_bytes(), self.apk.read_bytes())
        checksums(self.output)
        self.assertEqual((self.output / "SHA256SUMS.txt").read_text(),
                         f"{hashlib.sha256(destination.read_bytes()).hexdigest()}  {destination.name}\n")

    def test_universal_requires_all_four_abis(self):
        with self.assertRaises(ValueError):
            package(self.apk, "v0.2.0-rc.1", "universal", 42, self.output)
        self.write_apk({"arm64-v8a", "armeabi-v7a", "x86", "x86_64"})
        self.assertTrue(package(self.apk, "v0.2.0-rc.1", "universal", 42, self.output).is_file())

    def test_wrong_version_name_or_code_is_rejected(self):
        for tag, code in [("v0.2.1", 42), ("v0.2.0-rc.1", 43)]:
            with self.subTest(tag=tag, code=code), self.assertRaises(ValueError):
                package(self.apk, tag, "arm64-v8a", code, self.output)

    def test_debug_variant_or_wrong_application_is_rejected(self):
        for field, value in [("variantName", "debug"), ("applicationId", "other.app")]:
            original = self.metadata[field]
            self.metadata[field] = value
            self.write_metadata()
            with self.subTest(field=field), self.assertRaises(ValueError):
                package(self.apk, "v0.2.0-rc.1", "arm64-v8a", 42, self.output)
            self.metadata[field] = original

    def test_missing_jni_or_unexpected_abi_is_rejected(self):
        self.write_apk({"arm64-v8a"}, missing_player=True)
        with self.assertRaises(ValueError):
            package(self.apk, "v0.2.0-rc.1", "arm64-v8a", 42, self.output)
        self.write_apk({"arm64-v8a", "x86_64"})
        with self.assertRaises(ValueError):
            package(self.apk, "v0.2.0-rc.1", "arm64-v8a", 42, self.output)

    def test_path_injection_and_unknown_abi_are_rejected(self):
        for tag, abi in [("../../unsafe", "arm64-v8a"), ("v0.2.0-rc.1", "x86")]:
            with self.subTest(tag=tag, abi=abi), self.assertRaises(ValueError):
                package(self.apk, tag, abi, 42, self.output)

    def test_checksums_require_apk_and_include_mapping(self):
        self.output.mkdir()
        with self.assertRaises(ValueError):
            checksums(self.output)
        package(self.apk, "v0.2.0-rc.1", "arm64-v8a", 42, self.output)
        (self.output / "mapping.txt").write_text("mapping fixture", encoding="utf-8")
        checksums(self.output)
        first = (self.output / "SHA256SUMS.txt").read_text()
        checksums(self.output)
        self.assertEqual(first, (self.output / "SHA256SUMS.txt").read_text())
        self.assertIn("  mapping.txt\n", first)


if __name__ == "__main__":
    unittest.main()
