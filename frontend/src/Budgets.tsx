import { useState, type FormEvent } from 'react';
import { Check, PencilSimple, Trash, Wallet, X } from '@phosphor-icons/react';
import { api, eur, monthLabel } from './api';
import { useLoad } from './hooks';
import { Card, CategoryField, Empty, ErrorBanner, Flag, Meter, Skeleton } from './ui';

interface Props {
  month: string;
  revision: number;
  onChanged: () => void;
}

export function Budgets({ month, revision, onChanged }: Props) {
  const list = useLoad(() => api.budgets(month), `budgets:${month}:${revision}`);
  const categories = useLoad(() => api.categories(), `cat:${revision}`);

  const [categoryId, setCategoryId] = useState<number | null>(null);
  const [limit, setLimit] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<{ category: string; limit: string } | null>(null);

  const cats = categories.data ?? [];
  const rows = list.data ?? [];

  async function save(e: FormEvent) {
    e.preventDefault();
    const name = cats.find((c) => c.id === categoryId)?.name;
    const value = Number(limit.replace(',', '.'));
    if (!name || !Number.isFinite(value) || value <= 0) {
      setError('Escolhe uma categoria e um limite maior que zero.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await api.setBudget(name, value, month);
      setLimit('');
      onChanged();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Não consegui gravar.');
    } finally {
      setBusy(false);
    }
  }

  // The PUT is an upsert, so changing a limit is the same call as setting one
  async function saveEdit(category: string, raw: string) {
    const value = Number(raw.replace(',', '.'));
    if (!Number.isFinite(value) || value <= 0) return;
    await api.setBudget(category, value, month);
    setEditing(null);
    onChanged();
  }

  async function remove(name: string) {
    if (!confirm(`Remover o limite de ${name}?`)) return;
    await api.removeBudget(name, month);
    onChanged();
  }

  const totalLimit = rows.reduce((s, b) => s + b.limitAmount, 0);
  const totalSpent = rows.reduce((s, b) => s + b.spent, 0);
  const alreadySet = new Set(rows.map((b) => b.category));

  return (
    <>
      <Card title="Definir limite" hint={monthLabel(month)}>
        <form className="formrow" onSubmit={save}>
          <div className="field" style={{ flex: 1, minWidth: 180 }}>
            <label htmlFor="bc">Categoria</label>
            <CategoryField
              id="bc"
              categories={cats.filter((c) => !alreadySet.has(c.name))}
              value={categoryId}
              onChange={setCategoryId}
              onCreated={onChanged}
              noneLabel="Escolher…"
            />
          </div>
          <div className="field" style={{ width: 140 }}>
            <label htmlFor="bl">Limite (€)</label>
            <input id="bl" className="input num" inputMode="decimal" placeholder="250"
                   value={limit} onChange={(e) => setLimit(e.target.value)} />
          </div>
          <button className="btn" type="submit" disabled={busy || !categoryId || !limit}>Guardar</button>
        </form>
        <p className="help" style={{ marginTop: 9 }}>
          O limite vale para este mês. Meses futuros herdam o último que definiste.
        </p>
        {error && <p className="err" style={{ marginTop: 8 }}>{error}</p>}
      </Card>

      <Card title="Este mês" hint={rows.length ? `${eur(totalSpent)} € de ${eur(totalLimit)} €` : undefined}>
        {list.error ? (
          <ErrorBanner message={list.error} onRetry={list.reload} />
        ) : list.loading ? (
          <Skeleton rows={4} />
        ) : rows.length === 0 ? (
          <Empty
            icon={<Wallet size={30} />}
            title="Ainda sem limites"
            body="Escolhe uma categoria acima. A partir daí vês quanto já foi e quanto sobra, todos os meses."
          />
        ) : (
          rows.map((b) => {
            const used = b.percentUsed ?? 0;
            const open = editing?.category === b.category;
            return (
              <Meter
                key={b.category}
                name={b.category}
                valueLabel={
                  open ? (
                    <span className="pickrow">
                      <input
                        className="input mini num"
                        style={{ width: 92, textAlign: 'right' }}
                        inputMode="decimal"
                        autoFocus
                        aria-label={`Limite de ${b.category}`}
                        value={editing.limit}
                        onChange={(e) => setEditing({ ...editing, limit: e.target.value })}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter') void saveEdit(b.category, editing.limit);
                          if (e.key === 'Escape') setEditing(null);
                        }}
                      />
                      <button className="iconbtn" onClick={() => void saveEdit(b.category, editing.limit)} aria-label="Guardar">
                        <Check size={15} weight="bold" />
                      </button>
                      <button className="iconbtn" onClick={() => setEditing(null)} aria-label="Cancelar">
                        <X size={15} />
                      </button>
                    </span>
                  ) : (
                    <>
                      <b>{eur(b.spent)} €</b> de {eur(b.limitAmount)} €
                    </>
                  )
                }
                percent={used}
                tone={b.overspent ? 'over' : used >= 80 ? 'near' : undefined}
                foot={
                  <span className="pickrow">
                    {b.overspent ? (
                      <Flag tone="over">{eur(-b.remaining)} € acima</Flag>
                    ) : used >= 80 ? (
                      <Flag tone="near">restam {eur(b.remaining)} €</Flag>
                    ) : (
                      <Flag tone="ok">restam {eur(b.remaining)} €</Flag>
                    )}
                    <span className="grow" />
                    <button
                      className="iconbtn"
                      onClick={() => setEditing({ category: b.category, limit: String(b.limitAmount) })}
                      aria-label={`Editar o limite de ${b.category}`}
                    >
                      <PencilSimple size={14} />
                    </button>
                    <button className="iconbtn" onClick={() => remove(b.category)} aria-label={`Remover ${b.category}`}>
                      <Trash size={14} />
                    </button>
                  </span>
                }
              />
            );
          })
        )}
      </Card>
    </>
  );
}
