"""Exercise CI event trust, stale-run exclusion and the selected SHA without GitHub/AWS calls."""

import io
import json
import os
from pathlib import Path
import runpy
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import select_release


REPOSITORY = "Team-Hanmaum/hanmaum-backend"
CI_SHA = "a" * 40
WORKFLOW_SHA = "b" * 40


def successful_ci():
    return {"action": "completed", "workflow_run": {
        "name": "Backend CI", "event": "push", "status": "completed", "conclusion": "success",
        "head_branch": "dev", "head_repository": {"full_name": REPOSITORY}, "head_sha": CI_SHA,
    }}


class ReleaseSelectionTests(unittest.TestCase):
    def select(self, event, name="workflow_run", repository=REPOSITORY, ref="refs/heads/dev"):
        return select_release.select_sha(name, event, repository, ref, WORKFLOW_SHA)

    def test_workflow_run_uses_tested_commit_instead_of_default_branch_sha(self):
        self.assertEqual(self.select(successful_ci()), CI_SHA)

    def test_only_completed_successful_dev_push_from_our_ci_is_eligible(self):
        changes = [
            ("name", "Another workflow"), ("event", "pull_request"),
            ("event", "pull_request_target"), ("event", "workflow_dispatch"),
            ("status", "in_progress"), ("head_branch", "main"), ("head_branch", "feat/45-test"),
            ("head_repository", {"full_name": "outsider/hanmaum-backend"}),
            ("head_repository", None),
        ] + [("conclusion", result) for result in
             (None, "failure", "cancelled", "skipped", "timed_out", "neutral", "action_required")]
        for key, value in changes:
            with self.subTest(key=key, value=value):
                event = successful_ci()
                event["workflow_run"][key] = value
                self.assertIsNone(self.select(event))
        event = successful_ci()
        event["action"] = "requested"
        self.assertIsNone(self.select(event))
        self.assertIsNone(self.select({}))

    def test_wrong_repository_or_workflow_ref_never_selects_a_release(self):
        for name in ("workflow_run", "workflow_dispatch"):
            with self.subTest(event=name):
                self.assertIsNone(self.select(successful_ci(), name, repository="outsider/backend"))
                self.assertIsNone(self.select(successful_ci(), name, ref="refs/heads/main"))
                self.assertIsNone(self.select(successful_ci(), name, ref="refs/tags/dev"))

    def test_manual_dev_run_keeps_its_selected_commit(self):
        self.assertEqual(self.select({}, "workflow_dispatch"), WORKFLOW_SHA)
        self.assertIsNone(self.select(successful_ci(), "push"))

    def test_missing_or_malformed_ci_sha_is_rejected_without_fallback(self):
        for sha in (None, "", "dev", "a" * 39, CI_SHA + "\nother-output=true"):
            with self.subTest(sha=sha):
                event = successful_ci()
                event["workflow_run"]["head_sha"] = sha
                with self.assertRaisesRegex(ValueError, "Invalid release SHA"):
                    self.select(event)

    def run_selector(self, event, latest=CI_SHA, error=None):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            event_file, output = root / "event.json", root / "output"
            event_file.write_text(json.dumps(event), encoding="utf-8")
            environment = {
                "GITHUB_EVENT_PATH": str(event_file), "GITHUB_OUTPUT": str(output),
                "GITHUB_EVENT_NAME": "workflow_run", "GITHUB_REPOSITORY": REPOSITORY,
                "GITHUB_REF": "refs/heads/dev", "GITHUB_SHA": WORKFLOW_SHA, "GH_TOKEN": "synthetic",
            }
            with patch.dict(os.environ, environment), patch("sys.stdout", new_callable=io.StringIO), \
                    patch("select_release.current_dev_sha", return_value=latest, side_effect=error) as lookup:
                if error:
                    with self.assertRaisesRegex(ValueError, "Cannot verify"):
                        select_release.main()
                else:
                    select_release.main()
            return output.read_text(encoding="utf-8") if output.exists() else "", lookup.call_count

    def test_only_verified_current_ci_sha_is_exposed_to_build_and_deploy(self):
        output, calls = self.run_selector(successful_ci())
        self.assertEqual(output, f"sha={CI_SHA}\n")
        self.assertEqual(calls, 1)

    def test_failed_ci_never_checks_dev_or_exposes_a_release(self):
        event = successful_ci()
        event["workflow_run"]["conclusion"] = "failure"
        self.assertEqual(self.run_selector(event), ("", 0))

    def test_stale_ci_is_skipped_instead_of_deploying_new_untested_head(self):
        self.assertEqual(self.run_selector(successful_ci(), latest=WORKFLOW_SHA), ("", 1))

    def test_unavailable_dev_ref_cannot_produce_a_deployable_output(self):
        self.assertEqual(self.run_selector(successful_ci(), error=ValueError("Cannot verify")), ("", 1))

    def test_backup_cli_uses_same_release_sha_as_image_and_payload(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "upload.json"
            sdk, config = MagicMock(), MagicMock()
            sdk.client.return_value.get_caller_identity.return_value = {"Account": "123456789012"}
            sdk.client.return_value.get_bucket_location.return_value = {"LocationConstraint": "ap-southeast-2"}
            sdk.client.return_value.get_bucket_versioning.return_value = {"Status": "Enabled"}
            sdk.client.return_value.get_public_access_block.return_value = {
                "PublicAccessBlockConfiguration": dict.fromkeys(
                    ("BlockPublicAcls", "IgnorePublicAcls", "BlockPublicPolicy", "RestrictPublicBuckets"), True)}
            sdk.client.return_value.generate_presigned_url.return_value = "synthetic-url"
            environment = {"RELEASE_SHA": CI_SHA, "GITHUB_SHA": WORKFLOW_SHA,
                           "GITHUB_RUN_ID": "42", "GITHUB_RUN_ATTEMPT": "2", "BACKUP_S3_BUCKET": "test-backups"}
            script = Path(__file__).resolve().parents[1] / "prepare_backup.py"
            with patch.dict(os.environ, environment), patch.object(sys, "argv", [str(script), str(output)]), \
                    patch.dict(sys.modules, {"boto3": sdk, "botocore.config": config}):
                runpy.run_path(str(script), run_name="__main__")
            data = json.loads(output.read_text(encoding="utf-8"))
            self.assertTrue(data["key"].startswith(f"hanmaum/postgres/{CI_SHA}-42-2-"))
            self.assertNotIn(WORKFLOW_SHA, data["key"])


if __name__ == "__main__":
    unittest.main()
