import { ArrowUpRight, ChartBar, PiggyBank, Target, Wallet } from '@phosphor-icons/react';
import { api, eur, monthLabel, shiftMonth, type Summary } from './api';
import { useLoad } from './hooks';
import { Card, Empty, ErrorBanner, Figure, Flag, Meter, SERIES, Skeleton } from './ui';
import type { View } from './App';

interface Props {
  month: string;
  revision: number;
  onChanged: () => void;
  onOpen: (view: View) => void;
}

/** Six months of totals, drawn as paired bars. WhatsApp can send a number; it cannot send this. */
function Trend({ month, revision }: { month: string; revision: number }) {
  const months = Array.from({ length: 6 }, (_, i) => shiftMonth(month, i - 5));
  const { data, loading } = useLoad(
    () => Promise.all(months.map((m) => api.summary(m))),
    `trend:${month}:${revision}`,
  );

  if (loading || !data) return <div className="skel" style={{ height: 150 }} />;

  const peak = Math.max(1, ...data.flatMap((s) => [s.income, s.spent]));

  return (
    <>
      <div className="trend" role="img" aria-label="Entradas e gastos dos últimos seis meses">
        {data.map((s, i) => (
          <div className="tcol" key={months[i]}>
            <div className="tbars">
              <i style={{ height: `${(s.income / peak) * 100}%`, background: 'var(--d3)' }}
                 title={`Entrou ${eur(s.income)} €`} />
              <i style={{ height: `${(s.spent / peak) * 100}%`, background: 'var(--d2)' }}
                 title={`Gastou ${eur(s.spent)} €`} />
            </div>
            <span className="tlabel">{monthLabel(months[i]).slice(0, 3)}</span>
          </div>
        ))}
      </div>
      <div className="keys">
        <span><i className="dot" style={{ background: 'var(--d3)' }} /> Entrou</span>
        <span><i className="dot" style={{ background: 'var(--d2)' }} /> Gastou</span>
      </div>
    </>
  );
}

/** Where the month's money went, as one bar: each category, what was set aside, what is still free. */
function Allocation({ s }: { s: Summary }) {
  const top = s.byCategory.slice(0, 5);
  const others = s.byCategory.slice(5).reduce((sum, c) => sum + c.amount, 0);
  const slices = [
    ...top.map((c, i) => ({ label: c.category, amount: c.amount, color: SERIES[i] })),
    ...(others > 0 ? [{ label: 'Outras', amount: others, color: SERIES[5] }] : []),
  ];
  const base = Math.max(s.income, s.spent + s.reserved, 0.01);
  const free = Math.max(0, base - s.spent - s.reserved);
  const pct = (n: number) => `${(n / base) * 100}%`;

  return (
    <>
      <div className="alloc">
        {slices.map((sl) => (
          <span key={sl.label} style={{ width: pct(sl.amount), background: sl.color }}
                title={`${sl.label}: ${eur(sl.amount)} €`} />
        ))}
        {s.reserved > 0 && (
          <span className="saved" style={{ width: pct(s.reserved) }}
                title={`Guardado em objetivos: ${eur(s.reserved)} €`} />
        )}
        {free > 0 && <span className="rest" style={{ width: pct(free) }} title={`Livre: ${eur(free)} €`} />}
      </div>
      <div className="keys">
        {slices.map((sl) => (
          <span key={sl.label}>
            <i className="dot" style={{ background: sl.color }} />
            {sl.label} <b>{eur(sl.amount)} €</b>
          </span>
        ))}
        {s.reserved > 0 && (
          <span><i className="dot saved" /> Objetivos <b>{eur(s.reserved)} €</b></span>
        )}
        {free > 0 && <span><i className="dot rest" /> Livre <b>{eur(free)} €</b></span>}
      </div>
    </>
  );
}

export function Dashboard({ month, revision, onOpen }: Props) {
  const { data, error, loading, reload } = useLoad(() => api.summary(month), `summary:${month}:${revision}`);

  if (error) return <ErrorBanner message={error} onRetry={reload} />;
  if (loading || !data) {
    return (
      <>
        <div className="figs">
          <div className="skel" style={{ height: 116 }} />
          <div className="skel" style={{ height: 116 }} />
          <div className="skel" style={{ height: 116 }} />
        </div>
        <Card><Skeleton /></Card>
      </>
    );
  }

  const s = data;
  const nothing = s.income === 0 && s.spent === 0 && s.reserved === 0 && s.spentFromGoals === 0;
  const rate = s.income > 0 ? Math.round((s.reserved / s.income) * 100) : null;

  if (nothing) {
    return (
      <Card>
        <Empty
          icon={<Wallet size={34} />}
          title={`Nada registado em ${monthLabel(month)}`}
          body="Manda uma mensagem ao assistente, ou um recibo pelo WhatsApp, e aparece aqui."
        />
      </Card>
    );
  }

  return (
    <>
      <div className="figs">
        <Figure lead label="Livre este mês" value={`${eur(s.remaining)} €`}
                meta={`${eur(s.income)} € entraram, ${eur(s.spent + s.reserved)} € saíram`}
                positive={s.remaining > 0} />
        <Figure label="Gastos" value={`${eur(s.spent)} €`}
                meta={s.byCategory.length ? `${s.byCategory.length} categorias` : 'sem categorias'} />
        <Figure label="Guardado" value={`${eur(s.reserved)} €`}
                meta={rate === null ? 'em objetivos' : `${rate}% do que entrou`} />
      </div>

      <Card title="Para onde foi" hint={monthLabel(month)}>
        <Allocation s={s} />
      </Card>

      <div className="grid2">
        <Card title="Orçamentos" hint={s.budgets.length ? `${s.budgets.length} definidos` : undefined}>
          {s.budgets.length === 0 ? (
            <Empty
              icon={<Wallet size={30} />}
              title="Sem orçamentos"
              body="Define um limite por categoria e o progresso aparece aqui todos os meses."
              action={<button className="btn" onClick={() => onOpen('budgets')}>Definir limites</button>}
            />
          ) : (
            s.budgets.map((b) => {
              const used = b.percentUsed ?? 0;
              const tone = b.overspent ? 'over' : used >= 80 ? 'near' : undefined;
              return (
                <Meter
                  key={b.category}
                  name={b.category}
                  valueLabel={<><b>{eur(b.spent)} €</b> de {eur(b.limitAmount)} €</>}
                  percent={used}
                  tone={tone}
                  foot={
                    b.overspent ? (
                      <Flag tone="over">{eur(-b.remaining)} € acima do limite</Flag>
                    ) : used >= 80 ? (
                      <Flag tone="near">restam {eur(b.remaining)} €</Flag>
                    ) : (
                      <Flag tone="ok">restam {eur(b.remaining)} €</Flag>
                    )
                  }
                />
              );
            })
          )}
        </Card>

        <Card title="Objetivos">
          {s.goals.length === 0 ? (
            <Empty
              icon={<Target size={30} />}
              title="Sem objetivos"
              body="Um fundo de emergência, umas férias, o que for. Metes o alvo e eu acompanho."
              action={<button className="btn" onClick={() => onOpen('goals')}>Criar objetivo</button>}
            />
          ) : (
            s.goals.map((g) => (
              <Meter
                key={g.id}
                name={g.name}
                valueLabel={<><b>{eur(g.saved)} €</b> de {eur(g.target)} €</>}
                percent={g.percent ?? 0}
                tone="save"
                foot={
                  g.reached ? (
                    <Flag tone="ok">objetivo atingido</Flag>
                  ) : g.perMonth ? (
                    <Flag tone="ok">{eur(g.perMonth)} € por mês para lá chegar</Flag>
                  ) : (
                    <Flag tone="ok">faltam {eur(g.remaining)} €</Flag>
                  )
                }
              />
            ))
          )}
        </Card>
      </div>

      <Card title="Seis meses" hint="entradas contra gastos">
        <Trend month={month} revision={revision} />
      </Card>

      <button className="btn ghost linkrow" onClick={() => onOpen('tx')}>
        <ChartBar size={16} />
        Ver todos os movimentos de {monthLabel(month)}
        <ArrowUpRight size={15} />
      </button>

      {s.spentFromGoals > 0 && (
        <div className="note">
          <PiggyBank size={17} weight="fill" />
          Mais {eur(s.spentFromGoals)} € saíram de objetivos, pagos pelo que juntaste antes, por isso não contam neste mês.
        </div>
      )}

      {rate !== null && rate >= 20 && (
        <div className="note">
          <PiggyBank size={17} weight="fill" />
          Guardaste {rate}% do que entrou este mês.
        </div>
      )}
    </>
  );
}
