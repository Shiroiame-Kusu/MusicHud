package indi.etern.musichud.client.music.decoder;

public interface AudioDecoder extends AutoCloseable {
    byte[] readChunk(int maxSize);
    int getFormat();
    int getSampleRate();
    void close();
}
