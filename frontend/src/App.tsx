import { lazy, Suspense } from 'react';
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
import { StudentProfilePage, StudentsPage } from './pages/StudentsPage';
import { SignInPage } from './pages/SignInPage';
import { TodayPage } from './pages/TodayPage';

// Loaded on first visit so the first screen stays light.
const FeesPage = lazy(() => import('./pages/FeesPage').then((m) => ({ default: m.FeesPage })));
const SupportPage = lazy(() => import('./pages/SupportPage').then((m) => ({ default: m.SupportPage })));
const NoticesPage = lazy(() => import('./pages/NoticesPage').then((m) => ({ default: m.NoticesPage })));
const AssessmentPage = lazy(() =>
  import('./pages/AssessmentPage').then((m) => ({ default: m.AssessmentPage })),
);
const AdmissionsPage = lazy(() =>
  import('./pages/AdmissionsPage').then((m) => ({ default: m.AdmissionsPage })),
);
const LearningPage = lazy(() => import('./pages/LearningPage').then((m) => ({ default: m.LearningPage })));
const SafeguardingPage = lazy(() =>
  import('./pages/SafeguardingPage').then((m) => ({ default: m.SafeguardingPage })),
);
const StudentSupportPage = lazy(() =>
  import('./pages/StudentSupportPage').then((m) => ({ default: m.StudentSupportPage })),
);
const AttendancePage = lazy(() =>
  import('./pages/AttendancePage').then((m) => ({ default: m.AttendancePage })),
);
const TimetablePage = lazy(() => import('./pages/TimetablePage').then((m) => ({ default: m.TimetablePage })));

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
        <Route
          path="/students"
          element={
            <StudentsPage
              campuses={campuses.status === 'ready' ? campuses.data : []}
              selectedCampusId={selectedCampusId}
            />
          }
        />
        <Route path="/students/:studentId" element={<StudentProfilePage me={me.data} />} />
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
        <Route
          path="/notices"
          element={
            <Suspense fallback={<LoadingState label="Opening notices" />}>
              <NoticesPage
                me={me.data}
                campuses={campuses.status === 'ready' ? campuses.data : []}
                selectedCampusId={selectedCampusId}
              />
            </Suspense>
          }
        />
        <Route
          path="/fees"
          element={
            <Suspense fallback={<LoadingState label="Opening fees" />}>
              <FeesPage
                me={me.data}
                campuses={campuses.status === 'ready' ? campuses.data : []}
                selectedCampusId={selectedCampusId}
              />
            </Suspense>
          }
        />
        <Route
          path="/support"
          element={
            <Suspense fallback={<LoadingState label="Opening support" />}>
              <SupportPage me={me.data} />
            </Suspense>
          }
        />
        <Route
          path="/attendance"
          element={
            <Suspense fallback={<LoadingState label="Opening attendance" />}>
              <AttendancePage
                me={me.data}
                campuses={campuses.status === 'ready' ? campuses.data : []}
                selectedCampusId={selectedCampusId}
              />
            </Suspense>
          }
        />
        <Route
          path="/admissions"
          element={
            <Suspense fallback={<LoadingState label="Opening admissions" />}>
              <AdmissionsPage
                me={me.data}
                campuses={campuses.status === 'ready' ? campuses.data : []}
                selectedCampusId={selectedCampusId}
              />
            </Suspense>
          }
        />
        <Route
          path="/learning"
          element={
            <Suspense fallback={<LoadingState label="Opening syllabus and homework" />}>
              <LearningPage
                me={me.data}
                campuses={campuses.status === 'ready' ? campuses.data : []}
                selectedCampusId={selectedCampusId}
              />
            </Suspense>
          }
        />
        <Route
          path="/safeguarding"
          element={
            <Suspense fallback={<LoadingState label="Opening safeguarding" />}>
              <SafeguardingPage
                me={me.data}
                campuses={campuses.status === 'ready' ? campuses.data : []}
                selectedCampusId={selectedCampusId}
              />
            </Suspense>
          }
        />
        <Route
          path="/student-support"
          element={
            <Suspense fallback={<LoadingState label="Opening student support" />}>
              <StudentSupportPage
                me={me.data}
                campuses={campuses.status === 'ready' ? campuses.data : []}
                selectedCampusId={selectedCampusId}
              />
            </Suspense>
          }
        />
        <Route
          path="/assessment"
          element={
            <Suspense fallback={<LoadingState label="Opening marks and reports" />}>
              <AssessmentPage
                me={me.data}
                campuses={campuses.status === 'ready' ? campuses.data : []}
                selectedCampusId={selectedCampusId}
              />
            </Suspense>
          }
        />
        <Route
          path="/timetable"
          element={
            <Suspense fallback={<LoadingState label="Opening the timetable" />}>
              <TimetablePage
                me={me.data}
                campuses={campuses.status === 'ready' ? campuses.data : []}
                selectedCampusId={selectedCampusId}
              />
            </Suspense>
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
