import pathlib
import subprocess
import tempfile
import shutil
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
MERGER = ROOT / ".github/scripts/merge-target.sh"


class MergeTest(unittest.TestCase):
    def test_no_update_clean_conflict_and_resume(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            def run(*cmd, cwd=root, check=True):
                result = subprocess.run([shutil.which(cmd[0]), *cmd[1:]], cwd=cwd, capture_output=True, text=True)
                if check and result.returncode:
                    raise AssertionError(f"{cmd[0]} failed: {result.stderr} {result.stdout}")
                return result.stdout.strip()
            def git(*args, cwd=root):
                return run("git", *args, cwd=cwd)
            def commit(path, name, data):
                (path / name).write_text(data + "\n", encoding="utf-8")
                git("add", name, cwd=path)
                git("commit", "-m", data, cwd=path)
                return git("rev-parse", "HEAD", cwd=path)
            git("init", "-b", "main")
            git("config", "user.name", "test")
            git("config", "user.email", "test@localhost")
            base = commit(root, "shared", "base")
            upstream = root / "upstream"
            git("clone", ".", str(upstream))
            git("config", "user.name", "upstream", cwd=upstream)
            git("config", "user.email", "upstream@localhost", cwd=upstream)
            git("remote", "add", "upstream", str(upstream))
            git("switch", "-c", "codex/sync-upstream")
            run("bash", str(MERGER), base)
            self.assertEqual(git("rev-parse", "HEAD"), base)
            commit(root, "shared", "custom")
            clean = commit(upstream, "official", "new")
            run("bash", str(MERGER), clean)
            self.assertEqual((root / "official").read_text().strip(), "new")
            conflict = commit(upstream, "shared", "official")
            run("bash", str(MERGER), conflict)
            self.assertEqual(git("diff", "--name-only", "--diff-filter=U"), "shared")
            self.assertEqual(git("rev-parse", "main"), base)
            commit(root, "shared", "custom plus official")
            newer = commit(upstream, "next", "again")
            run("bash", str(MERGER), newer)
            git("merge-base", "--is-ancestor", newer, "HEAD")
            self.assertEqual(git("branch", "--show-current"), "codex/sync-upstream")


class IdentityTest(unittest.TestCase):
    def test_phone_and_update_contracts(self):
        pkg = ROOT / "app/src/main/java/com/wangzan101/mika"
        def read(path): return path.read_text(encoding="utf-8")
        self.assertIn('applicationId = "com.wangzan101.mika.phonecompat"', read(ROOT / "app/build.gradle.kts"))
        self.assertIn("repos/Zephyrxxx/Mika/releases/latest", read(pkg / "about/MikaLinks.kt"))
        for check in ("requireOfficialUrl", "requireSameSigner", "archive.longVersionCode", "archive.packageName"):
            self.assertIn(check, read(pkg / "about/ApkUpdater.kt"))
        for check in ("ROLE_DIALER", "hasPhonePermissions", "defaultDialerPackage"):
            self.assertIn(check, read(pkg / "calls/CallTelecomAccess.kt"))
        self.assertIn("app.capsule.phoneControl", read(pkg / "calls/NativeCallOverlay.kt"))
        self.assertIn("onTranscript =", read(pkg / "calls/NativeCallAudio.kt"))
        self.assertIn("!state.systemPhone", read(pkg / "ui/CallSettingsScreen.kt"))
