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

def scroll_to(label, attempts=14, up=False):
    for _ in range(attempts):
        try:
            return find(label, timeout=1)
        except AssertionError:
            # Swipe the content panel, not the fixed navigation rail.
            size=adb('shell','wm','size').decode()
            width,height=map(int,re.findall(r'(\d+)x(\d+)',size)[-1])
            start,end=(.3,.8) if up else (.8,.3)
            adb('shell','input','swipe',int(width*.75),int(height*start),int(width*.75),int(height*end),350)
    raise AssertionError('Could not scroll to '+label)

def gone(label, wait=3):
    """True when no node shows [label] after a short wait (for things that must be hidden)."""
    time.sleep(wait)
    return not any(n.get('content-desc')==label or label in n.get('text','').splitlines() for n in tree().iter('node'))

def open_section(name, probe):
    """Settings keeps its open section and scroll position for the session: open [name] only if needed."""
    try:
        find(probe, timeout=2)
        return
    except AssertionError:
        pass
    try:
        find(name, timeout=1)
    except AssertionError:
        scroll_to(name, up=True)
    click(name)
    scroll_to(probe)

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
    adb('shell','settings','put','secure','immersive_mode_confirmations','confirmed')
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
    find('Connection');find('Playback');find('Library')
    # Diagnostics is the last section; it may sit below the fold on a phone.
    scroll_to('Diagnostics');scroll_to('Connection',up=True)
    screenshot('settings-categories')
    click('Connection');find('Server address');find('Share name');click('Advanced connection options');find('Domain (optional)');find('Require SMB encryption')
    # The new Artwork section adds a row, so fold Connection away before opening the next section.
    click('Connection')
    click('Playback');find('Player gestures')
    click('Appearance')
    current='Dark grey'
    for theme in ['AMOLED black','Midnight blue','Forest','Plum','Dark grey']:
        click('Theme: '+current);click(theme);find('Theme: '+theme)
        current=theme
    screenshot('dark-grey-theme')
    adb('shell','am','force-stop',APP)
    adb('shell','am','start','-n',APP+'/com.deepak.videolibrary.MainActivity')
    find('Virtual folders')  # Wait for the fresh home frame after restart.
    click('Settings');click('Appearance');find('Theme: Dark grey')
    print('PASS: five theme choices apply live and persist across restart')
    click('Folders')
    click('Original folders');find('Movies');screenshot('originals')
    # Holding a video opens its menu (the same as folders); Select starts selecting.
    click('Videos');click(NAME,long=True);click('Select')
    # Holding a video selects it; the header shows one Select all / Unselect all button and Delete.
    find('Delete selected')
    # With every video already ticked the same button reads Unselect all.
    try:
        click('Select all')
    except AssertionError:
        find('Unselect all')
    # The Videos view can include the same SMB fixture in multiple library
    # sections. Select all operates on the visible library selection, so do not
    # assume that exactly one item is indexed at this point.
    end = time.monotonic() + 20
    while time.monotonic() < end:
        if any(re.fullmatch(r'[1-9][0-9]* selected', n.get('text', ''))
               for n in tree().iter('node')):
            break
        time.sleep(.5)
    else:
        raise AssertionError('Select all did not produce a nonzero selection count')
    screenshot('selection')
    click('More selection actions');click('Copy')
    click('Folders');click('Original folders');click('Paste…')
    click('Destination');click('Paste here')
    target=Path('/tmp/videolibrary-fixtures/Destination')/NAME
    for _ in range(60):
        if target.exists():break
        time.sleep(1)
    assert target.read_bytes()==(Path('/tmp/videolibrary-fixtures/Movies')/NAME).read_bytes()
    print('PASS: Select all in Videos -> shared Copy -> Paste from Original folders')
    click('Videos');click(NAME,long=True);click('Remove / Hide')
    click('Files');click('Movies');screenshot('hidden-in-explorer')
    assert gone(NAME), 'A hidden video must not be listed in Files'
    click('Settings');open_section('Library','Unhide files and folders');click('Unhide files and folders');find('Hidden files and folders');click('Unhide all (1)')
    find('No hidden items.');screenshot('unhide')
    adb('shell','input','keyevent','4')  # Back from Hidden items to the Settings list.
    print('PASS: hidden video is left out of Files and can be restored in Settings')
    click('Folders');click('Custom folders');click('Add custom folder');click('Create filename collection')
    enter_text('Cruise');click('Create');find('Cruise')
    click('Cruise');click(NAME,long=True);click('Select');click('Select all');find('2 selected');click('Done')
    print('PASS: automatic custom folder matches filenames and supports Select all')
    click('Folders');click('Custom folders');click('Add custom folder');click('Add drive folders')
    click('Choose folder Movies');click('Done');find('Movies')
    print('PASS: live folders can be added to Custom folders')
    # Put many live, unindexed files before the failed video in the real listing.
    for i in range(80):
        (target.parent/f'A{i:03}.txt').write_text('fixture')
    click('Settings');open_section('Sync & thumbnails','View failed thumbnails');click('View failed thumbnails');scroll_to(NAME);click(NAME);click('Open folder in File Explorer')
    find('Target file: '+NAME);screenshot('exact-file-jump')
    labels=[n.get('text','') for n in tree().iter('node')]
    assert 'A000.txt' not in labels, 'Shortcut must scroll beyond the first page'
    print('PASS: failed-thumbnail shortcut scrolls to the exact file and highlights it')
    click('Settings');open_section('Appearance','Floating bottom bar');click('Floating bottom bar');click('Folders');find('Custom folders');screenshot('floating-navigation')
    # Publish codec fixtures only now so background refresh cannot change the
    # earlier one-video selection scenario. Exercise generated WMV8 and real WMV9.
    for source,name in [('/tmp/videolibrary-sample.wmv','Sample.wmv'),('/tmp/videolibrary-wmv9.wmv','WMV9.wmv')]:
        shutil.copyfile(source,'/tmp/videolibrary-fixtures/Movies/'+name)
        if name=='Sample.wmv':
            click('Files');click('Go to Share root');click('Movies')
        else:
            click('Refresh')
        adb('logcat','-c');click(name)
        for _ in range(40):
            output=adb('logcat','-d','-s','SmbWmv:I','*:S').decode(errors='replace')
            if 'video_output_active' in output:break
            time.sleep(1)
        else:
            raise AssertionError(name+' did not produce advancing video output: '+output)
        Path('preview/device-wmv-log.txt').write_text(output)
        screenshot('wmv-playing' if name=='Sample.wmv' else 'wmv9-playing')
        # Android 15 may place its one-time immersive tutorial over the player.
        # Back first dismisses that tutorial, so wait for the actual browser.
        for _ in range(10):
            nodes=list(tree().iter('node'))
            if any(n.get('text')=='Refresh' or n.get('content-desc')=='Refresh' for n in nodes):
                break
            # Use the current hierarchy directly: the system tutorial cannot
            # reliably be dismissed with Back, and may vanish before a lookup.
            got_it=next((n for n in nodes if n.get('text')=='Got it'),None)
            if got_it is not None and any(n.get('text')=='Viewing full screen' for n in nodes):
                x1,y1,x2,y2=map(int,re.findall(r'\d+',got_it.attrib['bounds']))
                adb('shell','input','tap',(x1+x2)//2,(y1+y2)//2)
            else:
                adb('shell','input','keyevent','4')
            time.sleep(1)
        find('Refresh')
        print('PASS: '+name+' streams from SMB with advancing video output')

    # Native visual review at exact logical widths, after the existing functional checks.
    click('Settings');open_section('Appearance','Sidebar');click('Sidebar')
    for width,height,density,label in [(780,1688,320,'phone-390'),(1536,2048,320,'tablet-768'),(2560,1600,320,'landscape-1280')]:
        adb('shell','wm','size',str(width)+'x'+str(height))
        adb('shell','wm','density',str(density))
        adb('shell','am','force-stop',APP)
        adb('shell','am','start','-n',APP+'/com.deepak.videolibrary.MainActivity')
        find('Custom folders')
        screenshot('redesign-'+label+'-folders')
        click('Videos');find('Search')
        screenshot('redesign-'+label+'-videos')
        click('Settings');find('Connection')
        screenshot('redesign-'+label+'-settings')
        print('PASS: native redesign renders at '+label)
    adb('shell','wm','size','2560x1600')
    adb('shell','wm','density','240')

except Exception:
    screenshot('failure')
    print('Visible labels:',[(n.get('text'),n.get('content-desc')) for n in tree().iter('node') if n.get('text') or n.get('content-desc')])
    log=adb('logcat','-d','-t','500')
    Path('preview/device-logcat.txt').write_bytes(log)
    print('\n'.join(line for line in log.decode(errors='replace').splitlines() if 'AndroidRuntime' in line or 'WorkManager' in line))
    raise
