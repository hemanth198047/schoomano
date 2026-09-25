import { render } from '@testing-library/react';
import { expect, it } from 'vitest';
import { axeViolations } from './render';

it('control: axe really reports violations (guards against a vacuous a11y test)', async () => {
  const { container } = render(
    <main>
      <input type="text" />
      <img src="x.png" />
    </main>,
  );
  const v = await axeViolations(container);
  expect(v.some((x) => x.startsWith('label'))).toBe(true);
  expect(v.some((x) => x.startsWith('image-alt'))).toBe(true);
});
