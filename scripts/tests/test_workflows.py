"""Run the Android release and device-test workflow entry points against local fixtures."""

from pathlib import Path
import os
import re
import shutil
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]


def checked_in(build_file, pattern):
    """The version a build file declares; the release script rewrites the same line."""
    return re.search(pattern, (ROOT / build_file).read_text(), re.M).group(1)


SDK_VERSION = checked_in("traceway/build.gradle.kts", r'^val sdkVersion = "([^"]+)"')
PLUGIN_VERSION = checked_in("gradle-plugin/build.gradle.kts",
                            r'coordinates\("com\.tracewayapp", "traceway-symbols-plugin", "([^"]+)"\)')


class WorkflowTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.repo = Path(tmp.name) / "repo"
        self.repo.mkdir()
        self.bin = Path(tmp.name) / "bin"
        self.bin.mkdir()
        self.env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                        GITHUB_REF_NAME="main", GITHUB_REF_TYPE="branch", VERSION=SDK_VERSION)
        self.run_cmd("git", "init", "-qb", "main")
        self.run_cmd("git", "config", "user.name", "CI")
        self.run_cmd("git", "config", "user.email", "ci@example.test")
        for name in ("traceway", "gradle-plugin"):
            (self.repo / name).mkdir()
            shutil.copyfile(ROOT / name / "build.gradle.kts", self.repo / name / "build.gradle.kts")
        self.run_cmd("git", "add", ".")
        self.run_cmd("git", "commit", "-qm", "candidate")
        self.run_cmd("git", "init", "--bare", "-q", "../origin")
        self.run_cmd("git", "remote", "add", "origin", "../origin")
        self.run_cmd("git", "push", "-q", "origin", "main")
        self.mock(self.bin / "curl", 'echo "${CENTRAL_STATUS:-404}"')
        self.mock(self.repo / "gradlew", 'echo "$*" >> publish-calls')
        self.env.update(dict.fromkeys(("ORG_GRADLE_PROJECT_mavenCentralUsername",
                                       "ORG_GRADLE_PROJECT_mavenCentralPassword",
                                       "ORG_GRADLE_PROJECT_signingInMemoryKey",
                                       "ORG_GRADLE_PROJECT_signingInMemoryKeyPassword"), "fixture"))

    def mock(self, path, cmd):
        path.write_text("#!/bin/sh\n" + cmd + "\n")
        path.chmod(0o755)

    def run_cmd(self, *cmd, ok=True):
        result = subprocess.run(cmd, cwd=self.repo, env=self.env, text=True, capture_output=True)
        if ok:
            self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        else:
            self.assertNotEqual(result.returncode, 0, result.stdout)
        return result.stdout.strip()

    def release(self, kind, ok=True):
        self.run_cmd("bash", str(ROOT / "scripts/release-maven.sh"), kind, ok=ok)

    def no_change(self, kind, version, tag):
        self.env["VERSION"] = version
        sha = self.run_cmd("git", "rev-parse", "HEAD")
        self.release(kind)
        self.assertEqual(self.run_cmd("git", "rev-parse", tag + "^{}"), sha)
        self.assertEqual(self.run_cmd("git", "--git-dir=../origin", "rev-parse", tag + "^{}"), sha)
        self.env["CENTRAL_STATUS"] = "200"
        self.release(kind)
        self.assertEqual(len((self.repo / "publish-calls").read_text().splitlines()), 1)

    def test_sdk_prebumped(self):
        self.no_change("sdk", SDK_VERSION, "v" + SDK_VERSION)

    def test_plugin_prebumped(self):
        self.no_change("plugin", PLUGIN_VERSION, "plugin-v" + PLUGIN_VERSION)

    def test_resume_published_coordinates_at_confirmed_revision(self):
        # A version the checked-in build file does not have yet: the release commit sets it.
        major, minor, patch = re.match(r"(\d+)\.(\d+)\.(\d+)", SDK_VERSION).groups()
        published = f"{major}.{minor}.{int(patch) + 1}"
        self.env.update(CENTRAL_STATUS="200", VERSION=published)
        self.release("sdk", ok=False)
        self.env["PUBLISHED_SHA"] = self.run_cmd("git", "rev-parse", "HEAD")
        self.release("sdk")
        self.assertFalse((self.repo / "publish-calls").exists())
        self.assertIn(f'val sdkVersion = "{published}"', (self.repo / "traceway/build.gradle.kts").read_text())
        self.assertEqual(self.run_cmd("git", "rev-parse", f"v{published}^{{}}"), self.run_cmd("git", "rev-parse", "HEAD"))

    def test_incomplete_plugin_release_cannot_be_tagged_or_republished(self):
        self.mock(self.bin / "curl", 'case "$*" in *gradle.plugin*) echo 404 ;; *) echo 200 ;; esac')
        self.env["PUBLISHED_SHA"] = self.run_cmd("git", "rev-parse", "HEAD")
        self.release("plugin", ok=False)
        self.assertFalse((self.repo / "publish-calls").exists())
        self.assertEqual(self.run_cmd("git", "tag"), "")

    def test_central_lookup_error_blocks_publication(self):
        self.env["CENTRAL_STATUS"] = "403"
        self.release("sdk", ok=False)
        self.assertFalse((self.repo / "publish-calls").exists())
        self.assertEqual(self.run_cmd("git", "tag"), "")

    def workflow_step(self, name):
        workflow = (ROOT / ".github/workflows/instrumented-tests.yml").read_text()
        step = workflow.split("      - name: " + name + "\n")[-1].split("\n      - ")[0]
        script = textwrap.dedent(step.split("        run: |\n", 1)[1])
        return re.sub(r"\$\{\{.*?\}\}", "fixture", script)

    def test_device_and_download_failures_propagate(self):
        for cmd, name in (("gcloud", "Run on Firebase Test Lab"), ("gsutil", "Download results from GCS")):
            self.mock(self.bin / cmd, "exit 13")
            self.run_cmd("bash", "-ec", self.workflow_step(name), ok=False)

    def test_instrumentation_apk_target_is_installable_on_android14(self):
        self.env["ANDROID_HOME"] = str(self.repo / "sdk")
        aapt = self.repo / "sdk/build-tools/35.0.0/aapt"
        aapt.parent.mkdir(parents=True)
        self.mock(aapt, "printf \"targetSdkVersion:'%s'\\n\" \"$APK_TARGET\"")
        self.env["APK_TARGET"] = "21"
        script = self.workflow_step("Verify the instrumentation APK can install on Android 14")
        self.run_cmd("bash", "-ec", script, ok=False)
        self.env["APK_TARGET"] = "35"
        self.run_cmd("bash", "-ec", script)

    def test_device_results_require_executed_passing_tests(self):
        report = self.repo / "test_result_1.xml"
        command = ("python3", str(ROOT / "scripts/check-device-results.py"), str(self.repo))
        self.run_cmd(*command, ok=False)
        for body in ("", "<testcase><skipped/></testcase>", "<testcase><failure/></testcase>"):
            report.write_text("<testsuite>" + body + "</testsuite>")
            self.run_cmd(*command, ok=False)
        report.write_text("<testsuite><testcase classname='Other' name='olderApk'/></testsuite>")
        self.run_cmd(*command, ok=False)
        case = ("<testcase classname='com.tracewayapp.traceway.wireframe.ViewWireframeInstrumentedTest' "
                "name='serializedNativePhotoFallsBackToLayoutAcrossSnapshots'>")
        report.write_text("<testsuite>" + case + "<skipped/></testcase></testsuite>")
        self.run_cmd(*command, ok=False)
        report.write_text("<testsuite>" + case + "</testcase></testsuite>")
        self.run_cmd(*command)


if __name__ == "__main__":
    unittest.main()
