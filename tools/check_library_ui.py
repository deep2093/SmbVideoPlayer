"""Tablet smoke test against only the disposable CI share, using visible UI labels."""
import re
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
    for _ in range(5):
        adb('shell','uiautomator','dump','/sdcard/library-ui.xml')
        text=adb('exec-out','cat','/sdcard/library-ui.xml').decode()
        try:
            root=ET.fromstring(text)
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
    adb('shell','input','keyevent','111')

try:
    adb('shell','wm','size','2560x1600')
    adb('shell','wm','density','240')
    adb('shell','am','force-stop',APP)
    adb('shell','am','start','-n',APP+'/com.deepak.videolibrary.MainActivity')
    find('Virtual folders');find('Original folders')
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
    click('File Explorer');click('← Up');click('Movies');click('Sample.wmv')
    find('WMV playback active',timeout=40);screenshot('wmv-playing')
    adb('shell','input','keyevent','4')
    print('PASS: WMV/ASF with WMA audio plays from SMB through the VLC compatibility player')
except Exception:
    screenshot('failure')
    print('Visible labels:',[(n.get('text'),n.get('content-desc')) for n in tree().iter('node') if n.get('text') or n.get('content-desc')])
    log=adb('logcat','-d','-t','500')
    Path('preview/device-logcat.txt').write_bytes(log)
    print('\n'.join(line for line in log.decode(errors='replace').splitlines() if 'AndroidRuntime' in line or 'WorkManager' in line))
    raise
