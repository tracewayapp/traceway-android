"""Checks that a minified APK keeps what OpenTelemetry reads by reflection (finding android-002).

OpenTelemetry's OTLP marshaler reads a resource's entities through
io.opentelemetry.sdk.resources.internal.EntityUtil, which looks the package-private
methods up by name: Resource.getDeclaredMethod("getEntities") and
ResourceBuilder.getDeclaredMethod("addEntity", Entity.class). R8 inlines or renames
them unless a rule keeps them, and every OTLP export then fails ("Error calling
getEntities on Resource"): a minified app sends no logs, spans or metrics.

Usage: check_minified_otel.py --apk APP.apk --mapping mapping.txt
"""

import argparse
import glob
import os
import re
import subprocess
import sys
import tempfile
import zipfile

# (class, method) pairs looked up by name through reflection, and whether the class must be in the app.
REFLECTED = [
    ("io.opentelemetry.sdk.resources.Resource", "getEntities", True),
    ("io.opentelemetry.sdk.resources.ResourceBuilder", "addEntity", False),
]


def dexdump():
    home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not home:
        sys.exit("ANDROID_HOME is not set")
    tools = sorted(glob.glob(os.path.join(home, "build-tools", "*", "dexdump")))
    if not tools:
        sys.exit("no dexdump under " + home)
    return tools[-1]


def renamed_classes(mapping):
    """original class name -> name in the dex, from R8's mapping.txt."""
    out = {}
    with open(mapping, encoding="utf-8") as f:
        for line in f:
            m = re.match(r"^(\S+) -> (\S+):$", line.rstrip("\n"))
            if m:
                out[m.group(1)] = m.group(2)
    return out


def methods_by_class(apk, tool):
    """class name in the dex -> names of the methods it declares."""
    out = {}
    with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(apk) as z:
        for name in z.namelist():
            if not re.match(r"^classes\d*\.dex$", name):
                continue
            path = z.extract(name, tmp)
            text = subprocess.run([tool, path], capture_output=True, text=True, check=True).stdout
            current = None
            for line in text.splitlines():
                m = re.match(r"^\s*Class descriptor\s*:\s*'L(.+);'", line)
                if m:
                    current = m.group(1).replace("/", ".")
                    out.setdefault(current, set())
                    continue
                m = re.match(r"^\s*name\s*:\s*'(.+)'", line)
                if m and current is not None:
                    out[current].add(m.group(1))
    return out


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--apk", required=True)
    p.add_argument("--mapping", required=True)
    a = p.parse_args()
    renamed = renamed_classes(a.mapping)
    methods = methods_by_class(a.apk, dexdump())
    failed = False
    for cls, method, required in REFLECTED:
        dex_name = renamed.get(cls, cls)
        if dex_name not in methods:
            if required:
                print(f"FAIL {cls}: not in the app (looked for {dex_name})")
                failed = True
            else:
                print(f"ok   {cls}: removed by R8, nothing looks {method} up")
            continue
        if method in methods[dex_name]:
            print(f"ok   {cls}.{method} kept (as {dex_name}.{method})")
        else:
            print(f"FAIL {cls}.{method} is gone from {dex_name}: R8 removed or renamed it, and OpenTelemetry looks it up by name")
            failed = True
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
