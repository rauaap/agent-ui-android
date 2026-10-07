import importlib.util
import json
import os
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


bump = load("bump_version", "bump-version.py")
gitea = load("gitea_release", "gitea-release.py")


class BumpTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / "build.gradle"

    def test_only_code_changes(self):
        original = "defaultConfig {\n    versionCode = 42 // Keep this comment\n    versionName = '2.3-preview'\n}\n"
        self.path.write_text(original)
        self.path.chmod(0o640)
        self.assertEqual(bump.bump(self.path), (42, 43))
        self.assertEqual(self.path.read_text(), original.replace("= 42", "= 43"))
        self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o640)

    def test_groovy_method_syntax_and_crlf(self):
        self.path.write_bytes(b"versionCode 9\r\nversionName '1.0'\r\n")
        bump.bump(self.path)
        self.assertEqual(self.path.read_bytes(), b"versionCode 10\r\nversionName '1.0'\r\n")

    def test_rejects_missing_ambiguous_expression_and_limits_without_edits(self):
        for text in ["versionName = '1.0'\n", "versionCode = 2\nversionCode = 3\n",
                     "versionCode = getCode()\n", "versionCode = 0\n",
                     "versionCode = 2100000000\n", "versionCode = -1\n"]:
            with self.subTest(text=text):
                self.path.write_text(text)
                with self.assertRaises(ValueError):
                    bump.bump(self.path)
                self.assertEqual(self.path.read_text(), text)

    def test_last_available_code(self):
        self.path.write_text("versionCode = 2099999999\n")
        self.assertEqual(bump.bump(self.path), (2099999999, 2100000000))


class LocalReleaseTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / "app").mkdir()
        self.gradle = self.root / "app/build.gradle"
        self.gradle.write_text("versionCode = 42\nversionName = '2.3-preview'\n")
        self.original = self.gradle.read_bytes()
        tools = self.root / "bin"
        tools.mkdir()
        # A fake Gradle models the metadata contract; real version derivation
        # lives in release.gradle and is tested separately with actual tooling.
        (tools / "gradle").write_text("#!/usr/bin/env python3\n" + '''
import json
from pathlib import Path
import re
import sys
Path('gradle-args').write_text(' '.join(sys.argv[1:]))
text = Path('app/build.gradle').read_text()
code = int(re.search(r'versionCode = (\\d+)', text)[1])
base = re.search(r"versionName = '([^']+)'", text)[1]
out = Path('app/build/outputs/apk/release')
out.mkdir(parents=True, exist_ok=True)
(out / 'app-release-unsigned.apk').write_bytes(b'unsigned')
(out / 'output-metadata.json').write_text(json.dumps({
    'applicationId': 'com.example.app',
    'elements': [{'versionCode': code, 'versionName': f'{base}.{code}'}],
}, indent=2))
''')
        (tools / "gradle").chmod(0o755)
        android = self.root / "android/build-tools/36.0.0"
        android.mkdir(parents=True)
        (android / "apksigner").write_text("#!/usr/bin/env bash\n" + '''
if [[ $* == *--print-certs* ]]; then
    echo "Signer #1 certificate SHA-256 digest: ${TEST_CERT}"
fi
''')
        (android / "apksigner").chmod(0o755)
        self.env = {**os.environ, "ANDROID_HOME": str(self.root / "android"),
                    "PATH": f"{tools}:{os.environ['PATH']}", "TEST_CERT": "a" * 64}

    def run_step(self, step, check=True):
        return subprocess.run(["bash", str(ROOT / "scripts/release.sh"), step],
                              cwd=self.root, env=self.env, check=check,
                              capture_output=True, text=True)

    def build_and_sign(self):
        self.run_step("build")
        (self.root / "build/release/signed/app.apk").write_bytes(b"signed")

    def test_build_and_rebuild_do_not_bump_or_use_counter(self):
        self.build_and_sign()
        self.run_step("finish")
        apk = self.root / "dist/com.example.app-2.3-preview.42.apk"
        self.assertEqual(apk.read_bytes(), b"signed")
        self.assertEqual((self.root / "build/release/info").read_text(),
                         "com.example.app\n2.3-preview.42\n42\n")
        self.assertNotIn("-PreleaseVersionCode", (self.root / "gradle-args").read_text())
        self.assertEqual(self.gradle.read_bytes(), self.original)
        self.assertFalse((self.root / ".last-version-code").exists())
        self.build_and_sign()
        (self.root / "build/release/signed/app.apk").write_bytes(b"rebuilt")
        self.run_step("finish")
        self.assertEqual(apk.read_bytes(), b"rebuilt")
        self.assertEqual(self.gradle.read_bytes(), self.original)

    def test_certificate_mismatch_does_not_replace_local_apk(self):
        self.build_and_sign()
        self.run_step("finish")
        self.env["TEST_CERT"] = "b" * 64
        self.build_and_sign()
        (self.root / "build/release/signed/app.apk").write_bytes(b"wrong key")
        result = self.run_step("finish", check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((self.root / "dist/com.example.app-2.3-preview.42.apk").read_bytes(), b"signed")
        self.assertEqual(self.gradle.read_bytes(), self.original)

    def test_failed_build_does_not_bump(self):
        (self.root / "bin/gradle").write_text("#!/usr/bin/env bash\nexit 1\n")
        self.assertNotEqual(self.run_step("build", check=False).returncode, 0)
        self.assertEqual(self.gradle.read_bytes(), self.original)


class FakeAPI:
    def __init__(self):
        self.head = "abc"
        self.tag = None
        self.release = None
        self.assets = []
        self.calls = []

    def optional(self, path):
        self.calls.append(("GET", path, {}))
        return self.tag if path.startswith("/tags/") else self.release

    def items(self, path):
        return iter(self.assets)

    def request(self, method, path, **kwargs):
        self.calls.append((method, path, kwargs))
        if method == "GET" and path == "/branches/main":
            return {"commit": {"id": self.head}}
        if method == "POST" and path == "/releases":
            self.tag = {"commit": {"sha": kwargs["payload"]["target_commitish"]}}
            self.release = {"id": 7, "draft": True}
            return self.release
        if method == "PATCH":
            self.release["draft"] = False
            return {"html_url": "https://example.com/releases/build-42"}
        return {}


class GiteaTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.cwd = Path.cwd()
        self.addCleanup(self.tmp.cleanup)
        self.addCleanup(os.chdir, self.cwd)
        os.chdir(self.tmp.name)
        Path("app").mkdir()
        Path("app/build.gradle").write_text("versionCode = 42\nversionName = '2.3-preview'\n")
        Path("release-cert.sha256").write_text("a" * 64 + "\n")
        Path("signing").mkdir()
        Path("signing/signing.properties").touch()
        self.env = patch.dict(os.environ, {
            "SIGNING_DIR": str(Path("signing").resolve()), "GITEA_RELEASE_TOKEN": "secret",
        })
        self.env.start()
        self.addCleanup(self.env.stop)
        self.api = FakeAPI()

    def fake_build(self, *args, **kwargs):
        self.assertNotIn("GITEA_RELEASE_TOKEN", kwargs["env"])
        self.assertEqual(args[0][:2], ["make", "release"])
        Path("build/release").mkdir(parents=True, exist_ok=True)
        Path("build/release/info").write_text("com.example.app\n2.3-preview.42\n42\n")
        Path("dist").mkdir(exist_ok=True)
        Path("dist/com.example.app-2.3-preview.42.apk").write_bytes(b"signed apk")

    def run_release(self, build=None):
        with patch.object(gitea.subprocess, "run", side_effect=build or self.fake_build):
            gitea.build_and_publish(self.api, "abc", "repo")

    def writes(self):
        return [(method, path) for method, path, _ in self.api.calls if method != "GET"]

    def test_publishes_committed_code_without_allocating(self):
        original = Path("app/build.gradle").read_bytes()
        self.run_release()
        self.assertEqual(self.writes(), [
            ("POST", "/releases"),
            ("POST", "/releases/7/assets?name=com.example.app-2.3-preview.42.apk"),
            ("PATCH", "/releases/7"),
        ])
        creation = next(kwargs["payload"] for method, path, kwargs in self.api.calls
                        if method == "POST" and path == "/releases")
        self.assertEqual(creation["tag_name"], "build-42")
        self.assertEqual(creation["name"], "2.3-preview.42")
        self.assertEqual(Path("app/build.gradle").read_bytes(), original)
        self.assertFalse(Path(".last-version-code").exists())

    def test_published_version_is_untouched(self):
        self.api.tag = {"commit": {"sha": "abc"}}
        self.api.release = {"id": 7, "draft": False}
        self.run_release()
        self.assertEqual(self.writes(), [])

    def test_reused_code_for_different_commit_fails_without_writes(self):
        self.api.tag = {"commit": {"sha": "older"}}
        self.api.release = {"id": 7, "draft": False}
        with self.assertRaises(gitea.ReleaseError):
            self.run_release()
        self.assertEqual(self.writes(), [])

    def test_release_without_tag_fails_closed(self):
        self.api.release = {"id": 7, "draft": False}
        with self.assertRaises(gitea.ReleaseError):
            self.run_release()
        self.assertEqual(self.writes(), [])

    def test_failed_build_does_not_create_a_release(self):
        def failed_build(*args, **kwargs):
            raise subprocess.CalledProcessError(1, "make release")
        with self.assertRaises(subprocess.CalledProcessError):
            self.run_release(failed_build)
        self.assertEqual(self.writes(), [])

    def test_same_commit_draft_retry_replaces_only_unpublished_assets(self):
        self.api.tag = {"commit": {"sha": "abc"}}
        self.api.release = {"id": 7, "draft": True}
        self.api.assets = [{"id": 8}]
        self.run_release()
        self.assertEqual(self.writes()[0], ("DELETE", "/releases/7/assets/8"))
        self.assertEqual(self.writes()[-1], ("PATCH", "/releases/7"))

    def test_superseded_queued_push_does_not_build(self):
        self.api.head = "newer"
        with patch.object(gitea.subprocess, "run") as build:
            gitea.build_and_publish(self.api, "abc", "repo")
        build.assert_not_called()
        self.assertEqual(self.writes(), [])

    def test_push_during_build_does_not_upload(self):
        def build(*args, **kwargs):
            self.fake_build(*args, **kwargs)
            self.api.head = "newer"
        self.run_release(build)
        self.assertEqual(self.writes(), [])

    def test_push_during_upload_leaves_draft_unpublished(self):
        def upload(*args):
            self.api.head = "newer"
        with patch.object(gitea, "upload", side_effect=upload) as attachment:
            self.run_release()
        attachment.assert_called_once()
        self.assertTrue(self.api.release["draft"])
        self.assertEqual(self.writes(), [("POST", "/releases")])

    def test_upload_failure_leaves_draft_unpublished(self):
        with patch.object(gitea, "upload", side_effect=gitea.ReleaseError("upload failed")):
            with self.assertRaises(gitea.ReleaseError):
                self.run_release()
        self.assertTrue(self.api.release["draft"])
        self.assertEqual(self.writes(), [("POST", "/releases")])

    def test_missing_certificate_fails_before_build_or_publication(self):
        Path("release-cert.sha256").unlink()
        with patch.object(gitea.subprocess, "run") as build:
            with self.assertRaises(gitea.ReleaseError):
                gitea.build_and_publish(self.api, "abc", "repo")
        build.assert_not_called()
        self.assertEqual(self.writes(), [])

    def test_api_failure_is_not_treated_as_superseded_or_absent(self):
        with patch.object(self.api, "request", side_effect=gitea.APIError("GET", "/branches/main", 503)):
            with self.assertRaises(gitea.APIError):
                self.run_release()
        self.assertEqual(self.writes(), [])

    def test_optional_only_swallows_404(self):
        api = gitea.API("https://example.com", "owner/repo", "secret")
        with patch.object(api, "request", side_effect=gitea.APIError("GET", "/tags/build-42", 404)):
            self.assertIsNone(api.optional("/tags/build-42"))
        with patch.object(api, "request", side_effect=gitea.APIError("GET", "/tags/build-42", 403)):
            with self.assertRaises(gitea.APIError):
                api.optional("/tags/build-42")


if __name__ == "__main__":
    unittest.main()
