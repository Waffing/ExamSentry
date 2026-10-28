(function (root) {
  'use strict';
  const START = '\u200d', END = '\u2060', ZERO = '\u200b', ONE = '\u200c';
  function encode(text) {
    const bytes = new TextEncoder().encode(String(text).slice(0, 128));
    let value = START;
    for (const byte of bytes) for (let bit = 7; bit >= 0; bit--) value += ((byte >> bit) & 1) ? ONE : ZERO;
    return value + END;
  }
  function decode(text) {
    const matches = String(text).match(/\u200d([\u200b\u200c]{8,4096})\u2060/);
    if (!matches || matches[1].length % 8 !== 0) return '';
    const bytes = [];
    for (let offset = 0; offset < matches[1].length; offset += 8) {
      let byte = 0;
      for (let bit = 0; bit < 8; bit++) byte = (byte << 1) | Number(matches[1][offset + bit] === ONE);
      bytes.push(byte);
    }
    try { return new TextDecoder('utf-8', { fatal: true }).decode(new Uint8Array(bytes)); } catch (_) { return ''; }
  }
  const api = { encode, decode };
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.SentryWatermark = api;
})(typeof window === 'object' ? window : globalThis);
