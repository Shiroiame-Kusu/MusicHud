package indi.etern.musichud.beans.music;

import com.fasterxml.jackson.annotation.JsonCreator;
import indi.etern.musichud.client.music.decoder.AudioDecoder;
import indi.etern.musichud.client.music.decoder.AudioFormatDetector;
import indi.etern.musichud.client.music.decoder.FLACStreamDecoder;
import indi.etern.musichud.client.music.decoder.MP3StreamDecoder;
import lombok.SneakyThrows;

import java.io.BufferedInputStream;
import java.util.Arrays;

public enum FormatType {
    FLAC {
        @Override
        @SneakyThrows
        public AudioDecoder newDecoder(BufferedInputStream inputStream) {
            return new FLACStreamDecoder(inputStream);
        }
    },
    MP3 {
        @Override
        public AudioDecoder newDecoder(BufferedInputStream inputStream) {
            return new MP3StreamDecoder(inputStream);
        }
    },
    AUTO {
        @Override
        @SneakyThrows
        public AudioDecoder newDecoder(BufferedInputStream inputStream) {
            return AudioFormatDetector.detectFormat(inputStream).newDecoder(inputStream);
        }
    };
    @JsonCreator
    public static FormatType fromString(String value) {
        if (value == null) return null;
        return Arrays.stream(FormatType.values())
                .filter(e -> e.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow();
    }

    public abstract AudioDecoder newDecoder(BufferedInputStream inputStream);
}
