"""Worker opt-in, fail-fast settings and upgrade compatibility; synthetic values only."""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import release_env
from test_release import example_values
from validate import WORKER_EXAMPLE, compose_args


class WorkerSettingsTests(unittest.TestCase):
    def test_missing_or_empty_opt_in_stays_disabled(self):
        for values in ({}, dict.fromkeys(release_env.WORKER_NAMES, "")):
            with self.subTest(values=values):
                result = release_env.checked_worker_values(values)
                self.assertEqual(result["ANALYSIS_WORKER_ENABLED"], "false")
                self.assertTrue(all(result[name] == "" for name in release_env.WORKER_NAMES[1:]))

    def test_enabled_worker_requires_each_operating_value(self):
        for name in release_env.WORKER_NAMES[1:]:
            with self.subTest(name=name):
                values = WORKER_EXAMPLE.copy()
                del values[name]
                with self.assertRaisesRegex(ValueError, name):
                    release_env.checked_values({**example_values(), **values})

    def test_invalid_values_are_rejected_without_echoing_input(self):
        invalid = {
            "ANALYSIS_WORKER_ENABLED": ("TRUE", "1", "yes", "true\nprivate-value"),
            "ANALYSIS_WORKER_CONCURRENCY": ("0", "-1", "1.5", "2147483648", "private-value"),
            "ANALYSIS_WORKER_POLL_INTERVAL": ("0s", "-1s", "2", "PT2S", "9223372036854776s"),
            "ANALYSIS_WORKER_LEASE_DURATION": ("0s", "33s", "30s", "120", "120s\nprivate-value"),
        }
        for name, cases in invalid.items():
            for value in cases:
                with self.subTest(name=name, value=value):
                    with self.assertRaisesRegex(ValueError, name) as raised:
                        release_env.checked_worker_values({**WORKER_EXAMPLE, name: value})
                    self.assertNotIn("private-value", str(raised.exception))

    def test_disabled_worker_still_rejects_values_that_would_break_spring_binding(self):
        for name in release_env.WORKER_NAMES[1:]:
            with self.subTest(name=name):
                with self.assertRaisesRegex(ValueError, name):
                    release_env.checked_worker_values({"ANALYSIS_WORKER_ENABLED": "false", name: "bad"})

    def test_upgrade_and_disable_do_not_require_rotating_persisted_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            old, new = Path(directory) / "old.env", Path(directory) / "new.env"
            # Actual pre-upgrade format: no worker fields at all.
            old.write_text(release_env.render(example_values()), encoding="utf-8")
            for enabled in ("true", "false"):
                with self.subTest(enabled=enabled):
                    settings = {**WORKER_EXAMPLE, "ANALYSIS_WORKER_ENABLED": enabled}
                    new.write_text(release_env.render(release_env.checked_values(
                        {**example_values(), **settings})), encoding="utf-8")
                    release_env.compare_stable(old, new)
                    restored = release_env.read_generated(new)
                    self.assertEqual({key: restored[key] for key in settings}, settings)

    def test_release_dotenv_reaches_only_backend_compose_environment(self):
        with tempfile.TemporaryDirectory() as directory:
            env_file = Path(directory) / ".env.prod"
            environment = {name: value for name, value in os.environ.items()
                           if name not in (*release_env.SECRET_NAMES, *release_env.WORKER_NAMES,
                                           "BACKEND_IMAGE", "COMPOSE_FILE", "COMPOSE_PROJECT_NAME",
                                           "COMPOSE_PROFILES", "COMPOSE_ENV_FILES")}
            for enabled in ("true", "false"):
                with self.subTest(enabled=enabled):
                    settings = {**WORKER_EXAMPLE, "ANALYSIS_WORKER_ENABLED": enabled}
                    env_file.write_text(release_env.render(release_env.checked_values(
                        {**example_values(), **settings})), encoding="utf-8")
                    command = compose_args("compose.prod.yml")
                    command[command.index("--env-file") + 1] = str(env_file)
                    result = subprocess.run(command + ["--format", "json"], env=environment,
                                            capture_output=True, text=True, check=True)
                    services = json.loads(result.stdout)["services"]
                    actual = services["backend"]["environment"]
                    self.assertEqual({name: actual[name] for name in settings}, settings)
                    for name, service in services.items():
                        if name != "backend":
                            self.assertFalse(set(settings) & set(service.get("environment", {})))

    def test_invalid_cli_settings_never_create_a_payload(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / ".env.prod"
            result = subprocess.run(
                [sys.executable, str(Path(release_env.__file__)), "write", str(target)],
                env={**os.environ, **example_values(), **WORKER_EXAMPLE,
                     "ANALYSIS_WORKER_LEASE_DURATION": "33s"},
                capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("ANALYSIS_WORKER_LEASE_DURATION", result.stderr)
            self.assertFalse(target.exists())


if __name__ == "__main__":
    unittest.main()
