import { BrowserRouter, Route, Routes } from 'react-router-dom';
import type { Campus, Me } from './api/types';
import { useSession } from './app/session';
import { useApiResource } from './app/useApiResource';
import { AppShell } from './components/AppShell';
import { PageHeader } from './components/PageHeader';
import { ErrorState, LoadingState } from './components/States';
import { AcademicPage } from './pages/AcademicPage';
import { CampusesPage } from './pages/CampusesPage';
import { ImportsPage } from './pages/ImportsPage';
import { DesignSystemPage } from './pages/DesignSystemPage';
import { SettingsPage } from './pages/SettingsPage';
import { SignInPage } from './pages/SignInPage';
import { TodayPage } from './pages/TodayPage';

export function App() {
  const { session } = useSession();
  return <BrowserRouter>{session ? <SignedIn /> : <SignInPage />}</BrowserRouter>;
}

function SignedIn() {
  const { signOut, selectedCampusId, setSelectedCampusId } = useSession();
  const [me, reloadMe] = useApiResource<Me>('/api/v1/me');
  const [campuses] = useApiResource<Campus[]>('/api/v1/campuses');

  if (me.status === 'loading') return <LoadingState label="Opening your school" />;
  if (me.status === 'error') {
    return (
      <main id="main" className="signin">
        <ErrorState error={me.error} onRetry={reloadMe} />
        <button type="button" className="btn btn--quiet" onClick={signOut}>
          Back to sign in
        </button>
      </main>
    );
  }

  return (
    <AppShell
      onSignOut={signOut}
      scope={{
        groupName: me.data.tenant.name,
        campuses: campuses.status === 'ready' ? campuses.data.map((c) => ({ id: c.id, name: c.name })) : [],
        selectedCampusId,
        onSelectCampus: setSelectedCampusId,
        personName: me.data.displayName,
      }}
    >
      <Routes>
        <Route path="/" element={<TodayPage me={me.data} />} />
        <Route path="/campuses" element={<CampusesPage />} />
        <Route
          path="/academic"
          element={
            <AcademicPage
              me={me.data}
              campuses={campuses.status === 'ready' ? campuses.data : []}
              selectedCampusId={selectedCampusId}
            />
          }
        />
        <Route
          path="/imports"
          element={
            <ImportsPage
              me={me.data}
              campuses={campuses.status === 'ready' ? campuses.data : []}
              selectedCampusId={selectedCampusId}
            />
          }
        />
        <Route path="/settings" element={<SettingsPage me={me.data} />} />
        <Route path="/design-system" element={<DesignSystemPage />} />
        <Route
          path="*"
          element={<PageHeader title="Page not found" lead="Use the menu to find what you need." />}
        />
      </Routes>
    </AppShell>
  );
}
