"""Build the pinned x64 motion component; Python 3.11+ and Rust/MSVC required in CI.
--plugins permits local validation with DLLs built from the same patched source.
No downloaded executable or installer is launched. Extraction stays in dedicated build directories.
"""
import argparse, hashlib, json, os, pathlib, shutil, subprocess, tarfile, tempfile, urllib.request, zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
MOTION = json.loads((ROOT/'windows/legal/NATIVE-PROVENANCE.json').read_text())['motion']

def sha(p):
    with p.open('rb') as f: return hashlib.file_digest(f, 'sha256').hexdigest()

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--cache', type=pathlib.Path, required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    parser.add_argument('--plugins', type=pathlib.Path)
    parser.add_argument('--cargo-home', type=pathlib.Path, default=pathlib.Path(os.environ.get('CARGO_HOME', pathlib.Path.home()/'.cargo')))
    args=parser.parse_args()
    args.cache.mkdir(parents=True,exist_ok=True)
    downloads={}
    for item in MOTION['downloads']:
        archive=args.cache/item['name']
        if not archive.exists(): urllib.request.urlretrieve(item['url'],archive)
        if sha(archive)!=item['sha256']: raise RuntimeError('Checksum mismatch: '+item['name'])
        downloads[item['name']]=archive
    # Always stage into a fresh subdirectory; never recursively erase a supplied path.
    with tempfile.TemporaryDirectory(prefix='skywing-motion-',dir=args.cache) as temporary:
        stage=pathlib.Path(temporary)
        with tarfile.open(downloads['open-svpflow-deadbad.tar.gz']) as archive:
            archive.extractall(stage/'source',filter='data')
        source=next((stage/'source').iterdir())
        # A Windows checkout may use CRLF; the upstream tarball uses LF.
        patch=stage/'open-svpflow-windows.patch'
        patch.write_text((ROOT/MOTION['patch']).read_text(encoding='utf-8'),encoding='utf-8',newline='\n')
        subprocess.run(['git','apply','--check',str(patch)],cwd=source,check=True)
        subprocess.run(['git','apply',str(patch)],cwd=source,check=True)
        for name in ('crates/svpflow-host/src/opencl.rs','crates/svpflow1/src/analyse/gpu.rs','crates/svpflow2/src/gpu.rs'):
            if 'device_matches_vendor' not in (source/name).read_text(): raise RuntimeError('Vendor patch was not applied: '+name)
        if args.plugins:
            plugins=args.plugins.resolve()
        else:
            toolchain=MOTION['rustToolchain']
            subprocess.run(['rustup','toolchain','install',toolchain,'--profile','minimal'],check=True)
            env=os.environ.copy()
            env['RUSTFLAGS']='-C target-feature=+crt-static'
            subprocess.run(['cargo','+'+toolchain,'build','--release','--locked','--target',MOTION['target'],
                '-p','svpflow1','-p','svpflow2'],cwd=source,env=env,check=True)
            plugins=source/'target'/MOTION['target']/'release'
        payload=stage/'payload'
        payload.mkdir()
        with zipfile.ZipFile(downloads['python-3.13.9-embed-amd64.zip']) as archive: archive.extractall(payload)
        vs=stage/'vapoursynth'
        with zipfile.ZipFile(downloads['VapourSynth64-Portable-R73.zip']) as archive: archive.extractall(vs)
        for pattern in ('VSScript.dll','portable.vs','msvcp*.dll','vcruntime*.dll','concrt*.dll'):
            for item in vs.glob(pattern): shutil.copy2(item,payload/item.name)
        site=payload/'Lib/site-packages'
        with zipfile.ZipFile(next((vs/'wheel').glob('*.whl'))) as archive: archive.extractall(site)
        for data in site.glob('*.data/data'): shutil.copytree(data,payload,dirs_exist_ok=True)
        # Private portable search path: no user Python modules or external VS plugins.
        (payload/'python313._pth').write_text('python313.zip\n.\nLib/site-packages\n',encoding='utf-8')
        for name in ('svpflow1_vs.dll','svpflow2_vs.dll'): shutil.copy2(plugins/name,payload/name)
        legal=payload/'legal'; legal.mkdir()
        shutil.copy2(source/'LICENSE',legal/'open-svpflow-LICENSE.txt')
        shutil.copy2(payload/'LICENSE.txt',legal/'Python-LICENSE.txt')
        shutil.copy2(site/'vapoursynth-73.dist-info/licenses/COPYING.LESSER',legal/'VapourSynth-COPYING.LESSER')
        # Native Windows dependency closure (Cargo.lock supplies versions/checksums).
        import tomllib
        packages={p['name']:p for p in tomllib.loads((source/'Cargo.lock').read_text())['package']}
        names=['rayon','rayon-core','either','crossbeam-deque','crossbeam-epoch','crossbeam-utils','libloading','windows-link']
        notices=[]
        for name in names:
            package=packages[name]; key=name+'-'+package['version']
            folders=list((args.cargo_home/'registry/src').glob('*/'+key))
            if not folders: raise RuntimeError('Missing crate notices: '+key)
            files=[p for p in folders[0].iterdir() if p.is_file() and p.name.lower().startswith(('license','copying','notice','copyright'))]
            if not files: raise RuntimeError('Missing crate licence: '+key)
            for item in files: notices.append(key+'/'+item.name+'\n\n'+item.read_text(encoding='utf-8')+'\n')
        (legal/'RUST-CRATES.txt').write_text('\n'.join(notices),encoding='utf-8')
        manifest={'motion':MOTION,'patchSha256':sha(ROOT/MOTION['patch']),
            'localPluginOverride':bool(args.plugins),'files':{p.relative_to(payload).as_posix():sha(p) for p in sorted(payload.rglob('*')) if p.is_file()}}
        (payload/'BUILD-MANIFEST.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
        args.output.mkdir(parents=True,exist_ok=True)
        if any(args.output.iterdir()): raise RuntimeError('Output must be empty: '+str(args.output))
        shutil.copytree(payload,args.output,dirs_exist_ok=True)
        print('Motion component:',sum(p.stat().st_size for p in args.output.rglob('*') if p.is_file()),'bytes')

if __name__=='__main__': main()
