// @ts-check
/**
 * The page `jfr-web flame` writes: one file holding a recording's profile and
 * the flame graph panel of the interface, its modules inlined (see
 * jfrweb.export.FlamePage). Its filters change with no server.
 */
import {decode} from './profile/profile.js';
import {flamePanel} from './components/flame.js';
import {section} from './components/section.js';

/** @param {string} id */
function json(id) {
  return JSON.parse(document.getElementById(id)?.textContent ?? 'null');
}

const profile = Promise.resolve().then(() => decode(json('profile')));
const panel = flamePanel(profile, new AbortController().signal, {filter: json('filter') ?? {}});
document.getElementById('flame')?.append(panel.element, section('hot', panel.hot.title, ...panel.hot.content));
