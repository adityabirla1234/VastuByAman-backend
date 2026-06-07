package com.vastu.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Thin wrapper around the Telegram Bot HTTP API.
 *
 * Every public method is annotated with {@link Retryable} so transient
 * network failures (5xx, timeouts) are retried with exponential back-off
 * before the exception bubbles up to {@link com.panditaman.consultation.service.queue.TelegramDispatchQueue}.
 *
 * A second layer of retry / back-off lives in the dispatch queue itself,
 * so failures that survive all {@code @Retryable} attempts are still
 * re-attempted after a delay rather than being dropped immediately.
 *
 * Rate limits
 * ───────────
 * Telegram allows ~30 messages/second to different chats and ~1 message/second
 * to the same chat. The dispatch queue enforces a 1 s inter-send delay, so we
 * never breach the per-chat ceiling in normal operation.
 *
 * Timeouts
 * ────────
 * ConnectTimeout = 5 s   — fail fast if Telegram is unreachable
 * ReadTimeout    = 30 s  — allow enough time for multipart document uploads
 */
@Slf4j
@Service
public class TelegramApiService {

    private static final String TELEGRAM_API_BASE = "https://api.telegram.org/bot";

    @Value("${telegram.bot.token}")
    private String botToken;

    @Value("${telegram.bot.chat-id}")
    private String chatId;

    private final RestTemplate restTemplate;

    public TelegramApiService() {
        this.restTemplate = buildRestTemplate();
    }

    // ── Public API ───────────────────────────────────────────────

    /**
     * Sends the text message (and any file attachments) for a notification.
     * Attachments are sent one-by-one as separate Telegram documents.
     */
    public void sendTextMessage(String text) {
        doSendTextMessage(text);
    }

    public void sendDocument(Path filePath, String caption) {
        doSendDocument(filePath, caption);
    }

    // ── Retryable internals ──────────────────────────────────────

    /**
     * Sends a plain MarkdownV2 message.
     * Retried up to {@code telegram.retry.max-attempts} times with exponential
     * back-off on any exception except 4xx client errors (wrong token / chat-id),
     * which are permanent and must not be retried.
     */
    @Retryable(
        retryFor   = { Exception.class },
        noRetryFor = { HttpClientErrorException.class },
        maxAttemptsExpression = "${telegram.retry.max-attempts:5}",
        backoff = @Backoff(
            delayExpression      = "${telegram.retry.initial-delay-ms:2000}",
            multiplierExpression = "${telegram.retry.multiplier:2.0}",
            maxDelayExpression   = "${telegram.retry.max-delay-ms:60000}"
        )
    )
    protected void doSendTextMessage(String text) {
        String url = TELEGRAM_API_BASE + botToken + "/sendMessage";

        Map<String, Object> body = new HashMap<>();
        body.put("chat_id", chatId);
        body.put("text", escapeMarkdownV2(text));
        body.put("parse_mode", "MarkdownV2");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.postForEntity(
                url, new HttpEntity<>(body, headers), String.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Telegram sendMessage failed: "
                    + response.getStatusCode() + " – " + response.getBody());
        }
        log.debug("[TelegramApi] sendMessage OK");
    }

    /**
     * Sends a file as a Telegram document.
     * Same retry policy as {@link #doSendTextMessage}.
     */
    @Retryable(
        retryFor   = { Exception.class },
        noRetryFor = { HttpClientErrorException.class },
        maxAttemptsExpression = "${telegram.retry.max-attempts:5}",
        backoff = @Backoff(
            delayExpression      = "${telegram.retry.initial-delay-ms:2000}",
            multiplierExpression = "${telegram.retry.multiplier:2.0}",
            maxDelayExpression   = "${telegram.retry.max-delay-ms:60000}"
        )
    )
    protected void doSendDocument(Path filePath, String caption) {
        String url = TELEGRAM_API_BASE + botToken + "/sendDocument";

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("chat_id", chatId);
        body.add("document", new FileSystemResource(filePath));
        if (caption != null && !caption.isBlank()) {
            body.add("caption", caption);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> response = restTemplate.postForEntity(
                url, new HttpEntity<>(body, headers), String.class);

        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new RuntimeException("Telegram sendDocument failed: "
                    + response.getStatusCode() + " – " + response.getBody());
        }
        log.debug("[TelegramApi] sendDocument OK – file={}", filePath.getFileName());
    }

    // ── Helpers ──────────────────────────────────────────────────

    /**
     * Escapes all special characters required by the Telegram MarkdownV2 format.
     * Must be called exactly once, immediately before the HTTP call.
     */
    public static String escapeMarkdownV2(String text) {
        if (text == null) return "";
        return text
            .replace("\\", "\\\\")
            .replace("_",  "\\_")
            .replace("*",  "\\*")
            .replace("[",  "\\[")
            .replace("]",  "\\]")
            .replace("(",  "\\(")
            .replace(")",  "\\)")
            .replace("~",  "\\~")
            .replace("`",  "\\`")
            .replace(">",  "\\>")
            .replace("#",  "\\#")
            .replace("+",  "\\+")
            .replace("-",  "\\-")
            .replace("=",  "\\=")
            .replace("|",  "\\|")
            .replace("{",  "\\{")
            .replace("}",  "\\}")
            .replace(".",  "\\.")
            .replace("!",  "\\!");
    }

    private RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);   // 5 s — fail fast on unreachable host
        factory.setReadTimeout(30_000);      // 30 s — allow time for document uploads
        return new RestTemplate(factory);
    }
}
