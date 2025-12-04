package indi.etern.musichud.client.music;

import indi.etern.musichud.MusicHud;
import indi.etern.musichud.beans.music.FormatType;
import indi.etern.musichud.client.music.decoder.AudioDecoder;
import indi.etern.musichud.client.music.decoder.AudioFormatDetector;
import lombok.Getter;
import lombok.SneakyThrows;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import org.apache.logging.log4j.Logger;
import org.lwjgl.openal.AL10;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class StreamAudioPlayer {
    private static final int BUFFER_COUNT = 4;
    private static final int BUFFER_SIZE = 65536;
    private static final Logger LOGGER = MusicHud.getLogger(StreamAudioPlayer.class);
    private static volatile StreamAudioPlayer instance = null;

    private final int[] buffers = new int[BUFFER_COUNT];
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final Minecraft minecraft = Minecraft.getInstance();
    private final AtomicReference<Status> status = new AtomicReference<>(Status.IDLE);
    private final int maxRetries = 5;
    private final int retryDelayMs = 0;
    private final int retryDelayDelta = 1000;
    private final BlockingQueue<byte[]> audioBuffer = new LinkedBlockingQueue<>(30); // 最大30个数据块的缓冲区

    @Getter
    private final Set<Consumer<Status>> statusChangeListener = new HashSet<>();
    private int source = 0;
    private float lastVolume;
    private Future<?> playingFuture;
    private Future<?> downloadFuture;
    private volatile boolean shouldContinuePlaying = false;
    private volatile boolean shouldContinueDownloading = false;
    private volatile AudioDecoder currentDecoder;
    private volatile long bytesPerSecond = 0;
    private final AtomicLong totalBufferedBytes = new AtomicLong(0);
    private final AtomicLong totalPlayedBytes = new AtomicLong(0);
    private volatile long skipBytesOnRetry = 0;
    private volatile boolean isBuffering = false;
    private volatile LocalDateTime currentStartTime;

    public static StreamAudioPlayer getInstance() {
        if (instance == null) {
            synchronized (StreamAudioPlayer.class) {
                if (instance == null) {
                    instance = new StreamAudioPlayer();
                }
            }
        }
        return instance;
    }

    private static AudioDecoder loadAudioDecoder(String urlString, FormatType formatType) throws URISyntaxException, IOException {
        URL url = new URI(urlString).toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        InputStream inputStream = connection.getInputStream();
        BufferedInputStream bufferedStream = new BufferedInputStream(inputStream, 8192);

        FormatType detectedFormatType = AudioFormatDetector.detectFormat(bufferedStream);
        if (detectedFormatType != formatType) {
            LOGGER.warn("Detected format type is not equals to resource format type, using detected");
        }

        return detectedFormatType.newDecoder(bufferedStream);
    }

    public Status getStatus() {
        return status.get();
    }

    private void setStatus(Status status) {
        this.status.set(status);
        statusChangeListener.forEach(c -> c.accept(status));
    }

    public CompletableFuture<LocalDateTime> playAsyncFromUrl(String urlString, FormatType formatType, LocalDateTime startTime) {
        synchronized (StreamAudioPlayer.class) {
            try {
                stop(); // 先停止之前的播放

                source = AL10.alGenSources();
                checkALError("alGenSources");

                for (int i = 0; i < BUFFER_COUNT; i++) {
                    buffers[i] = AL10.alGenBuffers();
                    checkALError("alGenBuffers");
                }

                // 配置为非空间播放
                AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
                AL10.alSource3f(source, AL10.AL_POSITION, 0, 0, 0);
                AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 0);
                checkALError("source configuration");
                lastVolume = 1;

                initialized.set(true);
            } catch (Exception e) {
                cleanup();
                return CompletableFuture.failedFuture(e);
            }
        }

        // 清空缓冲区
        audioBuffer.clear();
        totalBufferedBytes.set(0);
        totalPlayedBytes.set(0);
        skipBytesOnRetry = 0;
        shouldContinuePlaying = true;
        shouldContinueDownloading = true;
        currentStartTime = startTime;

        CompletableFuture<LocalDateTime> startPlayingFuture = new CompletableFuture<>();

        // 启动下载任务（使用虚拟线程池）
        downloadFuture = MusicHud.EXECUTOR.submit(() -> {
            try {
                downloadAudioWithRetry(urlString, formatType, startTime, startPlayingFuture);
            } catch (Exception e) {
                LOGGER.error("Download thread error", e);
                setStatus(Status.ERROR);
            }
        });

        // 启动播放任务（使用虚拟线程池）
        playingFuture = MusicHud.EXECUTOR.submit(() -> {
            try {
                playFromBuffer(startPlayingFuture);
            } catch (Exception e) {
                LOGGER.error("Play thread error", e);
                if (!startPlayingFuture.isDone()) {
                    startPlayingFuture.completeExceptionally(e);
                }
            }
        });

        return startPlayingFuture;
    }

    private void downloadAudioWithRetry(String urlString, FormatType formatType, LocalDateTime startTime,
                                        CompletableFuture<LocalDateTime> startFuture) {
        int localRetryCount = 0;

        while (shouldContinueDownloading && localRetryCount < maxRetries) {
            try {
                LOGGER.debug("Starting audio download (attempt {})", localRetryCount + 1);

                AudioDecoder decoder = loadAudioDecoder(urlString, formatType);
                currentDecoder = decoder;

                // 如果是第一次下载，或者重试时需要跳过已播放的部分
                long skipBytes = 0;
                if (localRetryCount == 0 && startTime != null) {
                    long secondsToSkip = Duration.between(startTime, LocalDateTime.now()).getSeconds();
                    if (secondsToSkip > 0) {
                        // 估算要跳过的字节数
                        int bytesPerSample = getBytesPerSample(decoder.getFormat());
                        long samplesToSkip = secondsToSkip * decoder.getSampleRate();
                        skipBytes = samplesToSkip * bytesPerSample;
                        LOGGER.debug("Need to skip approximately {} bytes", skipBytes);
                    }
                } else if (localRetryCount > 0) {
                    skipBytes = skipBytesOnRetry;
                    LOGGER.debug("Retry: skipping {} bytes to continue", skipBytes);
                }

                // 跳过音频数据
                if (skipBytes > 0) {
                    long bytesSkipped = 0;
                    while (bytesSkipped < skipBytes && shouldContinueDownloading) {
                        byte[] chunk = decoder.readChunk(BUFFER_SIZE);
                        if (chunk == null) break;
                        bytesSkipped += chunk.length;
                    }
                    LOGGER.debug("Skipped {} bytes", bytesSkipped);
                }

                // 开始下载到缓冲区
                setStatus(Status.BUFFERING);

                // 先填充一些数据到缓冲区
                int initialBuffers = 0;
                while (shouldContinueDownloading && initialBuffers < BUFFER_COUNT * 2) {
                    byte[] audioData = decoder.readChunk(BUFFER_SIZE);
                    if (audioData == null) break;

                    if (!audioBuffer.offer(audioData, 100, TimeUnit.MILLISECONDS)) {
                        // 缓冲区满，继续尝试
                        continue;
                    }
                    totalBufferedBytes.addAndGet(audioData.length);
                    initialBuffers++;

                    // 计算下载速度
                    if (initialBuffers == 1) {
                        // 简单估算每秒字节数
                        bytesPerSecond = (long) audioData.length * 20; // 假设每秒20个数据块
                    }

                    if (calculateBufferedSeconds(decoder.getFormat()) >= 2 && status.get() == Status.BUFFERING) {
                        setStatus(Status.PLAYING);
                    }
                }

                // 继续下载剩余数据
                while (shouldContinueDownloading) {
                    byte[] audioData = decoder.readChunk(BUFFER_SIZE);
                    if (audioData == null) break;

                    // 如果缓冲区已满，等待一会儿
                    while (shouldContinueDownloading && audioBuffer.remainingCapacity() == 0) {
                        Thread.sleep(50);
                    }

                    if (!shouldContinueDownloading) break;

                    audioBuffer.put(audioData);
                    totalBufferedBytes.addAndGet(audioData.length);
                }

                // 下载完成
                LOGGER.debug("Audio download completed");
                shouldContinueDownloading = false;
                break;

            } catch (Exception e) {
                LOGGER.error("Download error (attempt {})", localRetryCount + 1, e);

                if (localRetryCount < maxRetries - 1) {
                    localRetryCount++;
                    setStatus(Status.RETRYING);

                    try {
                        // 等待重试延迟
                        int delay = retryDelayMs + localRetryCount * retryDelayDelta;
                        LOGGER.debug("Waiting {} ms before retry", delay);
                        Thread.sleep(delay);

                        // 继续尝试下载
                        continue;
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        LOGGER.debug("Download thread interrupted");
                        break;
                    }
                } else {
                    LOGGER.error("Max retries reached, stopping download");
                    setStatus(Status.ERROR);
                    break;
                }
            }
        }

        LOGGER.debug("Download task finished");
    }

    private void playFromBuffer(CompletableFuture<LocalDateTime> startFuture) {
        try {
            // 等待一些数据缓冲
            int waitCount = 0;
            while (shouldContinuePlaying && totalBufferedBytes.get() < BUFFER_SIZE * BUFFER_COUNT && waitCount < 100) {
                Thread.sleep(50);
                waitCount++;
            }

            if (totalBufferedBytes.get() == 0) {
                LOGGER.error("No audio data available");
                startFuture.completeExceptionally(new IOException("No audio data available"));
                return;
            }

            synchronized (StreamAudioPlayer.class) {
                if (!initialized.get() || source == 0) {
                    startFuture.completeExceptionally(new IllegalStateException("Audio player not initialized"));
                    return;
                }

                // 从缓冲区填充初始数据
                for (int i = 0; i < BUFFER_COUNT; i++) {
                    byte[] audioData = audioBuffer.poll(1, TimeUnit.SECONDS);
                    if (audioData == null) break;

                    ByteBuffer directBuffer = ByteBuffer.allocateDirect(audioData.length);
                    directBuffer.put(audioData);
                    directBuffer.flip();

                    int format = currentDecoder != null ? currentDecoder.getFormat() : AL10.AL_FORMAT_STEREO16;
                    int sampleRate = currentDecoder != null ? currentDecoder.getSampleRate() : 44100;

                    AL10.alBufferData(buffers[i], format, directBuffer, sampleRate);
                    checkALError("alBufferData");
                    AL10.alSourceQueueBuffers(source, buffers[i]);
                    checkALError("alSourceQueueBuffers");

                    totalPlayedBytes.addAndGet(audioData.length);
                    totalBufferedBytes.addAndGet(-audioData.length);
                }

                startFuture.complete(currentStartTime != null ? currentStartTime : LocalDateTime.now());
                setStatus(Status.PLAYING);

                AL10.alSourcePlay(source);
                checkALError("alSourcePlay");
            }

            // 主播放循环
            while (shouldContinuePlaying) {
                try {
                    synchronized (StreamAudioPlayer.class) {
                        updateVolumeIfNecessary();
                        if (!initialized.get() || source == 0) break;

                        int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
                        checkALError("alGetSourcei");

                        while (processed-- > 0) {
                            int[] buffer = new int[1];
                            AL10.alSourceUnqueueBuffers(source, buffer);
                            checkALError("alSourceUnqueueBuffers");

                            // 从缓冲区获取音频数据，最多等待500ms
                            byte[] audioData = audioBuffer.poll(500, TimeUnit.MILLISECONDS);

                            if (audioData == null) {
                                // 没有数据可用
                                if (!shouldContinueDownloading && audioBuffer.isEmpty()) {
                                    // 下载已完成且缓冲区为空，结束播放
                                    LOGGER.debug("No more audio data available");
                                    shouldContinuePlaying = false;
                                    break;
                                } else {
                                    // 可能是网络缓冲，填充静音数据
                                    audioData = new byte[BUFFER_SIZE];
                                    isBuffering = true;
                                    setStatus(Status.BUFFERING);
                                }
                            } else {
                                isBuffering = false;
                                if (status.get() == Status.BUFFERING && calculateBufferedSeconds(
                                        currentDecoder != null ? currentDecoder.getFormat() : AL10.AL_FORMAT_STEREO16) >= 1) {
                                    setStatus(Status.PLAYING);
                                }
                            }

                            ByteBuffer directBuffer = ByteBuffer.allocateDirect(audioData.length);
                            directBuffer.put(audioData);
                            directBuffer.flip();

                            int format = currentDecoder != null ? currentDecoder.getFormat() : AL10.AL_FORMAT_STEREO16;
                            int sampleRate = currentDecoder != null ? currentDecoder.getSampleRate() : 44100;

                            AL10.alBufferData(buffer[0], format, directBuffer, sampleRate);
                            checkALError("alBufferData");
                            AL10.alSourceQueueBuffers(source, buffer[0]);
                            checkALError("alSourceQueueBuffers");

                            if (audioData.length == BUFFER_SIZE) { // 不是静音数据
                                totalPlayedBytes.addAndGet(audioData.length);
                                totalBufferedBytes.addAndGet(-audioData.length);

                                // 更新跳过的字节数，用于重试时继续播放
                                skipBytesOnRetry = totalPlayedBytes.get();
                            }
                        }

                        int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
                        if (state != AL10.AL_PLAYING && shouldContinuePlaying) {
                            AL10.alSourcePlay(source);
                            checkALError("alSourcePlay");
                        }
                    }

                    // 检查缓冲状态
                    if (isBuffering || audioBuffer.isEmpty()) {
                        setStatus(Status.BUFFERING);
                    }

                    Thread.sleep(40);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    LOGGER.error("Playback error: {}", e.getMessage(), e);
                    // 播放错误，停止播放线程，但让下载线程尝试重连
                    shouldContinuePlaying = false;
                    break;
                }
            }

        } catch (Exception e) {
            LOGGER.error("Playback error: {}", e.getMessage(), e);
            if (!startFuture.isDone()) {
                startFuture.completeExceptionally(e);
            }
        } finally {
            LOGGER.debug("Play task finished");
            stop();
        }
    }

    private void updateVolumeIfNecessary() {
        float musicVolume = minecraft.options.getSoundSourceVolume(SoundSource.MUSIC);
        if (lastVolume != musicVolume && source != 0 && AL10.alIsSource(source)) {
            AL10.alSourcef(source, AL10.AL_GAIN, musicVolume);
            lastVolume = musicVolume;
        }
    }

    private int getBytesPerSample(int format) {
        return switch (format) {
            case AL10.AL_FORMAT_MONO8 -> 1;
            case AL10.AL_FORMAT_MONO16 -> 2;
            case AL10.AL_FORMAT_STEREO8 -> 2;
            case AL10.AL_FORMAT_STEREO16 -> 4;
            default -> 4;
        };
    }

    private float calculateBufferedSeconds(int format) {
        if (bytesPerSecond == 0) return 0;
        int bytesPerSample = getBytesPerSample(format);
        long samplesPerSecond = bytesPerSecond / bytesPerSample;
        if (samplesPerSecond == 0) return 0;

        long bufferedSamples = totalBufferedBytes.get() / bytesPerSample;
        return (float) bufferedSamples / samplesPerSecond;
    }

    private void checkALError(String operation) {
        int error = AL10.alGetError();
        if (error != AL10.AL_NO_ERROR) {
            String errorMsg = getALErrorString(error);
            LOGGER.error("OpenAL Error during {}: {} ({})", operation, errorMsg, error);
            throw new RuntimeException("OpenAL Error during " + operation + ": " + errorMsg + " (" + error + ")");
        }
    }

    private String getALErrorString(int error) {
        return switch (error) {
            case AL10.AL_INVALID_NAME -> "AL_INVALID_NAME";
            case AL10.AL_INVALID_ENUM -> "AL_INVALID_ENUM";
            case AL10.AL_INVALID_VALUE -> "AL_INVALID_VALUE";
            case AL10.AL_INVALID_OPERATION -> "AL_INVALID_OPERATION";
            case AL10.AL_OUT_OF_MEMORY -> "AL_OUT_OF_MEMORY";
            default -> "UNKNOWN_ERROR";
        };
    }

    @SneakyThrows
    public void stop() {
        shouldContinuePlaying = false;
        shouldContinueDownloading = false;

        // 取消任务
        if (playingFuture != null) {
            playingFuture.cancel(true);
            playingFuture = null;
        }

        if (downloadFuture != null) {
            downloadFuture.cancel(true);
            downloadFuture = null;
        }

        if (currentDecoder != null) {
            try {
                currentDecoder.close();
            } catch (Exception e) {
                // Ignore
            }
            currentDecoder = null;
        }

        lastVolume = 1;
        setStatus(Status.IDLE);
        cleanup();
    }

    private void cleanup() {
        synchronized (StreamAudioPlayer.class) {
            try {
                if (source != 0 && AL10.alIsSource(source)) {
                    AL10.alSourceStop(source);
                    int error = AL10.alGetError();
                    if (error != AL10.AL_NO_ERROR) {
                        LOGGER.warn("Error stopping source: {}", error);
                    }

                    int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
                    while (processed-- > 0) {
                        int[] buffer = new int[1];
                        AL10.alSourceUnqueueBuffers(source, buffer);
                        checkALError("alSourceUnqueueBuffers");
                    }

                    AL10.alDeleteSources(source);
                    checkALError("alDeleteSources");
                    source = 0;
                }

                for (int i = 0; i < buffers.length; i++) {
                    if (buffers[i] != 0 && AL10.alIsBuffer(buffers[i])) {
                        AL10.alDeleteBuffers(buffers[i]);
                        checkALError("alDeleteBuffers");
                        buffers[i] = 0;
                    }
                }

                initialized.set(false);
                lastVolume = 1;

                // 清空缓冲区
                audioBuffer.clear();
                totalBufferedBytes.set(0);
                totalPlayedBytes.set(0);

                LOGGER.debug("Cleanup completed");
            } catch (Exception e) {
                LOGGER.error("Cleanup error", e);
            }
        }
    }

    // 获取当前缓冲状态（秒）
    public float getBufferedSeconds() {
        if (currentDecoder == null) return 0;
        return calculateBufferedSeconds(currentDecoder.getFormat());
    }

    public enum Status {
        IDLE, BUFFERING, PLAYING, RETRYING, ERROR
    }
}