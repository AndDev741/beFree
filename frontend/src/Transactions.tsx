import { useEffect, useState, type FormEvent } from 'react';
import { Check, PencilSimple, Plus, Receipt, Trash, X } from '@phosphor-icons/react';
import { api, currentMonth, eur, monthLabel, type Transaction, type TransactionType } from './api';
import { useLoad } from './hooks';
import { Card, CategoryField, Empty, ErrorBanner, Skeleton } from './ui';

interface Props {
  month: string;
  revision: number;
  onChanged: () => void;
  startDay: number;
}

/** A row being edited. Amounts stay strings while typing, so "12," is not NaN. */
interface Draft {
  id: number;
  amount: string;
  description: string;
  occurredOn: string;
  categoryId: number | null;
}

/** Today when you are looking at the month you are living in, else where that month starts. */
function defaultDate(month: string, startDay: number) {
  const today = new Date();
  if (month === currentMonth(startDay, today)) {
    return today.toISOString().slice(0, 10);
  }
  const [y, m] = month.split('-').map(Number);
  const first = startDay > 1 ? new Date(y, m - 2, startDay) : new Date(y, m - 1, 1);
  return `${first.getFullYear()}-${String(first.getMonth() + 1).padStart(2, '0')}-${String(first.getDate()).padStart(2, '0')}`;
}

const dayLabel = (iso: string) =>
  new Date(iso + 'T00:00:00').toLocaleDateString('pt-PT', { day: '2-digit', month: 'short' });

export function Transactions({ month, revision, onChanged, startDay }: Props) {
  const list = useLoad(() => api.transactions(month), `tx:${month}:${revision}`);
  const categories = useLoad(() => api.categories(), `cat:${revision}`);

  const [type, setType] = useState<TransactionType>('EXPENSE');
  const [amount, setAmount] = useState('');
  const [description, setDescription] = useState('');
  const [occurredOn, setOccurredOn] = useState(() => defaultDate(month, startDay));
  const [categoryId, setCategoryId] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [editing, setEditing] = useState<Draft | null>(null);

  // Changing month up in the header should move the default date with it
  useEffect(() => setOccurredOn(defaultDate(month, startDay)), [month, startDay]);

  async function add(e: FormEvent) {
    e.preventDefault();
    const value = Number(amount.replace(',', '.'));
    if (!Number.isFinite(value) || value <= 0) {
      setFormError('O valor tem de ser maior que zero.');
      return;
    }
    setBusy(true);
    setFormError(null);
    try {
      await api.createTransaction({
        amount: value,
        type,
        description: description.trim() || null,
        occurredOn,
        categoryId,
      });
      setAmount('');
      setDescription('');
      onChanged();
    } catch (err) {
      setFormError(err instanceof Error ? err.message : 'Não consegui gravar.');
    } finally {
      setBusy(false);
    }
  }

  async function remove(t: Transaction) {
    if (!confirm(`Apagar ${eur(t.amount)} € ${t.description ?? ''}?`)) return;
    await api.deleteTransaction(t.id);
    onChanged();
  }

  async function saveEdit(draft: Draft) {
    const value = Number(draft.amount.replace(',', '.'));
    if (!Number.isFinite(value) || value <= 0) return;
    await api.updateTransaction(draft.id, {
      amount: value,
      description: draft.description,
      occurredOn: draft.occurredOn,
      ...(draft.categoryId === null ? { clearCategory: true } : { categoryId: draft.categoryId }),
    });
    setEditing(null);
    onChanged();
  }

  const rows = list.data ?? [];
  const cats = categories.data ?? [];

  return (
    <>
      <Card title="Novo movimento" hint={monthLabel(month)}>
        <form className="formrow" onSubmit={add}>
          <div className="seg" role="group" aria-label="Tipo">
            <button type="button" onClick={() => setType('EXPENSE')} aria-pressed={type === 'EXPENSE'}>
              Gasto
            </button>
            <button type="button" onClick={() => setType('INCOME')} aria-pressed={type === 'INCOME'}>
              Entrada
            </button>
          </div>
          <div className="field" style={{ width: 118 }}>
            <label htmlFor="a">Valor (€)</label>
            <input id="a" className="input num" inputMode="decimal" placeholder="12,50"
                   value={amount} onChange={(e) => setAmount(e.target.value)} />
          </div>
          <div className="field" style={{ flex: 1, minWidth: 160 }}>
            <label htmlFor="d">Descrição</label>
            <input id="d" className="input" placeholder="almoço"
                   value={description} onChange={(e) => setDescription(e.target.value)} />
          </div>
          <div className="field" style={{ width: 168 }}>
            <label htmlFor="c">Categoria</label>
            <CategoryField id="c" categories={cats} value={categoryId}
                           onChange={setCategoryId} onCreated={onChanged} />
          </div>
          <div className="field" style={{ width: 150 }}>
            <label htmlFor="o">Data</label>
            <input id="o" className="input" type="date" value={occurredOn}
                   onChange={(e) => setOccurredOn(e.target.value)} />
          </div>
          <button className="btn" type="submit" disabled={busy || !amount}>
            <Plus size={15} weight="bold" />
            Registar
          </button>
        </form>
        {formError && <p className="err" style={{ marginTop: 10 }}>{formError}</p>}
      </Card>

      <Card title="Movimentos" hint={rows.length ? `${rows.length} em ${monthLabel(month)}` : undefined}>
        {list.error ? (
          <ErrorBanner message={list.error} onRetry={list.reload} />
        ) : list.loading ? (
          <Skeleton rows={6} />
        ) : rows.length === 0 ? (
          <Empty
            icon={<Receipt size={30} />}
            title="Nada neste mês"
            body="Regista acima, ou manda uma mensagem ao assistente e ele trata disso."
          />
        ) : (
          <table>
            <thead>
              <tr>
                <th style={{ width: 78 }}>Data</th>
                <th>Descrição</th>
                <th style={{ width: 130 }}>Categoria</th>
                <th style={{ width: 110, textAlign: 'right' }}>Valor</th>
                <th style={{ width: 66 }} />
              </tr>
            </thead>
            <tbody>
              {rows.map((t) =>
                editing?.id === t.id ? (
                  <tr key={t.id}>
                    <td>
                      <input className="input mini" type="date" value={editing.occurredOn}
                             onChange={(e) => setEditing({ ...editing, occurredOn: e.target.value })} />
                    </td>
                    <td>
                      <input className="input mini" value={editing.description}
                             onChange={(e) => setEditing({ ...editing, description: e.target.value })} />
                    </td>
                    <td>
                      <CategoryField
                        compact
                        categories={cats}
                        value={editing.categoryId}
                        onChange={(id) => setEditing({ ...editing, categoryId: id })}
                        onCreated={() => categories.reload()}
                      />
                    </td>
                    <td>
                      <input className="input mini num" style={{ textAlign: 'right' }} inputMode="decimal"
                             value={editing.amount}
                             onChange={(e) => setEditing({ ...editing, amount: e.target.value })} />
                    </td>
                    <td>
                      <div className="rowbtns">
                        <button className="iconbtn" onClick={() => saveEdit(editing)} aria-label="Guardar">
                          <Check size={15} weight="bold" />
                        </button>
                        <button className="iconbtn" onClick={() => setEditing(null)} aria-label="Cancelar">
                          <X size={15} />
                        </button>
                      </div>
                    </td>
                  </tr>
                ) : (
                  <tr key={t.id}>
                    <td className="num" style={{ color: 'var(--ink-2)' }}>{dayLabel(t.occurredOn)}</td>
                    <td>
                      {t.description || <span style={{ color: 'var(--ink-3)' }}>sem descrição</span>}
                      {t.source === 'WHATSAPP' && <span className="tag" style={{ marginLeft: 8 }}>whatsapp</span>}
                    </td>
                    <td>{t.category ? <span className="tag">{t.category}</span> : null}</td>
                    <td className="r num" style={t.type === 'INCOME' ? { color: 'var(--good)' } : undefined}>
                      {t.type === 'INCOME' ? '+' : '−'}{eur(t.amount)} €
                    </td>
                    <td>
                      <div className="rowbtns">
                        <button
                          className="iconbtn"
                          onClick={() => setEditing({
                            id: t.id,
                            amount: String(t.amount),
                            description: t.description ?? '',
                            occurredOn: t.occurredOn,
                            categoryId: cats.find((c) => c.name === t.category)?.id ?? null,
                          })}
                          aria-label="Editar"
                        >
                          <PencilSimple size={15} />
                        </button>
                        <button className="iconbtn" onClick={() => remove(t)} aria-label="Apagar">
                          <Trash size={15} />
                        </button>
                      </div>
                    </td>
                  </tr>
                ),
              )}
            </tbody>
          </table>
        )}
      </Card>
    </>
  );
}
