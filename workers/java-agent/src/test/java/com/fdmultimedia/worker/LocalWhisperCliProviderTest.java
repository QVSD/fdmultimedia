package com.fdmultimedia.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocalWhisperCliProviderTest {

    @Test
    void buildsControlledWhisperCommandWithoutShell() {
        LocalWhisperCliProvider provider = new LocalWhisperCliProvider("whisper", "base", Duration.ofSeconds(1));
        Path audio = Path.of("audio.wav; rm -rf nope");
        Path output = Path.of("out dir");

        List<String> command = provider.command(audio, output);

        assertEquals("whisper", command.get(0));
        assertEquals(audio.toString(), command.get(1));
        assertEquals("base", command.get(command.indexOf("--model") + 1));
        assertEquals("json", command.get(command.indexOf("--output_format") + 1));
        assertEquals(output.toString(), command.get(command.indexOf("--output_dir") + 1));
        assertTrue(command.stream().noneMatch(argument -> argument.equals("sh") || argument.equals("cmd") || argument.equals("powershell")));
    }

    @Test
    void parsesStructuredWhisperJson() throws Exception {
        LocalWhisperCliProvider provider = new LocalWhisperCliProvider("whisper", "base", Duration.ofSeconds(1));

        TranscriptionResult result = provider.parse("""
                {
                  "language": "ro",
                  "segments": [
                    { "start": 0.0, "end": 1.5, "text": " Bun venit " },
                    { "start": 1.5, "end": 3.0, "text": " astazi discutam " }
                  ]
                }
                """, authorization(10_000, 10, 100, 1_000));

        assertEquals("ro", result.detectedLanguage());
        assertEquals(2, result.segments().size());
        assertEquals(1500, result.segments().get(0).endMs());
        assertEquals("Bun venit", result.segments().get(0).text());
    }

    @Test
    void rejectsMalformedOrOversizedProviderOutput() {
        LocalWhisperCliProvider provider = new LocalWhisperCliProvider("whisper", "base", Duration.ofSeconds(1));

        assertThrows(ImportFailureException.class, () -> provider.parse("{\"segments\": \"nope\"}", authorization(10_000, 10, 100, 1_000)));
        ImportFailureException ex = assertThrows(
                ImportFailureException.class,
                () -> provider.parse("""
                        {"segments":[{"start":0,"end":1,"text":"this text is too long"}]}
                        """, authorization(10_000, 10, 4, 1_000)));
        assertEquals("TRANSCRIPTION_OUTPUT_TOO_LARGE", ex.code());
        assertTrue(ex.terminal());
        assertFalse(ex.getMessage().isBlank());
    }

    private TranscriptionAuthorization authorization(long durationMs, int maxSegments, int maxSegmentText, int maxTotalText) {
        return new TranscriptionAuthorization(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "http://minio/source",
                1_000_000,
                5,
                20,
                durationMs,
                "LOCAL_WHISPER_CLI",
                "base",
                maxSegments,
                maxSegmentText,
                maxTotalText);
    }
}
