package org.beFree.whatsapp;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.beFree.assistant.FinanceAssistant;
import org.beFree.media.Transcriber;
import org.beFree.whatsapp.WebhookPayload.InboundMessage;
import org.beFree.whatsapp.WebhookPayload.Media;
import org.beFree.whatsapp.WhatsAppApi.SendTextRequest;
import org.beFree.whatsapp.WhatsAppMedia.Downloaded;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;


import static org.beFree.whatsapp.WhatsAppFixtures.ME;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestProfile(AssistantOnProfile.class)
class WhatsAppAudioFlowTest {


    @InjectMock
    FinanceAssistant assistant;

    @InjectMock
    Transcriber transcriber;

    @InjectMock
    WhatsAppMedia media;

    @InjectMock
    @RestClient
    WhatsAppApi whatsapp;

    @Inject
    WhatsAppService service;

    private static InboundMessage voice(String wamid, String mediaId) {
        return new InboundMessage(ME, wamid, WhatsAppFixtures.TS_2026_09_01, "audio",
                null, null, null, new Media(mediaId, "audio/ogg; codecs=opus", null, null));
    }

    @Test
    void voiceNoteIsTranscribedThenHandledByTheAssistant() throws Exception {
        when(transcriber.enabled()).thenReturn(true);
        when(media.download("aud-1")).thenReturn(new Downloaded(new byte[]{1, 2, 3}, "audio/ogg; codecs=opus"));
        when(transcriber.transcribe(any(), anyString())).thenReturn("gastei doze euros e cinquenta no almoço");
        when(assistant.chat(eq(ME), anyString(), anyString())).thenReturn("✅ #9 12.50 EUR almoço");

        service.handle(voice("wamid.AUD1", "aud-1"));

        var toAssistant = ArgumentCaptor.forClass(String.class);
        verify(assistant).chat(eq(ME), anyString(), toAssistant.capture());
        assertEquals("[Voice message transcript]\ngastei doze euros e cinquenta no almoço", toAssistant.getValue());
        var reply = ArgumentCaptor.forClass(SendTextRequest.class);
        verify(whatsapp).sendMessage(any(), any(), reply.capture());
        assertEquals("✅ #9 12.50 EUR almoço", reply.getValue().text().body());
    }

    @Test
    void withoutATranscriptionKeyTheUserIsToldWhatIsMissing() {
        when(transcriber.enabled()).thenReturn(false);

        service.handle(voice("wamid.AUD2", "aud-2"));

        verifyNoInteractions(assistant, media);
        var reply = ArgumentCaptor.forClass(SendTextRequest.class);
        verify(whatsapp).sendMessage(any(), any(), reply.capture());
        assertTrue(reply.getValue().text().body().contains("transcription provider"));
    }
}
