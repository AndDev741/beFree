import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import {
  FileArrowUp, FilePdf, Image as ImageIcon, Microphone,
  PaperPlaneRight, Sparkle, Stop, Waveform,
} from '@phosphor-icons/react';
import { api } from './api';

interface Turn {
  who: 'me' | 'it';
  text: string;
  /** Set when the turn is a file the user sent, so it draws as an attachment. */
  file?: 'image' | 'pdf' | 'audio';
}

const HINTS = [
  'gastei 12,50 no almoço',
  'quanto gastei em comida este mês?',
  'põe 200 no fundo de emergência',
];

const ACCEPT = 'image/*,application/pdf,audio/*';

/** The thread survives a refresh; the model's own memory lives on the server. */
function restore(): Turn[] {
  try {
    const raw = sessionStorage.getItem('befree-thread');
    return raw ? (JSON.parse(raw) as Turn[]) : [];
  } catch {
    return [];
  }
}

function kindOf(type: string, name: string): Turn['file'] {
  if (type.startsWith('image/')) return 'image';
  if (type.startsWith('audio/') || type.startsWith('video/webm')) return 'audio';
  if (type === 'application/pdf' || name.toLowerCase().endsWith('.pdf')) return 'pdf';
  return undefined;
}

export function Assistant({ enabled, onChanged }: { enabled: boolean; onChanged: () => void }) {
  const [turns, setTurns] = useState<Turn[]>(restore);
  const [draft, setDraft] = useState('');
  const [busy, setBusy] = useState(false);
  const [recording, setRecording] = useState(false);
  const thread = useRef<HTMLDivElement>(null);
  const picker = useRef<HTMLInputElement>(null);
  const box = useRef<HTMLTextAreaElement>(null);
  const recorder = useRef<MediaRecorder | null>(null);

  // getUserMedia only exists on a secure origin, so over plain http on the LAN
  // there is no microphone to offer
  const canRecord = typeof navigator !== 'undefined'
    && !!navigator.mediaDevices?.getUserMedia
    && typeof MediaRecorder !== 'undefined';

  useEffect(() => {
    try {
      sessionStorage.setItem('befree-thread', JSON.stringify(turns.slice(-40)));
    } catch {
      // private mode: the thread just does not survive a refresh
    }
    thread.current?.scrollTo({ top: thread.current.scrollHeight, behavior: 'smooth' });
  }, [turns, busy]);

  useEffect(() => () => recorder.current?.stream.getTracks().forEach((t) => t.stop()), []);

  useEffect(() => {
    const el = box.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, 168)}px`;
  }, [draft]);

  function answered(reply: string) {
    setTurns((t) => [...t, { who: 'it', text: reply }]);
    // It may have written to the ledger, so the screens behind it are now stale
    onChanged();
  }

  function failed() {
    setTurns((t) => [...t, { who: 'it', text: 'Não consegui responder agora. Tenta outra vez daqui a pouco.' }]);
  }

  async function send(text: string) {
    setTurns((t) => [...t, { who: 'me', text }]);
    setDraft('');
    setBusy(true);
    try {
      answered((await api.chat(text)).reply);
    } catch {
      failed();
    } finally {
      setBusy(false);
    }
  }

  async function sendFile(file: Blob, name: string, caption: string) {
    const kind = kindOf(file.type, name);
    setTurns((t) => [...t, { who: 'me', text: caption || name, file: kind }]);
    setDraft('');
    setBusy(true);
    try {
      answered((await api.chatMedia(file, name, caption)).reply);
    } catch {
      failed();
    } finally {
      setBusy(false);
    }
  }

  async function toggleRecording() {
    if (recording) {
      recorder.current?.stop();
      return;
    }
    let stream: MediaStream;
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    } catch {
      setTurns((t) => [...t, { who: 'it', text: 'Não consegui aceder ao microfone. Verifica as permissões do browser.' }]);
      return;
    }
    const type = MediaRecorder.isTypeSupported('audio/webm') ? 'audio/webm' : '';
    const rec = new MediaRecorder(stream, type ? { mimeType: type } : undefined);
    const chunks: Blob[] = [];
    rec.ondataavailable = (e) => e.data.size > 0 && chunks.push(e.data);
    rec.onstop = () => {
      stream.getTracks().forEach((t) => t.stop());
      setRecording(false);
      const blob = new Blob(chunks, { type: rec.mimeType || 'audio/webm' });
      if (blob.size > 0) void sendFile(blob, 'mensagem-de-voz.webm', draft.trim());
    };
    recorder.current = rec;
    rec.start();
    setRecording(true);
  }

  function submit(e: FormEvent) {
    e.preventDefault();
    const text = draft.trim();
    if (text && !busy) void send(text);
  }

  // Enter sends, Shift+Enter breaks the line, like every chat you already use
  function keyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      const text = draft.trim();
      if (text && !busy && !recording) void send(text);
    }
  }

  if (!enabled) {
    return (
      <div className="thread">
        <p className="help" style={{ margin: 'auto', textAlign: 'center', maxWidth: '30ch' }}>
          O assistente não está configurado neste servidor. Falta a chave do modelo.
        </p>
      </div>
    );
  }

  return (
    <>
      <div className="thread" ref={thread}>
        {turns.length === 0 && (
          <div className="hints">
            <p className="help" style={{ margin: '0 0 10px' }}>
              Escreve como escreverias no WhatsApp. É a mesma conversa, e aceita foto, PDF e voz.
            </p>
            {HINTS.map((h) => (
              <button key={h} className="ctl" onClick={() => void send(h)}>
                <Sparkle size={13} weight="fill" />
                {h}
              </button>
            ))}
          </div>
        )}
        {turns.map((t, i) => (
          <div key={i} className={`bub ${t.who}`}>
            {t.file && (
              <span className="attach">
                {t.file === 'image' ? <ImageIcon size={14} weight="fill" />
                  : t.file === 'pdf' ? <FilePdf size={14} weight="fill" />
                  : <Waveform size={14} weight="fill" />}
              </span>
            )}
            {t.text}
          </div>
        ))}
        {busy && (
          <div className="bub it typing" aria-live="polite">
            <i /><i /><i />
          </div>
        )}
      </div>

      <form className="composer" onSubmit={submit}>
        <input
          ref={picker}
          type="file"
          accept={ACCEPT}
          hidden
          onChange={(e) => {
            const file = e.target.files?.[0];
            if (file) void sendFile(file, file.name, draft.trim());
            e.target.value = '';
          }}
        />
        <button
          type="button"
          className="iconbtn"
          onClick={() => picker.current?.click()}
          disabled={busy || recording}
          aria-label="Anexar foto, PDF ou áudio"
          title="Foto, PDF ou áudio"
        >
          <FileArrowUp size={17} />
        </button>
        {canRecord && (
          <button
            type="button"
            className={recording ? 'iconbtn rec' : 'iconbtn'}
            onClick={() => void toggleRecording()}
            disabled={busy}
            aria-label={recording ? 'Parar e enviar' : 'Gravar mensagem de voz'}
            title={recording ? 'Parar e enviar' : 'Mensagem de voz'}
          >
            {recording ? <Stop size={16} weight="fill" /> : <Microphone size={17} />}
          </button>
        )}
        <textarea
          ref={box}
          className="input composerbox"
          rows={1}
          placeholder={recording ? 'A gravar…' : 'Escreve aqui… (Shift+Enter quebra a linha)'}
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={keyDown}
          disabled={busy || recording}
        />
        <button className="btn" type="submit" disabled={busy || recording || !draft.trim()} aria-label="Enviar">
          <PaperPlaneRight size={15} weight="fill" />
        </button>
      </form>
    </>
  );
}
