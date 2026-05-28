/*
 * Copyright 2016 Luca Martino.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copyFile of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package nie.translator.rtranslator.tools;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;

import nie.translator.rtranslator.voice_translation.neural_networks.translation.Translator;

public class TTS {
    public static final String PREF_ONLINE_TTS_ENABLED = "onlineTtsEnabled";
    public static final String PREF_ONLINE_TTS_API_BASE_URL = "onlineTtsApiBaseUrl";
    public static final String PREF_ONLINE_TTS_API_KEY = "onlineTtsApiKey";
    public static final String PREF_ONLINE_TTS_VOICE = "onlineTtsVoice";
    public static final String PREF_ONLINE_TTS_RATE = "onlineTtsRate";
    public static final String PREF_ONLINE_TTS_PITCH = "onlineTtsPitch";
    public static final String PREF_ONLINE_TTS_STYLE = "onlineTtsStyle";
    public static final String PREF_ONLINE_TTS_OUTPUT_FORMAT = "onlineTtsOutputFormat";

    private static final String DEFAULT_ONLINE_TTS_API_BASE_URL = "https://t.leftsite.cn";
    private static final String DEFAULT_ONLINE_TTS_VOICE = "zh-CN-XiaochenMultilingualNeural";
    private static final String DEFAULT_ONLINE_TTS_RATE = "-20%";
    private static final String DEFAULT_ONLINE_TTS_PITCH = "0%";
    private static final String DEFAULT_ONLINE_TTS_OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3";

    //object
    private TextToSpeech tts;
    private Context context;
    private CustomLocale currentLanguage;
    private UtteranceProgressListener onlineTtsListener;
    private MediaPlayer onlineMediaPlayer;

    //Attributes used for getting the supported languages
    private static Thread getSupportedLanguageThread;
    private static ArrayDeque<SupportedLanguagesListener> supportedLanguagesListeners = new ArrayDeque<>();
    private static final Object lock = new Object();
    private static final ArrayList<CustomLocale> ttsLanguages = new ArrayList<>();


    public TTS(Context context, final InitListener listener) {
        this.context = context.getApplicationContext();
        if (isOnlineTTSEnabled(this.context)) {
            tts = null;
            listener.onInit();
            return;
        }
        tts = new TextToSpeech(context, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    if (tts != null) {
                        listener.onInit();
                        return; // Set TTS to the default TTS directly.
                    }
                }
                tts = null;
                listener.onError(ErrorCodes.GOOGLE_TTS_ERROR);
            }
        },
        null);// use default TTS when this is null
    }

    public boolean isActive() {
        return isOnlineTTSEnabled(context) || tts != null;
    }

    public int speak(CharSequence text, int queueMode, Bundle params, String utteranceId) {
        if (isOnlineTTSEnabled(context)) {
            speakOnline(text == null ? "" : text.toString(), queueMode, utteranceId);
            return TextToSpeech.SUCCESS;
        }
        if (tts != null) {
            return tts.speak(text, queueMode, params, utteranceId);
        }
        return TextToSpeech.ERROR;
    }

    @Nullable
    public Voice getVoice() {
        if (tts != null) {
            return tts.getVoice();
        }
        return null;
    }

    @Nullable
    public Set<Voice> getVoices() {
        if (tts != null) {
            return tts.getVoices();
        }
        return null;
    }

    public int setOnUtteranceProgressListener(UtteranceProgressListener listener) {
        onlineTtsListener = listener;
        if (tts != null) {
            return tts.setOnUtteranceProgressListener(listener);
        }
        return TextToSpeech.SUCCESS;
    }

    public int setLanguage(CustomLocale loc, Context context) {
        currentLanguage = loc;
        if (tts != null) {
            return tts.setLanguage(new Locale(loc.getLocale().getLanguage()));
        }
        return TextToSpeech.SUCCESS;
    }

    public int stop() {
        stopOnlineMediaPlayer();
        if (tts != null) {
            return tts.stop();
        }
        return TextToSpeech.SUCCESS;
    }

    public void shutdown() {
        stopOnlineMediaPlayer();
        if (tts != null) {
            tts.shutdown();
        }
    }

    private void speakOnline(final String text, int queueMode, final String utteranceId) {
        if (queueMode == TextToSpeech.QUEUE_FLUSH) {
            stopOnlineMediaPlayer();
        }
        final String safeUtteranceId = utteranceId != null ? utteranceId : String.valueOf(System.currentTimeMillis());
        new Thread(new Runnable() {
            @Override
            public void run() {
                File audioFile = null;
                try {
                    if (onlineTtsListener != null) {
                        onlineTtsListener.onStart(safeUtteranceId);
                    }
                    audioFile = downloadOnlineTTS(text);
                    final File finalAudioFile = audioFile;
                    MediaPlayer player = new MediaPlayer();
                    synchronized (TTS.this) {
                        onlineMediaPlayer = player;
                    }
                    player.setDataSource(finalAudioFile.getAbsolutePath());
                    player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                        @Override
                        public void onCompletion(MediaPlayer mp) {
                            mp.release();
                            synchronized (TTS.this) {
                                if (onlineMediaPlayer == mp) {
                                    onlineMediaPlayer = null;
                                }
                            }
                            //noinspection ResultOfMethodCallIgnored
                            finalAudioFile.delete();
                            if (onlineTtsListener != null) {
                                onlineTtsListener.onDone(safeUtteranceId);
                            }
                        }
                    });
                    player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                        @Override
                        public boolean onError(MediaPlayer mp, int what, int extra) {
                            mp.release();
                            synchronized (TTS.this) {
                                if (onlineMediaPlayer == mp) {
                                    onlineMediaPlayer = null;
                                }
                            }
                            //noinspection ResultOfMethodCallIgnored
                            finalAudioFile.delete();
                            if (onlineTtsListener != null) {
                                onlineTtsListener.onError(safeUtteranceId);
                            }
                            return true;
                        }
                    });
                    player.prepare();
                    player.start();
                } catch (Exception e) {
                    e.printStackTrace();
                    if (audioFile != null) {
                        //noinspection ResultOfMethodCallIgnored
                        audioFile.delete();
                    }
                    if (onlineTtsListener != null) {
                        onlineTtsListener.onError(safeUtteranceId);
                    }
                }
            }
        }, "onlineTtsPerformer").start();
    }

    private synchronized void stopOnlineMediaPlayer() {
        if (onlineMediaPlayer != null) {
            try {
                onlineMediaPlayer.stop();
            } catch (IllegalStateException ignored) {
            }
            onlineMediaPlayer.release();
            onlineMediaPlayer = null;
        }
    }

    private File downloadOnlineTTS(String text) throws IOException {
        SharedPreferences sharedPreferences = context.getSharedPreferences("default", Context.MODE_PRIVATE);
        String apiBaseUrl = trimTrailingSlash(sharedPreferences.getString(PREF_ONLINE_TTS_API_BASE_URL, DEFAULT_ONLINE_TTS_API_BASE_URL));
        String apiKey = sharedPreferences.getString(PREF_ONLINE_TTS_API_KEY, "");
        String voice = sharedPreferences.getString(PREF_ONLINE_TTS_VOICE, DEFAULT_ONLINE_TTS_VOICE);
        String rate = sharedPreferences.getString(PREF_ONLINE_TTS_RATE, DEFAULT_ONLINE_TTS_RATE);
        String pitch = sharedPreferences.getString(PREF_ONLINE_TTS_PITCH, DEFAULT_ONLINE_TTS_PITCH);
        String style = sharedPreferences.getString(PREF_ONLINE_TTS_STYLE, "");
        String outputFormat = sharedPreferences.getString(PREF_ONLINE_TTS_OUTPUT_FORMAT, DEFAULT_ONLINE_TTS_OUTPUT_FORMAT);

        if (apiBaseUrl == null || apiBaseUrl.length() == 0) {
            throw new IOException("Online TTS API address is empty");
        }

        StringBuilder urlBuilder = new StringBuilder(apiBaseUrl);
        urlBuilder.append("/tts?t=").append(URLEncoder.encode(text, "UTF-8"));
        urlBuilder.append("&v=").append(URLEncoder.encode(voice, "UTF-8"));
        urlBuilder.append("&r=").append(URLEncoder.encode(rate, "UTF-8"));
        urlBuilder.append("&p=").append(URLEncoder.encode(pitch, "UTF-8"));
        urlBuilder.append("&o=").append(URLEncoder.encode(outputFormat, "UTF-8"));
        if (style != null && style.length() > 0) {
            urlBuilder.append("&s=").append(URLEncoder.encode(style, "UTF-8"));
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(urlBuilder.toString()).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        if (apiKey != null && apiKey.length() > 0) {
            connection.setRequestProperty("X-API-Key", apiKey);
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        }

        int status = connection.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            throw new IOException("Online TTS API error: HTTP " + status);
        }

        File outFile = File.createTempFile("online_tts_", ".mp3", context.getCacheDir());
        BufferedInputStream inputStream = new BufferedInputStream(connection.getInputStream());
        BufferedOutputStream outputStream = new BufferedOutputStream(new FileOutputStream(outFile));
        byte[] buffer = new byte[8192];
        int read;
        int total = 0;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
            total += read;
        }
        outputStream.close();
        inputStream.close();
        connection.disconnect();
        if (total <= 100) {
            //noinspection ResultOfMethodCallIgnored
            outFile.delete();
            throw new IOException("Online TTS returned empty audio");
        }
        return outFile;
    }

    private static String trimTrailingSlash(String value) {
        if (value == null) {
            return "";
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static boolean isOnlineTTSEnabled(Context context) {
        if (context == null) {
            return false;
        }
        SharedPreferences sharedPreferences = context.getSharedPreferences("default", Context.MODE_PRIVATE);
        return sharedPreferences.getBoolean(PREF_ONLINE_TTS_ENABLED, false);
    }

    public static void getSupportedLanguages(Context context, SupportedLanguagesListener responseListener){
        if (isOnlineTTSEnabled(context)) {
            ArrayList<CustomLocale> languages = Translator.getSupportedLanguages(context, Translator.NLLB);
            if (!CustomLocale.containsLanguage(languages, CustomLocale.getInstance("my"))) {
                languages.add(CustomLocale.getInstance("my"));
            }
            if (responseListener != null) {
                responseListener.onLanguagesListAvailable(languages);
            }
            return;
        }
        synchronized (lock) {
            if (responseListener != null) {
                supportedLanguagesListeners.addLast(responseListener);
            }
            if (getSupportedLanguageThread == null) {
                getSupportedLanguageThread = new Thread(new GetSupportedLanguageRunnable(context, new SupportedLanguagesListener() {
                    @Override
                    public void onLanguagesListAvailable(ArrayList<CustomLocale> languages) {
                        notifyGetSupportedLanguagesSuccess(languages);
                    }

                    @Override
                    public void onError(int reason) {
                        notifyGetSupportedLanguagesFailure(reason);
                    }
                }), "getSupportedLanguagePerformer");
                getSupportedLanguageThread.start();
            }
        }
    }

    private static void notifyGetSupportedLanguagesSuccess(ArrayList<CustomLocale> languages) {
        synchronized (lock) {
            while (supportedLanguagesListeners.peekFirst() != null) {
                supportedLanguagesListeners.pollFirst().onLanguagesListAvailable(languages);
            }
            getSupportedLanguageThread = null;
        }
    }

    private static void notifyGetSupportedLanguagesFailure(final int reasons) {
        synchronized (lock) {
            while (supportedLanguagesListeners.peekFirst() != null) {
                supportedLanguagesListeners.pollFirst().onError(reasons);
            }
            getSupportedLanguageThread = null;
        }
    }

    private static class GetSupportedLanguageRunnable implements Runnable {
        private SupportedLanguagesListener responseListener;
        private Context context;
        private static TTS tempTts;
        private static android.os.Handler mainHandler;   // handler that can be used to post to the main thread

        private GetSupportedLanguageRunnable(Context context, final SupportedLanguagesListener responseListener) {
            this.responseListener = responseListener;
            this.context = context;
            mainHandler = new android.os.Handler(Looper.getMainLooper());
        }

        @Override
        public void run() {
            tempTts = new TTS((context), new TTS.InitListener() {    // tts initialization (to be improved, automatic package installation)
                @Override
                public void onInit() {
                    Set<Voice> set = tempTts.getVoices();
                    SharedPreferences sharedPreferences = context.getSharedPreferences("default", Context.MODE_PRIVATE);
                    boolean qualityLow = sharedPreferences.getBoolean("languagesQualityLow", false);
                    int quality;
                    if (qualityLow) {
                        quality = Voice.QUALITY_VERY_LOW;
                    } else {
                        quality = Voice.QUALITY_NORMAL;
                    }
                    boolean foundLanguage = false; // if there is available languages
                    ttsLanguages.clear();
                    if (set != null) {
                        // we filter the languages that have a tts that reflects the quality characteristics we want
                        for (Voice aSet : set) {
                            if (aSet.getQuality() >= quality && (qualityLow || !aSet.getFeatures().contains("legacySetLanguageVoice"))) {
                                CustomLocale language;
                                if(aSet.getLocale() != null){
                                    language = new CustomLocale(aSet.getLocale()); // Use .getLocale() for google
                                    foundLanguage = true;
                                }else{
                                    language = CustomLocale.getInstance(aSet.getName()); // Use .getName() for samsung/huawei (maybe others also)
                                    foundLanguage = true;
                                }

                                ttsLanguages.add(language);
                            }
                        }
                    }
                    if (foundLanguage) {    // start TTS if the above lines find at least 1 supported language
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                responseListener.onLanguagesListAvailable(ttsLanguages);
                            }
                        });
                    } else {
                        onError(ErrorCodes.GOOGLE_TTS_ERROR);
                    }
                    tempTts.stop();
                    tempTts.shutdown();
                }

                @Override
                public void onError(final int reason) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            responseListener.onError(reason);
                        }
                    });
                }
            });
        }
    }

    public interface InitListener {
        void onInit();

        void onError(int reason);
    }

    public interface SupportedLanguagesListener {
        void onLanguagesListAvailable(ArrayList<CustomLocale> languages);
        void onError(int reason);
    }
}
