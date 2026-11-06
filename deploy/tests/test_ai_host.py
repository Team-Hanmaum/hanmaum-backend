import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch, Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_ai_host


class AiHostTests(unittest.TestCase):
    def test_read_only_prerequisites_fail_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(ValueError):
                check_ai_host.check(root)
            (root / 'data').mkdir()
            (root / 'tls').mkdir()
            (root / 'initialized').touch()
            (root / 'data/receipts.sqlite3').touch()
            (root / 'tls/ca.crt').write_text('synthetic-public-ca')
            state = root / 'deployment-state.json'
            for phase in ('applying', 'failed', 'recovery-required'):
                state.write_text(json.dumps({'phase': phase}))
                with self.assertRaises(ValueError):
                    check_ai_host.check(root)
            state.write_text('{"phase":"succeeded"}')
            with patch.object(check_ai_host.subprocess, 'run') as docker:
                docker.return_value = Mock(stdout='[{"Driver":"bridge","Internal":true}]')
                check_ai_host.check(root)
                docker.assert_called_once()
                self.assertEqual(docker.call_args.args[0], ['docker', 'network', 'inspect', 'hanmaum-ai-link'])
                docker.return_value = Mock(stdout='[{"Driver":"bridge","Internal":false}]')
                with self.assertRaises(ValueError):
                    check_ai_host.check(root)
