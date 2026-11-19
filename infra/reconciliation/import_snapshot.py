"""Manual, offline Sheet checkpoint. Only reconciliation tables are writable."""
import argparse
from collections import defaultdict
from datetime import date, datetime
import hashlib
import json
import re
import sys
from urllib.parse import urlsplit

import psycopg
from psycopg.types.json import Jsonb

EMPLOYER_TABS = {'domestic_employers', 'international_employers'}
ACTION_TABS = {'domestic_actions', 'international_actions'}
TABS = EMPLOYER_TABS | ACTION_TABS | {'domestic_gaps'}
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
    required = {'workbook', 'captured_at', 'previous', 'tabs', 'status_map', 'timezone'}
    require(required <= set(doc) <= required | {'action_reviews'})
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


def nonempty_text(value):
    return isinstance(value, str) and bool(value.strip())


def validate_decision(decision):
    required = {'kind', 'evidence'}
    optional = {'employer', 'event_key', 'occurred_on', 'supersedes_plan'}
    require(isinstance(decision, dict) and required <= set(decision) <= required | optional)
    kind = decision['kind']
    require(kind in ('LINK', 'STANDALONE', 'NOTE') and nonempty_text(decision['evidence']))
    require(('employer' in decision) == (kind == 'LINK'))
    if kind == 'NOTE':
        require(set(decision) == required)
    for field in ('event_key', 'occurred_on', 'supersedes_plan'):
        require(field not in decision or nonempty_text(decision[field]))
    if 'occurred_on' in decision:
        value = decision['occurred_on']
        require(date.fromisoformat(value).isoformat() == value)
    if 'supersedes_plan' in decision:
        require(kind == 'LINK' and 'occurred_on' in decision)


def review_actions(doc):
    """Validate private decisions and identify contradictions about the same occurrence."""
    reviews = doc.get('action_reviews', {})
    require(isinstance(reviews, dict) and set(reviews) <= ACTION_TABS)
    by_key = {tab: groups(doc['tabs'][tab]['rows']) for tab in EMPLOYER_TABS | ACTION_TABS}
    reasons = {}
    events = defaultdict(list)
    for tab, decisions in reviews.items():
        require(isinstance(decisions, dict))
        for key, decision in decisions.items():
            rows = by_key[tab].get(('key', key), [])
            require(rows)
            validate_decision(decision)
            reference = (tab, key)
            if decision['kind'] == 'LINK':
                target = decision['employer']
                require(isinstance(target, dict) and set(target) == {'tab', 'key'})
                require(target['tab'] in EMPLOYER_TABS and nonempty_text(target['key']))
                employers = by_key[target['tab']].get(('key', target['key']), [])
                require(employers)
                if len(employers) > 1:
                    reasons[reference] = 'AMBIGUOUS_EMPLOYER_REFERENCE'
                elif 'supersedes_plan' in decision:
                    require(decision['supersedes_plan'] == employers[0]['action'])
            if len(rows) == 1 and 'event_key' in decision:
                signature = (decision['kind'], decision.get('employer'), decision.get('occurred_on'),
                             rows[0]['action'])
                events[decision['event_key']].append((reference, signature))
    for records in events.values():
        if any(signature != records[0][1] for _, signature in records):
            for reference, _ in records:
                reasons.setdefault(reference, 'CONFLICTING_ACTIONS')
    return reviews, reasons


def classify(cur, kind, row, mapping, decision, review_reason, ambiguous):
    reasons = []
    if ambiguous or not row['key']:
        reasons.append('AMBIGUOUS_ROW_IDENTITY')
    if row['status'] and row['status'] not in mapping:
        reasons.append('UNKNOWN_STATUS')
    vacancy = None
    if kind in ACTION_TABS:
        if decision is None:
            reasons.append('MISSING_ACTION_REVIEW')
        elif decision['kind'] != 'NOTE' and not row['action']:
            reasons.append('MISSING_HISTORICAL_ACTION')
        if review_reason:
            reasons.append(review_reason)
    if kind in EMPLOYER_TABS:
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
    reviews, review_reasons = review_actions(doc)
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
        previous_reviews = head[1].get('action_reviews', {}) if head else {}
        for kind, tab in doc['tabs'].items():
            old = groups(previous_tabs.get(kind, {}).get('rows', []))
            current = groups(tab['rows'])
            for key, rows in current.items():
                before = old.get(key, [])
                ambiguous = len(rows) > 1 or len(before) > 1
                prior = before[0] if before and not ambiguous else None
                for row in rows:
                    decision = reviews.get(kind, {}).get(row['key'])
                    review_reason = review_reasons.get((kind, row['key']))
                    reasons, vacancy = classify(cur, kind, row, doc['status_map'], decision, review_reason, ambiguous)
                    edited = prior is not None and any(row[k] != prior[k] for k in row if k != 'position')
                    moved = prior is not None and prior['position'] != row['position']
                    linked = vacancy is not None or (decision and decision['kind'] == 'LINK')
                    outcome = 'LINKED' if linked else 'IMPORTED'
                    if prior and not edited and decision == previous_reviews.get(kind, {}).get(row['key']):
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
