// @ts-check
/**
 * What the next session records: a preset, a few events to switch on or off
 * with their period or threshold, and JFR settings written by hand. A change
 * is kept by the profiler as soon as it is made, for the sessions started by
 * hand and by the auto mode.
 */
import * as api from '../api.js';
import {h, replace} from '../lib/dom.js';
import {readSetting, writeSetting} from '../lib/settings.js';

/** Whether the panel is unfolded, kept per browser. */
const OPEN_KEY = 'jfrweb.recording.open';

/**
 * A foldable panel, whose title tells what is recorded when folded.
 *
 * @param {api.RecordingState} initial
 * @param {boolean} rolling whether a rolling recording runs in the same JVM, for the snapshots: JFR merges
 *        its settings with those of a session
 * @returns {{element: HTMLElement, update: (state: api.RecordingState) => void}} update: shows what the
 *          profiler says, e.g. once a session started or stopped
 */
export function recordingPanel(initial, rolling) {
  const summary = h('span', {class: 'hint'});
  const status = h('span', {class: 'hint'});
  const lock = h('div', {class: 'hint warn'});

  const preset = h('select', {id: 'rec-preset'},
    initial.presets.map(p => h('option', {value: p.id}, p.label)));
  const presetHint = h('div', {class: 'hint'});

  /** @type {Map<string, {box: HTMLInputElement, value: HTMLInputElement | null}>} */
  const rows = new Map();
  const eventRows = initial.events.map(event => {
    const box = h('input', {type: 'checkbox', id: `rec-${event.id}`});
    const value = event.option
      ? h('input', {type: 'text', class: 'short', 'aria-label': `${event.label}: ${event.optionLabel}`,
                    placeholder: event.option})
      : null;
    rows.set(event.id, {box, value});
    return [
      h('label', {class: 'label', for: box.id}, event.label),
      h('div', null,
        h('div', {class: 'inline'}, box, value ? [' ', event.optionLabel, ' ', value] : null),
        h('div', {class: 'hint'}, event.hint)),
    ];
  });

  const other = h('textarea', {id: 'rec-other', class: 'wide mono', rows: 3, spellcheck: 'false',
                               placeholder: 'jdk.ClassLoad\njdk.ClassLoad#threshold=5 ms'});
  const unknown = h('div', {class: 'hint warn'});
  const required = h('div', {class: 'hint'});
  const depth = h('span');

  const form = h('form', {class: 'form-grid', onsubmit: (/** @type {Event} */ e) => e.preventDefault()},
    h('label', {class: 'label', for: 'rec-preset'}, 'Start from'),
    h('div', null,
      preset, ' ',
      h('button', {type: 'button', title: 'Back to what the profiler was started with',
                   onclick: () => save({preset: 'standard', enabled: {}, values: {}, other: ''})}, 'Reset'),
      ' ', status, lock,
      presetHint,
      rolling
        ? h('div', {class: 'hint'}, 'A rolling recording for the snapshots runs in this JVM, with the settings of the ' +
            'Light preset unless it was given others. JFR merges the settings of recordings that run together: ' +
            'an event is on if any of them switches it on, and the shortest period or lowest threshold wins. ' +
            'A slower period, a higher threshold, or an event switched off here does not lower what that recording records.')
        : null),
    eventRows,
    h('label', {class: 'label', for: 'rec-other'}, 'Other events'),
    h('div', null, other,
      h('div', {class: 'hint'}, 'One per line, as for jcmd JFR.start: an event name switches it on, ' +
        'event#setting=value sets one of enabled, threshold, period, stackTrace, cutoff, throttle. ' +
        'A setting alone does not switch its event on.'),
      unknown, required),
    h('span', {class: 'label'}, 'Stack depth'),
    h('div', null, depth,
      h('div', {class: 'hint'}, 'Set once per JVM, before JFR records for the first time: it cannot change here.')));

  form.addEventListener('change', e => {
    if (e.target === preset)
      save({preset: preset.value, enabled: {}, values: {}, other: ''});
    else
      save(collect());
  });

  const element = h('details', {class: 'card recording', open: readSetting(OPEN_KEY) === 'true'},
    h('summary', null, 'Recording ', summary), form);
  element.addEventListener('toggle', () => writeSetting(OPEN_KEY, String(element.open)));

  /** @returns {api.RecordingChoice} */
  function collect() {
    /** @type {api.RecordingChoice} */
    const c = {preset: preset.value, enabled: {}, values: {}, other: other.value};
    for (const [id, r] of rows) {
      c.enabled[id] = r.box.checked;
      if (r.value)
        c.values[id] = r.value.value;
    }
    return c;
  }

  /** @param {api.RecordingChoice} c */
  async function save(c) {
    status.className = 'hint';
    status.textContent = 'Saving…';
    try {
      show(await api.setRecording(c));
      status.textContent = 'Kept, for the next sessions';
    }
    catch (e) {
      // the answer's text, without its status
      status.className = 'hint warn';
      status.textContent = (e instanceof Error ? e.message : String(e)).replace(/^\d+ /, '');
    }
  }

  /** @param {api.RecordingState} s */
  function show(s) {
    preset.value = s.preset;
    replace(presetHint, s.presets.find(p => p.id === s.preset)?.description ?? '');
    for (const e of s.events) {
      const r = rows.get(e.id);
      if (!r)
        continue;
      r.box.checked = e.enabled;
      if (r.value)
        r.value.value = e.value;
    }
    other.value = s.other;
    replace(unknown, s.unknown.length
      ? `Not known by the JVM (yet): ${s.unknown.join(', ')}. An event of the application is known once its class is loaded.`
      : s.other && !s.known ? 'Event names are not checked: the JVM does not list its events yet.' : null);
    replace(required, `Always recorded, for the operations: ${s.required.join(', ')}.`);
    replace(depth, s.stackDepth ? `${s.stackDepth} frames` : 'not known yet');
    replace(lock, s.recording ? 'A session is recording: it keeps its settings. Change them once it stopped.' : null);
    replace(summary, '· ', describe(s));

    for (const r of rows.values()) {
      r.box.disabled = s.recording;
      if (r.value)
        r.value.disabled = s.recording || !r.box.checked;
    }
    preset.disabled = other.disabled = s.recording;
    for (const b of form.querySelectorAll('button'))
      b.disabled = s.recording;
  }

  // a box switched on or off enables its value at once, before the change is kept
  form.addEventListener('input', e => {
    for (const r of rows.values())
      if (e.target === r.box && r.value)
        r.value.disabled = !r.box.checked;
  });

  show(initial);
  return {element, update: show};
}

/**
 * What is recorded in a few words: the preset, whether it was changed, and
 * how Java code is sampled.
 *
 * @param {api.RecordingState} s
 */
function describe(s) {
  const preset = s.presets.find(p => p.id === s.preset)?.label ?? s.preset;
  const java = s.events.find(e => e.id === 'java');
  return [preset + (s.standard ? '' : ', changed'),
          java && java.enabled ? `Java samples${java.value ? ' every ' + java.value : ''}` : 'no Java samples',
         ].join(' · ');
}
