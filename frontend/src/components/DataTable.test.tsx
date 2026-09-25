import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { DataTable } from './DataTable';

const rows = [
  { id: 'a', name: 'South Campus', n: 12 },
  { id: 'b', name: 'North Campus', n: 3 },
];
const columns = [
  {
    key: 'name',
    header: 'Campus',
    render: (r: (typeof rows)[number]) => r.name,
    sortValue: (r: (typeof rows)[number]) => r.name,
  },
  {
    key: 'n',
    header: 'Classes',
    render: (r: (typeof rows)[number]) => r.n,
    sortValue: (r: (typeof rows)[number]) => r.n,
    numeric: true,
  },
];

describe('DataTable', () => {
  it('renders a captioned table and sorts with aria-sort announced', async () => {
    render(
      <DataTable caption="Campuses" rows={rows} columns={columns} rowKey={(r) => r.id} empty={<p>none</p>} />,
    );
    const table = screen.getByRole('table', { name: 'Campuses' });
    await userEvent.click(within(table).getByRole('button', { name: /Campus/ }));
    expect(within(table).getAllByRole('columnheader')[0]).toHaveAttribute('aria-sort', 'ascending');
    const firstCell = () => within(table).getAllByRole('row')[1]!.querySelector('td')!.textContent;
    expect(firstCell()).toBe('North Campus');
    await userEvent.click(within(table).getByRole('button', { name: /Campus/ }));
    expect(firstCell()).toBe('South Campus');
  });

  it('sorts numbers numerically', async () => {
    render(
      <DataTable caption="Campuses" rows={rows} columns={columns} rowKey={(r) => r.id} empty={<p>none</p>} />,
    );
    await userEvent.click(screen.getByRole('button', { name: /Classes/ }));
    expect(screen.getAllByRole('row')[1]).toHaveTextContent('North Campus');
  });

  it('shows the empty state instead of an empty table', () => {
    render(
      <DataTable
        caption="Campuses"
        rows={[]}
        columns={columns}
        rowKey={(r) => r.id}
        empty={<p>No campuses</p>}
      />,
    );
    expect(screen.queryByRole('table')).toBeNull();
    expect(screen.getByText('No campuses')).toBeInTheDocument();
  });
});
