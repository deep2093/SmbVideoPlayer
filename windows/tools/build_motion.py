"""Build the pinned x64 motion component; Python 3.11+ and Rust/MSVC required in CI.
--plugins permits local validation with DLLs built from the same patched source.
No downloaded executable or installer is launched. Extraction stays in dedicated build directories.
"""
import argparse, hashlib, json, os, pathlib, platform, shutil, subprocess, sys, tarfile, urllib.request, zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
MOTION = json.loads((ROOT/'windows/legal/NATIVE-PROVENANCE.json').read_text())['motion']

def sha(p):
    with p.open('rb') as f: return hashlib.file_digest(f, 'sha256').hexdigest()

def tool_version(command, env=None):
    """First output line of a version command, or None when the tool is not on PATH."""
    try: result=subprocess.run(command,capture_output=True,text=True,env=env,check=False)
    except FileNotFoundError: return None
    lines=(result.stdout or result.stderr).strip().splitlines()
    return lines[0].strip() if lines else None

def toolchain_info(toolchain_name, plugins):
    """Versions that produced this payload, so two builds can be compared and rebuilt."""
    info={'python':sys.version.split()[0],'platform':platform.platform()}
    if plugins: info['plugins']='supplied with --plugins; compiler versions unknown'
    else:
        info['rustc']=subprocess.run(['rustc','+'+toolchain_name,'-vV'],capture_output=True,text=True,check=True).stdout.strip().splitlines()
        info['cargo']=tool_version(['cargo','+'+toolchain_name,'-V'])
        # Set by the MSVC developer environment (ilammy/msvc-dev-cmd in CI).
        for name in ('VCToolsVersion','WindowsSDKVersion','VSCMD_VER'):
            info[name]=os.environ.get(name,'').strip('\\') or None
        info['link']=tool_version(['link','/?'])
    return info

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
    # Stage at a fixed path: Cargo derives crate metadata hashes from the source path, so a random
    # folder can change the plugin binaries. Only a folder this script created (marker file) is erased.
    stage=args.cache/'skywing-motion-stage'
    marker=stage/'.skywing-motion-stage'
    if stage.exists():
        if not marker.is_file(): raise RuntimeError('Refusing to erase a folder this script did not create: '+str(stage))
        shutil.rmtree(stage)
    stage.mkdir()
    marker.write_text('Created by windows/tools/build_motion.py; erased on the next run.\n',encoding='utf-8')
    try:
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
            # RUSTFLAGS replaces .cargo/config.toml flags; retain upstream's CPU target.
            env['RUSTFLAGS']='-C target-cpu=x86-64-v3 -C target-feature=+crt-static'
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
        # Hash the LF patch that was applied, not the checkout copy, whose line endings vary.
        manifest={'motion':MOTION,'patchSha256':sha(patch),'toolchain':toolchain_info(MOTION['rustToolchain'],args.plugins),
            'localPluginOverride':bool(args.plugins),'files':{p.relative_to(payload).as_posix():sha(p) for p in sorted(payload.rglob('*')) if p.is_file()}}
        (payload/'BUILD-MANIFEST.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
        args.output.mkdir(parents=True,exist_ok=True)
        if any(args.output.iterdir()): raise RuntimeError('Output must be empty: '+str(args.output))
        shutil.copytree(payload,args.output,dirs_exist_ok=True)
        print('Motion component:',sum(p.stat().st_size for p in args.output.rglob('*') if p.is_file()),'bytes')
    finally:
        shutil.rmtree(stage)

if __name__=='__main__': main()
