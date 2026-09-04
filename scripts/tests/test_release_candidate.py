import importlib.util
import io
import json
import os
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest import mock
import subprocess


SCRIPT = Path(__file__).resolve().parents[1] / "release_candidate.py"
SPEC = importlib.util.spec_from_file_location("release_candidate", SCRIPT)
release_candidate = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(release_candidate)


class Args:
    pass


class ReleaseCandidateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=os.environ.get("ARES_TEST_TEMP"))
        self.root = Path(self.temp.name) / "candidate"
        (self.root / "release-assets").mkdir(parents=True)
        (self.root / "final-ares-repository").mkdir()
        (self.root / "release-assets" / "Studio.msi").write_bytes(b"msi")
        (self.root / "final-ares-repository" / "core.pom").write_bytes(b"pom")
        self.manifest = self.root / "release-candidate.json"

        args = Args()
        args.root = self.root
        args.manifest = self.manifest
        args.repository = "ARES-23247/ARES-Robotics"
        args.source_tree = "a" * 40
        args.source_commit = "b" * 40
        args.event = "pull_request"
        args.workflow_ref = "ARES-23247/ARES-Robotics/.github/workflows/build-distributions.yml@refs/pull/1/merge"
        args.run_id = 123
        args.run_attempt = 1
        args.ares_version = "14.0.0"
        args.studio_version = "4.0.0"
        release_candidate.seal(args)

    def tearDown(self):
        self.temp.cleanup()

    def verify(self, **overrides):
        args = Args()
        args.root = self.root
        args.manifest = self.manifest
        args.expected_repository = overrides.get("repository", "ARES-23247/ARES-Robotics")
        args.expected_tree = overrides.get("tree", "a" * 40)
        args.expected_event = overrides.get("event", "pull_request")
        args.expected_run_id = overrides.get("run_id", 123)
        args.expected_run_attempt = overrides.get("run_attempt", 1)
        args.expected_ares_version = overrides.get("ares", "14.0.0")
        args.expected_studio_version = overrides.get("studio", "4.0.0")
        return release_candidate.verify(args)

    def provenance(self, actual_commit="b" * 40, actual_tree="a" * 40):
        args = Args()
        args.manifest = self.manifest
        args.archive = Path(self.temp.name) / "candidate.tar.gz"
        args.expected_repository = "ARES-23247/ARES-Robotics"
        args.expected_tree = "a" * 40
        def command(argv, **kwargs):
            if argv[0] == "gh":
                identity_flags = {"--cert-identity", "--cert-identity-regex", "--signer-repo", "--signer-workflow"}
                self.assertEqual({"--cert-identity"}, identity_flags.intersection(argv))
                self.assertEqual(
                    "https://github.com/ARES-23247/ARES-Robotics/.github/workflows/build-distributions.yml@refs/pull/1/merge",
                    argv[argv.index("--cert-identity") + 1],
                )
                self.assertEqual("b" * 40, argv[argv.index("--signer-digest") + 1])
                self.assertIn("--deny-self-hosted-runners", argv)
                if argv[argv.index("--source-digest") + 1] != actual_commit:
                    raise subprocess.CalledProcessError(1, argv)
            return mock.Mock(stdout=actual_tree + "\n")
        with mock.patch.object(release_candidate.subprocess, "run", side_effect=command):
            release_candidate.verify_provenance(args)

    def test_actual_attested_source_cannot_differ_from_claimed_commit(self):
        with self.assertRaises(subprocess.CalledProcessError):
            self.provenance(actual_commit="c" * 40)

    def test_actual_attested_tree_cannot_differ_from_claimed_tree(self):
        with self.assertRaisesRegex(release_candidate.CandidateError, "attested source Git tree"):
            self.provenance(actual_tree="c" * 40)

    def test_verified_commit_may_differ_from_main_when_trees_match(self):
        self.provenance()

    def test_other_workflow_cannot_supply_a_candidate(self):
        data = json.loads(self.manifest.read_text())
        data["source"]["workflowRef"] = "ARES-23247/ARES-Robotics/.github/workflows/evil.yml@refs/heads/main"
        self.manifest.write_text(json.dumps(data))
        with self.assertRaisesRegex(release_candidate.CandidateError, "trusted builder"):
            self.provenance()

    def test_sealed_candidate_verifies(self):
        manifest = self.verify()
        self.assertEqual(2, len(manifest["files"]))

    def test_file_tampering_is_rejected(self):
        (self.root / "release-assets" / "Studio.msi").write_bytes(b"changed")
        with self.assertRaisesRegex(release_candidate.CandidateError, "changed=.*Studio.msi"):
            self.verify()

    def test_extra_file_is_rejected(self):
        (self.root / "release-assets" / "unexpected.txt").write_text("extra", encoding="utf-8")
        with self.assertRaisesRegex(release_candidate.CandidateError, "extra=.*unexpected.txt"):
            self.verify()

    def test_wrong_tree_is_rejected(self):
        with self.assertRaisesRegex(release_candidate.CandidateError, "tree mismatch"):
            self.verify(tree="c" * 40)

    def test_wrong_run_is_rejected(self):
        with self.assertRaisesRegex(release_candidate.CandidateError, "run ID mismatch"):
            self.verify(run_id=456)

    def test_wrong_run_attempt_is_rejected(self):
        with self.assertRaisesRegex(release_candidate.CandidateError, "run attempt mismatch"):
            self.verify(run_attempt=2)

    def test_manifest_identity_tampering_is_rejected(self):
        data = json.loads(self.manifest.read_text(encoding="utf-8"))
        data["source"]["event"] = "push"
        self.manifest.write_text(json.dumps(data), encoding="utf-8")
        with self.assertRaisesRegex(release_candidate.CandidateError, "unsupported candidate event"):
            self.verify()

    def test_archive_path_traversal_is_rejected(self):
        archive = Path(self.temp.name) / "candidate.tar.gz"
        with tarfile.open(archive, "w:gz") as bundle:
            payload = b"escape"
            member = tarfile.TarInfo("../outside.txt")
            member.size = len(payload)
            bundle.addfile(member, io.BytesIO(payload))
        args = Args()
        args.archive = archive
        args.root = Path(self.temp.name) / "extract"
        with self.assertRaisesRegex(release_candidate.CandidateError, "escapes extraction root"):
            release_candidate.extract(args)


if __name__ == "__main__":
    unittest.main()
