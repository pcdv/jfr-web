// @ts-check
/**
 * The temperature of the frames of a flame graph: how hot a method is, wherever
 * it appears, minus what this frame already shows of it.
 *
 * A method running at the top of stacks in many places is split in as many
 * frames, each too narrow to notice. Its heat is the time it spent running (its
 * self time) in all its frames together; a frame is as hot as the part of that
 * time which is in the other frames. So a method with one frame is cold, however
 * wide it is, and a method spread over twenty callers is hot in each of them.
 */

/** @typedef {import('../profile/profile.js').Frame} Frame */

/** The exponent of the scale: below 1, so that a frame with a tenth of the hottest one's is not cold. */
const CURVE = 0.4;

/** The least share of the time the hottest frame has elsewhere: a graph with none stays cold. */
const MIN_HOTTEST = 0.05;

/**
 * @typedef {object} Heat
 * @property {(name: string) => number} method the self time of a method in all its frames, in the
 *           unit of the totals
 * @property {(frame: Frame) => number} elsewhere the share of the whole time that the method of a
 *           frame spent running in other frames, between 0 and 1
 * @property {(frame: Frame) => number} of its temperature, 0 for cold and 1 for the hottest frame
 *           of the graph; what it has elsewhere to the power 0.4, to tell small amounts apart
 */

/**
 * @param {Frame} root
 * @returns {Heat}
 */
export function heatOf(root) {
  /** @type {Map<string, number>} */
  const self = new Map();
  /** @param {Frame} f */
  const sum = f => {
    self.set(f.name, (self.get(f.name) ?? 0) + f.self);
    f.children.forEach(sum);
  };
  sum(root);

  const total = root.total || 1;
  /** @param {Frame} f */
  const elsewhere = f => Math.max(0, (self.get(f.name) ?? 0) - f.self) / total;
  let hottest = MIN_HOTTEST;
  /** @param {Frame} f */
  const scan = f => {
    hottest = Math.max(hottest, elsewhere(f));
    f.children.forEach(scan);
  };
  scan(root);

  return {
    method: name => self.get(name) ?? 0,
    elsewhere,
    of: f => Math.pow(elsewhere(f) / hottest, CURVE),
  };
}
