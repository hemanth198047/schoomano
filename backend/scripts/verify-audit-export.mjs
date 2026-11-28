// Checks an audit export (the CSV from the audit screen) on its own, without the school's database.
//   node verify-audit-export.mjs audit-2026-01-01-to-2026-01-31.csv
// For every row it checks that the sequence numbers follow on without a gap, that each row names the hash of the row before it,
// and (for rows hashed in UTC) that the row's own hash matches its content, so an edited, removed, added or reordered row is
// found. It cannot see rows outside the file, so it says nothing about entries before the first or after the last one.
// Exit code 0: nothing wrong found. 1: a problem (the first one is printed). 2: the file could not be read.
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';

export function parseCsv(text) {
  const rows = [];
  let row = [];
  let cell = '';
  let quoted = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quoted) {
      if (c === '"' && text[i + 1] === '"') {
        cell += '"';
        i++;
      } else if (c === '"') {
        quoted = false;
      } else {
        cell += c;
      }
    } else if (c === '"') {
      quoted = true;
    } else if (c === ',') {
      row.push(cell);
      cell = '';
    } else if (c === '\r' && text[i + 1] === '\n') {
      row.push(cell);
      rows.push(row);
      row = [];
      cell = '';
      i++;
    } else if (c === '\n') {
      row.push(cell);
      rows.push(row);
      row = [];
      cell = '';
    } else {
      cell += c;
    }
  }
  if (cell !== '' || row.length > 0) {
    row.push(cell);
    rows.push(row);
  }
  return rows;
}

// The export puts an apostrophe in front of a cell that starts with = + - @ tab or return, so a spreadsheet will not run it.
function undefuse(v) {
  return v.length > 1 && v[0] === "'" && '=+-@\t\r'.includes(v[1]) ? v.slice(1) : v;
}

const NEEDED = ['seq', 'actor_kind', 'action', 'resource_type', 'resource_id', 'outcome', 'reason', 'correlation_id', 'details', 'prev_hash', 'hash',
  'tenant_id', 'actor_person_id', 'policy_version', 'occurred_at_utc', 'hash_in_utc'];

/** Returns { checked, hashed, problem } where problem is null or { seq, text }. */
export function verify(text) {
  const rows = parseCsv(text).filter((r) => !(r.length === 1 && r[0] === ''));
  if (rows.length === 0) throw new Error('the file is empty');
  const head = rows[0];
  const at = Object.fromEntries(head.map((h, i) => [h, i]));
  for (const n of NEEDED) {
    if (!(n in at)) throw new Error(`the file has no "${n}" column: it was not made by the audit export, or by an older version of it`);
  }
  let prevSeq = null;
  let prevHash = null;
  let hashed = 0;
  for (let i = 1; i < rows.length; i++) {
    const r = rows[i];
    if (r.length !== head.length) return { checked: i - 1, hashed, problem: { seq: r[at.seq], text: 'a line has the wrong number of columns' } };
    const get = (n) => undefuse(r[at[n]]);
    const seq = Number(get('seq'));
    if (prevSeq !== null && seq !== prevSeq + 1) {
      return { checked: i - 1, hashed, problem: { seq, text: 'a row is missing, repeated or out of order before this one' } };
    }
    if (prevHash !== null && get('prev_hash') !== prevHash) {
      return { checked: i - 1, hashed, problem: { seq, text: 'the link to the previous row is broken' } };
    }
    if (get('hash_in_utc') === 'true') {
      const parts = [get('prev_hash'), ...['tenant_id', 'seq', 'occurred_at_utc', 'actor_person_id', 'actor_kind', 'action', 'resource_type', 'resource_id',
        'outcome', 'reason', 'correlation_id', 'policy_version', 'details'].map(get)];
      // The database joins the values with "|" and leaves out the ones that are empty (null), except the first (the previous hash).
      const text = [parts[0], ...parts.slice(1).filter((p) => p !== '')].join('|');
      if (createHash('sha256').update(text, 'utf8').digest('hex') !== get('hash')) {
        return { checked: i - 1, hashed, problem: { seq, text: 'this row was changed after it was written' } };
      }
      hashed++;
    }
    prevSeq = seq;
    prevHash = get('hash');
  }
  return { checked: rows.length - 1, hashed, problem: null };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const file = process.argv[2];
  if (!file) {
    console.error('usage: node verify-audit-export.mjs <audit export .csv>');
    process.exit(2);
  }
  try {
    const r = verify(readFileSync(file, 'utf8'));
    if (r.problem) {
      console.error(`PROBLEM at entry ${r.problem.seq}: ${r.problem.text} (${r.checked} entries were fine before it)`);
      process.exit(1);
    }
    console.log(`OK: ${r.checked} entries, in order and linked; ${r.hashed} of them also match their own hash.`);
  } catch (e) {
    console.error(`could not check the file: ${e.message}`);
    process.exit(2);
  }
}
