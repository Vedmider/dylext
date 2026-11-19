'use strict';
/*
 * Dylext TypeScript/JavaScript parser sidecar. Honours parsers/CONTRACT.md:
 *   POST /parse {repo, filePath, language, source} -> {symbols:[...], parseOk:true}
 *   GET  /health -> {status:"UP"}
 * Uses the TypeScript compiler API (parses .ts/.tsx/.js/.jsx/.mjs/.cjs). Dylext owns identity,
 * hashing, storage and edges; this only reports neutral symbols. On failure returns {symbols:[]}.
 */
const http = require('http');
const ts = require('typescript');

const PORT = process.env.PORT || 9093;

function lineOf(sf, pos) {
  return sf.getLineAndCharacterOfPosition(pos).line + 1; // 1-based
}

function firstDocLine(node) {
  const full = node.getFullText();
  const ranges = ts.getLeadingCommentRanges(full, 0) || [];
  for (const r of ranges) {
    const text = full.slice(r.pos, r.end).replace(/^\/\*\*?|\*\/$/g, '').split('\n')
      .map(l => l.replace(/^\s*\*?\s?/, '').trim()).find(l => l.length > 0);
    if (text) return text;
  }
  return '';
}

function params(node) {
  if (!node.parameters) return '';
  return node.parameters.map(p => (p.type ? p.type.getText() : p.name.getText())).join(',');
}

function isExported(node) {
  return !!(node.modifiers && node.modifiers.some(m =>
    m.kind === ts.SyntaxKind.ExportKeyword || m.kind === ts.SyntaxKind.DefaultKeyword));
}

function modifiers(node) {
  const out = [];
  if (isExported(node)) out.push('exported');
  (node.modifiers || []).forEach(m => {
    if (m.kind === ts.SyntaxKind.StaticKeyword) out.push('static');
    if (m.kind === ts.SyntaxKind.AsyncKeyword) out.push('async');
  });
  return out;
}

function callTargets(node) {
  const out = new Set();
  (function walk(n) {
    if (ts.isCallExpression(n)) {
      const e = n.expression;
      // Bare qualified name (no arity) per CONTRACT.md, so Dylext's edge resolver can match it.
      if (ts.isIdentifier(e)) out.add(e.text);
      else if (ts.isPropertyAccessExpression(e)) out.add(e.getText());
    }
    ts.forEachChild(n, walk);
  })(node);
  return [...out];
}

function parse(filePath, source) {
  const sf = ts.createSourceFile(filePath || 'f.ts', source, ts.ScriptTarget.Latest, true,
    (filePath || '').endsWith('x') ? ts.ScriptKind.TSX : undefined);
  const symbols = [];
  const imports = [];

  function sym(kind, name, symbolPath, signature, node, extra) {
    symbols.push(Object.assign({
      kind, name, symbolPath, signature,
      docFirstLine: firstDocLine(node),
      startLine: lineOf(sf, node.getStart(sf)),
      endLine: lineOf(sf, node.getEnd()),
    }, extra || {}));
  }

  function member(owner, m) {
    const nm = m.name ? m.name.getText(sf) : '';
    if (ts.isConstructorDeclaration(m)) {
      sym('CONSTRUCTOR', 'constructor', owner + '#constructor(' + params(m) + ')',
        'constructor(' + params(m) + ')', m, { receiver: owner, callTargets: callTargets(m) });
    } else if (ts.isMethodDeclaration(m) || ts.isMethodSignature(m)
               || ts.isGetAccessor(m) || ts.isSetAccessor(m)) {
      const ret = m.type ? ': ' + m.type.getText(sf) : '';
      sym('METHOD', nm, owner + '#' + nm + '(' + params(m) + ')',
        nm + '(' + params(m) + ')' + ret, m,
        { receiver: owner, returnType: m.type ? m.type.getText(sf) : undefined,
          modifiers: modifiers(m), callTargets: callTargets(m) });
    } else if (ts.isPropertyDeclaration(m) || ts.isPropertySignature(m)) {
      const t = m.type ? ': ' + m.type.getText(sf) : '';
      sym('FIELD', nm, owner + '#' + nm, nm + t, m,
        { receiver: owner, returnType: m.type ? m.type.getText(sf) : undefined });
    }
  }

  function isFnLike(n) {
    return n && (ts.isArrowFunction(n) || ts.isFunctionExpression(n));
  }

  function isDefaultExport(node) {
    return !!(node.modifiers && node.modifiers.some(m => m.kind === ts.SyntaxKind.DefaultKeyword));
  }

  // A value binding: function-valued -> METHOD (params + call edges), otherwise FIELD.
  function emitValue(name, init, exported, node) {
    if (isFnLike(init)) {
      const ret = init.type ? ': ' + init.type.getText(sf) : '';
      const mods = [];
      if (exported) mods.push('exported');
      (init.modifiers || []).forEach(m => {
        if (m.kind === ts.SyntaxKind.AsyncKeyword) mods.push('async');
      });
      sym('METHOD', name, name + '(' + params(init) + ')',
        'const ' + name + ' = (' + params(init) + ')' + ret + ' =>', node,
        { returnType: init.type ? init.type.getText(sf) : undefined,
          modifiers: mods, callTargets: callTargets(init) });
    } else {
      const t = node && node.type ? ': ' + node.type.getText(sf) : '';
      sym('FIELD', name, name, name + t, node, { modifiers: exported ? ['exported'] : [] });
    }
  }

  sf.statements.forEach(st => {
    if (ts.isImportDeclaration(st) && st.moduleSpecifier) {
      imports.push(st.moduleSpecifier.getText(sf).replace(/['"]/g, ''));
    } else if (ts.isClassDeclaration(st) && (st.name || isDefaultExport(st))) {
      const n = st.name ? st.name.text : 'default';
      sym('CLASS', n, n, 'class ' + n, st, { modifiers: modifiers(st) });
      st.members.forEach(m => member(n, m));
    } else if (ts.isInterfaceDeclaration(st) && st.name) {
      const n = st.name.text;
      sym('INTERFACE', n, n, 'interface ' + n, st, { modifiers: modifiers(st) });
      st.members.forEach(m => member(n, m));
    } else if (ts.isTypeAliasDeclaration(st) && st.name) {
      // No neutral TYPE kind in the contract; a named type maps to CLASS (as Go's `type`).
      const n = st.name.text;
      sym('CLASS', n, n, 'type ' + n, st, { modifiers: modifiers(st) });
    } else if (ts.isEnumDeclaration(st) && st.name) {
      const n = st.name.text;
      sym('ENUM', n, n, 'enum ' + n, st, { modifiers: modifiers(st) });
    } else if (ts.isFunctionDeclaration(st) && (st.name || isDefaultExport(st))) {
      const n = st.name ? st.name.text : 'default';
      const ret = st.type ? ': ' + st.type.getText(sf) : '';
      sym('METHOD', n, n + '(' + params(st) + ')', 'function ' + n + '(' + params(st) + ')' + ret, st,
        { returnType: st.type ? st.type.getText(sf) : undefined,
          modifiers: modifiers(st), callTargets: callTargets(st) });
    } else if (ts.isVariableStatement(st)) {
      const exported = isExported(st);
      st.declarationList.declarations.forEach(d => {
        emitValue(d.name.getText(sf), d.initializer, exported, d);
      });
    } else if (ts.isExportAssignment(st) && st.expression) {
      // `export default <expr>` (arrow/function/other value)
      emitValue('default', st.expression, true, st);
    } else if (ts.isExpressionStatement(st) && ts.isBinaryExpression(st.expression)
               && st.expression.operatorToken.kind === ts.SyntaxKind.EqualsToken) {
      // CommonJS: module.exports.X = ... / exports.X = ... / module.exports = function(){}
      const lhs = st.expression.left, rhs = st.expression.right;
      if (ts.isPropertyAccessExpression(lhs)) {
        const base = lhs.expression.getText(sf);
        if (base === 'module.exports' || base === 'exports') {
          emitValue(lhs.name.getText(sf), rhs, true, st);
        } else if (lhs.getText(sf) === 'module.exports' && isFnLike(rhs)) {
          emitValue(rhs.name && rhs.name.text ? rhs.name.text : 'default', rhs, true, st);
        }
      }
    }
  });

  symbols.unshift({
    kind: 'MODULE', name: (filePath || 'module').split('/').pop(), symbolPath: '<module>',
    signature: symbols.map(s => s.signature).join('\n'),
    docFirstLine: '', startLine: 1, endLine: lineOf(sf, source.length),
    imports: imports,
  });
  return symbols;
}

const server = http.createServer((req, res) => {
  if (req.method === 'GET' && req.url === '/health') {
    res.writeHead(200, { 'content-type': 'application/json' });
    return res.end('{"status":"UP","tier":"T2"}');
  }
  if (req.method === 'POST' && req.url === '/parse') {
    let body = '';
    req.on('data', c => { body += c; });
    req.on('end', () => {
      let symbols = [];
      let parseOk = true;
      try {
        const j = JSON.parse(body);
        symbols = parse(j.filePath || 'f.ts', j.source || '');
      } catch (e) {
        parseOk = false;
        symbols = [];
      }
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ symbols, parseOk }));
    });
    return;
  }
  res.writeHead(404);
  res.end();
});

if (require.main === module) {
  server.listen(PORT, () => console.log('ts-parser on ' + PORT));
}

module.exports = { parse };
