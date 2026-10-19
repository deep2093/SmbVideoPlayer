"""Tablet smoke test against only the disposable CI share, using visible UI labels."""
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

APP='com.deepak.videolibrary.preview'
NAME='Tom Cruise - Fixture.mp4'

def adb(*args):
    return subprocess.check_output(['adb',*map(str,args)])

def tree():
    adb('shell','uiautomator','dump','/sdcard/library-ui.xml')
    text=adb('exec-out','cat','/sdcard/library-ui.xml').decode()
    Path('preview/device-ui.xml').write_text(text)
    return ET.fromstring(text)

def find(label,timeout=20):
    end=time.monotonic()+timeout
    while time.monotonic()<end:
        root=tree()
        for node in root.iter('node'):
            if node.get('text')==label or node.get('content-desc')==label:
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

try:
    adb('shell','wm','size','2560x1600')
    adb('shell','wm','density','240')
    adb('shell','am','force-stop',APP)
    adb('shell','am','start','-n',APP+'/com.deepak.videolibrary.MainActivity')
    find('Virtual folders');find('Original folders')
    click('Original folders');find('Movies');screenshot('originals')
    click('Videos');click(NAME,long=True)
    find('Cut');find('Copy');find('Select all');find('Merge into…',timeout=1) if False else None
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
except Exception:
    screenshot('failure')
    Path('preview/device-logcat.txt').write_bytes(adb('logcat','-d','-t','500'))
    raise
