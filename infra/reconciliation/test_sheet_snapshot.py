"""Run only against the disposable postgres instance made by test-local.sh."""
from copy import deepcopy
import os
from pathlib import Path
import unittest

import psycopg

from import_snapshot import TABS, reconcile


def row(position, key, posting='', status='', action='', subject=''):
    return dict(position=position, key=key, subject=subject, posting=posting, status=status, action=action,
                cells={'Company': 'Same synthetic name', 'Role': 'Engineer', 'Date': '03/10/2026',
                       'Posting': posting, 'Status': status, 'Next': action})


class SnapshotIT(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        params = dict(host=os.environ['TEST_DB_HOST'], user='test_admin', password=os.environ['TEST_DB_PASSWORD'])
        with psycopg.connect(**params, dbname='postgres', autocommit=True) as admin:
            admin.execute('CREATE DATABASE sheet_rehearsal')
        cls.conn = psycopg.connect(**params, dbname='sheet_rehearsal', autocommit=True)
        cls.addClassCleanup(cls.conn.close)
        for migration in sorted(Path('/migrations').glob('V*__*.sql'), key=lambda p: int(p.name[1:].split('__')[0])):
            cls.conn.execute(migration.read_text())
        cls.conn.execute("CREATE ROLE sheet_import_fixture")
        cls.conn.execute("GRANT INSERT, SELECT ON sheet_snapshots, sheet_reconciliation, sheet_removals TO sheet_import_fixture")
        cls.conn.execute("GRANT SELECT (id,source_namespace,listing_id) ON vacancies TO sheet_import_fixture")
        cls.conn.execute("INSERT INTO employers(source_namespace,external_id,name) VALUES ('synthetic','1','Same synthetic name')")
        cls.conn.execute("INSERT INTO vacancies(employer_id,source_namespace,listing_id,title) VALUES (1,'linkedin','123','Engineer')")

    def setUp(self):
        self.conn.execute('TRUNCATE sheet_removals,sheet_reconciliation,sheet_snapshots')
        self.doc = dict(workbook='synthetic', timezone='Europe/Berlin', captured_at='2026-10-03T18:00:00Z',
                        previous=None, status_map={'Open': 'OPEN'},
                        tabs={k: dict(id=k, rows=[]) for k in TABS})

    def test_replay_delta_and_historical_actions(self):
        employer = row(2, 'stable', 'https://www.linkedin.com/jobs/view/123', 'Open', subject='org-1')
        self.doc['tabs']['domestic_employers']['rows'] = [employer, row(3, 'removed')]
        self.doc['tabs']['domestic_actions']['rows'] = [row(2, 'past', action='Contacted', subject='org-1')]
        self.doc['tabs']['domestic_gaps']['rows'] = [row(2, 'research', employer['posting'])]
        self.conn.execute('SET ROLE sheet_import_fixture')
        self.addCleanup(self.conn.execute, 'RESET ROLE')
        first = reconcile(self.conn, self.doc)
        self.assertEqual(1, first['tabs']['domestic_employers']['LINKED'])
        self.assertEqual(1, first['tabs']['domestic_actions']['IMPORTED'])
        self.assertEqual(1, first['tabs']['domestic_gaps']['IMPORTED'])
        self.assertTrue(reconcile(self.conn, self.doc)['replay'])
        delta = deepcopy(self.doc)
        delta.update(previous=first['checkpoint'], captured_at='2026-10-04T18:00:00Z')
        rows = delta['tabs']['domestic_employers']['rows']
        rows.pop()
        rows[0]['position'] = 7
        rows[0]['cells']['Date'] = '04/10/2026'
        rows.append(row(8, 'added'))
        final = reconcile(self.conn, delta)
        self.assertEqual((1, 1, 1, 1), tuple(final[k] for k in ('added','edited','moved','removed')))
        self.assertEqual(1, final['tabs']['domestic_actions']['UNCHANGED'])
        self.assertTrue(reconcile(self.conn, delta)['replay'])
        with self.assertRaises(ValueError):
            reconcile(self.conn, self.doc)
        self.assertEqual(2, self.conn.execute('SELECT count(*) FROM sheet_snapshots').fetchone()[0])
        self.conn.execute('RESET ROLE')
        self.assertEqual((1, 0, 0, 0), self.conn.execute('''SELECT (SELECT count(*) FROM vacancies),
            (SELECT count(*) FROM outbox_events),(SELECT count(*) FROM gmail_alert_messages),
            (SELECT count(*) FROM scheduled_result_items)''').fetchone())

    def test_ambiguous_identity_unknown_status_and_conflicting_actions(self):
        rows = [row(2, 'same', status='Unmapped', action='Send', subject='legacy-1'),
                row(3, 'same', action='Stop', subject='legacy-1'), row(4, 'distinct')]
        self.doc['tabs']['international_employers']['rows'] = rows
        result = reconcile(self.conn, self.doc)
        self.assertEqual(2, result['tabs']['international_employers']['REVIEW'])
        self.assertEqual(1, result['tabs']['international_employers']['IMPORTED'])
        stored = self.conn.execute('SELECT vacancy_id,reasons FROM sheet_reconciliation ORDER BY position').fetchall()
        self.assertEqual((None, ['AMBIGUOUS_ROW_IDENTITY','UNKNOWN_STATUS','CONFLICTING_ACTIONS']), stored[0])
        self.assertEqual((None, []), stored[2])
