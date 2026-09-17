import { useCallback, useEffect, useState } from 'react';

interface Loaded<T> {
  data: T | null;
  error: string | null;
  loading: boolean;
  reload: () => void;
}

/**
 * Load once per key, expose the three states the UI actually draws, and give
 * callers a reload they can fire after a write. Nothing fancier is needed: the
 * whole app is one user reading their own month.
 */
export function useLoad<T>(load: () => Promise<T>, key: string): Loaded<T> {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [nonce, setNonce] = useState(0);

  // `load` is a fresh closure every render, so the key is what decides a refetch
  const run = useCallback(load, [key, nonce]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    let live = true;
    setLoading(true);
    run()
      .then((value) => {
        if (!live) return;
        setData(value);
        setError(null);
      })
      .catch((e: unknown) => {
        if (!live) return;
        setError(e instanceof Error ? e.message : 'Falha de rede.');
      })
      .finally(() => {
        if (live) setLoading(false);
      });
    return () => {
      live = false;
    };
  }, [run]);

  return { data, error, loading, reload: () => setNonce((n) => n + 1) };
}

/** Light by default, dark when the system says so, and a manual override that sticks. */
export function useTheme() {
  const [theme, setTheme] = useState<'light' | 'dark' | null>(() => {
    try {
      const saved = localStorage.getItem('befree-theme');
      return saved === 'light' || saved === 'dark' ? saved : null;
    } catch {
      return null;
    }
  });

  useEffect(() => {
    const root = document.documentElement;
    if (theme) root.setAttribute('data-theme', theme);
    else root.removeAttribute('data-theme');
    try {
      if (theme) localStorage.setItem('befree-theme', theme);
      else localStorage.removeItem('befree-theme');
    } catch {
      // private mode: the toggle still works for this tab
    }
  }, [theme]);

  const isDark = theme
    ? theme === 'dark'
    : typeof matchMedia === 'function' && matchMedia('(prefers-color-scheme: dark)').matches;

  return { isDark, toggle: () => setTheme(isDark ? 'light' : 'dark') };
}
