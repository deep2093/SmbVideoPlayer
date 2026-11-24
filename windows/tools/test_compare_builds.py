"""Tests for compare_builds.py: python -m unittest windows/tools/test_compare_builds.py"""
import contextlib, io, pathlib, sys, tempfile, unittest, zipfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import compare_builds  # noqa: E402

HASH_A = '0123456789abcdef0123456789abcd'
HASH_B = 'fedcba9876543210fedcba98765432'


def jar(path, entries, stamp=(2026, 1, 1, 0, 0, 0)):
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, 'w') as z:
        for name, data in entries.items():
            z.writestr(zipfile.ZipInfo(name, stamp), data)


class CompareBuildsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.a = pathlib.Path(self.tmp.name, 'a')
        self.b = pathlib.Path(self.tmp.name, 'b')
        for root in (self.a, self.b):
            (root / 'app').mkdir(parents=True)

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, rel, data_a, data_b=None):
        for root, data in ((self.a, data_a), (self.b, data_a if data_b is None else data_b)):
            path = root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)

    def run_main(self, kind='image'):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = compare_builds.main(['--kind', kind, str(self.a), str(self.b)])
        return code, out.getvalue()

    def test_identical_builds_pass(self):
        self.write('app/resources/libmpv-2.dll', b'MZ native')
        code, out = self.run_main()
        self.assertEqual(code, 0)
        self.assertIn('1 files: 1 identical', out)

    def test_regenerated_jar_with_same_entries_is_metadata(self):
        jar(self.a / f'app/skiko-awt-runtime-{HASH_A}.jar', {'META-INF/MANIFEST.MF': 'x'}, (2026, 1, 1, 0, 0, 0))
        jar(self.b / f'app/skiko-awt-runtime-{HASH_B}.jar', {'META-INF/MANIFEST.MF': 'x'}, (2026, 2, 2, 0, 0, 0))
        code, out = self.run_main()
        self.assertEqual(code, 0)
        self.assertIn('metadata  app/skiko-awt-runtime.jar', out)

    def test_jar_with_changed_class_is_different(self):
        jar(self.a / f'app/skywing-{HASH_A}.jar', {'Main.class': 'one'})
        jar(self.b / f'app/skywing-{HASH_B}.jar', {'Main.class': 'two'})
        code, out = self.run_main()
        self.assertEqual(code, 1)
        self.assertIn('different app/skywing.jar: jar contents differ', out)

    def test_cfg_that_differs_only_in_jar_hashes_is_metadata(self):
        self.write('app/SkyWing.cfg', f'app.classpath=$APPDIR\\ui-{HASH_A}.jar\n'.encode(),
                   f'app.classpath=$APPDIR\\ui-{HASH_B}.jar\n'.encode())
        code, _ = self.run_main()
        self.assertEqual(code, 0)

    def test_cfg_with_several_hashed_jars_is_metadata(self):
        # Real SkyWing.cfg files list one jar per line, with 31- or 32-character hashes.
        lines = lambda h1, h2: f'app.classpath=$APPDIR\\SkyWing-Windows-x64-{h1}.jar\napp.classpath=$APPDIR\\skiko-{h2}.jar\n'
        self.write('app/SkyWing.cfg', lines(HASH_A + 'aa', HASH_B[:-1]).encode(), lines(HASH_B + 'b', HASH_A + '1').encode())
        code, _ = self.run_main()
        self.assertEqual(code, 0)

    def test_cfg_with_other_change_is_different(self):
        self.write('app/SkyWing.cfg', b'java-options=-Xmx1g\n', b'java-options=-Xmx2g\n')
        code, _ = self.run_main()
        self.assertEqual(code, 1)

    def test_changed_native_dll_is_different(self):
        self.write('app/resources/motion/svpflow1_vs.dll', b'MZ one', b'MZ two')
        code, out = self.run_main()
        self.assertEqual(code, 1)
        self.assertIn('different app/resources/motion/svpflow1_vs.dll', out)

    def test_file_in_one_build_only_is_different(self):
        (self.a / 'app/extra.txt').write_text('x')
        code, out = self.run_main()
        self.assertEqual(code, 1)
        self.assertIn('only in the first build', out)

    def test_reformatted_motion_manifest_is_metadata(self):
        for root in (self.a, self.b):
            (root / 'svpflow1_vs.dll').write_bytes(b'MZ')
        (self.a / 'BUILD-MANIFEST.json').write_text('{"files": {"svpflow1_vs.dll": "aa"}}')
        (self.b / 'BUILD-MANIFEST.json').write_text('{\n  "files": {\n    "svpflow1_vs.dll": "aa"\n  }\n}\n')
        code, _ = self.run_main('motion')
        self.assertEqual(code, 0)

    def test_motion_manifest_with_other_hash_is_different(self):
        (self.a / 'BUILD-MANIFEST.json').write_text('{"files": {"svpflow1_vs.dll": "aa"}}')
        (self.b / 'BUILD-MANIFEST.json').write_text('{"files": {"svpflow1_vs.dll": "bb"}}')
        code, _ = self.run_main('motion')
        self.assertEqual(code, 1)

    def test_wrong_folder_kind_is_rejected(self):
        with self.assertRaises(SystemExit) as raised, contextlib.redirect_stderr(io.StringIO()):
            compare_builds.main(['--kind', 'motion', str(self.a), str(self.b)])
        self.assertEqual(raised.exception.code, 2)


if __name__ == '__main__':
    unittest.main()
