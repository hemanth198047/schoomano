import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Field } from './Field';

describe('Field', () => {
  it('associates label, hint and error with the control', () => {
    render(
      <Field label="Mobile number" hint="Ten digits" error="Enter a valid number." required>
        {(p) => <input {...p} />}
      </Field>,
    );
    const input = screen.getByLabelText(/Mobile number/);
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toBeRequired();
    expect(input).toHaveAccessibleDescription('Ten digits Enter a valid number.');
  });

  it('has no invalid state without an error', () => {
    render(<Field label="Name">{(p) => <input {...p} />}</Field>);
    expect(screen.getByLabelText('Name')).not.toHaveAttribute('aria-invalid');
  });
});
