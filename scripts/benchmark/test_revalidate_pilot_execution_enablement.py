import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import revalidate_pilot_execution_enablement as revalidate


class RevalidatePilotExecutionEnablementTest(unittest.TestCase):
    def test_module_exposes_read_only_entrypoint(self):
        self.assertTrue(callable(revalidate.main))


if __name__ == "__main__":
    unittest.main()
