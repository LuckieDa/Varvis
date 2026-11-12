import unittest
from evaluate_combined_repeat_panel import positions,coverage


class MetricsTest(unittest.TestCase):
    def test_overlaps_union_not_duplicate_count_or_hull(self):
        found=positions([dict(units=[dict(start=1,end=4),dict(start=8,end=10)]),
                         dict(units=[dict(start=3,end=4)])])
        self.assertEqual(len(found),7)
        self.assertEqual(coverage(5,7,found)['hitResidues'],0)
        self.assertEqual(coverage(2,9,found)['hitResidues'],5)

    def test_boundary_extension_not_hidden(self):
        found=set(range(1,16)); expected=coverage(6,10,found)
        self.assertEqual(expected['hitResidues'],expected['residues'])
        self.assertEqual(len(found-set(range(6,11))),10)


if __name__ == '__main__':unittest.main()
