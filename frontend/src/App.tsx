import { useCallback, useEffect, useState } from 'react';
import {
  CaretLeft, CaretRight, ChartDonut, ChatCircleDots, CirclesThreePlus, Gear,
  Minus, Moon, Receipt, SignOut, Sun, Target, Wallet, X,
} from '@phosphor-icons/react';
import {
  api, currentMonth, monthLabel, setUnauthorizedHandler, shiftMonth, spanLabel,
  type Session, type Settings,
} from './api';
import { useTheme } from './hooks';
import { Login } from './Login';
import { Dashboard } from './Dashboard';
import { Transactions } from './Transactions';
import { Budgets } from './Budgets';
import { Goals } from './Goals';
import { Assistant } from './Assistant';

export type View = 'dash' | 'tx' | 'budgets' | 'goals';

/** Past the 28th a month would skip February, so the choice stops there. */
const DAYS = Array.from({ length: 28 }, (_, i) => i + 1);

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
  const [month, setMonth] = useState<string | null>(null);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [cycle, setCycle] = useState<Settings | null>(null);
  const [railOpen, setRailOpen] = useState(openByDefault);
  // Bumped whenever anything writes, including the assistant, so every open
  // view refetches. The assistant edits the same ledger the screens draw.
  const [revision, setRevision] = useState(0);
  const changed = useCallback(() => setRevision((r) => r + 1), []);
  const { isDark, toggle } = useTheme();

  const check = useCallback(() => {
    api
      .session()
      .then((s) => {
        setSession(s);
        // The month you land on depends on the cycle, so it waits for the session
        setMonth((m) => m ?? currentMonth(s.monthStartDay));
      })
      .catch(() => setSession(null))
      .finally(() => setBooting(false));
  }, []);

  useEffect(check, [check]);
  useEffect(() => setUnauthorizedHandler(() => setSession(null)), []);

  useEffect(() => {
    if (!session || !month) return;
    let live = true;
    api.settings(month).then((s) => live && setCycle(s)).catch(() => undefined);
    return () => {
      live = false;
    };
  }, [session, month, revision]);
  useEffect(() => {
    try {
      localStorage.setItem('befree-rail', railOpen ? 'open' : 'closed');
    } catch {
      // the choice just does not survive a reload
    }
  }, [railOpen]);

  if (booting) return <div className="loginwrap" />;
  if (!session) return <Login onSignedIn={check} />;
  if (!month) return <div className="loginwrap" />;

  const startDay = cycle?.startDay ?? session.monthStartDay;
  const thisMonth = currentMonth(session.monthStartDay);
  const props = { month, revision, onChanged: changed };
  const txProps = { ...props, startDay };

  async function applyCycle(next: Promise<Settings>) {
    const updated = await next;
    setCycle(updated);
    setSession((s) => (s ? { ...s, monthStartDay: updated.defaultStartDay } : s));
    changed();
  }

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
              {cycle && cycle.startDay > 1 && <small>{spanLabel(cycle.from, cycle.to)}</small>}
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

        <div className="pop">
          <button
            className="iconbtn"
            onClick={() => setSettingsOpen((o) => !o)}
            aria-label="Definições"
            aria-expanded={settingsOpen}
          >
            <Gear size={17} />
          </button>
          {settingsOpen && (
            <div className="popcard">
              <div className="chead" style={{ marginBottom: 12 }}>
                <span className="ctitle">O meu mês</span>
                <span className="grow" />
                <button className="iconbtn" onClick={() => setSettingsOpen(false)} aria-label="Fechar">
                  <X size={14} />
                </button>
              </div>

              <div className="field">
                <label htmlFor="msd">Começa normalmente no dia</label>
                <select
                  id="msd"
                  className="input"
                  value={cycle?.defaultStartDay ?? 1}
                  onChange={(e) => void applyCycle(api.setMonthStartDay(Number(e.target.value)))}
                >
                  {DAYS.map((d) => (
                    <option key={d} value={d}>{d === 1 ? '1 (mês de calendário)' : d}</option>
                  ))}
                </select>
              </div>

              <div className="field" style={{ marginTop: 12 }}>
                <label htmlFor="msx">Exceção para {monthLabel(month)}</label>
                <select
                  id="msx"
                  className="input"
                  value={cycle?.custom ? cycle.startDay : ''}
                  onChange={(e) =>
                    void applyCycle(
                      e.target.value
                        ? api.setMonthStartDay(Number(e.target.value), month)
                        : api.clearMonthStartDay(month),
                    )
                  }
                >
                  <option value="">Sem exceção</option>
                  {DAYS.map((d) => (
                    <option key={d} value={d}>dia {d}</option>
                  ))}
                </select>
              </div>

              <p className="help" style={{ marginTop: 10 }}>
                {cycle && cycle.startDay > 1
                  ? `${monthLabel(month)} corre de ${spanLabel(cycle.from, cycle.to)}. Um mês acaba onde o seguinte começa, por isso mudar um dia move só essa fronteira.`
                  : 'Cada mês vai do dia 1 ao último dia.'}
              </p>
            </div>
          )}
        </div>

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
          {view === 'tx' && <Transactions {...txProps} />}
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
