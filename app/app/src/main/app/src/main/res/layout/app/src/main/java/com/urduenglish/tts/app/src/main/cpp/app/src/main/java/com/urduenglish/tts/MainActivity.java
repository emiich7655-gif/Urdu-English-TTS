package com.urduenglish.tts;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int SAVE_AUDIO = 101;

    private TextToSpeech tts;
    private EditText textInput;
    private Spinner languageSpinner;
    private Button playButton;
    private Button stopButton;
    private Button saveButton;
    private TextView statusText;

    private boolean ready = false;
    private boolean busy = false;
    private volatile boolean destroyed = false;

    private volatile String activeSaveId;
    private File wavFile;
    private File mp3File;

    private final ExecutorService worker =
            Executors.newSingleThreadExecutor();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Keep controls clear of status/navigation bars.
        View content = findViewById(android.R.id.content);
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars()
                                | WindowInsets.Type.displayCutout()
                );

                android.graphics.Insets keyboard = insets.getInsets(
                        WindowInsets.Type.ime()
                );

                view.setPadding(
                        bars.left,
                        bars.top,
                        bars.right,
                        Math.max(bars.bottom, keyboard.bottom)
                );
            } else {
                view.setPadding(
                        insets.getSystemWindowInsetLeft(),
                        insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(),
                        insets.getSystemWindowInsetBottom()
                );
            }
            return insets;
        });
        content.requestApplyInsets();

        textInput = findViewById(R.id.textInput);
        languageSpinner = findViewById(R.id.languageSpinner);
        playButton = findViewById(R.id.playButton);
        stopButton = findViewById(R.id.stopButton);
        saveButton = findViewById(R.id.saveButton);
        statusText = findViewById(R.id.statusText);

        String[] languages = {
                "Urdu (Pakistan)",
                "Urdu (India)",
                "English (United States)"
        };

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                languages
        );
        adapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item
        );
        languageSpinner.setAdapter(adapter);

        playButton.setOnClickListener(view -> playText());

        stopButton.setOnClickListener(view -> {
            if (tts != null) {
                tts.stop();
                statusText.setText("Audio rok di gayi.");
            }
        });

        saveButton.setOnClickListener(view -> generateMp3());

        tts = new TextToSpeech(this, result -> {
            runOnUiThread(() -> {
                if (destroyed) return;

                if (result != TextToSpeech.SUCCESS) {
                    statusText.setText(
                            "Speech engine start nahi hua. "
                                    + "Phone ki TTS settings check karein."
                    );
                    return;
                }

                configureListener();
                ready = true;
                updateControls();
                statusText.setText(
                        "Tayyar! Zabaan select karein aur text paste karein."
                );
            });
        });
    }

    private void configureListener() {
        tts.setOnUtteranceProgressListener(
                new UtteranceProgressListener() {
                    @Override
                    public void onStart(String id) {
                    }

                    @Override
                    public void onDone(String id) {
                        if (id != null && id.equals(activeSaveId)) {
                            runOnUiThread(() -> {
                                if (!destroyed &&
                                        id.equals(activeSaveId)) {
                                    activeSaveId = null;
                                    convertAudio();
                                }
                            });
                        }
                    }

                    @Override
                    public void onError(String id) {
                        handleSpeechError(id);
                    }

                    @Override
                    public void onError(String id, int errorCode) {
                        handleSpeechError(id);
                    }
                }
        );
    }

    private void handleSpeechError(String id) {
        runOnUiThread(() -> {
            if (destroyed) return;

            if (id != null && id.equals(activeSaveId)) {
                activeSaveId = null;
                finishJob(
                        "Audio nahi bani. Voice data aur internet "
                                + "check karke dobara try karein."
                );
            } else if (!busy) {
                statusText.setText(
                        "Text parhne mein error aaya. "
                                + "Voice data aur internet check karein."
                );
            }
        });
    }

    private void updateControls() {
        playButton.setEnabled(ready && !busy);
        saveButton.setEnabled(ready && !busy);
        stopButton.setEnabled(ready && !busy);
        textInput.setEnabled(!busy);
        languageSpinner.setEnabled(!busy);
    }

    private String prepareText() {
        if (!ready || busy) return null;

        String text = textInput.getText().toString().trim();

        if (text.isEmpty()) {
            statusText.setText("Pehle text likhein ya paste karein.");
            return null;
        }

        int limit = Math.min(
                4000,
                TextToSpeech.getMaxSpeechInputLength()
        );

        if (text.length() > limit) {
            statusText.setText(
                    "Is version mein ek baar mein maximum "
                            + limit
                            + " characters hain. Text chhota karein."
            );
            return null;
        }

        Locale language;

        switch (languageSpinner.getSelectedItemPosition()) {
            case 1:
                language = new Locale("ur", "IN");
                break;
            case 2:
                language = Locale.US;
                break;
            default:
                language = new Locale("ur", "PK");
                break;
        }

        int result = tts.setLanguage(language);

        if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED) {
            statusText.setText(
                    "Yeh voice available nahi. TTS settings mein "
                            + "voice download karein ya doosri zabaan chunein."
            );
            return null;
        }

        return text;
    }

    private void playText() {
        String text = prepareText();
        if (text == null) return;

        tts.setSpeechRate(1.0f);

        int result = tts.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                new Bundle(),
                "play-" + System.nanoTime()
        );

        statusText.setText(
                result == TextToSpeech.SUCCESS
                        ? "Audio play ki ja rahi hai..."
                        : "Audio play nahi ho saki."
        );
    }

    private void generateMp3() {
        String text = prepareText();
        if (text == null) return;

        tts.stop();
        busy = true;
        updateControls();

        try {
            wavFile = File.createTempFile(
                    "tts_audio_", ".wav", getCacheDir()
            );
            mp3File = File.createTempFile(
                    "tts_audio_", ".mp3", getCacheDir()
            );

            activeSaveId = "save-" + System.nanoTime();
            statusText.setText(
                    "Audio ban rahi hai... App khuli rakhein."
            );

            int result = tts.synthesizeToFile(
                    text,
                    new Bundle(),
                    wavFile,
                    activeSaveId
            );

            if (result != TextToSpeech.SUCCESS) {
                activeSaveId = null;
                finishJob("Audio banana start nahi ho saka.");
            }
        } catch (Exception error) {
            activeSaveId = null;
            finishJob("Error: " + error.getMessage());
        }
    }

    private void convertAudio() {
        statusText.setText("MP3 mein convert ho rahi hai...");

        final File input = wavFile;
        final File output = mp3File;

        worker.execute(() -> {
            try {
                Mp3Encoder.convert(input, output);

                runOnUiThread(() -> {
                    if (destroyed) {
                        input.delete();
                        output.delete();
                        return;
                    }

                    openSavePicker();
                });

            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (destroyed) {
                        input.delete();
                        output.delete();
                        return;
                    }

                    finishJob(
                            "MP3 nahi bani: " + error.getMessage()
                    );
                });
            }
        });
    }

    private void openSavePicker() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/mpeg");
        intent.putExtra(
                Intent.EXTRA_TITLE,
                "TTS-" + System.currentTimeMillis() + ".mp3"
        );

        try {
            statusText.setText(
                    "Folder aur file ka naam chun kar Save dabayein."
            );
            startActivityForResult(intent, SAVE_AUDIO);
        } catch (Exception error) {
            finishJob(
                    "Phone ka file picker nahi khul saka: "
                            + error.getMessage()
            );
        }
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != SAVE_AUDIO) return;

        if (resultCode != RESULT_OK ||
                data == null || data.getData() == null) {
            finishJob("Save cancel kar diya gaya.");
            return;
        }

        if (mp3File == null || !mp3File.isFile()) {
            finishJob(
                    "Temporary audio nahi mili. Dobara Save MP3 dabayein."
            );
            return;
        }

        Uri destination = data.getData();
        final File source = mp3File;
        statusText.setText("MP3 phone mein save ho rahi hai...");

        worker.execute(() -> {
            try {
                try (
                        FileInputStream input =
                                new FileInputStream(source);
                        OutputStream output =
                                getContentResolver().openOutputStream(
                                        destination, "w"
                                )
                ) {
                    if (output == null) {
                        throw new IOException(
                                "Selected file mein likhna mumkin nahi."
                        );
                    }

                    byte[] buffer = new byte[8192];
                    int count;

                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                    }

                    output.flush();
                }

                runOnUiThread(() -> {
                    if (!destroyed) {
                        finishJob(
                                "MP3 aapki select ki hui jagah par save ho gayi!"
                        );
                    }
                });

            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!destroyed) {
                        finishJob(
                                "Save nahi hui: " + error.getMessage()
                        );
                    }
                });
            }
        });
    }

    private void finishJob(String message) {
        activeSaveId = null;
        busy = false;

        if (wavFile != null) {
            wavFile.delete();
            wavFile = null;
        }

        if (mp3File != null) {
            mp3File.delete();
            mp3File = null;
        }

        updateControls();
        statusText.setText(message);
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        activeSaveId = null;

        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }

        worker.shutdown();
        super.onDestroy();
    }
    }
