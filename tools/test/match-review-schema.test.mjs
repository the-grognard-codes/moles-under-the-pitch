import assert from 'node:assert/strict';
import test from 'node:test';
import { reviewMigrations, reviewSchemaMarker } from '../match-review-schema.mjs';

test('provisions review copies in order and accepts an existing marker 11 source', () => {
  const ack = '011-expired-match-acknowledgement.sql';
  assert.equal(reviewSchemaMarker, '11');
  assert.deepEqual(reviewMigrations('11'), []);
  assert.deepEqual(reviewMigrations('10'), [ack]);
  assert.deepEqual(reviewMigrations('9'), ['010-computer-job-availability.sql', ack]);
  for (const marker of ['6', '12', '11x']) assert.throws(() => reviewMigrations(marker), /Unsupported/);
});
