// The requests of the interface to a profiler (no static.json next to the page).
import {test, before} from 'node:test';
import assert from 'node:assert/strict';

/** @type {{url: string, method: string}[]} */
const fetched = [];

/** @type {typeof import('../../main/resources/jfrweb/ui/js/api.js')} */
let api;

before(async () => {
  globalThis.fetch = async (url, init) => {
    fetched.push({url: String(url), method: init?.method ?? 'GET'});
    if (url === 'static.json')
      return new Response('', {status: 404});
    if (String(url).startsWith('api/sessions/9'))
      return new Response('No session 9', {status: 404});
    return Response.json({url});
  };
  api = await import('../../main/resources/jfrweb/ui/js/api.js');
  await api.ready;
});

test('without static.json, the profiler answers', () => {
  assert.equal(api.isStatic(), false);
  assert.ok(fetched.some(f => f.url === 'api/about'));
});

test('requests carry their parameters encoded, and their method', async () => {
  assert.deepEqual(await api.listInstances(1, 'GET /x', {from: 2, to: 4.5}, 20),
                   {url: 'api/sessions/1/instances?limit=20&name=GET+%2Fx&from=2&to=4.5'});
  await api.renameSession(1, 'a b');
  assert.deepEqual(fetched.at(-1), {url: 'api/sessions/1/name?name=a+b', method: 'POST'});
});

test('an error carries the status and the text of the answer', async () => {
  await assert.rejects(api.getSession(9), {message: '404 No session 9'});
});

test('flame graph URLs keep only the filters set', () => {
  assert.equal(api.flameUrl(1, {}), 'api/sessions/1/flame');
  assert.equal(api.flameUrl(1, {name: 'GET /x', from: 0, merged: true, reversed: false}),
               'api/sessions/1/flame?name=GET+%2Fx&from=0&merged=true');
});
