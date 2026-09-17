package org.beFree.media;

import dev.langchain4j.data.image.Image;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.beFree.assistant.VisionReader;
import org.jboss.logging.Logger;

import java.util.Base64;

/**
 * A photo, a voice note or a PDF turned into the text the assistant reads.
 * WhatsApp and the in-app chat both come through here, so a receipt behaves the
 * same whichever way it arrives, and the wording of a failure lives in one place.
 */
@ApplicationScoped
public class MediaIngest {

    private static final Logger LOG = Logger.getLogger(MediaIngest.class);

    // The model answers in the language you write in; these do not go through it,
    // so they are written in the one language this app is used in
    public static final String UNSUPPORTED = "Leio texto, fotos, mensagens de voz e PDFs.";
    public static final String IMAGE_UNREADABLE = "Não consegui ler essa imagem. Tenta uma foto mais nítida, ou escreve o valor.";
    public static final String AUDIO_NOT_CONFIGURED = "As mensagens de voz precisam de um serviço de transcrição, que ainda não está configurado. Por agora, escreve.";
    public static final String AUDIO_UNREADABLE = "Não percebi essa mensagem de voz. Tenta outra vez, ou escreve.";
    public static final String PDF_UNREADABLE = "Não consegui abrir esse PDF. Se tiver palavra-passe, tira-a e envia outra vez.";
    public static final String PDF_NO_TEXT = "Esse PDF não tem texto legível, deve ser digitalizado. Manda fotos das páginas e eu leio.";

    /** Either text to hand the assistant, or the sentence to send back instead. */
    public sealed interface Result {

        record Framed(String text) implements Result {
        }

        record Rejected(String reason) implements Result {
        }
    }

    @Inject
    VisionReader vision;

    @Inject
    Transcriber transcriber;

    @Inject
    DocumentReader documents;

    /** Lets callers skip a download they would only throw away. */
    public boolean canTranscribe() {
        return transcriber.enabled();
    }

    public Kind kindOf(String mimeType, String filename) {
        String m = mimeType == null ? "" : mimeType.toLowerCase();
        String f = filename == null ? "" : filename.toLowerCase();
        if (m.startsWith("image/")) return Kind.IMAGE;
        if (m.startsWith("audio/") || m.startsWith("video/webm")) return Kind.AUDIO;
        if (m.startsWith("application/pdf") || f.endsWith(".pdf")) return Kind.DOCUMENT;
        return Kind.OTHER;
    }

    public Result read(byte[] bytes, String mimeType, String filename, String caption, String ref) {
        return switch (kindOf(mimeType, filename)) {
            case IMAGE -> image(bytes, mimeType, caption, ref);
            case AUDIO -> audio(bytes, mimeType, ref);
            case DOCUMENT -> document(bytes, filename, caption, ref);
            case OTHER -> new Result.Rejected(UNSUPPORTED);
        };
    }

    public Result image(byte[] bytes, String mimeType, String caption, String ref) {
        String text = caption == null ? "" : caption.trim();
        String extraction;
        try {
            extraction = vision.extract(Image.builder()
                    .base64Data(Base64.getEncoder().encodeToString(bytes))
                    .mimeType(mimeType == null ? "image/jpeg" : mimeType)
                    .build(), text);
        } catch (Exception e) {
            LOG.warnf(e, "Could not read image %s", ref);
            return new Result.Rejected(IMAGE_UNREADABLE);
        }
        if (extraction == null || extraction.isBlank()) {
            LOG.warnf("Vision model returned no text for image %s", ref);
            return new Result.Rejected(IMAGE_UNREADABLE);
        }
        LOG.infof("Vision extraction for %s: %s", ref, extraction.replace('\n', '|'));
        return new Result.Framed("[The user sent an image"
                + (text.isEmpty() ? "" : " with the caption: \"" + text + "\"") + "]\n"
                + "Extracted from the image:\n" + extraction);
    }

    public Result audio(byte[] bytes, String mimeType, String ref) {
        if (!transcriber.enabled()) {
            return new Result.Rejected(AUDIO_NOT_CONFIGURED);
        }
        String transcript;
        try {
            transcript = transcriber.transcribe(bytes, mimeType);
        } catch (Exception e) {
            LOG.warnf(e, "Could not transcribe audio %s", ref);
            return new Result.Rejected(AUDIO_UNREADABLE);
        }
        if (transcript == null || transcript.isBlank()) {
            return new Result.Rejected(AUDIO_UNREADABLE);
        }
        LOG.infof("Transcript for %s: %s", ref, transcript);
        return new Result.Framed("[Voice message transcript]\n" + transcript.trim());
    }

    public Result document(byte[] bytes, String filename, String caption, String ref) {
        String name = filename == null || filename.isBlank() ? "document.pdf" : filename;
        String text = caption == null ? "" : caption.trim();
        DocumentReader.Extracted extracted;
        try {
            extracted = documents.extract(bytes);
        } catch (Exception e) {
            LOG.warnf(e, "Could not read PDF %s (%s)", ref, name);
            return new Result.Rejected(PDF_UNREADABLE);
        }
        if (extracted.isBlank()) {
            return new Result.Rejected(PDF_NO_TEXT);
        }
        LOG.infof("PDF %s: %s, %d pages, %d chars%s", ref, name, extracted.pages(),
                extracted.text().length(), extracted.truncated() ? " (truncated)" : "");
        return new Result.Framed("[The user sent a PDF document \"" + name + "\" (" + extracted.pages() + " pages"
                + (extracted.truncated() ? ", text truncated" : "") + ")"
                + (text.isEmpty() ? "" : " with the caption: \"" + text + "\"") + "]\n"
                + "Extracted text:\n" + extracted.text());
    }

    public enum Kind {
        IMAGE, AUDIO, DOCUMENT, OTHER
    }
}
