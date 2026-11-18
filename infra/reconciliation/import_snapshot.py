"""Manual, offline Sheet checkpoint. Only reconciliation tables are writable."""
import argparse
from collections import defaultdict
from datetime import datetime
import hashlib
import json
import re
import sys
from urllib.parse import urlsplit

import psycopg
from psycopg.types.json import Jsonb

TABS = {'domestic_employers', 'international_employers', 'domestic_actions',
        'international_actions', 'domestic_gaps'}
STATUSES = {'OPEN', 'CONTACTED', 'APPLIED', 'CLOSED', 'ON_HOLD'}
MAX_BYTES = 8 * 1024 * 1024


def require(condition):
    if not condition:
        raise ValueError('Invalid snapshot structure or lineage')


def digest(value):
    data = json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False)
    return hashlib.sha256(data.encode()).hexdigest()


def distinct_fields(pairs):
    result = dict(pairs)
    require(len(result) == len(pairs))
    return result


def validate(doc):
    require(set(doc) == {'workbook', 'captured_at', 'previous', 'tabs', 'status_map', 'timezone'})
    require(isinstance(doc['workbook'], str) and doc['workbook'].strip())
    require(isinstance(doc['timezone'], str) and doc['timezone'].strip())
    captured = datetime.fromisoformat(doc['captured_at'].replace('Z', '+00:00'))
    require(captured.utcoffset() is not None)
    require(doc['previous'] is None or re.fullmatch('[0-9a-f]{64}', doc['previous']))
    require(set(doc['status_map'].values()) <= STATUSES and set(doc['tabs']) == TABS)
    ids = set()
    for tab in doc['tabs'].values():
        require(set(tab) == {'id', 'rows'} and isinstance(tab['id'], str) and tab['id'].strip())
        ids.add(tab['id'])
        require(isinstance(tab['rows'], list) and len(tab['rows']) <= 5000)
        positions = set()
        for row in tab['rows']:
            require(set(row) == {'position', 'key', 'subject', 'cells', 'status', 'action', 'posting'})
            pos = row['position']
            require(type(pos) is int and 2 <= pos <= 2147483647 and pos not in positions)
            positions.add(pos)
            require(isinstance(row['cells'], dict) and row['cells'])
            require(all(isinstance(v, str) for v in row['cells'].values()))
            for field in ('key', 'subject', 'status', 'action', 'posting'):
                require(isinstance(row[field], str) and row[field] == row[field].strip())
            for field in ('status', 'action', 'posting'):
                require(not row[field] or row[field] in row['cells'].values())
    require(len(ids) == len(TABS))
    return captured


def identity(row):
    # Missing keys can establish identical content across exports, never an edit.
    return ('key', row['key']) if row['key'] else ('content', digest(row['cells']))


def listing(url):
    try:
        u = urlsplit(url)
        if u.scheme != 'https' or u.username or u.password or u.port or not u.hostname:
            return None
        if u.hostname in ('www.linkedin.com', 'linkedin.com'):
            match = re.fullmatch(r'/jobs/view/(?:[\w-]+-)?([0-9]{1,32})/?', u.path)
            if match:
                return 'linkedin', match[1]
        if u.hostname in ('boards.greenhouse.io', 'job-boards.greenhouse.io'):
            match = re.fullmatch(r'/([a-z0-9_-]+)/jobs/(\d+)/?', u.path)
            if match:
                return 'greenhouse:' + match[1], match[2]
        if u.hostname in ('jobs.lever.co', 'jobs.eu.lever.co'):
            match = re.fullmatch(r'/([a-z0-9_-]+)/([0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})/?', u.path)
            if match:
                region = 'eu' if u.hostname == 'jobs.eu.lever.co' else 'global'
                return f'lever:{region}:{match[1]}', match[2]
    except ValueError:
        pass
    return None


def groups(rows):
    result = defaultdict(list)
    for row in rows:
        result[identity(row)].append(row)
    return result


def action_groups(doc):
    result = defaultdict(set)
    for kind, tab in doc['tabs'].items():
        if kind == 'domestic_gaps':
            continue
        for row in tab['rows']:
            if row['action']:
                for key in subjects(row):
                    result[key].add(row['action'])
    return result


def subjects(row):
    if row['subject']:
        yield ('legacy', row['subject'])
    posting = listing(row['posting'])
    if posting:
        yield posting


def classify(cur, kind, row, mapping, actions, employer_subjects, ambiguous):
    reasons = []
    if ambiguous or not row['key']:
        reasons.append('AMBIGUOUS_ROW_IDENTITY')
    if row['status'] and row['status'] not in mapping:
        reasons.append('UNKNOWN_STATUS')
    vacancy = None
    if kind != 'domestic_gaps':
        if any(len(actions[key]) > 1 for key in subjects(row)):
            reasons.append('CONFLICTING_ACTIONS')
        if kind.endswith('_actions') and (not row['action'] or (not listing(row['posting'])
                and row['subject'] not in employer_subjects)):
            reasons.append('UNRESOLVED_ACTION')
        if row['posting']:
            source = listing(row['posting'])
            if source:
                cur.execute('SELECT id FROM public.vacancies WHERE source_namespace=%s AND listing_id=%s', source)
                found = cur.fetchone()
                vacancy = found[0] if found else None
            if vacancy is None:
                reasons.append('UNRESOLVED_POSTING')
    return reasons, vacancy


def report(cur, checkpoint):
    cur.execute('''SELECT tab,outcome,count(*) FROM public.sheet_reconciliation
                   WHERE snapshot=%s GROUP BY tab,outcome''', (checkpoint,))
    totals = {kind: dict.fromkeys(('IMPORTED', 'LINKED', 'UNCHANGED', 'REVIEW'), 0) for kind in sorted(TABS)}
    for tab, outcome, count in cur.fetchall():
        totals[tab][outcome] = count
    cur.execute('''SELECT count(*) FILTER (WHERE added),count(*) FILTER (WHERE edited),
                          count(*) FILTER (WHERE moved) FROM public.sheet_reconciliation WHERE snapshot=%s''', (checkpoint,))
    added, edited, moved = cur.fetchone()
    cur.execute('SELECT count(*) FROM public.sheet_removals WHERE snapshot=%s', (checkpoint,))
    return dict(checkpoint=checkpoint, tabs=totals, added=added, edited=edited, moved=moved, removed=cur.fetchone()[0])


def reconcile(conn, doc):
    captured = validate(doc)
    checkpoint = digest(doc)
    with conn.transaction(), conn.cursor() as cur:
        cur.execute('SELECT pg_advisory_xact_lock(710025)')
        cur.execute('''SELECT digest,document,captured_at FROM public.sheet_snapshots
                       ORDER BY captured_at DESC LIMIT 1''')
        head = cur.fetchone()
        if head and head[0] == checkpoint:
            return dict(report(cur, checkpoint), replay=True)
        require(doc['previous'] == (head[0] if head else None))
        previous_tabs = {}
        if head:
            require(doc['workbook'] == head[1]['workbook'] and captured > head[2])
            previous_tabs = head[1]['tabs']
            require(all(previous_tabs[k]['id'] == doc['tabs'][k]['id'] for k in TABS))
        cur.execute('INSERT INTO public.sheet_snapshots VALUES (%s,%s,%s,%s)',
                    (checkpoint, doc['previous'], captured, Jsonb(doc)))
        actions = action_groups(doc)
        employer_subjects = {row['subject'] for kind, tab in doc['tabs'].items() if kind.endswith('_employers')
                             for row in tab['rows'] if row['subject']}
        for kind, tab in doc['tabs'].items():
            old = groups(previous_tabs.get(kind, {}).get('rows', []))
            current = groups(tab['rows'])
            for key, rows in current.items():
                before = old.get(key, [])
                ambiguous = len(rows) > 1 or len(before) > 1
                prior = before[0] if before and not ambiguous else None
                for row in rows:
                    reasons, vacancy = classify(cur, kind, row, doc['status_map'], actions, employer_subjects, ambiguous)
                    edited = prior is not None and any(row[k] != prior[k] for k in row if k != 'position')
                    moved = prior is not None and prior['position'] != row['position']
                    outcome = 'LINKED' if vacancy else 'IMPORTED'
                    if prior and not edited:
                        cur.execute('''SELECT outcome,vacancy_id FROM public.sheet_reconciliation
                                       WHERE snapshot=%s AND tab=%s AND position=%s''',
                                    (doc['previous'], kind, prior['position']))
                        prior_outcome, prior_vacancy = cur.fetchone()
                        if prior_outcome != 'REVIEW' and prior_vacancy == vacancy and (
                                not row['status'] or head[1]['status_map'].get(row['status']) == doc['status_map'].get(row['status'])):
                            outcome = 'UNCHANGED'
                    cur.execute('INSERT INTO public.sheet_reconciliation VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)',
                                (checkpoint, kind, row['position'], 'REVIEW' if reasons else outcome,
                                 reasons, vacancy, not before, edited, moved))
            for key, old_rows in old.items():
                if key in current:
                    if len(old_rows) == 1 and len(current[key]) == 1:
                        continue
                    # Duplicate identities cannot prove continuity outside the same coordinate.
                    occupied = {r['position'] for r in current[key]}
                    old_rows = [r for r in old_rows if r['position'] not in occupied]
                cur.executemany('INSERT INTO public.sheet_removals VALUES (%s,%s,%s)',
                                [(checkpoint, kind, r['position']) for r in old_rows])
        return dict(report(cur, checkpoint), replay=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('snapshot', type=argparse.FileType('rb'))
    args = parser.parse_args()
    try:
        with args.snapshot as file:
            data = file.read(MAX_BYTES + 1)
        require(len(data) <= MAX_BYTES)
        doc = json.loads(data.decode('utf-8'), object_pairs_hook=distinct_fields)
        with psycopg.connect(autocommit=True) as conn:
            result = reconcile(conn, doc)
        print(json.dumps(result, sort_keys=True))
        return 2 if any(tab['REVIEW'] for tab in result['tabs'].values()) else 0
    except (ValueError, TypeError, KeyError, AttributeError, OSError, psycopg.Error):
        print('Sheet import failed. Check snapshot format, predecessor, connection and grants.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
