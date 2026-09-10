import importlib.util
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location('ci_path_boundaries', Path(__file__).resolve().parents[1] / 'classify_ci_paths.py')
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class CiPathBoundariesTest(unittest.TestCase):
    def test_git_literal_backslash_does_not_become_a_documentation_directory(self):
        for path in ('docs\\build-input.kt', 'ARES-Analytics/docs\\build-input.kt', '.agents\\runtime.py'):
            with self.subTest(path=path):
                result = MODULE.classify_paths([path])
                self.assertTrue(result['full'] or result['analytics_app'])

    def test_root_attributes_are_shared_checkout_and_archive_inputs(self):
        result = MODULE.classify_paths(['.gitattributes'])
        self.assertTrue(result['full'])
        self.assertTrue(result['packages'])

    def test_full_object_id_validation_does_not_accept_intermediate_lengths(self):
        completed = subprocess.CompletedProcess([], 0, b'')
        with patch.object(MODULE.subprocess, 'run', return_value=completed) as run:
            for length in (39, 41, 48, 63, 65):
                with self.subTest(length=length), self.assertRaises(ValueError):
                    MODULE._git_changed_paths('a' * length, 'b' * 40)
            run.assert_not_called()
