'use strict';
// Self-test for the TS/JS parser sidecar's parse(). No framework: `node test_parser.js`.
const assert = require('assert');
const { parse } = require('./parser_server');

let passed = 0;
function test(name, fn) {
  try { fn(); passed++; }
  catch (e) { console.error('FAIL ' + name + ': ' + (e.message || e)); process.exitCode = 1; }
}
const byName = (syms, n) => syms.find(s => s.name === n);
const byKind = (syms, k) => syms.filter(s => s.kind === k);

test('top-level class, interface, enum, function still extracted', () => {
  const s = parse('a.ts',
    'export interface Repo { find(id: string): string; }\n' +
    'export enum Status { Active, Closed }\n' +
    'export class Service { handle(id: string): string { return id; } }\n' +
    'export function topLevel(a: number): number { return a + 1; }\n');
  assert.strictEqual(byName(s, 'Repo').kind, 'INTERFACE');
  assert.strictEqual(byName(s, 'Status').kind, 'ENUM');
  assert.strictEqual(byName(s, 'Service').kind, 'CLASS');
  const fn = byName(s, 'topLevel');
  assert.strictEqual(fn.kind, 'METHOD');
  assert.strictEqual(fn.symbolPath, 'topLevel(number)');
});

test('exported arrow-const becomes a METHOD with params, return type and call edges', () => {
  const s = parse('a.ts',
    'function dep(n: number): number { return n; }\n' +
    'export const arrowHandler = (req: string): number => { return dep(req.length); };\n');
  const h = byName(s, 'arrowHandler');
  assert.strictEqual(h.kind, 'METHOD');
  assert.strictEqual(h.symbolPath, 'arrowHandler(string)');
  assert.strictEqual(h.returnType, 'number');
  assert.ok(h.modifiers.includes('exported'));
  assert.ok(h.callTargets.includes('dep'), 'expected dep edge, got ' + JSON.stringify(h.callTargets));
});

test('non-exported arrow-const and function-expression const are METHODs; async flagged', () => {
  const s = parse('a.ts',
    'const plain = (a, b) => a + b;\n' +
    'const fx = async function (x) { return x; };\n');
  assert.strictEqual(byName(s, 'plain').kind, 'METHOD');
  const fx = byName(s, 'fx');
  assert.strictEqual(fx.kind, 'METHOD');
  assert.ok(fx.modifiers.includes('async'));
});

test('plain value const stays a FIELD and keeps its type annotation', () => {
  const s = parse('a.ts', 'export const MAX: number = 10;\n');
  const f = byName(s, 'MAX');
  assert.strictEqual(f.kind, 'FIELD');
  assert.strictEqual(f.signature, 'MAX: number');
  assert.ok(f.modifiers.includes('exported'));
});

test('type alias maps to CLASS (no TYPE kind in the contract)', () => {
  const s = parse('a.ts', 'export type UserId = string;\n');
  const t = byName(s, 'UserId');
  assert.strictEqual(t.kind, 'CLASS');
  assert.strictEqual(t.signature, 'type UserId');
});

test('CommonJS module.exports.X = function is a METHOD with edges', () => {
  const s = parse('b.js',
    'const http = require("http");\n' +
    'function helper(x) { return x * 2; }\n' +
    'module.exports.start = function start(port) { return helper(port); };\n');
  const start = byName(s, 'start');
  assert.ok(start, 'start export must be a symbol');
  assert.strictEqual(start.kind, 'METHOD');
  assert.strictEqual(start.symbolPath, 'start(port)');
  assert.ok(start.callTargets.includes('helper'));
});

test('CommonJS exports.X = arrow is a METHOD', () => {
  const s = parse('b.js', 'exports.run = (a, b) => a + b;\n');
  const run = byName(s, 'run');
  assert.strictEqual(run.kind, 'METHOD');
  assert.strictEqual(run.symbolPath, 'run(a,b)');
});

test('CommonJS module.exports = function() names it by the function or default', () => {
  const named = parse('b.js', 'module.exports = function boot(opts) { return opts; };\n');
  assert.strictEqual(byName(named, 'boot').kind, 'METHOD');
  const anon = parse('b.js', 'module.exports = function (opts) { return opts; };\n');
  assert.strictEqual(byName(anon, 'default').kind, 'METHOD');
});

test('module.exports = {object} does not emit a spurious symbol', () => {
  const s = parse('b.js',
    'function a() {}\nfunction b() {}\nmodule.exports = { a, b };\n');
  assert.ok(!byName(s, 'module'), 'must not emit a "module" symbol');
  assert.ok(!byName(s, 'exports'), 'must not emit an "exports" symbol');
});

test('anonymous default exports get the synthetic name "default"', () => {
  assert.strictEqual(byName(parse('a.ts', 'export default function (x) { return x; }\n'), 'default').kind, 'METHOD');
  assert.strictEqual(byName(parse('a.ts', 'export default class { m() {} }\n'), 'default').kind, 'CLASS');
  assert.strictEqual(byName(parse('a.ts', 'export default (x: number) => x + 1;\n'), 'default').kind, 'METHOD');
});

test('named export default function keeps its own name', () => {
  const s = parse('a.ts', 'export default function bootstrap() { return 1; }\n');
  assert.strictEqual(byName(s, 'bootstrap').kind, 'METHOD');
  assert.ok(!byName(s, 'default'));
});

test('symbolPaths are unique within a file', () => {
  const s = parse('a.ts',
    'export const f = (a) => a;\n' +
    'export function g() {}\n' +
    'export class C { m() {} }\n');
  const paths = s.map(x => x.symbolPath);
  assert.strictEqual(new Set(paths).size, paths.length, 'duplicate symbolPath: ' + paths);
});

test('exactly one MODULE symbol, carrying imports', () => {
  const s = parse('a.ts', 'import { readFile } from "fs";\nexport const x = 1;\n');
  const mods = byKind(s, 'MODULE');
  assert.strictEqual(mods.length, 1);
  assert.ok(mods[0].imports.includes('fs'));
});

test('conformance: parse(sample.ts) matches the shared expected fixture', () => {
  const fs = require('fs');
  const path = require('path');
  const dir = path.join(__dirname, '..', 'conformance');
  const source = fs.readFileSync(path.join(dir, 'sample.ts'), 'utf8');
  const expected = JSON.parse(fs.readFileSync(path.join(dir, 'sample.ts.expected.json'), 'utf8'));
  const got = parse('sample.ts', source);
  for (const exp of expected.symbols) {
    const actual = got.find(s => s.symbolPath === exp.symbolPath);
    assert.ok(actual, 'missing symbol ' + exp.symbolPath);
    for (const [field, value] of Object.entries(exp)) {
      assert.deepStrictEqual(actual[field], value,
        exp.symbolPath + '.' + field + ': expected ' + JSON.stringify(value)
        + ' got ' + JSON.stringify(actual[field]));
    }
  }
  const mod = got.find(s => s.kind === 'MODULE');
  assert.deepStrictEqual(mod.imports, expected.moduleImports);
});

if (process.exitCode) console.error(passed + ' passed, see failures above');
else console.log(passed + ' passed');
