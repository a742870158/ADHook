package com.adhook.ui;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;
import java.io.File;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

/** Exercises the unchanged Android 35 export order through the real public production entry. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class ImportExportMinSdkTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Context context;
    private File privateDir;
    private MediaProvider provider;
    public static class MediaProvider extends ContentProvider {
        File row;
        ContentValues inserted;
        boolean failInsert;
        int deleted;
        @Override public boolean onCreate() { return true; }
        @Override public Uri insert(Uri uri, ContentValues values) {
            if (failInsert) return null;
            inserted = new ContentValues(values); return Uri.parse("content://media/external/downloads/42");
        }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            return ParcelFileDescriptor.open(row, ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
        }
        @Override public int delete(Uri uri, String selection, String[] args) { deleted++; row.delete(); return 1; }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
        @Override public String getType(Uri uri) { return "application/json"; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    }
    @Before public void setUp() throws Exception {
        Context app = RuntimeEnvironment.getApplication(); privateDir = temporary.newFolder("private-export");
        context = new ContextWrapper(app) { @Override public File getExternalFilesDir(String type) { return privateDir; } };
        provider = new MediaProvider(); provider.row = temporary.newFile("media-row.json");
        ProviderInfo info = new ProviderInfo(); info.authority = "media"; provider.attachInfo(app, info);
        ShadowContentResolver.registerProviderInternal("media", provider);
        // Reset only this fixture URI; a prior write-failure stream must not affect another test lifetime.
        Shadows.shadowOf(context.getContentResolver()).registerOutputStreamSupplier(
                Uri.parse("content://media/external/downloads/42"), () -> null);
    }
    private String export() { return ImportExportHelper.exportToJsonFile(context, "adhook_fixture", "{\"规则\":true}"); }
    @Test public void mediaStoreRemainsFirstChoiceAndWritesExactUtf8Content() throws Exception {
        String result = export(); assertNotNull(result); assertTrue(result.startsWith("Download/ADHook/"));
        assertEquals("application/json", provider.inserted.getAsString(MediaStore.Downloads.MIME_TYPE));
        assertEquals("Download/ADHook", provider.inserted.getAsString(MediaStore.Downloads.RELATIVE_PATH));
        assertEquals("{\"规则\":true}", new String(Files.readAllBytes(provider.row.toPath()), StandardCharsets.UTF_8));
        assertEquals(0, privateDir.list().length); assertEquals(0, provider.deleted);
    }
    @Test public void missingMediaStoreInsertStillFallsBackToPrivateExport() throws Exception {
        provider.failInsert = true;
        String result = export(); assertNotNull(result); assertTrue(new File(result).getCanonicalPath().startsWith(privateDir.getCanonicalPath() + File.separator));
        assertEquals("{\"规则\":true}", new String(Files.readAllBytes(new File(result).toPath()), StandardCharsets.UTF_8));
        assertEquals(0, provider.deleted);
    }
    @Test public void mediaStoreWriteFailureCleansItsRowThenUsesTheSamePrivateFallback() throws Exception {
        java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        // Robolectric's one-argument openOutputStream swallows FileNotFoundException from openFile.
        // A registered stream instead faults at the real production os.write, after successful insertion.
        Shadows.shadowOf(context.getContentResolver()).registerOutputStream(
                Uri.parse("content://media/external/downloads/42"), new java.io.OutputStream() {
                    @Override public void write(int value) throws java.io.IOException {
                        writes.incrementAndGet(); throw new java.io.IOException("fixture write failed");
                    }
                    @Override public void close() { closed.set(true); }
                });
        String result = export(); assertNotNull(result); assertEquals(1, provider.deleted); assertFalse(provider.row.exists());
        assertTrue("Failure seam must actually be written", writes.get() > 0); assertTrue("Failed output must close", closed.get());
        assertEquals("{\"规则\":true}", new String(Files.readAllBytes(new File(result).toPath()), StandardCharsets.UTF_8));
    }
}
