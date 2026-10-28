"""Install the shrunk Preview over the tested debug Preview and browse the fixture share."""
import subprocess,time,re,xml.etree.ElementTree as ET
from pathlib import Path
APP='com.deepak.videolibrary.preview'
def adb(*args):return subprocess.check_output(['adb',*args],timeout=30)
def tree():
    for _ in range(5):
        try:
            adb('shell','uiautomator','dump','/sdcard/optimized-ui.xml')
            return ET.fromstring(adb('exec-out','cat','/sdcard/optimized-ui.xml'))
        except (ET.ParseError,subprocess.SubprocessError):time.sleep(1)
    raise AssertionError('No optimized UI hierarchy')
def find(label):
    end=time.monotonic()+30
    while time.monotonic()<end:
        for n in tree().iter('node'):
            if label in n.get('text','').splitlines() or n.get('content-desc')==label:return n
        time.sleep(1)
    raise AssertionError('Optimized UI missing '+label)
def click(label):
    n=find(label);x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
    adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(1)
adb('shell','am','force-stop',APP)
adb('install','-r','optimized/update.apk')
adb('logcat','-c')
adb('shell','am','start','-n',APP+'/com.deepak.videolibrary.MainActivity')
for _ in range(5):
    nodes=list(tree().iter('node'))
    got=next((n for n in nodes if n.get('text')=='Got it'),None)
    if got is not None and any(n.get('text')=="What's new" for n in nodes):
        x1,y1,x2,y2=map(int,re.findall(r'\d+',got.get('bounds')));adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(1)
    else:break
find('Virtual folders')
click('Settings');click('Appearance');find('Theme: Dark grey')
click('Files');click('Movies');find('Tom Cruise - Fixture.mp4')
Path('preview/device-optimized.png').write_bytes(adb('exec-out','screencap','-p'))
log=adb('logcat','-d').decode(errors='replace')
assert 'FATAL EXCEPTION' not in log,log[-6000:]
print('PASS: optimized Preview updates in place, keeps theme/index/settings and browses authenticated SMB')
