#!/usr/bin/env python3
"""Fail if any .so inside an APK/AAR keeps 4 KB LOAD alignment (Android 17 requires 16 KB)."""
import struct, sys, zipfile

def load_aligns(blob):
    if blob[:4] != b"ELF":
        return []
    phoff = struct.unpack_from("<Q", blob, 0x20)[0]
    entsize = struct.unpack_from("<H", blob, 0x36)[0]
    count = struct.unpack_from("<H", blob, 0x38)[0]
    out = []
    for i in range(count):
        off = phoff + i * entsize
        fields = struct.unpack_from("<IIQQQQQQ", blob, off)
        if fields[0] == 1:
            out.append(fields[7])
    return out

bad = 0
for path in sys.argv[1:]:
    with zipfile.ZipFile(path) as z:
        for name in z.namelist():
            if not name.endswith(".so"):
                continue
            aligns = load_aligns(z.read(name))
            ok = aligns and all(a >= 16384 for a in aligns)
            print(f"{'OK  ' if ok else 'FAIL'} {path}:{name} LOAD align={[hex(a) for a in aligns]}")
            bad += 0 if ok else 1
sys.exit(1 if bad else 0)
