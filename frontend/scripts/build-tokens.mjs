// Design tokens: src/styles/tokens.json -> src/styles/tokens.generated.css (CSS custom properties).
// `--check` (used by `npm run check` and CI) fails if any stylesheet uses a var(--token) that is not defined.
import { readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const stylesDir = resolve(dirname(fileURLToPath(import.meta.url)), '../src/styles');
const tokens = JSON.parse(readFileSync(resolve(stylesDir, 'tokens.json'), 'utf8'));
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
const css = `/* GENERATED from src/styles/tokens.json by scripts/build-tokens.mjs. Do not edit. */\n:root {\n${lines.join('\n')}\n}\n`;

if (process.argv.includes('--check')) {
  const defined = new Set(lines.map((l) => l.trim().split(':')[0]));
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
  console.log(`design tokens ok: ${lines.length} tokens, all CSS token references resolve`);
} else {
  writeFileSync(resolve(stylesDir, 'tokens.generated.css'), css);
  console.log(`wrote ${lines.length} tokens`);
}
