"""Tests for stage_mpv_preview.py's package pins: python -I -m unittest discover -s windows/tools -p test_*.py"""
import json, pathlib, sys, tempfile, unittest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import stage_mpv_preview  # noqa: E402

PINNED = {"mingw-w64-ucrt-x86_64-ffmpeg": "9.1-1", "mingw-w64-ucrt-x86_64-zlib": "1.4-2"}


class PackagePinTest(unittest.TestCase):
    def test_the_same_versions_pass(self):
        self.assertIsNone(stage_mpv_preview.package_drift(PINNED, dict(PINNED)))

    def test_a_changed_version_names_both_versions(self):
        drift = stage_mpv_preview.package_drift(PINNED, {**PINNED, "mingw-w64-ucrt-x86_64-ffmpeg": "9.2-1"})
        self.assertIn("mingw-w64-ucrt-x86_64-ffmpeg 9.1-1 -> 9.2-1", drift)

    def test_an_added_or_missing_package_is_drift(self):
        added = stage_mpv_preview.package_drift(PINNED, {**PINNED, "mingw-w64-ucrt-x86_64-dav1d": "1.5.5-1"})
        self.assertIn("added mingw-w64-ucrt-x86_64-dav1d 1.5.5-1", added)
        missing = stage_mpv_preview.package_drift(PINNED, {"mingw-w64-ucrt-x86_64-ffmpeg": "9.1-1"})
        self.assertIn("missing mingw-w64-ucrt-x86_64-zlib 1.4-2", missing)

    def test_the_build_stops_and_prints_the_new_list(self):
        with tempfile.TemporaryDirectory() as folder:
            pins = pathlib.Path(folder, "pins.json")
            pins.write_text(json.dumps({"packages": PINNED}), encoding="utf-8")
            stage_mpv_preview.check_pins({**PINNED}, pins)
            with self.assertRaises(RuntimeError) as failure:
                stage_mpv_preview.check_pins({**PINNED, "mingw-w64-ucrt-x86_64-zlib": "1.4-3"}, pins)
        message = str(failure.exception)
        self.assertIn("mingw-w64-ucrt-x86_64-zlib 1.4-2 -> 1.4-3", message)
        self.assertIn('"mingw-w64-ucrt-x86_64-zlib": "1.4-3"', message)

    def test_the_checked_in_pins_cover_ffmpeg_and_libplacebo(self):
        pins = json.loads(pathlib.Path(stage_mpv_preview.__file__).with_name("mpv-preview-packages.json").read_text(encoding="utf-8"))
        self.assertIn("mingw-w64-ucrt-x86_64-ffmpeg", pins["packages"])
        self.assertIn("mingw-w64-ucrt-x86_64-libplacebo", pins["packages"])


if __name__ == "__main__":
    unittest.main()
