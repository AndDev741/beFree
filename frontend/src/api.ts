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
  saved: number;
  remaining: number;
  percent: number | null;
  reached: boolean;
  monthsLeft: number | null;
  perMonth: number | null;
}

export interface Summary {
  month: string;
  income: number;
  spent: number;
  reserved: number;
  remaining: number;
  byCategory: { category: string; amount: number }[];
  budgets: BudgetView[];
  goals: GoalView[];
}

export interface Session {
  username: string;
  assistantEnabled: boolean;
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

  goals: () => request<GoalView[]>('/api/goals'),

  createGoal: (body: { name: string; target: number; targetDate?: string | null; description?: string | null }) =>
    request<GoalView>('/api/goals', { method: 'POST', body: JSON.stringify(body) }),

  updateGoal: (
    id: number,
    body: Partial<{
      name: string;
      target: number;
      targetDate: string;
      description: string;
      clearTargetDate: boolean;
    }>,
  ) => request<GoalView>(`/api/goals/${id}`, { method: 'PATCH', body: JSON.stringify(body) }),

  contribute: (id: number, body: { amount: number; occurredOn?: string; note?: string }) =>
    request<GoalView>(`/api/goals/${id}/contributions`, { method: 'POST', body: JSON.stringify(body) }),

  deleteGoal: (id: number) => request<void>(`/api/goals/${id}`, { method: 'DELETE' }),

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
