// Runs the pure logic suites in Node; DevEco still checks ArkTS and ArkUI.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const esbuild = require('../../ds-bot/node_modules/esbuild');
const root = path.resolve(__dirname, '../entry/src');
const cache = new Map();
const tests = [];
const suiteStack = [];
const hypium = {
  describe(name, cb) { suiteStack.push(name); try { cb(); } finally { suiteStack.pop(); } },
  it(name, flags, cb) { tests.push({ name: suiteStack.concat(name).join('/'), cb }); },
  expect(actual) { return {
    assertEqual(expected) { assert.strictEqual(actual, expected); },
    assertTrue() { assert.strictEqual(actual, true); },
    assertFalse() { assert.strictEqual(actual, false); },
    assertNull() { assert.strictEqual(actual, null); }
  }; }
};
function load(file) {
  if (cache.has(file)) return cache.get(file).exports;
  const mod = { exports: {} }; cache.set(file, mod);
  const output = esbuild.transformSync(fs.readFileSync(file, 'utf8'), { loader: 'ts', format: 'cjs', target: 'es2022', sourcefile: file }).code;
  const requireLocal = (specifier) => {
    if (specifier === '@ohos/hypium') return hypium;
    if (!specifier.startsWith('.')) throw new Error('Unexpected runtime import: ' + specifier);
    return load(path.resolve(path.dirname(file), specifier + '.ets'));
  };
  vm.runInThisContext('(function(exports, require, module) {\n' + output + '\n})', { filename: file })(mod.exports, requireLocal, mod);
  return mod.exports;
}
(async () => {
  load(path.join(root, 'test/List.test.ets')).default();
  let failed = 0;
  for (const test of tests) {
    try { await test.cb(); } catch (error) { failed++; console.log('FAIL', test.name, String(error)); }
  }
  console.log(JSON.stringify({ mode: 'Node esbuild TS transpilation + Hypium assertion adapter; no ArkUI runtime or ArkTS typecheck', tests: tests.length, passed: tests.length - failed, failed }, null, 2));
  process.exitCode = failed ? 1 : 0;
})();
