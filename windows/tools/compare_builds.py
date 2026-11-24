"""Compare two builds of the same SkyWing source and report genuine binary differences.

Usage: python compare_builds.py [--kind image|motion] FIRST SECOND

FIRST and SECOND are two app images (windows/build/compose/binaries/main/app/SkyWing) or two motion payloads
(windows/build/native-resources/windows/motion). Every file is classed as:

- identical: same bytes;
- metadata: differs only in data a rebuild is expected to change (see EXPECTED below);
- different: a genuine difference, or a file present in only one build.

Exit code 0 when nothing is "different", 1 otherwise, 2 on bad arguments.
"""
import argparse, hashlib, json, pathlib, re, sys, zipfile

# Compose Desktop names each packaged jar <name>-<hash of its path>.jar and regenerates a manifest-only
# skiko-awt-runtime jar on every packaging run; SkyWing.cfg lists those jar names.
JAR_HASH = re.compile(r'-[0-9a-f]{28,32}(?=\.jar$)')
JAR_HASH_IN_TEXT = re.compile(r'-[0-9a-f]{28,32}(?=\.jar\b)')
EXPECTED = {
    'jar': 'same entries and contents; only the jar file name hash or zip timestamps differ',
    'cfg': 'SkyWing.cfg differs only in the jar file name hashes',
    'manifest': 'BUILD-MANIFEST.json lists the same payload hashes, inputs and toolchain',
}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def files(root):
    """Map each file's relative path, with jar name hashes removed, to its path."""
    found = {}
    for path in sorted(p for p in root.rglob('*') if p.is_file()):
        key = JAR_HASH.sub('', path.relative_to(root).as_posix())
        if key in found:
            raise SystemExit(f'Two files map to {key} in {root}')
        found[key] = path
    return found


def jar_entries(path):
    with zipfile.ZipFile(path) as jar:
        return {i.filename: sha(jar.read(i)) for i in jar.infolist() if not i.is_dir()}


def classify(key, first, second):
    """Return ('identical'|'metadata'|'different', reason)."""
    a, b = first.read_bytes(), second.read_bytes()
    if a == b:
        return 'identical', ''
    if key.endswith('.jar'):
        try:
            if jar_entries(first) == jar_entries(second):
                return 'metadata', EXPECTED['jar']
        except zipfile.BadZipFile:
            pass
        return 'different', 'jar contents differ'
    if key.endswith('SkyWing.cfg'):
        mask = lambda data: JAR_HASH_IN_TEXT.sub('', data.decode('utf-8', 'replace'))
        if mask(a) == mask(b):
            return 'metadata', EXPECTED['cfg']
    if key.endswith('BUILD-MANIFEST.json'):
        try:
            if json.loads(a) == json.loads(b):
                return 'metadata', EXPECTED['manifest']
        except ValueError:
            pass
    return 'different', f'{len(a)} vs {len(b)} bytes, SHA-256 {sha(a)[:16]} vs {sha(b)[:16]}'


def compare(first_root, second_root):
    first, second = files(first_root), files(second_root)
    rows = []
    for key in sorted(set(first) | set(second)):
        if key not in second:
            rows.append((key, 'different', 'only in the first build'))
        elif key not in first:
            rows.append((key, 'different', 'only in the second build'))
        else:
            rows.append((key, *classify(key, first[key], second[key])))
    return rows


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--kind', choices=('image', 'motion'), default='image')
    parser.add_argument('first', type=pathlib.Path)
    parser.add_argument('second', type=pathlib.Path)
    args = parser.parse_args(argv)
    for root in (args.first, args.second):
        if not root.is_dir():
            parser.error(f'not a folder: {root}')
        marker = 'BUILD-MANIFEST.json' if args.kind == 'motion' else 'app'
        if not (root / marker).exists():
            parser.error(f'{root} is not a {args.kind} build (no {marker})')
    rows = compare(args.first, args.second)
    counts = {c: sum(1 for _, k, _ in rows if k == c) for c in ('identical', 'metadata', 'different')}
    for key, kind, reason in rows:
        if kind != 'identical':
            print(f'{kind:9} {key}: {reason}')
    print(f"{len(rows)} files: {counts['identical']} identical, {counts['metadata']} metadata only, "
          f"{counts['different']} different")
    return 1 if counts['different'] else 0


if __name__ == '__main__':
    sys.exit(main())
