"""Tablet smoke test against only the disposable CI share, using visible UI labels."""
import re
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

APP='com.deepak.videolibrary.preview'
NAME='Tom Cruise - Fixture.mp4'

def adb(*args):
    return subprocess.check_output(['adb',*map(str,args)],timeout=30)

def tree():
    # A cold activity can briefly have no accessibility root after resizing.
    # UiAutomator reports that on stdout without a failing exit code.
    text=''
    for _ in range(5):
        try:
            adb('shell','uiautomator','dump','/sdcard/library-ui.xml')
            text=adb('exec-out','cat','/sdcard/library-ui.xml').decode()
        except (subprocess.CalledProcessError,subprocess.TimeoutExpired) as error:
            # The separate UiAutomator process can crash during a transition.
            text=str(error)
            time.sleep(1)
            continue
        try:
            root=ET.fromstring(text)
            # Screen resizing can hang the stock emulator launcher. Recover only
            # Quickstep, never an ANR from the app under test.
            if any("Quickstep isn't responding" in n.get('text','') for n in root.iter('node')):
                close=next(n for n in root.iter('node') if n.get('text')=='Close app')
                x1,y1,x2,y2=map(int,re.findall(r'\d+',close.attrib['bounds']))
                adb('shell','input','tap',(x1+x2)//2,(y1+y2)//2)
                adb('shell','am','start','-n',APP+'/com.deepak.videolibrary.MainActivity')
                print('Recovered stock emulator launcher after screen resize')
                time.sleep(1)
                continue
            Path('preview/device-ui.xml').write_text(text)
            return root
        except ET.ParseError:
            time.sleep(1)
    raise AssertionError('UiAutomator did not produce a hierarchy: '+text[:300])

def find(label,timeout=20):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        root=tree()
        for node in root.iter('node'):
            if node.get('content-desc')==label:
                return node
        for node in root.iter('node'):
            # Compose merges a clickable card's filename and secondary caption
            # into one accessibility node separated by newlines.
            if label in node.get('text','').splitlines() or node.get('content-desc')==label:
                return node
        time.sleep(.5)
    raise AssertionError('UI label not found: '+label)

def click(label,long=False):
    node=find(label)
    x1,y1,x2,y2=map(int,re.findall(r'\d+',node.attrib['bounds']))
    x,y=(x1+x2)//2,(y1+y2)//2
    if long:
        adb('shell','input','swipe',x,y,x,y,900)
    else:
        adb('shell','input','tap',x,y)
    time.sleep(.6)

def screenshot(name):
    Path('preview/device-'+name+'.png').write_bytes(adb('exec-out','screencap','-p'))

def enter_text(value):
    field=next(n for n in tree().iter('node') if n.get('class')=='android.widget.EditText')
    x1,y1,x2,y2=map(int,re.findall(r'\d+',field.attrib['bounds']))
    adb('shell','input','tap',(x1+x2)//2,(y1+y2)//2)
    adb('shell','input','text',value)

try:
    adb('shell','wm','size','2560x1600')
    adb('shell','wm','density','240')
    adb('shell','am','force-stop',APP)
    adb('shell','am','start','-n',APP+'/com.deepak.videolibrary.MainActivity')
    # Android 13+ can show the app's normal notification-permission prompt.
    # Handle the system dialog before looking for app tabs.
    for _ in range(10):
        nodes=list(tree().iter('node'))
        allow=next((n for n in nodes if n.get('resource-id','').endswith(':id/permission_allow_button')),None)
        if allow is not None:
            x1,y1,x2,y2=map(int,re.findall(r'\d+',allow.attrib['bounds']))
            adb('shell','input','tap',(x1+x2)//2,(y1+y2)//2)
            time.sleep(1)
        elif any('Virtual folders' in n.get('text','').splitlines() for n in nodes):
            break
        else:
            time.sleep(1)
    find('Virtual folders');find('Original folders')
    click('Virtual folders');find('Favourites')
    labels=[n.get('text','') for n in tree().iter('node')]
    assert not any('Choose source folders' in label for label in labels)
    print('PASS: Virtual folders restored automatically without a source picker')
    click('Settings')
    current='Dark grey'
    for theme in ['AMOLED black','Midnight blue','Forest','Plum','Dark grey']:
        click('Theme: '+current);click(theme);find('Theme: '+theme)
        current=theme
    screenshot('dark-grey-theme')
    adb('shell','am','force-stop',APP)
    adb('shell','am','start','-n',APP+'/com.deepak.videolibrary.MainActivity')
    click('Settings');find('Theme: Dark grey')
    print('PASS: five theme choices apply live and persist across restart')
    click('Folders')
    click('Original folders');find('Movies');screenshot('originals')
    click('Videos');click(NAME,long=True)
    find('Cut');find('Copy');find('Select all')
    click('Select all');find('1 selected');screenshot('selection')
    click('Copy')
    click('Folders');click('Original folders');click('Paste…')
    click('Destination');click('Paste here')
    target=Path('/tmp/videolibrary-fixtures/Destination')/NAME
    for _ in range(60):
        if target.exists():break
        time.sleep(1)
    assert target.read_bytes()==(Path('/tmp/videolibrary-fixtures/Movies')/NAME).read_bytes()
    print('PASS: Select all in Videos -> shared Copy -> Paste from Original folders')
    click('Videos');click(NAME,long=True);click('Remove / Hide')
    click('File Explorer');click('Movies');find(NAME);screenshot('hidden-in-explorer')
    click('Settings');click('Unhide files and folders');find('Hidden files and folders');click('Unhide all (1)')
    find('No hidden items.');screenshot('unhide')
    print('PASS: hidden video stays browsable in File Explorer and can be restored in Settings')
    click('Folders');click('Custom folders');click('+ Create')
    enter_text('Cruise');click('Create');find('Cruise')
    click('Cruise');click(NAME,long=True);click('Select all');find('2 selected');click('Done')
    print('PASS: automatic custom folder matches filenames and supports Select all')
    # Put many live, unindexed files before the failed video in the real listing.
    for i in range(80):
        (target.parent/f'A{i:03}.txt').write_text('fixture')
    click('Scan');click(NAME);click('Open folder in File Explorer')
    find('Target file: '+NAME);screenshot('exact-file-jump')
    labels=[n.get('text','') for n in tree().iter('node')]
    assert 'A000.txt' not in labels, 'Shortcut must scroll beyond the first page'
    print('PASS: failed-thumbnail shortcut scrolls to the exact file and highlights it')
    click('Settings');click('Floating bottom bar');click('Folders');find('Custom folders');screenshot('floating-navigation')
    # Publish codec fixtures only now so background refresh cannot change the
    # earlier one-video selection scenario. Exercise generated WMV8 and real WMV9.
    for source,name in [('/tmp/videolibrary-sample.wmv','Sample.wmv'),('/tmp/videolibrary-wmv9.wmv','WMV9.wmv')]:
        shutil.copyfile(source,'/tmp/videolibrary-fixtures/Movies/'+name)
        if name=='Sample.wmv':
            click('File Explorer');click('← Up');click('Movies')
        else:
            click('Refresh')
        adb('logcat','-c');click(name)
        for _ in range(40):
            output=adb('logcat','-d','-s','SmbWmv:I','*:S').decode(errors='replace')
            if 'video_output_active' in output:break
            time.sleep(1)
        else:
            raise AssertionError(name+' did not produce advancing video output: '+output)
        assert 'decoder=software' in output
        Path('preview/device-wmv-log.txt').write_text(output)
        screenshot('wmv-playing' if name=='Sample.wmv' else 'wmv9-playing')
        adb('shell','input','keyevent','4')
        time.sleep(1)
        print('PASS: '+name+' streams from SMB with software decoding and advancing video output')

except Exception:
    screenshot('failure')
    print('Visible labels:',[(n.get('text'),n.get('content-desc')) for n in tree().iter('node') if n.get('text') or n.get('content-desc')])
    log=adb('logcat','-d','-t','500')
    Path('preview/device-logcat.txt').write_bytes(log)
    print('\n'.join(line for line in log.decode(errors='replace').splitlines() if 'AndroidRuntime' in line or 'WorkManager' in line))
    raise
