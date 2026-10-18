import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readSetting, writeSetting} from '../../../main/resources/jfrweb/ui/js/lib/settings.js';

/** @param {PropertyDescriptor} storage what globalThis.localStorage is, for one test */
function withStorage(storage, run) {
  Object.defineProperty(globalThis, 'localStorage', {...storage, configurable: true});
  try {
    run();
  }
  finally {
    delete globalThis.localStorage;
  }
}

function memoryStorage() {
  const m = new Map();
  return {
    getItem: k => m.has(k) ? m.get(k) : null,
    setItem: (k, v) => m.set(k, v),
  };
}

test('a setting written is read back, as a string', () => {
  withStorage({value: memoryStorage()}, () => {
    assert.equal(readSetting('jfrweb.test'), null);
    writeSetting('jfrweb.test', 15);
    assert.equal(readSetting('jfrweb.test'), '15');
    writeSetting('jfrweb.test', false);
    assert.equal(readSetting('jfrweb.test'), 'false');
  });
});

test('without storage, nothing is remembered and nothing fails', () => {
  // a private window, or site data blocked: reading localStorage throws
  withStorage({get() { throw new Error('SecurityError'); }}, () => {
    writeSetting('jfrweb.test', 'x');
    assert.equal(readSetting('jfrweb.test'), null);
  });
  // no storage at all
  writeSetting('jfrweb.test', 'x');
  assert.equal(readSetting('jfrweb.test'), null);
});
