// @ts-check
/**
 * A share of a total in a table cell, as the tables of operations and of hot
 * methods show it.
 */
import {h} from '../lib/dom.js';
import * as fmt from '../format.js';

/**
 * The share of a total taken by one row: a bar scaled to the biggest row, so
 * that the longest bar spans the cell, and the percentage of the total.
 *
 * @param {number} total of the row
 * @param {number} sum the total of all rows
 * @param {number} max that of the biggest row
 * @param {string} [of] what the sum is, for the tooltip
 */
export function shareCell(total, sum, max, of = 'the time spent in all operations') {
  const share = sum > 0 ? fmt.percent(total / sum) : '';
  return h('td', {class: 'share', title: sum > 0 ? `${share} of ${of}` : null},
    h('span', {class: 'share-track'},
      h('span', {class: 'share-bar', style: `width: ${max > 0 ? total / max * 100 : 0}%`})),
    h('span', {class: 'share-label'}, share));
}
