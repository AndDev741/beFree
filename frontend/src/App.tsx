import { useCallback, useEffect, useState } from 'react';
import {
  CaretLeft, CaretRight, ChartDonut, ChatCircleDots, CirclesThreePlus,
  Minus, Moon, Receipt, SignOut, Sun, Target, Wallet,
} from '@phosphor-icons/react';
import { api, monthKey, monthLabel, setUnauthorizedHandler, shiftMonth, type Session } from './api';
import { useTheme } from './hooks';
import { Login } from './Login';
import { Dashboard } from './Dashboard';
import { Transactions } from './Transactions';
import { Budgets } from './Budgets';
import { Goals } from './Goals';
import { Assistant } from './Assistant';

export type View = 'dash' | 'tx' | 'budgets' | 'goals';

const TABS: { id: View; label: string; icon: typeof ChartDonut }[] = [
  { id: 'dash', label: 'Resumo', icon: ChartDonut },
  { id: 'tx', label: 'Movimentos', icon: Receipt },
  { id: 'budgets', label: 'Orçamentos', icon: Wallet },
  { id: 'goals', label: 'Objetivos', icon: Target },
];

/**
 * Open on a wide screen, closed on a phone where it would cover everything.
 * Whatever you last chose wins over both.
 */
function openByDefault() {
  try {
    const saved = localStorage.getItem('befree-rail');
    if (saved === 'open') return true;
    if (saved === 'closed') return false;
  } catch {
    // no storage: fall back to the screen size
  }
  return typeof window !== 'undefined' && window.innerWidth > 860;
}

export function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [booting, setBooting] = useState(true);
  const [view, setView] = useState<View>('dash');
  const [month, setMonth] = useState(monthKey());
  const [railOpen, setRailOpen] = useState(openByDefault);
  // Bumped whenever anything writes, including the assistant, so every open
  // view refetches. The assistant edits the same ledger the screens draw.
  const [revision, setRevision] = useState(0);
  const changed = useCallback(() => setRevision((r) => r + 1), []);
  const { isDark, toggle } = useTheme();

  const check = useCallback(() => {
    api
      .session()
      .then(setSession)
      .catch(() => setSession(null))
      .finally(() => setBooting(false));
  }, []);

  useEffect(check, [check]);
  useEffect(() => setUnauthorizedHandler(() => setSession(null)), []);
  useEffect(() => {
    try {
      localStorage.setItem('befree-rail', railOpen ? 'open' : 'closed');
    } catch {
      // the choice just does not survive a reload
    }
  }, [railOpen]);

  if (booting) return <div className="loginwrap" />;
  if (!session) return <Login onSignedIn={check} />;

  const thisMonth = monthKey();
  const props = { month, revision, onChanged: changed };

  return (
    <div className="app">
      <header className="appbar">
        <span className="logo">
          <CirclesThreePlus size={20} weight="fill" />
          beFree
        </span>

        <nav className="nav">
          {TABS.map((t) => (
            <button key={t.id} onClick={() => setView(t.id)} aria-current={view === t.id ? 'page' : undefined}>
              {t.label}
            </button>
          ))}
        </nav>

        <span className="grow" />

        {view !== 'goals' && (
          <div className="monthnav">
            <button className="iconbtn" onClick={() => setMonth(shiftMonth(month, -1))} aria-label="Mês anterior">
              <CaretLeft size={16} weight="bold" />
            </button>
            <button className="mlabel" onClick={() => setMonth(thisMonth)} title="Voltar ao mês atual">
              {monthLabel(month)}
            </button>
            <button
              className="iconbtn"
              onClick={() => setMonth(shiftMonth(month, 1))}
              disabled={month >= thisMonth}
              aria-label="Mês seguinte"
            >
              <CaretRight size={16} weight="bold" />
            </button>
          </div>
        )}

        <button className="iconbtn" onClick={toggle} aria-label="Alternar tema">
          {isDark ? <Sun size={17} /> : <Moon size={17} />}
        </button>
        <button
          className="iconbtn"
          onClick={() => api.logout().then(() => setSession(null))}
          aria-label="Sair"
          title={session.username}
        >
          <SignOut size={17} />
        </button>
      </header>

      <div className="main">
        <div className="content">
          {view === 'dash' && <Dashboard {...props} onOpen={setView} />}
          {view === 'tx' && <Transactions {...props} />}
          {view === 'budgets' && <Budgets {...props} />}
          {view === 'goals' && <Goals revision={revision} onChanged={changed} />}
        </div>

        {railOpen && (
          <aside className="rail open">
            <div className="railhead">
              <ChatCircleDots size={16} weight="fill" />
              Assistente
              <span className="grow" />
              <button className="iconbtn" onClick={() => setRailOpen(false)} aria-label="Fechar o assistente">
                <Minus size={16} weight="bold" />
              </button>
            </div>
            <Assistant enabled={session.assistantEnabled} onChanged={changed} />
          </aside>
        )}
      </div>

      {!railOpen && (
        <button className="bubble" onClick={() => setRailOpen(true)} aria-label="Abrir o assistente">
          <ChatCircleDots size={24} weight="fill" />
        </button>
      )}

      <nav className="tabbar">
        {TABS.map((t) => (
          <button key={t.id} onClick={() => setView(t.id)} aria-current={view === t.id ? 'page' : undefined}>
            <t.icon size={19} weight={view === t.id ? 'fill' : 'regular'} />
            {t.label}
          </button>
        ))}
      </nav>
    </div>
  );
}
