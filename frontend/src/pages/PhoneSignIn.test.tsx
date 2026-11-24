import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SessionProvider } from '../app/SessionProvider';
import { useSession } from '../app/session';
import { I18nProvider } from '../i18n/I18nProvider';
import { axeViolations, stubFetch } from '../test/render';
import { PhoneSignIn } from './PhoneSignIn';

const tokens = (n: number, expiresIn = 1800) => ({
  tokenType: 'Bearer' as const,
  accessToken: `smo_access${n}`,
  expiresIn,
  refreshToken: `smr_refresh${n}`,
  refreshExpiresIn: 2592000,
});

function Probe() {
  const { session, signInWithTokens } = useSession();
  return (
    <>
      <p data-testid="who">{session ? session.token : 'signed out'}</p>
      <button type="button" onClick={() => signInWithTokens(tokens(1, 130))}>
        start session
      </button>
    </>
  );
}

function renderSignIn() {
  return render(
    <I18nProvider>
      <SessionProvider>
        <main>
          <PhoneSignIn />
          <Probe />
        </main>
      </SessionProvider>
    </I18nProvider>,
  );
}

afterEach(() => {
  vi.useRealTimers();
});

describe('PhoneSignIn', () => {
  it('asks for the number, then the code, then signs the parent in', async () => {
    const calls = stubFetch((url) =>
      url.endsWith('/otp/request') ? { status: 202, body: { resendAfterSeconds: 30 } } : { body: tokens(1) },
    );
    const user = userEvent.setup();
    renderSignIn();

    await user.type(screen.getByLabelText(/Mobile number/), '98450 12345');
    await user.click(screen.getByRole('button', { name: 'Send code' }));

    expect(JSON.parse(String(calls[0]?.init.body))).toEqual({ phone: '98450 12345' });
    expect(calls[0]?.url).toBe('/api/v1/auth/otp/request');
    expect(await screen.findByRole('status')).toHaveTextContent(/If this number is registered/);
    // It never says whether the number is known, and sending again must wait.
    expect(screen.getByRole('button', { name: /Send again in 30 s/ })).toBeDisabled();

    await user.type(screen.getByLabelText(/6-digit code/), '123456');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    await waitFor(() => expect(screen.getByTestId('who')).toHaveTextContent('smo_access1'));
    expect(JSON.parse(String(calls[1]?.init.body))).toEqual({
      phone: '98450 12345',
      code: '123456',
      deviceLabel: 'Web browser',
    });
    // The public endpoints are called without any Authorization header.
    expect((calls[0]?.init.headers as Record<string, string>).Authorization).toBeUndefined();
  });

  it('shows what the server said when the code is wrong, and stays on the code step', async () => {
    stubFetch((url) =>
      url.endsWith('/otp/request')
        ? { status: 202, body: { resendAfterSeconds: 30 } }
        : {
            status: 401,
            body: {
              code: 'invalid_code',
              detail: 'That code is not right or has expired. Check the SMS or ask for a new code.',
            },
          },
    );
    const user = userEvent.setup();
    renderSignIn();
    await user.type(screen.getByLabelText(/Mobile number/), '9845012345');
    await user.click(screen.getByRole('button', { name: 'Send code' }));
    await user.type(await screen.findByLabelText(/6-digit code/), '000000');
    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(/not right or has expired/);
    expect(screen.getByTestId('who')).toHaveTextContent('signed out');
    expect(screen.getByLabelText(/6-digit code/)).toBeInTheDocument();
  });

  it('goes on to the code step when a code was only just sent, counting down from the server wait', async () => {
    stubFetch(() => ({
      status: 429,
      body: { code: 'otp_resend_too_soon', detail: 'A code was sent a moment ago.', retryAfterSeconds: 12 },
    }));
    const user = userEvent.setup();
    renderSignIn();
    await user.type(screen.getByLabelText(/Mobile number/), '9845012345');
    await user.click(screen.getByRole('button', { name: 'Send code' }));
    expect(await screen.findByRole('button', { name: /Send again in 12 s/ })).toBeDisabled();
  });

  it('keeps the parent on the number step with a clear message when too many codes were asked for', async () => {
    stubFetch(() => ({
      status: 429,
      body: {
        code: 'otp_rate_limited',
        detail: 'Too many codes were requested. Please wait and try again later.',
        retryAfterSeconds: 900,
      },
    }));
    const user = userEvent.setup();
    renderSignIn();
    await user.type(screen.getByLabelText(/Mobile number/), '9845012345');
    await user.click(screen.getByRole('button', { name: 'Send code' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(/Too many codes/);
    expect(screen.getByLabelText(/Mobile number/)).toBeInTheDocument();
  });

  it('can go back to change the number', async () => {
    stubFetch(() => ({ status: 202, body: { resendAfterSeconds: 30 } }));
    const user = userEvent.setup();
    renderSignIn();
    await user.type(screen.getByLabelText(/Mobile number/), '9845012345');
    await user.click(screen.getByRole('button', { name: 'Send code' }));
    await user.click(await screen.findByRole('button', { name: 'Change number' }));
    expect(screen.getByLabelText(/Mobile number/)).toHaveValue('9845012345');
  });

  it('has no accessibility violations on either step', async () => {
    stubFetch(() => ({ status: 202, body: { resendAfterSeconds: 30 } }));
    const user = userEvent.setup();
    const { container } = renderSignIn();
    expect(await axeViolations(container)).toEqual([]);
    await user.type(screen.getByLabelText(/Mobile number/), '9845012345');
    await user.click(screen.getByRole('button', { name: 'Send code' }));
    await screen.findByLabelText(/6-digit code/);
    expect(await axeViolations(container)).toEqual([]);
  });
});

describe('a phone-code session', () => {
  it('renews itself before the access token runs out, with the rotated refresh token', async () => {
    vi.useFakeTimers();
    const calls = stubFetch(() => ({ body: tokens(2) }));
    renderSignIn();
    fireEvent.click(screen.getByRole('button', { name: 'start session' }));
    expect(screen.getByTestId('who')).toHaveTextContent('smo_access1');

    // 130 s lifetime, renewed 120 s early (never sooner than 5 s): due after 10 s.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(9_000);
    });
    expect(calls).toHaveLength(0);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2_000);
    });
    expect(calls).toHaveLength(1);
    expect(calls[0]?.url).toBe('/api/v1/auth/token/refresh');
    expect(JSON.parse(String(calls[0]?.init.body))).toEqual({ refreshToken: 'smr_refresh1' });
    expect(screen.getByTestId('who')).toHaveTextContent('smo_access2');
  });

  it('signs out when the server refuses to renew (session ended by the school)', async () => {
    vi.useFakeTimers();
    stubFetch(() => ({
      status: 401,
      body: { code: 'invalid_refresh_token', detail: 'Please sign in again.' },
    }));
    renderSignIn();
    fireEvent.click(screen.getByRole('button', { name: 'start session' }));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(11_000);
    });
    expect(screen.getByTestId('who')).toHaveTextContent('signed out');
  });

  it('stays signed in and tries again when the network is down', async () => {
    vi.useFakeTimers();
    let online = false;
    const calls = stubFetch(() =>
      online ? { body: tokens(2) } : { status: 503, body: { code: 'x', detail: 'down' } },
    );
    renderSignIn();
    fireEvent.click(screen.getByRole('button', { name: 'start session' }));
    await act(async () => {
      await vi.advanceTimersByTimeAsync(11_000);
    });
    expect(screen.getByTestId('who')).toHaveTextContent('smo_access1');
    online = true;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(16_000);
    });
    expect(calls.length).toBeGreaterThanOrEqual(2);
    expect(screen.getByTestId('who')).toHaveTextContent('smo_access2');
  });
});
