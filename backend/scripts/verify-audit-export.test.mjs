import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { verify } from './verify-audit-export.mjs';

const good = readFileSync(new URL('./fixtures/audit-export-sample.csv', import.meta.url), 'utf8');

test('an untouched export checks out', () => {
  const r = verify(good);
  assert.equal(r.problem, null);
  assert.equal(r.checked, 4);
  assert.equal(r.hashed, 4);
});

test('a changed value is found', () => {
  const r = verify(good.replace('fixture.two', 'fixture.twO'));
  assert.equal(r.problem.seq, 2);
  assert.match(r.problem.text, /changed/);
});

test('a changed detail is found', () => {
  assert.equal(verify(good.replace('""n"": 2', '""n"": 3')).problem.seq, 2);
});

test('a removed row is found', () => {
  const lines = good.split('\r\n');
  lines.splice(2, 1);
  const r = verify(lines.join('\r\n'));
  assert.equal(r.problem.seq, 3);
  assert.match(r.problem.text, /missing/);
});

test('reordered rows are found', () => {
  const lines = good.split('\r\n');
  assert.notEqual(lines[1], lines[2]);
  [lines[1], lines[2]] = [lines[2], lines[1]];
  assert.ok(verify(lines.join('\r\n')).problem);
});

test('a row with its hash rewritten breaks the next link', () => {
  const lines = good.split('\r\n');
  const hash = lines[1].split(',').reverse()[5];
  const r = verify(good.replace(new RegExp(hash, 'g'), 'a'.repeat(64)));
  assert.ok(r.problem);
});

test('a file from another source is refused', () => {
  assert.throws(() => verify('a,b\r\n1,2\r\n'), /no "seq"|no "actor_kind"/);
});
