import { useEffect } from 'react';
import { useSession } from '../app/session';
import { Button } from '../components/Button';

const devUsers = [
  { subject: 'dev-asha', name: 'Asha', role: 'Group administrator for the whole school group' },
  { subject: 'dev-ravi', name: 'Ravi', role: 'Teacher on North Campus' },
  { subject: 'dev-farah', name: 'Farah', role: 'Safeguarding lead on North Campus' },
  { subject: 'dev-meera', name: 'Meera', role: 'Guardian of two children on different campuses' },
  { subject: 'dev-karan', name: 'Karan', role: 'Separated guardian with notice-only access' },
  { subject: 'dev-other', name: 'Other', role: 'Staff member of a different school group' },
];

export function SignInPage() {
  const { signIn } = useSession();
  const devAuth = import.meta.env.VITE_DEV_AUTH === 'true';
  useEffect(() => {
    document.title = 'Sign in · Schoomano';
  }, []);

  return (
    <main className="signin" id="main">
      <div className="signin__panel">
        <h1 className="signin__title">Sign in to Schoomano</h1>
        {devAuth ? (
          <>
            <p className="signin__lead">
              Local development only. Every account below is synthetic seed data; no real people or students.
            </p>
            <ul className="signin__list">
              {devUsers.map((u) => (
                <li key={u.subject} className="signin__item">
                  <div>
                    <p className="signin__name">{u.name}</p>
                    <p className="signin__role">{u.role}</p>
                  </div>
                  <Button variant="primary" onClick={() => signIn(`dev:${u.subject}`)}>
                    Sign in as {u.name}
                  </Button>
                </li>
              ))}
            </ul>
          </>
        ) : (
          <p className="signin__lead">
            School sign-in (OIDC) is not configured for this environment yet. Set VITE_DEV_AUTH=true for local
            development with synthetic accounts.
          </p>
        )}
      </div>
    </main>
  );
}
