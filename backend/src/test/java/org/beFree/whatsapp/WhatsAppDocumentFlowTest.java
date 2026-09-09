package org.beFree.whatsapp;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.beFree.assistant.FinanceAssistant;
import org.beFree.media.DocumentReader;
import org.beFree.media.DocumentReader.Extracted;
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
class WhatsAppDocumentFlowTest {


    @InjectMock
    FinanceAssistant assistant;

    @InjectMock
    DocumentReader documents;

    @InjectMock
    WhatsAppMedia media;

    @InjectMock
    @RestClient
    WhatsAppApi whatsapp;

    @Inject
    WhatsAppService service;

    private static InboundMessage doc(String wamid, String mediaId, String mime, String name) {
        return new InboundMessage(ME, wamid, WhatsAppFixtures.TS_2026_09_01, "document",
                null, null, new Media(mediaId, mime, null, name), null);
    }

    @Test
    void pdfTextReachesTheAssistantWithDocumentFraming() throws Exception {
        when(media.download("pdf-1")).thenReturn(new Downloaded(new byte[]{1}, "application/pdf"));
        when(documents.extract(any())).thenReturn(new Extracted("01-09 LIDL -50.00\n02-09 GALP -12.00", 2, false));
        when(assistant.chat(eq(ME), anyString(), anyString())).thenReturn("Encontrei 2 movimentos (62.00 EUR). Importar?");

        service.handle(doc("wamid.PDF1", "pdf-1", "application/pdf", "extrato.pdf"));

        var toAssistant = ArgumentCaptor.forClass(String.class);
        verify(assistant).chat(eq(ME), anyString(), toAssistant.capture());
        assertTrue(toAssistant.getValue().startsWith("[The user sent a PDF document \"extrato.pdf\" (2 pages)]"), toAssistant.getValue());
        assertTrue(toAssistant.getValue().contains("01-09 LIDL -50.00"));
        var reply = ArgumentCaptor.forClass(SendTextRequest.class);
        verify(whatsapp).sendMessage(any(), any(), reply.capture());
        assertEquals("Encontrei 2 movimentos (62.00 EUR). Importar?", reply.getValue().text().body());
    }

    @Test
    void scannedPdfIsExplainedNotGuessed() throws Exception {
        when(media.download("pdf-2")).thenReturn(new Downloaded(new byte[]{1}, "application/pdf"));
        when(documents.extract(any())).thenReturn(new Extracted("", 3, false));

        service.handle(doc("wamid.PDF2", "pdf-2", "application/pdf", "scan.pdf"));

        verifyNoInteractions(assistant);
        var reply = ArgumentCaptor.forClass(SendTextRequest.class);
        verify(whatsapp).sendMessage(any(), any(), reply.capture());
        assertTrue(reply.getValue().text().body().contains("no readable text"));
    }

    @Test
    void nonPdfDocumentsAreDeclinedWithoutDownloading() {
        service.handle(doc("wamid.DOCX1", "docx-1", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "notas.docx"));

        verifyNoInteractions(assistant, media, documents);
        var reply = ArgumentCaptor.forClass(SendTextRequest.class);
        verify(whatsapp).sendMessage(any(), any(), reply.capture());
        assertTrue(reply.getValue().text().body().startsWith("I can only read PDF documents"));
    }
}
