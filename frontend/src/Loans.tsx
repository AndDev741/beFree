import { useState, type FormEvent } from 'react';
import {
  ArrowUUpLeft, CaretDown, CaretRight, CheckCircle, HandCoins, Link, PencilSimple, Plus, Trash,
} from '@phosphor-icons/react';
import { api, money, type LoanView, type Transaction } from './api';
import { useLoad } from './hooks';
import { Card, Empty, ErrorBanner, Figure, Skeleton } from './ui';

interface Props {
  revision: number;
  onChanged: () => void;
}

/** The currencies on offer. A loan in anything else keeps its own in the list. */
const CURRENCIES = ['EUR', 'BRL'];

const todayIso = () => {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
};

const dayLabel = (iso: string) =>
  new Date(iso + 'T00:00:00').toLocaleDateString('pt-PT', { day: 'numeric', month: 'short', year: 'numeric' });

/** How long the money has been out, in the unit a person would say it in. */
function ago(iso: string) {
  const then = new Date(iso + 'T00:00:00');
  const now = new Date();
  const days = Math.floor((now.getTime() - then.getTime()) / 86_400_000);
  if (days < 1) return 'hoje';
  if (days < 31) return days === 1 ? 'há 1 dia' : `há ${days} dias`;
  const months = (now.getFullYear() - then.getFullYear()) * 12 + now.getMonth() - then.getMonth()
    - (now.getDate() < then.getDate() ? 1 : 0);
  if (months < 12) return months <= 1 ? 'há 1 mês' : `há ${months} meses`;
  const years = Math.floor(months / 12);
  return years === 1 ? 'há 1 ano' : `há ${years} anos`;
}

/** Accepts "1.100,50", "1100.50" and "1100,5". Anything else is NaN. */
function parseAmount(raw: string) {
  const clean = raw.trim().replace(/\s/g, '');
  if (!clean) return NaN;
  return Number(clean.includes(',') ? clean.replace(/\./g, '').replace(',', '.') : clean);
}

const currencyName = (c: string) => (c === 'EUR' ? 'euros' : c === 'BRL' ? 'reais' : c);

const failed = (e: unknown) => (e instanceof Error ? e.message : 'Não consegui guardar.');

function CurrencySelect({ value, onChange, mini, id, label }: {
  value: string; onChange: (v: string) => void; mini?: boolean; id?: string; label?: string;
}) {
  const options = CURRENCIES.includes(value) ? CURRENCIES : [...CURRENCIES, value];
  return (
    <select id={id} className={mini ? 'input mini' : 'input'} value={value} aria-label={label}
            onChange={(e) => onChange(e.target.value)}>
      {options.map((c) => <option key={c} value={c}>{c === 'BRL' ? 'R$' : c === 'EUR' ? '€' : c}</option>)}
    </select>
  );
}

interface Draft {
  person: string;
  amount: string;
  currency: string;
  lentOn: string;
  description: string;
  /** The expense it came from, when started from an untracked movement. */
  transaction: Transaction | null;
}

const blank = (): Draft => ({
  person: '', amount: '', currency: 'EUR', lentOn: todayIso(), description: '', transaction: null,
});

function NewLoan({ draft, setDraft, people, onDone }: {
  draft: Draft; setDraft: (d: Draft | null) => void; people: string[]; onDone: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function create(e: FormEvent) {
    e.preventDefault();
    const amount = parseAmount(draft.amount);
    if (!draft.person.trim() || !Number.isFinite(amount) || amount <= 0) {
      setError('Precisa de uma pessoa e de um valor maior que zero.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await api.createLoan({
        person: draft.person.trim(),
        amount,
        currency: draft.currency,
        lentOn: draft.lentOn || undefined,
        description: draft.description.trim() || null,
        transactionId: draft.transaction?.id ?? null,
      });
      setDraft(null);
      onDone();
    } catch (err) {
      setError(failed(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="formrow" onSubmit={create}>
      <div className="field" style={{ flex: 1, minWidth: 150 }}>
        <label htmlFor="lp">Pessoa</label>
        <input id="lp" className="input" autoFocus list="loan-people" placeholder="Pai"
               value={draft.person} onChange={(e) => setDraft({ ...draft, person: e.target.value })} />
        <datalist id="loan-people">
          {people.map((p) => <option key={p} value={p} />)}
        </datalist>
      </div>
      <div className="field" style={{ width: 120 }}>
        <label htmlFor="la">Valor</label>
        <input id="la" className="input num" inputMode="decimal" placeholder="50"
               value={draft.amount} onChange={(e) => setDraft({ ...draft, amount: e.target.value })} />
      </div>
      <div className="field" style={{ width: 78 }}>
        <label htmlFor="lc">Moeda</label>
        <CurrencySelect id="lc" value={draft.currency} onChange={(currency) => setDraft({ ...draft, currency })} />
      </div>
      <div className="field" style={{ width: 150 }}>
        <label htmlFor="ld">Emprestado a</label>
        <input id="ld" className="input" type="date"
               value={draft.lentOn} onChange={(e) => setDraft({ ...draft, lentOn: e.target.value })} />
      </div>
      <div className="field" style={{ flex: 1, minWidth: 160 }}>
        <label htmlFor="lx">Para quê</label>
        <input id="lx" className="input" placeholder="ajudar com a renda"
               value={draft.description} onChange={(e) => setDraft({ ...draft, description: e.target.value })} />
      </div>
      <button className="btn" type="submit" disabled={busy}>Criar</button>
      <button className="btn ghost" type="button" onClick={() => setDraft(null)}>Cancelar</button>
      {draft.transaction && (
        <p className="help" style={{ width: '100%', margin: 0 }}>
          Fica ligado ao movimento de {dayLabel(draft.transaction.occurredOn)}
          {draft.transaction.description ? ` "${draft.transaction.description}"` : ''}.
        </p>
      )}
      {error && <p className="err" style={{ width: '100%', margin: 0 }}>{error}</p>}
    </form>
  );
}

interface Edit {
  person: string;
  amount: string;
  currency: string;
  lentOn: string;
  description: string;
}

/** One loan: how much came back, what is left, and a box to record the next payment. */
function LoanCard({ loan, onChanged }: { loan: LoanView; onChanged: () => void }) {
  const [amount, setAmount] = useState('');
  const [currency, setCurrency] = useState(loan.currency);
  const [on, setOn] = useState(todayIso());
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [edit, setEdit] = useState<Edit | null>(null);
  const [closing, setClosing] = useState<string | null>(null);
  const [history, setHistory] = useState(false);
  const [paying, setPaying] = useState(false);

  const others = Object.entries(loan.otherCurrencies);

  async function run(write: () => Promise<unknown>) {
    setBusy(true);
    setError(null);
    try {
      await write();
      onChanged();
      return true;
    } catch (err) {
      setError(failed(err));
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function repay(e: FormEvent) {
    e.preventDefault();
    const value = parseAmount(amount);
    if (!Number.isFinite(value) || value <= 0) return;
    const ok = await run(() => api.repayLoan(loan.id, {
      amount: value, currency, occurredOn: on || undefined, note: note.trim() || null,
    }));
    if (ok) {
      setAmount('');
      setNote('');
      setPaying(false);
    }
  }

  async function saveEdit(e: FormEvent) {
    e.preventDefault();
    if (!edit) return;
    const value = parseAmount(edit.amount);
    if (!edit.person.trim() || !Number.isFinite(value) || value <= 0) return;
    const ok = await run(() => api.updateLoan(loan.id, {
      person: edit.person.trim(),
      amount: value,
      currency: edit.currency,
      lentOn: edit.lentOn,
      description: edit.description.trim(),
    }));
    if (ok) setEdit(null);
  }

  async function close(e: FormEvent) {
    e.preventDefault();
    const ok = await run(() => api.closeLoan(loan.id, closing?.trim() || null));
    if (ok) setClosing(null);
  }

  async function remove() {
    if (!confirm(`Apagar o empréstimo a ${loan.person} e as devoluções registadas?`)) return;
    await run(() => api.deleteLoan(loan.id));
  }

  return (
    <section className="card">
      <div className="chead" style={{ marginBottom: 4 }}>
        <span className="ctitle">{loan.person}</span>
        {loan.description && <span className="help">{loan.description}</span>}
        <span className="grow" />
        <button
          className="iconbtn"
          onClick={() => setClosing(closing === null ? '' : null)}
          aria-label={`Fechar o empréstimo a ${loan.person}`}
          title="Fechar à mão: pago noutra moeda, perdoado, acertado de outra forma"
        >
          <CheckCircle size={15} />
        </button>
        <button
          className="iconbtn"
          onClick={() => setEdit({
            person: loan.person,
            amount: String(loan.amount),
            currency: loan.currency,
            lentOn: loan.lentOn,
            description: loan.description ?? '',
          })}
          aria-label={`Editar o empréstimo a ${loan.person}`}
        >
          <PencilSimple size={15} />
        </button>
        <button className="iconbtn" onClick={() => void remove()} aria-label={`Apagar o empréstimo a ${loan.person}`}>
          <Trash size={15} />
        </button>
      </div>

      <div className="mfoot" style={{ marginTop: 0, marginBottom: 10 }}>
        Emprestado a {dayLabel(loan.lentOn)}, {ago(loan.lentOn)}
        {loan.transactionId && (
          <span title={loan.transactionDescription ?? undefined}>
            {' · '}<Link size={12} style={{ verticalAlign: -1 }} /> saiu no movimento #{loan.transactionId}
          </span>
        )}
      </div>

      {edit && (
        <form className="formrow" style={{ marginBottom: 12 }} onSubmit={saveEdit}>
          <div className="field" style={{ flex: 1, minWidth: 140 }}>
            <label>Pessoa</label>
            <input className="input mini" autoFocus value={edit.person}
                   onChange={(e) => setEdit({ ...edit, person: e.target.value })} />
          </div>
          <div className="field" style={{ width: 104 }}>
            <label>Valor</label>
            <input className="input mini num" inputMode="decimal" value={edit.amount}
                   onChange={(e) => setEdit({ ...edit, amount: e.target.value })} />
          </div>
          <div className="field" style={{ width: 70 }}>
            <label>Moeda</label>
            <CurrencySelect mini value={edit.currency} onChange={(c) => setEdit({ ...edit, currency: c })} />
          </div>
          <div className="field" style={{ width: 140 }}>
            <label>Emprestado a</label>
            <input className="input mini" type="date" value={edit.lentOn}
                   onChange={(e) => setEdit({ ...edit, lentOn: e.target.value })} />
          </div>
          <div className="field" style={{ flex: 1, minWidth: 140 }}>
            <label>Para quê</label>
            <input className="input mini" value={edit.description}
                   onChange={(e) => setEdit({ ...edit, description: e.target.value })} />
          </div>
          <button className="btn" type="submit" disabled={busy}>Guardar</button>
          <button className="btn ghost" type="button" onClick={() => setEdit(null)}>Cancelar</button>
        </form>
      )}

      {closing !== null && (
        <form className="formrow" style={{ marginBottom: 12 }} onSubmit={close}>
          <div className="field" style={{ flex: 1, minWidth: 200 }}>
            <label htmlFor={`cl${loan.id}`}>Como ficou acertado</label>
            <input id={`cl${loan.id}`} className="input mini" autoFocus
                   placeholder="os R$ recebidos cobrem o valor em euros"
                   value={closing} onChange={(e) => setClosing(e.target.value)} />
          </div>
          <button className="btn" type="submit" disabled={busy}>Fechar empréstimo</button>
          <button className="btn ghost" type="button" onClick={() => setClosing(null)}>Cancelar</button>
        </form>
      )}

      <div className="mrow">
        <span className="mnum num" style={{ marginLeft: 0 }}>
          <b>{money(loan.repaid, loan.currency)}</b> de {money(loan.amount, loan.currency)} devolvidos
        </span>
      </div>
      <div className="bar">
        <i className="save" style={{ width: `${Math.min(100, loan.percent ?? 0)}%` }} />
      </div>
      <div className="mfoot">
        Falta <b className="num" style={{ color: 'var(--ink)' }}>{money(loan.outstanding, loan.currency)}</b>
        {others.length > 0 && (
          <> · já chegaram {others.map(([c, v]) => money(v, c)).join(' e ')}, que não converto. Quando a conta bater, fecha-o à mão.</>
        )}
      </div>

      {paying && (
        <form className="formrow" style={{ marginTop: 12 }} onSubmit={repay}>
          <div className="field" style={{ width: 110 }}>
            <input className="input num" inputMode="decimal" placeholder="Devolveu" autoFocus
                   aria-label={`Valor devolvido por ${loan.person}`}
                   value={amount} onChange={(e) => setAmount(e.target.value)} />
          </div>
          <div className="field" style={{ width: 70 }}>
            <CurrencySelect value={currency} onChange={setCurrency} label="Moeda da devolução" />
          </div>
          <div className="field" style={{ width: 150 }}>
            <input className="input" type="date" aria-label="Data da devolução"
                   value={on} onChange={(e) => setOn(e.target.value)} />
          </div>
          <div className="field" style={{ flex: 1, minWidth: 120 }}>
            <input className="input" placeholder="nota (opcional)" aria-label="Nota da devolução"
                   value={note} onChange={(e) => setNote(e.target.value)} />
          </div>
          <button className="btn" type="submit" disabled={busy || !amount}>Registar</button>
          <button className="btn ghost" type="button" onClick={() => setPaying(false)}>Cancelar</button>
        </form>
      )}

      {error && <p className="err" style={{ marginTop: 9 }}>{error}</p>}

      {(!paying || loan.repayments.length > 0) && (
        <div className="formrow" style={{ marginTop: 12, alignItems: 'center' }}>
          {!paying && (
            <button className="btn ghost" type="button" onClick={() => setPaying(true)}>
              <HandCoins size={15} />
              Registar devolução
            </button>
          )}
          {loan.repayments.length > 0 && (
            <button
              type="button"
              className="chead fold"
              style={{ width: 'auto', marginBottom: 0 }}
              onClick={() => setHistory(!history)}
              aria-expanded={history}
            >
              {history ? <CaretDown size={14} /> : <CaretRight size={14} />}
              <span className="help">
                {loan.repayments.length === 1 ? '1 devolução' : `${loan.repayments.length} devoluções`}
              </span>
            </button>
          )}
        </div>
      )}

      {loan.repayments.length > 0 && (
        <>
          {history && (
            <table style={{ marginTop: 8 }}>
              <tbody>
                {loan.repayments.map((r) => (
                  <tr key={r.id}>
                    <td style={{ width: 120 }}>{dayLabel(r.occurredOn)}</td>
                    <td className="help">{r.note}</td>
                    <td className="r num">{money(r.amount, r.currency)}</td>
                    <td style={{ width: 36 }}>
                      <div className="rowbtns">
                        <button
                          className="iconbtn"
                          disabled={busy}
                          onClick={() => {
                            if (confirm(`Apagar a devolução de ${money(r.amount, r.currency)}?`)) {
                              void run(() => api.deleteRepayment(loan.id, r.id));
                            }
                          }}
                          aria-label="Apagar devolução"
                        >
                          <Trash size={14} />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </>
      )}
    </section>
  );
}

/** One loan that is over, with how it ended. */
function FinishedRow({ loan, onChanged }: { loan: LoanView; onChanged: () => void }) {
  const [busy, setBusy] = useState(false);

  async function act(write: () => Promise<unknown>) {
    setBusy(true);
    try {
      await write();
      onChanged();
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="arow">
      <div className="mrow" style={{ marginBottom: 2 }}>
        <span className="mname">{loan.person}</span>
        {loan.description && <span className="help">{loan.description}</span>}
        <span className="grow" />
        {loan.status === 'CLOSED' && (
          <button
            className="iconbtn"
            onClick={() => void act(() => api.reopenLoan(loan.id))}
            disabled={busy}
            aria-label={`Reabrir o empréstimo a ${loan.person}`}
            title="Reabrir: volta a contar como em dívida"
          >
            <ArrowUUpLeft size={15} />
          </button>
        )}
        <button
          className="iconbtn"
          disabled={busy}
          onClick={() => {
            if (confirm(`Apagar o empréstimo a ${loan.person} e as devoluções registadas?`)) {
              void act(() => api.deleteLoan(loan.id));
            }
          }}
          aria-label={`Apagar o empréstimo a ${loan.person}`}
        >
          <Trash size={15} />
        </button>
      </div>
      <div className="afig num">
        {money(loan.amount, loan.currency)} a {dayLabel(loan.lentOn)} · devolvidos <b>{money(loan.repaid, loan.currency)}</b>
        {Object.entries(loan.otherCurrencies).map(([c, v]) => <span key={c}> + {money(v, c)}</span>)}
      </div>
      <div className="mfoot">
        {loan.status === 'SETTLED' && loan.settledOn
          ? `Pago a ${dayLabel(loan.settledOn)}`
          : `Fechado a ${loan.closedOn ? dayLabel(loan.closedOn) : '?'}${loan.closingNote ? `: ${loan.closingNote}` : ''}`}
      </div>
    </div>
  );
}

/** Loans that are over, folded away: a record to look back on, not something to act on. */
function Finished({ revision, onChanged }: Props) {
  const list = useLoad(() => api.finishedLoans(), `finished-loans:${revision}`);
  const [open, setOpen] = useState(false);
  const rows = list.data ?? [];

  if (list.error) return <ErrorBanner message={list.error} onRetry={list.reload} />;
  if (rows.length === 0) return null;

  return (
    <section className="card">
      <button
        type="button"
        className="chead fold"
        style={open ? undefined : { marginBottom: 0 }}
        onClick={() => setOpen(!open)}
        aria-expanded={open}
      >
        {open ? <CaretDown size={14} /> : <CaretRight size={14} />}
        <span className="ctitle">Encerrados</span>
        <span className="chint">{rows.length === 1 ? '1 empréstimo' : `${rows.length} empréstimos`}</span>
      </button>
      {open && rows.map((l) => <FinishedRow key={l.id} loan={l} onChanged={onChanged} />)}
    </section>
  );
}

/** Expenses filed as loans that nobody is tracking yet, one click from becoming one. */
function Untracked({ rows, onTrack }: { rows: Transaction[]; onTrack: (t: Transaction) => void }) {
  if (rows.length === 0) return null;
  return (
    <Card title="Movimentos de empréstimo por controlar" hint="Saíram como empréstimo, mas não estão aqui">
      <table>
        <tbody>
          {rows.map((t) => (
            <tr key={t.id}>
              <td style={{ width: 120 }}>{dayLabel(t.occurredOn)}</td>
              <td>
                {t.description ?? 'sem descrição'}
                {t.goal && <span className="tag jar" style={{ marginLeft: 8 }}>{t.goal}</span>}
              </td>
              <td className="r num">{money(t.amount, t.currency)}</td>
              <td style={{ width: 96 }}>
                <div className="rowbtns">
                  <button className="btn ghost" style={{ height: 30 }} onClick={() => onTrack(t)}>Controlar</button>
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </Card>
  );
}

export function Loans({ revision, onChanged }: Props) {
  const list = useLoad(() => api.loans(), `loans:${revision}`);
  const untracked = useLoad(() => api.untrackedLoans(), `untracked-loans:${revision}`);
  const [draft, setDraft] = useState<Draft | null>(null);

  const rows = list.data ?? [];
  const people = [...new Set(rows.map((l) => l.person))].sort((a, b) => a.localeCompare(b, 'pt'));

  // What is owed, per currency, euros first
  const owed = new Map<string, number>();
  for (const l of rows) owed.set(l.currency, (owed.get(l.currency) ?? 0) + l.outstanding);
  const totals = [...owed.entries()].sort(([a], [b]) => (a === 'EUR' ? -1 : b === 'EUR' ? 1 : a.localeCompare(b)));

  function track(t: Transaction) {
    setDraft({
      ...blank(),
      amount: String(t.amount),
      currency: t.currency,
      lentOn: t.occurredOn,
      description: t.description ?? '',
      transaction: t,
    });
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  return (
    <>
      {rows.length > 0 && (
        <div className="figs">
          {totals.map(([currency, sum], i) => {
            const count = rows.filter((l) => l.currency === currency).length;
            return (
              <Figure key={currency} lead={i === 0}
                      label={i === 0 ? 'Ainda te devem' : `Em ${currencyName(currency)}`}
                      value={money(sum, currency)}
                      meta={`${count === 1 ? '1 empréstimo' : `${count} empréstimos`} em ${currencyName(currency)}`} />
            );
          })}
          <Figure label="Em aberto" value={String(rows.length)}
                  meta={people.length === 1 ? '1 pessoa' : `${people.length} pessoas`} />
        </div>
      )}

      {(draft || rows.length > 0) && (
        <Card title={draft ? 'Novo empréstimo' : undefined}>
          {draft ? (
            <NewLoan draft={draft} setDraft={setDraft} people={people} onDone={onChanged} />
          ) : (
            <button className="btn" onClick={() => setDraft(blank())}>
              <Plus size={15} weight="bold" />
              Novo empréstimo
            </button>
          )}
        </Card>
      )}

      {!untracked.error && <Untracked rows={untracked.data ?? []} onTrack={track} />}

      {list.error ? (
        <ErrorBanner message={list.error} onRetry={list.reload} />
      ) : list.loading ? (
        <Card><Skeleton rows={3} /></Card>
      ) : rows.length === 0 ? (
        !draft && (
          <Card>
            <Empty
              icon={<HandCoins size={32} />}
              title="Ninguém te deve nada"
              body="Quando emprestares dinheiro a alguém, regista-o aqui ou diz ao assistente. Cada devolução vai descontando, mesmo que chegue noutra moeda."
              action={<button className="btn" onClick={() => setDraft(blank())}>Registar um empréstimo</button>}
            />
          </Card>
        )
      ) : (
        rows.map((l) => <LoanCard key={l.id} loan={l} onChanged={onChanged} />)
      )}

      <Finished revision={revision} onChanged={onChanged} />
    </>
  );
}
