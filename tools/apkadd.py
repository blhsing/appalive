#!/usr/bin/env python3
"""Add files to an existing APK/zip. Used instead of `zip`, which Git Bash
on Windows does not ship.

    python tools/apkadd.py out.apk classes.dex=build/dex/classes.dex
"""

import os
import sys
import zipfile


def main(argv):
    if len(argv) < 3:
        sys.stderr.write(__doc__)
        return 2
    apk = argv[1]
    if not os.path.isfile(apk):
        sys.stderr.write("apkadd: no such archive: %s\n" % apk)
        return 1

    with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as zf:
        existing = set(zf.namelist())
        for spec in argv[2:]:
            if "=" not in spec:
                sys.stderr.write("apkadd: expected NAME=PATH, got %r\n" % spec)
                return 2
            name, path = spec.split("=", 1)
            if not os.path.isfile(path):
                sys.stderr.write("apkadd: no such file: %s\n" % path)
                return 1
            if name in existing:
                sys.stderr.write("apkadd: %s already present in %s\n" % (name, apk))
                return 1
            zf.write(path, name)
            print("  added %-20s %8d bytes" % (name, os.path.getsize(path)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
