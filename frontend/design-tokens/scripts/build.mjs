// Generates web CSS custom properties and a typed TS object (for React Native) from tokens.json.
// `--check` fails if the committed TS file is out of date (used in CI).
import { readFileSync, writeFileSync, mkdirSync, existsSync, readdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '..');
const tokens = JSON.parse(readFileSync(resolve(root, 'tokens.json'), 'utf8'));
delete tokens.$description;

const kebab = (s) =>
  s
    .replace(/([a-z])([0-9])/g, '$1-$2')
    .replace(/([a-z0-9])([A-Z])/g, '$1-$2')
    .toLowerCase();
const lines = [];
for (const [group, values] of Object.entries(tokens)) {
  for (const [name, value] of Object.entries(values)) {
    lines.push(`  --${kebab(group)}-${kebab(name)}: ${value};`);
  }
}
const css = `/* GENERATED from frontend/design-tokens/tokens.json. Do not edit. */\n:root {\n${lines.join('\n')}\n}\n`;
const ts = `// GENERATED from tokens.json by scripts/build.mjs. Do not edit.\nexport const tokens = ${JSON.stringify(tokens, null, 2)} as const;\n\nexport type Tokens = typeof tokens;\n`;

const tsPath = resolve(root, 'src/tokens.ts');
if (process.argv.includes('--check')) {
  const current = existsSync(tsPath) ? readFileSync(tsPath, 'utf8') : '';
  if (current !== ts) {
    console.error(
      'design tokens are out of date: run `npm run tokens` and commit design-tokens/src/tokens.ts',
    );
    process.exit(1);
  }
  // Every var(--x) used by web styles must be a generated token (catches renamed or misspelled tokens).
  const defined = new Set(lines.map((l) => l.trim().split(':')[0]));
  const stylesDir = resolve(root, '../src/styles');
  const missing = [];
  for (const f of readdirSync(stylesDir).filter((n) => n.endsWith('.css') && !n.includes('generated'))) {
    for (const m of readFileSync(resolve(stylesDir, f), 'utf8').matchAll(/var\((--[a-z0-9-]+)/g)) {
      if (!defined.has(m[1])) missing.push(`${f}: ${m[1]}`);
    }
  }
  if (missing.length) {
    console.error('undefined design tokens used:\n  ' + missing.join('\n  '));
    process.exit(1);
  }
  console.log('design tokens up to date; all CSS token references resolve');
} else {
  writeFileSync(tsPath, ts);
  const cssPath = resolve(root, '../src/styles/tokens.generated.css');
  mkdirSync(dirname(cssPath), { recursive: true });
  writeFileSync(cssPath, css);
  console.log(`wrote ${lines.length} tokens`);
}
