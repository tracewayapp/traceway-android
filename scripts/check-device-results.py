"""Validate the instrumentation JUnit files downloaded from this Firebase test matrix."""

import sys
import xml.etree.ElementTree as ET
from pathlib import Path

required_class = "com.tracewayapp.traceway.wireframe.ViewWireframeInstrumentedTest"
required_method = "serializedNativePhotoFallsBackToLayoutAcrossSnapshots"
tested_image_budget = False
executed = 0
for report in Path(sys.argv[1]).rglob("test_result_*.xml"):
    root = ET.parse(report).getroot()
    if next(root.iter("failure"), None) is not None or next(root.iter("error"), None) is not None:
        sys.exit(f"{report} contains a failing device test")
    for suite in root.iter("testsuite"):
        if int(suite.get("failures", 0)) or int(suite.get("errors", 0)):
            sys.exit(f"{report} reports a failing test suite")
    for case in root.iter("testcase"):
        if case.find("skipped") is None:
            executed += 1
            if case.get("classname") == required_class and case.get("name") == required_method:
                tested_image_budget = True
if not executed:
    sys.exit("Firebase produced no executed device tests")
if not tested_image_budget:
    sys.exit(f"Firebase did not run {required_class}.{required_method}")
print(f"Firebase reported {executed} passing device tests")
