package nie.translator.rtranslator.voice_translation.neural_networks.translation;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

import nie.translator.rtranslator.tools.CustomLocale;

public class OnlineLlmTranslator {
    public static final String PREF_LLM_TRANSLATION_ENABLED = "llmTranslationEnabled";
    public static final String PREF_LLM_API_BASE_URL = "llmTranslationApiBaseUrl";
    public static final String PREF_LLM_API_KEY = "llmTranslationApiKey";
    public static final String PREF_LLM_MODEL = "llmTranslationModel";
    public static final String PREF_LLM_TEMPERATURE = "llmTranslationTemperature";
    public static final String PREF_LLM_PROMPT = "llmTranslationPrompt";
    public static final String PREF_LLM_DISABLE_THINKING = "llmTranslationDisableThinking";

    private static final String DEFAULT_API_BASE_URL = "https://api.openai.com/v1";
    private static final String DEFAULT_MODEL = "gpt-4o-mini";
    private static final String DEFAULT_TEMPERATURE = "0.3";
    private static final String DEFAULT_PROMPT = "自然直译版：在保留原文结构和含义的基础上，让译文符合目标语言的表达习惯，读起来流畅自然，不生硬。只输出译文，不要解释。";

    public static boolean isEnabled(Context context) {
        if (context == null) {
            return false;
        }
        SharedPreferences sharedPreferences = context.getSharedPreferences("default", Context.MODE_PRIVATE);
        return sharedPreferences.getBoolean(PREF_LLM_TRANSLATION_ENABLED, false);
    }

    public static String translate(Context context, String text, CustomLocale inputLanguage, CustomLocale outputLanguage) throws IOException, JSONException {
        SharedPreferences sharedPreferences = context.getSharedPreferences("default", Context.MODE_PRIVATE);
        String apiBaseUrl = sharedPreferences.getString(PREF_LLM_API_BASE_URL, DEFAULT_API_BASE_URL);
        String apiKey = sharedPreferences.getString(PREF_LLM_API_KEY, "");
        String model = sharedPreferences.getString(PREF_LLM_MODEL, DEFAULT_MODEL);
        String temperatureText = sharedPreferences.getString(PREF_LLM_TEMPERATURE, DEFAULT_TEMPERATURE);
        String prompt = sharedPreferences.getString(PREF_LLM_PROMPT, DEFAULT_PROMPT);
        boolean disableThinking = sharedPreferences.getBoolean(PREF_LLM_DISABLE_THINKING, true);
        float temperature = parseTemperature(temperatureText);

        String endpoint = normalizeChatCompletionsEndpoint(apiBaseUrl);
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("temperature", temperature);
        body.put("stream", false);

        JSONArray messages = new JSONArray();
        JSONObject systemMessage = new JSONObject();
        systemMessage.put("role", "system");
        String systemPrompt = prompt + "\nTranslate from " + displayName(inputLanguage) + " (" + inputLanguage.getCode() + ") to " + displayName(outputLanguage) + " (" + outputLanguage.getCode() + "). Return only the translated text.";
        if (disableThinking) {
            systemPrompt += " Do not output reasoning, thinking, markdown, explanations, or <think> tags.";
        }
        systemMessage.put("content", systemPrompt);
        messages.put(systemMessage);

        JSONObject userMessage = new JSONObject();
        userMessage.put("role", "user");
        userMessage.put("content", text);
        messages.put(userMessage);
        body.put("messages", messages);

        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(60000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (apiKey != null && apiKey.length() > 0) {
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        }

        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(connection.getOutputStream(), "UTF-8"));
        writer.write(body.toString());
        writer.flush();
        writer.close();

        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
        String response = readAll(stream);
        connection.disconnect();
        if (status < 200 || status >= 300) {
            throw new IOException("LLM translation API error: HTTP " + status + " " + response);
        }

        JSONObject json = new JSONObject(response);
        JSONArray choices = json.getJSONArray("choices");
        if (choices.length() == 0) {
            throw new IOException("LLM translation API returned no choices");
        }
        JSONObject message = choices.getJSONObject(0).getJSONObject("message");
        String content = message.optString("content", "").trim();
        content = stripThinkingTags(content).trim();
        if (content.length() == 0) {
            throw new IOException("LLM translation API returned empty content");
        }
        return content;
    }

    private static String normalizeChatCompletionsEndpoint(String apiBaseUrl) {
        if (apiBaseUrl == null || apiBaseUrl.length() == 0) {
            apiBaseUrl = DEFAULT_API_BASE_URL;
        }
        while (apiBaseUrl.endsWith("/")) {
            apiBaseUrl = apiBaseUrl.substring(0, apiBaseUrl.length() - 1);
        }
        if (apiBaseUrl.endsWith("/chat/completions")) {
            return apiBaseUrl;
        }
        return apiBaseUrl + "/chat/completions";
    }

    private static float parseTemperature(String temperatureText) {
        try {
            return Float.parseFloat(temperatureText);
        } catch (Exception ignored) {
            return 0.3f;
        }
    }

    private static String displayName(CustomLocale locale) {
        String displayName = locale.getDisplayName(Locale.ENGLISH);
        if (displayName == null || displayName.length() == 0) {
            return locale.getCode();
        }
        return displayName;
    }

    private static String readAll(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            builder.append(line);
        }
        reader.close();
        return builder.toString();
    }

    private static String stripThinkingTags(String text) {
        return text.replaceAll("(?s)<think>.*?</think>", "");
    }
}
