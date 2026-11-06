package cl.pkgbridge.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PKG Bridge 1.0: cliente FTP sencillo para enviar PKG a servidores de confianza de la LAN.
 * No requiere acceso global a archivos, ni guarda contraseñas, ni copia el PKG en memoria.
 */
public final class MainActivity extends Activity {
    private static final int PICK_PKG_REQUEST = 501;
    private static final String PREFS = "connection";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    private EditText hostInput, portInput, dirInput, userInput, passwordInput;
    private Button testButton, selectButton, uploadButton, cancelButton;
    private TextView advancedToggle, fileNameText, fileSizeText, statusText, progressText, speedText;
    private LinearLayout advancedSection;
    private ProgressBar progressBar;

    private volatile boolean busy = false;
    private volatile boolean transferring = false;
    private Uri selectedUri;
    private String selectedName;
    private long selectedSize = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        hostInput = findViewById(R.id.hostInput);
        portInput = findViewById(R.id.portInput);
        dirInput = findViewById(R.id.dirInput);
        userInput = findViewById(R.id.userInput);
        passwordInput = findViewById(R.id.passwordInput);
        testButton = findViewById(R.id.testButton);
        selectButton = findViewById(R.id.selectButton);
        uploadButton = findViewById(R.id.uploadButton);
        cancelButton = findViewById(R.id.cancelButton);
        advancedToggle = findViewById(R.id.advancedToggle);
        advancedSection = findViewById(R.id.advancedSection);
        fileNameText = findViewById(R.id.fileNameText);
        fileSizeText = findViewById(R.id.fileSizeText);
        statusText = findViewById(R.id.statusText);
        progressText = findViewById(R.id.progressText);
        speedText = findViewById(R.id.speedText);
        progressBar = findViewById(R.id.progressBar);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        hostInput.setText(prefs.getString("host", ""));
        portInput.setText(prefs.getString("port", "2121"));
        dirInput.setText(prefs.getString("directory", "/data/pkg"));
        userInput.setText(prefs.getString("username", "anonymous"));

        advancedToggle.setOnClickListener(v -> {
            boolean show = advancedSection.getVisibility() == View.GONE;
            advancedSection.setVisibility(show ? View.VISIBLE : View.GONE);
            advancedToggle.setText(show
                    ? "▾  Opciones avanzadas (usuario y contraseña)"
                    : "▸  Opciones avanzadas (usuario y contraseña)");
        });
        testButton.setOnClickListener(v -> startConnectionTest());
        selectButton.setOnClickListener(v -> openFilePicker());
        uploadButton.setOnClickListener(v -> startUpload());
        cancelButton.setOnClickListener(v -> {
            cancelled.set(true);
            cancelButton.setEnabled(false);
            setStatus("Cancelando después del bloque actual…", false);
        });
        updateButtons();
    }

    private void openFilePicker() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*"); // Un PKG suele tener MIME genérico o desconocido.
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, PICK_PKG_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_PKG_REQUEST || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;

        String name = getFileName(uri);
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".pkg")) {
            setStatus("Selecciona un archivo que termine en .pkg", true);
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException | IllegalArgumentException ignored) {
            // Algunos proveedores ofrecen acceso solamente durante la sesión.
        }
        selectedUri = uri;
        selectedName = cleanFileName(name);
        selectedSize = getFileSize(uri);
        fileNameText.setText(selectedName);
        fileSizeText.setText(selectedSize > 0
                ? "Tamaño: " + formatSize(selectedSize)
                : "Tamaño desconocido: se enviará por streaming");
        resetProgress();
        setStatus("PKG seleccionado. Puedes iniciar el envío.", false);
        updateButtons();
    }

    private String getFileName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) { }
        return uri.getLastPathSegment();
    }

    private long getFileSize(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) return cursor.getLong(index);
            }
        } catch (Exception ignored) { }
        return -1;
    }

    /** No se permiten rutas, secuencias de control ni nombres que no sean PKG. */
    private static String cleanFileName(String name) {
        String safe = name.replace('\\', '_').replace('/', '_')
                .replace('\r', '_').replace('\n', '_');
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < safe.length(); i++) {
            char c = safe.charAt(i);
            if (c >= 32 && c != 127) out.append(c);
        }
        safe = out.toString().trim();
        if (safe.startsWith(".")) safe = "pkg_" + safe;
        return safe;
    }

    private ConnectionSettings readSettings() {
        String host = hostInput.getText().toString().trim();
        if (!validIPv4(host)) {
            hostInput.setError("Ingresa la IP IPv4 de la PS4 (ej.: 192.168.1.100)");
            return null;
        }
        int port;
        try {
            port = Integer.parseInt(portInput.getText().toString().trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            portInput.setError("El puerto debe estar entre 1 y 65535");
            return null;
        }
        String directory = dirInput.getText().toString().trim();
        if (!directory.startsWith("/") || directory.contains("..")
                || directory.contains("\\") || directory.contains("\n")
                || directory.contains("\r")) {
            dirInput.setError("Usa una ruta FTP absoluta, como /data/pkg");
            return null;
        }
        String username = userInput.getText().toString().trim();
        if (username.isEmpty()) username = "anonymous";
        String password = passwordInput.getText().toString();
        // Configuración visible; la contraseña nunca se persiste.
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("host", host)
                .putString("port", Integer.toString(port))
                .putString("directory", directory)
                .putString("username", username)
                .apply();
        return new ConnectionSettings(host, port, directory, username, password);
    }

    private static boolean validIPv4(String value) {
        String[] octets = value.split("\\.", -1);
        if (octets.length != 4) return false;
        for (String s : octets) {
            if (s.isEmpty() || s.length() > 3) return false;
            for (int i = 0; i < s.length(); i++) {
                if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
            }
            try {
                int n = Integer.parseInt(s);
                if (n < 0 || n > 255) return false;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    private void startConnectionTest() {
        if (busy) return;
        ConnectionSettings settings = readSettings();
        if (settings == null) return;
        busy = true;
        transferring = false;
        updateButtons();
        setStatus("Conectando a " + settings.host + ":" + settings.port + "…", false);

        worker.execute(() -> {
            FTPClient ftp = new FTPClient();
            try {
                connect(ftp, settings);
                ensureRemoteDirectory(ftp, settings.directory);
                runOnUiThread(() -> setStatus("✓ Conexión correcta. Carpeta disponible: "
                        + settings.directory, false));
            } catch (Exception e) {
                String error = messageFor(e);
                runOnUiThread(() -> setStatus("No se pudo conectar: " + error, true));
            } finally {
                disconnectQuietly(ftp);
                runOnUiThread(() -> { busy = false; updateButtons(); });
            }
        });
    }

    private void startUpload() {
        if (busy || selectedUri == null || selectedName == null) return;
        ConnectionSettings settings = readSettings();
        if (settings == null) return;
        final Uri uri = selectedUri;
        final String fileName = selectedName;
        final long totalSize = selectedSize;
        cancelled.set(false);
        busy = true;
        transferring = true;
        resetProgress();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        updateButtons();
        setStatus("Conectando para enviar " + fileName + "…", false);

        worker.execute(() -> uploadFile(settings, uri, fileName, totalSize));
    }

    private void uploadFile(ConnectionSettings settings, Uri uri, String fileName, long totalSize) {
        FTPClient ftp = new FTPClient();
        String temporaryName = null;
        boolean complete = false;
        try {
            connect(ftp, settings);
            ensureRemoteDirectory(ftp, settings.directory);
            ensureNotExisting(ftp, fileName);
            temporaryName = fileName + ".subiendo-" + UUID.randomUUID().toString().substring(0, 8);
            checkCancelled();

            final String sendingName = fileName;
            runOnUiThread(() -> setStatus("Enviando " + sendingName + "…", false));
            long sent = 0;
            long lastUi = 0;
            long started = SystemClock.elapsedRealtime();

            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("Android no pudo abrir el archivo seleccionado");
                try (OutputStream output = ftp.storeFileStream(temporaryName)) {
                    if (output == null) throw new IOException("Servidor rechazó la transferencia: " + cleanReply(ftp));
                    byte[] buffer = new byte[256 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        checkCancelled();
                        output.write(buffer, 0, read);
                        sent += read;
                        long now = SystemClock.elapsedRealtime();
                        if (now - lastUi >= 250) {
                            showProgress(sent, totalSize, started);
                            lastUi = now;
                        }
                    }
                    checkCancelled();
                    output.flush();
                }
            }
            if (!ftp.completePendingCommand()) {
                throw new IOException("El servidor no confirmó el envío completo: " + cleanReply(ftp));
            }
            checkCancelled();
            if (!ftp.rename(temporaryName, fileName)) {
                throw new IOException("Los datos llegaron, pero el servidor no permitió renombrar el archivo. "
                        + "Revisa la carpeta FTP y elimina el temporal si aparece.");
            }
            complete = true;
            showProgress(totalSize > 0 ? totalSize : sent, totalSize, started);
            runOnUiThread(() -> {
                progressBar.setIndeterminate(false);
                progressBar.setProgress(100);
                progressText.setText("100% · Completado");
                setStatus("✓ PKG enviado a " + settings.directory + "/" + fileName
                        + ". Abre Package Installer en tu PS4.", false);
            });
        } catch (Exception e) {
            String error = cancelled.get() ? "Transferencia cancelada" : messageFor(e);
            runOnUiThread(() -> setStatus(error, !cancelled.get()));
        } finally {
            if (!complete && temporaryName != null && ftp.isConnected()) {
                // Una subida interrumpida no debe figurar como PKG instalable.
                // Si el servidor no permite borrar, puede quedar un .subiendo-* parcial.
                try { ftp.deleteFile(temporaryName); } catch (IOException ignored) { }
            }
            disconnectQuietly(ftp);
            runOnUiThread(() -> {
                busy = false;
                transferring = false;
                cancelled.set(false);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                updateButtons();
            });
        }
    }

    private static void connect(FTPClient ftp, ConnectionSettings settings) throws IOException {
        ftp.setConnectTimeout(12_000);
        ftp.setDefaultTimeout(30_000);
        ftp.setDataTimeout(Duration.ofSeconds(90));
        ftp.setBufferSize(256 * 1024);
        ftp.setControlEncoding("UTF-8");
        ftp.connect(settings.host, settings.port);
        ftp.setSoTimeout(90_000);
        if (!FTPReply.isPositiveCompletion(ftp.getReplyCode())) {
            throw new IOException("El servidor rechazó la conexión: " + cleanReply(ftp));
        }
        if (!ftp.login(settings.username, settings.password)) {
            throw new IOException("Usuario o contraseña FTP incorrectos: " + cleanReply(ftp));
        }
        ftp.enterLocalPassiveMode();
        if (!ftp.setFileType(FTP.BINARY_FILE_TYPE)) {
            throw new IOException("No se pudo activar el modo binario FTP");
        }
    }

    /** Recorre desde la raíz para funcionar con servidores que no aceptan MKD /data/pkg. */
    private static void ensureRemoteDirectory(FTPClient ftp, String directory) throws IOException {
        if (!ftp.changeWorkingDirectory("/")) throw new IOException("No hay acceso al directorio raíz FTP");
        for (String segment : directory.split("/")) {
            if (segment.isEmpty()) continue;
            if (!ftp.changeWorkingDirectory(segment)) {
                if (!ftp.makeDirectory(segment) || !ftp.changeWorkingDirectory(segment)) {
                    throw new IOException("No se puede abrir o crear la carpeta: " + directory);
                }
            }
        }
    }

    private static void ensureNotExisting(FTPClient ftp, String fileName) throws IOException {
        FTPFile[] listing = ftp.listFiles();
        if (listing == null) throw new IOException("No se pudo verificar si el PKG ya existe");
        for (FTPFile f : listing) {
            if (f.getName().equalsIgnoreCase(fileName)) {
                throw new IOException("Ya existe un PKG con el mismo nombre en la carpeta. "
                        + "Renómbralo desde un gestor FTP antes de volver a enviar.");
            }
        }
    }

    private void checkCancelled() throws IOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new IOException("Transferencia cancelada");
        }
    }

    private void showProgress(long sent, long total, long started) {
        long elapsed = Math.max(1, SystemClock.elapsedRealtime() - started);
        double mbps = sent / (1024.0 * 1024.0) / (elapsed / 1000.0);
        int percent = total > 0 ? (int) Math.min(100, sent * 100.0 / total) : -1;
        String sentText = formatSize(sent);
        runOnUiThread(() -> {
            progressBar.setIndeterminate(percent < 0);
            if (percent >= 0) {
                progressBar.setProgress(percent);
                progressText.setText(percent + "% · " + sentText + " / " + formatSize(total));
            } else {
                progressText.setText("Enviado: " + sentText);
            }
            speedText.setText(String.format(Locale.getDefault(), "%.2f MB/s", mbps));
        });
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
        return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static String cleanReply(FTPClient ftp) {
        String reply = ftp.getReplyString();
        return reply == null ? "sin detalles" : reply.trim();
    }

    private static String messageFor(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) return e.getClass().getSimpleName();
        if (message.length() > 240) return message.substring(0, 240) + "…";
        return message;
    }

    private static void disconnectQuietly(FTPClient ftp) {
        if (ftp == null || !ftp.isConnected()) return;
        try { ftp.logout(); } catch (IOException ignored) { }
        try { ftp.disconnect(); } catch (IOException ignored) { }
    }

    private void resetProgress() {
        progressBar.setIndeterminate(false);
        progressBar.setProgress(0);
        progressText.setText("0%");
        speedText.setText("0 MB/s");
    }

    private void setStatus(String message, boolean error) {
        statusText.setText(message);
        statusText.setTextColor(getColor(error ? R.color.red : R.color.primary));
    }

    private void updateButtons() {
        testButton.setEnabled(!busy);
        selectButton.setEnabled(!busy);
        uploadButton.setEnabled(!busy && selectedUri != null);
        cancelButton.setEnabled(busy && transferring && !cancelled.get());
        hostInput.setEnabled(!busy);
        portInput.setEnabled(!busy);
        dirInput.setEnabled(!busy);
        userInput.setEnabled(!busy);
        passwordInput.setEnabled(!busy);
    }

    @Override
    protected void onDestroy() {
        if (isFinishing()) {
            cancelled.set(true);
            worker.shutdownNow();
        }
        super.onDestroy();
    }

    private static final class ConnectionSettings {
        final String host;
        final int port;
        final String directory, username, password;
        ConnectionSettings(String host, int port, String directory, String username, String password) {
            this.host = host;
            this.port = port;
            this.directory = directory;
            this.username = username;
            this.password = password;
        }
    }
}
