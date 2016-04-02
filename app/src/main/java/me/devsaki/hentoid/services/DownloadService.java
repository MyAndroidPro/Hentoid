package me.devsaki.hentoid.services;

import android.app.IntentService;
import android.content.Intent;
import android.util.Log;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import me.devsaki.hentoid.HentoidApplication;
import me.devsaki.hentoid.database.HentoidDB;
import me.devsaki.hentoid.database.domains.Content;
import me.devsaki.hentoid.database.domains.ImageFile;
import me.devsaki.hentoid.enums.StatusContent;
import me.devsaki.hentoid.parsers.HitomiParser;
import me.devsaki.hentoid.parsers.NhentaiParser;
import me.devsaki.hentoid.parsers.TsuminoParser;
import me.devsaki.hentoid.util.AndroidHelper;
import me.devsaki.hentoid.util.Helper;
import me.devsaki.hentoid.util.HttpClientHelper;
import me.devsaki.hentoid.util.NetworkStatus;

/**
 * Download Manager implemented as a service
 */
public class DownloadService extends IntentService {
    public static final String INTENT_PERCENT_BROADCAST = "broadcast_percent";
    public static final String NOTIFICATION = "me.devsaki.hentoid.services";

    private static final String TAG = DownloadService.class.getName();

    public static boolean paused;
    private NotificationPresenter notificationPresenter;
    private HentoidDB db;
    private Content currentContent;

    public DownloadService() {
        super(DownloadService.class.getName());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        db = new HentoidDB(this);
        notificationPresenter = new NotificationPresenter();

        Log.i(TAG, "Download service created");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Log.i(TAG, "Download service destroyed");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (!NetworkStatus.isOnline(this)) {
            Log.e(TAG, "No connection");
            return;
        }

        currentContent = db.selectContentByStatus(StatusContent.DOWNLOADING);
        if (currentContent != null && currentContent.getStatus() != StatusContent.DOWNLOADED) {

            notificationPresenter.downloadStarted(currentContent);
            if (paused) {
                interruptDownload();
                return;
            }
            try {
                parseImageFiles();
            } catch (Exception e) {
                currentContent.setStatus(StatusContent.UNHANDLED_ERROR);
                notificationPresenter.updateNotification(0);
                currentContent.setStatus(StatusContent.PAUSED);
                db.updateContentStatus(currentContent);
                updateActivity(-1);

                return;
            }

            if (paused) {
                interruptDownload();
                return;
            }

            Log.i(TAG, "Content download started: " + currentContent.getTitle());

            // Tracking Event (Download Added)
            HentoidApplication.getInstance().trackEvent("Download Service", "Download",
                    "Download Content: Start.");

            // Initialize
            File dir = AndroidHelper.getDownloadDir(currentContent, this);
            ImageDownloadBatch downloadBatch = new ImageDownloadBatch();

            //add download tasks
            downloadBatch.newTask(dir, "thumb", currentContent.getCoverImageUrl());
            for (ImageFile imageFile : currentContent.getImageFiles()) {
                downloadBatch.newTask(dir, imageFile.getName(), imageFile.getUrl());
            }

            //Track and wait for download to complete
            final int qtyPages = currentContent.getQtyPages();
            for (int i = 0; i <= qtyPages; ++i) {

                if (paused) {
                    interruptDownload();
                    downloadBatch.cancelAllTasks();
                    if (currentContent.getStatus() == StatusContent.SAVED) {
                        try {
                            FileUtils.deleteDirectory(dir);
                        } catch (IOException e) {
                            Log.e(TAG, "error deleting content directory", e);
                        }
                    }

                    return;
                }

                downloadBatch.waitForOneCompletedTask();
                double percent = i * 100.0 / qtyPages;
                notificationPresenter.updateNotification(percent);
                updateActivity(percent);
            }

            //assign status tag to ImageFile(s)
            short errorCount = downloadBatch.getErrorCount();
            for (ImageFile imageFile : currentContent.getImageFiles()) {
                if (errorCount > 0) {
                    imageFile.setStatus(StatusContent.ERROR);
                    errorCount--;
                } else {
                    imageFile.setStatus(StatusContent.DOWNLOADED);
                }
                db.updateImageFileStatus(imageFile);
            }

            //assign status tag to Content, add timestamp, and save to db
            if (downloadBatch.hasError()) {
                currentContent.setStatus(StatusContent.ERROR);
            } else {
                currentContent.setStatus(StatusContent.DOWNLOADED);
            }
            currentContent.setDownloadDate(new Date().getTime());
            db.updateContentStatus(currentContent);

            // Save JSON file
            try {
                Helper.saveJson(currentContent, dir);
            } catch (IOException e) {
                Log.e(TAG, "Error Save JSON " + currentContent.getTitle(), e);
            }

            notificationPresenter.updateNotification(0);
            updateActivity(-1);
            Log.i(TAG, "Content download finished: " + currentContent.getTitle());

            //Search for queued content download tasks and fire intent
            currentContent = db.selectContentByStatus(StatusContent.DOWNLOADING);
            if (currentContent != null) {
                Intent intentService = new Intent(Intent.ACTION_SYNC, null, this,
                        DownloadService.class);
                intentService.putExtra("content_id", currentContent.getId());
                startService(intentService);
            }
        }
    }

    private void interruptDownload() {
        paused = false;
        currentContent = db.selectContentById(currentContent.getId());
        notificationPresenter.downloadInterrupted(currentContent);
    }

    private void updateActivity(double percent) {
        Intent intent = new Intent(NOTIFICATION);
        intent.putExtra(INTENT_PERCENT_BROADCAST, percent);
        sendBroadcast(intent);
    }

    private void parseImageFiles() throws Exception {
        List<String> aUrls = new ArrayList<>();
        try {
            switch (currentContent.getSite()) {
                case HITOMI:
                    String html = HttpClientHelper.call(currentContent.getReaderUrl());
                    aUrls = HitomiParser.parseImageList(html);
                    break;
                case NHENTAI:
                    String json = HttpClientHelper.call(currentContent.getGalleryUrl() + "/json");
                    aUrls = NhentaiParser.parseImageList(json);
                    break;
                case TSUMINO:
                    aUrls = TsuminoParser.parseImageList(currentContent);
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting image urls", e);
            throw e;
        }

        int i = 1;
        List<ImageFile> imageFileList = new ArrayList<>();
        for (String str : aUrls) {
            String name = String.format(Locale.US, "%03d", i);
            imageFileList.add(new ImageFile()
                    .setUrl(str)
                    .setOrder(i++)
                    .setStatus(StatusContent.SAVED)
                    .setName(name));
        }
        currentContent.setImageFiles(imageFileList);
        db.insertImageFiles(currentContent);
    }
}