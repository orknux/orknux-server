// The upload limit the desktop hands the jar, without Electron: `node --test test/` in desktop/docker.
const test = require('node:test');
const assert = require('node:assert/strict');
const { uploadLimitOf } = require('../src/server');

test('the desktop app accepts 500 MB where the environment says nothing', () => {
  assert.equal(uploadLimitOf({}), '500');
});

test('a limit already in the environment wins', () => {
  assert.equal(uploadLimitOf({ ORKNUX_ATTACHMENTS_MAX_FILE_SIZE_MB: '40' }), '40');
});

test('an empty one is not a limit, and the desktop default stands', () => {
  assert.equal(uploadLimitOf({ ORKNUX_ATTACHMENTS_MAX_FILE_SIZE_MB: ' ' }), '500');
});
