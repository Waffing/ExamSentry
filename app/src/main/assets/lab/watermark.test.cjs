const test = require('node:test');
const assert = require('node:assert/strict');
const watermark = require('./watermark.js');
test('session watermark round-trips through surrounding pasted text', () => {
  const sid = 'session-92bb11ab-1234';
  assert.equal(watermark.decode('样本' + watermark.encode(sid) + '结束'), sid);
});
test('UTF-8 identifiers round-trip', () => assert.equal(watermark.decode(watermark.encode('本地会话')), '本地会话'));
test('plain and truncated text do not claim a decoded watermark', () => {
  assert.equal(watermark.decode('普通文本'), '');
  assert.equal(watermark.decode(watermark.encode('session').slice(0, -1)), '');
  assert.equal(watermark.decode('\u200d\u200b\u2060'), '');
});
test('invalid UTF-8 does not become another session', () => assert.equal(watermark.decode('\u200d' + '\u200c'.repeat(8) + '\u2060'), ''));
