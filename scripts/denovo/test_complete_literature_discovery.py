import unittest
from unittest.mock import patch

import complete_literature_discovery as runner


class Transport:
    requests = 0
    events = []
    def __enter__(self): return self
    def __exit__(self, *args): pass


def result(index, more=True, complete=True):
    return {'checkpoint': {'queries': [{'queryId': str(index), 'executionComplete': not more}]},
            'resumeAvailable': more, 'targets': [{'plannedQueriesComplete': complete}]}


class CompletionTests(unittest.TestCase):
    def test_continues_beyond_three_batches_and_records_each(self):
        rows = []
        with patch.object(runner, 'discover', side_effect=[result(i, i < 6) for i in range(1, 7)]):
            done = runner.complete([], lambda n: Transport(), lambda *args: rows.append(args))
        self.assertEqual(6, done['batches'])
        self.assertEqual(6, len(rows))
        self.assertTrue(done['queueDrained'])

    def test_no_progress_stops_without_claiming_completion(self):
        with patch.object(runner, 'discover', return_value=result(1)):
            done = runner.complete([], lambda n: Transport(), lambda *args: None)
        self.assertEqual(4, done['batches'])
        self.assertEqual('NO_PROGRESS_REQUIRES_REVIEW', done['stopReason'])
        self.assertFalse(done['queueDrained'])

    def test_rate_limit_preserves_pending_checkpoint(self):
        tr = Transport()
        tr.events = [{'status': 'UNAVAILABLE', 'reasonCode': 'LITERATURE_SEARCH_RATE_LIMIT'}]
        with patch.object(runner, 'discover', return_value=result(1)):
            done = runner.complete([], lambda n: tr, lambda *args: None)
        self.assertEqual(1, done['batches'])
        self.assertFalse(done['queueDrained'])

    def test_drained_is_not_error_free(self):
        with patch.object(runner, 'discover', return_value=result(1, False, False)):
            done = runner.complete([], lambda n: Transport(), lambda *args: None)
        self.assertTrue(done['queueDrained'])
        self.assertFalse(done['plannedQueriesComplete'])


if __name__ == '__main__': unittest.main()
