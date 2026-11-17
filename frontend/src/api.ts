/** Everything the app knows about the backend. One place, typed. */

export type TransactionType = 'EXPENSE' | 'INCOME';
export type Source = 'MANUAL' | 'WHATSAPP' | 'OPEN_BANKING';

export interface Transaction {
  id: number;
  amount: number;
  type: TransactionType;
  currency: string;
  occurredOn: string;
  description: string | null;
  category: string | null;
  /** Set when this expense was paid out of a goal instead of out of the month. */
  goal: string | null;
  goalId: number | null;
  source: Source;
  createdAt: string;
}

export interface Category {
  id: number;
  name: string;
}

export interface BudgetView {
  category: string;
  limitAmount: number;
  spent: number;
  remaining: number;
  percentUsed: number | null;
  overspent: boolean;
}

export interface GoalView {
  id: number;
  name: string;
  description: string | null;
  target: number;
  targetDate: string | null;
  /** What was already put by when the goal was created, outside any month's flow. */
  initial: number;
  saved: number;
  /** Already paid out of this jar. */
  spent: number;
  remaining: number;
  percent: number | null;
  reached: boolean;
  /** The day it was reached. Set once and kept, so spending it does not undo it. */
  achievedOn: string | null;
  /** Set once it is put away: out of the active goals, kept as a record. */
  archivedOn: string | null;
  monthsLeft: number | null;
  perMonth: number | null;
}

export type LoanStatus = 'OPEN' | 'SETTLED' | 'CLOSED';

export interface Repayment {
  id: number;
  amount: number;
  currency: string;
  occurredOn: string;
  note: string | null;
}

export interface LoanView {
  id: number;
  person: string;
  description: string | null;
  amount: number;
  currency: string;
  lentOn: string;
  /** The expense that paid it out, when it went through the ledger. */
  transactionId: number | null;
  transactionDescription: string | null;
  /** In the loan's own currency. */
  repaid: number;
  outstanding: number;
  percent: number | null;
  /** Money that came back in other currencies, as it came, never converted. */
  otherCurrencies: Record<string, number>;
  /** SETTLED is paid back in full; CLOSED is called done by hand. */
  status: LoanStatus;
  settledOn: string | null;
  closedOn: string | null;
  closingNote: string | null;
  repayments: Repayment[];
}

export interface Summary {
  month: string;
  income: number;
  spent: number;
  /** Paid out of goals, which is money earlier months already covered. */
  spentFromGoals: number;
  reserved: number;
  remaining: number;
  byCategory: { category: string; amount: number }[];
  budgets: BudgetView[];
  goals: GoalView[];
}

export interface Session {
  username: string;
  assistantEnabled: boolean;
  /** 1 means the calendar month; 25 means a month that runs 25th to 24th. */
  monthStartDay: number;
}

export interface Settings {
  /** Applies to any month without an exception of its own. */
  defaultStartDay: number;
  /** The day this month starts on, exception or default. */
  startDay: number;
  custom: boolean;
  month: string;
  from: string;
  to: string;
}

/** Thrown for any non-2xx so callers can branch on 401 without parsing text. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

/** Set once by the app shell: any 401 anywhere drops you back to the login screen. */
let onUnauthorized: () => void = () => {};
export const setUnauthorizedHandler = (fn: () => void) => {
  onUnauthorized = fn;
};

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const json = init.body !== undefined && !(init.body instanceof FormData);
  const res = await fetch(path, {
    credentials: 'include',
    headers: json ? { 'content-type': 'application/json' } : {},
    ...init,
  });
  if (!res.ok) {
    if (res.status === 401 && path !== '/api/session') onUnauthorized();
    let detail = res.statusText;
    try {
      const text = await res.text();
      if (text) detail = text.slice(0, 200);
    } catch {
      // keep the status text
    }
    throw new ApiError(res.status, detail);
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export const api = {
  session: () => request<Session>('/api/session'),

  async login(username: string, password: string): Promise<void> {
    const body = new URLSearchParams({ j_username: username, j_password: password });
    const res = await fetch('/api/login', {
      method: 'POST',
      credentials: 'include',
      headers: { 'content-type': 'application/x-www-form-urlencoded' },
      body,
    });
    if (!res.ok) throw new ApiError(res.status, 'Utilizador ou palavra-passe errados.');
  },

  logout: () => request<void>('/api/logout', { method: 'POST' }).catch(() => undefined),

  summary: (month?: string) =>
    request<Summary>(`/api/summary${month ? `?month=${month}` : ''}`),

  transactions: (month?: string) =>
    request<Transaction[]>(`/api/transactions${month ? `?month=${month}` : ''}`),

  createTransaction: (body: {
    amount: number;
    type?: TransactionType;
    description?: string | null;
    occurredOn?: string;
    categoryId?: number | null;
    goalId?: number | null;
  }) => request<Transaction>('/api/transactions', { method: 'POST', body: JSON.stringify(body) }),

  // A field left out keeps its value; clearCategory is how you take one off
  updateTransaction: (
    id: number,
    body: Partial<{
      amount: number;
      type: TransactionType;
      description: string;
      occurredOn: string;
      categoryId: number;
      clearCategory: boolean;
      goalId: number;
      clearGoal: boolean;
    }>,
  ) => request<Transaction>(`/api/transactions/${id}`, { method: 'PATCH', body: JSON.stringify(body) }),

  deleteTransaction: (id: number) =>
    request<void>(`/api/transactions/${id}`, { method: 'DELETE' }),

  categories: () => request<Category[]>('/api/categories'),

  createCategory: (name: string) =>
    request<Category>('/api/categories', { method: 'POST', body: JSON.stringify({ name }) }),

  budgets: (month?: string) =>
    request<BudgetView[]>(`/api/budgets${month ? `?month=${month}` : ''}`),

  setBudget: (category: string, limitAmount: number, month?: string) =>
    request<BudgetView[]>('/api/budgets', {
      method: 'PUT',
      body: JSON.stringify({ category, limitAmount, month }),
    }),

  removeBudget: (category: string, month?: string) =>
    request<void>(`/api/budgets/${encodeURIComponent(category)}${month ? `?month=${month}` : ''}`, {
      method: 'DELETE',
    }),

  /** The goals still in play. Archived ones only come back from archivedGoals. */
  goals: () => request<GoalView[]>('/api/goals'),

  archivedGoals: () => request<GoalView[]>('/api/goals?archived=true'),

  createGoal: (body: {
    name: string;
    target: number;
    targetDate?: string | null;
    description?: string | null;
    initial?: number;
  }) =>
    request<GoalView>('/api/goals', { method: 'POST', body: JSON.stringify(body) }),

  updateGoal: (
    id: number,
    body: Partial<{
      name: string;
      target: number;
      targetDate: string;
      description: string;
      clearTargetDate: boolean;
      initial: number;
      achieved: boolean;
    }>,
  ) => request<GoalView>(`/api/goals/${id}`, { method: 'PATCH', body: JSON.stringify(body) }),

  contribute: (id: number, body: { amount: number; occurredOn?: string; note?: string }) =>
    request<GoalView>(`/api/goals/${id}/contributions`, { method: 'POST', body: JSON.stringify(body) }),

  deleteGoal: (id: number) => request<void>(`/api/goals/${id}`, { method: 'DELETE' }),

  archiveGoal: (id: number) => request<GoalView>(`/api/goals/${id}/archive`, { method: 'PUT' }),

  unarchiveGoal: (id: number) => request<GoalView>(`/api/goals/${id}/archive`, { method: 'DELETE' }),

  /** Still owed. Paid back and closed ones only come back from finishedLoans. */
  loans: () => request<LoanView[]>('/api/loans'),

  finishedLoans: () => request<LoanView[]>('/api/loans?closed=true'),

  /** Expenses filed as loans that no loan tracks yet. */
  untrackedLoans: () => request<Transaction[]>('/api/loans/untracked'),

  createLoan: (body: {
    person: string;
    amount: number;
    currency?: string;
    lentOn?: string;
    description?: string | null;
    transactionId?: number | null;
  }) => request<LoanView>('/api/loans', { method: 'POST', body: JSON.stringify(body) }),

  updateLoan: (
    id: number,
    body: Partial<{
      person: string;
      amount: number;
      currency: string;
      lentOn: string;
      description: string;
      transactionId: number;
      clearTransaction: boolean;
    }>,
  ) => request<LoanView>(`/api/loans/${id}`, { method: 'PATCH', body: JSON.stringify(body) }),

  deleteLoan: (id: number) => request<void>(`/api/loans/${id}`, { method: 'DELETE' }),

  repayLoan: (id: number, body: { amount: number; currency?: string; occurredOn?: string; note?: string | null }) =>
    request<LoanView>(`/api/loans/${id}/repayments`, { method: 'POST', body: JSON.stringify(body) }),

  deleteRepayment: (id: number, repaymentId: number) =>
    request<LoanView>(`/api/loans/${id}/repayments/${repaymentId}`, { method: 'DELETE' }),

  closeLoan: (id: number, note?: string | null) =>
    request<LoanView>(`/api/loans/${id}/close`, { method: 'PUT', body: JSON.stringify({ note: note ?? null }) }),

  reopenLoan: (id: number) => request<LoanView>(`/api/loans/${id}/close`, { method: 'DELETE' }),

  settings: (month?: string) =>
    request<Settings>(`/api/settings${month ? `?month=${month}` : ''}`),

  /** Without a month this sets the default; with one it sets that month's exception. */
  setMonthStartDay: (monthStartDay: number, month?: string) =>
    request<Settings>(`/api/settings${month ? `?month=${month}` : ''}`, {
      method: 'PUT',
      body: JSON.stringify({ monthStartDay }),
    }),

  clearMonthStartDay: (month: string) =>
    request<Settings>(`/api/settings/months/${month}`, { method: 'DELETE' }),

  chat: (message: string) =>
    request<{ reply: string }>('/api/chat', { method: 'POST', body: JSON.stringify({ message }) }),

  /** A photo, a voice note or a PDF. Same pipeline the WhatsApp channel uses. */
  chatMedia: (file: Blob, filename: string, message?: string) => {
    const form = new FormData();
    form.append('file', file, filename);
    if (message) form.append('message', message);
    // No content-type here: the browser has to add the multipart boundary
    return request<{ reply: string }>('/api/chat/media', { method: 'POST', body: form });
  },
};

export const eur = (n: number) =>
  n.toLocaleString('pt-PT', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

const SYMBOLS: Record<string, string> = { EUR: '€', BRL: 'R$', USD: 'US$', GBP: '£' };

/** "12,50 €" for euros, "R$ 205,00" for reais: each currency written the way its people write it. */
export const money = (n: number, currency: string) => {
  const symbol = SYMBOLS[currency] ?? currency;
  return currency === 'EUR' ? `${eur(n)} ${symbol}` : `${symbol} ${eur(n)}`;
};

export const monthKey = (d = new Date()) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;

export const monthLabel = (key: string) => {
  const [y, m] = key.split('-').map(Number);
  return new Date(y, m - 1, 1).toLocaleDateString('pt-PT', { month: 'long', year: 'numeric' });
};

export const shiftMonth = (key: string, by: number) => {
  const [y, m] = key.split('-').map(Number);
  return monthKey(new Date(y, m - 1 + by, 1));
};

/**
 * The month you are living in. With a cycle starting on the 25th, the 26th is
 * already next month, which is the whole point of the setting.
 */
export const currentMonth = (startDay: number, today = new Date()) => {
  const key = monthKey(today);
  return startDay > 1 && today.getDate() >= startDay ? shiftMonth(key, 1) : key;
};

/**
 * A month runs from its own start to the next month's start, so its span
 * depends on two days and is computed by the server. This only formats it.
 */
export const spanLabel = (from: string, to: string) => {
  const day = (iso: string) =>
    new Date(iso + 'T00:00:00').toLocaleDateString('pt-PT', { day: 'numeric', month: 'short' });
  return `${day(from)} a ${day(to)}`;
};
