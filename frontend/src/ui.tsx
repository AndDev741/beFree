import { useState, type ReactNode } from 'react';
import { Check, WarningCircle, Warning, X } from '@phosphor-icons/react';
import { api, type Category } from './api';

export function Card({ title, hint, children }: { title?: string; hint?: string; children: ReactNode }) {
  return (
    <section className="card">
      {title && (
        <div className="chead">
          <span className="ctitle">{title}</span>
          {hint && <span className="chint">{hint}</span>}
        </div>
      )}
      {children}
    </section>
  );
}

export function Figure({ label, value, meta, lead, positive }: {
  label: string; value: string; meta?: string; lead?: boolean; positive?: boolean;
}) {
  return (
    <div className={lead ? 'fig lead' : 'fig'}>
      <div className="flabel">{label}</div>
      <div className={positive ? 'fval num pos' : 'fval num'}>{value}</div>
      {meta && <div className="fmeta">{meta}</div>}
    </div>
  );
}

export function Meter({ name, valueLabel, percent, tone, foot }: {
  name: string; valueLabel: ReactNode; percent: number;
  tone?: 'over' | 'near' | 'save'; foot?: ReactNode;
}) {
  return (
    <div className="meter">
      <div className="mrow">
        <span className="mname">{name}</span>
        <span className="mnum num">{valueLabel}</span>
      </div>
      <div className="bar">
        <i className={tone} style={{ width: `${Math.min(100, Math.max(0, percent))}%` }} />
      </div>
      {foot && <div className="mfoot">{foot}</div>}
    </div>
  );
}

export function Flag({ tone, children }: { tone: 'over' | 'near' | 'ok'; children: ReactNode }) {
  const Icon = tone === 'over' ? WarningCircle : Warning;
  return (
    <span className={`flag ${tone}`}>
      {tone !== 'ok' && <Icon size={14} weight="fill" />}
      {children}
    </span>
  );
}

export function ErrorBanner({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div className="banner">
      <WarningCircle size={18} weight="fill" style={{ color: 'var(--crit)', flex: 'none', marginTop: 1 }} />
      <div>
        <b>Alguma coisa correu mal.</b>
        <p>{message}</p>
      </div>
      <span className="grow" />
      {onRetry && <button className="btn ghost" style={{ flex: 'none' }} onClick={onRetry}>Tentar de novo</button>}
    </div>
  );
}

export function Skeleton({ rows = 4 }: { rows?: number }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 11 }}>
      <div className="skel" style={{ height: 44 }} />
      {Array.from({ length: rows }).map((_, i) => (
        <div key={i} style={{ display: 'flex', gap: 10 }}>
          <div className="skel" style={{ height: 15, flex: 1 }} />
          <div className="skel" style={{ height: 15, width: 62 }} />
        </div>
      ))}
    </div>
  );
}

export function Empty({ icon, title, body, action }: {
  icon: ReactNode; title: string; body: string; action?: ReactNode;
}) {
  return (
    <div className="empty">
      <div style={{ color: 'var(--ink-3)' }}>{icon}</div>
      <h3>{title}</h3>
      <p>{body}</p>
      {action}
    </div>
  );
}

const NEW = '__new__';

/**
 * Every place that asks for a category can also invent one, because the moment
 * you need a category is the moment you are typing the movement, not before.
 */
export function CategoryField({ categories, value, onChange, onCreated, compact, id, noneLabel }: {
  categories: Category[];
  value: number | null;
  onChange: (id: number | null) => void;
  onCreated?: () => void;
  compact?: boolean;
  id?: string;
  noneLabel?: string;
}) {
  const [naming, setNaming] = useState(false);
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const cls = compact ? 'input mini' : 'input';

  async function create() {
    const clean = name.trim();
    if (!clean) {
      setNaming(false);
      return;
    }
    setBusy(true);
    try {
      // Typing a name that already exists should pick it, not fail on the unique index
      const existing = categories.find((c) => c.name.toLowerCase() === clean.toLowerCase());
      const category = existing ?? (await api.createCategory(clean));
      onChange(category.id);
      onCreated?.();
      setName('');
      setNaming(false);
    } finally {
      setBusy(false);
    }
  }

  if (naming) {
    return (
      <div className="pickrow">
        <input
          className={cls}
          autoFocus
          placeholder="nome da categoria"
          value={name}
          disabled={busy}
          onChange={(e) => setName(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              e.preventDefault();
              void create();
            }
            if (e.key === 'Escape') setNaming(false);
          }}
        />
        <button type="button" className="iconbtn" onClick={() => void create()} disabled={busy} aria-label="Criar categoria">
          <Check size={15} weight="bold" />
        </button>
        <button type="button" className="iconbtn" onClick={() => setNaming(false)} aria-label="Cancelar">
          <X size={15} />
        </button>
      </div>
    );
  }

  return (
    <select
      id={id}
      className={cls}
      value={value ?? ''}
      onChange={(e) => {
        if (e.target.value === NEW) setNaming(true);
        else onChange(e.target.value ? Number(e.target.value) : null);
      }}
    >
      <option value="">{noneLabel ?? 'Sem categoria'}</option>
      {categories.map((c) => (
        <option key={c.id} value={c.id}>{c.name}</option>
      ))}
      <option value={NEW}>＋ nova…</option>
    </select>
  );
}

/** One colour per slot, assigned in fixed order and never cycled. */
export const SERIES = ['var(--d1)', 'var(--d2)', 'var(--d3)', 'var(--d4)', 'var(--d5)', 'var(--d0)'];
