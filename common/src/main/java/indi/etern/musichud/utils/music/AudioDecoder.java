package indi.etern.musichud.utils.music;

public interface AudioDecoder extends AutoCloseable {
    byte[] readChunk(int maxSize);
    int getFormat();
    int getSampleRate();
    void close();
}
