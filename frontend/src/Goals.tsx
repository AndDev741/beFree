import { useState, type FormEvent } from 'react';
import {
  Archive, ArrowCounterClockwise, ArrowUUpLeft, CaretDown, CaretRight, CheckCircle, Confetti, PencilSimple, Plus, Target, Trash,
} from '@phosphor-icons/react';
import { api, ApiError, eur, type GoalView } from './api';
import { useLoad } from './hooks';
import { Card, Empty, ErrorBanner, Skeleton } from './ui';

interface Props {
  revision: number;
  onChanged: () => void;
}

const dateLabel = (iso: string) =>
  new Date(iso + 'T00:00:00').toLocaleDateString('pt-PT', { month: 'long', year: 'numeric' });

/** The day it was reached deserves a day, not just the month. */
const dayLabel = (iso: string) =>
  new Date(iso + 'T00:00:00').toLocaleDateString('pt-PT', { day: 'numeric', month: 'short', year: 'numeric' });

interface Edit {
  name: string;
  target: string;
  initial: string;
  targetDate: string;
  description: string;
}

/** One goal: the ring, what is missing, and a box to put money in. */
function GoalCard({ goal, onChanged }: { goal: GoalView; onChanged: () => void }) {
  const [amount, setAmount] = useState('');
  const [busy, setBusy] = useState(false);
  const [edit, setEdit] = useState<Edit | null>(null);
  const percent = Math.min(100, goal.percent ?? 0);

  async function saveEdit(e: FormEvent) {
    e.preventDefault();
    if (!edit) return;
    const target = Number(edit.target.replace(',', '.'));
    if (!edit.name.trim() || !Number.isFinite(target) || target <= 0) return;
    setBusy(true);
    try {
      const already = edit.initial.trim() ? Number(edit.initial.replace(',', '.')) : 0;
      if (!Number.isFinite(already) || already < 0 || already > target) return;
      await api.updateGoal(goal.id, {
        name: edit.name.trim(),
        target,
        initial: already,
        description: edit.description.trim(),
        // An empty date field means the goal no longer has a deadline
        ...(edit.targetDate ? { targetDate: edit.targetDate } : { clearTargetDate: true }),
      });
      setEdit(null);
      onChanged();
    } finally {
      setBusy(false);
    }
  }

  async function contribute(e: FormEvent) {
    e.preventDefault();
    const value = Number(amount.replace(',', '.'));
    if (!Number.isFinite(value) || value === 0) return;
    setBusy(true);
    try {
      await api.contribute(goal.id, { amount: value });
      setAmount('');
      onChanged();
    } finally {
      setBusy(false);
    }
  }

  async function toggleAchieved() {
    setBusy(true);
    try {
      await api.updateGoal(goal.id, { achieved: !goal.reached });
      onChanged();
    } finally {
      setBusy(false);
    }
  }

  async function archive() {
    // Whatever is left in the jar goes out of sight with it
    if (goal.saved > 0 && !confirm(`Ainda há ${eur(goal.saved)} € em "${goal.name}". Arquivar na mesma?`)) return;
    setBusy(true);
    try {
      await api.archiveGoal(goal.id);
      onChanged();
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    if (!confirm(`Apagar o objetivo "${goal.name}" e as suas contribuições?`)) return;
    await api.deleteGoal(goal.id);
    onChanged();
  }

  return (
    <section className="card goal">
      <div className="ring" style={{ ['--p' as string]: `${percent}` }}>
        <span className="num">{Math.round(percent)}%</span>
      </div>

      <div className="ginfo">
        <div className="chead" style={{ marginBottom: 6 }}>
          <span className="ctitle">{goal.name}</span>
          {goal.reached && (
            <span className="flag ok" style={{ color: 'var(--d3)' }} title={goal.achievedOn ? `Atingido a ${goal.achievedOn}` : undefined}>
              <Confetti size={14} weight="fill" /> atingido
            </span>
          )}
          <span className="grow" />
          <button
            className="iconbtn"
            onClick={() => void toggleAchieved()}
            disabled={busy}
            aria-label={goal.reached ? `Voltar a juntar para ${goal.name}` : `Marcar ${goal.name} como atingido`}
            title={goal.reached ? 'Voltar a juntar' : 'Já atingi este objetivo'}
          >
            {goal.reached ? <ArrowCounterClockwise size={15} /> : <CheckCircle size={15} />}
          </button>
          <button
            className="iconbtn"
            onClick={() => setEdit({
              name: goal.name,
              target: String(goal.target),
              initial: String(goal.initial),
              targetDate: goal.targetDate ?? '',
              description: goal.description ?? '',
            })}
            aria-label={`Editar ${goal.name}`}
          >
            <PencilSimple size={15} />
          </button>
          <button
            className="iconbtn"
            onClick={() => void archive()}
            disabled={busy}
            aria-label={`Arquivar ${goal.name}`}
            title="Arquivar: sai dos objetivos e fica no histórico"
          >
            <Archive size={15} />
          </button>
          <button className="iconbtn" onClick={remove} aria-label={`Apagar ${goal.name}`}>
            <Trash size={15} />
          </button>
        </div>

        {edit && (
          <form className="formrow" style={{ marginBottom: 12 }} onSubmit={saveEdit}>
            <div className="field" style={{ flex: 1, minWidth: 150 }}>
              <label>Nome</label>
              <input className="input mini" autoFocus value={edit.name}
                     onChange={(e) => setEdit({ ...edit, name: e.target.value })} />
            </div>
            <div className="field" style={{ width: 110 }}>
              <label>Alvo (€)</label>
              <input className="input mini num" inputMode="decimal" value={edit.target}
                     onChange={(e) => setEdit({ ...edit, target: e.target.value })} />
            </div>
            <div className="field" style={{ width: 120 }}>
              <label>Já tinha (€)</label>
              <input className="input mini num" inputMode="decimal" value={edit.initial}
                     onChange={(e) => setEdit({ ...edit, initial: e.target.value })} />
            </div>
            <div className="field" style={{ width: 146 }}>
              <label>Data alvo</label>
              <input className="input mini" type="date" value={edit.targetDate}
                     onChange={(e) => setEdit({ ...edit, targetDate: e.target.value })} />
            </div>
            <div className="field" style={{ flex: 1, minWidth: 140 }}>
              <label>Nota</label>
              <input className="input mini" value={edit.description}
                     onChange={(e) => setEdit({ ...edit, description: e.target.value })} />
            </div>
            <button className="btn" type="submit" disabled={busy}>Guardar</button>
            <button className="btn ghost" type="button" onClick={() => setEdit(null)}>Cancelar</button>
          </form>
        )}

        {!edit && goal.description && <p className="help" style={{ margin: '0 0 8px' }}>{goal.description}</p>}

        <div className="mrow">
          <span className="mnum num" style={{ marginLeft: 0 }}>
            <b>{eur(goal.saved)} €</b> de {eur(goal.target)} €
          </span>
        </div>
        <div className="bar">
          <i className="save" style={{ width: `${percent}%` }} />
        </div>
        {goal.spent > 0 && (
          <div className="mfoot">
            Já gastaste {eur(goal.spent)} € deste objetivo, e sobram {eur(goal.saved)} €
          </div>
        )}
        {goal.initial > 0 && goal.spent === 0 && !goal.reached && (
          <div className="mfoot">
            {eur(goal.initial)} € já tinhas, {eur(goal.saved - goal.initial)} € juntaste desde então
          </div>
        )}
        <div className="mfoot">
          {goal.reached
            ? goal.spent > 0
              ? `Cumprido${goal.achievedOn ? ` a ${dayLabel(goal.achievedOn)}` : ''}. Estás a gastar o que juntaste.`
              : `Objetivo cumprido${goal.achievedOn ? ` a ${dayLabel(goal.achievedOn)}` : ''}.`
            : goal.perMonth && goal.targetDate
              ? `${eur(goal.perMonth)} € por mês até ${dateLabel(goal.targetDate)}`
              : goal.targetDate
                ? `Faltam ${eur(goal.remaining)} € até ${dateLabel(goal.targetDate)}`
                : `Faltam ${eur(goal.remaining)} €`}
        </div>

        <form className="formrow" style={{ marginTop: 12 }} onSubmit={contribute}>
          <div className="field" style={{ width: 120 }}>
            <input className="input num" inputMode="decimal" placeholder="Juntar €"
                   aria-label={`Juntar a ${goal.name}`}
                   value={amount} onChange={(e) => setAmount(e.target.value)} />
          </div>
          <button className="btn ghost" type="submit" disabled={busy || !amount}>Juntar</button>
        </form>
      </div>
    </section>
  );
}

/** One archived goal with its money and its dates. */
function ArchivedRow({ goal, onChanged }: { goal: GoalView; onChanged: () => void }) {
  const [busy, setBusy] = useState(false);

  async function unarchive() {
    setBusy(true);
    try {
      await api.unarchiveGoal(goal.id);
      onChanged();
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    if (!confirm(`Apagar o objetivo "${goal.name}" e as suas contribuições?`)) return;
    await api.deleteGoal(goal.id);
    onChanged();
  }

  return (
    <div className="arow">
      <div className="mrow" style={{ marginBottom: 2 }}>
        <span className="mname">{goal.name}</span>
        <span className="grow" />
        <button
          className="iconbtn"
          onClick={() => void unarchive()}
          disabled={busy}
          aria-label={`Desarquivar ${goal.name}`}
          title="Desarquivar: volta aos objetivos"
        >
          <ArrowUUpLeft size={15} />
        </button>
        <button className="iconbtn" onClick={remove} aria-label={`Apagar ${goal.name}`}>
          <Trash size={15} />
        </button>
      </div>
      <div className="afig num">
        Alvo {eur(goal.target)} € · juntaste <b>{eur(goal.saved + goal.spent)} €</b>
        {goal.spent > 0 && <> · gastaste <b>{eur(goal.spent)} €</b></>}
        {goal.saved !== 0 && <> · sobram {eur(goal.saved)} €</>}
      </div>
      <div className="mfoot">
        {goal.reached ? (
          <span className="flag ok" style={{ color: 'var(--d3)' }}>
            <Confetti size={14} weight="fill" />
            {goal.achievedOn ? `atingido a ${dayLabel(goal.achievedOn)}` : 'atingido'}
          </span>
        ) : (
          'não chegou ao alvo'
        )}
        {goal.archivedOn && ` · arquivado a ${dayLabel(goal.archivedOn)}`}
      </div>
    </div>
  );
}

/** Goals you are done with, folded away by default because they are a record to look back on. */
function Archived({ revision, onChanged }: Props) {
  const list = useLoad(() => api.archivedGoals(), `archived:${revision}`);
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
        <span className="ctitle">Arquivados</span>
        <span className="chint">{rows.length === 1 ? '1 objetivo' : `${rows.length} objetivos`}</span>
      </button>
      {open && rows.map((g) => <ArchivedRow key={g.id} goal={g} onChanged={onChanged} />)}
    </section>
  );
}

export function Goals({ revision, onChanged }: Props) {
  const list = useLoad(() => api.goals(), `goals:${revision}`);

  const [open, setOpen] = useState(false);
  const [name, setName] = useState('');
  const [target, setTarget] = useState('');
  const [initial, setInitial] = useState('');
  const [targetDate, setTargetDate] = useState('');
  const [description, setDescription] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function create(e: FormEvent) {
    e.preventDefault();
    const value = Number(target.replace(',', '.'));
    if (!name.trim() || !Number.isFinite(value) || value <= 0) {
      setError('Precisa de um nome e de um alvo maior que zero.');
      return;
    }
    const already = initial.trim() ? Number(initial.replace(',', '.')) : 0;
    if (!Number.isFinite(already) || already < 0) {
      setError('O valor que já tens não pode ser negativo.');
      return;
    }
    if (already > value) {
      setError('Já tens mais do que o alvo. Sobe o alvo.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await api.createGoal({
        name: name.trim(),
        target: value,
        initial: already,
        targetDate: targetDate || null,
        description: description.trim() || null,
      });
      setName('');
      setTarget('');
      setInitial('');
      setTargetDate('');
      setDescription('');
      setOpen(false);
      onChanged();
    } catch (err) {
      // A taken name comes back as a bare 400, and an archived goal is out of sight
      const archived = err instanceof ApiError && err.status === 400
        ? await api.archivedGoals().catch(() => [])
        : [];
      const hidden = archived.some((g) => g.name.toLowerCase() === name.trim().toLowerCase());
      setError(hidden
        ? 'Já tens um objetivo arquivado com este nome. Desarquiva-o, ou escolhe outro nome.'
        : err instanceof Error ? err.message : 'Não consegui criar.');
    } finally {
      setBusy(false);
    }
  }

  const rows = list.data ?? [];

  return (
    <>
      {(open || rows.length > 0) && (
        <Card title={open ? 'Novo objetivo' : 'Objetivos'}>
          {open ? (
            <form className="formrow" onSubmit={create}>
              <div className="field" style={{ flex: 1, minWidth: 160 }}>
                <label htmlFor="gn">Nome</label>
                <input id="gn" className="input" autoFocus placeholder="Fundo de emergência"
                       value={name} onChange={(e) => setName(e.target.value)} />
              </div>
              <div className="field" style={{ width: 130 }}>
                <label htmlFor="gt">Alvo (€)</label>
                <input id="gt" className="input num" inputMode="decimal" placeholder="3000"
                       value={target} onChange={(e) => setTarget(e.target.value)} />
              </div>
              <div className="field" style={{ width: 150 }}>
                <label htmlFor="gi">Já tenho (€)</label>
                <input id="gi" className="input num" inputMode="decimal" placeholder="0"
                       value={initial} onChange={(e) => setInitial(e.target.value)} />
              </div>
              <div className="field" style={{ width: 150 }}>
                <label htmlFor="gd">Data alvo</label>
                <input id="gd" className="input" type="date"
                       value={targetDate} onChange={(e) => setTargetDate(e.target.value)} />
              </div>
              <div className="field" style={{ flex: 1, minWidth: 160 }}>
                <label htmlFor="gx">Nota</label>
                <input id="gx" className="input" placeholder="seis meses de despesas"
                       value={description} onChange={(e) => setDescription(e.target.value)} />
              </div>
              <button className="btn" type="submit" disabled={busy}>Criar</button>
              <button className="btn ghost" type="button" onClick={() => setOpen(false)}>Cancelar</button>
              <p className="help" style={{ width: '100%', margin: 0 }}>
                O que já tens conta para o objetivo, mas não sai do mês: esse dinheiro foi guardado antes.
              </p>
            </form>
          ) : (
            <button className="btn" onClick={() => setOpen(true)}>
              <Plus size={15} weight="bold" />
              Novo objetivo
            </button>
          )}
          {error && <p className="err" style={{ marginTop: 9 }}>{error}</p>}
        </Card>
      )}

      {list.error ? (
        <ErrorBanner message={list.error} onRetry={list.reload} />
      ) : list.loading ? (
        <Card><Skeleton rows={3} /></Card>
      ) : rows.length === 0 ? (
        <Card>
          <Empty
            icon={<Target size={32} />}
            title="Sem objetivos"
            body="Um fundo de emergência, umas férias, um portátil novo. Metes o alvo e a data, e eu digo quanto tens de guardar por mês."
            action={<button className="btn" onClick={() => setOpen(true)}>Criar o primeiro</button>}
          />
        </Card>
      ) : (
        rows.map((g) => <GoalCard key={g.id} goal={g} onChanged={onChanged} />)
      )}

      <Archived revision={revision} onChanged={onChanged} />
    </>
  );
}
