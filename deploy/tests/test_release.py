"""Offline deployment regressions: no AWS calls, no real credentials or servers."""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import firewall
import release_env
from validate import REQUIRED


def example_values():
    return {**REQUIRED, "BACKEND_IMAGE": "ghcr.io/team-hanmaum/hanmaum-backend@sha256:" + "a" * 64}


class EnvironmentTests(unittest.TestCase):
    def test_compose_preserves_secret_punctuation_without_executing_or_interpolating_it(self):
        values = example_values()
        value = 'quote" apostrophe\' slash\\ dollar$HOME ${UNSET_ENV} $$ # `id` $(id) 한글'
        values["GOOGLE_CLIENT_SECRET"] = value
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            env_file = root / ".env.prod"
            env_file.write_text(release_env.render(release_env.checked_values(values)), encoding="utf-8")
            compose_file = root / "compose.yml"
            compose_file.write_text(
                'services:\n  probe:\n    image: unused\n    environment:\n'
                '      PROBE: ${GOOGLE_CLIENT_SECRET}\n', encoding="utf-8")
            environment = {name: value for name, value in os.environ.items()
                           if name not in (*release_env.SECRET_NAMES, "COMPOSE_FILE", "COMPOSE_PROFILES")}
            result = subprocess.run(
                ["docker", "compose", "-p", "hanmaum-env-test", "--env-file", str(env_file),
                 "-f", str(compose_file), "config", "--environment"],
                env=environment, capture_output=True, text=True, encoding="utf-8", check=True)
            # config's YAML/JSON deliberately re-escapes $ for reuse; --environment reports input values.
            resolved = dict(line.split("=", 1) for line in result.stdout.splitlines() if "=" in line)
            self.assertEqual(resolved["GOOGLE_CLIENT_SECRET"], value)
            self.assertEqual(release_env.read_generated(env_file), values)

    def test_missing_values_and_multiline_values_fail_without_echoing_the_secret(self):
        for name in release_env.SECRET_NAMES:
            for value in ("", "private-value\nINJECTED=bad", "private-value\x00"):
                with self.subTest(name=name, value=repr(value)):
                    values = example_values()
                    values[name] = value
                    with self.assertRaises(ValueError) as raised:
                        release_env.checked_values(values)
                    self.assertNotIn("private-value", str(raised.exception))

    def test_rejects_mutable_image_and_bad_encryption_key(self):
        for name, value in (("BACKEND_IMAGE", "nginx:latest"),
                            ("INVITATION_ENCRYPTION_KEY", "YWJj")):
            with self.subTest(name=name):
                with self.assertRaises(ValueError):
                    release_env.checked_values({**example_values(), name: value})

    def test_rotation_guard_allows_oauth_updates_but_blocks_persisted_key_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            old, new = Path(directory) / "old", Path(directory) / "new"
            values = example_values()
            old.write_text(release_env.render(values), encoding="utf-8")
            new.write_text(release_env.render({**values, "GOOGLE_CLIENT_SECRET": "new-secret"}),
                           encoding="utf-8")
            release_env.compare_stable(old, new)
            for name in release_env.STABLE_NAMES:
                changed = {**values, name: "x" * 44}
                if name == "INVITATION_ENCRYPTION_KEY":
                    changed[name] = "eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHg="
                new.write_text(release_env.render(changed), encoding="utf-8")
                with self.assertRaisesRegex(ValueError, name):
                    release_env.compare_stable(old, new)


class FirewallTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.state = Path(self.directory.name) / "state.json"
        self.rules = [{"protocol": "tcp", "fromPort": 22, "toPort": 22,
                       "state": "open", "cidrs": ["203.0.113.4/32"],
                       "cidrListAliases": ["lightsail-connect"]}]

    @patch("firewall.wait_for_rule")
    @patch("firewall.aws")
    @patch("firewall.ssh_rules")
    def test_only_owned_runner_rule_is_closed_and_admin_rules_are_not_replaced(self, rules, aws, wait):
        rules.return_value = self.rules
        firewall.open_rule(self.state, "ap-southeast-2", "hanmaum-server-01", "8.8.4.4")
        firewall.close_rule(self.state)
        self.assertEqual([call.args[1] for call in aws.call_args_list],
                         ["open-instance-public-ports", "close-instance-public-ports"])
        for call in aws.call_args_list:
            self.assertEqual(call.kwargs["portInfo"],
                             {"fromPort": 22, "toPort": 22, "protocol": "tcp", "cidrs": ["8.8.4.4/32"]})
        self.assertFalse(self.state.exists())

    @patch("firewall.aws")
    @patch("firewall.ssh_rules")
    def test_preexisting_runner_rule_is_never_removed(self, rules, aws):
        rules.return_value = [{**self.rules[0], "cidrs": ["8.8.4.4/32"]}]
        firewall.open_rule(self.state, "ap-southeast-2", "instance", "8.8.4.4")
        firewall.close_rule(self.state)
        aws.assert_not_called()

    @patch("firewall.aws", side_effect=RuntimeError("lost response"))
    @patch("firewall.ssh_rules")
    def test_cleanup_ownership_is_saved_even_when_open_response_is_lost(self, rules, aws):
        rules.return_value = self.rules
        with self.assertRaises(RuntimeError):
            firewall.open_rule(self.state, "ap-southeast-2", "instance", "8.8.4.4")
        self.assertTrue(json.loads(self.state.read_text())["owned"])

    @patch("firewall.ssh_rules")
    def test_rejects_globally_open_ssh(self, rules):
        rules.return_value = [{**self.rules[0], "cidrs": ["0.0.0.0/0"]}]
        with self.assertRaises(ValueError):
            firewall.open_rule(self.state, "ap-southeast-2", "instance", "8.8.4.4")
        self.assertFalse(self.state.exists())

    def test_refuses_cleanup_with_an_entire_subnet(self):
        with self.assertRaises(ValueError):
            firewall.port_info("8.8.4.0/24")


if __name__ == "__main__":
    unittest.main()
