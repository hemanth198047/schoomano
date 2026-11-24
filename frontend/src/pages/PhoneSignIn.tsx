import { useEffect, useState, type FormEvent } from 'react';
import { ApiError } from '../api/client';
import { requestOtp, verifyOtp } from '../api/auth';
import { useSession } from '../app/session';
import { Button } from '../components/Button';
import { Field } from '../components/Field';

// Same-origin by default (proxied). Set VITE_API_BASE_URL only to call an API on another origin.
const baseUrl = import.meta.env.VITE_API_BASE_URL ?? '';

/**
 * Parent sign-in with a code sent by SMS (ADR 0009). The server answers the same way whether or not the
 * number is on a guardian record, so this screen never says which it is: it only says what to do next.
 */
export function PhoneSignIn() {
  const { signInWithTokens } = useSession();
  const [step, setStep] = useState<'phone' | 'code'>('phone');
  const [phone, setPhone] = useState('');
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [wait, setWait] = useState(0);

  // Count down until "send again" is allowed.
  useEffect(() => {
    if (wait <= 0) return;
    const t = setTimeout(() => setWait((w) => w - 1), 1000);
    return () => clearTimeout(t);
  }, [wait]);

  async function sendCode(e?: FormEvent) {
    e?.preventDefault();
    setError(null);
    setBusy(true);
    try {
      const r = await requestOtp(baseUrl, phone);
      setWait(r.resendAfterSeconds);
      setCode('');
      setStep('code');
    } catch (err) {
      if (err instanceof ApiError && err.code === 'otp_resend_too_soon') {
        // A code was sent a moment ago: go on to entering it.
        setWait(err.retryAfterSeconds ?? 30);
        setStep('code');
      } else {
        setError(err instanceof ApiError ? err.detail : 'Something went wrong. Please try again.');
      }
    } finally {
      setBusy(false);
    }
  }

  async function submitCode(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      signInWithTokens(await verifyOtp(baseUrl, phone, code, 'Web browser'));
    } catch (err) {
      setError(err instanceof ApiError ? err.detail : 'Something went wrong. Please try again.');
      setBusy(false);
      if (err instanceof ApiError && err.code === 'otp_locked') setCode('');
    }
  }

  if (step === 'phone') {
    return (
      <form className="form" onSubmit={sendCode} noValidate aria-labelledby="phone-signin-title">
        <h2 id="phone-signin-title" className="signin__subtitle">
          Parents: sign in with your phone
        </h2>
        <Field
          label="Mobile number"
          hint="The number the school has for you, for example 98450 12345. We will text you a code."
          required
        >
          {(p) => (
            <input
              {...p}
              type="tel"
              inputMode="tel"
              autoComplete="tel-national"
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
            />
          )}
        </Field>
        {error && (
          <p role="alert" className="field__error">
            {error}
          </p>
        )}
        <div className="form__actions">
          <Button type="submit" variant="primary" busy={busy} disabled={phone.trim() === ''}>
            Send code
          </Button>
        </div>
      </form>
    );
  }

  return (
    <form className="form" onSubmit={submitCode} noValidate aria-labelledby="phone-signin-title">
      <h2 id="phone-signin-title" className="signin__subtitle">
        Enter your code
      </h2>
      <p role="status">
        If this number is registered with the school, a 6-digit code is on its way by SMS. It works for 5
        minutes.
      </p>
      <Field label="6-digit code" required>
        {(p) => (
          <input
            {...p}
            type="text"
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={7}
            value={code}
            onChange={(e) => setCode(e.target.value)}
          />
        )}
      </Field>
      {error && (
        <p role="alert" className="field__error">
          {error}
        </p>
      )}
      <div className="form__actions">
        <Button type="submit" variant="primary" busy={busy} disabled={code.trim() === ''}>
          Sign in
        </Button>
        <Button onClick={() => void sendCode()} disabled={busy || wait > 0}>
          {wait > 0 ? `Send again in ${wait} s` : 'Send again'}
        </Button>
        <Button
          variant="quiet"
          onClick={() => {
            setStep('phone');
            setError(null);
          }}
        >
          Change number
        </Button>
      </div>
    </form>
  );
}
