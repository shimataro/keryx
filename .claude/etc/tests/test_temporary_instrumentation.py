"""Exercise the documented instrumentation marker check in disposable Git repositories."""

import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


POLICY = Path(__file__).resolve().parents[1] / "temporary-instrumentation.md"
MARKER = "TEMP-INSTRUMENTATION"


def documented_marker_check():
    section = POLICY.read_text(encoding="utf-8").split("## 3. Rules for the temporary code", 1)[1]
    section = section.split("## 4.", 1)[0]
    match = re.search(r"```bash\s*\n(.*?)\n\s*```", section, re.DOTALL)
    if match is None:
        raise AssertionError("The marker check's bash block is missing from section 3")
    return match.group(1).strip()



def documented_snapshot_command():
    section = POLICY.read_text(encoding="utf-8").split("## 4. Re-measuring after the fix", 1)[1]
    match = re.search(r"```bash\s*\n(.*?)\n\s*```", section, re.DOTALL)
    if match is None:
        raise AssertionError("The snapshot command's bash block is missing from section 4")
    return match.group(1)

class MarkerCheckTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.repo = Path(self.temp_dir.name)
        self.env = os.environ.copy()
        self.env.update(GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM="1")
        self.git("init", "--quiet")

    def git(self, *args):
        return subprocess.run(
            ["git", *args], cwd=self.repo, env=self.env, check=True, capture_output=True, text=True
        )

    def write(self, name, content):
        path = self.repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def check_markers(self):
        return subprocess.run(
            ["bash", "-c", documented_marker_check()],
            cwd=self.repo,
            env=self.env,
            capture_output=True,
            text=True,
        )

    def test_finds_markers_in_tracked_changes_and_untracked_files(self):
        self.write("tracked.kt", "original\n")
        self.git("add", "tracked.kt")
        self.write("tracked.kt", f"original\n// {MARKER}: probe\n")
        self.write("nested/untracked test.kt", f"// {MARKER}: test\n")

        result = self.check_markers()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            set(result.stdout.splitlines()),
            {f"tracked.kt:2:// {MARKER}: probe", f"nested/untracked test.kt:1:// {MARKER}: test"},
        )

    def test_finds_markers_in_unmodified_tracked_files(self):
        self.write("committed.kt", f"// {MARKER}\n")
        self.git("add", "committed.kt")

        result = self.check_markers()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, f"committed.kt:1:// {MARKER}\n")

    def test_excludes_policy_directory_but_not_similarly_named_directory(self):
        self.write(".claude/policy.md", f"{MARKER}\n")
        self.git("add", ".claude/policy.md")
        self.write(".claude/nested/untracked.md", f"{MARKER}\n")
        self.write(".claude-other/probe.kt", f"{MARKER}\n")

        result = self.check_markers()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, f".claude-other/probe.kt:1:{MARKER}\n")

    def test_ignored_untracked_files_do_not_appear(self):
        self.write(".gitignore", "ignored/\n")
        self.write("ignored/probe.kt", f"{MARKER}\n")
        self.write("visible.kt", f"{MARKER}\n")

        result = self.check_markers()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, f"visible.kt:1:{MARKER}\n")

    def test_removing_instrumentation_leaves_no_matches(self):
        self.write("tracked.kt", "original\n")
        self.git("add", "tracked.kt")
        self.write("tracked.kt", f"original\n// {MARKER}\n")
        self.write("probe.kt", f"// {MARKER}\n")
        self.assertEqual(self.check_markers().returncode, 0)

        self.write("tracked.kt", "original\n")
        (self.repo / "probe.kt").unlink()
        result = self.check_markers()

        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertEqual(result.stdout, "")
        self.assertEqual(self.git("status", "--porcelain").stdout, "A  tracked.kt\n")



class SnapshotTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.root = Path(self.temp_dir.name)
        self.repo = self.root / "repo"
        self.repo.mkdir()
        self.env = os.environ.copy()
        self.env.update(GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM="1")
        self.git("init", "--quiet")
        self.write("tracked.kt", "original\n")
        self.git("add", "tracked.kt")
        self.git("-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "--quiet", "-m", "base")
        self.count = 0

    def git(self, *args):
        return subprocess.run(
            ["git", *args], cwd=self.repo, env=self.env, check=True, capture_output=True, text=True
        )

    def write(self, name, content):
        path = self.repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")

    def snapshot(self):
        self.count += 1
        target = self.root / f"{self.count}.snapshot"
        result = subprocess.run(
            ["bash", "-c", documented_snapshot_command()],
            cwd=self.repo,
            env={**self.env, "SNAPSHOT": str(target)},
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.stderr, "")
        return target.read_text(encoding="utf-8")

    def test_covers_tracked_changes_and_untracked_files(self):
        self.write("tracked.kt", "original\nfix\n")
        self.write("nested/New Test.kt", "test body\n")

        snapshot = self.snapshot()

        self.assertIn("+fix", snapshot)
        self.assertIn("nested/New Test.kt", snapshot)
        self.assertIn("+test body", snapshot)

    def test_detects_a_change_left_in_an_untracked_file(self):
        self.write("tracked.kt", "original\nfix\n")
        self.write("NewTest.kt", "assertion\n")
        before = self.snapshot()

        self.write("NewTest.kt", "")
        self.assertNotEqual(self.snapshot(), before)

        self.write("NewTest.kt", "assertion\n")
        self.assertEqual(self.snapshot(), before)

    def test_ignored_files_are_excluded(self):
        self.write(".gitignore", "build/\n")
        self.git("add", ".gitignore")
        self.write("build/output.txt", "generated\n")

        self.assertEqual(self.snapshot(), self.git("diff").stdout)

    def test_without_untracked_files_it_equals_git_diff(self):
        self.write("tracked.kt", "original\nfix\n")

        self.assertEqual(self.snapshot(), self.git("diff").stdout)

if __name__ == "__main__":
    unittest.main()
