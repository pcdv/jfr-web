// Takes the screenshots of the README from a running demo, in headless Edge
// (or Chrome) driven over the DevTools protocol. Node 22+, no dependency.
//
//   ./gradlew demo -Pdemo.session=true -Pdemo.load=true
//   # let the load run a few minutes, for the live and history pages
//   node docs/screenshots/capture.mjs <profiler URL>
//
//   ./gradlew tcpDemo -Pdemo.session=true -Pdemo.load=true
//   node docs/screenshots/capture.mjs <profiler URL> tcp- order
//
// The profiler's URL is the one the demo prints. The prefix names the files,
// and the operation is the one whose flame graph and slowest instance are
// shown. BROWSER= sets the path of Edge or Chrome.
import {spawn} from 'node:child_process';
import {mkdtempSync, rmSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {dirname, join} from 'node:path';
import {fileURLToPath} from 'node:url';

const base = process.argv[2] ?? 'http://localhost:8080/';
const prefix = process.argv[3] ?? '';
const operation = process.argv[4] ?? 'GET /sort/:n';
const browser = process.env.BROWSER ?? 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const outDir = dirname(fileURLToPath(import.meta.url));
const port = 9338;
const width = 1200;

const profile = mkdtempSync(join(tmpdir(), 'jfrweb-shots-'));
const proc = spawn(browser, ['--headless=new', '--disable-gpu', '--no-first-run',
  '--hide-scrollbars', `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`, 'about:blank']);
const sleep = (/** @type {number} */ ms) => new Promise(r => setTimeout(r, ms));

let target;
for (let i = 0; i < 50 && !target; i++) {
  await sleep(200);
  try {
    target = (await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()).find(t => t.type === 'page');
  } catch (e) { /* not up yet */ }
}
const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise(r => ws.addEventListener('open', r));
let nextId = 1;
const pending = new Map();
ws.addEventListener('message', m => {
  const msg = JSON.parse(m.data);
  if (msg.id && pending.has(msg.id)) {
    pending.get(msg.id)(msg);
    pending.delete(msg.id);
  }
});
const send = (method, params = {}) => new Promise(resolve => {
  const id = nextId++;
  pending.set(id, resolve);
  ws.send(JSON.stringify({id, method, params}));
});
const evaluate = async expr =>
  (await send('Runtime.evaluate', {expression: expr, returnByValue: true, awaitPromise: true})).result.result.value;

async function open(hash) {
  await send('Page.navigate', {url: base + hash});
  await sleep(4000);
}

/** the h2 of a section, by the start of its title */
const sectionOf = title =>
  `[...document.querySelectorAll('details.section')].find(d => d.querySelector('h2').textContent.startsWith(${JSON.stringify(title)}))`;

/**
 * Saves the part of the page from the top of the first element to the bottom
 * of the last one, given as JS expressions, across the page's whole width.
 */
async function shot(name, first, last = first) {
  const r = await evaluate(`(() => {
    const a = (${first}).getBoundingClientRect(), b = (${last}).getBoundingClientRect();
    return {y: a.top + scrollY, h: b.bottom - a.top};
  })()`);
  const top = Math.max(0, r.y - 8);
  const clip = {x: 0, y: top, width, height: r.y + r.h + 16 - top, scale: 1};
  const res = await send('Page.captureScreenshot', {format: 'png', clip, captureBeyondViewport: true});
  writeFileSync(join(outDir, prefix + name), Buffer.from(res.result.data, 'base64'));
  console.log(prefix + name, `${width}x${Math.round(clip.height)}`);
}

/** drags across the timeline, between two fractions of its width */
async function drag(f1, f2) {
  const box = await evaluate(`(() => {
    const el = document.querySelector('svg.timeline').lastElementChild;
    el.scrollIntoView({block: 'center'});
    const r = el.getBoundingClientRect();
    return {x: r.x, y: r.y, w: r.width, h: r.height};
  })()`);
  const y = box.y + box.h / 2;
  const mouse = (type, f) => send('Input.dispatchMouseEvent', {
    type, x: box.x + box.w * f, y, button: 'left', buttons: type === 'mouseReleased' ? 0 : 1, clickCount: 1});
  await mouse('mouseMoved', f1);
  await mouse('mousePressed', f1);
  await mouse('mouseMoved', (f1 + f2) / 2);
  await mouse('mouseMoved', f2);
  await mouse('mouseReleased', f2);
  await sleep(2000);
}

/** scrolls the flame graph to its roots, at the bottom of its frame */
async function flameRoots() {
  await evaluate(`(() => {
    const w = document.querySelector('iframe.flame').contentWindow;
    w.scrollTo(0, w.document.documentElement.scrollHeight);
  })()`);
  await sleep(300);
}

const click = async (selector, text) => {
  await evaluate(`[...document.querySelectorAll(${JSON.stringify(selector)})]
    .find(e => e.textContent.includes(${JSON.stringify(text)})).click()`);
  await sleep(2500);
};

try {
  await send('Page.enable');
  await send('Emulation.setDeviceMetricsOverride', {width, height: 900, deviceScaleFactor: 1, mobile: false});
  await send('Emulation.setEmulatedMedia', {features: [{name: 'prefers-color-scheme', value: 'light'}]});

  // the sessions, with the auto mode panel unfolded
  await open('#/');
  await evaluate(`document.querySelectorAll('details:not([open])').forEach(d => d.open = true)`);
  await sleep(300);
  await shot('sessions.png', `document.body`);

  // a session's report: how it was recorded, then its timeline
  const session = await evaluate(`fetch('api/sessions').then(r => r.json())
    .then(l => l.filter(s => s.state === 'STOPPED' && !s.trigger.startsWith('snapshot')).pop().id)`);
  await open(`#/sessions/${session}`);
  await shot('report.png', `document.querySelector('p.summary')`, sectionOf('Timeline'));

  // an interval selected on the timeline narrows the operations and the rest
  await drag(0.3, 0.6);
  await shot('interval.png', sectionOf('Timeline'), sectionOf('Operations'));

  // the flame graph of an operation, picked in the table
  await drag(0.5, 0.5);
  await click('tr.clickable', operation);
  await flameRoots();
  await shot('flame.png', sectionOf('Flame graph'));

  // the slowest of these operations: its details and flame graph
  await evaluate(`document.querySelector('tr[data-id]').click()`);
  await sleep(3000);
  await flameRoots();
  await shot('request.png', `document.querySelector('details.section .card')`, sectionOf('Flame graph'));

  await open('#/live');
  await shot('live.png', `document.body`);

  // the last hour of the history, zoomed in on the part that has data
  await open('#/history');
  const first = await evaluate(`(() => {
    const plot = document.querySelector('svg.timeline').lastElementChild.getBoundingClientRect();
    const xs = [...document.querySelectorAll('svg.timeline rect:not(.hover):not(.selection)')]
      .map(r => r.getBoundingClientRect())
      .filter(r => r.height > 0 && r.width < plot.width / 2)
      .map(r => r.left);
    return (Math.min(...xs) - plot.left) / plot.width;
  })()`);
  if (first > 0.1)
    await drag(Math.max(0, first - 0.01), 0.999);
  await shot('history.png', `document.body`, sectionOf('Operations'));
}
finally {
  ws.close();
  // killing the process leaves Edge's children running, on the debugging
  // port: the next run would drive their stale page
  await closeBrowser();
  proc.kill();
  await sleep(1000);
  rmSync(profile, {recursive: true, force: true, maxRetries: 5});
}

/** Closes the whole browser, through its own endpoint: Browser.close has no effect on a page's. */
async function closeBrowser() {
  try {
    const {webSocketDebuggerUrl} = await (await fetch(`http://127.0.0.1:${port}/json/version`)).json();
    const browser = new WebSocket(webSocketDebuggerUrl);
    await new Promise((resolve, reject) => {
      browser.addEventListener('open', resolve);
      browser.addEventListener('error', reject);
    });
    browser.send(JSON.stringify({id: 1, method: 'Browser.close'}));
    await new Promise(r => browser.addEventListener('close', r));
  } catch (e) { /* already gone */ }
}
