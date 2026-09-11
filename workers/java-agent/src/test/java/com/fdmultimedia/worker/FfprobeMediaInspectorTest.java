package com.fdmultimedia.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class FfprobeMediaInspectorTest {

    private final FfprobeMediaInspector inspector = new FfprobeMediaInspector("ffprobe", Duration.ofSeconds(1));

    @Test
    void buildsCommandWithoutShell() {
        Path malicious = Path.of("video.mp4; touch owned");

        List<String> command = inspector.command(malicious);

        assertEquals("ffprobe", command.get(0));
        assertTrue(command.contains("-show_streams"));
        assertEquals(malicious.toString(), command.get(command.size() - 1));
        assertTrue(command.stream().noneMatch(argument -> argument.equals("sh") || argument.equals("cmd") || argument.equals("powershell")));
    }

    @Test
    void parsesVideoAndAudioMetadata() throws Exception {
        InspectionMetadata metadata = inspector.parse("""
                {
                  "streams": [
                    {"codec_type":"video","codec_name":"h264","width":1920,"height":1080,"avg_frame_rate":"30000/1001","duration":"12.345","disposition":{"attached_pic":0}},
                    {"codec_type":"audio","codec_name":"aac","duration":"12.000"}
                  ],
                  "format": {"duration":"12.345","format_name":"mov,mp4,m4a,3gp,3g2,mj2","bit_rate":"800000"}
                }
                """);

        assertEquals(12_345L, metadata.durationMs());
        assertEquals(1920, metadata.width());
        assertEquals(1080, metadata.height());
        assertEquals("h264", metadata.videoCodec());
        assertEquals("aac", metadata.audioCodec());
        assertEquals(new BigDecimal("29.970"), metadata.frameRate());
        assertEquals(800_000L, metadata.bitrate());
        assertEquals(true, metadata.hasVideo());
        assertEquals(true, metadata.hasAudio());
    }

    @Test
    void ignoresAttachedPictureForPrimaryVideoAndAllowsAudioOnly() throws Exception {
        InspectionMetadata metadata = inspector.parse("""
                {
                  "streams": [
                    {"codec_type":"video","codec_name":"mjpeg","width":600,"height":600,"disposition":{"attached_pic":1}},
                    {"codec_type":"audio","codec_name":"mp3","duration":"3.5"}
                  ],
                  "format": {"format_name":"mp3"}
                }
                """);

        assertNull(metadata.width());
        assertNull(metadata.videoCodec());
        assertEquals("mp3", metadata.audioCodec());
        assertEquals(3_500L, metadata.durationMs());
        assertEquals(false, metadata.hasVideo());
        assertEquals(true, metadata.hasAudio());
    }

    @Test
    void handlesMalformedFrameRateAndInvalidOutput() throws Exception {
        InspectionMetadata metadata = inspector.parse("""
                {"streams":[{"codec_type":"video","codec_name":"h264","avg_frame_rate":"0/0"}],"format":{}}
                """);

        assertNull(metadata.frameRate());
        assertThrows(ImportFailureException.class, () -> inspector.parse("{not json"));
        assertThrows(ImportFailureException.class, () -> inspector.parse("""
                {"streams":[],"format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2"}}
                """));
    }
}
