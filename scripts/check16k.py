#!/usr/bin/env python3
"""Fail if any .so inside an APK/AAR — or a bare .so — keeps 4 KB LOAD alignment.

Android 17 refuses to load a library whose PT_LOAD segments are not 16 KB aligned, so this
runs on every artifact we ship: the AAR, both APKs, and the standalone libgojni-<tag>.so that
goes out as a hot-update asset. Accepting a bare ELF matters because that .so is extracted
from the AAR and published as-is; checking only the archives would leave the one file users
actually download unverified.
"""
import struct
import sys
import zipfile

PAGE = 16384
ELF_MAGIC = b"\x7fELF"


def load_aligns(blob):
    if blob[:4] != ELF_MAGIC:
        return []
    phoff = struct.unpack_from("<Q", blob, 0x20)[0]
    entsize = struct.unpack_from("<H", blob, 0x36)[0]
    count = struct.unpack_from("<H", blob, 0x38)[0]
    out = []
    for i in range(count):
        off = phoff + i * entsize
        fields = struct.unpack_from("<IIQQQQQQ", blob, off)
        if fields[0] == 1:  # PT_LOAD
            out.append(fields[7])
    return out


def check(path, name, blob):
    """Report one library; returns 1 when it would not load on a 16 KB page device."""
    aligns = load_aligns(blob)
    ok = bool(aligns) and all(a >= PAGE for a in aligns)
    label = "%s:%s" % (path, name) if name else path
    print("%s %s LOAD align=%s" % ("OK  " if ok else "FAIL", label, [hex(a) for a in aligns]))
    return 0 if ok else 1


bad = 0
for path in sys.argv[1:]:
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as z:
            for name in z.namelist():
                if name.endswith(".so"):
                    bad += check(path, name, z.read(name))
        continue
    with open(path, "rb") as fh:
        blob = fh.read()
    if blob[:4] != ELF_MAGIC:
        print("FAIL %s is neither a zip nor an ELF file" % path)
        bad += 1
        continue
    bad += check(path, "", blob)

sys.exit(1 if bad else 0)
