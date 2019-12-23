package com.rnziparchive;

import android.content.res.AssetFileDescriptor;
import android.os.Build;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.util.Zip4jConstants;

import java.nio.charset.Charset;

public class RNZipArchiveModule extends ReactContextBaseJavaModule {
  private static final String TAG = RNZipArchiveModule.class.getSimpleName();

  private static final String PROGRESS_EVENT_NAME = "zipArchiveProgressEvent";
  private static final String EVENT_KEY_FILENAME = "filePath";
  private static final String EVENT_KEY_PROGRESS = "progress";

  public RNZipArchiveModule(ReactApplicationContext reactContext) {
    super(reactContext);
  }

  @Override
  public String getName() {
    return "RNZipArchive";
  }

  @ReactMethod
  public void isPasswordProtected(final String zipFilePath, final Promise promise) {
  }

  @ReactMethod
  public void unzipWithPassword(final String zipFilePath, final String destDirectory,
        final String password, final Promise promise) {
  }

  @ReactMethod
  public void unzip(final String zipFilePath, final String destDirectory, final String charset, final Promise promise) {
  }

  /**
   * Extract a zip held in the assets directory.
   * <p>
   * Note that the progress value isn't as accurate as when unzipping
   * from a file. When reading a zip from a stream, we can't
   * get accurate uncompressed sizes for files (ZipEntry#getCompressedSize() returns -1).
   * <p>
   * Instead, we compare the number of bytes extracted to the size of the compressed zip file.
   * In most cases this means the progress 'stays on' 100% for a little bit (compressedSize < uncompressed size)
   */
  @ReactMethod
  public void unzipAssets(final String assetsPath, final String destDirectory, final Promise promise) {
    new Thread(new Runnable() {
      @Override
      public void run() {
        InputStream assetsInputStream;
        final long size;

        try {
          assetsInputStream = getReactApplicationContext().getAssets().open(assetsPath);
          AssetFileDescriptor fileDescriptor = getReactApplicationContext().getAssets().openFd(assetsPath);
          size = fileDescriptor.getLength();
        } catch (IOException e) {
          promise.reject(null, String.format("Asset file `%s` could not be opened", assetsPath));
          return;
        }

        try {
          try {
            File destDir = new File(destDirectory);
            if (!destDir.exists()) {
              //noinspection ResultOfMethodCallIgnored
              destDir.mkdirs();
            }
            ZipInputStream zipIn = new ZipInputStream(assetsInputStream);
            BufferedInputStream bin = new BufferedInputStream(zipIn);

            ZipEntry entry;

            final long[] extractedBytes = {0};
            final int[] lastPercentage = {0};

            updateProgress(0, 1, assetsPath); // force 0%
            File fout;
            while ((entry = zipIn.getNextEntry()) != null) {
              if (entry.isDirectory()) continue;
              fout = new File(destDirectory, entry.getName());

              ensureZipPathSafety(fout, destDirectory);

              if (!fout.exists()) {
                //noinspection ResultOfMethodCallIgnored
                (new File(fout.getParent())).mkdirs();
              }

              final ZipEntry finalEntry = entry;
              StreamUtil.ProgressCallback cb = new StreamUtil.ProgressCallback() {
                @Override
                public void onCopyProgress(long bytesRead) {
                  extractedBytes[0] += bytesRead;

                  int lastTime = lastPercentage[0];
                  int percentDone = (int) ((double) extractedBytes[0] * 100 / (double) size);

                  // update at most once per percent.
                  if (percentDone > lastTime) {
                    lastPercentage[0] = percentDone;
                    updateProgress(extractedBytes[0], size, finalEntry.getName());
                  }
                }
              };

              FileOutputStream out = new FileOutputStream(fout);
              BufferedOutputStream Bout = new BufferedOutputStream(out);
              StreamUtil.copy(bin, Bout, cb);
              Bout.close();
              out.close();
            }

            updateProgress(1, 1, assetsPath); // force 100%
            bin.close();
            zipIn.close();
          } catch (Exception ex) {
            ex.printStackTrace();
            updateProgress(0, 1, assetsPath); // force 0%
            throw new Exception(String.format("Couldn't extract %s", assetsPath));
          }
        } catch (Exception ex) {
          promise.reject(null, ex.getMessage());
          return;
        }
        promise.resolve(destDirectory);
      }
    }).start();
  }

  @ReactMethod
  public void zip(String fileOrDirectory, String destDirectory, Promise promise) {
  }

  @ReactMethod
  public void zipWithPassword(String fileOrDirectory, String destDirectory, String password,
      String encryptionMethod, Promise promise) {
  }

  protected void updateProgress(long extractedBytes, long totalSize, String zipFilePath) {
    // Ensure progress can't overflow 1
    double progress = Math.min((double) extractedBytes / (double) totalSize, 1);
    Log.d(TAG, String.format("updateProgress: %.0f%%", progress * 100));

    WritableMap map = Arguments.createMap();
    map.putString(EVENT_KEY_FILENAME, zipFilePath);
    map.putDouble(EVENT_KEY_PROGRESS, progress);
    getReactApplicationContext().getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
            .emit(PROGRESS_EVENT_NAME, map);
  }

  /**
   * Return the uncompressed size of the ZipFile (only works for files on disk, not in assets)
   *
   * @return -1 on failure
   */
  private long getUncompressedSize(String zipFilePath, String charset) {
    long totalSize = 0;
    try {
      ZipFile zipFile = null;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        zipFile = new ZipFile(zipFilePath, Charset.forName(charset));
      } else {
        zipFile = new ZipFile(zipFilePath);
      }
      Enumeration<? extends ZipEntry> entries = zipFile.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        long size = entry.getSize();
        if (size != -1) {
          totalSize += size;
        }
      }
      zipFile.close();
    } catch (IOException ignored) {
      return -1;
    }
    return totalSize;
  }

  /**
   * Returns the exception stack trace as a string
   */
  private String getStackTrace(Exception e) {
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    e.printStackTrace(pw);
    return sw.toString();
  }

  private void ensureZipPathSafety(final File fout, final String destDirectory) throws Exception {
    String canonicalPath = fout.getCanonicalPath();
    if (!canonicalPath.startsWith(destDirectory)) {
      throw new SecurityException(String.format("Found Zip Path Traversal Vulnerability with %s", canonicalPath));
    }
  }

}
