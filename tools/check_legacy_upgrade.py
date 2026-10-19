"""Exercise the real v2 -> v3 upgrade with the last user-tested signed preview."""
import sqlite3
import subprocess
import tempfile
import time
import struct
import zlib
from pathlib import Path

APP = 'com.deepak.videolibrary.preview'
VIDEO = 'Movies/Tom Cruise - Fixture.mp4'

def adb(*args, data=None, check=True):
    return subprocess.run(['adb', *args], input=data, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, check=check).stdout

def shell(command, **kwargs):
    return adb('shell', '-T', command, **kwargs)

def read(path):
    return adb('exec-out', 'run-as', APP, 'cat', path)

def write(path, data):
    shell(f"run-as {APP} sh -c 'cat > {path}'", data=data)

def launch():
    shell(f'am start -n {APP}/com.deepak.videolibrary.MainActivity')

def stop():
    shell(f'am force-stop {APP}')

def database(folder):
    target = Path(folder)/'library.db'
    target.write_bytes(read('databases/library.db'))
    for suffix in ('-wal', '-shm'):
        data=adb('exec-out','run-as',APP,'cat','databases/library.db'+suffix,check=False)
        if data:
            Path(str(target)+suffix).write_bytes(data)
    return sqlite3.connect(target)

adb('install','preview/legacy/update.apk')
launch()
for _ in range(30):
    if b'yes' in shell(f"run-as {APP} sh -c 'test -f databases/library.db && echo yes'",check=False):
        break
    time.sleep(1)
else:
    raise AssertionError('Legacy app did not create its library')
# File creation precedes Room's first schema transaction on a cold emulator.
# Wait for that transaction, rather than stopping as soon as an empty file exists.
time.sleep(5)
stop()
with tempfile.TemporaryDirectory() as folder:
    db=database(folder)
    version=db.execute('PRAGMA user_version').fetchone()[0]
    assert version==2, f'Expected legacy schema 2, got {version}'
    db.execute('PRAGMA wal_checkpoint(TRUNCATE)')
    db.execute('PRAGMA journal_mode=DELETE')
    db.execute('INSERT INTO custom_folders(name) VALUES (?)',('Favourites',))
    db.execute('INSERT INTO videos(id,relativePath,name,parentDir,size,modified,groupKey,customGroup,thumbStatus,lastSeenSession,addedAt) VALUES (?,?,?,?,?,?,?,?,?,?,?)',
               (9001,VIDEO,VIDEO.split('/')[-1],'Movies',2097152,1,'Tom Cruise','Favourites','DONE',0,1))
    db.commit()
    db.close()
    shell(f"run-as {APP} sh -c 'rm -f databases/library.db-wal databases/library.db-shm; mkdir -p files/thumbs'")
    write('databases/library.db',(Path(folder)/'library.db').read_bytes())

def chunk(kind, data):
    return struct.pack('!I',len(data))+kind+data+struct.pack('!I',zlib.crc32(kind+data)&0xffffffff)
png=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('!2I5B',1,1,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(b'\x00\xff\x00\x00'))+chunk(b'IEND',b'')
write('files/thumbs/9001.jpg',png)
write('shared_prefs/smb_settings.xml',b'<?xml version="1.0" encoding="utf-8"?><map><string name="host">10.0.2.2</string><string name="share">fixtures</string><string name="root"></string><string name="user"></string><boolean name="save_password" value="false" /></map>')
write('shared_prefs/player_prefs.xml',f'<?xml version="1.0" encoding="utf-8"?><map><long name="resume_path_{VIDEO}" value="60000" /><long name="duration_path_{VIDEO}" value="120000" /><float name="hold_speed" value="1.75" /></map>'.encode())
adb('install','-r','preview/base.apk')
launch()
for _ in range(40):
    if b'database_history_imported_v3' in adb('exec-out','run-as',APP,'cat','shared_prefs/player_prefs.xml',check=False):
        break
    time.sleep(1)
else:
    raise AssertionError('Database history migration did not finish')
stop()
with tempfile.TemporaryDirectory() as folder:
    db=database(folder)
    assert db.execute('PRAGMA user_version').fetchone()[0]==3
    assert db.execute('SELECT id,thumbStatus,customGroup FROM videos WHERE relativePath=?',(VIDEO,)).fetchone()==(9001,'DONE','Favourites')
    assert db.execute('SELECT positionMs,durationMs FROM playback_state WHERE connectionKey=? AND path=?',('10.0.2.2/fixtures',VIDEO)).fetchone()==(60000,120000)
    db.close()
assert read('files/thumbs/9001.jpg')==png
assert b'hold_speed' in read('shared_prefs/player_prefs.xml')
assert b'resume_path_' not in read('shared_prefs/player_prefs.xml')
print('PASS: real preview upgrade preserves indexed IDs, artwork, assignments, settings and migrates resume history')
