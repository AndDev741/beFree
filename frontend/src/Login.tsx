import { useState, type FormEvent } from 'react';
import { CirclesThreePlus } from '@phosphor-icons/react';
import { api } from './api';

export function Login({ onSignedIn }: { onSignedIn: () => void }) {
  const [username, setUsername] = useState('andre');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.login(username, password);
      onSignedIn();
    } catch {
      setError('Utilizador ou palavra-passe errados.');
      setBusy(false);
    }
  }

  return (
    <div className="loginwrap">
      <form className="loginbox" onSubmit={submit}>
        <div className="logo" style={{ fontSize: 22, marginBottom: 4 }}>
          <CirclesThreePlus size={24} weight="fill" />
          beFree
        </div>
        <div className="field">
          <label htmlFor="u">Utilizador</label>
          <input id="u" className="input" autoComplete="username"
                 value={username} onChange={(e) => setUsername(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="p">Palavra-passe</label>
          <input id="p" className="input" type="password" autoComplete="current-password"
                 value={password} onChange={(e) => setPassword(e.target.value)} />
          {error && <span className="err">{error}</span>}
        </div>
        <button className="btn" type="submit" disabled={busy || !password}>
          {busy ? 'A entrar…' : 'Entrar'}
        </button>
      </form>
    </div>
  );
}
