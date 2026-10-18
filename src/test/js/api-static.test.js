// The requests of the interface mapped to the files of a static export, as
// jfrweb.export.StaticExport writes them in static.json: the path, then its
// parameters sorted by name, not encoded.
import {test, before} from 'node:test';
import assert from 'node:assert/strict';

const files = {
  'api/sessions': 'files/sessions.json',
  'api/sessions/1/profile': 'files/profile-1.json',
  'api/history?from=1970-01-01T00:00:00.000Z&name=GET /x&to=1970-01-01T00:00:01.000Z': 'files/history-1.json',
  'api/sessions/1/recording.jfr': 'files/recording-1.jfr',
};

/** @type {string[]} what was fetched */
const fetched = [];

/** @type {typeof import('../../main/resources/jfrweb/ui/js/api.js')} */
let api;

before(async () => {
  globalThis.fetch = async url => {
    fetched.push(String(url));
    if (url === 'static.json')
      return Response.json({files, history: null});
    return Object.values(files).includes(String(url))
        ? Response.json({file: url})
        : new Response('', {status: 404});
  };
  // the module asks for static.json as it loads
  api = await import('../../main/resources/jfrweb/ui/js/api.js');
  await api.ready;
});

test('a static export is recognized by its static.json', () => {
  assert.equal(api.isStatic(), true);
  assert.ok(!fetched.includes('api/about'), 'no profiler to ask');
});

test('a call reads the file of its request, parameters in any order and encoded', async () => {
  assert.deepEqual(await api.listSessions(), {file: 'files/sessions.json'});
  assert.deepEqual(await api.getProfile(1), {file: 'files/profile-1.json'});
  // URLSearchParams writes the space as "+" and the colons encoded, name after to here
  assert.deepEqual(await api.getHistory(new Date(0), new Date(1000), 'GET /x'), {file: 'files/history-1.json'});
});

test('a request not exported fails, telling which', async () => {
  await assert.rejects(api.getHistory(new Date(0), new Date(1000), 'GET /y'),
                       /Not in this static export: api\/history\?from=1970-01-01T00:00:00.000Z&name=GET \/y&to=/);
});

test('what changes something is refused', async () => {
  await assert.rejects(api.renameSession(1, 'x'), /Not available in a static export/);
  assert.ok(!fetched.some(u => u.includes('/name')));
});

test('a download points to its file, or to the missing page', () => {
  assert.equal(api.downloadUrl(1), 'files/recording-1.jfr');
  assert.equal(api.downloadUrl(2), 'missing.html');
});
