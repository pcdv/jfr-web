// How long a session records at most: what the field says, in seconds.
import {test} from 'node:test';
import assert from 'node:assert/strict';

const {toSeconds} = await import('../../main/resources/jfrweb/ui/js/components/stopAfter.js');

test('minutes and seconds, in seconds', () => {
  assert.equal(toSeconds('5', 'minutes'), 300);
  assert.equal(toSeconds(' 90 ', 'seconds'), 90);
  assert.equal(toSeconds('0.5', 'minutes'), 30);
});

test('nothing typed is no limit', () => {
  assert.equal(toSeconds('', 'minutes'), null);
  assert.equal(toSeconds('  ', 'seconds'), null);
});

test('anything else than a positive number is refused, with what was typed', () => {
  for (const bad of ['0', '-3', 'soon', 'Infinity'])
    assert.throws(() => toSeconds(bad, 'minutes'), {message: new RegExp(`${bad}$`)});
  assert.throws(() => toSeconds('5', 'hours'), /hours/);
});
