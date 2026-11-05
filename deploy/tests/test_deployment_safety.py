"""Failure-focused deployment regressions using local files and fake AWS/HTTP clients."""

import hashlib
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import prepare_backup
import release_env
import release_state
import retention
import upload_backup
from test_release import example_values


class ReleaseStateTests(unittest.TestCase):
    @patch("release_state.urllib.request.urlopen")
    def test_stale_rerun_and_unavailable_dev_ref_are_rejected(self, urlopen):
        with tempfile.TemporaryDirectory() as directory:
            token = Path(directory) / "token"
            token.write_text("synthetic-token")
            urlopen.return_value = io.StringIO(json.dumps({"object": {"sha": "b" * 40}}))
            with self.assertRaisesRegex(ValueError, "Stale release"):
                release_state.require_latest("a" * 40, token)
            urlopen.side_effect = OSError("unavailable")
            with self.assertRaisesRegex(ValueError, "Cannot verify"):
                release_state.require_latest("b" * 40, token)
            urlopen.side_effect = None
            urlopen.return_value = io.StringIO(json.dumps({"object": {"sha": "b" * 40}}))
            release_state.require_latest("b" * 40, token)

    def test_failed_attempt_is_current_but_does_not_replace_last_success(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            first, second, third = [root / "releases" / f'{char * 40}-1-1' for char in "abc"]
            for path in (first, second, third):
                path.mkdir(parents=True)
            release_state.transition(root, first, "applying")
            release_state.transition(root, first, "succeeded")
            release_state.transition(root, second, "applying")
            release_state.transition(root, second, "failed")
            self.assertEqual((root / "current").resolve(), second)
            self.assertEqual((root / "last-successful").resolve(), first)
            self.assertEqual(json.loads((root / "deployment-state.json").read_text())["phase"], "failed")
            release_state.transition(root, third, "applying")
            release_state.transition(root, third, "succeeded")
            self.assertEqual((root / "previous").resolve(), first)
            self.assertEqual((root / "last-successful").resolve(), third)


class BackupTests(unittest.TestCase):
    def client(self):
        client = MagicMock()
        client.get_bucket_location.return_value = {"LocationConstraint": "ap-southeast-2"}
        client.get_bucket_versioning.return_value = {"Status": "Enabled"}
        client.get_public_access_block.return_value = {"PublicAccessBlockConfiguration": dict.fromkeys(
            ("BlockPublicAcls", "IgnorePublicAcls", "BlockPublicPolicy", "RestrictPublicBuckets"), True)}
        client.generate_presigned_url.side_effect = lambda operation, **kwargs: operation
        return client

    def test_delegates_only_one_encrypted_put_and_head_and_requires_bucket_protection(self):
        client = self.client()
        result = prepare_backup.prepare(client, "test-backups", "123456789012", "a" * 40 + "-1-1")
        self.assertEqual(result["headers"]["If-None-Match"], "*")
        self.assertEqual(result["headers"]["x-amz-server-side-encryption"], "AES256")
        self.assertEqual([call.args[0] for call in client.generate_presigned_url.call_args_list],
                         ["put_object", "head_object"])
        self.assertEqual(client.get_bucket_versioning.call_args.kwargs["ExpectedBucketOwner"], "123456789012")
        client.get_bucket_versioning.return_value = {"Status": "Suspended"}
        with self.assertRaisesRegex(ValueError, "versioning"):
            prepare_backup.prepare(client, "test-backups", "123456789012", "a" * 40 + "-1-1")

    def test_upload_receipt_requires_verified_size_encryption_and_version(self):
        with tempfile.TemporaryDirectory() as directory:
            dump, config = Path(directory) / "test.dump", Path(directory) / "upload.json"
            dump.write_bytes(b"synthetic-dump")
            endpoint = "https://test-backups.s3.ap-southeast-2.amazonaws.com/hanmaum/postgres/test.dump"
            config.write_text(json.dumps({"bucket": "test-backups", "key": "hanmaum/postgres/test.dump",
                                          "put": endpoint, "head": endpoint, "headers": {}}))
            reply = MagicMock()
            reply.__enter__.return_value = reply
            reply.status = 200
            reply.headers = {"Content-Length": "15", "x-amz-server-side-encryption": "AES256",
                             "x-amz-version-id": "version-1"}
            opener = MagicMock(return_value=reply)
            with self.assertRaisesRegex(ValueError, "verification failed"):
                upload_backup.upload(dump, config, opener)
            receipt = dump.with_suffix(".dump.uploaded.json")
            self.assertFalse(receipt.exists())
            reply.headers["Content-Length"] = str(dump.stat().st_size)
            upload_backup.upload(dump, config, opener)
            self.assertEqual(json.loads(receipt.read_text())["sha256"], hashlib.sha256(dump.read_bytes()).hexdigest())


class RetentionTests(unittest.TestCase):
    def test_incomplete_unreferenced_releases_are_removed_before_selecting_retained_releases(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "backups").mkdir()
            complete, missing, truncated = [root / "releases" / f'{char * 40}-1-1' for char in "abc"]
            for path in (complete, missing, truncated):
                path.mkdir(parents=True)
            (complete / ".env.prod").write_text(release_env.render(example_values()))
            (truncated / ".env.prod").write_text('BACKEND_IMAGE="unfinished')
            (root / "current").symlink_to(complete)
            removed_images = []
            retention.prune(root, set(), removed_images.append)
            self.assertEqual(set((root / "releases").iterdir()), {complete})
            self.assertEqual(removed_images, [])
            # The next attempt's image-retention pass can read every remaining release.
            with patch("retention.subprocess.check_output", return_value=""):
                retention.prune_cached_images(root)

    def test_incomplete_protected_releases_stop_cleanup_without_deleting_any_release(self):
        for reference in ("current", "last-successful", "previous", "mounted"):
            with self.subTest(reference=reference), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / "backups").mkdir()
                protected, unreferenced = [root / "releases" / f'{char * 40}-1-1' for char in "ab"]
                for path in (protected, unreferenced):
                    path.mkdir(parents=True)
                mounted = {protected} if reference == "mounted" else set()
                if reference != "mounted":
                    (root / reference).symlink_to(protected)
                with self.assertRaisesRegex(ValueError, "Protected release"):
                    retention.prune(root, mounted, lambda image: self.fail("Unexpected image removal"))
                self.assertTrue(protected.exists())
                self.assertTrue(unreferenced.exists())

    @patch("retention.subprocess.run")
    @patch("retention.subprocess.check_output")
    def test_orphaned_pulls_are_retried_without_pruning_other_repositories(self, output, run):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            release = root / "releases" / ("a" * 40 + "-1-1")
            release.mkdir(parents=True)
            values = example_values()
            (release / ".env.prod").write_text(release_env.render(values))
            orphan = "ghcr.io/team-hanmaum/hanmaum-backend@sha256:" + "c" * 64
            output.return_value = "\n".join((values["BACKEND_IMAGE"], orphan,
                                             "postgres@sha256:" + "d" * 64,
                                             "ghcr.io/team-hanmaum/hanmaum-backend@<none>"))
            retention.prune_cached_images(root)
            self.assertEqual(run.call_count, 1)
            self.assertEqual(run.call_args.args[0], ["docker", "image", "rm", orphan])

    def test_keeps_success_pointers_and_mounted_releases_and_unexported_backups(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "releases").mkdir()
            (root / "backups").mkdir()
            releases = []
            for number in range(6):
                release = root / "releases" / f'{number:040x}-1-1'
                release.mkdir()
                image = "ghcr.io/team-hanmaum/hanmaum-backend@sha256:" + f"{number:064x}"
                (release / ".env.prod").write_text(release_env.render({**example_values(), "BACKEND_IMAGE": image}))
                os.utime(release, (number + 1, number + 1))
                releases.append(release)
            for name, index in (("current", 5), ("last-successful", 4), ("previous", 3)):
                (root / name).symlink_to(releases[index])
            for number in range(5):
                dump = root / "backups" / f"hanmaum-{number}.dump"
                dump.write_bytes(b"synthetic")
                os.utime(dump, (number + 1, number + 1))
                if number != 0:  # Never remove a dump without successful off-host verification.
                    dump.with_suffix(".dump.uploaded.json").write_text(json.dumps({
                        "sha256": hashlib.sha256(b"synthetic").hexdigest(), "size": 9, "version_id": "v1"}))
            removed = []
            retention.prune(root, {releases[2]}, removed.append)
            self.assertEqual({path.name for path in (root / "releases").iterdir()},
                             {path.name for path in releases[2:]})
            self.assertEqual(len(removed), 2)
            self.assertTrue((root / "backups/hanmaum-0.dump").exists())
            self.assertFalse((root / "backups/hanmaum-1.dump").exists())


if __name__ == "__main__":
    unittest.main()
