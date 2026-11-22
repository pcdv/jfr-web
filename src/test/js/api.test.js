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
  assert.deepEqual(await api.getHistory(new Date(0), new Date(1000), 'GET /x'),
                   {url: 'api/history?from=1970-01-01T00%3A00%3A00.000Z&to=1970-01-01T00%3A00%3A01.000Z&name=GET+%2Fx'});
  assert.deepEqual(await api.getTimeline(1, {from: 0, to: 4.5}), {url: 'api/sessions/1/timeline?from=0&to=4.5'});
  await api.renameSession(1, 'a b');
  assert.deepEqual(fetched.at(-1), {url: 'api/sessions/1/name?name=a+b', method: 'POST'});
});

test('a session starts with its name, the next ones record as chosen', async () => {
  await api.startSession('slow login');
  assert.deepEqual(fetched.at(-1), {url: 'api/sessions?name=slow+login', method: 'POST'});
  await api.startSession();
  assert.deepEqual(fetched.at(-1), {url: 'api/sessions', method: 'POST'});
  await api.startSession('', 300);
  assert.deepEqual(fetched.at(-1), {url: 'api/sessions?stopAfter=300', method: 'POST'});
  await api.startSession('a b', null);
  assert.deepEqual(fetched.at(-1), {url: 'api/sessions?name=a+b', method: 'POST'});

  await api.setRecording({preset: 'light', enabled: {exceptions: false}, values: {java: '5 ms'},
                          other: 'jdk.ClassLoad#threshold=5 ms'});
  assert.deepEqual(fetched.at(-1), {
    url: 'api/recording?preset=light&other=jdk.ClassLoad%23threshold%3D5+ms&exceptions=false&java.value=5+ms',
    method: 'POST',
  });
  await api.getRecording();
  assert.deepEqual(fetched.at(-1), {url: 'api/recording', method: 'GET'});
  await api.reuseSettings(3);
  assert.deepEqual(fetched.at(-1), {url: 'api/sessions/3/settings', method: 'POST'});
});

test('an error carries the status and the text of the answer', async () => {
  await assert.rejects(api.getSession(9), {message: '404 No session 9'});
});

test('a download is the request itself', () => {
  assert.equal(api.downloadUrl(1), 'api/sessions/1/recording.jfr');
});
