import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { axeViolations, renderWithApp } from '../test/render';
import { AppShell } from './AppShell';
import { DesignSystemPage } from '../pages/DesignSystemPage';

const scope = (onSelectCampus = vi.fn()) => ({
  groupName: 'Nilgiri Learning Trust (synthetic)',
  campuses: [
    { id: 'n', name: 'North Campus' },
    { id: 's', name: 'South Campus' },
  ],
  selectedCampusId: null,
  onSelectCampus,
  personName: 'Asha Admin',
});

describe('AppShell', () => {
  it('always states the acting scope and lets the user switch campus', async () => {
    const onSelect = vi.fn();
    renderWithApp(
      <AppShell scope={scope(onSelect)} onSignOut={() => undefined}>
        <p>content</p>
      </AppShell>,
    );
    const bar = screen.getByRole('region', { name: 'You are working in' });
    expect(bar).toHaveTextContent('Nilgiri Learning Trust (synthetic)');
    expect(screen.getByRole('combobox', { name: 'Campus' })).toHaveDisplayValue('All campuses');
    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Campus' }), 'South Campus');
    expect(onSelect).toHaveBeenCalledWith('s');
  });

  it('does not show a campus scope to people without campus access (guardians)', () => {
    renderWithApp(
      <AppShell scope={{ ...scope(), campuses: [] }} onSignOut={() => undefined}>
        <p>content</p>
      </AppShell>,
    );
    const bar = screen.getByRole('region', { name: 'You are working in' });
    expect(bar).toHaveTextContent('Nilgiri Learning Trust (synthetic)');
    expect(bar).not.toHaveTextContent('All campuses');
    expect(screen.queryByRole('combobox', { name: 'Campus' })).toBeNull();
  });

  it('states a single campus as text rather than offering a one-item switch', () => {
    renderWithApp(
      <AppShell
        scope={{ ...scope(), campuses: [{ id: 'n', name: 'North Campus' }] }}
        onSignOut={() => undefined}
      >
        <p>content</p>
      </AppShell>,
    );
    expect(screen.getByRole('region', { name: 'You are working in' })).toHaveTextContent('North Campus');
    expect(screen.queryByRole('combobox')).toBeNull();
  });

  it('has a skip link and landmark structure', () => {
    renderWithApp(
      <AppShell scope={scope()} onSignOut={() => undefined}>
        <p>content</p>
      </AppShell>,
    );
    expect(screen.getByRole('link', { name: 'Skip to main content' })).toHaveAttribute('href', '#main');
    expect(screen.getByRole('navigation', { name: 'Main' })).toBeInTheDocument();
    expect(screen.getByRole('main')).toBeInTheDocument();
  });

  it('has no automatically detectable accessibility violations (shell + component gallery)', async () => {
    const { container } = renderWithApp(
      <AppShell scope={scope()} onSignOut={() => undefined}>
        <DesignSystemPage />
      </AppShell>,
    );
    expect(await axeViolations(container)).toEqual([]);
  });
});
